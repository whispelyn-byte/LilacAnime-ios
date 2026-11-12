package com.lilac.anime.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.data.SavedAnime
import com.lilac.anime.android.data.WatchEntry
import com.lilac.anime.android.data.await
import com.lilac.anime.android.download.DownloadStore
import com.lilac.anime.shared.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The work's page (ott.css #detailDialog, iOS DetailView): a tall still fading into the page, the poster and title over
 *  it, the play · 내 목록 · 작품 정보 buttons, and the 회차 · 작품 정보 · 관련 작품 tabs. */
@Composable
fun DetailScreen(summary: Anime, source: String) {
    val app = LocalApp.current
    val library = app.library
    val navigation = app.navigation
    val context = LocalContext.current
    val wide = LilacStyle.wide
    val gutter = LilacStyle.gutter
    val service = remember { IosServices() }
    DisposableEffect(Unit) { onDispose { service.close() } }
    var detail by remember { mutableStateOf<Anime?>(null) }
    var servers by remember { mutableStateOf(emptyList<EpisodeServer>()) }
    var extras by remember { mutableStateOf<SourceExtras?>(null) }
    /** 작품 정보 더 보기 (desktop 19110b2): AniList's and MAL's extras, filled in when they come (null without an entry). */
    var anilist by remember { mutableStateOf<AnimeExtras?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true; error = null
        service.await<SourceDetail> { detail(summary, source, it) }
            .onSuccess { value ->
                detail = value.anime; servers = value.servers
                launch { com.lilac.anime.android.data.DesktopCatalog.enrich(value.anime, source, cast = true) }
                if (source == "linkkf") service.await<SourceExtras> { sourceExtras(value.anime, it) }.onSuccess { extras = it }
                launch { service.await<AnimeExtras> { animeExtras(value.anime, source, it) }.onSuccess { anilist = it } }
            }.onFailure { error = it.message }
        loading = false
    }
    // Linkkf counts a view after nine seconds on the page (app.js recordView).
    LaunchedEffect(detail?.id) {
        val anime = detail ?: return@LaunchedEffect
        if (anime.source != "linkkf") return@LaunchedEffect
        delay(9_000)
        service.await<SourceExtras> { recordView(anime, it) }.onSuccess { extras = it }
    }
    val anime = detail ?: summary
    var serverID by remember { mutableIntStateOf(0) }
    val server = servers.firstOrNull { it.id == serverID } ?: servers.firstOrNull { it.name == library.preferences.preferredServer } ?: servers.firstOrNull()
    var tab by remember { mutableIntStateOf(0) }
    val sortKey = "episodeNewestFirst:$source:${anime.id}"
    var newestFirst by remember { mutableStateOf(navigation.bool(sortKey)) }
    var page by remember { mutableIntStateOf(0) }
    val savedID = "$source:${anime.id}"

    fun episodeName(episode: Episode) = episode.displayNumber.ifEmpty { episode.number.toString() }
    /** The episode with the ones after and before it, to step through in the player (iOS DetailView.playback). */
    fun play(episodes: List<Episode>, episode: Episode) {
        val saved = SavedAnime.of(AnimeSnapshot.withEpisodes(anime, episodes), source)
        navigation.play(com.lilac.anime.android.player.PlaybackItem.inSeries(saved, episodes, episodes.indexOf(episode).coerceAtLeast(0)))
    }
    /** Detail 재생 (app.js resumeEpisode): the last played episode, resumed below 95%; without one, the first playable. */
    val resume: Triple<EpisodeServer, Int, Boolean>? = run {
        val last = library.history.firstOrNull { it.anime.id == savedID }
        fun resumable(entry: WatchEntry) = entry.position > 0 && (entry.duration <= 0 || entry.position / entry.duration < 0.95)
        if (last != null) {
            servers.forEach { s -> s.episodes.indexOfFirst { it.id == last.episodeID }.takeIf { it >= 0 }?.let { return@run Triple(s, it, resumable(last)) } }
            servers.forEach { s -> s.episodes.indexOfFirst { it.number == last.number && it.playable }.takeIf { it >= 0 }?.let { return@run Triple(s, it, resumable(last)) } }
        }
        server?.let { s -> s.episodes.indexOfFirst { it.playable }.takeIf { it >= 0 }?.let { Triple(s, it, false) } }
    }
    val heroHeight = if (wide) 460.dp else 300.dp
    // The wide header picture and the Korean title logo, asked from the card first, then with the details (iOS loadArtwork).
    var art by remember { mutableStateOf(TitleArtworkStore.cached(summary, source)) }
    LaunchedEffect(detail == null) {
        var found = TitleArtworkStore.artwork(summary, source)
        if (!found.complete) detail?.let { found = TitleArtworkStore.artwork(it, source) }
        if (found.complete || art?.complete != true) art = found
    }
    val background = LilacStyle.background
    val pageSize = if (source == "reanime") 100 else 50
    val ordered = server?.let { if (newestFirst) it.episodes.reversed() else it.episodes }.orEmpty()
    val pages = maxOf(1, (ordered.size + pageSize - 1) / pageSize)
    val current = minOf(page, pages - 1)
    val overview = DisplayText.plain(com.lilac.anime.android.data.DesktopCatalog.record(anime, source)?.overview?.ifEmpty { null } ?: anime.description)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    art?.banner?.takeIf { it.isNotEmpty() }?.let { AnimeArtwork(it, Modifier.matchParentSize()) }
                        ?: AnimeArtwork(anime.backdrop.ifEmpty { anime.poster }, Modifier.matchParentSize(), PosterFocus)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 0.68f to background.copy(alpha = 0.55f), 1f to background)))
                }
            }
            item {
                Column(Modifier.padding(horizontal = gutter).offset(y = if (wide) (-200).dp else (-130).dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (wide) 30.dp else 16.dp)) {
                        AnimeArtwork(anime.poster, Modifier.size(if (wide) 170.dp else 108.dp, if (wide) 255.dp else 162.dp)
                            .shadow(16.dp, RoundedCornerShape(LilacStyle.radius)).clip(RoundedCornerShape(LilacStyle.radius)))
                        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val logo = art?.takeIf { a -> a.logo(LilacStyle.palette.dark)?.let { TitleArtworkStore.image(it.first) } != null }
                            if (logo != null) {
                                TitleLogo(logo, if (wide) 440.dp else 230.dp, if (wide) 150.dp else 90.dp, Modifier.padding(bottom = 4.dp))
                                Text(com.lilac.anime.android.data.displayTitle(anime, source), fontSize = if (wide) 16.sp else 14.sp, fontWeight = FontWeight.Bold, color = LilacStyle.muted, maxLines = 2)
                            } else Text(com.lilac.anime.android.data.displayTitle(anime, source), fontSize = if (wide) 40.sp else 25.sp, lineHeight = if (wide) 44.sp else 29.sp, fontWeight = FontWeight.Black,
                                color = LilacStyle.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (anime.native.isNotEmpty()) Text(anime.native, fontSize = 14.sp, color = LilacStyle.muted, maxLines = 1)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (anime.score > 0) Text(scoreText(anime.score), color = LilacStyle.score, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(listOf(anime.format, anime.year, anime.status, anime.studios.firstOrNull() ?: "").filter { it.isNotEmpty() }.joinToString(" · "),
                                    color = LilacStyle.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            }
                            anilist?.let { airingLine(it) }?.let { Text(it, color = LilacStyle.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2) }
                        }
                    }
                    if (anime.genres.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        anime.genres.forEach { Text(it, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LilacStyle.text,
                            modifier = Modifier.background(LilacStyle.card2, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)) }
                    }
                    if (overview.isNotEmpty()) Text(overview, fontSize = 14.sp, lineHeight = 20.sp, color = LilacStyle.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    // ○화 재생 filled white, 내 목록 and 작품 정보 beside it; on a narrow screen the last two as icons.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        resume?.let { (s, index, again) ->
                            LilacPrimaryButton(episodeName(s.episodes[index]) + "화 " + (if (again) "이어보기" else "재생"), icon = Icons.Filled.PlayArrow) {
                                play(s.episodes, s.episodes[index])
                            }
                        }
                        val saved = library.contains(anime, source)
                        LilacGhostButton(if (wide) "내 목록" else "", icon = if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            tint = if (saved) LilacStyle.accent else null) { library.toggle(anime, source) }
                        val link = anime.detailUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        if (link != null) LilacGhostButton(if (wide) "작품 정보" else "", icon = Icons.AutoMirrored.Filled.OpenInNew) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReAnimeHost.live(link)))) }
                        }
                        anilist?.trailer?.takeIf { it.isNotEmpty() }?.let { trailer ->
                            LilacGhostButton(if (wide) "PV" else "", icon = Icons.Filled.Movie) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailer))) } }
                        }
                    }
                    // Tabs underlined in lilac (#detailDialog tabs).
                    Box {
                        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = LilacStyle.line)
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            listOf("회차", "작품 정보", "관련 작품").forEachIndexed { index, title ->
                                Column(Modifier.width(IntrinsicSize.Max).clickable { tab = index }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Text(title, fontSize = 14.sp, fontWeight = if (tab == index) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (tab == index) LilacStyle.text else LilacStyle.muted)
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == index) LilacStyle.accent else Color.Transparent))
                                }
                            }
                        }
                    }
                    if (loading) LilacProgress("회차를 불러오는 중…")
                    error?.let {
                        Column(Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(8.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(it, fontSize = 15.sp, color = LilacStyle.muted)
                            LilacOutlineButton("다시 시도") { attempt++ }
                        }
                    }
                    if (tab == 0 && server != null) {
                        // The count on the left, the tools on the right (app.js mountEpisodeList).
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically)) {
                                Text("회차", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LilacStyle.accent)
                                Text("${server.episodes.size}화", fontSize = 11.sp, color = LilacStyle.muted)
                            }
                            if (servers.size > 1) {
                                var open by remember { mutableStateOf(false) }
                                Box {
                                    LilacOutlineButton(server.name + " ▾") { open = true }
                                    DropdownMenu(open, { open = false }, containerColor = LilacStyle.card) {
                                        servers.forEach { s ->
                                            DropdownMenuItem(text = { Text(s.name, color = LilacStyle.text) }, onClick = {
                                                open = false; serverID = s.id; page = 0; library.update { it.copy(preferredServer = s.name) }
                                            })
                                        }
                                    }
                                }
                            }
                            LilacOutlineButton(if (newestFirst) "최신화순" else "오래된화순") { newestFirst = !newestFirst; navigation.putBool(sortKey, newestFirst); page = 0 }
                            // 전체 저장: every playable episode not saved yet (app.js batch download).
                            LilacOutlineButton("전체 저장", icon = Icons.Filled.CloudDownload) {
                                val saved = SavedAnime.of(AnimeSnapshot.withEpisodes(anime, server.episodes), source)
                                val targets = server.episodes.indices.filter { server.episodes[it].playable }.map { com.lilac.anime.android.player.PlaybackItem.inSeries(saved, server.episodes, it) }
                                    .filter { DownloadStore.state(it) == DownloadStore.EpisodeState.Idle }
                                DownloadStore.enqueue(targets)
                            }
                        }
                        if (pages > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(pages) { index ->
                                LilacChip(episodeName(ordered[index * pageSize]) + "–" + episodeName(ordered[minOf(ordered.size, (index + 1) * pageSize) - 1]), index == current) { page = index }
                            }
                        }
                    }
                    if (tab == 0 && server == null && !loading && error == null) {
                        LilacEmptyState(Icons.Outlined.PlayCircle, "현재 제공처에 영상이 없는 작품입니다", "설정에서 다른 콘텐츠 소스를 선택해 주세요.")
                    }
                    if (tab == 1) Information(anime, extras, overview, anilist)
                    if (tab == 2) Related(anime, extras, source, anilist)
                }
            }
            if (tab == 0 && server != null) {
                val watched = watchProgress(library.history, savedID, server.episodes)
                items(ordered.subList(current * pageSize, minOf(ordered.size, (current + 1) * pageSize)), key = { it.id }) { episode ->
                    // #detailDialog .episode-row.rich: one bordered card.
                    Row(
                        Modifier.padding(horizontal = gutter).offset(y = if (wide) (-200).dp else (-130).dp).padding(bottom = 8.dp).fillMaxWidth()
                            .background(LilacStyle.card2, RoundedCornerShape(LilacStyle.radius)).border(1.dp, LilacStyle.line, RoundedCornerShape(LilacStyle.radius))
                            .clip(RoundedCornerShape(LilacStyle.radius)).clickable(enabled = episode.playable) { play(server.episodes, episode) }
                            .graphicsLayer { alpha = if (episode.playable) 1f else 0.5f }.padding(vertical = 12.dp).padding(start = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(episodeName(episode), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.accent, maxLines = 1, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (episode.title.isEmpty() || episode.title == "Episode ${episode.number}") episodeName(episode) + "화" else episode.title,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (episode.nativeTitle.isNotEmpty()) Text(episode.nativeTitle, fontSize = 11.sp, color = LilacStyle.muted, maxLines = 1)
                            val badges = listOf(if (episode.dubbed) "더빙" else "", if (episode.isFiller) "FILLER" else "", if (episode.isRecap) "RECAP" else "",
                                episode.airedDate.substringBefore("T"), if (episode.playable) "" else "재생 불가").filter { it.isNotEmpty() }
                            if (badges.isNotEmpty()) Text(badges.joinToString(" · "), fontSize = 11.sp, color = LilacStyle.muted)
                            // 본 회차 (desktop 8065f4a): checked from 90%, how far a started one got with a bar.
                            watched[episode.id]?.takeIf { it > 0 }?.let { percent ->
                                Text(if (percent >= 90) "✓ 시청함" else "${percent.toInt()}% 시청", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (percent >= 90) LilacStyle.accent else LilacStyle.muted)
                                if (percent < 90) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)).background(LilacStyle.line)) {
                                    Box(Modifier.fillMaxWidth((percent / 100).toFloat()).fillMaxHeight().background(LilacStyle.accent))
                                }
                            }
                        }
                        // The episode's download, inside its card: save, stop, resume or delete.
                        val target = com.lilac.anime.android.player.PlaybackItem.of(SavedAnime.of(AnimeSnapshot.withEpisodes(anime, server.episodes), source), episode)
                        val state = DownloadStore.state(target)
                        Icon(when (state) {
                            DownloadStore.EpisodeState.Completed -> Icons.Filled.Delete; DownloadStore.EpisodeState.Active -> Icons.Filled.Cancel
                            DownloadStore.EpisodeState.Stopped -> Icons.Filled.Refresh; DownloadStore.EpisodeState.Idle -> Icons.Filled.Download
                        }, when (state) {
                            DownloadStore.EpisodeState.Completed -> "다운로드 삭제"; DownloadStore.EpisodeState.Active -> "다운로드 취소"
                            DownloadStore.EpisodeState.Stopped -> "다운로드 다시 시작"; DownloadStore.EpisodeState.Idle -> "다운로드"
                        }, tint = if (state == DownloadStore.EpisodeState.Completed) LilacStyle.accent else LilacStyle.muted,
                            modifier = Modifier.size(38.dp).clip(CircleShape).clickable(enabled = episode.playable) { DownloadStore.toggle(target) }.padding(9.dp))
                    }
                }
            }
        }
        // The back button over the still (iOS navigation bar back).
        Box(Modifier.padding(12.dp).size(38.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).clip(CircleShape).clickable { navigation.back() },
            contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Information(anime: Anime, extras: SourceExtras?, overview: String, anilist: AnimeExtras?) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(8.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("작품 소개", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
        Text(overview.ifEmpty { "등록된 소개가 없습니다." }, fontSize = 15.sp, lineHeight = 22.sp, color = LilacStyle.muted)
        HorizontalDivider(color = LilacStyle.line)
        extras?.views?.takeIf { it.isNotEmpty() }?.let { Text("조회 $it", fontSize = 12.sp, color = LilacStyle.muted) }
        anime.detailUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { link ->
            Row(Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReAnimeHost.live(link)))) } }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = LilacStyle.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("사이트에서 작품 정보 보기", fontSize = 15.sp, color = LilacStyle.accent)
            }
        }
        listOf("원제" to anime.native, "로마자" to anime.romaji, "영문명" to anime.english, "방영" to anime.airedDate,
            "제작" to anime.studios.joinToString(", "), "장르" to anime.genres.joinToString(" · "), "비고" to anime.note).forEach { (label, value) ->
            if (value.isNotEmpty()) Row {
                Text(label, fontSize = 15.sp, color = LilacStyle.muted, modifier = Modifier.width(60.dp))
                Text(value, fontSize = 15.sp, color = LilacStyle.text)
            }
        }
        anilist?.let { AnilistInformation(it) }
    }
}

/** 다음 화 · 8화 일본 방영 11월 13일 (금) 오후 10:30 (2일 후), while one is announced. */
private fun airingLine(extras: AnimeExtras): String? {
    if (extras.nextAiringAt <= 0 || extras.nextEpisode <= 0) return null
    val at = java.time.Instant.ofEpochSecond(extras.nextAiringAt); val left = at.toEpochMilli() - System.currentTimeMillis()
    if (left < -60_000) return null
    val when_ = java.time.format.DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", java.util.Locale.KOREAN).format(at.atZone(java.time.ZoneId.systemDefault()))
    val hours = left / 3_600_000
    val soon = if (left < 3_600_000) "곧" else if (hours < 24) "${hours}시간 후" else "${hours / 24}일 후"
    return "다음 화 · ${extras.nextEpisode}화 일본 방영 $when_ ($soon)"
}

/** How far each listed episode was watched (episode id -> 0–100), from 시청 기록: the same episode, or for an entry of this
 *  work whose episode is not in the list (the phone's, an older list) the episode of the same number (desktop 8065f4a). */
private fun watchProgress(history: List<WatchEntry>, work: String, episodes: List<Episode>): Map<String, Double> {
    val ids = episodes.map { it.id }.toSet(); val progress = mutableMapOf<String, Double>()
    for (entry in history) {
        if (entry.anime.id != work) continue
        val id = if (entry.episodeID in ids) entry.episodeID else episodes.firstOrNull { it.number == entry.number && !it.dubbed }?.id ?: continue
        progress[id] = maxOf(progress[id] ?: 0.0, entry.watchedPercent)
    }
    return progress
}

/** 캐릭터 · 성우, the songs (MAL), tags and sites: AniList's and MAL's part of 작품 정보. */
@Composable
private fun AnilistInformation(extras: AnimeExtras) {
    val context = LocalContext.current
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    if (extras.characters.isNotEmpty()) {
        HorizontalDivider(color = LilacStyle.line)
        Text("캐릭터 · 성우", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
        extras.characters.forEach { character ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimeArtwork(character.image, Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(6.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(character.native.ifEmpty { character.name }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 1)
                    val role = when (character.role) { "MAIN" -> "주연"; "SUPPORTING" -> "조연"; else -> "" }
                    listOf(if (character.native.isEmpty()) "" else character.name, role).filter { it.isNotEmpty() }.joinToString(" · ")
                        .takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 11.sp, color = LilacStyle.muted, maxLines = 1) }
                    character.voiceNative.ifEmpty { character.voice }.takeIf { it.isNotEmpty() }?.let { Text("CV $it", fontSize = 11.sp, color = LilacStyle.muted, maxLines = 1) }
                }
            }
        }
    }
    listOf("오프닝 곡" to extras.openings, "엔딩 곡" to extras.endings).forEach { (title, songs) ->
        if (songs.isNotEmpty()) {
            HorizontalDivider(color = LilacStyle.line)
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
            songs.forEach { Text("• $it", fontSize = 14.sp, color = LilacStyle.muted) }
        }
    }
    if (extras.tags.isNotEmpty()) {
        HorizontalDivider(color = LilacStyle.line)
        Text("태그", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            extras.tags.forEach { Text(it, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LilacStyle.text,
                modifier = Modifier.background(LilacStyle.card2, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)) }
        }
    }
    val links = listOfNotNull(extras.url.takeIf { it.isNotEmpty() }?.let { "AniList" to it },
        extras.malId.takeIf { it > 0 }?.let { "MyAnimeList" to "https://myanimelist.net/anime/$it" }) + extras.links.map { it.site to it.url }
    if (links.isNotEmpty()) {
        HorizontalDivider(color = LilacStyle.line)
        Text("공식 · 정보 사이트", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (site, url) -> Text(site, fontSize = 14.sp, color = LilacStyle.accent, modifier = Modifier.clickable { open(url) }) }
        }
    }
}

/** AniList's related works (for the sources that list none of their own) and the works recommended with this one. They are
 *  not works of the source playing: one picked is searched for on it. */
@Composable
private fun AnilistRelated(extras: AnimeExtras, source: String) {
    val navigation = LocalApp.current.navigation
    val groups = mutableListOf<Pair<String, List<ExtraWork>>>()
    if (source !in listOf("reanime", "miruro", "linkkf")) extras.relations.groupBy { extras.relationName(it.relationType) }.forEach { (name, works) -> groups += name to works }
    if (extras.recommendations.isNotEmpty()) groups += "비슷한 작품" to extras.recommendations
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        groups.forEach { (name, works) ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LilacStyle.accent)
                Text("AniList · 누르면 지금 소스에서 검색", fontSize = 11.sp, color = LilacStyle.muted)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                works.forEach { work ->
                    Column(Modifier.width(120.dp).clickable { navigation.search(work.title) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            AnimeArtwork(work.image, Modifier.size(120.dp, 170.dp).clip(RoundedCornerShape(LilacStyle.radius)))
                            if (work.score > 0) Text("★ ${work.score}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                        Text(work.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(work.year, work.format.replace('_', ' ')).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 11.sp, color = LilacStyle.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Related(anime: Anime, extras: SourceExtras?, source: String, anilist: AnimeExtras?) {
    val navigation = LocalApp.current.navigation
    @Composable fun grid(cells: List<@Composable () -> Unit>) = BoxWithConstraints {
        val columns = maxOf(1, ((maxWidth + 12.dp) / (150.dp + 12.dp)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { Box(Modifier.weight(1f)) { it() } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    val anilistGroups = anilist != null && (anilist.recommendations.isNotEmpty() || (source !in listOf("reanime", "miruro", "linkkf") && anilist.relations.isNotEmpty()))
    when {
        extras != null && extras.related.isNotEmpty() -> grid(extras.related.map { related -> @Composable { AnimePosterCard(related) { navigation.open(related, source) } } })
        anime.reAnimeRelated.isEmpty() -> if (!anilistGroups) LilacEmptyState(Icons.Outlined.ViewAgenda, "관련 작품이 없습니다", "관련 작품 정보가 제공되면 여기에 표시됩니다.")
        else -> grid(anime.reAnimeRelated.map { relation ->
            @Composable {
                val related = AnimeSnapshot.related(relation)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnimePosterCard(related) { navigation.open(related, if (source == "miruro") "miruro" else "reanime") }
                    Text(relation.relationType, fontSize = 12.sp, color = LilacStyle.accent)
                }
            }
        })
    }
    if (anilist != null && anilistGroups) {
        Spacer(Modifier.height(16.dp))
        AnilistRelated(anilist, source)
    }
}
