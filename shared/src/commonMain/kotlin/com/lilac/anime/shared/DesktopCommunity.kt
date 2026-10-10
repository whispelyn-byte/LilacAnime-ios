package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.HangulSimilarityMatcher
import kotlinx.serialization.Serializable

@Serializable
data class CommunityPost(val title: String, val url: String, val html: String)
data class CommunityMatch(val post: CommunityPost, val links: List<String>, val episode: Int, val strict: Boolean, val score: Double = 0.0, val bundle: Boolean = false)

/** main.cjs/communityLinks and rankCommunityPosts. */
object DesktopCommunity {
    private val trailing = Regex("(?<!season|시즌|part|파트|vol\\.?|제)\\s+(\\d{1,3})\\s*(?:\\((?:끝|완)\\))?\\s*(?:자막)?\\s*$", RegexOption.IGNORE_CASE)
    data class Episodes(val list: List<Int>, val ranges: List<IntRange>) {
        val any get() = list.isNotEmpty() || ranges.isNotEmpty()
        fun has(value: Int) = value in list || ranges.any { value in it }
    }
    fun episodes(input: String): Episodes {
        val value = normalizeDesktopTitle(input)
        val ranges = Regex("(?<![\\d.])(\\d+)(?![\\d.])\\s*[~∼〜～–—-]\\s*(\\d+)(?![\\d.])\\s*(?:화|회|편)")
        val found = ranges.findAll(value).map { it.groupValues[1].toInt()..it.groupValues[2].toInt() }.toList()
        val text = ranges.replace(value, " ")
        val list = Regex("(?<![\\d.])((?:\\d+\\s*[,、&/]\\s*)*\\d+)(?![\\d.])\\s*(?:화|회|편)").findAll(text).flatMap { it.groupValues[1].split(Regex("[,、&/]")).map { part -> part.trim().toInt() } }.toMutableList()
        list += Regex("\\bep(?:isode)?\\s*\\.?\\s*(\\d+)(?![\\d.])", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1].toInt() }
        return Episodes(list, found)
    }
    private fun postEpisodes(title: String): Episodes = episodes(title).takeIf { it.any } ?: trailing.find(title)?.let { Episodes(listOf(it.groupValues[1].toInt()), emptyList()) } ?: Episodes(emptyList(), emptyList())
    private fun title(value: String): String = value.replace(Regex("(\\d+)\\s*[~∼,-]\\s*(?=\\d)"), "").replace(Regex("\\d+\\s*(?:화|회|편)|\\((?:끝|완)\\)|작업\\s*중|블루레이판|자막"), " ").trim()
    fun links(post: CommunityPost, episode: Int, offsets: List<Int> = emptyList(), allowOffset: Boolean = false): CommunityMatch {
        val anchors = Ksoup.parse(post.html, post.url).select("a[href]").map { it.absUrl("href") to it.text().trim() }
            .filter { Regex("drive\\.google\\.com|docs\\.google\\.com|\\.zip(?:$|\\?)|\\.(?:ass|ssa|srt|vtt|smi)(?:$|\\?)", RegexOption.IGNORE_CASE).containsMatchIn(it.first) }
        val fonts = anchors.filter { Regex("폰트|font", RegexOption.IGNORE_CASE).containsMatchIn(it.second) }
        val subs = anchors.filter { it !in fonts }
        fun withFonts(selected: List<Pair<String,String>>) = if (selected.isEmpty()) emptyList() else (selected + fonts).map { it.first }.distinct()
        val own = postEpisodes(post.title)
        if (own.any) return CommunityMatch(post, if (own.has(episode)) withFonts(subs) else emptyList(), episode, false)
        val labeled = subs.map { it to episodes(it.second) }.filter { it.second.any }
        if (labeled.isEmpty()) return CommunityMatch(post, withFonts(subs), episode, true)
        fun pick(number: Int) = labeled.firstOrNull { number in it.second.list } ?: labeled.firstOrNull { it.second.has(number) }
        val direct = pick(episode)
        val number = if (direct != null || !allowOffset) episode else offsets.map { episode + it }.firstOrNull { it > episode && pick(it) != null } ?: episode
        val chosen = direct ?: pick(number)
        val bundle = chosen != null && number !in chosen.second.list
        return CommunityMatch(post, chosen?.let { withFonts(listOf(it.first)) } ?: emptyList(), number, bundle, bundle = bundle)
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
    fun rank(posts: List<CommunityPost>, wantedTitle: String, episode: Int, offsets: List<Int>): List<CommunityMatch> {
        val season = DesktopTitleRules.season(wantedTitle)
        val usable = posts.filterNot { Regex("작업\\s*중|하차").containsMatchIn(it.title) }
        fun ranked(items: List<CommunityPost>, name: String, number: Int) = items.map { post ->
            val score = score(title(name), title(trailing.replace(post.title, " ")))
            links(post, number, offsets, season > 1).copy(score = score)
        }.filter { it.score >= .52 && it.links.isNotEmpty() }.sortedWith { a,b ->
            val difference = kotlin.math.floor((b.score - a.score) * 20 + .5).toInt()
            if (difference != 0) difference else a.strict.compareTo(b.strict)
        }
        val direct = ranked(usable.filter { (DesktopTitleRules.explicitSeason(title(it.title)) ?: 1) == season }, wantedTitle, episode)
        if (direct.isNotEmpty() || season < 2) return direct
        val unmarked = usable.filter { DesktopTitleRules.explicitSeason(title(it.title)) == null }
        val base = wantedTitle.replace(Regex("\\s*(?:\\d+\\s*기(?![가-힣])|season\\s*\\d+|시즌\\s*\\d+|\\d+(?:st|nd|rd|th)\\s*season)", RegexOption.IGNORE_CASE), " ")
        for (offset in offsets) {
            val number = episode + offset
            val found = ranked(unmarked, base, number).filter { it.episode == number && (postEpisodes(it.post.title).has(number) || !it.strict) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }
}
