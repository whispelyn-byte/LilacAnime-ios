package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.HangulSimilarityMatcher
import kotlinx.serialization.Serializable

@Serializable
data class CommunityPost(val title: String, val url: String, val html: String)
data class CommunityMatch(val post: CommunityPost, val links: List<String>, val episode: Int, val strict: Boolean, val score: Double = 0.0, val bundle: Boolean = false, val episodeNumber: Double = episode.toDouble())

/** main.cjs/communityLinks and rankCommunityPosts. */
object DesktopCommunity {
    private val trailing = Regex("(?<!season|시즌|part|파트|vol\\.?|제)\\s+(\\d{1,3}(?:\\.\\d+)?)\\s*(?:\\((?:끝|완|完)\\))?\\s*(?:자막)?\\s*$", RegexOption.IGNORE_CASE)
    data class Episodes(val list: List<Double>, val ranges: List<ClosedFloatingPointRange<Double>>) {
        val any get() = list.isNotEmpty() || ranges.isNotEmpty()
        fun has(value: Int) = has(value.toDouble())
        fun has(value: Double) = value in list || ranges.any { value in it }
    }
    fun episodes(input: String): Episodes {
        val value = normalizeDesktopTitle(input)
        val ranges = Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*[~∼〜～–—-]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:화|회|편)")
        val found = ranges.findAll(value).map { it.groupValues[1].toDouble()..it.groupValues[2].toDouble() }.filter { it.start <= it.endInclusive }.toList()
        val text = ranges.replace(value, " ")
        val list = Regex("(?<![\\d.])((?:\\d+(?:\\.\\d+)?\\s*[,、&/]\\s*)*\\d+(?:\\.\\d+)?)\\s*(?:화|회|편)").findAll(text).flatMap { it.groupValues[1].split(Regex("[,、&/]")).map { part -> part.trim().toDouble() } }.toMutableList()
        list += Regex("\\bep(?:isode)?\\s*\\.?\\s*(\\d+(?:\\.\\d+)?)(?![\\d.])", RegexOption.IGNORE_CASE).findAll(value).map { it.groupValues[1].toDouble() }
        return Episodes(list.distinct(), found)
    }
    private fun postEpisodes(title: String): Episodes = episodes(title).takeIf { it.any } ?: trailing.find(normalizeDesktopTitle(title))?.let { Episodes(listOf(it.groupValues[1].toDouble()), emptyList()) } ?: Episodes(emptyList(), emptyList())
    private fun title(value: String): String = normalizeDesktopTitle(value).replace(Regex("\\d+(?:\\.\\d+)?\\s*[~∼〜～–—\\-,、&/]\\s*(?=\\d)"), "").replace(Regex("\\d+(?:\\.\\d+)?\\s*(?:화|회|편)|\\bep(?:isode)?\\s*\\.?\\s*\\d+(?:\\.\\d+)?|\\((?:끝|완|完)\\)|작업\\s*중|블루레이판|자막", RegexOption.IGNORE_CASE), " ").trim()
    fun links(post: CommunityPost, episode: Int, offsets: List<Int> = emptyList(), allowOffset: Boolean = false, episodeNumber: Double = episode.toDouble()): CommunityMatch {
        val anchors = Ksoup.parse(post.html, post.url).select("a[href]").map { it.absUrl("href") to it.text().trim() }
            .filter { Regex("drive\\.google\\.com|docs\\.google\\.com|\\.zip(?:$|\\?)|\\.(?:ass|ssa|srt|vtt|smi)(?:$|\\?)", RegexOption.IGNORE_CASE).containsMatchIn(it.first) }
        val fonts = anchors.filter { Regex("폰트|font", RegexOption.IGNORE_CASE).containsMatchIn(it.second) }
        val subs = anchors.filter { it !in fonts }
        fun withFonts(selected: List<Pair<String,String>>) = if (selected.isEmpty()) emptyList() else (selected + fonts).map { it.first }.distinct()
        val own = postEpisodes(post.title)
        if (own.any) {
            val bundle = own.ranges.isNotEmpty() || own.list.size > 1
            return CommunityMatch(post, if (own.has(episodeNumber)) withFonts(subs) else emptyList(), episode, bundle, bundle = bundle, episodeNumber = episodeNumber)
        }
        val labeled = subs.map { it to episodes(it.second) }.filter { it.second.any }
        if (labeled.isEmpty()) return CommunityMatch(post, withFonts(subs), episode, true, episodeNumber = episodeNumber)
        fun pick(number: Double) = labeled.filter { number in it.second.list }.ifEmpty { labeled.filter { it.second.has(number) } }
        val direct = pick(episodeNumber)
        val number = if (direct.isNotEmpty() || !allowOffset) episodeNumber else offsets.map { episodeNumber + it }.firstOrNull { it > episodeNumber && pick(it).isNotEmpty() } ?: episodeNumber
        val chosen = direct.ifEmpty { pick(number) }
        val bundle = chosen.any { it.second.ranges.isNotEmpty() || it.second.list.size > 1 }
        return CommunityMatch(post, withFonts(chosen.map { it.first }), number.toInt(), bundle, bundle = bundle, episodeNumber = number)
    }
    private fun titleScore(first: String, second: String): Double {
        val a = DesktopTitleRules.key(first); val b = DesktopTitleRules.key(second)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        val aa = DesktopTitleRules.simple(first).split(' ').filter(String::isNotBlank).distinct()
        val bb = DesktopTitleRules.simple(second).split(' ').filter(String::isNotBlank).distinct()
        fun missing(x: List<String>, y: List<String>) = x.count { word -> word.length >= 2 && y.none { minOf(word.length, it.length) >= 2 && (word.contains(it) || it.contains(word)) } }
        val ma = missing(aa, bb); val mb = missing(bb, aa)
        return if (ma > 0 && mb > 0) 0.0 else minOf((aa.size - ma).toDouble() / aa.size, (bb.size - mb).toDouble() / bb.size)
    }
    fun score(first: String, second: String): Double {
        fun bare(value: String) = value.replace(Regex("[~〜～][^~〜～]*[~〜～]"), " ").replace(Regex("\\s+"), " ").trim()
        val a = DesktopTitleRules.key(bare(first)); val b = DesktopTitleRules.key(bare(second))
        val short = if (a.length < b.length) a else b; val long = if (short == a) b else a
        var best = maxOf(titleScore(first, second), titleScore(bare(first), bare(second)))
        if (Regex("^[가-힣]{4,}$").matches(short) && long.startsWith(short)) best = maxOf(best, .6)
        val distance = HangulSimilarityMatcher.weightedEditDistance(a.replace("카구야", "가구야"), b.replace("카구야", "가구야"))
        val similarity = if (maxOf(a.length, b.length) == 0) 0.0 else 1 - distance / maxOf(a.length, b.length)
        if (similarity >= .75 && distance <= 1.5) best = maxOf(best, similarity)
        return best
    }
    fun rank(posts: List<CommunityPost>, wantedTitle: String, episode: Int, offsets: List<Int>, episodeNumber: Double = episode.toDouble()): List<CommunityMatch> {
        val season = DesktopTitleRules.season(wantedTitle)
        val usable = posts.filterNot { Regex("작업\\s*중|하차").containsMatchIn(it.title) }
        fun ranked(items: List<CommunityPost>, name: String, number: Double) = items.map { post ->
            val score = score(title(name), title(trailing.replace(post.title, " ")))
            links(post, number.toInt(), offsets, season > 1, number).copy(score = score)
        }.filter { it.score >= .52 && it.links.isNotEmpty() }.sortedWith { a,b ->
            val difference = kotlin.math.floor((b.score - a.score) * 20 + .5).toInt()
            if (difference != 0) difference else a.strict.compareTo(b.strict)
        }
        val direct = ranked(usable.filter { (DesktopTitleRules.explicitSeason(title(it.title)) ?: 1) == season }, wantedTitle, episodeNumber)
        if (direct.isNotEmpty() || season < 2) return direct
        val unmarked = usable.filter { DesktopTitleRules.explicitSeason(title(it.title)) == null }
        val base = wantedTitle.replace(Regex("\\s*(?:\\d+\\s*기(?![가-힣])|season\\s*\\d+|시즌\\s*\\d+|\\d+(?:st|nd|rd|th)\\s*season)", RegexOption.IGNORE_CASE), " ")
        for (offset in offsets) {
            val number = episodeNumber + offset
            val found = ranked(unmarked, base, number).filter { it.episodeNumber == number && (postEpisodes(it.post.title).has(number) || !it.strict) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }
}
