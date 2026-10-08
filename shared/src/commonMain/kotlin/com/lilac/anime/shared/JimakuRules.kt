package com.lilac.anime.shared

import com.lilac.anime.shared.ported.SubtitleEpisodeMatcher

object JimakuRules {
    private val formats = mapOf("ass" to 100, "ssa" to 96, "srt" to 90, "vtt" to 86, "smi" to 84, "sami" to 84,
        "sub" to 80, "ttml" to 80, "zip" to 70, "7z" to 70, "rar" to 70)
    fun rank(files: List<SubtitleAsset>, title: String, episode: Int, preferred: String): List<SubtitleAsset> {
        val season = DesktopTitleRules.season(title)
        val candidates = files.filter { asset ->
            val parsed = SubtitleEpisodeMatcher.parse(asset.name)
            (parsed?.season == null || parsed.season == season) && asset.name.substringAfterLast('.').lowercase() in formats
        }
        val movie = episode == 1 && candidates.none { SubtitleEpisodeMatcher.matches(it.name, 2, season) }
        val eligible = candidates.mapNotNull { asset ->
            val extension = asset.name.substringAfterLast('.').lowercase()
            val unnumbered = SubtitleEpisodeMatcher.parse(asset.name)?.episode == null
            if (!SubtitleEpisodeMatcher.matches(asset.name, episode, season) && !(unnumbered && (movie || extension in listOf("zip","7z","rar")))) null
            else asset.copy(score = 1.0)
        }.sortedWith(compareByDescending<SubtitleAsset> {
            val name = it.name.lowercase()
            formats[name.substringAfterLast('.')].orEmptyScore() + when {
                name.endsWith(".ass") || name.endsWith(".ssa") -> if ("furigana" in name) 8 else if (".ja" in name) 6 else 3
                ".ja" in name -> 2
                else -> 0
            }
        }.thenBy { it.name })
        if (preferred.isBlank()) return eligible
        val chosen = eligible.maxByOrNull { likeness(it.name, preferred) }?.takeIf { likeness(it.name, preferred) >= 0.8 } ?: return eligible
        return listOf(chosen) + eligible.filter { it.url != chosen.url }
    }
    private fun Int?.orEmptyScore() = this ?: 0
    private fun likeness(left: String, right: String): Double {
        fun words(name: String) = name.lowercase().substringBeforeLast('.').replace(Regex("\\d+"), "#")
            .split(Regex("[^\\p{L}\\p{N}#]+")).filter(String::isNotBlank).toSet()
        fun group(name: String) = Regex("[\\[【(]([^\\]】)]+)[\\]】)]").find(name)?.groupValues?.get(1)?.lowercase().orEmpty()
        val a = words(left); val b = words(right)
        return (a intersect b).size.toDouble() / (a union b).size.coerceAtLeast(1) +
            (if (group(left).isNotEmpty() && group(left) == group(right)) 0.5 else 0.0) +
            (if (left.substringAfterLast('.') == right.substringAfterLast('.')) 0.2 else 0.0)
    }
}
