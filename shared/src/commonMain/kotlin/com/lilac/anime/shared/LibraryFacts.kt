package com.lilac.anime.shared

/** What a saved work has now (desktop 19110b2 libraryFacts, GitHub issue #28): its counts as its page gives them and
 *  [updatedAt], the day ("2026-11-10") of the latest episode that is out ("" when no episode is dated: 링크애니).
 *  -1 for a count the page does not give. */
data class LibraryFact(val availableEpisodes: Int, val totalEpisodes: Int, val episodes: Int, val status: String, val updatedAt: String)

/**
 * 내 목록 kept up to date (desktop 19110b2, issue #28). A saved work used to be the card of the day it was saved: its
 * episode count stood still (a show at 7화 still said 3화), and 회차 업데이트순 knew a date only for RE:Anime's and Miruro's
 * works that were in their recent lists, so the other sources' works followed in an order of their own. Each saved
 * work's page is read now and then (a show still airing every few hours, a finished one weekly; see [dueAfterMillis])
 * and the list is ordered by the day of each work's latest episode, whatever the source.
 */
object LibraryFacts {
    /** The sources whose works' pages are read for it (Linkkf has no update order). */
    val sources = listOf("reanime", "miruro", "animenosub", "ohli24", "linkani")
    /** The sources whose episode lists carry air dates (링크애니 dates none). */
    val dated = listOf("reanime", "miruro", "animenosub", "ohli24")
    private val isoDay = Regex("""\d{4}-\d{2}-\d{2}""")
    private val finishedStatus = Regex("finished|completed|ended|완결", RegexOption.IGNORE_CASE)

    /** The facts of a work from its page: the latest episode out by [nowMillis] (an episode listed ahead of its airing, or
     *  one that cannot be played yet, is not). Dubs and specials count: a new one is an update too. */
    fun of(detail: SourceDetail, nowMillis: Long): LibraryFact {
        val limit = kotlin.time.Instant.fromEpochMilliseconds(nowMillis + 12 * 60 * 60 * 1000L).toString().take(10)
        val latest = detail.servers.flatMap { it.episodes }.filter { it.playable }
            .map { it.airedDate.take(10) }.filter { isoDay.matches(it) && it <= limit }.maxOrNull().orEmpty()
        val anime = detail.anime
        return LibraryFact(anime.availableEpisodes ?: -1, anime.totalEpisodes ?: -1, -1, anime.status, latest)
    }
    /** The card as it is now: the saved one with the counts and status [fact] gives (a value it does not give stays). */
    fun shown(anime: Anime, fact: LibraryFact?): Anime {
        fact ?: return anime
        return anime.copy(
            availableEpisodes = fact.availableEpisodes.takeIf { it >= 0 } ?: anime.availableEpisodes,
            totalEpisodes = fact.totalEpisodes.takeIf { it >= 0 } ?: anime.totalEpisodes,
            status = fact.status.ifBlank { anime.status },
            updatedAt = fact.updatedAt.ifBlank { anime.updatedAt })
    }
    /** How long a work's facts are good for: a week for one that has all its episodes or is marked finished, else 3 hours. */
    fun dueAfterMillis(anime: Anime): Long {
        val uploaded = anime.availableEpisodes ?: 0; val total = anime.totalEpisodes ?: 0
        val finished = (total > 0 && uploaded >= total) || finishedStatus.containsMatchIn(anime.status)
        return if (finished) 7 * 24 * 60 * 60 * 1000L else 3 * 60 * 60 * 1000L
    }
    /** The day an update's time names ("2026-11-10T15:30:00Z" → "2026-11-10"), "" for none. */
    fun day(value: String): String = value.take(10).takeIf { isoDay.matches(it) }.orEmpty()
}
