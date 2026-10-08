package com.lilac.anime.shared
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.time.LocalDate
import java.time.ZoneOffset
internal actual fun inflateCatalogGzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { input ->
    val output = input.readNBytes(16 * 1024 * 1024 + 1)
    require(output.size <= 16 * 1024 * 1024) { "Catalog exceeds size limit" }
    output
}
internal actual fun currentCatalogDate() = LocalDate.now(ZoneOffset.UTC).toString()
