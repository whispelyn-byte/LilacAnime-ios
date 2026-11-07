package com.lilac.anime.android.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.ui.ContentSources
import com.lilac.anime.android.ui.LilacStyle
import com.lilac.anime.android.ui.lilacAppear
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val PanelColor = Color(0xF7111116)
private val PSNote = Color.White.copy(alpha = 0.5f)
private val PSDetail = Color.White.copy(alpha = 0.55f)
private val PSLine = Color.White.copy(alpha = 0.07f)

fun Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) { if (context is ComponentActivity) return context; context = context.baseContext }
    return null
}

private fun clock(seconds: Double): String {
    val value = if (seconds.isFinite()) maxOf(0, seconds.toInt()) else 0
    return if (value >= 3600) "%d:%02d:%02d".format(value / 3600, value % 3600 / 60, value % 60) else "%02d:%02d".format(value / 60, value % 60)
}

/** The full-screen player (iOS EpisodePlayerView): the video, the controls over it, 플레이어 설정 and 회차 at the right. */
@Composable
fun PlayerScreen(start: PlaybackItem, close: () -> Unit) {
    val app = LocalApp.current
    val library = app.library
    val context = LocalContext.current
    val activity = context.findActivity()
    val model = remember { PlayerModel(context, start, library) }
    var settings by remember { mutableStateOf(false) }
    var episodePanel by remember { mutableStateOf(false) }
    var showWeb by remember { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val scope = rememberCoroutineScope()
    // 파일에서 자막 열기 / 글꼴 파일 선택: the file is copied into the app and used from there.
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val name = displayName(context, uri) ?: "subtitle.ass"
                    val folder = File(context.cacheDir, "opened/" + System.currentTimeMillis()).apply { mkdirs() }
                    File(folder, name).also { file -> context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }
                }.getOrNull()
            }
            if (copied != null) model.subtitles.import(copied.absolutePath, translate = false, provider = "user") else model.notify("파일을 열지 못했습니다.")
        }
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = displayName(context, uri) ?: "font.ttf"
            if (name.substringAfterLast('.').lowercase() !in setOf("ttf", "otf", "ttc")) { model.notify("TTF · OTF · TTC 글꼴 파일을 선택하세요."); return@launch }
            val family = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { com.lilac.anime.android.subtitle.SubtitleFiles.importFont(it, name) } }.getOrNull()
            }
            if (family != null) library.update { it.copy(subtitleFont = family) } else model.notify("글꼴을 가져오지 못했습니다.")
        }
    }

    // Landscape, full screen, the screen kept on while the player is open; all put back as it closes.
    DisposableEffect(Unit) {
        val window = activity?.window
        val original = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        val pipListener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(pipListener)
        model.begin()
        onDispose {
            activity?.removeOnPictureInPictureModeChangedListener(pipListener)
            PlaybackService.stop(context)
            model.shutdown()
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = original
        }
    }
    // In the background: the sound goes on (설정 > 백그라운드 재생) through a media service, else it pauses.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true && !library.preferences.backgroundAudio) model.engine.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.engine.paused, model.active) {
        if (!model.engine.paused && model.active != null && library.preferences.backgroundAudio)
            PlaybackService.start(context, model.engine, model.item.anime.title, model.item.title)
    }
    LaunchedEffect(library.preferences) { model.engine.applyPreferences(library.preferences) }
    LaunchedEffect(showWeb) { if (showWeb) model.engine.pause(); model.resolver.setWebPlaybackActive(showWeb) }
    LaunchedEffect(model.resolver.streams) { if (model.resolver.streams.isNotEmpty()) showWeb = false }
    BackHandler {
        when { settings -> settings = false; episodePanel -> episodePanel = false; showWeb -> showWeb = false; else -> close() }
    }
    fun enterPip() {
        val target = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { target.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            .onFailure { model.notify("PiP를 시작하지 못했습니다.") }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
            model.engine.create()
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { model.engine.attachSurface(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { model.engine.surfaceSize(width, height) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { model.engine.detachSurface() }
                })
            }
        }, modifier = Modifier.fillMaxSize())
        // The resolver's page: hidden while it looks for the stream, shown as 웹 플레이어.
        AndroidView(factory = { ctx -> android.widget.FrameLayout(ctx) }, update = { frame ->
            val web = model.resolver.playbackWebView
            if (web.parent !== frame) {
                (web.parent as? ViewGroup)?.removeView(web)
                frame.removeAllViews()
                frame.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (showWeb) 1f else 0.001f }.then(if (showWeb) Modifier else Modifier.pointerInput(Unit) {}))

        if (!showWeb && !inPip) {
            PlayerControls(model, close = close, settings = { episodePanel = false; settings = true },
                episodes = if (model.seriesItems.size < 2) null else ({ settings = false; episodePanel = true }),
                pip = { enterPip() }, keyboardEnabled = !settings && !episodePanel,
                screenshot = { saveScreenshot(context, model) })
        } else if (showWeb) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f)).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White, modifier = Modifier.clickable { showWeb = false })
                Icon(Icons.Outlined.Settings, "설정", tint = Color.White, modifier = Modifier.clickable { settings = true })
            }
        }
        val failure = model.engine.error ?: model.error ?: model.resolver.error
        if (!showWeb && !inPip && (model.active == null || model.engine.error != null)) {
            Column(Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.resolver.loading || (failure == null && model.active == null)) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    Text("영상을 준비하고 있어요", color = Color.White, fontSize = 14.sp)
                }
                if (failure != null) {
                    Text(failure, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Text("다시 시도", color = LilacStyle.accent, fontSize = 15.sp, modifier = Modifier.clickable { model.begin() }.padding(4.dp))
                    Text("웹 플레이어 열기", color = LilacStyle.accent, fontSize = 15.sp, modifier = Modifier.clickable { showWeb = true }.padding(4.dp))
                }
            }
        }
        if (settings && !inPip) PlayerSettingsPanel(model, close = { settings = false }, openWeb = { settings = false; showWeb = true },
            openSubtitleFile = { settings = false; subtitlePicker.launch(arrayOf("*/*")) }, openFontFile = { fontPicker.launch(arrayOf("*/*")) })
        // desktop showSyncDone: what the sync did, and 되돌리기 while the subtitle it changed is on screen.
        model.subtitles.syncNotice?.takeIf { !inPip }?.let { notice ->
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp, start = 24.dp, end = 24.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(notice, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                Text("되돌리기", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(LilacStyle.accent, RoundedCornerShape(8.dp)).clickable { model.subtitles.undoSync() }.padding(horizontal = 12.dp, vertical = 6.dp))
                Text("확인", color = Color.White, fontSize = 14.sp, modifier = Modifier.clickable { model.subtitles.dismissSync() }.padding(6.dp))
            }
        }
        model.subtitles.koreanOffer?.takeIf { !inPip }?.let { offer ->
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp, start = 24.dp, end = 24.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(offer, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                Text("바꾸기", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(LilacStyle.accent, RoundedCornerShape(8.dp)).clickable { model.subtitles.acceptOffer() }.padding(horizontal = 12.dp, vertical = 6.dp))
                Text("닫기", color = Color.White, fontSize = 14.sp, modifier = Modifier.clickable { model.subtitles.dismissOffer() }.padding(6.dp))
            }
        }
        if (episodePanel && !inPip) EpisodePanel(model, close = { episodePanel = false }) { index -> episodePanel = false; model.jump(index) }
    }
}

private suspend fun saveScreenshot(context: Context, model: PlayerModel): String {
    val file = File(context.cacheDir, "shot-${System.currentTimeMillis()}.png")
    if (!model.engine.screenshot(file)) return "먼저 영상을 재생하세요."
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val name = (model.item.anime.title + " " + model.item.displayNumber + "화 " + clock(model.engine.position).replace(":", "-")).replace(Regex("[\\\\/:*?\"<>|]"), " ") + ".png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LilacAnime")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("사진을 저장하지 못했습니다")
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            "스크린샷을 사진에 저장했어요"
        }.getOrElse { "스크린샷 저장 실패: " + it.message }.also { file.delete() }
    }
}

/** iOS PlayerControls: three tap zones (double tap ±seek / play-pause, hold 2배속), the title, the transport row, the
 *  seek bar with screenshot and lock, the skip button and the hints; keyboard shortcuts as on iPad. */
@Composable
private fun PlayerControls(model: PlayerModel, close: () -> Unit, settings: () -> Unit, episodes: (() -> Unit)?, pip: () -> Unit,
                           keyboardEnabled: Boolean, screenshot: suspend () -> String) {
    val app = LocalApp.current
    val library = app.library
    val engine = model.engine
    val seek = library.preferences.seekSeconds
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    var interaction by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    var feedbackAt by remember { mutableLongStateOf(0L) }
    var hint by remember { mutableStateOf<SeekHint?>(null) }
    var taking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun touch() { hint = null; interaction++ }
    fun flash(text: String, millis: Long = 1400) {
        feedback = text; val at = System.nanoTime(); feedbackAt = at
        scope.launch { delay(millis); if (feedbackAt == at) feedback = "" }
    }
    LaunchedEffect(interaction, engine.paused, locked, dragging) {
        if (engine.paused || locked || dragging) return@LaunchedEffect
        delay(2000); visible = false
    }
    LaunchedEffect(engine.paused) { if (engine.paused) visible = true }
    LaunchedEffect(model.toast) { model.toast?.let { flash(it.first, 2200) } }
    LaunchedEffect(hint?.id) { val current = hint ?: return@LaunchedEffect; delay(800); if (hint?.id == current.id) hint = null }
    LaunchedEffect(keyboardEnabled) { if (keyboardEnabled) runCatching { focus.requestFocus() } }
    fun shot() { if (taking) return; taking = true; scope.launch { flash(screenshot()); taking = false } }
    fun changeSpeed(up: Boolean) {
        val speeds = listOf(0.1, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0)
        val at = speeds.indexOfFirst { kotlin.math.abs(it - library.preferences.speed) < 0.001 }.takeIf { it >= 0 } ?: 4
        val next = speeds[(at + if (up) 1 else -1).coerceIn(0, speeds.size - 1)]
        library.update { it.copy(speed = next) }; engine.finishHold(); engine.changeSpeed(next)
        flash("재생 속도 " + "%.2f".format(next) + "x")
    }
    val chapter = model.currentChapter

    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { event ->
        if (!keyboardEnabled || locked || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.Spacebar -> { engine.toggle(); touch() }
            Key.DirectionLeft -> SeekHint.relative(-seek, engine.position, engine.duration, hint)?.let { engine.seekByViewer(it.target); hint = it }
            Key.DirectionRight -> SeekHint.relative(seek, engine.position, engine.duration, hint)?.let { engine.seekByViewer(it.target); hint = it }
            Key.DirectionUp -> engine.changeVolume(engine.volume + 5)
            Key.DirectionDown -> engine.changeVolume(engine.volume - 5)
            Key.M -> engine.toggleMute()
            Key.C -> { engine.toggleSubtitleVisibility(); flash(if (engine.subtitlesVisible) "자막을 켰어요" else "자막을 껐어요") }
            Key.S -> if (chapter != null) engine.seek(chapter.end) else flash("지금은 건너뛸 OP/ED 구간이 아니에요")
            Key.P -> shot()
            Key.LeftBracket -> changeSpeed(false)
            Key.RightBracket -> changeSpeed(true)
            Key.PageDown -> if (model.item.next.isNotEmpty()) model.nextEpisode()
            Key.PageUp -> if (model.previous.isNotEmpty()) model.previousEpisode()
            Key.Escape -> close()
            else -> {
                val digit = event.key.keyCode - Key.Zero.keyCode
                if (digit !in 0..9) return@onKeyEvent false
                SeekHint.jump(digit.toInt(), engine.duration)?.let { engine.seekByViewer(it.target); hint = it }
            }
        }
        true
    }) {
        // Left, middle, right: a tap shows or hides the controls, a double tap seeks or pauses, a hold plays at 2배속.
        Row(Modifier.fillMaxSize()) {
            listOf(-seek, 0.0, seek).forEach { delta ->
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(delta, locked) {
                    detectTapGestures(
                        onTap = { if (!locked) { visible = !visible; touch() } },
                        onDoubleTap = {
                            if (locked) return@detectTapGestures
                            if (delta == 0.0) engine.toggle() else { engine.skip(delta); flash((if (delta > 0) "+" else "−") + "${kotlin.math.abs(delta).toInt()}초", 700) }
                            touch()
                        },
                        onLongPress = { if (!locked) engine.boostHold() },
                        onPress = { tryAwaitRelease(); engine.finishHold() },
                    )
                })
            }
        }
        AnimatedVisibility(visible && !locked, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlButton(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") { close() }
                        Column(Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(com.lilac.anime.android.data.displayTitle(model.item.anime.anime, model.item.anime.source), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val origin = if (model.active?.url?.let(::isFileURL) == true) "다운로드" else ContentSources.name(model.item.anime.source)
                            Text(model.item.title.ifEmpty { model.item.displayNumber + "화" } + " · " + origin, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1)
                        }
                        Spacer(Modifier.weight(1f))
                        ControlButton(Icons.Filled.PictureInPictureAlt, "자막과 함께 PiP") { pip(); touch() }
                        if (episodes != null) ControlButton(Icons.AutoMirrored.Filled.FormatListBulleted, "회차 목록") { episodes(); touch() }
                        ControlButton(Icons.Outlined.Settings, "플레이어 설정") { settings(); touch() }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        ControlButton(Icons.Filled.SkipPrevious, "이전 회차", enabled = model.previous.isNotEmpty()) { model.previousEpisode(); touch() }
                        ControlButton(Icons.Filled.FastRewind, "${seek.toInt()}초 뒤로") { engine.skip(-seek); touch() }
                        Box(Modifier.size(64.dp).background(Color.White.copy(alpha = 0.92f), CircleShape).clip(CircleShape).clickable { engine.toggle(); touch() }, contentAlignment = Alignment.Center) {
                            Icon(if (engine.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (engine.paused) "재생" else "일시정지", tint = Color.Black, modifier = Modifier.size(34.dp))
                        }
                        ControlButton(Icons.Filled.FastForward, "${seek.toInt()}초 앞으로") { engine.skip(seek); touch() }
                        ControlButton(Icons.Filled.SkipNext, "다음 회차", enabled = model.item.next.isNotEmpty()) { model.nextEpisode(); touch() }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f).background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(clock(if (dragging) value.toDouble() else engine.position), color = Color.White, fontSize = 11.sp)
                            Slider(
                                value = if (dragging) value else engine.position.toFloat().coerceIn(0f, maxOf(engine.duration.toFloat(), 1f)),
                                onValueChange = { if (!dragging) { dragging = true }; value = it; touch() },
                                onValueChangeFinished = { dragging = false; engine.seekByViewer(value.toDouble()); touch() },
                                valueRange = 0f..maxOf(engine.duration.toFloat(), 1f), modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = LilacStyle.accent, activeTrackColor = LilacStyle.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                            )
                            Text(clock(engine.duration), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                        }
                        ControlButton(Icons.Outlined.CameraAlt, "스크린샷", enabled = !taking) { shot(); touch() }
                        ControlButton(Icons.Filled.Lock, "화면 잠금") { locked = true; visible = false }
                    }
                }
            }
        }
        if (locked) Box(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp)) {
            ControlButton(Icons.Filled.LockOpen, "잠금 해제") { locked = false; visible = true; touch() }
        }
        if (feedback.isNotEmpty()) Text(feedback, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(22.dp)).padding(14.dp))
        hint?.takeIf { !locked }?.let { current ->
            val alignment = when { current.seconds == null -> Alignment.Center; current.seconds < 0 -> Alignment.CenterStart; else -> Alignment.CenterEnd }
            Column(Modifier.align(alignment).padding(horizontal = 80.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                current.seconds?.let { Text((if (it > 0) "+" else "−") + "${kotlin.math.abs(it).toInt()}초", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Text(clock(current.target), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
        if (engine.buffering && model.active != null) CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.align(Alignment.Center).size(30.dp))
        if (engine.speedBoosted) Text("2배속", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(Color.Black.copy(alpha = 0.75f), CircleShape).padding(horizontal = 18.dp, vertical = 8.dp))
        if (chapter != null && !locked && library.preferences.showSkipButton) {
            Row(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 18.dp, bottom = if (visible) 70.dp else 18.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape).clip(CircleShape).clickable { engine.seek(chapter.end) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(Chapter.skipLabel(chapter.type), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.size(44.dp).graphicsLayer { alpha = if (enabled) 1f else 0.3f }.background(Color.Black.copy(alpha = 0.48f), CircleShape)
        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape).clip(CircleShape).clickable(enabled = enabled, onClick = action), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

/** The dark card at the right (player-android.css .player-settings), as iOS PlayerSettingsPanel and PlayerEpisodePanel. */
@Composable
private fun SidePanel(icon: ImageVector, title: String, subtitle: String? = null, close: () -> Unit, header: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)).clickable(remember { MutableInteractionSource() }, null, onClick = close))
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val top = if (maxHeight < 500.dp) 12.dp else 72.dp
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = top, end = 16.dp, bottom = 12.dp).width(minOf(380.dp, maxWidth - 32.dp)).lilacAppear()
                    .shadow(30.dp, RoundedCornerShape(22.dp)).background(PanelColor, RoundedCornerShape(22.dp)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(22.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {},
            ) {
                Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        subtitle?.let { Text(it, color = PSNote, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    header()
                    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = close), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, "닫기", tint = Color.White) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(PSLine))
                content()
            }
        }
    }
}

/** .ps-chips: small chips that wrap, white with black text when chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGrid(options: List<Pair<T, String>>, selection: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEach { (value, label) ->
            val chosen = value == selection
            Box(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).background(if (chosen) Color.White else Color.White.copy(alpha = 0.1f))
                .clickable { onSelect(value) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (chosen) Color.Black else Color.White, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}
@Composable private fun Group(title: String) = Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
@Composable private fun Note(text: String, color: Color = PSNote) = Text(text, color = color, fontSize = 11.sp, lineHeight = 15.sp)
@Composable private fun Switch(title: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(on, change, colors = SwitchDefaults.colors(checkedTrackColor = LilacStyle.accent, checkedThumbColor = Color.White))
    }
}
@Composable private fun SheetButton(text: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f))
        .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(10.dp)).clickable(onClick = action).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}
@Composable private fun TrackOption(title: String, detail: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) LilacStyle.accent.copy(alpha = 0.15f) else Color.Transparent)
        .border(1.dp, if (selected) LilacStyle.accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).clickable(onClick = action)
        .padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (detail.isNotEmpty()) Text(detail, color = PSNote, fontSize = 11.sp, maxLines = 1)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = LilacStyle.accent, modifier = Modifier.size(14.dp))
    }
}

/** 플레이어 설정: 재생 · 자막 · 자막 모양 (iOS PlayerSettingsPanel and its tabs). */
@Composable
private fun PlayerSettingsPanel(model: PlayerModel, close: () -> Unit, openWeb: () -> Unit, openSubtitleFile: () -> Unit, openFontFile: () -> Unit) {
    val app = LocalApp.current
    val library = app.library
    val prefs = library.preferences
    val defaults = LocalContext.current.getSharedPreferences("lilac", Context.MODE_PRIVATE)
    var tab by remember { mutableIntStateOf(defaults.getInt("playerSettingsTab", 0)) }
    var openKind by remember { mutableStateOf("") }
    SidePanel(Icons.Outlined.Settings, "플레이어 설정", close = close) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("재생", "자막", "자막 모양").forEachIndexed { index, title ->
                Box(Modifier.weight(1f).heightIn(min = 32.dp).clip(RoundedCornerShape(9.dp)).background(if (tab == index) Color.White else Color.Transparent)
                    .clickable { tab = index; defaults.edit().putInt("playerSettingsTab", index).apply() }, contentAlignment = Alignment.Center) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (tab == index) Color.Black else Color.White.copy(alpha = 0.7f))
                }
            }
        }
        key(tab) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 14.dp).lilacAppear()) {
                when (tab) {
                    0 -> {
                        val choices = ServerChoice.all(model.resolver.streams, model.resolver.servers)
                        if (choices.size >= 2) {
                            Group("영상 서버")
                            val playing = model.active?.label ?: model.resolver.serverLabel
                            val picked = prefs.preferredServers[model.item.anime.source].orEmpty()
                            val kinds = ServerChoice.kinds(choices)
                            val open = if (openKind in kinds) openKind else (choices.firstOrNull { it.label == playing } ?: choices[0]).kind
                            Note("재생 중: " + playing.ifEmpty { "-" }, Color.White.copy(alpha = 0.66f))
                            Spacer(Modifier.height(4.dp))
                            ChoiceGrid(listOf("" to "자동") + kinds.map { "kind:$it" to ServerChoice.title(it) }, if (picked.isEmpty()) "" else "kind:$open") { value ->
                                if (value.startsWith("kind:")) { openKind = value.removePrefix("kind:"); return@ChoiceGrid }
                                library.update { it.copy(preferredServers = it.preferredServers - model.item.anime.source, preferredStream = null) }
                                model.save(); model.begin()
                            }
                            Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ChoiceGrid(choices.filter { it.kind == open }.map { it.id to ServerChoice.name(it.label) },
                                    model.active?.id ?: choices.firstOrNull { it.label == picked.ifEmpty { playing } }?.id ?: "") { id ->
                                    val choice = choices.firstOrNull { it.id == id } ?: return@ChoiceGrid
                                    library.update { it.copy(preferredServers = it.preferredServers + (model.item.anime.source to choice.label)) }
                                    model.save()
                                    choice.stream?.let { stream ->
                                        library.update { it.copy(preferredStream = stream.label) }
                                        if (model.item.anime.source == "miruro") { model.active = null; model.selectResolved(model.resolver.streams) } else model.play(stream)
                                    } ?: choice.server?.let { server -> model.active = null; model.engine.pause(); model.resolver.selectServer(server) }
                                }
                                ServerChoice.note(open)?.let { Note(it) }
                            }
                            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(PSLine))
                        }
                        Group("화질")
                        ChoiceGrid(listOf("Auto", "480p", "720p", "1080p").map { it to if (it == "Auto") "자동" else it }, prefs.quality) { value ->
                            library.update { it.copy(quality = value) }; model.reloadForQuality()
                        }
                        Group("재생 속도  " + "%.2fx".format(prefs.speed))
                        ChoiceGrid(listOf(0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0).map { it to "%.2fx".format(it) }, prefs.speed) { value ->
                            model.engine.finishHold(); library.update { it.copy(speed = value) }; model.engine.changeSpeed(value)
                        }
                        Group("뒤로/앞으로 이동  ${prefs.seekSeconds.toInt()}초")
                        ChoiceGrid(SeekStep.choices.map { it.toDouble() to "${it}초" }, prefs.seekSeconds) { value -> library.update { it.copy(seekSeconds = SeekStep.clamped(value)) } }
                        Group("화면 비율")
                        ChoiceGrid(PlayerAspect.entries.map { it.key to it.title }, prefs.playerAspect ?: "original") { value ->
                            library.update { it.copy(playerAspect = value, playerFit = if (value == "fill") "stretch" else it.playerFit.takeIf { f -> f != "stretch" } ?: "contain") }
                        }
                        Text("화면 맞춤", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        ChoiceGrid(PlayerFit.entries.map { it.key to it.title }, PlayerPresentation(PlayerAspect.of(prefs.playerAspect), PlayerFit.of(prefs.playerFit)).effectiveFit.key) { value ->
                            library.update { it.copy(playerFit = value, playerAspect = if (value == "stretch") "fill" else "original") }
                        }
                        Group("Anime4K 영상 보정")
                        ChoiceGrid(Anime4KMode.entries.map { it.key to it.title }, prefs.anime4k) { value -> library.update { it.copy(anime4k = value) } }
                        model.engine.anime4kError?.let { Note(it, Color(0xFFFFA500)) }
                        Group("자동 재생 · 건너뛰기")
                        Switch("다음 화 자동재생", prefs.autoPlay) { on -> library.update { it.copy(autoPlay = on) } }
                        Switch("오프닝·엔딩 건너뛰기 버튼", prefs.showSkipButton) { on -> library.update { it.copy(showSkipButton = on) } }
                        Switch("오프닝·엔딩 자동으로 건너뛰기", prefs.autoSkip) { on -> library.update { it.copy(autoSkip = on) } }
                        val audio = model.engine.tracks.filter { it.type == "audio" }
                        if (audio.size > 1) {
                            Group("음성")
                            ChoiceGrid(audio.map { it.id to it.title }, audio.firstOrNull { it.selected }?.id ?: -1) { id -> audio.firstOrNull { it.id == id }?.let(model.engine::selectTrack) }
                        }
                        Group("이 기기에서")
                        Switch("백그라운드 재생", prefs.backgroundAudio) { on -> library.update { it.copy(backgroundAudio = on) } }
                        SheetButton("웹 플레이어 열기", Modifier.fillMaxWidth().padding(top = 8.dp), openWeb)
                        Text("조작  영상 꾹 누르기 2배속 · 왼쪽/오른쪽 두 번 탭 탐색 · 키보드: Space 재생/정지 · ←/→ 탐색 · 0~9 위치 · [ ] 속도 · C 자막 켜기/끄기 · S 오프닝·엔딩 건너뛰기 · P 스크린샷 · M 음소거 · PageUp/PageDown 이전/다음 화",
                            color = PSDetail, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp))
                        model.error?.let { Note(it, Color.Red) }
                    }
                    1 -> SubtitleTab(model, openSubtitleFile)
                    else -> StyleTab(model, openFontFile)
                }
            }
        }
    }
}

/** 회차 (index.html #episodePanel, iOS PlayerEpisodePanel): the series' episodes, the one playing marked. */
@Composable
private fun EpisodePanel(model: PlayerModel, close: () -> Unit, pick: (Int) -> Unit) {
    val items = model.seriesItems
    val current = model.previous.size
    val defaults = LocalContext.current.getSharedPreferences("lilac", Context.MODE_PRIVATE)
    var newestFirst by remember { mutableStateOf(defaults.getBoolean("episodeNewestFirst", false)) }
    val library = LocalApp.current.library
    SidePanel(Icons.AutoMirrored.Filled.FormatListBulleted, "회차", model.item.anime.title + " · ${items.size}화", close, header = {
        Box(Modifier.height(30.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable { newestFirst = !newestFirst; defaults.edit().putBoolean("episodeNewestFirst", newestFirst).apply() }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(if (newestFirst) "최신회부터" else "첫회부터", color = Color.White, fontSize = 12.sp)
        }
    }) {
        val order = if (newestFirst) items.indices.reversed().toList() else items.indices.toList()
        val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, order.indexOf(current) - 2))
        val episodes = remember(items) { items.firstOrNull()?.anime?.anime?.episodes.orEmpty().associateBy { it.id } }
        LazyColumn(state = state, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(order) { _, index ->
                val item = items[index]
                val playing = index == current
                val episode = episodes[item.episodeID]
                val label = item.displayNumber.ifEmpty { item.number.toString() } + "화"
                val named = item.title.takeIf { it.isNotEmpty() && it != label && it != "Episode ${item.number}" }.orEmpty()
                val watched = library.history.firstOrNull { it.anime.id == item.anime.id && it.episodeID == item.episodeID }?.progress ?: 0.0
                val meta = listOf(if (playing) "재생 중" else "", if (episode?.dubbed == true) "더빙" else "", episode?.airedDate?.substringBefore("T").orEmpty(),
                    if (episode?.playable == false) "재생 불가" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (playing) LilacStyle.accent.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable(enabled = !playing && episode?.playable != false) { pick(index) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(label + if (named.isNotEmpty()) " · $named" else "", color = if (playing) LilacStyle.accent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        if (meta.isNotEmpty()) Text(meta, color = PSNote, fontSize = 11.sp, maxLines = 1)
                        if (watched > 0) Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(watched.toFloat().coerceIn(0f, 1f)).background(LilacStyle.accent))
                        }
                    }
                }
            }
        }
    }
}
