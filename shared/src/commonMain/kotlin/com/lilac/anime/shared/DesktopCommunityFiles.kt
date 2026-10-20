package com.lilac.anime.shared

/** electron/community-matching.cjs; filename numbers must describe an episode. */
object DesktopCommunityFiles {
    data class Parsed(val episode: Double?, val season: Int?)
    // Compiled once: a pool parses every file of an archive.
    private val extension = Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE)
    private val crc = Regex("\\[[0-9a-f]{8}]", RegexOption.IGNORE_CASE)
    private val resolution = Regex("(?<![a-z0-9])(?:\\d{3,4}[pi]|[xh]\\.?26[45]|(?:19|20)\\d{2})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val seasonCount = Regex("\\d+\\s*기")
    private val trailingMarks = Regex("[ ._-]+$")
    // "S02E05", and "Show S2 - 04SubsPlease" as one maker's series post names its second season's files.
    private val seasonEpisode = Regex("(?:^|[^a-z0-9])s(\\d{1,2})(?:[ ._-]*e|\\s+-\\s*)(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val explicit = Regex("(?:^|[^a-z])(?:episode|ep|e)[ ._-]*(\\d+(?:\\.\\d+)?)(?![\\d.])", RegexOption.IGNORE_CASE)
    private val finished = Regex("\\((?:끝|완|完|end|fin)\\)", RegexOption.IGNORE_CASE)
    private val tag = Regex("\\[[^\\]]*]|\\([^)]*\\)")
    private val numberTag = Regex("^[\\[(]\\s*\\d+(?:\\.\\d+)?(?:v\\d+)?\\s*[\\])]$", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")
    private val notEpisode = Regex("\\b(?:no|vol|volume|part|cour|season|movie|level|lv)[\\s.]*\\d+$", RegexOption.IGNORE_CASE)
    private val bare = Regex("(?:^|[\\s_.\\-\\[\\](])(\\d+(?:\\.\\d+)?)(?:v\\d+)?[\\s\\])]*$", RegexOption.IGNORE_CASE)
    // "Show - 01SubsPlease", "Show 01_ToonsHub": the release group glued after the number (a capital, so "Show - 2nd"
    // stays unnumbered).
    private val glued = Regex("(?:^|[\\s_.-])(\\d+(?:\\.\\d+)?)(?:v\\d+)?_?[A-Z][A-Za-z0-9-]*$")
    // A Korean title with the number glued on ("약사의 혼잣말03"); two digits, as "그대들에게2" is a season.
    private val hangul = Regex("[가-힣](\\d{2,3})$")
    private val number = Regex("\\d+(?:\\.\\d+)?")
    private val extras = Regex("non-?telop|textless|(?:^|[^a-z0-9])(?:NC(?:OP|ED)|PV|CM)(?=$|[^a-z0-9])|tokuten|preview|trailer|논텔롭|예고편|특전", RegexOption.IGNORE_CASE)
    private val revision = Regex("(?:\\d|[ _.-])v(\\d+)(?=[ ._\\]\\-]|$)", RegexOption.IGNORE_CASE)

    fun parse(filename: String): Parsed {
        val name = normalizeDesktopTitle(filename.replace('\\', '/').substringAfterLast('/')).replace(extension, "")
            .replace(crc, " ").replace(resolution, " ").replace(seasonCount, " ").replace(trailingMarks, "").trim()
        seasonEpisode.find(name)?.let { return Parsed(it.groupValues[2].toDouble(), it.groupValues[1].toInt()) }
        explicit.find(name)?.let { return Parsed(it.groupValues[1].toDouble(), null) }
        val korean = DesktopCommunity.episodes(name)
        if (korean.list.size == 1) return Parsed(korean.list.single(), null)
        // Tags in brackets or parentheses go ("[SubsPlease] Show - 02 (1080p) [CRC]", "Show - 01 (BS11 1280x720 x264
        // AAC)"), except a bare number ("Show [01]").
        val tail = name.replace(finished, "").replace(tag) { if (numberTag.matches(it.value)) it.value else " " }.replace(spaces, " ").trim()
        if (notEpisode.containsMatchIn(tail)) return Parsed(null, null)
        bare.find(tail)?.let { return Parsed(it.groupValues[1].toDouble(), null) }
        glued.find(tail)?.let { return Parsed(it.groupValues[1].toDouble(), null) }
        return Parsed(hangul.find(tail)?.groupValues?.get(1)?.toDouble(), null)
    }

    /**
     * Files named alike but for one number ("A 01 x.smi" … "A 09 x.smi", whatever the shape around it) are an episode
     * set numbered by it: names the parser does not read are numbered this way, so the set is never taken as unnumbered
     * (and any one of its files given to every episode).
     */
    private fun seriesEpisodes(indices: List<Int>, names: List<String>): Map<Int, Double> {
        val groups = linkedMapOf<String, MutableList<Pair<Int, List<String>>>>()
        for (index in indices) {
            val base = normalizeDesktopTitle(names[index]).replace(extension, "")
            groups.getOrPut(base.replace(number, "#").lowercase()) { mutableListOf() } += index to number.findAll(base).map { it.value }.toList()
        }
        val found = mutableMapOf<Int, Double>()
        for (list in groups.values) {
            if (list.size < 2) continue
            val varying = list[0].second.indices.filter { at -> list.map { it.second.getOrNull(at)?.toDoubleOrNull() }.toSet().size > 1 }
            if (varying.size == 1) for ((index, numbers) in list) numbers.getOrNull(varying.single())?.toDoubleOrNull()?.let { found[index] = it }
        }
        return found
    }

    /** Subtitle files with their episode numbers (extras such as NCOP and other seasons' files left out). */
    private fun pool(names: List<String>, season: Int): List<Pair<Int, Parsed>> {
        val pool = names.indices.filter { !extras.containsMatchIn(names[it]) }.map { it to parse(names[it]) }
            .filter { season <= 0 || it.second.season == null || it.second.season == season }
        val numbered = seriesEpisodes(pool.filter { it.second.episode == null }.map { it.first }, names)
        return pool.map { (index, parsed) -> index to (numbered[index]?.let { parsed.copy(episode = it) } ?: parsed) }
    }

    /** selectCommunityFile; [season] 0 when the title marks none. Returns -1 when no file fits. */
    fun select(names: List<String>, sizes: List<Long>, episode: Double, strict: Boolean, bundle: Boolean, season: Int): Int {
        val pool = pool(names, season)
        fun quality(index: Int): Int = (mapOf("ass" to 50, "ssa" to 40, "srt" to 30, "vtt" to 20, "smi" to 10)[names[index].substringAfterLast('.').lowercase()] ?: 0) +
            (revision.find(names[index])?.groupValues?.get(1)?.toIntOrNull() ?: 0).coerceAtMost(9)
        // A file marked with the season asked for beats an unmarked one of the same number; with no season asked for, a
        // first-season or unmarked file beats a later season's.
        fun marked(parsed: Parsed): Int = if (season > 0) (if (parsed.season == season) 1 else 0) else -(if ((parsed.season ?: 1) > 1) 1 else 0)
        fun best(items: List<Pair<Int, Parsed>>) = items.sortedWith(compareByDescending<Pair<Int, Parsed>> { marked(it.second) }
            .thenByDescending { quality(it.first) }.thenByDescending { sizes.getOrElse(it.first) { 0 } }).firstOrNull()?.first ?: -1
        val exact = pool.filter { it.second.episode == episode }
        if (exact.isNotEmpty()) return best(exact)
        val unnumbered = pool.filter { it.second.episode == null }
        return if (!strict || !bundle && episode == 1.0 && unnumbered.size == pool.size) best(unnumbered) else -1
    }

    /**
     * pickCommunityFile: the file for an episode among one tier's downloaded files: its numbered file, or, from links
     * named for that episode alone ([exact]), whatever single episode they hold (makers number a later season on:
     * "골든카무이 5기 13화" holds "Golden Kamuy - 62"). Otherwise the rules for unnumbered files (strict / bundle).
     */
    fun pick(names: List<String>, sizes: List<Long>, episode: Double, exact: Boolean, strict: Boolean, bundle: Boolean, season: Int): Int {
        val found = select(names, sizes, episode, strict = true, bundle = true, season = season)
        if (found >= 0) return found
        if (exact) {
            val numbers = pool(names, season).map { it.second.episode }.toSet()
            if (numbers.size == 1) return select(names, sizes, numbers.single() ?: episode, strict = false, bundle = false, season = season)
        }
        return select(names, sizes, episode, strict, bundle, season)
    }
}
