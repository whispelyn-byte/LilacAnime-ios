package com.lilac.anime.android.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.android.subtitle.SubtitleLabels
import com.lilac.anime.android.subtitle.SubtitleNames
import com.lilac.anime.android.ui.LilacStyle

private val Note = Color.White.copy(alpha = 0.5f)
private val Detail = Color.White.copy(alpha = 0.55f)

fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()

@Composable private fun TabGroup(title: String) = Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
@Composable private fun TabNote(text: String, color: Color = Note) = Text(text, color = color, fontSize = 11.sp, lineHeight = 15.sp)
@Composable private fun TabSwitch(title: String, on: Boolean, small: Boolean = false, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Switch(on, change, colors = SwitchDefaults.colors(checkedTrackColor = LilacStyle.accent, checkedThumbColor = Color.White))
    }
}
@Composable private fun TabButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, action: () -> Unit) {
    Box(modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f))
        .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = action).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}
@Composable private fun ButtonRow(content: @Composable RowScope.() -> Unit) = Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
@Composable private fun Option(title: String, detail: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) LilacStyle.accent.copy(alpha = 0.15f) else Color.Transparent)
        .border(1.dp, if (selected) LilacStyle.accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).clickable(onClick = action)
        .padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (detail.isNotEmpty()) Text(detail, color = Note, fontSize = 11.sp, maxLines = 1)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = LilacStyle.accent, modifier = Modifier.size(14.dp))
    }
}
/** .sheet-tracks: a bordered box with its name and count on top, a note, then its rows. */
@Composable private fun PSBox(title: String, state: String, note: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row { Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(state, color = Detail, fontSize = 11.sp) }
        Text(note, color = Note, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Chips(options: List<Pair<String, String>>, selection: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEach { (value, label) ->
            val chosen = value == selection
            androidx.compose.foundation.layout.Box(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).background(if (chosen) Color.White else Color.White.copy(alpha = 0.1f))
                .clickable { onSelect(value) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (chosen) Color.Black else Color.White, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}
@Composable private fun Label(title: String, value: String) = Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp); Text(value, color = Color.White, fontSize = 11.sp)
}
@Composable private fun LilacSlider(value: Double, range: ClosedFloatingPointRange<Float>, step: Float, change: (Double) -> Unit) {
    val steps = ((range.endInclusive - range.start) / step).toInt() - 1
    Slider(value.toFloat().coerceIn(range), { change((Math.round(it / step) * step).toDouble()) }, valueRange = range, steps = maxOf(0, steps),
        colors = SliderDefaults.colors(thumbColor = LilacStyle.accent, activeTrackColor = LilacStyle.accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
}

/** The desktop's 자막 tab (index.html data-ps-pane="subtitle", iOS subtitleSettings): the state, the 번역 row, the sources
 *  as cards with the open one's list under them, and what to do when there is none or it is off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleTab(model: PlayerModel, openFile: () -> Unit) {
    val subtitles = model.subtitles
    val translation = subtitles.translation
    TabSwitch("자막 표시", model.engine.subtitlesVisible) { model.engine.toggleSubtitleVisibility() }
    val state = subtitles.subtitleState ?: (if (translation.running) translation.status else null) ?: subtitles.label?.let { "$it 적용됨" }
        ?: if (model.active == null) "영상 연결 후 자막을 확인합니다." else "자막을 찾는 중이거나 찾은 자막이 없습니다."
    ButtonRow { TabButton(subtitles.whisperStatus.ifEmpty { "Whisper 음성 자막 만들기" }, Modifier.weight(1f)) { subtitles.startWhisper() } }
    Text(subtitles.syncStatus ?: state, color = Color.White.copy(alpha = 0.66f), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    if (subtitles.translatable) {
        val again = subtitles.retranslatable
        ButtonRow {
            subtitles.cloudProvider()?.let { cloud ->
                TabButton(SubtitleLabels.engine(cloud) + if (again) "로 다시 번역" else "로 한국어 번역", Modifier.weight(1f), !translation.running) { subtitles.translate(again, cloud) }
            }
            TabButton(if (again) "로컬 AI로 다시 번역" else "로컬 AI로 번역", Modifier.weight(1f), !translation.running) { subtitles.translate(again, "local") }
        }
        translation.status?.let { TabNote(it) }
        if (translation.running) {
            LinearProgressIndicator({ translation.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 6.dp), color = LilacStyle.accent, trackColor = Color.White.copy(alpha = 0.15f))
            TabButton("번역 중단", Modifier.padding(top = 6.dp)) { translation.cancel() }
        }
        translation.error?.let { TabNote(it, Color.Red) }
    }
    TabGroup("자막 가져올 곳")
    val sources = buildList {
        if (subtitles.linkkfSubtitle) add(Triple("linkkf", "Linkkf", "사이트 자막"))
        if (subtitles.hasTrackList) add(Triple("reanime", subtitles.trackSourceLabel, "사이트 트랙"))
        add(Triple("kairan", "Kairan", "한국어")); add(Triple("csora", "Csora", "한국어")); add(Triple("anissia", "Anissia", "한국어"))
        add(Triple("jimaku", "Jimaku", "일본어 → 번역")); add(Triple("ai", "AI 번역", "자동 번역"))
    }
    val active = subtitles.activeSource
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 3) {
        sources.forEach { (id, title, detail) ->
            val chosen = active == id
            Column(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(if (chosen) Color.White else Color.White.copy(alpha = 0.1f))
                .clickable { subtitles.chooseSource(id) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(title, color = if (chosen) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(detail, color = if (chosen) Color.Black.copy(alpha = 0.55f) else Detail, fontSize = 10.sp, maxLines = 1)
            }
        }
        repeat((3 - sources.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
    }
    when (subtitles.openSheet) {
        "anissia" -> PSBox("Anissia 자막 제작자", if (subtitles.searching) "불러오는 중…" else if (subtitles.makers.isEmpty()) "" else "${subtitles.makers.size}명", "자막을 만든 분 · 누르면 그분 자막으로") {
            if (!subtitles.searching && subtitles.makers.isEmpty()) TabNote(subtitles.searchError ?: "제작자 정보를 찾지 못했습니다.")
            Chips(subtitles.makers.map { it.website to it.name }, subtitles.makers.firstOrNull { it.name == subtitles.selectedMaker }?.website ?: "") { website ->
                subtitles.makers.firstOrNull { it.website == website }?.let(subtitles::chooseMaker)
            }
        }
        "reanime" -> if (subtitles.hasTrackList) {
            val tracks = subtitles.siteTracks
            PSBox(subtitles.trackSourceLabel + " 자막 트랙", "${tracks.size}개 트랙", "사이트의 언어별 자막 · 한국어가 없으면 골라서 번역") {
                siteTrackOrder(tracks).forEach { track ->
                    Option(trackName(track), if (track.origin != null) listOf(subtitles.originLabel(track), trackDetail(track)).filter { it.isNotEmpty() }.joinToString(" · ") else trackDetail(track),
                        subtitles.selectedTrackURL == track.url) { subtitles.chooseTrack(track) }
                }
            }
        }
        "jimaku" -> {
            val revision = subtitles.preparer.jimaku.revision
            val files = remember(revision, model.item) { subtitles.preparer.jimaku.files(model.item) }
            val failure = remember(revision, model.item) { subtitles.preparer.jimaku.error(model.item) }
            PSBox("Jimaku 일본어 자막", when { failure != null -> ""; files == null -> "Jimaku에서 찾는 중…"; files.isEmpty() -> ""; else -> "${files.size}개 파일" }, "일본어 자막 파일 · 고르면 한국어로 번역") {
                if (failure != null) TabNote(failure, Color.Red) else if (files?.isEmpty() == true) TabNote("이 회차의 파일이 없습니다.")
                files.orEmpty().forEach { asset ->
                    val detail = listOf(asset.name.substringAfterLast('.', "").uppercase()) + if (asset.size > 0) listOf("${maxOf(1, Math.round(asset.size / 1024.0))}KB") else emptyList()
                    Option(SubtitleNames.label(asset.name, asset.url), detail.filter { it.isNotEmpty() }.joinToString(" · "), subtitles.selectedJimakuURL == asset.url) { subtitles.chooseJimaku(asset) }
                }
            }
        }
    }
    TabGroup("자막이 없거나 안 맞을 때")
    ButtonRow {
        TabButton("한국어 자막 다시 찾기", Modifier.weight(1f)) { subtitles.findKoreanAgain() }
        TabButton("파일에서 자막 열기", Modifier.weight(1f), action = openFile)
    }
    val embedded = model.engine.tracks.filter { it.type == "sub" }
    if (embedded.size > 1) {
        TabGroup("영상에 들어 있는 자막")
        embedded.forEach { track -> Option(track.title, "", track.selected) { model.engine.selectTrack(track) } }
    }
    val library = LocalApp.current.library
    ButtonRow { TabButton(subtitles.syncButtonTitle, Modifier.weight(1f), subtitles.syncRunning || subtitles.syncAvailable) { subtitles.runSync() } }
    TabSwitch("싱크가 어긋나면 자동으로 맞추기", library.preferences.subtitleSyncCheck, small = true) { on -> library.update { it.copy(subtitleSyncCheck = on) } }
    TabSwitch("다른 사이트 자막 트랙도 찾기", library.preferences.otherSiteTracks, small = true) { on -> library.update { it.copy(otherSiteTracks = on) } }
    subtitles.error?.let { Text(it, color = Color.Red, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
}

/** The site's tracks in app.js renderSubtitleTracks order: Korean, English and Japanese first (iOS SiteTrackOrder). */
private fun siteTrackOrder(tracks: List<RemoteSubtitle>): List<RemoteSubtitle> {
    fun rank(track: RemoteSubtitle): Int {
        val text = track.label + " " + track.language
        return when {
            SubtitlePolicy.isKorean(track) -> 0
            Regex("english|(?:^|\\s)en(?:[-_]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 1
            Regex("japanese|(?:^|\\s)ja(?:[-_]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 2
            else -> 3
        }
    }
    return tracks.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
}
/** "Chinese (Chinese (Han, Simplified) - Full Subtitles)": the language, and the rest underneath. */
private fun trackParts(track: RemoteSubtitle): Pair<String, String> {
    val open = track.label.indexOf('(')
    if (open <= 0 || !track.label.endsWith(")")) return track.label to track.language
    val name = track.label.substring(0, open).trim()
    val detail = track.label.substring(open + 1, track.label.length - 1).replaceFirst(name, "").replace(Regex("^[\\s-]+"), "").trim()
    return name.ifEmpty { track.label } to detail
}
private fun trackName(track: RemoteSubtitle) = trackParts(track).first
private fun trackDetail(track: RemoteSubtitle) = trackParts(track).second

/** The desktop's 자막 모양 tab (data-ps-pane="style"). */
@Composable
fun StyleTab(model: PlayerModel, openFont: () -> Unit) {
    val library = LocalApp.current.library
    val prefs = library.preferences
    val subtitles = model.subtitles
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("자막 줄 간격", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(prefs.subtitleLineHeight?.let { "${it.toInt()}%" } ?: "기본", color = Detail, fontSize = 12.sp)
        Text("기본값", color = LilacStyle.accent, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp).clickable { library.update { it.copy(subtitleLineHeight = null) } })
    }
    LilacSlider(prefs.subtitleLineHeight ?: 160.0, 80f..300f, 5f) { v -> library.update { it.copy(subtitleLineHeight = v) } }
    Label("자막 크기", "${prefs.subtitleSize.toInt()}%")
    LilacSlider(prefs.subtitleSize, 50f..180f, 5f) { v -> library.update { it.copy(subtitleSize = v) } }
    Label("자막 높이", "${prefs.subtitlePadding.toInt()}%")
    LilacSlider(prefs.subtitlePadding, 3f..30f, 1f) { v -> library.update { it.copy(subtitlePadding = v) } }
    val offset = subtitles.offset
    Label("자막 타이밍", if (kotlin.math.abs(offset) < 0.0005) "0초" else (if (offset > 0) "+" else "") + "%.2f".format(offset).trimEnd('0').trimEnd('.') + "초")
    SyncInput(offset) { subtitles.changeOffset(it) }
    Chips(listOf("fast" to "0.25초 빠르게", "reset" to "원래대로", "slow" to "0.25초 늦게"), "") { value ->
        if (value == "reset") subtitles.changeOffset(0.0) else subtitles.shift(if (value == "fast") -0.25 else 0.25)
    }
    TabGroup("특수 효과 자막 (ASS)")
    TabSwitch("원본 효과 그대로", prefs.assEffects, small = true) { on -> library.update { it.copy(assEffects = on) } }
    TabGroup("일반 자막 (SRT · VTT)")
    TabSwitch("자막 파일의 색 쓰기", prefs.vttStyle, small = true) { on -> library.update { it.copy(vttStyle = on) } }
    TabSwitch("글자 굵게", prefs.subtitleBold, small = true) { on -> library.update { it.copy(subtitleBold = on) } }
    Label("글자 테두리 두께", "%.1fpx".format(prefs.outlineWidth))
    LilacSlider(prefs.outlineWidth, 0f..6f, 0.5f) { v -> library.update { it.copy(outlineWidth = v) } }
    TabGroup("글꼴")
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("자막 글꼴", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(prefs.subtitleFont.ifEmpty { "기본 글꼴 사용 중" }, color = Detail, fontSize = 11.sp, maxLines = 1)
        }
        if (prefs.subtitleFont.isNotEmpty()) Text("기본으로", color = Color.White, fontSize = 12.sp, modifier = Modifier.clickable { library.update { it.copy(subtitleFont = "") } }.padding(8.dp))
        Text("글꼴 파일 선택", color = Color.White, fontSize = 12.sp, modifier = Modifier.clickable(onClick = openFont).padding(8.dp))
    }
    // player.js renderDiscoveredFonts: the fonts that came with the subtitle on screen.
    val families = remember(subtitles.subtitle) {
        subtitles.subtitle?.let { SubtitleFiles.fonts(it) }.orEmpty().map { SubtitleFiles.familyName(it) to it }.distinctBy { it.first }
    }
    if (families.isNotEmpty()) {
        Label("이 자막에 들어 있는 글꼴", "")
        Chips(listOf("" to "기본") + families.map { it.first to it.first }, prefs.subtitleFont) { family ->
            val chosen = if (family.isEmpty()) "" else families.firstOrNull { it.first == family }?.second?.let { runCatching { SubtitleFiles.importFont(it) }.getOrNull() } ?: family
            library.update { it.copy(subtitleFont = chosen) }
        }
    }
}

/** The 자막 타이밍 field (.ps-sync-input): milliseconds, applied on done. */
@Composable
private fun SyncInput(value: Double, update: (Double) -> Unit) {
    var input by remember(value) { mutableStateOf(Math.round(value * 1000).toString()) }
    var invalid by remember { mutableStateOf(false) }
    fun apply() { val ms = input.trim().toDoubleOrNull(); if (ms == null) invalid = true else { invalid = false; update(ms / 1000) } }
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(input, { input = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = SolidColor(LilacStyle.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { apply() }),
            modifier = Modifier.width(110.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .border(1.dp, if (invalid) Color.Red else Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
        Text("ms", color = Detail, fontSize = 12.sp)
        Text("적용", color = LilacStyle.accent, fontSize = 13.sp, modifier = Modifier.clickable { apply() }.padding(6.dp))
    }
}
