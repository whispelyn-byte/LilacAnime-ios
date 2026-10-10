package com.lilac.anime.shared

/** electron/community-matching.cjs; filename numbers must describe an episode. */
object DesktopCommunityFiles {
    data class Parsed(val episode: Double?, val season: Int?)
    fun parse(filename: String): Parsed {
        val name = normalizeDesktopTitle(filename.replace('\\', '/').substringAfterLast('/')).replace(Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[[0-9a-f]{8}]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?<![a-z0-9])(?:\\d{3,4}[pi]|[xh]\\.?26[45]|(?:19|20)\\d{2})(?![a-z0-9])", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\d+\\s*기"), " ").replace(Regex("[ ._-]+$"), "").trim()
        Regex("(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(name)?.let { return Parsed(it.groupValues[2].toDouble(), it.groupValues[1].toInt()) }
        Regex("(?:^|[^a-z])(?:episode|ep|e)[ ._-]*(\\d+(?:\\.\\d+)?)(?![\\d.])", RegexOption.IGNORE_CASE).find(name)?.let { return Parsed(it.groupValues[1].toDouble(), null) }
        val remaining = name.replace(Regex("(?<![\\d.])\\d+(?:\\.\\d+)?\\s*[~∼〜～–—-]\\s*\\d+(?:\\.\\d+)?\\s*(?:화|회|편)"), " ")
        val explicit = Regex("(?<![\\d.])((?:\\d+(?:\\.\\d+)?\\s*[,、&/]\\s*)*\\d+(?:\\.\\d+)?)\\s*(?:화|회|편)").findAll(remaining)
            .flatMap { it.groupValues[1].split(Regex("[,、&/]")).map(String::trim).map(String::toDouble) }.distinct().toList()
        if (explicit.size == 1) return Parsed(explicit.single(), null)
        val bare = name.replace(Regex("\\[[^\\]]*[^\\d.\\]\\s][^\\]]*]"), " ").replace(Regex("\\((?:끝|완|完|end|fin)\\)", RegexOption.IGNORE_CASE), "").trim()
        val episode = Regex("(?:^|[\\s_.\\-\\[\\]()])(\\d+(?:\\.\\d+)?)(?:v\\d+)?[\\s\\])]*$", RegexOption.IGNORE_CASE).find(bare)?.groupValues?.get(1)?.toDoubleOrNull()
        return Parsed(episode, null)
    }
    fun select(names: List<String>, sizes: List<Long>, episode: Double, strict: Boolean, bundle: Boolean, season: Int): Int {
        val extras = Regex("non-?telop|textless|\\bNC(?:OP|ED)\\b|tokuten|\\b(?:PV|CM)\\b|preview|trailer|논텔롭|예고편|특전", RegexOption.IGNORE_CASE)
        val pool = names.indices.filter { !extras.containsMatchIn(names[it]) }.map { it to parse(names[it]) }.filter { season <= 0 || it.second.season == null || it.second.season == season }
        fun quality(index: Int): Int = (mapOf("ass" to 50, "ssa" to 40, "srt" to 30, "vtt" to 20, "smi" to 10)[names[index].substringAfterLast('.').lowercase()] ?: 0) +
            (Regex("(?:\\d|[ _.-])v(\\d+)(?=[ ._\\]\\-]|$)", RegexOption.IGNORE_CASE).find(names[index])?.groupValues?.get(1)?.toIntOrNull() ?: 0).coerceAtMost(9)
        fun best(indices: List<Int>) = indices.sortedWith(compareByDescending<Int> { quality(it) }.thenByDescending { sizes.getOrElse(it) { 0 } }).firstOrNull() ?: -1
        val exact = pool.filter { it.second.episode == episode }.map { it.first }
        if (exact.isNotEmpty()) return best(exact)
        val unnumbered = pool.filter { it.second.episode == null }.map { it.first }
        return if (!strict || !bundle && episode == 1.0 && unnumbered.size == pool.size) best(unnumbered) else -1
    }
}
