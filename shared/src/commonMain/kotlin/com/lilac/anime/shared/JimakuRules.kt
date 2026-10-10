package com.lilac.anime.shared

internal expect fun compareJimakuNames(left: String, right: String): Int

/** Direct port of main.cjs jimakuEpisodeScore / jimakuEpisodeFiles / jimakuLikeness. */
object JimakuRules {
    private val formats = mapOf("ass" to 100, "ssa" to 96, "srt" to 90, "vtt" to 86, "smi" to 84, "sami" to 84)
    fun supported(name: String) = name.substringAfterLast('.').lowercase() in formats
    fun validDownload(url: String) = Regex("^https://jimaku\\.cc/entry/\\d+/download/").containsMatchIn(url)
    fun episodeScore(name: String, episode: Int): Int {
        val lower = name.lowercase()
        val range = Regex("(?:s\\d{1,2}e)0*(\\d{1,3})\\s*[-~〜–—]\\s*(?:s\\d{1,2}e)?0*(\\d{1,3})").find(lower)
            ?: Regex("(?:^|[^a-z0-9])(?:e|ep|episode)?0*(\\d{1,3})\\s*[-~〜–—]\\s*(?:e|ep|episode)?0*(\\d{1,3})(?:$|[^0-9])").find(lower)
        if (range != null) {
            val from = range.groupValues[1].toInt(); val to = range.groupValues[2].toInt()
            if (from < to) return if (episode in from..to) 45 else 0
        }
        if (Regex("(?:^|[^a-z0-9])s\\d{1,2}e0*$episode(?:[^0-9]|$)").containsMatchIn(lower)) return 65
        if (Regex("(?:^|[^a-z0-9])(?:ep|episode|e)0*$episode(?:[^0-9]|$)").containsMatchIn(lower)) return 58
        if (Regex("(?:^|[^a-z0-9])0*$episode\\s*(?:화|회|편|話)").containsMatchIn(lower)) return 55
        return if (Regex("(?:^|[^a-z0-9])0*$episode(?:[^a-z0-9]|$)").containsMatchIn(lower)) 50 else 0
    }
    fun rank(files: List<SubtitleAsset>, title: String, episode: Int, preferred: String): List<SubtitleAsset> {
        val season = DesktopTitleRules.season(title)
        val candidates = files.filter { supported(it.name) }
        val movie = episode == 1 && candidates.none { episodeScore(it.name, 2) > 0 }
        fun quality(asset: SubtitleAsset): Int {
            val lower = asset.name.lowercase(); val ext = lower.substringAfterLast('.')
            val ass = ext == "ass" || ext == "ssa"
            return formats.getValue(ext) + (if (ass) { if ("furigana" in lower) 8 else if (".ja" in lower) 6 else 3 } else if (".ja" in lower) 2 else 0) +
                (if (season > 1 && Regex("(?:^|[^a-z0-9])s0*$season(?:e|[-_ ])").containsMatchIn(lower)) 12 else 0)
        }
        val eligible = candidates.map { it.copy(score = (if (movie) 1 else episodeScore(it.name, episode)).toDouble()) }
            .filter { it.score > 0 }.sortedWith { a, b ->
                val score = (b.score + quality(b)).compareTo(a.score + quality(a))
                if (score != 0) score else if (b.size != a.size) b.size.compareTo(a.size) else compareJimakuNames(a.name, b.name)
            }
        return prefer(eligible, preferred)
    }
    fun prefer(files: List<SubtitleAsset>, preferred: String): List<SubtitleAsset> {
        if (preferred.isEmpty()) return files
        val chosen = files.filter { likeness(it.name, preferred) >= 0.8 }.maxByOrNull { likeness(it.name, preferred) } ?: return files
        return listOf(chosen) + files.filter { it !== chosen }
    }
    private fun likeness(left: String, right: String): Double {
        fun words(name: String) = name.lowercase().substringBeforeLast('.').replace(Regex("\\d+"), "#")
            .split(Regex("[^\\p{L}\\p{N}#]+")).filter(String::isNotBlank).toSet()
        fun group(name: String) = Regex("^\\s*[\\[【(]([^\\]】)]+)[\\]】)]").find(name.lowercase())?.groupValues?.get(1).orEmpty()
        val a = words(left); val b = words(right)
        return (a intersect b).size.toDouble() / (a union b).size.coerceAtLeast(1) +
            (if (group(left).isNotEmpty() && group(left) == group(right)) 0.5 else 0.0) +
            (if (left.substringAfterLast('.').lowercase() == right.substringAfterLast('.').lowercase()) 0.2 else 0.0)
    }
}
