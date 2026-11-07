package com.lilac.anime.android.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.data.CatalogModel
import com.lilac.anime.android.data.WatchEntry
import com.lilac.anime.android.data.await
import com.lilac.anime.shared.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/** 추천 (desktop app.js heroSelection, iOS HomeSpotlight): five works a day, taken in turn from this season, what is on
 *  air and the popular list, each with its own label; works in 시청기록 left out. */
object HomeSpotlight {
    data class Pick(val anime: Anime, val label: String)
    fun today(now: Long = System.currentTimeMillis()): Int {
        val offset = java.util.TimeZone.getDefault().getOffset(now)
        return floor((now + offset) / 86_400_000.0).toInt()
    }
    fun picks(season: List<Anime>, airing: List<Anime>, top: List<Anime>, watchedIDs: Set<String>, watchedTitles: Set<String>, day: Int): List<Pick> {
        val fresh = { anime: Anime -> anime.id !in watchedIDs && anime.title !in watchedTitles }
        val pools = listOf(season to "이번 시즌 신작", airing to "지금 방영 중", top to "인기 작품").mapIndexedNotNull { index, (list, label) ->
            val art = list.filter { it.poster.isNotEmpty() || it.backdrop.isNotEmpty() }
            val unseen = art.filter(fresh)
            val items = shuffled(if (unseen.size >= 3) unseen else art, (day * 7 + index).toUInt())
            if (items.isEmpty()) null else label to items
        }
        val picks = mutableListOf<Pick>(); val seen = mutableSetOf<String>()
        var round = 0
        while (picks.size < 5 && pools.any { it.second.size > round }) {
            for ((label, items) in pools) if (picks.size < 5 && round < items.size && seen.add(items[round].id)) picks += Pick(items[round], label)
            round++
        }
        return picks
    }
    /** A shuffle of the list's first 40, seeded by the day and the list (mulberry32, as the desktop). */
    fun <T> shuffled(list: List<T>, seed: UInt): List<T> {
        var state = seed
        fun random(): Double {
            state += 0x6D2B79F5u
            var t = (state xor (state shr 15)) * (1u or state)
            t = (t + ((t xor (t shr 7)) * (61u or t))) xor t
            return (t xor (t shr 14)).toDouble() / 4_294_967_296.0
        }
        val out = list.take(40).toMutableList()
        if (out.size < 2) return out
        for (i in out.size - 1 downTo 1) { val j = (random() * (i + 1)).toInt(); val v = out[i]; out[i] = out[j]; out[j] = v }
        return out
    }
}

/** Opens a watched episode in the player (iOS PlaybackButton with PlaybackItem(entry:)). */
@Composable
fun rememberPlayback(): (WatchEntry) -> Unit {
    val navigation = LocalApp.current.navigation
    return remember { { entry: WatchEntry -> navigation.play(com.lilac.anime.android.player.PlaybackItem.of(entry)) } }
}

/** The desktop home (ott.css #homeView), as iOS HomeView: the spotlight across the top, then rows of posters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val library = app.library
    val navigation = app.navigation
    val source = library.preferences.source
    val gutter = LilacStyle.gutter
    val model = remember { CatalogModel() }
    DisposableEffect(Unit) { onDispose { model.close() } }
    val service = remember { IosServices() }
    DisposableEffect(Unit) { onDispose { service.close() } }
    var sections by remember { mutableStateOf(emptyList<SourceSection>()) }
    var airing by remember { mutableStateOf(emptyList<Anime>()) }
    var homeError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source, reload) {
        model.source = source; model.mode = "browse"; model.query = ""; model.load()
        homeError = null
        launch {
            service.await<List<Anime>> { sourceSchedule(source, (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7, it) }
                .onSuccess { airing = it }.onFailure { if (airing.isEmpty()) homeError = it.message }
        }
        service.await<List<SourceSection>> { sourceSections(source, it) }
            .onSuccess { sections = it }.onFailure { if (sections.isEmpty()) homeError = it.message }
        refreshing = false
    }
    val items = model.items
    val picks = remember(sections, airing, items, library.history) {
        val season = sections.firstOrNull { it.name == "이번 시즌" }?.items.orEmpty()
        HomeSpotlight.picks(if (season.isEmpty()) items else season, airing, sections.firstOrNull { it.name == "인기 작품" }?.items.orEmpty(),
            library.history.map { it.anime.anime.id }.toSet(), library.history.map { it.anime.title }.toSet(), HomeSpotlight.today())
    }
    var featured by remember { mutableIntStateOf(0) }
    LaunchedEffect(featured, picks.size) {
        if (picks.size < 2) return@LaunchedEffect
        delay(9_000); featured = (minOf(featured, picks.size - 1) + 1) % picks.size
    }
    val playback = rememberPlayback()

    PullToRefreshBox(refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            if (picks.isNotEmpty()) HomeHero(picks, featured, source, onIndex = { featured = it })
            else if (model.loading) Box(Modifier.fillMaxWidth().height(420.dp).background(LilacStyle.card), contentAlignment = Alignment.Center) { LilacProgress("작품을 불러오는 중…") }
            val continuing = remember(library.history) { library.history.distinctBy { it.anime.id }.take(6) }
            if (continuing.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LilacSectionTitle("계속 시청하기", Modifier.padding(horizontal = gutter)) { navigation.go("history") }
                    LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(continuing, key = { it.id }) { entry -> ContinueCard(entry, if (LilacStyle.wide) 300 else 230) { playback(entry) } }
                    }
                }
            }
            // 이번 시즌 신작 / 최근 업데이트, kept across launches; a source without update lists shows 이번 시즌.
            var savedOrder by remember { mutableStateOf(navigation.string("homeOrder") ?: "season") }
            val homeOrder = if (savedOrder == "updated" && source in RecentUpdates.supported) "updated" else "season"
            LaunchedEffect(source, homeOrder) { if (homeOrder == "updated") RecentUpdates.loadHome(source) }
            if (source in RecentUpdates.supported) {
                LilacChipTray(Modifier.padding(horizontal = gutter)) {
                    listOf("season" to "이번 시즌 신작", "updated" to "최근 업데이트").forEach { (value, title) ->
                        LilacChip(title, homeOrder == value) { savedOrder = value; navigation.putString("homeOrder", value) }
                    }
                }
                if (homeOrder == "updated") {
                    Text(RecentUpdates.homeNote, fontSize = 12.sp, color = LilacStyle.muted, modifier = Modifier.padding(horizontal = gutter))
                    AnimeRail("최근 업데이트", RecentUpdates.home, source) { navigation.putString("allSort:$source", "updated"); navigation.go("catalog") }
                }
            }
            sections.filter { !(homeOrder == "updated" && it.name == "이번 시즌") }.forEach { section ->
                if (section.items.isNotEmpty()) AnimeRail(section.name, section.items, source) {
                    navigation.browse(when (section.name) { "인기 작품" -> "top"; "이번 시즌" -> "season"; "PV" -> "pv"; "극장판" -> "movie"; else -> "adult" })
                }
            }
            if (source == "linkkf" && items.isNotEmpty()) AnimeRail("최신 애니메이션", items, source) { navigation.go("catalog") }
            if (source != "linkkf" && airing.isNotEmpty()) AnimeRail("방영 중", airing, source) { navigation.browse("schedule") }
            if (library.favorites.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LilacSectionTitle("내 목록", Modifier.padding(horizontal = gutter)) { navigation.go("favorites") }
                    LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(library.favorites.take(10), key = { it.id }) { saved ->
                            AnimePosterCard(saved.anime, width = if (LilacStyle.wide) 170.dp else 130.dp, height = if (LilacStyle.wide) 255.dp else 195.dp) { navigation.open(saved.anime, saved.source) }
                        }
                    }
                }
            }
            val error = model.error ?: homeError
            if (error != null && items.isEmpty() && sections.isEmpty()) {
                Column(Modifier.padding(horizontal = gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LilacEmptyState(Icons.Filled.WifiOff, "작품을 불러오지 못했어요", error)
                    LilacPrimaryButton("다시 시도") { reload++ }
                }
            } else if (items.isEmpty() && sections.isEmpty() && !model.loading) {
                LilacEmptyState(Icons.Outlined.AutoAwesome, "새로운 작품을 찾아보세요", "다른 영상 소스를 선택하거나 탐색에서 검색해 보세요.")
            }
            // The weekly schedule last, under the rails.
            val scheduleKnown = items + airing + sections.flatMap { it.items } + library.favorites.filter { it.source == source }.map { it.anime }
            WeeklyScheduleView(source, scheduleKnown, reload, Modifier.padding(horizontal = gutter))
            LilacOutlineButton("전체 작품 둘러보기", Modifier.padding(horizontal = gutter).fillMaxWidth(), icon = Icons.Filled.GridView) { navigation.go("search") }
        }
    }
}

/** Continue watching (ott.css .continue-card): a wide still with the progress inside it, the title and episode under. */
@Composable
fun ContinueCard(entry: WatchEntry, width: Int, onClick: () -> Unit) {
    val progress = entry.progress
    Column(Modifier.width(width.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(LilacStyle.radius))) {
            AnimeArtwork(entry.anime.anime.backdrop.ifEmpty { entry.anime.poster }, Modifier.matchParentSize(), PosterFocus)
            Box(Modifier.align(Alignment.Center).size(44.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
            }
            StillProgress(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(com.lilac.anime.android.data.displayTitle(entry.anime.anime, entry.anime.source), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        val origin = if (entry.directURL?.startsWith("file:") == true) "다운로드" else ContentSources.name(entry.anime.source)
        Text(listOf(entry.episodeTitle, "${Math.round(progress * 100)}%", origin).joinToString(" · "), fontSize = 12.sp, color = LilacStyle.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun AnimeRail(title: String, items: List<Anime>, source: String, seeAll: (() -> Unit)? = null) {
    val navigation = LocalApp.current.navigation
    val wide = LilacStyle.wide
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LilacSectionTitle(title, Modifier.padding(horizontal = LilacStyle.gutter), seeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = LilacStyle.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { anime ->
                AnimePosterCard(anime, width = if (wide) 170.dp else 130.dp, height = if (wide) 255.dp else 195.dp) {
                    navigation.open(anime, if (anime.source == "jikan") "jikan" else source)
                }
            }
        }
    }
}

/** The desktop home's spotlight (ott.css .hero): the shown poster across the width under the page colour coming in from
 *  the left and the bottom, the copy on the left, and on a wide screen the picks on the right. */
@Composable
private fun HomeHero(picks: List<HomeSpotlight.Pick>, index: Int, source: String, onIndex: (Int) -> Unit) {
    val app = LocalApp.current
    val wide = LilacStyle.wide
    val current = minOf(maxOf(index, 0), picks.size - 1)
    val pick = picks[current]
    val anime = pick.anime
    val detailSource = if (anime.source == "jikan") "jikan" else source
    val height = if (wide) 520.dp else 420.dp
    val background = LilacStyle.background
    val ink = if (LilacStyle.palette.dark) Color.White else LilacStyle.text
    // Every pick's artwork is asked ahead, so a pick switched to shows its banner and logo at once (iOS HomeHero).
    var ready by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(picks.map { it.anime.id }) {
        for (candidate in picks) { TitleArtworkStore.artwork(candidate.anime, if (candidate.anime.source == "jikan") "jikan" else source); ready = ready + candidate.anime.id }
    }
    val art = if (anime.id in ready) TitleArtworkStore.cached(anime, detailSource) else null
    Box(
        Modifier.fillMaxWidth().height(height).pointerInput(picks.size) {
            var total = 0f
            detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                if (picks.size > 1 && abs(total) > 50) onIndex((current + if (total < 0) 1 else picks.size - 1) % picks.size)
            }) { _, amount -> total += amount }
        },
    ) {
        AnimatedContent(anime.id, transitionSpec = { fadeIn(LilacMotion.spec(280)) togetherWith fadeOut(LilacMotion.spec(280)) }, label = "hero") { _ ->
            Box(Modifier.fillMaxSize()) {
                art?.banner?.takeIf { it.isNotEmpty() }?.let { AnimeArtwork(it, Modifier.fillMaxSize()) }
                    ?: AnimeArtwork(anime.backdrop.ifEmpty { anime.poster }, Modifier.fillMaxSize(), PosterFocus)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, 0.26f to background.copy(alpha = 0.8f), 0.55f to background.copy(alpha = 0.25f), 0.75f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 0.78f to background.copy(alpha = 0.4f), 1f to background)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to background.copy(alpha = 0.6f), 0.2f to Color.Transparent)))
            }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = LilacStyle.gutter).padding(bottom = if (wide) 52.dp else 24.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.then(if (wide) Modifier.widthIn(max = 540.dp) else Modifier.weight(1f))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                    Text(pick.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LilacStyle.accent)
                    if (picks.size > 1) {
                        Box(Modifier.size(22.dp, 1.dp).background(ink.copy(alpha = 0.6f)))
                        Text("%02d / %02d".format(current + 1, picks.size), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.6f))
                    }
                }
                // The Korean title logo, with the text title kept small under it (it tells the season).
                if (art != null && art.logo(LilacStyle.palette.dark) != null && TitleArtworkStore.image(art.logo(LilacStyle.palette.dark)!!.first) != null) {
                    TitleLogo(art, if (wide) 480.dp else 300.dp, if (wide) 170.dp else 96.dp, Modifier.padding(bottom = 10.dp))
                    Text(com.lilac.anime.android.data.displayTitle(anime, detailSource), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, modifier = Modifier.padding(bottom = 10.dp))
                } else Text(com.lilac.anime.android.data.displayTitle(anime, detailSource), fontSize = if (wide) 52.sp else 30.sp, fontWeight = FontWeight.Black, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    lineHeight = if (wide) 56.sp else 34.sp, modifier = Modifier.padding(bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    if (anime.score > 0) Text(scoreText(anime.score), color = LilacStyle.score, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (anime.year.isNotEmpty()) Text(anime.year, color = ink.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (anime.format.isNotEmpty()) Text(anime.format, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.85f),
                        modifier = Modifier.border(1.dp, ink.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 1.dp))
                    val episodes = DesktopAnimeMetadata.episodeLabel(anime)
                    if (episodes.isNotEmpty()) Text(episodes, color = ink.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                val overview = DisplayText.plain(com.lilac.anime.android.data.DesktopCatalog.record(anime, detailSource)?.overview?.ifEmpty { null } ?: anime.description)
                if (overview.isNotEmpty()) Text(overview, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = ink.copy(alpha = 0.9f), modifier = Modifier.padding(bottom = 20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LilacPrimaryButton("자세히 보기", icon = Icons.Outlined.Info) { app.navigation.open(anime, detailSource) }
                    val saved = app.library.contains(anime, detailSource)
                    LilacGhostButton("내 목록", icon = if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder) { app.library.toggle(anime, detailSource) }
                }
            }
            if (wide && picks.size > 1) {
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    picks.forEachIndexed { offset, candidate ->
                        val active = offset == current
                        AnimeArtwork(candidate.anime.poster, Modifier.size(if (active) 200.dp else 92.dp, if (active) 300.dp else 138.dp)
                            .graphicsLayer { alpha = if (active) 1f else 0.5f }.clip(RoundedCornerShape(LilacStyle.radius)).clickable { onIndex(offset) })
                    }
                }
            }
        }
    }
}
