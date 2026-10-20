package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A ranked post's links as downloads (desktop downloadCommunityMatch): Drive file links as direct downloads, and Google
 * Drive folders searched for the episode's own file. Each asset keeps its link's tier, so the player downloads one tier
 * at a time.
 */
internal class CommunityAssets(private val repository: SourceRepository) {
    data class DriveEntry(val id: String, val name: String, val folder: Boolean)

    suspend fun assets(match: CommunityMatch, name: String, source: String, title: String): List<SubtitleAsset> {
        val season = match.fileSeason ?: 0
        fun asset(link: CommunityLink, url: String, label: String = link.label, font: Boolean = link.font) = SubtitleAsset(name, url, source, match.score, match.episode, match.strict, match.bundle, match.post.url, match.episodeNumber,
            tier = link.tier, exact = link.exact, font = font, label = label, fileSeason = season)
        val output = mutableListOf<SubtitleAsset>()
        for (link in match.linkInfo.ifEmpty { match.links.map { CommunityLink(it, "", tier = -1) } }) {
            val folder = folderId(link.href)
            if (folder == null) { output += asset(link, download(link.href)); continue }
            // A Drive folder: only the episode's own file (or archive) in it is downloaded, else its unnumbered archives.
            val entries = try { folderFiles(folder, title) } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
            val picked = DesktopCommunityFiles.pick(entries.map { it.name }, entries.map { 0L }, match.episodeNumber, exact = false, strict = true, bundle = false, season = season)
            val targets = if (picked >= 0) listOf(entries[picked]) else entries.filter { archive.containsMatchIn(it.name) && DesktopCommunityFiles.parse(it.name).episode == null }.take(2)
            for (entry in targets) output += asset(link, driveDownload(entry.id), entry.name)
            if (picked >= 0 && styled.containsMatchIn(entries[picked].name))
                for (entry in entries.filter { fontName.containsMatchIn(it.name) }) output += asset(link, driveDownload(entry.id), entry.name, font = true)
        }
        return output.distinctBy { it.url }
    }

    /**
     * Google Drive folders (drive.google.com/drive/folders/<id>): their public embedded view lists each entry's name and
     * id. A maker's folder can be a whole archive ("자막 모음" → 완결 → 2026 → 1분기 → <작품> → files), so it is searched
     * breadth first: a folder named like the title, then the newest years and quarters, for at most 40 listings. The
     * linked folder's own files are its answer when it has any.
     */
    suspend fun folderFiles(id: String, title: String): List<DriveEntry> = coroutineScope {
        data class Item(val id: String, val depth: Int, val match: Boolean, val path: List<Int>)
        val wanted = DesktopCommunity.title(title)
        fun named(entry: DriveEntry) = DesktopCommunity.score(wanted, DesktopCommunity.title(entry.name)) >= .52
        // Six listings at a time. Folders under numbered ones (years, quarters: where finished series are filed) go
        // first, the newest path first (2026 → 2분기 before 2025 → 4분기), then the rest in the order found.
        val order = Comparator<Item> { a, b ->
            for (i in 0 until maxOf(a.path.size, b.path.size)) {
                val x = a.path.getOrElse(i) { -1 }; val y = b.path.getOrElse(i) { -1 }
                if (x != y) return@Comparator y.compareTo(x)
            }
            a.depth - b.depth
        }
        val queue = mutableListOf(Item(id, 0, false, emptyList()))
        val seen = mutableSetOf<String>()
        var listed = 0
        while (queue.isNotEmpty() && listed < 40) {
            queue.sortWith(order)
            val batch = queue.take(6).also { repeat(it.size) { queue.removeAt(0) } }.filter { seen.add(it.id) }
            listed += batch.size
            val results = batch.map { item -> async { try { entries(item.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() } } }.awaitAll()
            var own: List<Item>? = null
            for ((index, item) in batch.withIndex()) {
                val files = results[index].filter { !it.folder }; val folders = results[index].filter { it.folder }
                if (files.any { subtitleOrArchive.containsMatchIn(it.name) } && (item.depth == 0 || item.match)) return@coroutineScope files
                val titled = folders.filter(::named)
                if (titled.isNotEmpty() && own == null) own = titled.map { Item(it.id, item.depth + 1, true, item.path) }
                if (item.depth < 5) queue += folders.filter { it !in titled }.map { Item(it.id, item.depth + 1, item.match, item.path + (digits.find(it.name)?.value?.toIntOrNull() ?: 0)) }
            }
            if (own != null) { queue.clear(); queue += own }
        }
        emptyList()
    }

    internal suspend fun entries(id: String): List<DriveEntry> {
        val document = Ksoup.parse(repository.getText("https://drive.google.com/embeddedfolderview", mapOf("id" to id)))
        return document.select(".flip-entry").mapNotNull { node ->
            val entry = node.attr("id").removePrefix("entry-")
            val name = node.selectFirst(".flip-entry-title")?.text()?.trim().orEmpty()
            if (entry.isBlank() || name.isBlank()) null else DriveEntry(entry, name, node.selectFirst("a")?.attr("href").orEmpty().contains("/folders/"))
        }
    }

    companion object {
        private val folderLink = Regex("drive\\.google\\.com/(?:drive/(?:u/\\d+/)?folders/|embeddedfolderview\\?id=)([\\w-]+)")
        private val subtitleOrArchive = Regex("\\.(?:ass|ssa|srt|vtt|smi|zip|7z|rar)$", RegexOption.IGNORE_CASE)
        private val archive = Regex("\\.(?:zip|7z|rar)$", RegexOption.IGNORE_CASE)
        private val styled = Regex("\\.(?:ass|ssa)$", RegexOption.IGNORE_CASE)
        private val fontName = Regex("폰트|font", RegexOption.IGNORE_CASE)
        private val digits = Regex("\\d+")
        fun folderId(url: String): String? = folderLink.find(url)?.groupValues?.get(1)
        private fun driveDownload(id: String) = "https://drive.usercontent.google.com/download?id=" + id.encodeURLParameter() + "&export=download&confirm=t"
        /** A Drive file link as its direct download; other links as they are. */
        fun download(link: String): String {
            val id = Regex("/file/d/([^/?]+)").find(link)?.groupValues?.get(1) ?: runCatching { Url(link).parameters["id"] }.getOrNull()
            return if (id != null && (link.contains("drive.google.com") || link.contains("docs.google.com"))) driveDownload(id) else link
        }
    }
}
