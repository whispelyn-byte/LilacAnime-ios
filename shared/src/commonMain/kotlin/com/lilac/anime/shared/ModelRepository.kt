package com.lilac.anime.shared
import com.lilac.anime.shared.compat.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
data class ModelSearchResult(val id: String, val downloads: Long, val likes: Int)
data class ModelFile(val name: String, val size: Long, val url: String)
class ModelRepository(private val client: io.ktor.client.HttpClient = newSharedClient()) {
    suspend fun search(query: String): List<ModelSearchResult> {
        val array = JSONArray(client.get("https://huggingface.co/api/models") {
            parameter("search", query); parameter("filter", "gguf"); parameter("sort", "downloads"); parameter("direction", "-1"); parameter("limit", "30")
        }.bodyAsText())
        return (0 until array.length()).mapNotNull { i ->
            val model = array.optJSONObject(i) ?: return@mapNotNull null
            val id = model.optString("id")
            if (id.isBlank()) null else ModelSearchResult(id, model.optLong("downloads"), model.optInt("likes"))
        }
    }
    suspend fun files(repo: String): List<ModelFile> {
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo))
        val result = mutableListOf<ModelFile>()
        var next: String? = "https://huggingface.co/api/models/$repo/tree/main?recursive=true&expand=false"
        val visited = mutableSetOf<String>()
        while (next != null) {
            check(visited.add(next) && visited.size <= 100) { "모델 페이지가 반복됩니다." }
            val response = client.get(next)
            val array = JSONArray(response.bodyAsText())
            for (i in 0 until array.length()) {
                val file = array.optJSONObject(i) ?: continue
                val name = file.optString("path")
                if (file.optString("type") == "file" && name.endsWith(".gguf", true)) {
                    val builder = URLBuilder("https://huggingface.co/$repo/resolve/main")
                    builder.appendPathSegments(name.split('/'), encodeSlash = false)
                    result += ModelFile(name, file.optLong("size"), builder.buildString())
                }
            }
            next = Regex("""<([^>]+)>;\s*rel="next"""").find(response.headers["Link"].orEmpty())?.groupValues?.get(1)
            require(next == null || next.startsWith("https://huggingface.co/api/")) { "Unexpected model pagination URL" }
            check(result.size < 10000) { "모델 파일이 너무 많습니다." }
        }
        return result.sortedBy { it.size }
    }
    fun close() = client.close()
}
