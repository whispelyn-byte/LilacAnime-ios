package com.lilac.anime.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.android.LocalApp
import com.lilac.anime.android.data.CatalogModel
import com.lilac.anime.shared.*
import java.util.Calendar

/** A field as the desktop's inputs, with its placeholder. */
@Composable
fun LilacTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, fontSize: Int = 14,
                   keyboard: KeyboardType = KeyboardType.Text, onSubmit: (() -> Unit)? = null) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, fontSize = fontSize.sp, color = LilacStyle.muted, maxLines = 1)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = fontSize.sp, color = LilacStyle.text),
            cursorBrush = SolidColor(LilacStyle.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }), modifier = Modifier.fillMaxWidth())
    }
}

/** Posters in the adaptive grid every list uses (GridItem(.adaptive(minimum: 150))), with the page's header above. */
@Composable
fun PosterGrid(items: List<Anime>, source: String, header: LazyGridScope.() -> Unit, footer: LazyGridScope.() -> Unit = {}, onEnd: (() -> Unit)? = null) {
    val navigation = LocalApp.current.navigation
    val state = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 4 } ?: false } }
    LaunchedEffect(nearEnd) { if (nearEnd) onEnd?.invoke() }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), state = state, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = LilacStyle.gutter, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        header()
        items(items, key = { it.id }) { anime -> AnimePosterCard(anime) { navigation.open(anime, source) } }
        footer()
    }
}

fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun sortLabel(value: String, source: String) = when (value) {
    "popular" -> "인기순"; "year" -> "최신순"; "score" -> "평점순"
    "updated" -> if (source == "miruro") "작품 정보 업데이트순" else "회차 업데이트순"
    else -> "기본 순서"
}

private fun filterLabel(value: String, field: String): String {
    if (field == "연도" && value.endsWith("s") && value.dropLast(1).toIntOrNull() != null) return value.dropLast(1) + "년대"
    if (field == "장르") {
        val key = value.lowercase().replace("-", " ")
        return mapOf("action" to "액션", "adventure" to "모험", "comedy" to "코미디", "drama" to "드라마", "fantasy" to "판타지", "horror" to "공포",
            "mystery" to "미스터리", "romance" to "로맨스", "sci fi" to "SF", "slice of life" to "일상", "sports" to "스포츠", "supernatural" to "초자연",
            "psychological" to "심리", "thriller" to "스릴러", "music" to "음악", "mecha" to "메카", "ecchi" to "에치", "mahou shoujo" to "마법소녀")[key] ?: value
    }
    return mapOf("TV" to "TV 애니", "TV_SHORT" to "단편 TV", "MOVIE" to "극장판", "ONA" to "웹 애니", "SPECIAL" to "스페셜", "MUSIC" to "뮤직비디오",
        "LIVE ACTION" to "실사", "BD" to "BD", "CM" to "광고", "PV" to "프로모션 영상", "TV SPECIAL" to "TV 스페셜",
        "WINTER" to "1분기", "SPRING" to "2분기", "SUMMER" to "3분기", "FALL" to "4분기")[value.uppercase()] ?: value
}

private data class CatalogSelection(val genre: String = "", val format: String = "", val year: String = "", val season: String = "", val extras: Map<String, String> = emptyMap()) {
    val active get() = listOf(genre, format, year, season).any { it.isNotEmpty() } || extras.values.any { it.isNotEmpty() }
}

/** 전체 (iOS DesktopFullCatalog): the title, the source and search, the sort chips, 상세 필터 and the posters. The
 *  Korean-title index that lists a whole source offline comes with stage 4; until then every source lists from the site. */
@Composable
fun FullCatalogScreen() {
    val app = LocalApp.current
    val library = app.library
    val navigation = app.navigation
    val source = library.preferences.source
    val model = remember { CatalogModel() }
    DisposableEffect(Unit) { onDispose { model.close() } }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(CatalogSelection()) }
    var applied by remember { mutableStateOf(CatalogSelection()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val sorts = when {
        source == "reanime" && applied.extras.values.any { it.isNotEmpty() } -> listOf("popular", "year", "score")
        applied.active -> model.filters?.sorts ?: listOf("default")
        source == "linkkf" -> listOf("default", "year")
        source == "linkani" || source == "ohli24" -> listOf("default", "updated")
        else -> listOf("popular", "year", "updated", "score")
    }
    var sort by remember { mutableStateOf(navigation.string("allSort:$source")?.takeIf { it in sorts } ?: sorts.first()) }
    // Miruro, Animenosub and 링크애니, a filter or 회차 업데이트순 list from the site; the rest from the gathered index.
    val remoteSource = (applied.active && !(source == "reanime" && sort == "year" && applied.extras.values.all { it.isEmpty() })) ||
        sort == "updated" || source in listOf("miruro", "animenosub", "linkani")
    val catalog = com.lilac.anime.android.data.DesktopCatalog
    val gathered = catalog.catalogs[source].orEmpty()
    val names = if (query.isNotBlank()) catalog.names else emptyMap()
    val local by produceState(emptyList<Anime>(), gathered, names, query, sort, remoteSource) {
        if (remoteSource) { value = emptyList(); return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val wanted = com.lilac.anime.android.data.TitleKeys.key(query)
            val filtered = gathered.filter { item ->
                wanted.isEmpty() || (listOf(item.title, catalog.names[item.id]?.korean ?: "", catalog.names[item.id]?.english ?: "") + catalog.names[item.id]?.aliases.orEmpty())
                    .any { com.lilac.anime.android.data.TitleKeys.key(it).contains(wanted) }
            }.map { it.anime }
            val today = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.YEAR) * 10000 + (it.get(java.util.Calendar.MONTH) + 1) * 100 + it.get(java.util.Calendar.DAY_OF_MONTH) }
            if (sort == "default") filtered else DesktopAnimeMetadata.sortedCatalog(filtered, sort, today)
        }
    }
    LaunchedEffect(source) { if (!catalog.running && (catalog.catalogs[source].isNullOrEmpty() || catalog.needsRefresh(source))) catalog.start(source) }
    val values = if (remoteSource) model.items else local
    fun reload() {
        if (!remoteSource) return
        model.source = source; model.query = query; model.mode = "catalog"
        model.sort = if (sort == "default") "" else sort
        model.genre = applied.genre; model.format = applied.format; model.year = applied.year; model.season = applied.season; model.extras = applied.extras
        model.load()
    }
    LaunchedEffect(Unit) { model.source = source; model.loadFilters() }
    LaunchedEffect(sort, applied, remoteSource) { navigation.putString("allSort:$source", sort); reload() }
    fun applyFilters() {
        val facets = model.filters ?: run { model.loadFilters(); return }
        var next = draft.copy(year = draft.year.trim(), extras = draft.extras.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() })
        if (next.season.isNotEmpty() && next.year.isEmpty()) {
            val current = Calendar.getInstance().get(Calendar.YEAR).toString()
            next = next.copy(year = if (facets.years.isEmpty() || current in facets.years) current else facets.years.first())
        }
        if (next.year.isNotEmpty() && (if (facets.years.isEmpty()) (next.year.toIntOrNull() ?: 0) <= 0 else next.year !in facets.years)) { validationError = "지원하는 연도를 선택하세요."; return }
        val nextSort = if (source == "reanime" && next.extras.isNotEmpty() && sort == "updated") "popular" else sort
        runCatching {
            DesktopCatalogTaxonomy.validate(source, AnimeSnapshot.extendedFilter(next.genre, next.year, next.season, next.format, nextSort, next.extras), facets)
        }.onFailure { validationError = it.message; return }
        draft = next; applied = next; validationError = null; sort = nextSort
    }
    PosterGrid(values, source, onEnd = { if (remoteSource && model.canLoadMore && !model.loading && model.items.isNotEmpty()) model.load(reset = false) }, header = {
        full {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("전체 애니메이션", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = LilacStyle.text)
                    Text(if (remoteSource) "선택한 콘텐츠 소스의 작품입니다." else catalog.progress(source), fontSize = 13.sp, color = LilacStyle.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).height(38.dp).lilacField().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = LilacStyle.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        LilacTextField(query, { query = it }, "한국어·원제·영어 검색", Modifier.weight(1f), onSubmit = { reload() })
                    }
                }
                if (applied.extras["order"].isNullOrEmpty()) {
                    LilacChipTray(Modifier.horizontalScroll(rememberScrollState())) {
                        sorts.forEach { value -> LilacChip(sortLabel(value, source), sort == value) { sort = value } }
                    }
                }
                FilterPanel(model, draft, { draft = it }, validationError,
                    reset = { draft = CatalogSelection(); applied = draft; validationError = null; if (sort !in sorts) sort = sorts.first() },
                    apply = { applyFilters() })
                Text("${values.size}개", fontSize = 13.sp, color = LilacStyle.muted)
                if (!remoteSource) catalog.failure(source)?.let { Text(it, fontSize = 13.sp, color = Color.Red) }
                model.error?.takeIf { remoteSource }?.let { error ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, fontSize = 13.sp, color = Color.Red)
                        LilacGhostButton("다시 시도") { reload() }
                    }
                }
            }
        }
    }, footer = {
        full {
            when {
                !remoteSource -> if (values.isEmpty()) { if (catalog.running) LilacProgress(catalog.status) else LilacEmptyState(Icons.Filled.GridView, "선택한 조건의 작품이 없습니다", "필터를 바꾸거나 초기화해 보세요.") }
                model.loading -> LilacProgress()
                model.canLoadMore && model.items.isNotEmpty() -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LilacGhostButton("더 불러오기") { model.load(reset = false) } }
                model.items.isEmpty() && model.error == null -> LilacEmptyState(Icons.Filled.GridView, "선택한 조건의 작품이 없습니다", "필터를 바꾸거나 초기화해 보세요.")
            }
        }
    })
}

/** .catalog-filters: 상세 필터 with each field as a labelled select, then 초기화 and 필터 적용. */
@Composable
private fun FilterPanel(model: CatalogModel, draft: CatalogSelection, onDraft: (CatalogSelection) -> Unit, validationError: String?, reset: () -> Unit, apply: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(10.dp)).border(1.dp, LilacStyle.line, RoundedCornerShape(10.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("상세 필터", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
        if (model.filtersLoading) LilacProgress("분류를 불러오는 중")
        model.filters?.let { facets ->
            @Composable fun picker(name: String, value: String, options: List<String>, set: (String) -> Unit) =
                CatalogSelect(name, value, listOf("" to "전체 $name") + options.map { it to filterLabel(it, name) }, Modifier.fillMaxWidth(), set)
            val fields = buildList<@Composable () -> Unit> {
                if (facets.genres.isNotEmpty()) add { picker("장르", draft.genre, facets.genres) { onDraft(draft.copy(genre = it)) } }
                if (facets.formats.isNotEmpty()) add { picker("형태", draft.format, facets.formats) { onDraft(draft.copy(format = it)) } }
                if (facets.supportsYear) add {
                    if (facets.years.isEmpty()) Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("연도", fontSize = 12.sp, color = LilacStyle.muted)
                        LilacTextField(draft.year, { onDraft(draft.copy(year = it)) }, "전체 연도", Modifier.fillMaxWidth().height(38.dp).lilacField().padding(horizontal = 10.dp), 13, KeyboardType.Number)
                    } else picker("연도", draft.year, facets.years) { onDraft(draft.copy(year = it)) }
                }
                if (facets.supportsSeason) add { picker("분기", draft.season, facets.seasons) { onDraft(draft.copy(season = it)) } }
                facets.extraFilters.forEach { field ->
                    add {
                        val value = draft.extras[field.key] ?: ""
                        val set = { v: String -> onDraft(draft.copy(extras = draft.extras + (field.key to v))) }
                        if (field.type == "select") CatalogSelect(field.label, value, listOf("" to "전체 " + field.label) + field.options.map { it.value to it.label }, Modifier.fillMaxWidth(), set)
                        else Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(field.label, fontSize = 12.sp, color = LilacStyle.muted)
                            LilacTextField(value, set, if (field.type == "number") "0~100" else "영문 분류명 입력",
                                Modifier.fillMaxWidth().height(38.dp).lilacField().padding(horizontal = 10.dp), 13,
                                if (field.type == "number") KeyboardType.Decimal else KeyboardType.Text)
                        }
                    }
                }
            }
            // GridItem(.adaptive(minimum: 150)): as many columns as fit.
            BoxWithConstraints {
                val columns = maxOf(1, ((maxWidth + 12.dp) / (150.dp + 12.dp)).toInt())
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    fields.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { field -> Box(Modifier.weight(1f)) { field() } }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Text(facets.note.ifEmpty { "장르·형태·연도·분기를 함께 선택할 수 있습니다." }, fontSize = 12.sp, color = LilacStyle.muted)
        }
        model.filterError?.let {
            Text(it, fontSize = 12.sp, color = Color.Red)
            LilacOutlineButton("분류 다시 불러오기") { model.loadFilters() }
        }
        validationError?.let { Text(it, fontSize = 12.sp, color = Color.Red) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            LilacGhostButton("초기화", onClick = reset)
            LilacPrimaryButton("필터 적용", enabled = model.filters != null, onClick = apply)
        }
    }
}

/** The desktop's search (index.html #searchView, iOS CatalogView): a heading, the big search box, the tag filters behind
 *  태그 필터, the posters. */
@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val library = app.library
    val navigation = app.navigation
    val model = remember { CatalogModel() }
    DisposableEffect(Unit) { onDispose { model.close() } }
    var query by remember { mutableStateOf(navigation.query) }
    var filters by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val source = library.preferences.source
    fun clearFilters() { model.genre = ""; model.year = ""; model.format = ""; model.status = ""; model.season = ""; model.studio = "" }
    fun applyNavigation() {
        model.source = source; model.query = navigation.query; model.mode = navigation.listMode; query = navigation.query
        clearFilters()
        if (model.mode == "season") {
            val calendar = Calendar.getInstance()
            model.year = calendar.get(Calendar.YEAR).toString()
            model.season = listOf("WINTER", "SPRING", "SUMMER", "FALL")[calendar.get(Calendar.MONTH) / 3]
        }
        if (source == "linkkf") model.format = mapOf("pv" to "5086", "movie" to "5061", "adult" to "5085")[model.mode] ?: ""
        model.load(); tick++
    }
    LaunchedEffect(navigation.query, navigation.listMode) { applyNavigation() }
    LaunchedEffect(Unit) { model.source = source; model.loadFilters() }
    fun search() { navigation.search(query); model.query = query; model.mode = "browse"; model.load() }
    val wide = LilacStyle.wide
    PosterGrid(model.items, source, onEnd = { if (model.canLoadMore && !model.loading && model.items.isNotEmpty()) model.load(reset = false) }, header = {
        full {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val searched = navigation.query.trim()
                    Text(if (searched.isEmpty()) "어떤 이야기를 찾고 있나요?" else "“$searched”", fontSize = if (wide) 34.sp else 26.sp, fontWeight = FontWeight.ExtraBold,
                        color = LilacStyle.text, maxLines = 2)
                    if (searched.isEmpty()) Text("제목을 검색해 작품 정보와 평점을 확인하세요.", fontSize = 14.sp, color = LilacStyle.muted)
                }
                Row(
                    Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(8.dp)).border(1.dp, LilacStyle.line, RoundedCornerShape(8.dp))
                        .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Search, null, tint = LilacStyle.muted)
                    LilacTextField(query, { query = it }, "애니메이션 제목 입력", Modifier.weight(1f), 16, onSubmit = { search() })
                    LilacPrimaryButton("검색") { search() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LilacOutlineButton("태그 필터", icon = if (filters) Icons.Filled.KeyboardArrowUp else Icons.Filled.FilterList) { filters = !filters }
                }
                AnimatedVisibility(filters) {
                    Column(
                        Modifier.fillMaxWidth().background(LilacStyle.card, RoundedCornerShape(10.dp)).border(1.dp, LilacStyle.line, RoundedCornerShape(10.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        key(tick) {
                            FilterGroup("시즌 타입", model.format, model.filters?.formats.orEmpty()) { model.format = it; tick++ }
                            FilterGroup("장르", model.genre, model.filters?.genres.orEmpty()) { model.genre = it; tick++ }
                            FilterGroup("연도", model.year, model.filters?.years.orEmpty()) { model.year = it; tick++ }
                            if (source in listOf("reanime", "miruro", "animenosub")) {
                                FilterGroup("상태", model.status, model.filters?.statuses?.takeIf { it.isNotEmpty() } ?: listOf("RELEASING", "FINISHED")) { model.status = it; tick++ }
                                FilterGroup("분기", model.season, model.filters?.seasons?.takeIf { it.isNotEmpty() } ?: listOf("WINTER", "SPRING", "SUMMER", "FALL")) { model.season = it; tick++ }
                            }
                            if (source == "reanime") FilterGroup("스튜디오", model.studio, model.filters?.studios.orEmpty()) { model.studio = it; tick++ }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            LilacGhostButton("초기화") { clearFilters(); model.mode = "browse"; model.load(); tick++ }
                            LilacPrimaryButton("적용") { model.load() }
                        }
                    }
                }
                model.error?.let { error ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, fontSize = 13.sp, color = Color.Red)
                        LilacGhostButton("다시 시도") { model.load() }
                    }
                }
                model.searchNote?.let { Text(it, fontSize = 13.sp, color = LilacStyle.muted) }
            }
        }
    }, footer = {
        full {
            when {
                model.loading -> LilacProgress()
                model.items.isEmpty() && model.error == null -> LilacEmptyState(Icons.Filled.Search, "검색 결과가 없습니다", "검색어나 필터를 바꿔서 다시 찾아보세요.")
                model.canLoadMore -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LilacGhostButton("더 불러오기") { model.load(reset = false) } }
            }
        }
    })
}

/** .filter-group: its name, then 전체 and its values as chips (typed in when the source lists none). */
@Composable
private fun FilterGroup(title: String, value: String, values: List<String>, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LilacStyle.text)
        if (values.isEmpty()) {
            LilacTextField(text, { text = it; onChange(it) }, title, Modifier.fillMaxWidth().height(36.dp).lilacField().padding(horizontal = 10.dp), 13)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("") + values).forEach { option -> LilacChip(if (option.isEmpty()) "전체" else option, value == option) { onChange(option) } }
            }
        }
    }
}
