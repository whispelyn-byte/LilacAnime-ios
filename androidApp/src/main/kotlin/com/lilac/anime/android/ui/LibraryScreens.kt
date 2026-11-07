package com.lilac.anime.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.data.Navigation
import com.lilac.anime.android.data.WatchEntry

/** 시청기록 (desktop historyView, iOS DesktopHistoryView): wide stills in a grid, the progress inside them, the title and
 *  episode under them; 선택 picks several to remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen() {
    val app = LocalApp.current
    val library = app.library
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var clear by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val playback = rememberPlayback()
    LazyVerticalGrid(
        GridCells.Adaptive(if (LilacStyle.wide) 220.dp else 150.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = LilacStyle.gutter, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        full {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("시청기록", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LilacStyle.text, modifier = Modifier.padding(end = 8.dp))
                    if (selecting) {
                        LilacOutlineButton("전체 선택") { selected = library.history.map { it.id }.toSet() }
                        LilacOutlineButton("삭제", tint = Color.Red, enabled = selected.isNotEmpty()) { library.deleteHistory(selected); selected = emptySet(); selecting = false }
                    }
                    LilacOutlineButton(if (selecting) "취소" else "선택", enabled = library.history.isNotEmpty()) { selecting = !selecting; selected = emptySet() }
                    if (!selecting) LilacOutlineButton("전체 삭제", tint = Color.Red, enabled = library.history.isNotEmpty()) { clear = true }
                }
                if (selecting) Text("${selected.size}개 선택", fontSize = 12.sp, color = LilacStyle.muted)
                if (library.history.isEmpty()) LilacEmptyState(Icons.Outlined.Schedule, "시청 기록이 없습니다", "재생한 작품이 여기에 표시됩니다.")
            }
        }
        items(library.history, key = { it.id }) { entry ->
            Box {
                HistoryCard(entry, selecting, entry.id in selected, Modifier.combinedClickable(
                    onClick = { if (selecting) selected = if (entry.id in selected) selected - entry.id else selected + entry.id else playback(entry) },
                    onLongClick = { if (!selecting) menuFor = entry.id },
                ))
                DropdownMenu(menuFor == entry.id, { menuFor = null }, containerColor = LilacStyle.card) {
                    DropdownMenuItem(text = { Text("기록에서 삭제", color = Color.Red) }, onClick = { menuFor = null; library.deleteHistory(setOf(entry.id)) })
                }
            }
        }
    }
    if (clear) AlertDialog(
        onDismissRequest = { clear = false }, containerColor = LilacStyle.card,
        title = { Text("시청 기록을 모두 삭제할까요?", color = LilacStyle.text) },
        confirmButton = { TextButton({ library.clearHistory(); selected = emptySet(); clear = false }) { Text("전체 삭제", color = Color.Red) } },
        dismissButton = { TextButton({ clear = false }) { Text("취소", color = LilacStyle.accent) } },
    )
}

@Composable
private fun HistoryCard(entry: WatchEntry, selecting: Boolean, selected: Boolean, modifier: Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            AnimeArtwork(entry.anime.anime.backdrop.ifEmpty { entry.anime.poster }, Modifier.matchParentSize(), PosterFocus)
            Box(Modifier.align(Alignment.Center).size(44.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (selecting) (if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle) else Icons.Filled.PlayArrow, null,
                    tint = if (selecting && selected) LilacStyle.accent else Color.White, modifier = Modifier.size(if (selecting) 26.dp else 22.dp))
            }
            StillProgress(entry.progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(entry.anime.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(entry.episodeTitle, fontSize = 12.sp, color = LilacStyle.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        Text(DateUtils.getRelativeTimeSpanString(entry.updatedAt).toString(), fontSize = 11.sp, color = LilacStyle.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 내 목록 (desktop libraryView, iOS DesktopSavedView): the saved series and the finished downloads, chosen with chips. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedScreen() {
    val app = LocalApp.current
    val library = app.library
    val navigation = app.navigation
    var tab by remember { mutableIntStateOf(if (navigation.showDownloads) 1 else 0) }
    LaunchedEffect(Unit) { navigation.showDownloads = false }
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = LilacStyle.gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        full {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LilacChipTray {
                    LilacChip("내 목록 ${library.favorites.size}", tab == 0) { tab = 0 }
                    LilacChip("다운로드 완료 0", tab == 1) { tab = 1 }
                }
                if (tab == 1) LilacEmptyState(Icons.Filled.Download, "받은 회차가 없습니다", "다운로드는 4단계에서 옮겨집니다.")
                else if (library.favorites.isEmpty()) LilacEmptyState(Icons.Outlined.FavoriteBorder, "아직 담은 작품이 없어요", "작품의 하트 버튼을 눌러 내 목록에 추가해 보세요.")
            }
        }
        if (tab == 0) items(library.favorites, key = { it.id }) { saved ->
            Box {
                AnimePosterCard(saved.anime, onLongClick = { menuFor = saved.id }) {
                    navigation.open(saved.anime, saved.source)
                }
                DropdownMenu(menuFor == saved.id, { menuFor = null }, containerColor = LilacStyle.card) {
                    DropdownMenuItem(text = { Text("내 목록에서 삭제", color = Color.Red) }, onClick = { menuFor = null; library.toggle(saved.anime, saved.source) })
                }
            }
        }
    }
}

/** 내 영상 재생 (iOS LocalVideoView); opening a file plays it, which comes with the player in stage 2. */
@Composable
fun LocalVideoScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Icon(Icons.Outlined.MovieFilter, null, tint = LilacStyle.accent, modifier = Modifier.size(52.dp))
        Text("내 영상 재생", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = LilacStyle.text)
        Text("기기에 있는 영상을 앱으로 복사해 재생합니다. 플레이어와 함께 2단계에서 옮겨집니다.", fontSize = 14.sp, color = LilacStyle.muted,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
    }
}

/** 설정 (iOS SettingsView): the parts ported so far — 일반 (theme, start view, source) and 한국어 제목 검색 (TMDB key).
 *  The rest (재생, 자막, 자동 번역, 로컬 AI, 동기화) come with the stages that use them. */
@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val library = app.library
    val navigation = app.navigation
    val prefs = library.preferences
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LilacStyle.gutter, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("설정", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LilacStyle.text)
        SettingsSection("일반") {
            SettingsChoice("테마", prefs.theme, listOf("dark" to "다크", "light" to "라이트", "system" to "시스템")) { v -> library.update { it.copy(theme = v) } }
            var start by remember { mutableStateOf(navigation.startSection) }
            SettingsChoice("시작 화면", start, Navigation.startSections) { start = it; navigation.startSection = it }
            SettingsChoice("콘텐츠 소스", prefs.source, ContentSources.keys.map { it to ContentSources.name(it) }) { v -> library.update { it.copy(source = v) } }
        }
        SettingsSection("한국어 제목 검색") {
            var key by remember { mutableStateOf(com.lilac.anime.android.data.SecureKeys.load("tmdb")) }
            var saved by remember { mutableStateOf<String?>(null) }
            Text("TMDB 키가 있으면 다른 언어 제목으로도 찾고 한국어 줄거리를 보여 줍니다. 키는 이 기기에만 저장됩니다.", fontSize = 13.sp, color = LilacStyle.muted)
            LilacTextField(key, { key = it; saved = null }, "TMDB API 키 또는 읽기 토큰", Modifier.fillMaxWidth().height(40.dp).lilacField().padding(horizontal = 10.dp), 13)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LilacOutlineButton("저장") {
                    saved = runCatching { com.lilac.anime.android.data.SecureKeys.save(key.trim(), "tmdb"); "저장했습니다." }.getOrElse { it.message }
                }
                saved?.let { Text(it, fontSize = 12.sp, color = LilacStyle.muted) }
            }
        }
        Text("Android 포팅 진행 중 · 재생, 자막, 번역, 다운로드는 다음 단계에서 추가됩니다.", fontSize = 12.sp, color = LilacStyle.muted)
        library.persistenceError?.let { Text(it, fontSize = 12.sp, color = Color.Red) }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LilacStyle.accent)
        Column(
            Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(10.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
        )
    }
}

@Composable
fun SettingsChoice(title: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, label) -> LilacChip(label, key == value) { onChange(key) } }
        }
    }
}
