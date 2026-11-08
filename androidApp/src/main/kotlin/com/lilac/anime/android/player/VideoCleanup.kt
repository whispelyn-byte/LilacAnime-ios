package com.lilac.anime.android.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.subtitle.SubtitleFiles
import com.lilac.anime.android.ui.LilacStyle
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONTokener
import java.io.File
import java.util.UUID

@Serializable
data class CleanupRegion(val id: String = UUID.randomUUID().toString(), val x: Double = 0.7, val y: Double = 0.05, val width: Double = 0.25, val height: Double = 0.12) {
    val valid get() = listOf(x, y, width, height).all { it.isFinite() } && x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.001 && y + height <= 1.001
}
/** 영상 정리 (iOS VideoCleanupSettings): a watermark and the subtitles burned into the picture hidden in chosen regions. */
@Serializable
data class VideoCleanupSettings(val watermark: Boolean = false, val subtitles: Boolean = false, val watermarkRegions: List<CleanupRegion> = emptyList(),
                                val subtitleRegions: List<CleanupRegion> = emptyList(), val maskRevision: String? = null)
@Serializable
data class WatermarkMask(val rgba: List<Int>, val width: Int, val height: Int, val origin: List<Double>, val extent: List<Double>) {
    val valid get() = width in 1..4096 && height in 1..4096 && width * height <= 700000 && rgba.size == width * height * 4 && origin.size == 2 && extent.size == 2 &&
        (origin + extent).all { it.isFinite() } && origin.all { it in 0.0..1.0 } && extent.all { it > 0 && it <= 1 } && origin[0] + extent[0] <= 1.01 && origin[1] + extent[1] <= 1.01
}

/** iOS VideoCleanup: the settings as one mpv GLSL hook (the learned mask lightened away, or regions filled from their edges,
 *  then the desktop's subtitle-cleanup shader over the subtitle regions). */
object VideoCleanup {
    private val json = Json { ignoreUnknownKeys = true }
    private fun root(context: Context) = File(context.filesDir, "VideoCleanup").apply { mkdirs() }
    private fun maskFile(context: Context) = File(root(context), "watermark.json")
    fun store(context: Context, mask: WatermarkMask) {
        if (!mask.valid) throw PlaybackFailure("워터마크 마스크 형식이 올바르지 않습니다.")
        maskFile(context).writeText(json.encodeToString(WatermarkMask.serializer(), mask))
    }
    fun paths(context: Context, settings: VideoCleanupSettings?): List<String> {
        if (settings == null || !(settings.watermark || settings.subtitles)) return emptyList()
        val script = StringBuilder()
        val mask = if (settings.watermark && settings.maskRevision != null) json.decodeFromString(WatermarkMask.serializer(), maskFile(context).readText()).also { if (!it.valid) throw PlaybackFailure("워터마크 마스크 오류") } else null
        if (mask != null) script.append("//!TEXTURE wm\n//!SIZE ${mask.width} ${mask.height}\n//!FORMAT rgba8\n//!FILTER LINEAR\n").append(mask.rgba.joinToString("") { "%02x".format(it) }).append("\n")
        val water = if (settings.watermark) settings.watermarkRegions.filter { it.valid }.take(4) else emptyList()
        val subs = if (settings.subtitles) settings.subtitleRegions.filter { it.valid }.take(4) else emptyList()
        if (mask == null && water.isEmpty() && subs.isEmpty()) return emptyList()
        script.append("//!HOOK MAIN\n//!BIND HOOKED\n").append(if (mask == null) "" else "//!BIND wm\n").append("//!DESC Lilac video cleanup\n")
        script.append("""
            bool inside(vec2 p,vec4 r){return p.x>=r.x&&p.y>=r.y&&p.x<=r.x+r.z&&p.y<=r.y+r.w;}
            float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}
            vec3 fillRegion(vec2 p,vec4 r){
                vec2 px=2.0/HOOKED_size; float l=r.x-px.x,rr=r.x+r.z+px.x,t=r.y-px.y,b=r.y+r.w+px.y;
                vec4 weights=1.0/max(vec4(p.x-l,rr-p.x,p.y-t,b-p.y),vec4(0.001));
                return (HOOKED_tex(vec2(l,p.y)).rgb*weights.x+HOOKED_tex(vec2(rr,p.y)).rgb*weights.y+
                        HOOKED_tex(vec2(p.x,t)).rgb*weights.z+HOOKED_tex(vec2(p.x,b)).rgb*weights.w)/dot(weights,vec4(1.0));
            }
            vec4 hook(){vec2 p=HOOKED_pos;vec4 c=HOOKED_tex(p);
        """.trimIndent()).append("\n")
        if (mask != null) {
            script.append("vec2 origin=vec2(${mask.origin[0]},${mask.origin[1]}),extent=vec2(${mask.extent[0]},${mask.extent[1]});")
            script.append("if(inside(p,vec4(origin,extent))){float a=min(wm_tex((p-origin)/extent).r,0.97);c.rgb=clamp((c.rgb-vec3(a))/(1.0-a),0.0,1.0);}\n")
        } else for (r in water) script.append("if(inside(p,vec4(${r.x},${r.y},${r.width},${r.height})))c.rgb=fillRegion(p,vec4(${r.x},${r.y},${r.width},${r.height}));\n")
        script.append("return c;}\n")
        if (subs.isNotEmpty()) {
            val shader = context.assets.open("subtitle-cleanup.glsl").bufferedReader().readText()
            val rects = subs.map { "vec4(${it.x},${it.y},${it.width},${it.height})" }
            val constants = "const int uRectCount=${rects.size}; const vec4 uRects[4]=vec4[4](" + (rects + List(4 - rects.size) { "vec4(0.0)" }).joinToString(",") + ");\n"
            script.append(shader.replace("// REGION_CONSTANTS", constants))
        }
        val file = File(root(context), SubtitleFiles.key(script.toString()) + ".glsl")
        if (!file.isFile) file.writeText(script.toString())
        return listOf(file.absolutePath)
    }
}

/** The desktop's watermark learner (Resources/watermark-learner.js), run in a hidden WebView as iOS runs it in JavaScriptCore. */
@SuppressLint("SetJavaScriptEnabled")
class WatermarkLearning(private val context: Context) {
    private val web = WebView(context).apply { settings.javaScriptEnabled = true }
    private val ready = CompletableDeferred<Unit>()
    init {
        val source = context.assets.open("watermark-learner.js").bufferedReader().readText()
        web.evaluateJavascript(source + "\n;var learner = new LilacWatermark.WatermarkLearner();true") { ready.complete(Unit) }
    }
    private suspend fun evaluate(script: String): Any? { ready.await(); return suspendCancellableCoroutine { c -> web.evaluateJavascript(script) { v -> if (c.isActive) c.resumeWith(Result.success(runCatching { JSONTokener(v ?: "null").nextValue() }.getOrNull())) } } }
    suspend fun add(file: File): Int {
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) } ?: throw PlaybackFailure("영상 프레임을 읽지 못했습니다.")
        val width = minOf(640, bitmap.width); val height = maxOf(1, bitmap.height * width / bitmap.width)
        val bytes = withContext(Dispatchers.Default) {
            val small = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
            val pixels = IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
            ByteArray(pixels.size * 4).also { out -> pixels.forEachIndexed { i, p -> out[i * 4] = (p shr 16).toByte(); out[i * 4 + 1] = (p shr 8).toByte(); out[i * 4 + 2] = p.toByte(); out[i * 4 + 3] = (p ushr 24).toByte() } }
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val count = evaluate("(()=>{const b=atob('$encoded');const a=new Uint8Array(b.length);for(let i=0;i<b.length;i++)a[i]=b.charCodeAt(i);learner.addFrame(a,$width,$height);return learner.acceptedFrames;})()")
        return (count as? Number)?.toInt() ?: 0
    }
    private fun mask(text: String?): WatermarkMask? = text?.takeIf { it.isNotEmpty() }?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(WatermarkMask.serializer(), it) }.getOrNull() }
    suspend fun finish(): WatermarkMask = mask(evaluate("(()=>{const r=learner.finish();return r?JSON.stringify({...r,rgba:Array.from(r.rgba)}):'';})()") as? String)
        ?: throw PlaybackFailure(evaluate("learner.failureReason") as? String ?: "충분한 장면을 모으지 못했습니다.")
    suspend fun parse(text: String): WatermarkMask {
        val quoted = org.json.JSONObject.quote(text)
        return mask(evaluate("(()=>{try{const p=LilacWatermark.parseMaskShader($quoted);return JSON.stringify({...p,rgba:Array.from(p.rgba)});}catch(e){return ''}})()") as? String)?.takeIf { it.valid }
            ?: throw PlaybackFailure("워터마크 마스크 파일 형식이 올바르지 않습니다.")
    }
    fun close() { web.destroy() }
}

/** 영상 정리 in 플레이어 설정 (iOS VideoCleanupControls): the two switches, the region editor, logo learning and mask import. */
@Composable
fun VideoCleanupControls(engine: MpvEngine) {
    val library = LocalApp.current.library
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val value = library.preferences.videoCleanup ?: VideoCleanupSettings()
    fun set(next: VideoCleanupSettings) = library.update { it.copy(videoCleanup = next) }
    var editor by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val learner = WatermarkLearning(context)
            try {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                if (text.size > 6_000_000) throw PlaybackFailure("마스크 파일이 너무 큽니다.")
                VideoCleanup.store(context, learner.parse(String(text)))
                set((library.preferences.videoCleanup ?: VideoCleanupSettings()).copy(maskRevision = UUID.randomUUID().toString(), watermark = true))
                message = "워터마크 마스크를 적용했습니다."
            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { message = failure.message.orEmpty() } finally { learner.close() }
        }
    }
    DisposableEffect(Unit) { onDispose { learning?.cancel() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("영상 정리", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        listOf("워터마크 지우기" to value.watermark, "영상에 박힌 자막 지우기" to value.subtitles).forEachIndexed { index, (title, on) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(on, { v -> set(if (index == 0) value.copy(watermark = v) else value.copy(subtitles = v)) }, colors = SwitchDefaults.colors(checkedTrackColor = LilacStyle.accent))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("영역 지정" to { editor = true }, (if (learning == null) "로고 학습" else "학습 중단") to {
                if (learning != null) { learning?.cancel(); learning = null } else learning = scope.launch {
                    val learner = WatermarkLearning(context)
                    val file = File(context.cacheDir, UUID.randomUUID().toString() + ".png")
                    try {
                        repeat(180) {
                            if (!engine.paused && engine.screenshot(file)) { message = "장면 ${learner.add(file)}개 학습 중 · 여러 밝기와 배경이 필요합니다" }
                            delay(2000)
                        }
                        VideoCleanup.store(context, learner.finish())
                        set((library.preferences.videoCleanup ?: VideoCleanupSettings()).copy(maskRevision = UUID.randomUUID().toString(), watermark = true)); message = "로고 학습 완료"
                    } catch (failure: CancellationException) { message = "로고 학습을 중단했습니다." } catch (failure: Throwable) { message = failure.message.orEmpty() }
                    finally { file.delete(); learner.close(); learning = null }
                }
            }, "마스크 가져오기" to { picker.launch(arrayOf("*/*")) }).forEach { (title, action) ->
                Box(Modifier.weight(1f).heightIn(min = 38.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(10.dp)).clickable(onClick = action).padding(6.dp), contentAlignment = Alignment.Center) {
                    Text(title, color = Color.White, fontSize = 12.sp)
                }
            }
        }
        if (value.maskRevision != null) Text("학습한 마스크 삭제", color = LilacStyle.accent, fontSize = 12.sp, modifier = Modifier.clickable { set(value.copy(maskRevision = null)) }.padding(4.dp))
        Text(message.ifEmpty { "선택한 영역만 처리합니다. 로고 학습은 재생 중 여러 장면을 모으며, 고정된 반투명 흰색 로고에 적용됩니다." }, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
    if (editor) RegionEditor(engine, value, { set(it) }) { editor = false }
}

/** iOS CleanupRegionEditor: the frame on screen with the regions over it, each moved and sized with sliders. */
@Composable
private fun RegionEditor(engine: MpvEngine, settings: VideoCleanupSettings, change: (VideoCleanupSettings) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var subtitle by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".png")
        if (engine.screenshot(file)) image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
        file.delete()
    }
    val regions = if (subtitle) settings.subtitleRegions else settings.watermarkRegions
    fun update(list: List<CleanupRegion>) = change(if (subtitle) settings.copy(subtitleRegions = list) else settings.copy(watermarkRegions = list))
    Dialog(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).background(Color(0xFF16161C), RoundedCornerShape(18.dp)).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("영상 정리 영역", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("완료", color = LilacStyle.accent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = close).padding(6.dp))
            }
            Row(Modifier.background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp)).padding(3.dp)) {
                listOf(false to "워터마크", true to "내장 자막").forEach { (kind, title) ->
                    Box(Modifier.weight(1f).background(if (subtitle == kind) Color.White else Color.Transparent, RoundedCornerShape(8.dp)).clickable { subtitle = kind }.padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(title, color = if (subtitle == kind) Color.Black else Color.White, fontSize = 13.sp)
                    }
                }
            }
            image?.let { bitmap ->
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
                    Image(bitmap, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    regions.forEach { r ->
                        Box(Modifier.offset(maxWidth * r.x.toFloat(), maxHeight * r.y.toFloat()).size(maxWidth * r.width.toFloat(), maxHeight * r.height.toFloat()).border(2.dp, Color(0xFFB57EDC)))
                    }
                }
            }
            regions.forEach { region ->
                fun put(next: CleanupRegion) = update(regions.map { if (it.id == region.id) next else it })
                Column(Modifier.border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("왼쪽 ${(region.x * 100).toInt()}% · 위 ${(region.y * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                    Slider(region.x.toFloat(), { put(region.copy(x = it.toDouble())) }, valueRange = 0f..maxOf(0.001f, (1 - region.width).toFloat()))
                    Slider(region.y.toFloat(), { put(region.copy(y = it.toDouble())) }, valueRange = 0f..maxOf(0.001f, (1 - region.height).toFloat()))
                    Text("너비 ${(region.width * 100).toInt()}% · 높이 ${(region.height * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                    Slider(region.width.toFloat(), { put(region.copy(width = it.toDouble())) }, valueRange = 0.01f..maxOf(0.011f, (1 - region.x).toFloat()))
                    Slider(region.height.toFloat(), { put(region.copy(height = it.toDouble())) }, valueRange = 0.01f..maxOf(0.011f, (1 - region.y).toFloat()))
                    Text("영역 삭제", color = Color.Red, fontSize = 13.sp, modifier = Modifier.clickable { update(regions.filter { it.id != region.id }) }.padding(4.dp))
                }
            }
            if (regions.size < 4) Text("영역 추가", color = LilacStyle.accent, fontSize = 14.sp, modifier = Modifier.clickable {
                update(regions + if (subtitle) CleanupRegion(x = 0.1, y = 0.75, width = 0.8, height = 0.2) else CleanupRegion())
            }.padding(6.dp))
        }
    }
}
