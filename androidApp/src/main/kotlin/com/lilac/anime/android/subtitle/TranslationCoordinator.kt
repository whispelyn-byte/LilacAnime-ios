package com.lilac.anime.android.subtitle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lilac.anime.android.data.AppPreferences
import com.lilac.anime.android.data.SavedAnime
import com.lilac.anime.android.data.SecureKeys
import com.lilac.anime.android.data.await
import com.lilac.anime.android.player.PlaybackFailure
import com.lilac.anime.android.localai.*
import com.lilac.anime.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** iOS TranslationCoordinator, the API part: a subtitle translated from the scene being watched outward, written as it
 *  goes, kept by its content and settings; a provider that fails hands over to the next with a key. */
class TranslationCoordinator {
    var running by mutableStateOf(false); private set
    var progress by mutableDoubleStateOf(0.0); private set
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    private val service = IosServices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var generation = 0L
    private val workingFiles = mutableSetOf<File>()
    private val json = Json

    fun translate(file: File, preferences: AppPreferences, position: () -> Double, seekRevision: () -> Long, anime: SavedAnime?, fresh: Boolean = false, localOnly: Boolean = false, completion: (File) -> Unit) {
        cancel(); if (fresh) service.clearTranslationCache()
        running = true; error = null; status = null; progress = 0.0
        val token = ++generation
        job = scope.launch {
            try {
                val ext = file.extension
                val dropCaptions = preferences.dropCaptions
                val parsed = withContext(Dispatchers.IO) {
                    val content = SubtitleFiles.text(file)
                    Triple(content, SubtitleTools.lines(content, ext), SubtitleTools.cues(content, ext)) to SubtitleTools.translationBlanks(content, ext)
                }
                val (content, originals, cues) = parsed.first
                val blanks = parsed.second
                val lines = if (dropCaptions) originals.map { DesktopCaptions.drop(it) } else originals
                if (lines.isEmpty()) throw PlaybackFailure("번역할 자막이 없습니다.")
                // The settings that change a translation are part of its cache name (as iOS), keys by their hash only.
                val settings = JSONObject().apply {
                    put("desktopPrompt", "prompt-3/local-5/bilingual-1/requests-2"); put("subtitleOutput", "plain-vtt-1")
                    put("provider", preferences.translationProvider); put("model", preferences.translationModel)
                    put("models", JSONObject(preferences.translationModels.toSortedMap() as Map<*, *>)); put("cloudFallback", preferences.cloudFallback)
                    put("credentials", JSONArray((listOf("gemini", "openai", "qwen", "deepl") + if (SecureKeys.load("claude").isEmpty()) emptyList() else listOf("claude")).map { SubtitleFiles.key(SecureKeys.load(it)) }))
                    put("region", preferences.qwenRegion); put("glossary", preferences.translationGlossary); put("fallback", preferences.translationFallback)
                    put("localOnly", localOnly); put("local", preferences.selectedGGUF); put("context", preferences.contextSize); put("maxTokens", preferences.maxTokens)
                    put("temperature", preferences.temperature); put("topP", preferences.topP); put("topK", preferences.topK); put("repetition", preferences.repetitionPenalty)
                    put("before", preferences.contextCues); put("after", preferences.prefetchAhead); put("modelSampling", preferences.modelSampling); put("thinking", preferences.thinking)
                    put("prompt", preferences.prompt); put("localInput", "input-1:" + preferences.selectedLocalInput.toString())
                    if (dropCaptions) put("dropCaptions", "drop-captions-1")
                    if (preferences.rulesExtra.isNotEmpty() || preferences.rulesCustom.isNotEmpty()) put("rules", JSONArray(listOf(preferences.rulesExtra, preferences.rulesCustom)))
                }
                val cacheID = SubtitleFiles.key(content + sortedJson(settings))
                SubtitleFiles.translations.mkdirs()
                val resultFile = File(SubtitleFiles.translations, "$cacheID.v2.vtt")
                val partialFile = File(SubtitleFiles.translations, "$cacheID.lines.json")
                if (fresh) { resultFile.delete(); partialFile.delete() }
                if (resultFile.isFile) {
                    if (token != generation) return@launch
                    completion(resultFile); running = false; progress = 1.0; status = "저장된 번역을 불러왔습니다."; return@launch
                }
                val kept = withContext(Dispatchers.IO) {
                    runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), partialFile.readText()) }.getOrDefault(emptyMap()).filterValues { it.isNotEmpty() }
                }.toMutableMap()
                for (index in blanks) lines.getOrNull(index)?.let { kept[it] = "" }
                for (line in lines) if (line.none { it.isLetter() }) kept[line] = line
                val working = File(SubtitleFiles.translations, "working-" + UUID.randomUUID() + ".vtt")
                workingFiles += working
                val writer = Dispatchers.IO.limitedParallelism(1)
                fun publish() {
                    if (token != generation) return
                    val translated = lines.indices.map { kept[lines[it]] ?: originals[it] }
                    val saved = kept.toMap()
                    progress = lines.count { kept[it] != null }.toDouble() / lines.size
                    scope.launch(writer) {
                        runCatching {
                            working.writeText(SubtitleTools.translatedVtt(content, ext, translated))
                            partialFile.writeText(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), saved))
                        }.onSuccess { withContext(Dispatchers.Main) { if (token == generation && running) completion(working) } }
                    }
                }
                if (kept.isNotEmpty()) { publish(); status = "중단된 번역을 이어서 진행합니다." }
                var provider = preferences.translationProvider
                val tried = mutableSetOf(provider)
                var terminal: Throwable? = null
                val context = buildList {
                    if (anime != null) {
                        add("Anime: " + anime.title + " / " + anime.anime.native)
                        add("Genres: " + anime.anime.genres.joinToString(", "))
                    }
                    add(AnimeGlossary.hints(lines.joinToString("\n"), emptyList(), preferences.translationGlossary))
                }.filter { it.isNotBlank() }.joinToString("\n")
                // The local model's state (iOS: localKind, localLines, attemptedLocal).
                var localKind: String? = null
                var localLines = emptyList<String>()
                var languageInput = false
                val attemptedLocal = mutableSetOf<String>()
                var localFailure: Throwable? = null
                val input = preferences.selectedLocalInput
                while (lines.any { kept[it] == null }) {
                    ensureActive()
                    try {
                        if (provider == "local") {
                            LocalInput.validate(input)
                            if (preferences.selectedGGUF.isEmpty()) throw PlaybackFailure("설정에서 GGUF 모델을 선택하세요.")
                            if (localKind == null) {
                                val seen = mutableSetOf<String>()
                                localLines = lines.filter { kept[it] == null && seen.add(it) }
                                val template = LocalInference.chatTemplate(preferences)
                                languageInput = LocalInput.usesTranslationInput(input, template)
                                localKind = if (input.inputMode == "chat") "chat" else DesktopTranslationPrompt.kind(preferences.selectedGGUF, template)
                            }
                            val attempted = kept + attemptedLocal.filter { it !in kept }.associateWith { "" }
                            val first = priority(cues, lines, attempted, position(), 1).firstOrNull()
                                ?: throw (localFailure ?: PlaybackFailure("로컬 모델이 일부 자막을 번역하지 못했습니다."))
                            val source = lines[first]
                            val at = localLines.indexOf(source).coerceAtLeast(0)
                            val before = if (!preferences.modelSampling) lines.subList(maxOf(0, first - preferences.contextCues), first).joinToString("\n")
                                else localLines.subList(maxOf(0, at - 2), at).joinToString("\n")
                            val after = lines.drop(first + 1).take(preferences.prefetchAhead).joinToString("\n")
                            val terms = AnimeGlossary.hints(source, emptyList(), preferences.translationGlossary)
                            val plain = LocalTranslationPrompt.build(preferences.selectedGGUF, preferences.prompt, source, before, after, preferences.thinking) +
                                (if (terms.isEmpty()) "" else "\n표기 기준:\n" + terms)
                            val desktop = DesktopTranslationPrompt.request(localKind ?: "chat", source, before, AnimeGlossary.localTerms(source, emptyList(), preferences.translationGlossary),
                                AnimeGlossary.localCast(emptyList()), preferences.rulesExtra, preferences.rulesCustom)
                            val useDesktop = preferences.modelSampling || languageInput || input.customPrompt.isNotEmpty() || input.inputMode == "chat"
                            val prompt = when { languageInput -> source; input.customPrompt.isNotEmpty() -> LocalInput.prompt(input, source); useDesktop -> desktop.prompt; else -> plain }
                            var settings = preferences
                            if (useDesktop) settings = settings.copy(temperature = desktop.temperature, topP = desktop.topP, topK = desktop.topK, repetitionPenalty = desktop.repetition,
                                maxTokens = DesktopTranslationPrompt.maxTokens(source), thinking = "off")
                            if (languageInput) settings = settings.copy(temperature = 0.0) else if (input.customPrompt.isNotEmpty()) settings = settings.copy(temperature = 0.3)
                            attemptedLocal += source
                            try {
                                var cleaned = ""
                                for (attempt in 0 until if (useDesktop) 3 else 1) {
                                    if (attempt > 0 && localKind == "jako" && !languageInput) settings = settings.copy(temperature = 0.5)
                                    val response = LocalInference.generate(prompt, settings, if (languageInput) LocalInput.language(input, source) else null)
                                    cleaned = if (useDesktop) DesktopTranslationPrompt.clean(response, source) else LocalTranslationPrompt.clean(response)
                                    if (languageInput && !LocalInput.acceptsTranslation(cleaned)) cleaned = ""
                                    if (!DesktopTranslationPrompt.needsRetry(cleaned)) break
                                }
                                if (cleaned.isNotEmpty()) kept[source] = cleaned else localFailure = PlaybackFailure("로컬 모델이 빈 번역을 반환했습니다.")
                            } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) { localFailure = failure }
                            publish(); continue
                        }
                        val config = TranslationConfig(provider, SecureKeys.load(provider),
                            preferences.translationModels[provider] ?: if (provider == preferences.translationProvider) preferences.translationModel else "",
                            preferences.qwenRegion, context, preferences.rulesExtra, preferences.rulesCustom)
                        cloudSchedule(lines, cues, provider, config, position, seekRevision, { kept }) { additions -> additions.forEach { (k, v) -> kept.putIfAbsent(k, v) }; publish() }
                        publish()
                    } catch (failure: CancellationException) { throw failure } catch (failure: Throwable) {
                        val next = listOf("gemini", "openai", "deepl", "qwen", "claude").firstOrNull { it !in tried && SecureKeys.load(it).isNotEmpty() }
                        val localReady = preferences.selectedGGUF.isNotEmpty() && LocalAI.available && File(LocalModelFiles.directory, preferences.selectedGGUF).isFile
                        if (provider != "local" && preferences.cloudFallback && next != null) {
                            status = "$provider 번역 실패 · ${next}로 이어서 번역합니다."
                            provider = next; tried += next
                        } else if (provider != "local" && "local" !in tried && preferences.translationFallback && localReady) {
                            status = "$provider 번역을 계속할 수 없어 로컬 AI로 이어서 번역합니다."
                            provider = "local"; tried += "local"
                        } else if (provider == "local" && !localOnly && preferences.translationFallback && next != null) {
                            status = "로컬 AI 번역 실패 · ${next}로 이어서 번역합니다."
                            provider = next; tried += next
                        } else { terminal = failure; break }
                    }
                }
                if (token != generation) return@launch
                val unique = lines.filter { line -> line.any { it.isLetter() } && kept[line] != "" }.toSet()
                val translatedCount = unique.count { kept[it] != null }
                val failed = unique.size - translatedCount
                if (translatedCount == 0) throw terminal ?: PlaybackFailure("번역 결과를 받지 못했습니다.")
                // A partial result is usable, but never stands for a finished one.
                val destination = if (failed == 0) resultFile else File(SubtitleFiles.translations, "$cacheID-partial-${UUID.randomUUID()}.vtt")
                val finished = lines.indices.map { kept[lines[it]] ?: originals[it] }
                withContext(writer) { destination.writeText(SubtitleTools.translatedVtt(content, ext, finished)) }
                if (token != generation) return@launch
                if (failed == 0) partialFile.delete()
                completion(destination); progress = 1.0; running = false
                if (failed > 0) status = "${failed}줄은 번역하지 못해 원문으로 남겼습니다."
            } catch (failure: CancellationException) { if (token == generation) running = false }
            catch (failure: Throwable) { if (token == generation) { error = failure.message; running = false } }
        }
    }

    private fun sortedJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + sortedJson(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { sortedJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    /** iOS CloudSubtitleScheduler: batches sized per provider, a few at once, the ones nearest the playing scene first;
     *  after a seek the new place is asked at once. A line an API leaves out twice is given up. */
    private suspend fun cloudSchedule(lines: List<String>, cues: List<SubtitleCue>, provider: String, config: TranslationConfig, position: () -> Double,
                                      seekRevision: () -> Long, cached: () -> Map<String, String>, save: (Map<String, String>) -> Unit) = coroutineScope {
        val batchLines = when (provider) { "gemini" -> 600; "openai" -> 150; "deepl" -> 50; "claude" -> 200; else -> 100 }
        val batchCharacters = when (provider) { "gemini" -> 30000; "openai" -> 9000; "deepl" -> 20000; "claude" -> 12000; else -> 6000 }
        val parallel = when (provider) { "gemini", "deepl" -> 2; "claude" -> 3; else -> 4 }
        val active = mutableMapOf<Long, List<Int>>()
        val results = Channel<Triple<Long, List<Int>, Result<List<String>>>>(Channel.UNLIMITED)
        val missing = mutableMapOf<String, Int>()
        var first = true; var lastSeek = seekRevision(); var ids = 0L
        val jobs = mutableListOf<Job>()
        fun pending(rush: Boolean): List<Int> {
            val reserved = active.values.flatten().map { lines[it] }.toSet()
            val translated = cached().toMutableMap()
            missing.forEach { (line, attempts) -> if (attempts >= 2) translated[line] = "" }
            if (!rush) reserved.forEach { translated[it] = "" }
            val retrying = lines.any { cached()[it] == null && missing[it] == 1 }
            val limit = if (retrying) 8 else if (rush || first) 40 else batchLines
            val candidates = priority(cues, lines, translated, position(), limit)
            var count = 0
            return candidates.takeWhile { count += lines[it].length; count <= batchCharacters || count == lines[it].length }
        }
        fun launchBatch(indices: List<Int>) {
            if (indices.isEmpty()) return
            val id = ++ids; active[id] = indices; first = false
            val texts = indices.map { lines[it] }
            jobs += launch { results.send(Triple(id, indices, service.await<List<String>> { translateLines(texts, config, it) })) }
        }
        try {
            while (lines.any { cached()[it] == null && (missing[it] ?: 0) < 2 }) {
                while (active.size < parallel) { val next = pending(false); if (next.isEmpty()) break; launchBatch(next) }
                val event = withTimeoutOrNull(250) { results.receive() }
                if (event == null) {
                    if (seekRevision() != lastSeek && active.size <= parallel) {
                        val rush = pending(true)
                        rush.firstOrNull()?.let { next ->
                            if ((cues.getOrNull(next)?.startSeconds ?: 0.0) <= position() + 60 && active.values.none { next in it && it.size <= 40 }) launchBatch(rush)
                        }
                        lastSeek = seekRevision()
                    }
                    if (active.isEmpty() && pending(false).isEmpty()) break
                    continue
                }
                val (id, indices, outcome) = event
                active.remove(id)
                val output = outcome.getOrNull()
                if (output == null || output.size != indices.size) throw PlaybackFailure(outcome.exceptionOrNull()?.message ?: "번역 줄 수가 일치하지 않습니다.")
                val existing = cached()
                val additions = mutableMapOf<String, String>()
                output.forEachIndexed { index, value ->
                    val line = lines[indices[index]]
                    if (existing[line] != null) return@forEachIndexed
                    if (value.isEmpty()) missing[line] = (missing[line] ?: 0) + 1 else additions[line] = value
                }
                save(additions)
            }
        } finally { jobs.forEach { it.cancel() } }
        if (missing.any { it.value >= 2 && cached()[it.key] == null }) throw PlaybackFailure("API 응답에서 일부 자막 번역이 누락되었습니다.")
    }

    /** iOS TranslationPriority: the untranslated lines from the playing scene on, the ones already passed last. */
    private fun priority(cues: List<SubtitleCue>, lines: List<String>, translated: Map<String, String>, position: Double, limit: Int): List<Int> {
        val seen = mutableSetOf<String>()
        val candidates = lines.indices.filter { seen.add(lines[it]) && translated[lines[it]] == null }
        fun score(index: Int): Double { val start = cues.getOrNull(index)?.startSeconds ?: index.toDouble(); return (if (start < maxOf(0.0, position) - 5) 1_000_000.0 else 0.0) + start }
        return candidates.sortedWith(compareBy({ score(it) }, { it })).take(limit)
    }

    fun cancel() { if (running) { service.cancel(); LocalInference.cancel() }; generation++; job?.cancel(); job = null; running = false }
    fun shutdown() { cancel(); workingFiles.forEach { it.delete() }; workingFiles.clear(); scope.cancel(); service.close() }
}
