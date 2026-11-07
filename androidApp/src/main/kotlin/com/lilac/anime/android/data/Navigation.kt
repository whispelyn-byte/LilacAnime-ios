package com.lilac.anime.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.shared.Anime

data class DetailRoute(val anime: Anime, val source: String)

/** iOS DesktopNavigation: the header's section, the search's query and list mode, and the works opened over the page. */
class Navigation(context: Context) {
    private val defaults = context.getSharedPreferences("lilac", Context.MODE_PRIVATE)
    companion object {
        /** 설정 > 일반 > 시작 화면: 홈 · 전체 · 시청기록 · 내 목록. */
        val startSections = listOf("home" to "홈", "catalog" to "전체", "history" to "시청기록", "favorites" to "내 목록")
    }
    var startSection: String
        get() = defaults.getString("startView", "home").takeIf { value -> startSections.any { it.first == value } } ?: "home"
        set(value) { defaults.edit().putString("startView", value).apply() }

    var section by mutableStateOf(startSection)
    var query by mutableStateOf("")
    var listMode by mutableStateOf("browse")
    var showDownloads by mutableStateOf(false)
    /** The works opened from the page (iOS NavigationStack inside each page): the last one is shown. */
    val details = mutableStateListOf<DetailRoute>()
    /** The episode open in the full-screen player (iOS PlaybackRouter). */
    var playing by mutableStateOf<com.lilac.anime.android.player.PlaybackItem?>(null)
    fun play(item: com.lilac.anime.android.player.PlaybackItem) { playing = item }

    fun go(value: String) { details.clear(); section = value }
    fun search(value: String) { query = value; listMode = "browse"; go("search") }
    fun browse(mode: String) { query = ""; listMode = mode; go("search") }
    fun open(anime: Anime, source: String) { details.add(DetailRoute(anime, source)) }
    fun back(): Boolean = if (details.isEmpty()) false else { details.removeAt(details.lastIndex); true }

    fun string(key: String): String? = defaults.getString(key, null)
    fun putString(key: String, value: String) { defaults.edit().putString(key, value).apply() }
    fun bool(key: String) = defaults.getBoolean(key, false)
    fun putBool(key: String, value: Boolean) { defaults.edit().putBoolean(key, value).apply() }
}
