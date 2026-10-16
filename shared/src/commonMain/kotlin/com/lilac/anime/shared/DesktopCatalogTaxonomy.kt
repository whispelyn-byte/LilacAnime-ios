package com.lilac.anime.shared
import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.*
import kotlinx.serialization.json.*

object DesktopCatalogTaxonomy {
    private fun options(vararg pairs: Pair<String, String>) = pairs.map { CatalogFilterOption(it.first, it.second) }
    private fun field(key: String, label: String, vararg pairs: Pair<String, String>) = CatalogFilterField(key, label, options(*pairs))
    private fun text(key: String, label: String, max: Int = 150) = CatalogFilterField(key, label, type = "text", maxLength = max)
    private val countries = options("JP" to "일본", "KR" to "한국", "CN" to "중국", "TW" to "대만")
    private val days = "일월화수목금토".map { CatalogFilterOption(it.toString(), "${it}요일") }
    fun reanime(root: JsonObject): List<CatalogFilterField> = listOf(
        CatalogFilterField("tag", "태그", root.obj("facets").obj("tags").keys.sorted().map { CatalogFilterOption(it, it) }),
        field("status", "방영 상태", "Finished" to "완결", "Releasing" to "방영 중", "Not Yet Released" to "방영 예정", "Cancelled" to "취소", "Unknown" to "미분류"),
        CatalogFilterField("country", "제작 국가", countries), field("audio", "더빙 제공", "dub" to "더빙 있음"),
        CatalogFilterField("rating", "연령 등급", listOf("G", "PG", "PG-13", "R", "R+", "Rx").map { CatalogFilterOption(it, it) }),
        text("studio", "제작사"), text("character", "등장인물"), text("staff", "제작진")
    ).filter { it.type != "select" || it.options.isNotEmpty() }
    fun miruro() = SourceFilters(
        genres = listOf("Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Hentai", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller"),
        formats = listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC"), supportsYear = true, supportsSeason = true,
        seasons = listOf("WINTER", "SPRING", "SUMMER", "FALL"), sorts = listOf("popular", "year", "updated", "score"),
        extraFilters = listOf(field("status", "방영 상태", "FINISHED" to "완결", "RELEASING" to "방영 중", "NOT_YET_RELEASED" to "방영 예정", "CANCELLED" to "취소", "HIATUS" to "휴방"),
            CatalogFilterField("country", "제작 국가", countries),
            field("source", "원작", "ORIGINAL" to "오리지널", "MANGA" to "만화", "LIGHT_NOVEL" to "라이트노벨", "VISUAL_NOVEL" to "비주얼노벨", "GAME" to "게임", "OTHER" to "기타", "NOVEL" to "소설", "DOUJINSHI" to "동인지", "ANIME" to "애니메이션", "WEB_NOVEL" to "웹소설", "LIVE_ACTION" to "실사", "COMIC" to "코믹", "MULTIMEDIA_PROJECT" to "미디어 믹스", "PICTURE_BOOK" to "그림책"),
            field("adult", "성인 분류", "false" to "일반 작품", "true" to "성인 작품"), text("tag", "태그 (영문, 쉼표 구분)", 500),
            CatalogFilterField("scoreMin", "최소 평점 (0~100)", type = "number"), CatalogFilterField("scoreMax", "최대 평점 (0~100)", type = "number")),
        note = "상세 조건은 소스 검색에 적용됩니다. 업데이트순은 작품 정보 업데이트 기준입니다.")
    fun linkani(html: String): SourceFilters {
        val groups = mutableMapOf<String, MutableList<String>>()
        Ksoup.parse(html).select("a[href]").forEach { node ->
            val path = runCatching { Url("https://linkani.tv".let { base -> if (node.attr("href").startsWith("http")) node.attr("href") else base + node.attr("href") }).encodedPath }.getOrNull() ?: return@forEach
            val match = Regex("^/list/2/(class|year|lang)/([^/]+)/?$").matchEntire(path) ?: return@forEach
            val value = match.groupValues[2].replace("+", " ").decodeURLPart()
            if (match.groupValues[1] == "class" && value in days.map { it.value }) return@forEach
            groups.getOrPut(match.groupValues[1]) { mutableListOf() }.add(value)
        }
        require(groups.values.any { it.isNotEmpty() }) { "링크애니 분류를 불러오지 못했습니다." }
        val years = groups["year"].orEmpty().distinct().sortedDescending()
        return SourceFilters(genres = groups["class"].orEmpty().distinct(), formats = groups["lang"].orEmpty().distinct(), years = years, supportsYear = years.isNotEmpty(), sorts = listOf("default", "updated"),
            extraFilters = listOf(CatalogFilterField("weekday", "방영 요일", days)), note = "장르·형태·연도·요일은 링크애니 분류를 따릅니다. 장르와 요일은 각각 선택하세요.")
    }
    fun ohli(root: JsonObject): SourceFilters {
        val filters = root.obj("filterOptions")
        require(filters["genres"] is JsonArray && filters["years"] is JsonArray) { "애니24 분류를 불러오지 못했습니다." }
        val genres = filters.list("genres").filterIsInstance<JsonObject>().flatMap { it.list("items") }.filterIsInstance<JsonObject>().map { it.text("value") }.filter(String::isNotBlank).distinct()
        val years = filters.list("years").filterIsInstance<JsonObject>().map { it.text("value") }.filter(String::isNotBlank)
        return SourceFilters(genres = genres, years = years, formats = listOf("TV", "Movie"), supportsYear = true, sorts = listOf("default", "updated"),
            extraFilters = listOf(field("status", "방영 상태", "방영중" to "방영 중", "종영" to "완결"),
                CatalogFilterField("weekday", "방영 요일", days.map { CatalogFilterOption(it.label, it.label) } + CatalogFilterOption("기타", "기타")),
                field("order", "정렬", "update-desc" to "업데이트 최신순", "update-asc" to "업데이트 오래된순", "release-desc" to "공개일 최신순", "release-asc" to "공개일 오래된순", "rating-desc" to "별점 높은순", "title-asc" to "제목 가나다순")),
            note = "연도·연대·방영 상태·요일·정렬을 선택할 수 있습니다. 요일은 방영 중 작품에 적용합니다.")
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun validate(source: String, filter: BrowseFilter, facets: SourceFilters) {
        filter.extras.filterValues { it.isNotBlank() }.forEach { (key, raw) ->
            val field = facets.extraFilters.firstOrNull { it.key == key } ?: error("이 소스에서 지원하지 않는 필터입니다.")
            val value = raw.trim()
            require(value.length <= field.maxLength) { "${field.label} 입력이 너무 깁니다." }
            if (field.type == "select") require(field.options.any { it.value == value }) { "${field.label}를 다시 선택해 주세요." }
            if (field.type == "number") require(value.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true) { "${field.label} 범위를 확인해 주세요." }
        }
        val e = filter.extras
        require(e["scoreMin"].isNullOrBlank() || e["scoreMax"].isNullOrBlank() || e.getValue("scoreMin").toDouble() <= e.getValue("scoreMax").toDouble()) { "최소 평점은 최대 평점보다 작거나 같아야 합니다." }
        require(source != "reanime" || filter.sort != "updated" || e.values.all { it.isBlank() }) { "RE:Anime 추가 조건은 인기순·최신순·평점순에서 사용할 수 있습니다." }
        require(source != "miruro" || e["status"] != "NOT_YET_RELEASED" || listOf("scoreMin", "scoreMax").none { !e[it].isNullOrBlank() }) { "방영 예정 작품에는 평점 조건을 적용할 수 없습니다." }
        require(source != "linkani" || filter.genres.isEmpty() || e["weekday"].isNullOrBlank()) { "링크애니 장르와 요일은 각각 선택하세요." }
        require(source != "ohli24" || !filter.format.equals("Movie", true) || listOf("status", "weekday").all { e[it].isNullOrBlank() }) { "극장판에는 방영 상태·요일 조건을 적용할 수 없습니다." }
        require(source != "ohli24" || e["weekday"].isNullOrBlank() || e["status"] != "종영") { "요일은 방영 중 작품과 함께 선택해 주세요." }
    }
    fun animenosub(html: String): SourceFilters {
        val doc = Ksoup.parse(html)
        fun values(name: String) = doc.select("input").filter { it.attr("name") == name }.map { it.attr("value") }.filter(String::isNotBlank).distinct()
        val seasons = values("season[]")
        val genres = values("genre[]")
        require(genres.isNotEmpty() || seasons.isNotEmpty()) { "Animenosub 분류를 불러오지 못했습니다." }
        val years = seasons.mapNotNull { Regex("-(\\d{4})$").find(it)?.groupValues?.get(1) }.distinct().sortedDescending()
        return SourceFilters(genres = genres, years = years, formats = values("type"), seasons = if (seasons.isEmpty()) emptyList() else listOf("WINTER", "SPRING", "SUMMER", "FALL"),
            supportsYear = years.isNotEmpty(), supportsSeason = seasons.isNotEmpty(), sorts = listOf("popular", "year", "updated", "score"), seasonValues = seasons,
            extraFilters = listOf(CatalogFilterField("studio", "제작사", values("studio[]").map { CatalogFilterOption(it, it) }),
                CatalogFilterField("status", "방영 상태", values("status").map { CatalogFilterOption(it, mapOf("ongoing" to "방영 중", "completed" to "완결", "upcoming" to "방영 예정", "hiatus" to "휴방")[it] ?: it) }),
                CatalogFilterField("sub", "자막·더빙", values("sub").map { CatalogFilterOption(it, mapOf("sub" to "자막", "dub" to "더빙", "raw" to "RAW")[it] ?: it) }),
                CatalogFilterField("order", "정렬", values("order").map { CatalogFilterOption(it, mapOf("title" to "제목 A–Z", "titlereverse" to "제목 Z–A", "update" to "업데이트 최신순", "latest" to "최신 등록순", "popular" to "인기순", "rating" to "평점순")[it] ?: it) })).filter { it.options.isNotEmpty() })
    }
}
