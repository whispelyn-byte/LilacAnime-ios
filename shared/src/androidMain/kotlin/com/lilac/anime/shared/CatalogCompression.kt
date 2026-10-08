package com.lilac.anime.shared
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.time.LocalDate
import java.time.ZoneOffset
internal actual fun inflateCatalogGzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { input ->
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 16 * 1024 * 1024) { "Catalog exceeds size limit" }
        output.write(buffer, 0, count)
    }
    output.toByteArray()
}
internal actual fun currentCatalogDate() = LocalDate.now(ZoneOffset.UTC).toString()
