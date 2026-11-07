package com.lilac.anime.android.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.R
import com.lilac.anime.android.ui.*
import kotlinx.coroutines.*

/** Keeps the app running while episodes download (iOS hands this to a background URLSession). */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel("downloads") == null)
            manager.createNotificationChannel(NotificationChannel("downloads", "다운로드", NotificationManager.IMPORTANCE_LOW))
        runCatching {
            ServiceCompat.startForeground(this, 2, notification(), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        scope.coroutineContext.cancelChildren()
        scope.launch { while (isActive) { delay(2000); manager.notify(2, notification()) } }
        return START_NOT_STICKY
    }
    private fun notification() = NotificationCompat.Builder(this, "downloads").setSmallIcon(R.mipmap.ic_launcher).setSilent(true).setOngoing(true)
        .setContentTitle("다운로드 중").setContentText(DownloadStore.entries.filter { it.status == "다운로드 중" }.let { running ->
            if (running.isEmpty()) "영상 준비 중 · ${DownloadStore.pendingResolution}개" else running.joinToString(" · ") { "${it.title} ${it.completed}/${it.total}" }
        }).build()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun start(context: Context) { runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) } }
        fun stop(context: Context) { context.stopService(Intent(context, DownloadService::class.java)) }
    }
}

/** .download-progress: a thin bar, lilac as far as it got. */
@Composable
fun DownloadBar(progress: Double, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(LilacStyle.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.0, 1.0).toFloat()).background(LilacStyle.accent))
    }
}
@Composable
private fun ActionButton(text: String, icon: ImageVector?, tint: Color? = null, action: () -> Unit) {
    Row(Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp)).background(LilacStyle.card2).border(1.dp, LilacStyle.line, RoundedCornerShape(10.dp))
        .clickable(onClick = action).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = tint ?: LilacStyle.text, modifier = Modifier.size(15.dp)); if (text.isNotEmpty()) Spacer(Modifier.width(5.dp)) }
        if (text.isNotEmpty()) Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint ?: LilacStyle.text, maxLines = 1)
    }
}
@Composable
private fun Modifier.downloadCard() = this.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(12.dp)).border(1.dp, LilacStyle.line, RoundedCornerShape(12.dp)).padding(10.dp)

/** 내 목록 > 다운로드 완료 (iOS DownloadsView): series cards with their episodes, the queue, and the tools. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsView() {
    val context = LocalContext.current
    val navigation = LocalApp.current.navigation
    val danger = Color(0xFFD86B75)
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val entries = DownloadStore.entries
    val transferScope = rememberCoroutineScope()
    var transfer by remember { mutableStateOf<String?>(null) }
    // 다운로드 폴더 내보내기 · 가져오기 (iOS DownloadTransferView): a folder picked on the device.
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        transferScope.launch {
            transfer = runCatching { DownloadTransfer.export(context, DownloadStore.entries.filter { it.localFile != null }, uri) { transfer = it } }
                .fold({ "$it 폴더로 내보냈습니다." }, { it.message })
        }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        transferScope.launch {
            transfer = runCatching { DownloadTransfer.import(context, uri, DownloadStore.entries.map { it.id }.toSet()) { transfer = it } }
                .fold({ DownloadStore.importCompleted(it); "${it.size}개 회차를 가져왔습니다." }, { it.message })
        }
    }
    val groups = entries.groupBy { it.anime.id }.toList().sortedByDescending { (_, list) -> list.maxOf { it.date } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val saved = entries.filter { it.localFile != null }
        val bytes = saved.sumOf { it.bytes }
        Text("저장한 회차 ${saved.size}개" + if (bytes > 0) " · " + Formatter.formatShortFileSize(context, bytes) else "", fontSize = 13.sp, color = LilacStyle.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LilacOutlineButton("모두 일시 중지", icon = Icons.Filled.Pause) { DownloadStore.pauseAll() }
            LilacOutlineButton("모두 이어받기", icon = Icons.Filled.Refresh) { DownloadStore.resumeAll() }
            if (LocalApp.current.library.preferences.translateDownloads) {
                LilacOutlineButton("저장한 회차 자막 번역", enabled = saved.isNotEmpty() && DownloadStore.translatingTitle == null) { DownloadStore.translateSaved() }
            }
            LilacOutlineButton("폴더로 내보내기", icon = Icons.Filled.Folder) { exporter.launch(null) }
            LilacOutlineButton("폴더에서 가져오기", icon = Icons.Filled.Folder) { importer.launch(null) }
            LilacOutlineButton("다운로드 전체 삭제", icon = Icons.Filled.Delete, tint = danger) { confirmClear = true }
        }
        transfer?.let { Text(it, fontSize = 12.sp, color = LilacStyle.muted) }
        DownloadStore.translatingTitle?.let { LilacProgress("$it 자막 번역 중") }
        if (DownloadStore.pendingResolution > 0) Column(Modifier.downloadCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacProgress("영상 준비 · ${DownloadStore.pendingResolution}개 남음")
            Text(DownloadStore.resolvingTitle, fontSize = 12.sp, color = LilacStyle.muted)
            LilacOutlineButton("준비 중단") { DownloadStore.stopResolving() }
        }
        if (entries.isEmpty()) LilacEmptyState(Icons.Filled.Download, "다운로드한 회차가 없어요", "작품 상세 화면의 저장 버튼으로 오프라인 회차를 추가하세요.")
        groups.forEach { (key, unsorted) ->
            val jobs = unsorted.sortedBy { it.number }
            val done = jobs.count { it.localFile != null }
            val active = jobs.count { it.localFile == null && it.status in listOf("대기", "준비 중", "다운로드 중") }
            val stopped = jobs.count { it.localFile == null && it.status in listOf("중단됨", "실패") }
            val summary = listOf(DownloadStore.episodeRanges(jobs.map { it.number }) + "화", if (done > 0) "${done}개 저장됨" else "", if (active > 0) "${active}개 받는 중" else "",
                if (stopped > 0) "${stopped}개 멈춤" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            val progress = jobs.sumOf { if (it.localFile != null) 1.0 else if (it.total > 0) it.completed.toDouble() / it.total else 0.0 } / jobs.size
            val open = key !in collapsed
            val cover = if (LilacStyle.wide) 112.dp else 80.dp
            Column(Modifier.downloadCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimeArtwork(jobs.first().anime.poster, Modifier.size(cover, cover * 66 / 112).clip(RoundedCornerShape(9.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(com.lilac.anime.android.data.displayTitle(jobs.first().anime.anime, jobs.first().anime.source) + "  ${jobs.size}개 회차",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(summary, fontSize = 12.sp, color = LilacStyle.muted, maxLines = 2)
                        DownloadBar(progress, Modifier.padding(top = 5.dp))
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, if (open) "회차 목록 접기" else "회차 목록 펼치기", tint = LilacStyle.muted,
                        modifier = Modifier.size(36.dp).rotate(if (open) 180f else 0f).clickable { collapsed = if (open) collapsed + key else collapsed - key }.padding(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (active > 0) ActionButton("전체 중지", Icons.Filled.Close) { DownloadStore.stopGroup(key) }
                    if (stopped > 0) ActionButton("이어 받기", Icons.Filled.Refresh) { DownloadStore.resumeGroup(key) }
                    ActionButton("전체 삭제", Icons.Filled.Delete, danger) { removing = key }
                }
            }
            if (open) jobs.forEach { entry ->
                val running = entry.status == "다운로드 중"
                val detail = listOf(entry.status, if (entry.bytes > 0) Formatter.formatShortFileSize(context, entry.bytes) else "",
                    if (running) DownloadStore.transferRate[entry.id]?.let { Formatter.formatShortFileSize(context, it.toLong()) + "/s" } ?: "" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                Column(Modifier.padding(start = if (LilacStyle.wide) 28.dp else 14.dp).downloadCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title.ifEmpty { "${entry.number}화" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 2)
                            Text(detail, fontSize = 12.sp, color = if (entry.localFile != null) LilacStyle.accent else LilacStyle.muted, maxLines = 1)
                        }
                        if (entry.localFile != null) ActionButton("재생", Icons.Filled.PlayArrow) { navigation.play(DownloadStore.playback(entry)) }
                        if (running) ActionButton("중단", Icons.Filled.Close) { DownloadStore.cancel(entry.id) }
                        else if (entry.localFile == null) ActionButton("다시 시도", Icons.Filled.Refresh) { DownloadStore.retry(entry) }
                        ActionButton("", Icons.Filled.Delete, danger) { DownloadStore.delete(entry.id) }
                    }
                    if (entry.total > 0 && running) DownloadBar(entry.completed.toDouble() / maxOf(entry.total, 1))
                    entry.error?.let { Text(it, fontSize = 12.sp, color = Color.Red) }
                }
            }
        }
        val scope = rememberCoroutineScope()
        var analyzing by remember { mutableStateOf(false) }
        var analysisStatus by remember { mutableStateOf<String?>(null) }
        Column(Modifier.downloadCard().padding(top = 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OP/ED 분석", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text)
            Text("받은 회차의 오디오를 서로 비교해 오프닝·엔딩 구간을 찾아 둡니다.", fontSize = 12.5.sp, color = LilacStyle.muted)
            analysisStatus?.let { Text(it, fontSize = 12.5.sp, color = LilacStyle.text) }
            LilacOutlineButton(if (analyzing) "분석하는 중…" else "다운로드 회차 OP/ED 분석", enabled = !analyzing && entries.any { it.localFile != null }) {
                analyzing = true
                scope.launch {
                    analysisStatus = runCatching { com.lilac.anime.android.audio.OfflineAnalyzer.analyze(context, entries.filter { it.localFile != null }) { analysisStatus = it } }
                        .fold({ "${it}개 회차 분석 완료" }, { it.message })
                    analyzing = false
                }
            }
        }
        DownloadStore.error?.let { Text(it, fontSize = 13.sp, color = Color.Red) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, containerColor = LilacStyle.card,
        title = { Text("다운로드한 영상·자막과 대기 목록을 모두 삭제할까요?", color = LilacStyle.text, fontSize = 16.sp) },
        confirmButton = { TextButton({ DownloadStore.clearAll(); confirmClear = false }) { Text("전체 삭제", color = Color.Red) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("취소", color = LilacStyle.accent) } })
    removing?.let { key ->
        val list = DownloadStore.group(key)
        AlertDialog(onDismissRequest = { removing = null }, containerColor = LilacStyle.card,
            title = { Text((list.firstOrNull()?.anime?.title ?: "") + "의 다운로드 ${list.size}개를 모두 삭제할까요? 저장한 영상과 자막도 함께 삭제됩니다.", color = LilacStyle.text, fontSize = 16.sp) },
            confirmButton = { TextButton({ DownloadStore.removeGroup(key); removing = null }) { Text("전체 삭제", color = Color.Red) } },
            dismissButton = { TextButton({ removing = null }) { Text("취소", color = LilacStyle.accent) } })
    }
}
