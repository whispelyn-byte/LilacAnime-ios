package com.lilac.anime.android.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lilac.anime.android.player.PlaybackFailure
import com.lilac.anime.android.subtitle.SubtitleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** iOS DownloadTransfer: finished downloads moved to a folder (a PC, a card) and back, with an index of what they are. */
object DownloadTransfer {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun safeName(name: String) = name.isNotEmpty() && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") && !name.contains(":") && !name.contains("\u0000")
    private fun valid(entry: DownloadEntry) = entry.id == SubtitleFiles.key(entry.anime.id + "#" + entry.episodeID) && entry.localFile?.let(::safeName) == true &&
        (entry.rootFile == null || safeName(entry.rootFile)) && entry.subtitleFiles.all(::safeName) && entry.parts.all { safeName(it.name) }

    /** Every file an entry needs: its root, parts, subtitles and what its playlists name. */
    private fun files(entry: DownloadEntry, folder: File): Set<String> {
        val result = mutableSetOf<String>()
        entry.localFile?.let { result += it }; entry.rootFile?.let { result += it }
        entry.parts.forEach { result += it.name }; result += entry.subtitleFiles
        val pending = result.filter { it.endsWith(".m3u8") }.toMutableList(); val visited = mutableSetOf<String>()
        val uri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        while (pending.isNotEmpty()) {
            val name = pending.removeAt(pending.lastIndex)
            if (!safeName(name) || !visited.add(name)) continue
            val content = File(folder, name).readText()
            val refs = content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") } + uri.findAll(content).map { it.groupValues[1] }
            for (resource in refs) {
                if (!safeName(resource)) throw PlaybackFailure("오프라인 HLS 파일 경로가 잘못되었습니다.")
                result += resource
                if (resource.endsWith(".m3u8") && resource !in visited) pending += resource
            }
            if (result.size > 11000) throw PlaybackFailure("한 회차의 파일이 너무 많습니다.")
        }
        return result
    }

    suspend fun export(context: Context, entries: List<DownloadEntry>, tree: Uri, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw PlaybackFailure("폴더를 열 수 없습니다.")
        val name = "LilacDownloads-" + System.currentTimeMillis() / 1000
        val folder = root.createDirectory(name) ?: throw PlaybackFailure("폴더를 만들 수 없습니다.")
        try {
            entries.forEachIndexed { index, entry ->
                ensureActive()
                if (!valid(entry)) throw PlaybackFailure("다운로드 목록에 잘못된 파일 경로가 있습니다.")
                withContext(Dispatchers.Main) { progress("${index + 1}/${entries.size} · ${entry.anime.title} ${entry.title}") }
                val source = File(DownloadStore.directory, entry.id)
                val target = folder.createDirectory(entry.id) ?: throw PlaybackFailure("폴더를 만들 수 없습니다.")
                for (file in files(entry, source)) {
                    val out = target.createFile("application/octet-stream", file) ?: throw PlaybackFailure("파일을 만들 수 없습니다.")
                    context.contentResolver.openOutputStream(out.uri)!!.use { o -> File(source, file).inputStream().use { it.copyTo(o) } }
                }
            }
            val index = folder.createFile("application/json", "index.json") ?: throw PlaybackFailure("목록 파일을 만들 수 없습니다.")
            context.contentResolver.openOutputStream(index.uri)!!.use { it.write(json.encodeToString(ListSerializer(DownloadEntry.serializer()), entries).toByteArray()) }
            name
        } catch (error: Throwable) { folder.delete(); throw error }
    }

    /** A folder exported here (index.json beside a folder per episode) taken in; episodes already here are skipped. */
    suspend fun import(context: Context, tree: Uri, existing: Set<String>, progress: (String) -> Unit): List<DownloadEntry> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw PlaybackFailure("폴더를 열 수 없습니다.")
        val indexFile = root.findFile("index.json") ?: throw PlaybackFailure("index.json이 없습니다. 이 앱에서 내보낸 다운로드 폴더를 고르세요.")
        if (indexFile.length() > 16_000_000) throw PlaybackFailure("다운로드 목록이 너무 큽니다.")
        val entries = json.decodeFromString(ListSerializer(DownloadEntry.serializer()), context.contentResolver.openInputStream(indexFile.uri)!!.use { String(it.readBytes()) })
        if (entries.size > 10000) throw PlaybackFailure("다운로드 회차가 너무 많습니다.")
        val imported = mutableListOf<DownloadEntry>()
        entries.filter { it.id !in existing }.forEachIndexed { index, entry ->
            ensureActive()
            if (!valid(entry)) throw PlaybackFailure("다운로드 목록에 잘못된 파일 경로가 있습니다.")
            withContext(Dispatchers.Main) { progress("${index + 1} · ${entry.anime.title} ${entry.title}") }
            val source = root.findFile(entry.id)?.takeIf { it.isDirectory } ?: throw PlaybackFailure("영상 또는 자막 파일이 빠져 있습니다.")
            val target = File(DownloadStore.directory, entry.id)
            if (target.isDirectory) { imported += entry; return@forEachIndexed }
            val staging = File(DownloadStore.directory, "import-" + System.nanoTime()).apply { mkdirs() }
            try {
                val children = source.listFiles()
                if (children.size > 11000) throw PlaybackFailure("한 회차의 파일이 너무 많습니다.")
                for (child in children) {
                    val name = child.name ?: continue
                    if (!child.isFile || !safeName(name)) throw PlaybackFailure("다운로드 폴더에는 일반 파일만 넣을 수 있습니다.")
                    context.contentResolver.openInputStream(child.uri)!!.use { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
                }
                if (!files(entry, staging).all { File(staging, it).isFile }) throw PlaybackFailure("영상 또는 자막 파일이 빠져 있습니다.")
                if (!staging.renameTo(target)) throw PlaybackFailure("가져온 파일을 옮기지 못했습니다.")
                imported += entry.copy(status = "완료")
            } catch (error: Throwable) { staging.deleteRecursively(); throw error }
        }
        imported
    }
}
