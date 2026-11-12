package com.lilac.anime.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.data.SavedAnime
import com.lilac.anime.android.data.await
import com.lilac.anime.android.data.displayTitle
import com.lilac.anime.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Date arithmetic in Korea, the week Monday to Sunday (iOS ScheduleWeek). */
object ScheduleWeek {
    private val zone = ZoneId.of("Asia/Seoul")
    fun today(): LocalDate = ZonedDateTime.now(zone).toLocalDate()
    fun dates(): List<LocalDate> { val monday = today().with(DayOfWeek.MONDAY); return (0..6).map { monday.plusDays(it.toLong()) } }
    fun key(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    fun label(date: LocalDate) = "${date.monthValue}.${date.dayOfMonth}"
    val todayIndex: Int get() = today().dayOfWeek.value - 1
}

/** Desktop weekly-schedule.js: titles compared without spacing, punctuation and symbols. */
private fun scheduleKey(title: String) = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).lowercase()
    .filter { it.isLetterOrDigit() }

/** 이번 주 방영표 (iOS WeeklyScheduleView): the week's days as tabs, the chosen day's entries by time, a known work opening
 *  its page and an unknown one searched. */
@Composable
fun WeeklyScheduleView(source: String, known: List<Anime>, refresh: Int, modifier: Modifier = Modifier) {
    val navigation = LocalApp.current.navigation
    val service = remember { IosServices() }
    DisposableEffect(Unit) { onDispose { service.close() } }
    var dates by remember { mutableStateOf(ScheduleWeek.dates()) }
    var selected by remember { mutableIntStateOf(ScheduleWeek.todayIndex) }
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WeeklyScheduleResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")
    LaunchedEffect(source, selected, ScheduleWeek.key(dates[0]), refresh, attempt) {
        expanded = false; result = null; error = null; loading = true
        val day = dates[selected].dayOfWeek.value % 7
        service.await<WeeklyScheduleResult> { weeklySchedule(source, day, ScheduleWeek.key(dates[selected]), ScheduleWeek.key(dates[0]), it) }
            .onSuccess { result = it }.onFailure { error = it.message }
        loading = false
    }
    val items = remember(result) {
        val collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)
        result?.items.orEmpty().sortedWith { a, b ->
            val x = a.time.ifEmpty { "99:99" }; val y = b.time.ifEmpty { "99:99" }
            if (x != y) x.compareTo(y) else collator.compare(a.title, b.title)
        }
    }
    fun matched(item: WeeklyScheduleEntry): Anime? {
        item.anime?.takeIf { it.source == source }?.let { return it }
        val names = listOf(item.title, item.originalTitle).map(::scheduleKey).filter { it.isNotEmpty() }
        return known.firstOrNull { anime -> anime.source == source && listOf(anime.title, anime.native, anime.english, anime.romaji).map(::scheduleKey).any { it in names } }
    }
    val heading = if (result?.native == true && result?.timed == false) (if (source == "linkani") "요일별 애니 목록" else "요일별 방영표") else "이번 주 방영표"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(heading, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LilacStyle.text, modifier = Modifier.weight(1f))
            Text("오늘", fontSize = 15.sp, color = LilacStyle.accent, modifier = Modifier.clickable { dates = ScheduleWeek.dates(); selected = ScheduleWeek.todayIndex }.padding(4.dp))
        }
        if (!(source == "linkani" && result?.native == true)) Text(ScheduleWeek.label(dates[0]) + " – " + ScheduleWeek.label(dates[6]), fontSize = 12.sp, color = LilacStyle.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weekdays.forEachIndexed { index, day ->
                val chosen = selected == index
                Column(Modifier.weight(1f).clickable { selected = index }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(day, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (chosen) LilacStyle.text else LilacStyle.muted)
                    Text(ScheduleWeek.label(dates[index]), fontSize = 11.sp, color = if (chosen) LilacStyle.text else LilacStyle.muted)
                    Text(if (dates[index] == ScheduleWeek.today()) "오늘" else " ", fontSize = 11.sp, color = if (chosen) LilacStyle.text else LilacStyle.muted)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (chosen) LilacStyle.accent else Color.Transparent))
                }
            }
        }
        if (loading) LilacProgress("방영표를 불러오는 중…")
        error?.let { Text(it, fontSize = 12.sp, color = LilacStyle.muted) }
        result?.let { value ->
            Text(value.note + " · ${items.size}개 일정", fontSize = 12.sp, color = LilacStyle.muted)
            if (items.isEmpty()) Text("이 요일에는 등록된 일정이 없습니다.", fontSize = 15.sp, color = LilacStyle.muted, modifier = Modifier.padding(vertical = 12.dp))
            (if (expanded) items else items.take(6)).forEach { item ->
                val anime = matched(item)
                val status = when {
                    item.cancelled -> "결방"; value.timed == false -> "방영 목록"; item.airingStatus == "aired" -> "방영됨"
                    item.airingStatus == "delayed" -> "방영 지연"; else -> "방영 예정"
                }
                val meta = buildList {
                    if (item.episode.isNotEmpty()) add(item.episode + "화")
                    addAll(item.genres.take(2))
                    if (item.track == "dub") add("영어 더빙")
                    val date = ScheduleWeek.key(dates[selected])
                    if (item.startDate.isNotEmpty() && item.startDate >= date) add(if (item.startDate == date) "첫방송" else item.startDate + " 첫방송")
                }.ifEmpty { listOf("애니메이션") }.joinToString(" · ")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { if (anime != null) navigation.open(anime, source) else navigation.search(item.title) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.time.ifEmpty { if (value.timed) "미정" else weekdays[selected] + "요일" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
                        Text(status, fontSize = 11.sp, color = LilacStyle.muted)
                    }
                    if (anime != null && anime.poster.isNotEmpty()) AnimeArtwork(anime.poster, Modifier.size(32.dp, 44.dp).clip(RoundedCornerShape(4.dp)), PosterFocus)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (anime != null) displayTitle(anime, source) else item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(meta, fontSize = 12.sp, color = LilacStyle.muted, maxLines = 2)
                    }
                }
            }
            if (items.size > 6) Text(if (expanded) "접기" else "더보기 (${items.size}개)", fontSize = 15.sp, color = LilacStyle.accent,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (error != null || result?.native == false) Text("다시 시도", fontSize = 15.sp, color = LilacStyle.accent, modifier = Modifier.clickable { attempt++ }.padding(4.dp))
    }
}

/** iOS DesktopRecentUpdates: a source's newest-episode lists, for 홈 > 최근 업데이트 and 내 목록 > 회차 업데이트순. */
object RecentUpdates {
    val supported = listOf("reanime", "miruro", "animenosub", "ohli24", "linkani")
    var home by mutableStateOf(emptyList<Anime>()); private set
    var homeNote by mutableStateOf(""); private set
    var libraryNote by mutableStateOf(""); private set
    private val service by lazy { IosServices() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = mutableMapOf<String, Pair<Long, Deferred<List<Anime>>>>()
    private var homeRequest = 0L
    private var libraryRequest = 0L

    private fun recent(source: String, pages: Int): Deferred<List<Anime>> {
        val key = "$source:$pages"
        cache[key]?.let { (at, task) -> if (System.currentTimeMillis() - at < 300_000) return task }
        val task = scope.async {
            val items = mutableListOf<Anime>(); val seen = mutableSetOf<String>()
            for (page in 1..pages) {
                val added = service.await<List<Anime>> { sourceUpdates(source, page, it) }.getOrThrow().filter { seen.add(it.id) }
                if (added.isEmpty()) break
                items += added
            }
            items.toList()
        }
        cache[key] = System.currentTimeMillis() to task
        return task
    }
    suspend fun loadHome(source: String) {
        val request = ++homeRequest; home = emptyList(); homeNote = "새 회차 목록을 불러오는 중…"
        runCatching { recent(source, 1).await() }.onSuccess { items ->
            if (request != homeRequest) return
            home = items.take(30)
            homeNote = if (source == "miruro") "최근 2주간 방영된 회차 기준입니다." else if (items.isEmpty()) "최근 업데이트된 회차가 없습니다." else "소스의 최근 회차 업데이트 목록입니다."
        }.onFailure { if (request == homeRequest) { homeNote = it.message ?: "목록을 불러오지 못했습니다."; cache.remove("$source:1") } }
    }
    // --- 내 목록 (desktop 19110b2, GitHub issue #28; iOS DesktopRecentUpdates) ---
    // A saved work was the card of the day it was saved: its episode count stood still, and 회차 업데이트순 knew a date only
    // for RE:Anime's and Miruro's works in their recent lists. What each saved work has now is kept apart from the saved
    // card ("libraryFacts" in the app's preferences, so the list synced with the other devices is not rewritten): its
    // counts and the day of its latest episode, from the sources' recent lists and the work's own page (LibraryFacts).
    // 링크애니 dates no episode: its works are dated by when a new episode was first seen here.
    @kotlinx.serialization.Serializable
    data class Fact(val available: Int = -1, val total: Int = -1, val status: String = "", val updatedAt: String = "", val seenAt: Long? = null, val checkedAt: Long? = null)
    private val factsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val factsSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Fact.serializer())
    var facts by mutableStateOf(emptyMap<String, Fact>()); private set
    private var store: com.lilac.anime.android.data.Navigation? = null
    private var feedRank = emptyMap<String, Int>()
    private var refreshing = false
    private fun attach(navigation: com.lilac.anime.android.data.Navigation) {
        if (store != null) return
        store = navigation
        facts = runCatching { factsJson.decodeFromString(factsSerializer, navigation.string("libraryFacts") ?: "{}") }.getOrDefault(emptyMap())
    }
    private fun keepFacts(favorites: List<SavedAnime>) {
        val saved = favorites.map { it.id }.toSet(); facts = facts.filterKeys { it in saved }
        store?.putString("libraryFacts", factsJson.encodeToString(factsSerializer, facts))
    }
    private fun uploaded(anime: Anime) = anime.availableEpisodes ?: 0
    /** What a list or a page says of a saved work now. A value it does not give leaves the known one; the later day stays. */
    private fun learn(saved: SavedAnime, available: Int, total: Int, status: String, updatedAt: String, checked: Boolean) {
        val before = uploaded(shown(saved))
        var fact = facts[saved.id] ?: Fact()
        if (available >= 0) fact = fact.copy(available = available)
        if (total >= 0) fact = fact.copy(total = total)
        if (status.isNotEmpty()) fact = fact.copy(status = status)
        val day = LibraryFacts.day(updatedAt)
        if (day.isNotEmpty() && day > fact.updatedAt) fact = fact.copy(updatedAt = day)
        if (fact.checkedAt != null && fact.available > before) fact = fact.copy(seenAt = System.currentTimeMillis())
        if (checked) fact = fact.copy(checkedAt = System.currentTimeMillis())
        facts = facts + (saved.id to fact)
    }
    /** The card as it is now (both orders show it). */
    fun shown(saved: SavedAnime): Anime {
        val fact = facts[saved.id] ?: return saved.anime
        return LibraryFacts.shown(saved.anime, LibraryFact(fact.available, fact.total, -1, fact.status, fact.updatedAt))
    }
    /** The saved works whose own page is due, those never looked at first, 40 a visit, 3 at a time. */
    private suspend fun refreshDue(favorites: List<SavedAnime>) {
        if (refreshing) return
        val now = System.currentTimeMillis()
        val due = favorites.filter { it.source in LibraryFacts.sources }
            .filter { saved -> facts[saved.id]?.checkedAt?.let { now - it > LibraryFacts.dueAfterMillis(shown(saved)) } ?: true }
            .sortedBy { facts[it.id]?.checkedAt ?: 0L }.take(40)
        if (due.isEmpty()) return
        refreshing = true
        try {
            for (chunk in due.chunked(3)) {
                val found = coroutineScope { chunk.map { saved -> async { saved to service.await<LibraryFact> { libraryFact(saved.anime, saved.source, it) }.getOrNull() } }.awaitAll() }
                for ((saved, fact) in found) {
                    if (fact != null) learn(saved, fact.availableEpisodes, fact.totalEpisodes, fact.status, fact.updatedAt, checked = true)
                    // A page that could not be read is tried again in half an hour.
                    else facts = facts + (saved.id to (facts[saved.id] ?: Fact()).copy(checkedAt = System.currentTimeMillis() - 3 * 3_600_000L + 1_800_000L))
                }
            }
        } finally { refreshing = false }
        keepFacts(favorites)
    }
    suspend fun loadLibrary(favorites: List<SavedAnime>, order: String, navigation: com.lilac.anime.android.data.Navigation) {
        attach(navigation)
        val request = ++libraryRequest
        if (order != "updated") { refreshDue(favorites); return }
        libraryNote = "저장한 작품의 최근 회차 목록을 확인하는 중…"
        val sources = favorites.map { it.source }.distinct().sorted()
        val saved = favorites.associateBy { it.id }
        val ranks = mutableMapOf<String, Int>(); val failed = mutableListOf<String>()
        for (source in sources.filter { it in supported }) {
            runCatching { recent(source, 8).await() }.onSuccess { list ->
                list.forEachIndexed { rank, anime ->
                    val id = "$source:${anime.id}"; val work = saved[id] ?: return@forEachIndexed
                    ranks[id] = rank
                    val before = uploaded(shown(work))
                    // A list that dates the work settles it; one that does not (Animenosub, 애니24) leaves its page to be read
                    // when the work has no day yet or shows more episodes than before. 링크애니 has no dates anywhere.
                    val settled = LibraryFacts.day(anime.updatedAt).isNotEmpty() || source !in LibraryFacts.dated
                    learn(work, uploaded(anime).takeIf { it > 0 } ?: -1, anime.totalEpisodes ?: -1, anime.status, anime.updatedAt, settled)
                    if (!settled && (facts[id]?.updatedAt.isNullOrEmpty() || uploaded(shown(work)) > before)) facts = facts + (id to facts.getValue(id).copy(checkedAt = null))
                }
            }.onFailure { failed += ContentSources.name(source); cache.remove("$source:8") }
        }
        if (request != libraryRequest) return
        feedRank = ranks; keepFacts(favorites)
        val unsupported = sources.filter { it !in supported }.map(ContentSources::name)
        val note = "최신 회차가 나온 날짜순입니다. 날짜를 알 수 없는 작품은 뒤에 둡니다." +
            (if (unsupported.isEmpty()) "" else " ${unsupported.joinToString("·")}: 업데이트순 미지원.") + (if (failed.isEmpty()) "" else " ${failed.joinToString("·")}: 목록을 불러오지 못했습니다.")
        libraryNote = "$note 저장한 작품의 최신 회차를 확인하는 중…"
        refreshDue(favorites)
        if (request == libraryRequest) libraryNote = note
    }
    /** The latest episode's day, newest first, whatever the source; works without a day follow, those in their source's
     *  recent list first (in its order), then the rest as saved. */
    fun ordered(favorites: List<SavedAnime>): List<SavedAnime> {
        fun time(saved: SavedAnime): String {
            val fact = facts[saved.id]
            return fact?.updatedAt?.takeIf { it.isNotEmpty() } ?: fact?.seenAt?.let { java.time.Instant.ofEpochMilli(it).toString().take(10) } ?: ""
        }
        return favorites.withIndex().sortedWith { l, r ->
            val a = time(l.value); val b = time(r.value)
            val ra = feedRank[l.value.id]; val rb = feedRank[r.value.id]
            when {
                a.isEmpty() != b.isEmpty() -> if (a.isNotEmpty()) -1 else 1
                a.isNotEmpty() -> if (a == b) l.index.compareTo(r.index) else b.compareTo(a)
                (ra != null) != (rb != null) -> if (ra != null) -1 else 1
                ra == null || rb == null -> l.index.compareTo(r.index)
                l.value.source != r.value.source -> l.value.source.compareTo(r.value.source)
                else -> if (ra == rb) l.index.compareTo(r.index) else ra.compareTo(rb)
            }
        }.map { it.value }
    }
}
