package com.lilac.anime.android.subtitle

import android.content.Context
import com.lilac.anime.android.player.HlsData
import com.lilac.anime.android.player.PlaybackFailure
import com.lilac.anime.android.player.await
import com.lilac.anime.android.player.shortKey
import com.lilac.anime.shared.SubtitleTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.security.MessageDigest

/** iOS SubtitleFiles: subtitles downloaded, unpacked and read into files mpv plays, with the fonts they bring. */
object SubtitleFiles {
    lateinit var root: File; private set
    val fontDirectory: File get() = File(root, "Fonts")
    val translations: File get() = File(root, "Translations")
    fun init(context: Context) { root = File(context.filesDir, "Subtitles").apply { mkdirs() }; fontDirectory.mkdirs() }

    fun key(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private val subtitleExtensions = listOf("ass", "ssa", "srt", "vtt", "smi")
    private val fontExtensions = listOf("ttf", "otf", "ttc")

    /** `label`: the link's text, used as the file's name when the download's own name says nothing (Tistory's tfile.smi). */
    suspend fun prepare(url: String, headers: Map<String, String> = emptyMap(), japanese: Boolean = false, filename: String? = null, label: String? = null): List<File> = withContext(Dispatchers.IO) {
        var name = filename ?: url.substringBefore('?').substringAfterLast('/').let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val data: ByteArray
        if (url.startsWith("file:") || url.startsWith("/")) {
            data = File(url.removePrefix("file://").removePrefix("file:")).readBytes()
        } else {
            var response = download(url, headers)
            // Google Drive answers a big file with a confirmation page first.
            repeat(2) {
                if (response.type != "text/html") return@repeat
                val confirmed = SubtitleTools.driveConfirmation(response.data.toString(Charsets.UTF_8), response.url)
                if (confirmed.isEmpty()) return@repeat
                response = download(confirmed, emptyMap())
            }
            if (response.type == "text/html") throw PlaybackFailure("자막 파일 대신 웹 페이지가 반환되었습니다. 원본 게시물에서 파일을 내려받아 가져오세요.")
            data = response.data
            name = filename ?: SubtitleNames.downloadFilename(response.disposition, name)
            if (filename == null) labelName(label)?.let { named -> if (!isArchive(data) && labelReplaces(name)) name = named }
        }
        name = SubtitleNames.readable(name)
        val folder = File(root, key(url)).apply { mkdirs() }
        val ext = name.substringAfterLast('.', "").lowercase()
        val safe = name.replace("\\", "/").substringAfterLast('/')
        val file = File(folder, (if (safe.isEmpty() || safe == "." || safe == "..") "source" else safe) + if (ext.isEmpty()) ".ass" else "")
        file.writeBytes(data)
        if (ext in fontExtensions) { importFont(file); associateFonts(listOf(file), File(folder, "asset")); return@withContext emptyList() }
        if (ext in listOf("zip", "7z", "rar") || isArchive(data)) {
            val results = mutableListOf<File>(); val fonts = mutableListOf<File>()
            for (extracted in SubtitleArchive.extract(file, folder)) {
                if (extracted.extension.lowercase() in fontExtensions) { importFont(extracted); fonts += extracted }
                else results += normalize(extracted, japanese)
            }
            if (results.isEmpty() && fonts.isEmpty()) throw PlaybackFailure("압축 파일에 지원하는 자막이 없습니다.")
            associateFonts(fonts, File(folder, "asset"))
            results.forEach { associateFonts(fonts, it) }
            return@withContext results
        }
        listOf(normalize(file, japanese))
    }

    private class Downloaded(val data: ByteArray, val type: String?, val disposition: String?, val url: String)
    private suspend fun download(url: String, headers: Map<String, String>): Downloaded {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return HlsData.client.newCall(request).await { response ->
            if (response.code !in 200..299) throw PlaybackFailure("자막 다운로드가 실패했습니다.")
            Downloaded(response.body?.bytes() ?: ByteArray(0), response.header("Content-Type")?.substringBefore(";")?.trim(), response.header("Content-Disposition"), response.request.url.toString())
        }
    }

    /** A link text that is a subtitle file name, its size ("0.02MB") left out. */
    fun labelName(label: String?): String? {
        val name = label?.replace(Regex("\\s*\\d+(?:\\.\\d+)?\\s*[KMG]B\\s*$", RegexOption.IGNORE_CASE), "")?.trim() ?: return null
        return name.takeIf { it.substringAfterLast('.', "").lowercase() in subtitleExtensions }
    }
    fun labelReplaces(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in fontExtensions) return false
        return ext !in subtitleExtensions || Regex("^t?file\\.", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
    private fun isArchive(data: ByteArray) = startsWith(data, 0x50, 0x4b, 0x03, 0x04) || startsWith(data, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) || startsWith(data, 0x52, 0x61, 0x72, 0x21)
    private fun startsWith(data: ByteArray, vararg bytes: Int) = data.size >= bytes.size && bytes.indices.all { data[it] == bytes[it].toByte() }

    suspend fun isKorean(file: File): Boolean = withContext(Dispatchers.IO) {
        val content = runCatching { text(file) }.getOrNull() ?: return@withContext false
        val lines = SubtitleTools.lines(content, file.extension).take(40)
        lines.isNotEmpty() && lines.count { line -> line.any { it in '\uac00'..'\ud7a3' } }.toDouble() / lines.size > 0.4
    }

    fun text(file: File, japanese: Boolean = false): String {
        val data = file.readBytes()
        if (startsWith(data, 0xff, 0xfe)) return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        if (startsWith(data, 0xfe, 0xff)) return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        decodeStrict(data, Charsets.UTF_8)?.let { return it.removePrefix("\uFEFF") }
        val legacy = if (japanese) listOf("Shift_JIS") else listOf("x-windows-949", "EUC-KR", "Shift_JIS", "EUC-JP")
        for (name in legacy) decodeStrict(data, runCatching { Charset.forName(name) }.getOrNull() ?: continue)?.takeIf { it.isNotEmpty() }?.let { return it }
        throw PlaybackFailure("자막 문자 인코딩을 읽을 수 없습니다.")
    }
    private fun decodeStrict(data: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data)).toString()
    }.getOrNull()

    private fun normalize(file: File, japanese: Boolean): File {
        val content = text(file, japanese)
        val ext = SubtitleTools.format(content, file.extension)
        if (ext.isEmpty()) throw PlaybackFailure("지원하는 자막 형식이 아닙니다.")
        val output = File(file.parentFile, file.nameWithoutExtension + "." + ext)
        output.writeText(playable(content))
        if (output != file && file.extension.lowercase() !in listOf("zip", "7z", "rar")) Unit
        return output
    }
    private val nbsp = Regex("&(?:amp;)?nbsp;?", RegexOption.IGNORE_CASE)
    /** mpv's SRT and SAMI readers draw &nbsp; as text: it becomes a non-breaking space. */
    fun playable(content: String) = content.replace(nbsp, "\u00A0")
    fun repairEntities(file: File) {
        if (!file.canonicalPath.startsWith(root.canonicalPath + "/")) return
        val content = runCatching { text(file) }.getOrNull() ?: return
        if (nbsp.containsMatchIn(content)) runCatching { file.writeText(playable(content)) }
    }

    fun preparedFonts(source: String) = fonts(File(File(root, key(source)), "asset"))
    fun fonts(subtitle: File): List<File> {
        val index = File(subtitle.path + ".fonts.json")
        val values = runCatching { org.json.JSONArray(index.readText()) }.getOrNull() ?: return emptyList()
        return (0 until values.length()).map { File(root, values.getString(it)) }.filter { it.canonicalPath.startsWith(root.canonicalPath + "/") && it.isFile }
    }
    fun associateFonts(fonts: List<File>, subtitle: File) {
        val prefix = root.canonicalPath + "/"
        val files = fonts.map { it.canonicalPath }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSortedSet()
        File(subtitle.path + ".fonts.json").writeText(org.json.JSONArray(files.toList()).toString())
    }

    /** A font copied where mpv (libass) finds fonts; its family name, as the subtitle font setting uses it. */
    fun importFont(file: File): String {
        fontDirectory.mkdirs()
        val target = File(fontDirectory, file.name)
        if (target.canonicalPath != file.canonicalPath) file.copyTo(target, overwrite = true)
        return familyName(target)
    }
    fun importFont(input: InputStream, name: String): String {
        fontDirectory.mkdirs()
        val target = File(fontDirectory, name.replace("/", "_"))
        target.outputStream().use { input.copyTo(it) }
        return familyName(target)
    }
    /** The family name in a TrueType/OpenType font's name table (nameID 1), else the file's name. */
    fun familyName(file: File): String = runCatching {
        val data = file.readBytes()
        fun u16(at: Int) = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
        fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        var font = 0
        if (String(data, 0, 4, Charsets.US_ASCII) == "ttcf") font = u32(12).toInt()
        val tables = u16(font + 4)
        var nameTable = -1
        for (i in 0 until tables) { val entry = font + 12 + i * 16; if (String(data, entry, 4, Charsets.US_ASCII) == "name") nameTable = u32(entry + 8).toInt() }
        if (nameTable < 0) return@runCatching null
        val count = u16(nameTable + 2); val storage = nameTable + u16(nameTable + 4)
        var fallback: String? = null
        for (i in 0 until count) {
            val record = nameTable + 6 + i * 12
            val platform = u16(record); val nameID = u16(record + 6); val length = u16(record + 8); val offset = u16(record + 10)
            if (nameID != 1) continue
            val bytes = data.copyOfRange(storage + offset, storage + offset + length)
            val value = if (platform == 3 || platform == 0) String(bytes, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
            if (platform == 3) return@runCatching value
            if (fallback == null) fallback = value
        }
        fallback
    }.getOrNull()?.trim()?.ifEmpty { null } ?: file.nameWithoutExtension

    fun clearTranslationCache() { translations.deleteRecursively() }

    /** All files under the subtitle folder and their size (설정 > 저장 자막·캐시). */
    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/** iOS SubtitleArchive (libarchive): ZIP (Korean names in CP949), 7z and RAR, only subtitle and font files taken. */
object SubtitleArchive {
    private val wanted = listOf("ass", "ssa", "srt", "vtt", "smi", "sbv", "sub", "mpl2", "ttml", "xml", "ttf", "otf", "ttc")
    fun extract(file: File, folder: File): List<File> {
        val head = file.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
        val entries = when {
            head[0] == 0x37.toByte() && head[1] == 0x7a.toByte() -> sevenZip(file)
            head[0] == 0x52.toByte() && head[1] == 0x61.toByte() -> rar(file)
            else -> zip(file)
        }
        val files = mutableListOf<File>()
        var total = 0L
        for ((original, bytes) in entries) {
            val name = SubtitleNames.readable(original)
            if (name.substringAfterLast('.', "").lowercase() !in wanted) continue
            total += bytes.size
            if (files.size >= 250 || bytes.size > 50_000_000 || total > 100_000_000) throw PlaybackFailure("압축된 자막 파일이 너무 큽니다.")
            val target = File(folder, SubtitleFiles.key(original).take(12) + "_" + name.replace("\\", "/").substringAfterLast('/'))
            target.writeBytes(bytes); files += target
        }
        if (files.isEmpty()) throw PlaybackFailure("압축 파일에 지원하는 자막이 없습니다.")
        return files
    }
    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); if (out.size() > 50_000_000) throw PlaybackFailure("압축된 자막 파일이 너무 큽니다.") }
        return out.toByteArray()
    }
    private fun zip(file: File): List<Pair<String, ByteArray>> {
        // Older Korean ZIP archives omit the UTF-8 name flag: their names are CP949.
        fun read(charset: Charset) = java.util.zip.ZipFile(file, charset).use { zip ->
            zip.entries().toList().filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in wanted }
                .map { entry -> entry.name to zip.getInputStream(entry).use(::readLimited) }
        }
        return runCatching { read(Charsets.UTF_8) }.recoverCatching { read(Charset.forName("x-windows-949")) }.getOrElse { throw PlaybackFailure("압축 자막을 열 수 없습니다.") }
    }
    private fun sevenZip(file: File): List<Pair<String, ByteArray>> = runCatching {
        org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(file).get().use { archive ->
            val result = mutableListOf<Pair<String, ByteArray>>()
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory || entry.name.substringAfterLast('.', "").lowercase() !in wanted) continue
                result += entry.name to readLimited(archive.getInputStream(entry))
            }
            result
        }
    }.getOrElse { throw PlaybackFailure("7z 자막을 열 수 없습니다: " + it.message) }
    private fun rar(file: File): List<Pair<String, ByteArray>> = runCatching {
        com.github.junrar.Archive(file).use { archive ->
            archive.fileHeaders.filter { !it.isDirectory && it.fileName.substringAfterLast('.', "").lowercase() in wanted }.map { header ->
                header.fileName to ByteArrayOutputStream().also { archive.extractFile(header, it) }.toByteArray()
            }
        }
    }.getOrElse { throw PlaybackFailure("RAR 자막을 열 수 없습니다 (RAR5는 지원하지 않아요): " + it.message) }
}

/** iOS SubtitleNames: names misread from Latin-1 or Windows-1252 bytes recovered, download names from their header. */
object SubtitleNames {
    private val windowsBytes = mapOf(0x20ac to 0x80, 0x201a to 0x82, 0x0192 to 0x83, 0x201e to 0x84, 0x2026 to 0x85, 0x2020 to 0x86, 0x2021 to 0x87,
        0x02c6 to 0x88, 0x2030 to 0x89, 0x0160 to 0x8a, 0x2039 to 0x8b, 0x0152 to 0x8c, 0x017d to 0x8e, 0x2018 to 0x91, 0x2019 to 0x92, 0x201c to 0x93,
        0x201d to 0x94, 0x2022 to 0x95, 0x2013 to 0x96, 0x2014 to 0x97, 0x02dc to 0x98, 0x2122 to 0x99, 0x0161 to 0x9a, 0x203a to 0x9b, 0x0153 to 0x9c,
        0x017e to 0x9e, 0x0178 to 0x9f)
    fun readable(name: String): String {
        var candidate = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC); var result = name
        val original = eastAsian(candidate)
        repeat(3) {
            val decoded = java.text.Normalizer.normalize(repair(candidate), java.text.Normalizer.Form.NFC)
            if (decoded == candidate) return result
            if (eastAsian(decoded) > original) result = decoded
            candidate = decoded
        }
        return result
    }
    private fun repair(name: String): String {
        val points = name.codePoints().toArray()
        fun byte(point: Int): Int? = if (point <= 0xff) point else windowsBytes[point]
        val out = StringBuilder(); var i = 0
        while (i < points.size) {
            val first = byte(points[i]) ?: 0
            val count = when (first) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 0 }
            if (count > 0 && i + count <= points.size) {
                val bytes = (i until i + count).mapNotNull { byte(points[it]) }
                if (bytes.size == count && bytes.drop(1).all { it in 0x80..0xbf }) {
                    val decoded = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.map { it.toByte() }.toByteArray())).toString() }.getOrNull()
                    if (decoded != null) { out.append(decoded); i += count; continue }
                }
            }
            out.appendCodePoint(points[i]); i++
        }
        return out.toString()
    }
    private fun eastAsian(value: String) = value.codePoints().filter { it in 0x3040..0x30ff || it in 0x3400..0x9fff || it in 0xac00..0xd7af }.count().toInt()

    fun downloadFilename(disposition: String?, fallback: String): String {
        disposition ?: return readable(fallback)
        var ordinary: String? = null
        for (match in Regex("(?:^|;)\\s*(filename\\*?)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]*))", RegexOption.IGNORE_CASE).findAll(disposition)) {
            val key = match.groupValues[1].lowercase()
            val value = (if (match.groups[2] != null) match.groupValues[2].replace(Regex("\\\\(.)"), "$1") else match.groupValues[3]).trim()
            if (key == "filename*") {
                val parts = value.split("'", limit = 3)
                if (parts.size == 3 && parts[0].lowercase() in setOf("utf-8", "utf8")) runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), "UTF-8") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return readable(it) }
            } else if (value.isNotEmpty()) ordinary = readable(value)
        }
        return ordinary ?: readable(fallback)
    }
    fun label(name: String, url: String = "", fallback: String = "자막 파일"): String {
        val value = readable(name).trim()
        if (displayable(value)) return value
        val file = readable(url.substringBefore('?').substringAfterLast('/'))
        if (displayable(file) && file.substringAfterLast('.', "").lowercase() in listOf("ass", "ssa", "srt", "smi", "vtt", "zip", "7z", "rar", "sub", "ttml")) return file
        return fallback
    }
    private fun displayable(value: String) = value.any { it.isLetterOrDigit() } && value.none { it == '\uFFFD' || it.code in 0x80..0x9f }
}
