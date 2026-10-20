package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup
import com.lilac.anime.shared.ported.HangulSimilarityMatcher
import kotlinx.serialization.Serializable

@Serializable
data class CommunityPost(val title: String, val url: String, val html: String)
/**
 * A post's download link with its text: [tier] 0 links named for the episode alone, 1 links covering it, 2 links naming
 * no episode; [exact] for a link named for the episode alone (or a post titled for it alone); [font] for a font link,
 * downloaded after the subtitle is found.
 */
data class CommunityLink(val href: String, val label: String, val tier: Int = 0, val exact: Boolean = false, val font: Boolean = false)
data class CommunityMatch(val post: CommunityPost, val links: List<String>, val episode: Int, val strict: Boolean, val score: Double = 0.0, val bundle: Boolean = false, val episodeNumber: Double = episode.toDouble(),
    val linkInfo: List<CommunityLink> = emptyList(), val fileSeason: Int? = null)

/** main.cjs/communityLinks and rankCommunityPosts, community-matching.cjs/planCommunityLinks. */
object DesktopCommunity {
    private val trailing = Regex("(?<!season|시즌|part|파트|vol\\.?|제)\\s+(\\d{1,3}(?:\\.\\d+)?)\\s*(?:\\((?:끝|완|完)\\))?\\s*(?:자막)?\\s*$", RegexOption.IGNORE_CASE)
    // Compiled once: ranking runs these over every post of a blog, and compiling them per call cost seconds.
    // "1~3화" and "1화~3화" are ranges; "1, 5화", "1+5화" and "1·5화" are lists.
    private val ranges = Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(?:화|회|편)?\\s*[~∼〜～–—-]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:화|회|편)")
    private val listed = Regex("(?<![\\d.])((?:\\d+(?:\\.\\d+)?\\s*[,、&/+·・]\\s*)*\\d+(?:\\.\\d+)?)\\s*(?:화|회|편)")
    private val listSeparator = Regex("[,、&/+·・]")
    private val episodeMark = Regex("\\bep(?:isode)?\\s*\\.?\\s*(\\d+(?:\\.\\d+)?)(?![\\d.])", RegexOption.IGNORE_CASE)
    private val numberJoin = Regex("\\d+(?:\\.\\d+)?\\s*(?:화|회|편)?\\s*[~∼〜～–—\\-,、&/+·・]\\s*(?=\\d)")
    private val titleNoise = Regex("\\d+(?:\\.\\d+)?\\s*(?:화|회|편)|\\bep(?:isode)?\\s*\\.?\\s*\\d+(?:\\.\\d+)?|\\((?:끝|완|完)\\)|작업\\s*중|블루레이판|자막", RegexOption.IGNORE_CASE)
    private val subtitleLink = Regex("drive\\.google\\.com|docs\\.google\\.com|\\.zip(?:$|\\?)|\\.(?:ass|ssa|srt|vtt|smi)(?:$|\\?)", RegexOption.IGNORE_CASE)
    private val fontLabel = Regex("폰트|font", RegexOption.IGNORE_CASE)
    private val tildeBlock = Regex("[~〜～][^~〜～]*[~〜～]")
    private val spaces = Regex("\\s+")
    private val longHangul = Regex("^[가-힣]{4,}$")
    private val unfinished = Regex("작업\\s*중|하차")
    private val seasonMark = Regex("\\s*(?:\\d+\\s*기(?![가-힣])|season\\s*\\d+|시즌\\s*\\d+|\\d+(?:st|nd|rd|th)\\s*season)", RegexOption.IGNORE_CASE)
    // A roman numeral at the end, or before a subtitle ("클레바테스Ⅱ ~마수왕과 가짜 용사 전승~", "시광대리인 Ⅱ -Link Click Ⅱ-").
    private val romanEnd = Regex("(?:^|\\s)(II|III|IV|V|VI)\\s*$", RegexOption.IGNORE_CASE)
    private val romanBefore = Regex("(?:^|[\\s가-힣])(II|III|IV|VI|V)(?=\\s*[~\\-(:])")
    private val romans = mapOf("II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6)
    private val seasonCount = Regex("(\\d+)\\s*기(?![가-힣])")
    private val bareDigit = Regex("[가-힣]\\s+([2-9])$")
    // Link texts: sizes Tistory shows ("6.3MB"), file extensions, bare ranges ("1-12 SubsPlease") and seasons ("S2 03-04").
    private val sizeText = Regex("\\d+(?:\\.\\d+)?\\s*[KMG]B\\b", RegexOption.IGNORE_CASE)
    private val sizeValue = Regex("(\\d+(?:\\.\\d+)?)\\s*([KMG])B\\b", RegexOption.IGNORE_CASE)
    private val fileExtension = Regex("\\.(?:zip|7z|rar|ass|ssa|srt|vtt|smi)$", RegexOption.IGNORE_CASE)
    private val bareRange = Regex("(?<![\\d.])(\\d{1,3})\\s*[~∼〜～–—-]\\s*(\\d{1,3})(?![\\d.])", RegexOption.IGNORE_CASE)
    private val seMark = Regex("(?:^|[^a-z])[se]$", RegexOption.IGNORE_CASE)
    private val linkSeasonMark = Regex("(\\d+)\\s*기(?![가-힣])|(?:^|[^a-z0-9])s(\\d{1,2})(?![\\d.])(?!\\s*[~∼〜～–—-]\\s*\\d)|season\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val finalPost = Regex("完|終|끝|완결|전체|통합|\\b(?:end|fin|complete)\\b", RegexOption.IGNORE_CASE)
    // Some makers put every episode so far into each new post's archive ("무리무리 10화" → "무리무리.zip 0.17MB",
    // "2화 [이전 화 포함].zip"). Tistory shows a file's size in its link: one episode's subtitles are 15-60 KB, so an
    // archive of 90 KB or more in a later episode's post may hold earlier ones, and is tried last.
    private val cumulativeLabel = Regex("포함|누적|전체|통합")
    data class Episodes(val list: List<Double>, val ranges: List<ClosedFloatingPointRange<Double>>) {
        val any get() = list.isNotEmpty() || ranges.isNotEmpty()
        fun has(value: Int) = has(value.toDouble())
        fun has(value: Double) = value in list || ranges.any { value in it }
    }
    data class LinkPlan(val tiers: List<List<CommunityLink>>, val fonts: List<CommunityLink>)
    fun episodes(input: String): Episodes {
        val value = normalizeDesktopTitle(input)
        val found = ranges.findAll(value).map { it.groupValues[1].toDouble()..it.groupValues[2].toDouble() }.filter { it.start <= it.endInclusive }.toList()
        val text = ranges.replace(value, " ")
        val list = listed.findAll(text).flatMap { it.groupValues[1].split(listSeparator).map { part -> part.trim().toDouble() } }.toMutableList()
        list += episodeMark.findAll(value).map { it.groupValues[1].toDouble() }
        return Episodes(list.distinct(), found)
    }
    private fun postEpisodes(title: String): Episodes = episodes(title).takeIf { it.any } ?: trailing.find(normalizeDesktopTitle(title))?.let { Episodes(listOf(it.groupValues[1].toDouble()), emptyList()) } ?: Episodes(emptyList(), emptyList())
    /** communityTitle: a title without episode numbers and post marks. */
    fun title(value: String): String = normalizeDesktopTitle(value).replace(numberJoin, "").replace(titleNoise, " ").trim()
    internal fun postTitle(value: String): String = title(trailing.replace(normalizeDesktopTitle(value), " "))
    /** communitySeason: the season a title marks, if any. */
    fun season(text: String): Int? {
        val value = normalizeDesktopTitle(text)
        DesktopTitleRules.explicitSeason(value)?.let { return it }
        val roman = romanEnd.find(value.trim()) ?: romanBefore.find(value)
        return roman?.let { romans[it.groupValues[1].uppercase()] }
    }
    /**
     * communitySeasons: every season a post title names: some makers keep a series in one post ("… 1기/2기/3기",
     * "클레바테스 1기 … / 2기 …"). A title naming none is the first season's.
     */
    fun seasons(text: String): Set<Int> {
        val listed = seasonCount.findAll(normalizeDesktopTitle(text)).map { it.groupValues[1].toInt() }.toSet()
        return if (listed.size > 1) listed else setOf(season(text) ?: 1)
    }
    /** Anissia also names a later season with a bare digit ("이세계 유유자적 농가 2"); in a post title such a digit is the episode. */
    internal fun bareDigitSeason(subject: String): Int? = bareDigit.find(normalizeDesktopTitle(subject))?.groupValues?.get(1)?.toInt()
    internal fun anissiaSeason(subject: String): Int = season(subject) ?: bareDigitSeason(subject) ?: 1

    /**
     * communityLinkEpisodes: episodes a download link's text names. Besides "3화" and "1~12화", makers label links with
     * bare ranges and file names ("1-12 SubsPlease", "sekamajo 01~02.zip 6.3MB", "Kanata kara - 03Subsplease.smi").
     */
    fun linkEpisodes(label: String): Episodes {
        val text = normalizeDesktopTitle(label).replace(sizeText, " ").trim().replace(fileExtension, "")
        val marked = episodes(text)
        if (marked.any) return marked
        // Not a season or episode mark's number ("S2 03-04" reads 3~4, "E01-02" nothing): desktop's lookbehind
        // (?<!(?:^|[^a-z])[se]), checked here as Kotlin/Native's regex has no start anchor inside a lookbehind.
        val range = bareRange.findAll(text).firstOrNull { !seMark.containsMatchIn(text.substring(0, it.range.first)) }
        if (range != null && range.groupValues[1].toInt() < range.groupValues[2].toInt()) return episodes("${range.groupValues[1].toInt()}~${range.groupValues[2].toInt()}화")
        val file = DesktopCommunityFiles.parse(text).episode
        return if (file == null) episodes("") else episodes("${file}화")
    }
    private fun declaredSize(label: String): Double = sizeValue.find(label)?.let {
        it.groupValues[1].toDouble() * when (it.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; else -> 1e9 }
    } ?: 0.0
    /** A season a link text names ("2기 1-12", "S2 03-04", "Season 3"). */
    private fun linkSeason(label: String): Int? = linkSeasonMark.find(normalizeDesktopTitle(label))?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)?.toInt()

    /**
     * planCommunityLinks: the order a post's subtitle links are tried in for an episode, as tiers: links named for it
     * alone, links whose range covers it, then links naming no episode ("통합", "자막 다운로드"). A link naming only other
     * episodes is left out (unless the post is titled for this episode alone), and so is a post titled for other episodes
     * unless it is a finished series' last post (完, 끝: its links often bundle every episode) or one of its links covers
     * the episode. Link texts are a hint only: the files inside decide (DesktopCommunityFiles.pick).
     */
    fun plan(title: String, titleEpisodes: Episodes, anchors: List<CommunityLink>, episode: Double, season: Int? = null): LinkPlan {
        var links = anchors
        // A series post may list every season's archives ("자판기 3기 1-2", "자판기 2기 1-12", "자판기 1-12"): those named for
        // another season are left out. When some are named for this one, the unnamed ones are the first season's: tried
        // after them for the first season, and not at all for a later one.
        if (season != null && season != 0) {
            val own = links.filter { linkSeason(it.label) == season }
            if (own.isNotEmpty() && own.size < links.size) {
                val named = plan(title, titleEpisodes, own, episode)
                if (season > 1) return named
                val rest = plan(title, titleEpisodes, links.filter { linkSeason(it.label) == null }, episode)
                return LinkPlan(named.tiers + rest.tiers, named.fonts + rest.fonts)
            }
            links = links.filter { (linkSeason(it.label) ?: season) == season }
        }
        val tiers = List(3) { mutableListOf<CommunityLink>() }
        val fonts = links.filter { fontLabel.containsMatchIn(it.label) }
        val titleNames = titleEpisodes.any; val titleHas = titleEpisodes.has(episode)
        val titleOnly = titleHas && titleEpisodes.list.size == 1 && titleEpisodes.ranges.isEmpty()
        val highest = (titleEpisodes.list + titleEpisodes.ranges.map { it.endInclusive }).maxOrNull()
        val lastPost = titleNames && !titleHas && finalPost.containsMatchIn(title) && highest != null && episode <= highest
        val laterPost = titleNames && !titleHas && titleEpisodes.list.size == 1 && titleEpisodes.ranges.isEmpty() && episode < titleEpisodes.list[0]
        fun cumulative(link: CommunityLink) = laterPost && (declaredSize(link.label) >= 90e3 || cumulativeLabel.containsMatchIn(link.label))
        for (link in links.filter { it !in fonts }) {
            val named = linkEpisodes(link.label)
            if (named.any) {
                // In a post titled for this episode alone, a link naming another number is still its file under the
                // maker's own numbering ("골든카무이 5기 13화" → "Golden Kamuy - 62.zip"), tried after the links that agree.
                if (!named.has(episode)) {
                    if (titleOnly) tiers[1] += link.copy(exact = named.list.size == 1 && named.ranges.isEmpty())
                    else if (cumulative(link) && named.list.size == 1 && named.list[0] == titleEpisodes.list[0]) tiers[2] += link.copy(exact = false)
                    continue
                }
                val alone = named.list.size == 1 && named.ranges.isEmpty()
                tiers[if (alone) 0 else 1] += link.copy(exact = alone)
            } else if (!titleNames || titleHas || lastPost) tiers[if (titleOnly) 0 else 2] += link.copy(exact = titleOnly)
            else if (cumulative(link)) tiers[2] += link.copy(exact = false)
        }
        return LinkPlan(tiers.filter { it.isNotEmpty() }, fonts)
    }

    /**
     * Picks the post's download links for one episode: per-episode posts (Kairan) carry the number in the title, series
     * posts (Csora) label each link ("13화", "1 ~ 12화") and add a separate "폰트" link. Each link keeps its text and tier
     * ([CommunityMatch.linkInfo]): the links are downloaded tier by tier.
     */
    fun links(post: CommunityPost, episode: Int, offsets: List<Int> = emptyList(), allowOffset: Boolean = false, episodeNumber: Double = episode.toDouble(), season: Int? = null): CommunityMatch {
        val anchors = Ksoup.parse(post.html, post.url).select("a[href]").map { CommunityLink(it.absUrl("href"), it.text().trim()) }
            .filter { subtitleLink.containsMatchIn(it.href) }.distinctBy { it.href }
        val own = postEpisodes(post.title)
        fun planFor(number: Double) = plan(post.title, own, anchors, number, season)
        fun named(found: LinkPlan) = found.tiers.any { tier -> tier.any { linkEpisodes(it.label).any } }
        var number = episodeNumber
        var found = planFor(episodeNumber)
        // Later seasons often continue the numbering (2기 = 13~24화) while the player counts from 1: a link named for the
        // continued number, when none is named for the episode itself.
        if (allowOffset && !own.any && !named(found)) for (offset in offsets) {
            val next = episodeNumber + offset
            if (next <= episodeNumber) continue
            val other = planFor(next)
            if (named(other)) { number = next; found = other; break }
        }
        if (found.tiers.isEmpty()) return CommunityMatch(post, emptyList(), number.toInt(), false, episodeNumber = number)
        val info = found.tiers.flatMapIndexed { index, tier -> tier.map { it.copy(tier = index) } } + found.fonts.map { it.copy(font = true) }
        // A range, a list or a link without a number: the file inside has to be matched by episode number.
        val bundle = own.ranges.isNotEmpty() || own.list.size > 1 || found.tiers.flatten().any { linkEpisodes(it.label).let { e -> e.ranges.isNotEmpty() || e.list.size > 1 } }
        val strict = bundle || found.tiers.first().first().exact.not()
        return CommunityMatch(post, info.map { it.href }, number.toInt(), strict, bundle = bundle, episodeNumber = number, linkInfo = info)
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
        fun bare(value: String) = value.replace(tildeBlock, " ").replace(spaces, " ").trim()
        val a = DesktopTitleRules.key(bare(first)); val b = DesktopTitleRules.key(bare(second))
        val short = if (a.length < b.length) a else b; val long = if (short == a) b else a
        var best = maxOf(titleScore(first, second), titleScore(bare(first), bare(second)))
        if (longHangul.matches(short) && long.startsWith(short)) best = maxOf(best, .6)
        val distance = HangulSimilarityMatcher.weightedEditDistance(a.replace("카구야", "가구야"), b.replace("카구야", "가구야"))
        val similarity = if (maxOf(a.length, b.length) == 0) 0.0 else 1 - distance / maxOf(a.length, b.length)
        if (similarity >= .75 && distance <= 1.5) best = maxOf(best, similarity)
        return best
    }
    /**
     * Posts of one blog ranked for a title and episode (best first). [season]: the season asked for (Anissia's own
     * title may give it); fileSeason, the season the title marks if any: an unmarked title may still be filed under a
     * later season by the maker (시광대리인 영도편 → "Link.Click.S03E01"), so its files are not filtered by season.
     */
    fun rank(posts: List<CommunityPost>, wantedTitle: String, episode: Int, offsets: List<Int>, episodeNumber: Double = episode.toDouble(), season: Int? = null): List<CommunityMatch> {
        val fileSeason = season ?: DesktopCommunity.season(wantedTitle) ?: DesktopTitleRules.season(wantedTitle).takeIf { it > 1 }
        val wantedSeason = fileSeason ?: 1
        val usable = posts.filterNot { unfinished.containsMatchIn(it.title) }
        fun ranked(items: List<CommunityPost>, name: String, number: Double): List<CommunityMatch> {
            val wanted = title(name)
            // Only a post whose title matches is worth parsing: parsing every post of a busy blog took seconds.
            return items.mapNotNull { post ->
                val score = score(wanted, postTitle(post.title))
                if (score < .52) null else links(post, number.toInt(), offsets, wantedSeason > 1, number, wantedSeason).copy(score = score, fileSeason = fileSeason)
            }.filter { it.links.isNotEmpty() }.sortedWith { a,b ->
                val difference = kotlin.math.floor((b.score - a.score) * 20 + .5).toInt()
                if (difference != 0) difference else a.strict.compareTo(b.strict)
            }
        }
        val direct = ranked(usable.filter { wantedSeason in seasons(postTitle(it.title)) }, wantedTitle, episodeNumber)
        if (direct.isNotEmpty() || wantedSeason < 2) return direct
        // Some makers number a later season on from the first ("정반대의 너와 나 15화" is 2기 3화) and drop the season
        // from the title. Such posts are matched under the series name with the previous seasons' episodes added,
        // and only when the number itself appears in the post (a title or a labelled link).
        val unmarked = usable.filter { DesktopCommunity.season(postTitle(it.title)) == null }
        val base = wantedTitle.replace(seasonMark, " ")
        for (offset in offsets) {
            val number = episodeNumber + offset
            val found = ranked(unmarked, base, number).filter { it.episodeNumber == number && (postEpisodes(it.post.title).has(number) || !it.strict) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }
}
