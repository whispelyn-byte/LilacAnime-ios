package com.lilac.anime.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.data.LocalModelInputOptions
import com.lilac.anime.android.data.SecureKeys
import com.lilac.anime.android.data.await
import com.lilac.anime.android.localai.*
import com.lilac.anime.shared.IosServices
import com.lilac.anime.shared.ModelFile
import com.lilac.anime.shared.ModelSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 로컬 AI 모델 (iOS LocalModelsView): the desktop's presets to download, the GGUF files here, a file or an address to add,
 *  each file's input options, and the llama.cpp settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalModelSettings() {
    val app = LocalApp.current
    val library = app.library
    val prefs = library.preferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(LocalModelFiles.files()) }
    var address by remember { mutableStateOf("") }
    var searchingModels by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var copying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ModelInstaller.revision) { files = LocalModelFiles.files() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        copying = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = com.lilac.anime.android.player.displayName(context, uri) ?: "model.gguf"
                    if (!name.lowercase().endsWith(".gguf")) throw IllegalStateException("GGUF 파일을 선택하세요.")
                    val target = File(LocalModelFiles.directory, name); val partial = File(target.path + ".part")
                    context.contentResolver.openInputStream(uri)!!.use { input -> partial.outputStream().use { input.copyTo(it) } }
                    LocalModelFiles.validate(partial); partial.renameTo(target); target.name
                }
            }
            copying = false
            result.onSuccess { name -> library.update { it.copy(selectedGGUF = name) }; files = LocalModelFiles.files() }.onFailure { error = it.message }
        }
    }
    Text("로컬 AI 모델", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
    SettingNote("인터넷 없이 이 기기에서 자막을 번역합니다 (llama.cpp b11490, CPU). 모델 파일 크기 외에 추론 메모리가 필요하니 기기 메모리에 맞는 양자화 모델을 고르세요." +
        if (LocalAI.available) "" else " 이 기기에서는 로컬 AI 라이브러리를 불러오지 못했습니다.")
    SettingBlock("데스크탑 제공 모델") {
        ModelInstaller.all(context).filter { !it.legacy || ModelInstaller.installed(it) }.forEach { model ->
            val using = prefs.selectedGGUF == model.file.substringAfterLast('/')
            Column(Modifier.fillMaxWidth().background(LilacStyle.background, RoundedCornerShape(LilacStyle.radius))
                .border(1.dp, if (using) LilacStyle.accent else LilacStyle.line, RoundedCornerShape(LilacStyle.radius)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text)
                Text(model.note, fontSize = 12.sp, color = LilacStyle.muted)
                val progress = ModelInstaller.progress[model.id]
                when {
                    progress != null -> {
                        LinearProgressIndicator({ progress.toFloat() }, Modifier.fillMaxWidth(), color = LilacStyle.accent, trackColor = LilacStyle.line)
                        LilacOutlineButton("다운로드 중단") { ModelInstaller.cancel(model.id) }
                    }
                    ModelInstaller.installed(model) -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LilacOutlineButton(if (using) "사용 중" else "사용", enabled = !using) { library.update { it.copy(selectedGGUF = model.file.substringAfterLast('/')) } }
                        LilacOutlineButton("삭제", tint = Color.Red) { ModelInstaller.remove(model); if (using) library.update { it.copy(selectedGGUF = "") } }
                    }
                    else -> LilacOutlineButton("받기 / 이어받기") { ModelInstaller.install(model) }
                }
            }
        }
        Text("Hugging Face 토큰", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingField(token, { token = it }, "접근 제한 모델을 받을 때만 필요합니다", secret = true, modifier = Modifier.weight(1f))
            LilacOutlineButton("저장") { runCatching { SecureKeys.save(token.trim(), "huggingface"); token = "" }.onFailure { error = it.message } }
        }
        ModelInstaller.error?.let { SettingNote(it, Color.Red) }
    }
    SettingBlock("내 GGUF 모델", if (files.isEmpty()) "가져온 모델이 없습니다. 기기의 GGUF 파일을 가져오거나 아래에서 받으세요." else null) {
        files.forEach { file ->
            val using = prefs.selectedGGUF == file.name
            Column(Modifier.fillMaxWidth().background(LilacStyle.background, RoundedCornerShape(LilacStyle.radius))
                .border(1.dp, if (using) LilacStyle.accent else LilacStyle.line, RoundedCornerShape(LilacStyle.radius)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, maxLines = 2, modifier = Modifier.weight(1f))
                    LilacOutlineButton(if (using) "사용 중" else "사용", enabled = !using) { library.update { it.copy(selectedGGUF = file.name) } }
                    LilacOutlineButton("삭제", tint = Color.Red) { file.delete(); if (using) library.update { it.copy(selectedGGUF = "") }; files = LocalModelFiles.files() }
                }
                if (ModelInstaller.all(context).none { it.file.substringAfterLast('/') == file.name }) InputOptionsEditor(file.name)
            }
        }
        LilacOutlineButton(if (copying) "모델 가져오는 중…" else "GGUF 파일 가져오기", enabled = !copying) { picker.launch(arrayOf("*/*")) }
    }
    SettingBlock("주소로 받기", "GGUF 파일의 HTTPS 주소를 넣으세요. 모델 파일은 앱 안에 저장됩니다.") {
        LilacOutlineButton("Hugging Face 모델 검색") { searchingModels = true }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingField(address, { address = it }, "https://…/model.gguf", modifier = Modifier.weight(1f))
            LilacOutlineButton(if (ModelInstaller.progress["address"] != null) "받는 중…" else "받기") {
                val url = address.trim()
                if (!url.startsWith("https://") || !url.substringBefore('?').lowercase().endsWith(".gguf")) error = "GGUF 파일의 HTTPS 주소를 입력하세요."
                else ModelInstaller.download("address", url, java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8"))
            }
        }
        ModelInstaller.progress["address"]?.let { LinearProgressIndicator({ it.toFloat() }, Modifier.fillMaxWidth(), color = LilacStyle.accent, trackColor = LilacStyle.line) }
    }
    SettingBlock("로컬 AI 세부 설정", "값을 바꾸면 다음 번역부터 적용됩니다. 모델별 권장값을 켜 두면 모델에 맞는 프롬프트와 샘플링을 씁니다." + if (LocalInference.lastRun.isEmpty()) "" else " 마지막 실행: " + LocalInference.lastRun) {
        Row { Text("모델별 권장 프롬프트·샘플링", fontSize = 13.sp, color = LilacStyle.text, modifier = Modifier.weight(1f))
            Switch(prefs.modelSampling, { on -> library.update { it.copy(modelSampling = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = LilacStyle.accent)) }
        Text("컨텍스트 ${prefs.contextSize}", fontSize = 12.sp, color = LilacStyle.muted)
        SettingChoices(listOf(2048, 4096, 8192).map { it to it.toString() }, prefs.contextSize) { v -> library.update { it.copy(contextSize = v) } }
        Text("스레드 " + if (prefs.threads == 0) "자동" else prefs.threads.toString(), fontSize = 12.sp, color = LilacStyle.muted)
        SettingChoices(listOf(0, 2, 4, 6, 8).map { it to if (it == 0) "자동" else it.toString() }, prefs.threads) { v -> library.update { it.copy(threads = v) } }
        if (!prefs.modelSampling) {
            Text("온도 %.2f".format(prefs.temperature), fontSize = 12.sp, color = LilacStyle.muted)
            SettingSlider(prefs.temperature, 0f..1.5f, 0.05f) { v -> library.update { it.copy(temperature = v) } }
            Text("Top-p %.2f · Top-k ${prefs.topK} · 반복 억제 %.2f".format(prefs.topP, prefs.repetitionPenalty), fontSize = 12.sp, color = LilacStyle.muted)
            SettingSlider(prefs.topP, 0.1f..1f, 0.05f) { v -> library.update { it.copy(topP = v) } }
            Text("최대 토큰 ${prefs.maxTokens}", fontSize = 12.sp, color = LilacStyle.muted)
            SettingChoices(listOf(256, 512, 1024).map { it to it.toString() }, prefs.maxTokens) { v -> library.update { it.copy(maxTokens = v) } }
            Text("Thinking", fontSize = 12.sp, color = LilacStyle.muted)
            SettingChoices(listOf("off" to "끄기", "on" to "켜기"), prefs.thinking) { v -> library.update { it.copy(thinking = v) } }
            Text("프롬프트", fontSize = 12.sp, color = LilacStyle.muted)
            SettingField(prefs.prompt, { v -> library.update { it.copy(prompt = v) } }, "{source_text}", lines = 4)
        }
    }
    error?.let { SettingNote(it, Color.Red) }
    if (searchingModels) ModelSearchDialog(
        dismiss = { searchingModels = false },
        select = { address = it; searchingModels = false },
    )
}

@Composable
private fun ModelSearchDialog(dismiss: () -> Unit, select: (String) -> Unit) {
    val service = remember { IosServices() }
    DisposableEffect(service) { onDispose { service.close() } }
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var repo by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf(emptyList<ModelSearchResult>()) }
    var files by remember { mutableStateOf(emptyList<ModelFile>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(search) {
        val term = search?.first ?: return@LaunchedEffect
        loading = true
        error = null
        models = emptyList()
        service.await<List<ModelSearchResult>> { searchModels(term, it) }
            .onSuccess { models = it }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(repo) {
        val selected = repo ?: return@LaunchedEffect
        loading = true
        error = null
        files = emptyList()
        service.await<List<ModelFile>> { modelFiles(selected, it) }
            .onSuccess { files = it }
            .onFailure { error = it.message }
        loading = false
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(repo ?: "Hugging Face") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (repo == null) {
                    SettingField(query, { query = it }, "GGUF 모델 이름")
                    LilacOutlineButton("검색", enabled = query.isNotBlank()) { search = query.trim() to System.nanoTime() }
                } else {
                    LilacOutlineButton("검색 결과로") { repo = null; error = null; loading = false }
                }
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                error?.let { SettingNote(it, Color.Red) }
                if (!loading && error == null && search != null && (if (repo == null) models.isEmpty() else files.isEmpty())) {
                    SettingNote("GGUF 파일을 찾지 못했습니다.")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    if (repo == null) {
                        items(models, key = { it.id }) { model ->
                            TextButton(onClick = { repo = model.id }) { Text(model.id) }
                        }
                    } else {
                        items(files, key = { it.name }) { file ->
                            TextButton(onClick = { select(file.url) }) {
                                Text(file.name + " · " + android.text.format.Formatter.formatFileSize(context, file.size))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("닫기") } },
    )
}

/** iOS LocalModelOptionsEditor: a file of the user's own, how it is asked. */
@Composable
private fun InputOptionsEditor(file: String) {
    val library = LocalApp.current.library
    val options = library.preferences.localModelInputs[file] ?: LocalModelInputOptions()
    var prompt by remember(file) { mutableStateOf(options.customPrompt) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(next: LocalModelInputOptions) {
        runCatching { LocalInput.validate(next) }.onFailure { error = it.message; return }
        error = null
        library.update { it.copy(localModelInputs = it.localModelInputs + (file to next)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("입력 형식", fontSize = 12.sp, color = LilacStyle.muted)
        SettingChoices(listOf("auto" to "자동", "chat" to "일반 지시문", "translation" to "번역 전용"), options.inputMode) { save(options.copy(inputMode = it)) }
        Text("원문 언어", fontSize = 12.sp, color = LilacStyle.muted)
        SettingChoices(listOf("auto" to "자동", "ja" to "일본어", "en" to "영어", "zh" to "중국어", "ko" to "한국어"), options.sourceLanguage) { save(options.copy(sourceLanguage = it)) }
        Text("프롬프트 ({text}에 원문이 들어갑니다)", fontSize = 12.sp, color = LilacStyle.muted)
        SettingField(prompt, { prompt = it }, "비우면 모델에 맞는 기본 프롬프트", lines = 2)
        LilacOutlineButton("프롬프트 저장") { save(options.copy(customPrompt = prompt.trim())) }
        error?.let { SettingNote(it, Color.Red) }
    }
}
