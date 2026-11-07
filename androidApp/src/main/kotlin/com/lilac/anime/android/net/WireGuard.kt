package com.lilac.anime.android.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.SecureKeys
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * 설정 > 연결 (WireGuard), desktop wireguard-proxy.cjs: this app's traffic alone goes through a WireGuard tunnel.
 * Desktop runs wireproxy as an HTTP proxy; here Android's VPN service carries it (wireguard-android's GoBackend,
 * Apache-2.0) with the tunnel limited to this app (IncludedApplications), so other apps are untouched. Addresses of
 * the local network (Chromecast, the LAN relay) stay direct, as desktop keeps LAN addresses off the proxy. While it
 * is up and the server does not answer, this app's traffic waits in the tunnel instead of leaving outside it.
 * The .conf holds a private key: it is kept encrypted (SecureKeys).
 */
object WireGuard {
    private const val CONF = "wireguard.conf"
    private lateinit var context: Context
    private val prefs by lazy { context.getSharedPreferences("lilac", Context.MODE_PRIVATE) }
    private val backend by lazy { GoBackend(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val tunnel = object : Tunnel {
        override fun getName() = "lilac"
        override fun onStateChange(newState: Tunnel.State) { up = newState == Tunnel.State.UP }
    }

    var enabled by mutableStateOf(false); private set
    var up by mutableStateOf(false); private set
    var state by mutableStateOf("꺼져 있어요."); private set
    /** What the saved file is: its name, server and peer count. */
    var file by mutableStateOf<String?>(null); private set
    private var endpoint = ""

    fun init(context: Context) {
        this.context = context.applicationContext
        enabled = prefs.getBoolean("wireguard.enabled", false)
        file = prefs.getString("wireguard.file", null)
        endpoint = prefs.getString("wireguard.endpoint", "").orEmpty()
    }

    /** As the app opens (from the activity: a VPN service is started from the foreground). */
    fun start() { if (enabled) scope.launch { apply() } }

    /** The system's consent screen when this app may not open a VPN yet. */
    fun permissionIntent(): Intent? = VpnService.prepare(context)

    suspend fun changeEnabled(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("wireguard.enabled", on).apply()
        apply()
    }

    /** A picked .conf (or .txt): checked, kept encrypted, and applied when the switch is on. */
    suspend fun load(uri: Uri) {
        val (name, bytes) = withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("설정 파일을 읽지 못했어요.")
            require(bytes.size < 64 * 1024) { "WireGuard 설정 파일이 아닙니다. 파일이 너무 커요." }
            name to bytes
        }
        val sanitized = WireGuardConf.sanitize(WireGuardConf.decode(bytes))
        // Read as the backend will read it, so a file it refuses is refused here.
        try { config(sanitized.text) } catch (error: Exception) { throw IllegalArgumentException("설정 파일을 읽지 못했어요: " + (error.message ?: error.javaClass.simpleName)) }
        withContext(Dispatchers.IO) { SecureKeys.save(sanitized.text, CONF) }
        endpoint = sanitized.endpoint
        file = listOf(name.ifBlank { "wireguard.conf" }, sanitized.endpoint, "서버 ${sanitized.peers}개").joinToString(" · ") +
            (if (sanitized.dropped.isEmpty()) "" else "\n이 앱에서 쓰지 않는 항목은 뺐어요: " + sanitized.dropped.joinToString(", "))
        prefs.edit().putString("wireguard.file", file).putString("wireguard.endpoint", endpoint).apply()
        if (enabled) apply()
    }

    suspend fun remove() {
        withContext(Dispatchers.IO) { SecureKeys.save("", CONF) }
        file = null; endpoint = ""
        prefs.edit().remove("wireguard.file").remove("wireguard.endpoint").apply()
        changeEnabled(false)
    }

    /** The tunnel as the switch and the file say. */
    private suspend fun apply() = lock.withLock {
        withContext(Dispatchers.IO) {
            val conf = SecureKeys.load(CONF)
            if (!enabled || conf.isEmpty()) {
                runCatching { if (backend.getState(tunnel) == Tunnel.State.UP) backend.setState(tunnel, Tunnel.State.DOWN, null) }
                up = false
                state = if (enabled) "설정 파일을 먼저 불러오세요." else "꺼져 있어요."
                return@withContext
            }
            if (VpnService.prepare(context) != null) { up = false; state = "VPN 연결을 허용해야 켤 수 있어요. 스위치를 다시 켜 주세요."; return@withContext }
            state = "연결하는 중…"
            try {
                backend.setState(tunnel, Tunnel.State.UP, config(conf))
                state = "켜져 있어요 · $endpoint"
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                up = false
                state = "연결하지 못했어요: " + (error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** How long ago the server last answered, for the settings page. */
    fun refresh() {
        if (!enabled || !up) return
        val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull() ?: return
        val last = stats.peers().mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis() }.maxOrNull() ?: 0L
        state = if (last <= 0L) "VPN 서버의 응답을 기다리는 중… · $endpoint"
        else "연결됨 · $endpoint · 최근 응답 ${((System.currentTimeMillis() - last) / 1000).coerceAtLeast(0)}초 전"
    }

    private fun config(text: String): Config =
        Config.parse(BufferedReader(StringReader(WireGuardConf.forThisApp(text, context.packageName))))
}

/** desktop sanitizeConf / decodeConfFile, and the tunnel limited to this app with the local network left direct. */
object WireGuardConf {
    data class Sanitized(val text: String, val peers: Int, val endpoint: String, val dropped: List<String>)

    private val interfaceKeys = listOf("PrivateKey", "Address", "DNS", "MTU", "ListenPort")
    private val peerKeys = listOf("PublicKey", "PresharedKey", "Endpoint", "AllowedIPs", "PersistentKeepalive")

    /** A .conf saved again in Windows Notepad as "Unicode" is UTF-16 with its byte order mark. */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }

    private class Section(val kind: String) { val keys = mutableListOf<Pair<String, String>>() }

    /** Its [Interface] and [Peer] sections with their known keys; the others (PostUp, Table, …) left out. */
    fun sanitize(text: String): Sanitized {
        val sections = mutableListOf<Section>(); val dropped = linkedSetOf<String>()
        var current: Section? = null
        for (raw in text.removePrefix("﻿").split(Regex("\\r?\\n"))) {
            val line = raw.replace(Regex("[#;].*$"), "").trim()
            if (line.isEmpty()) continue
            val header = Regex("^\\[\\s*([^\\]]+?)\\s*]$").find(line)
            if (header != null) {
                val name = header.groupValues[1]
                val section = when { name.equals("interface", true) -> Section("Interface"); name.equals("peer", true) -> Section("Peer"); else -> null }
                if (section != null) sections += section else dropped += "[$name]"
                current = section
                continue
            }
            val pair = Regex("^([A-Za-z]+)\\s*=\\s*(.+)$").find(line) ?: continue
            val section = current ?: continue
            val known = (if (section.kind == "Interface") interfaceKeys else peerKeys).firstOrNull { it.equals(pair.groupValues[1], true) }
            if (known != null) section.keys += known to pair.groupValues[2].trim() else dropped += pair.groupValues[1]
        }
        fun Section.value(key: String) = keys.firstOrNull { it.first == key }?.second.orEmpty()
        val interfaces = sections.filter { it.kind == "Interface" }; val peers = sections.filter { it.kind == "Peer" }
        require(interfaces.size == 1) { "WireGuard 설정 파일이 아닙니다. [Interface] 부분이 하나 있어야 해요." }
        require(interfaces[0].value("PrivateKey").isNotEmpty() && interfaces[0].value("Address").isNotEmpty()) { "설정 파일의 [Interface]에 PrivateKey와 Address가 있어야 해요." }
        require(peers.isNotEmpty()) { "설정 파일에 [Peer]가 없어요." }
        for (peer in peers) {
            require(peer.value("PublicKey").isNotEmpty() && peer.value("Endpoint").isNotEmpty()) { "설정 파일의 [Peer]에 PublicKey와 Endpoint가 있어야 해요." }
            // One peer takes all traffic when the file does not say which.
            if (peer.value("AllowedIPs").isEmpty() && peers.size == 1) peer.keys += "AllowedIPs" to "0.0.0.0/0, ::/0"
        }
        val out = sections.joinToString("\n\n") { section -> "[${section.kind}]\n" + section.keys.joinToString("\n") { "${it.first} = ${it.second}" } }
        return Sanitized("$out\n", peers.size, peers[0].value("Endpoint"), dropped.toList())
    }

    /** IPv4 without 10/8, 172.16/12 and 192.168/16 (the WireGuard app's "exclude private IPs" routes). */
    private val publicV4 = listOf("0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2",
        "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9",
        "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16",
        "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6",
        "200.0.0.0/5", "208.0.0.0/4")

    /** The tunnel for this app alone; a peer taking all IPv4 leaves the local network out, its DNS servers kept in. */
    fun forThisApp(text: String, packageName: String): String {
        val dns = Regex("^DNS\\s*=\\s*(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1).orEmpty()
            .split(',').map { it.trim() }.filter { Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(it) }.map { "$it/32" }
        return text.lines().joinToString("\n") { line ->
            val allowed = Regex("^AllowedIPs\\s*=\\s*(.+)$").find(line)
            when {
                line == "[Interface]" -> "$line\nIncludedApplications = $packageName"
                allowed != null -> {
                    val ranges = allowed.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val wide = ranges.flatMap { if (it == "0.0.0.0/0") publicV4 + dns else listOf(it) }.distinct()
                    "AllowedIPs = " + wide.joinToString(", ")
                }
                else -> line
            }
        }
    }
}
