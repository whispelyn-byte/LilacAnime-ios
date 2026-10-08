@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.lilac.anime.shared
import kotlinx.cinterop.*
import platform.zlib.*
import platform.posix.memset
import platform.Foundation.*
internal actual fun inflateCatalogGzip(data: ByteArray): ByteArray = memScoped {
    require(data.isNotEmpty())
    val stream = alloc<z_stream>()
    memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
    check(inflateInit2(stream.ptr, 31) == Z_OK) { "Gzip initialization failed" }
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    try {
        data.usePinned { input ->
            stream.next_in = input.addressOf(0).reinterpret()
            stream.avail_in = data.size.toUInt()
            var status = Z_OK
            do {
                val chunk = ByteArray(32768)
                chunk.usePinned { output ->
                    stream.next_out = output.addressOf(0).reinterpret()
                    stream.avail_out = chunk.size.toUInt()
                    status = inflate(stream.ptr, Z_NO_FLUSH)
                    check(status == Z_OK || status == Z_STREAM_END) { "Invalid gzip catalog" }
                    val count = chunk.size - stream.avail_out.toInt()
                    size += count
                    require(size <= 16 * 1024 * 1024) { "Catalog exceeds size limit" }
                    chunks += chunk.copyOf(count)
                }
            } while (status != Z_STREAM_END)
        }
        val output = ByteArray(size)
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(output, offset); offset += chunk.size }
        output
    } finally { inflateEnd(stream.ptr) }
}
internal actual fun currentCatalogDate(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd"
    formatter.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    return formatter.stringFromDate(NSDate())
}
internal actual fun pacificDayRemaining(): Int {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm:ss"
    formatter.timeZone = requireNotNull(NSTimeZone.timeZoneWithName("America/Los_Angeles"))
    val time = formatter.stringFromDate(NSDate()).split(':').map(String::toInt)
    return 86400 - time[0] * 3600 - time[1] * 60 - time[2]
}
