package com.lilac.anime.shared
import com.lilac.anime.shared.ported.*

object DesktopEpisodeRules {
    private val season = Regex("(?:\\d+\\s*기|(?:season|시즌)\\s*\\d+|\\d+(?:st|nd|rd|th)\\s*season|\\bS\\d+E\\d+)", RegexOption.IGNORE_CASE)
    fun findPost(title: String, episode: Int, posts: List<KairanPost>, episodeKey: String, offsets: List<Int>): KairanMatch? {
        KairanPostMatcher.findBestMatch(title, episode, posts, episodeKey)?.let { return it }
        if (DesktopTitleRules.season(title) < 2) return null
        val bare = title.replace(season, "").trim()
        val continued = posts.filter { !season.containsMatchIn(it.title) }
        return offsets.filter { it > 0 }.distinct().mapNotNull { offset ->
            KairanPostMatcher.findBestMatch(bare, episode + offset, continued, (episode + offset).toString())
        }.maxByOrNull { it.similarity }
    }
}
