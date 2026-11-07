package com.lilac.anime.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.BuildConfig
import com.lilac.anime.android.data.Navigation
import com.lilac.anime.android.data.SecureKeys
import com.lilac.anime.android.data.await
import com.lilac.anime.android.player.PlayerAspect
import com.lilac.anime.android.player.PlayerFit
import com.lilac.anime.android.player.SeekStep
import com.lilac.anime.android.player.displayName
import com.lilac.anime.android.subtitle.EpisodeSubtitleStore
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.android.subtitle.SubtitleLabels
import com.lilac.anime.android.subtitle.SubtitleNames
import com.lilac.anime.shared.CloudModelRules
import com.lilac.anime.shared.IosServices
import com.lilac.anime.shared.TranslationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// iOS SettingsComponents: a block with its title and note, chips, switches, fields and buttons.
@Composable
fun SettingBlock(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(10.dp)).border(1.dp, LilacStyle.line, RoundedCornerShape(10.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text)
        note?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = LilacStyle.muted) }
        content()
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingChoices(options: List<Pair<T, String>>, selection: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) -> LilacChip(label, value == selection) { onSelect(value) } }
    }
}
@Composable
fun SettingSwitch(title: String, detail: String? = null, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(10.dp)).border(1.dp, LilacStyle.line, RoundedCornerShape(10.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text)
            detail?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = LilacStyle.muted) }
        }
        Switch(on, change, colors = SwitchDefaults.colors(checkedTrackColor = LilacStyle.accent, checkedThumbColor = Color.White))
    }
}
@Composable
fun SettingField(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false, lines: Int = 1, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 40.dp).lilacField().padding(horizontal = 10.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = LilacStyle.muted)
        BasicTextField(value, onChange, singleLine = lines == 1, minLines = lines, textStyle = TextStyle(fontSize = 13.sp, color = LilacStyle.text), cursorBrush = SolidColor(LilacStyle.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, modifier = Modifier.fillMaxWidth())
    }
}
@Composable
fun SettingNote(text: String, color: Color = LilacStyle.muted) = Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = color)
@Composable
fun SettingSlider(value: Double, range: ClosedFloatingPointRange<Float>, step: Float, change: (Double) -> Unit) {
    Slider(value.toFloat().coerceIn(range), { change((Math.round(it / step) * step).toDouble()) }, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = LilacStyle.accent, activeTrackColor = LilacStyle.accent, inactiveTrackColor = LilacStyle.card2))
}

/** 설정 (iOS SettingsView): the categories as chips across the top (a column on a wide screen), the chosen one's page. */
@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val library = app.library
    val categories = listOf("general" to "일반", "playback" to "재생", "subtitle" to "자막", "titles" to "한국어 제목 검색", "translate" to "자막 자동 번역", "local" to "로컬 AI 모델", "storage" to "저장 자막·캐시", "sync" to "기기 동기화", "network" to "연결 (WireGuard)")
    var category by rememberSaveable { mutableStateOf("general") }
    val gutter = LilacStyle.gutter
    @Composable fun page() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (LilacStyle.wide) 0.dp else gutter).padding(top = 20.dp, bottom = 40.dp)
            .widthIn(max = 860.dp).lilacAppear(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (category) {
                "playback" -> PlaybackSettings()
                "subtitle" -> SubtitleSettings()
                "titles" -> TitleSettings()
                "translate" -> TranslateSettings()
                "storage" -> StorageSettings()
                "local" -> LocalModelSettings()
                "sync" -> SyncSettings()
                "network" -> WireGuardSettings()
                else -> GeneralSettings()
            }
            library.persistenceError?.let { SettingNote(it, Color.Red) }
        }
    }
    if (LilacStyle.wide) {
        Row(Modifier.fillMaxSize().padding(start = gutter), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(210.dp).padding(top = 30.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("설정", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LilacStyle.text, modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
                categories.forEach { (key, name) ->
                    val chosen = category == key
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(LilacStyle.radius)).background(if (chosen) LilacStyle.text.copy(alpha = 0.09f) else Color.Transparent)
                        .clickable { category = key }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(18.dp).background(if (chosen) LilacStyle.accent else Color.Transparent, RoundedCornerShape(2.dp)))
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (chosen) LilacStyle.text else LilacStyle.muted, modifier = Modifier.padding(horizontal = 11.dp))
                    }
                }
            }
            Box(Modifier.weight(1f).padding(end = gutter)) { key(category) { page() } }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Text("설정", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LilacStyle.text, modifier = Modifier.padding(horizontal = gutter).padding(top = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter - 4.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                categories.forEach { (key, name) ->
                    val chosen = category == key
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (chosen) LilacStyle.text else LilacStyle.muted, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(LilacStyle.radius)).background(if (chosen) LilacStyle.text.copy(alpha = 0.09f) else Color.Transparent)
                            .clickable { category = key }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            HorizontalDivider(color = LilacStyle.line)
            key(category) { page() }
        }
    }
}

@Composable private fun PageTitle(title: String, intro: String? = null) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
    intro?.let { SettingNote(it) }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun GeneralSettings() {
    val app = LocalApp.current
    val library = app.library; val navigation = app.navigation
    val prefs = library.preferences
    PageTitle("일반")
    SettingBlock("작품 제목 표시", "한국어 제목은 Anissia, TMDB, AniList, Wikidata에서 찾습니다. 찾지 못한 작품은 원래 제목으로 표시합니다.") {
        SettingChoices(listOf("ko" to "한국어", "en" to "영어", "original" to "사이트 원래 제목"), prefs.titleLanguage) { v -> library.update { it.copy(titleLanguage = v) } }
    }
    var start by remember { mutableStateOf(navigation.startSection) }
    SettingBlock("시작 화면", "앱을 켰을 때 처음 보이는 화면입니다.") { SettingChoices(Navigation.startSections, start) { start = it; navigation.startSection = it } }
    SettingBlock("테마") { SettingChoices(listOf("dark" to "다크", "light" to "라이트", "system" to "시스템"), prefs.theme) { v -> library.update { it.copy(theme = v) } } }
    SettingBlock("콘텐츠 / 영상 소스", "선택한 사이트의 애니 목록·제목·회차·영상 소스를 함께 사용합니다.") {
        SettingChoices(ContentSources.keys.map { it to ContentSources.name(it) }, prefs.source) { v -> library.update { it.copy(source = v) } }
    }
    var confirm by remember { mutableStateOf(false) }
    SettingBlock("시청 기록", "이 기기의 시청 기록을 모두 지웁니다.") { LilacOutlineButton("시청 기록 전체 삭제", tint = Color.Red, enabled = library.history.isNotEmpty()) { confirm = true } }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = LilacStyle.card, title = { Text("시청 기록을 모두 삭제할까요?", color = LilacStyle.text) },
        confirmButton = { TextButton({ library.clearHistory(); confirm = false }) { Text("전체 삭제", color = Color.Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("취소", color = LilacStyle.accent) } })
    AndroidBuildSettings()
}

@Composable
private fun AndroidBuildSettings() {
    val browser = LocalUriHandler.current
    var showChanges by remember { mutableStateOf(false) }
    SettingBlock("Android 빌드", "설치 버전 ${BuildConfig.VERSION_NAME} · Android APK 워크플로에서 새 빌드를 확인할 수 있습니다.") {
        LilacOutlineButton("새 APK 확인") {
            browser.openUri("https://github.com/whispelyn-byte/LilacAnime-ios/actions/workflows/android.yml")
        }
        LilacOutlineButton("변경 내역") { showChanges = true }
    }
    if (showChanges) AlertDialog(
        onDismissRequest = { showChanges = false },
        title = { Text("Android 변경 내역") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("재생 준비 상태와 오류 표시를 추가하고 일부 HLS 재생목록과 영상 조각의 처리를 개선했습니다.")
                Text("다음 화 영상과 자막을 미리 찾고, Space를 누르고 있는 동안 2배속으로 재생할 수 있습니다.")
                Text("다운로드 자막 번역, Anissia WinPNG 자막, Hugging Face GGUF 검색을 추가했습니다.")
            }
        },
        confirmButton = { TextButton(onClick = { showChanges = false }) { Text("닫기") } },
    )
}

@Composable
private fun PlaybackSettings() {
    val library = LocalApp.current.library
    val prefs = library.preferences
    PageTitle("재생")
    SettingBlock("기본 화질") { SettingChoices(listOf("Auto", "480p", "720p", "1080p").map { it to if (it == "Auto") "자동" else it }, prefs.quality) { v -> library.update { it.copy(quality = v) } } }
    SettingBlock("화면 비율", "선택한 비율로 영상을 늘립니다. 화면 채우기는 여백 없이 늘립니다.") {
        SettingChoices(PlayerAspect.entries.map { it.key to it.title }, prefs.playerAspect ?: "original") { v -> library.update { it.copy(playerAspect = v, playerFit = if (v == "fill") "stretch" else it.playerFit.takeIf { f -> f != "stretch" } ?: "contain") } }
    }
    SettingBlock("화면 맞춤", "맞춤은 원본 비율을 지키고, 채움은 가장자리를 잘라 화면을 채우고, 늘림은 화면 전체로 늘립니다.") {
        SettingChoices(PlayerFit.entries.map { it.key to it.title }, prefs.playerFit) { v -> library.update { it.copy(playerFit = v, playerAspect = if (v == "stretch") "fill" else "original") } }
    }
    SettingBlock("기본 재생 배속 (%.2fx)".format(prefs.speed)) { SettingSlider(prefs.speed, 0.1f..4f, 0.05f) { v -> library.update { it.copy(speed = v) } } }
    SettingBlock("뒤로/앞으로 버튼 이동 시간", "재생 화면 중앙 버튼과 두 번 탭, 방향키로 이동할 시간입니다.") {
        SettingChoices(SeekStep.choices.map { it.toDouble() to "${it}초" }, prefs.seekSeconds) { v -> library.update { it.copy(seekSeconds = v) } }
    }
    SettingSwitch("다음 화 자동재생", "영상이 끝나면 다음 화를 재생합니다.", prefs.autoPlay) { on -> library.update { it.copy(autoPlay = on) } }
    SettingSwitch("다음 화 미리 준비", "재생 중 다음 화 영상 주소와 자막을 미리 찾습니다.", prefs.prepareNextEpisode) { on -> library.update { it.copy(prepareNextEpisode = on) } }
    SettingSwitch("오프닝·엔딩 건너뛰기 버튼", null, prefs.showSkipButton) { on -> library.update { it.copy(showSkipButton = on) } }
    SettingSwitch("오프닝·엔딩 자동으로 건너뛰기", null, prefs.autoSkip) { on -> library.update { it.copy(autoSkip = on) } }
    SettingSwitch("백그라운드 재생", "앱을 내려도 소리가 계속 나오고, 알림에서 재생·일시정지할 수 있습니다.", prefs.backgroundAudio) { on -> library.update { it.copy(backgroundAudio = on) } }
}

@Composable
private fun SubtitleSettings() {
    val library = LocalApp.current.library
    val prefs = library.preferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = displayName(context, uri) ?: "font.ttf"
            val family = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)!!.use { SubtitleFiles.importFont(it, name) } }.getOrNull() }
            if (family != null) library.update { it.copy(subtitleFont = family) } else error = "글꼴을 가져오지 못했습니다."
        }
    }
    PageTitle("자막")
    SettingBlock("자막 소스", "자동은 영상에 딸린 한국어 자막(트랙)을 먼저 쓰고, 없으면 Kairan → Csora → Anissia 순서로 찾습니다. 다른 소스를 고르면 그 소스를 먼저 찾습니다. 다른 언어 트랙은 플레이어 설정에서 고릅니다.") {
        SettingChoices(listOf("auto" to "자동", "kairan" to "Kairan", "csora" to "Csora", "anissia" to "Anissia", "jimaku" to "Jimaku", "manual" to "직접 선택"), prefs.subtitleProvider) { v -> library.update { it.copy(subtitleProvider = v) } }
    }
    SettingBlock("자막 줄 간격 (" + (prefs.subtitleLineHeight?.let { "${it.toInt()}%" } ?: "기본") + ")") {
        SettingSlider(prefs.subtitleLineHeight ?: 160.0, 80f..300f, 5f) { v -> library.update { it.copy(subtitleLineHeight = v) } }
        LilacOutlineButton("기본값") { library.update { it.copy(subtitleLineHeight = null) } }
    }
    SettingBlock("기본 자막 크기 (${prefs.subtitleSize.toInt()}%)") { SettingSlider(prefs.subtitleSize, 50f..300f, 10f) { v -> library.update { it.copy(subtitleSize = v) } } }
    SettingBlock("기본 자막 폰트", if (prefs.subtitleFont.isEmpty()) "폰트 파일을 지정하면 기본 글꼴 대신 사용합니다." else "사용 중: " + prefs.subtitleFont) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacOutlineButton("폰트 파일 선택") { fontPicker.launch(arrayOf("*/*")) }
            if (prefs.subtitleFont.isNotEmpty()) LilacOutlineButton("파일 해제") { library.update { it.copy(subtitleFont = "") } }
        }
    }
    SettingBlock("자막 색", "SRT · VTT 자막의 글자와 테두리 색입니다.") {
        val colors = listOf("#FFFFFF" to "흰색", "#FFF4A3" to "연노랑", "#C8A2C8" to "라일락", "#A3E4FF" to "하늘", "#000000" to "검정")
        Text("글자", fontSize = 12.sp, color = LilacStyle.muted)
        SettingChoices(colors, prefs.subtitleColor.uppercase()) { v -> library.update { it.copy(subtitleColor = v) } }
        Text("테두리", fontSize = 12.sp, color = LilacStyle.muted)
        SettingChoices(colors.reversed(), prefs.outlineColor.uppercase()) { v -> library.update { it.copy(outlineColor = v) } }
    }
    SettingSwitch("자막 굵게", null, prefs.subtitleBold) { on -> library.update { it.copy(subtitleBold = on) } }
    SettingSwitch("특수 효과 자막 원본 그대로", "ASS 자막의 노래 가사·간판 번역 등의 위치·색·움직임을 그대로 보여 줍니다.", prefs.assEffects) { on -> library.update { it.copy(assEffects = on) } }
    SettingBlock("자막 테두리 두께 (%.1fpx)".format(prefs.outlineWidth)) { SettingSlider(prefs.outlineWidth, 0f..6f, 0.5f) { v -> library.update { it.copy(outlineWidth = v) } } }
    SettingBlock("기본 자막 위치 (${prefs.subtitlePadding.toInt()}%)") { SettingSlider(prefs.subtitlePadding, 3f..30f, 1f) { v -> library.update { it.copy(subtitlePadding = v) } } }
    SettingBlock("자막 싱크 (${Math.round(prefs.subtitleOffset * 1000)} ms)", "음수는 빠르게, 양수는 늦게 표시합니다. 250ms씩 조절할 수 있어요.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacOutlineButton("−250ms") { library.update { it.copy(subtitleOffset = it.subtitleOffset - 0.25) } }
            LilacOutlineButton("0") { library.update { it.copy(subtitleOffset = 0.0) } }
            LilacOutlineButton("+250ms") { library.update { it.copy(subtitleOffset = it.subtitleOffset + 0.25) } }
        }
    }
    error?.let { SettingNote(it, Color.Red) }
}

@Composable
private fun TitleSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { IosServices() }
    DisposableEffect(Unit) { onDispose { service.close() } }
    var key by remember { mutableStateOf(SecureKeys.load("tmdb")) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    PageTitle("한국어 제목 검색", "Re:Anime·Animenosub·Miruro의 원제를 한국어로 찾아 자막을 검색합니다. TMDB·AniList·Wikidata를 함께 사용하며, TMDB만 별도 API 키가 필요합니다.")
    SettingBlock("TMDB API 키", status ?: "themoviedb.org 설정 > API에서 발급한 API 키(v3) 또는 읽기 액세스 토큰을 넣을 수 있습니다. 키는 이 기기에만 암호화해 저장하고, 빈 키를 저장하면 삭제됩니다.") {
        SettingField(key, { key = it }, "TMDB API 키 또는 읽기 액세스 토큰", secret = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacOutlineButton("저장") { status = runCatching { SecureKeys.save(key.trim(), "tmdb"); "저장했습니다." }.getOrElse { it.message } }
            LilacOutlineButton(if (testing) "확인 중…" else "연결 테스트", enabled = !testing && key.isNotBlank()) {
                testing = true
                scope.launch { status = service.await<String> { testTmdb(key.trim(), it) }.fold({ it }, { it.message }); testing = false }
            }
            LilacOutlineButton("TMDB API 설정") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api"))) } }
        }
    }
    val library = LocalApp.current.library
    val catalog = com.lilac.anime.android.data.DesktopCatalog
    val source = library.preferences.source
    SettingBlock("한국어 전체 카탈로그 · 인덱스", "소스의 전체 목록을 받아 Wikidata → TMDB → AniList 순서로 한국어 제목을 찾아 둡니다. 모으는 중에도 검색할 수 있고, 앱을 다시 열면 저장한 목록을 씁니다.") {
        SettingChoices(ContentSources.keys.map { it to ContentSources.name(it) }, source) { v -> library.update { it.copy(source = v) } }
        SettingNote(catalog.progress(source), LilacStyle.text)
        catalog.failure(source)?.let { SettingNote(it, Color.Red) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacOutlineButton(if (catalog.running) "일시 중지" else "이어서 모으기") { if (catalog.running) catalog.stop() else catalog.start(source) }
            LilacOutlineButton("새로 모으기") { catalog.start(source, refresh = true) }
            LilacOutlineButton("인덱스 지우기", tint = Color.Red) { catalog.clear() }
        }
    }
    SettingNote("This product uses the TMDB API but is not endorsed or certified by TMDB.")
}

@Composable
private fun TranslateSettings() {
    val library = LocalApp.current.library
    val prefs = library.preferences
    val provider = prefs.translationProvider
    val scope = rememberCoroutineScope()
    val service = remember { IosServices() }
    DisposableEffect(Unit) { onDispose { service.close() } }
    var apiKey by remember(provider) { mutableStateOf(SecureKeys.load(provider)) }
    var region by remember(provider) { mutableStateOf(prefs.qwenRegion) }
    var models by remember(provider) { mutableStateOf(emptyList<String>()) }
    var status by remember(provider) { mutableStateOf<String?>(null) }
    var testing by remember(provider) { mutableStateOf(false) }
    fun fetch(save: Boolean) {
        val key = apiKey.trim()
        if (save && key.isEmpty()) { status = runCatching { SecureKeys.save("", provider); models = emptyList(); "API 키를 지웠습니다." }.getOrElse { it.message }; return }
        testing = true
        scope.launch {
            val result = service.await<List<String>> { cloudModels(TranslationConfig(provider, key, "", region, ""), it) }
            testing = false
            val values = result.getOrNull()
            if (values.isNullOrEmpty()) { status = result.exceptionOrNull()?.message ?: "사용 가능한 모델이 없습니다."; return@launch }
            if (save) runCatching {
                SecureKeys.save(key, provider)
                library.update { it.copy(qwenRegion = if (provider == "qwen") region else it.qwenRegion,
                    translationModel = if (it.translationModel in values) it.translationModel else CloudModelRules.default(provider, values),
                    translationModels = it.translationModels + (provider to (if (it.translationModel in values) it.translationModel else CloudModelRules.default(provider, values)))) }
            }.onFailure { status = it.message; return@launch }
            models = values; status = "연결했어요 · 모델 ${values.size}개"
        }
    }
    PageTitle("자막 자동 번역", "Re:Anime·Miruro 자막 트랙과 Jimaku 일본어 자막을 한국어로 번역합니다. 플레이어 설정의 번역 버튼으로 번역하고, 번역한 자막은 저장되어 다시 볼 때 바로 적용됩니다. 한쪽을 쓸 수 없으면 키를 등록한 다른 쪽이 이어서 번역합니다.")
    SettingBlock("번역 엔진", "Gemini · OpenAI · Qwen · Claude는 작품 정보와 이름 표기를 함께 보내 대사 흐름에 맞게 번역하고, DeepL은 줄마다 그대로 번역합니다. 로컬 AI는 인터넷 없이 이 기기에서 번역합니다 (설정 > 로컬 AI 모델).") {
        SettingChoices(listOf("gemini", "openai", "deepl", "qwen", "claude", "local").map { it to SubtitleLabels.engine(it) }, provider) { v ->
            library.update { it.copy(translationModels = it.translationModels + (it.translationProvider to it.translationModel), translationProvider = v, translationModel = it.translationModels[v] ?: "") }
        }
    }
    if (provider == "local") SettingNote("로컬 AI는 설정 > 로컬 AI 모델에서 모델을 받거나 고릅니다.")
    if (provider == "qwen") SettingBlock("Qwen 지역", "API 키를 만든 지역을 고르세요. 키는 그 지역에서만 쓸 수 있습니다.") {
        SettingChoices(listOf("international" to "국제 (싱가포르)", "china" to "중국 (베이징)"), region) { region = it }
    }
    if (provider != "local") SettingBlock((if (provider == "claude") "Anthropic" else SubtitleLabels.engine(provider)) + " API 키",
        status ?: if (provider == "claude") "console.anthropic.com에서 발급한 Anthropic API 키를 넣어 주세요. Claude 구독과 별개로 API 크레딧 충전이 필요합니다." else null) {
        SettingField(apiKey, { apiKey = it }, SubtitleLabels.engine(provider) + " API 키", secret = true)
        LilacOutlineButton(if (testing) "확인하는 중…" else "저장", enabled = !testing) { fetch(true) }
    }
    if (provider != "deepl" && provider != "local") SettingBlock(SubtitleLabels.engine(provider) + " 모델", if (provider == "claude") "키를 저장하면 그 키로 쓸 수 있는 모델을 불러와 Claude Opus 5.5(claude-opus-5-5)를 고릅니다. Haiku는 훨씬 싸고 빠르며, Opus는 번역이 가장 자연스럽습니다." else "키를 저장하면 쓸 수 있는 모델을 불러와 알맞은 모델을 고릅니다. 비워 두면 기본 모델을 씁니다.") {
        if (models.isNotEmpty()) SettingChoices(listOf("" to "자동") + models.map { it to it }, prefs.translationModel) { v -> library.update { it.copy(translationModel = v, translationModels = it.translationModels + (provider to v)) } }
        else SettingField(prefs.translationModel, { v -> library.update { it.copy(translationModel = v, translationModels = it.translationModels + (provider to v)) } }, "모델 ID (빈칸: 기본값)")
        LilacOutlineButton(if (testing) "불러오는 중…" else "연결 테스트 · 모델 목록", enabled = !testing && apiKey.isNotBlank()) { fetch(false) }
    }
    SettingBlock("Whisper 음성 자막", "자막이 없으면 설치한 Whisper 모델로 일본어 음성을 이 기기에서 인식해 자막을 만듭니다 (whisper.cpp). 현재 위치부터 30초씩 인식하고, 자동 번역 설정에 따라 한국어로 바꿉니다.") {
        val prefs2 = library.preferences
        com.lilac.anime.android.audio.WhisperModels.revision
        com.lilac.anime.android.audio.WhisperModels.variants.forEach { variant ->
            val installed = com.lilac.anime.android.audio.WhisperModels.file(variant.id) != null
            val downloading = com.lilac.anime.android.audio.WhisperModels.downloading == variant.id
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(variant.label, fontSize = 13.sp, color = LilacStyle.text, modifier = Modifier.weight(1f))
                when {
                    downloading -> LilacOutlineButton("중단 " + Math.round(com.lilac.anime.android.audio.WhisperModels.progress * 100) + "%") { com.lilac.anime.android.audio.WhisperModels.cancelDownload() }
                    installed -> {
                        LilacOutlineButton(if (prefs2.whisperModel == variant.id) "사용 중" else "사용", enabled = prefs2.whisperModel != variant.id) { library.update { it.copy(whisperModel = variant.id) } }
                        LilacOutlineButton("삭제", tint = Color.Red) { scope.launch { com.lilac.anime.android.audio.WhisperModels.remove(variant.id) } }
                    }
                    else -> LilacOutlineButton("받기") { com.lilac.anime.android.audio.WhisperModels.download(variant.id) }
                }
            }
        }
        com.lilac.anime.android.audio.WhisperModels.error?.let { SettingNote(it, Color.Red) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자막이 없으면 자동으로 음성 인식", fontSize = 13.sp, color = LilacStyle.text, modifier = Modifier.weight(1f))
            Switch(prefs2.whisperAuto, { on -> library.update { it.copy(whisperAuto = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = LilacStyle.accent))
        }
    }
    SettingSwitch("자막 자동 번역", "한국어 자막이 없으면 그 회차의 Jimaku 일본어 자막을, 없으면 영상 사이트의 일본어 트랙을, 그것도 없으면 영어 트랙을 알아서 번역해 띄웁니다. 보고 있는 장면부터 번역합니다.", prefs.autoTranslation) { on -> library.update { it.copy(autoTranslation = on) } }
    SettingSwitch("다운로드 자막 번역", "한국어 자막이 없는 다운로드에 번역 자막을 함께 저장합니다.", prefs.translateDownloads) { on -> library.update { it.copy(translateDownloads = on) } }
    SettingSwitch("효과음·화자 이름 빼고 번역", "（ため息）·[laughs] 같은 효과음 설명, 웃음소리만 있는 줄은 지우고, 줄 앞의 화자 이름을 떼고 번역합니다.", prefs.dropCaptions) { on -> library.update { it.copy(dropCaptions = on) } }
    SettingSwitch("실패하면 다른 번역 API로", "키를 등록한 다른 번역 API로 이어서 번역합니다.", prefs.cloudFallback) { on -> library.update { it.copy(cloudFallback = on) } }
    SettingSwitch("번역 API가 안 되면 로컬 AI로", "사용량을 다 썼거나 연결이 끊기면 로컬 AI가 이어서 번역합니다.", prefs.translationFallback) { on -> library.update { it.copy(translationFallback = on) } }
    SettingBlock("번역 프롬프트", "추가 지시사항은 앱의 기본 규칙 뒤에 붙고(겹치면 이쪽이 먼저), 고급: 번역 규칙 전체 교체는 기본 규칙 대신 쓰입니다. 작품 정보 · 이름 · 용어집 · 앞 대사와 답변 형식은 어느 쪽이든 앱이 넣어 줍니다. DeepL에는 적용되지 않습니다.") {
        Text("추가 지시사항", fontSize = 12.sp, color = LilacStyle.muted)
        SettingField(prefs.promptExtra, { v -> library.update { it.copy(promptExtra = v.take(4000)) } }, "예: 존댓말과 반말을 원문 그대로 지켜 주세요.", lines = 3)
        Text("고급: 번역 규칙 전체 교체", fontSize = 12.sp, color = LilacStyle.muted)
        SettingField(prefs.promptRules, { v -> library.update { it.copy(promptRules = v.take(4000)) } }, "비워 두면 앱의 기본 규칙을 씁니다.", lines = 3)
        LilacOutlineButton("되돌리기", tint = Color.Red) { library.update { it.copy(promptExtra = "", promptRules = "") } }
    }
    SettingBlock("이름·용어 표기", "한 줄에 하나씩 원문=한국어로 적으면 번역에서 그대로 씁니다.") {
        SettingField(prefs.translationGlossary, { v -> library.update { it.copy(translationGlossary = v) } }, "例: 竈門炭治郎=카마도 탄지로", lines = 4)
    }
}

/** 기기 동기화 (iOS DeviceSyncView, the desktop's data-settings-pane="sync"): the server, the password, where it stands. */
@Composable
private fun SyncSettings() {
    val sync = com.lilac.anime.android.data.DeviceSync
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(sync.server) }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun connect() {
        working = true; message = null
        scope.launch {
            runCatching { sync.connect(address, password) }.onSuccess { password = "" }.onFailure { if (it !is kotlinx.coroutines.CancellationException) message = it.message }
            working = false
        }
    }
    PageTitle("기기 동기화", "직접 만든 LilacAnime 서버(Vercel)를 거쳐 내 목록과 시청 기록을 데스크탑 앱·iPhone과 맞춥니다. 서버는 lilacanimeserver를 Vercel에 배포하고 Upstash Redis와 비밀번호(LILAC_PASSWORD)를 설정해 만듭니다.")
    SettingBlock("서버 주소") {
        SettingField(address, { address = it }, "https://내-서버.vercel.app")
        Text("비밀번호", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingField(password, { password = it }, "Vercel에 설정한 LILAC_PASSWORD", secret = true, modifier = Modifier.weight(1f))
            LilacOutlineButton(if (working) "연결 중…" else if (sync.connected) "다시 연결" else "연결", enabled = !working) { connect() }
        }
        message?.let { SettingNote(it, Color.Red) }
        SettingNote(sync.state, LilacStyle.text)
        if (sync.connected) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacOutlineButton(if (sync.running) "동기화 중…" else "지금 동기화", enabled = !sync.running) { scope.launch { sync.run() } }
            LilacOutlineButton("연결 끊기", tint = Color.Red) { sync.disconnect() }
        }
    }
    SettingNote("비밀번호는 연결할 때 한 번만 보내고, 서버가 준 토큰만 이 기기에 암호화해 둡니다. 이 기기의 파일로 본 기록은 보내지 않습니다. 같은 항목을 두 기기에서 바꾸면 나중에 바꾼 쪽이 남습니다.")
    Text("서버 만드는 방법 →", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.accent,
        modifier = Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/whispelyn-byte/LilacAnime-server"))) } })
}

/** 연결 (WireGuard), desktop data-settings-pane="network": this app's traffic alone through a WireGuard server. */
@Composable
private fun WireGuardSettings() {
    val wireGuard = com.lilac.anime.android.net.WireGuard
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) scope.launch { wireGuard.changeEnabled(true) }
        else error = "VPN 연결을 허용하지 않아 켜지 않았어요."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            error = null
            try { wireGuard.load(uri) } catch (failure: kotlinx.coroutines.CancellationException) { throw failure } catch (failure: Exception) { error = failure.message ?: "설정 파일을 읽지 못했어요." }
        }
    }
    LaunchedEffect(Unit) { while (true) { wireGuard.refresh(); kotlinx.coroutines.delay(3000) } }
    PageTitle("연결 (WireGuard)", "WireGuard 설정 파일(.conf)을 불러오면 이 앱의 통신만 그 VPN 서버를 거칩니다. 다른 앱과 같은 네트워크의 주소(Chromecast 등)는 그대로 바로 연결합니다.")
    SettingSwitch("이 앱만 WireGuard로 연결", wireGuard.state, wireGuard.enabled) { on ->
        error = null
        if (!on) scope.launch { wireGuard.changeEnabled(false) }
        else wireGuard.permissionIntent()?.let { consent.launch(it) } ?: scope.launch { wireGuard.changeEnabled(true) }
    }
    SettingBlock("설정 파일") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacOutlineButton("설정 파일 불러오기") { picker.launch(arrayOf("*/*")) }
            LilacOutlineButton("지우기", tint = Color.Red, enabled = wireGuard.file != null) { scope.launch { wireGuard.remove() } }
        }
        SettingNote(wireGuard.file ?: "불러온 설정 파일이 없습니다.", if (wireGuard.file != null) LilacStyle.text else LilacStyle.muted)
        error?.let { SettingNote(it, Color.Red) }
    }
    SettingNote("Android의 VPN 기능을 이 앱에만 써서 연결합니다. 처음 켤 때 VPN 연결 허용을 묻고, 켜 둔 동안에는 다른 VPN 앱과 함께 쓸 수 없어요. 켜 둔 동안 VPN 서버에 연결되지 않으면 이 앱의 통신은 바로 나가지 않고 멈춥니다(VPN 밖으로 새지 않도록). 끄면 바로 연결로 돌아갑니다. 설정 파일의 개인 키는 이 기기에 암호화해 저장합니다.")
}

/** 저장 자막·캐시 (iOS SubtitleStorageView): the subtitle folder's size, cleaning, and each episode's kept subtitles. */
@Composable
private fun StorageSettings() {
    val library = LocalApp.current.library
    val scope = rememberCoroutineScope()
    var size by remember { mutableLongStateOf(0L) }
    var cleaning by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { EpisodeSubtitleStore.load(); size = withContext(Dispatchers.IO) { SubtitleFiles.bytes() } }
    PageTitle("저장 자막·캐시")
    SettingBlock("자막 캐시", "번역 캐시 지우기는 다음에 볼 때 다시 번역하게 합니다. 모두 지우기는 회차마다 저장한 자막과 번역본까지 지워서, 다음에 볼 때 자막을 다시 받고 다시 번역합니다.") {
        SettingNote("자막 파일이 차지하는 용량: " + android.text.format.Formatter.formatShortFileSize(LocalContext.current, size))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilacOutlineButton(if (cleaning) "정리하는 중…" else "번역 캐시 지우기", enabled = !cleaning) {
                cleaning = true
                scope.launch { withContext(Dispatchers.IO) { SubtitleFiles.translations.listFiles()?.filter { !it.name.startsWith("working-") }?.forEach { it.delete() } }; size = withContext(Dispatchers.IO) { SubtitleFiles.bytes() }; cleaning = false }
            }
            LilacOutlineButton("모든 자막 지우기", tint = Color.Red, enabled = !cleaning) { confirm = true }
        }
    }
    Text("회차별 저장 자막", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, modifier = Modifier.padding(top = 12.dp))
    if (EpisodeSubtitleStore.records.isEmpty()) SettingNote("아직 저장한 자막이 없습니다. 자막을 찾거나 번역하면 회차마다 여기에 남습니다.")
    EpisodeSubtitleStore.records.sortedByDescending { it.date }.forEach { record ->
        Row(Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(10.dp)).border(1.dp, LilacStyle.line, RoundedCornerShape(10.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(SubtitleNames.label(record.name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 2)
                Text((if (record.translated) "번역 자막 · " else "원본 자막 · ") + SubtitleLabels.provider(record.provider), fontSize = 12.sp, color = LilacStyle.accent)
                Text(record.episodeKey, fontSize = 11.sp, color = LilacStyle.muted, maxLines = 1)
            }
            LilacOutlineButton("삭제", tint = Color.Red) { EpisodeSubtitleStore.remove(record.id) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = LilacStyle.card,
        title = { Text("저장한 원본·번역 자막도 삭제합니다. 글꼴은 보존됩니다.", color = LilacStyle.text, fontSize = 16.sp) },
        confirmButton = { TextButton({
            confirm = false; cleaning = true
            scope.launch {
                withContext(Dispatchers.IO) { SubtitleFiles.root.listFiles()?.filter { it.name != "Fonts" }?.forEach { it.deleteRecursively() } }
                EpisodeSubtitleStore.clear(); library.clearSubtitleFiles()
                size = withContext(Dispatchers.IO) { SubtitleFiles.bytes() }; cleaning = false
            }
        }) { Text("자막 캐시 전체 삭제", color = Color.Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("취소", color = LilacStyle.accent) } })
}
