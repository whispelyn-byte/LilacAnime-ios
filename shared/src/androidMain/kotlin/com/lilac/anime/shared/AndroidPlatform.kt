package com.lilac.anime.shared
import java.io.File

/** Where the Android app keeps shared caches; set by the app at launch (its cacheDir). */
object AndroidPlatform {
    @Volatile var cacheDirectory: String? = null
}
internal actual fun compareJimakuNames(left: String, right: String): Int = java.text.Collator.getInstance().compare(left, right)
internal actual fun normalizeDesktopTitle(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)

/** A subtitle blog's posts with their whole pages are a file in the cache folder, as on iOS (iosMain CommunityCache). */
private fun communityCacheFile(name: String): File? {
    val root = AndroidPlatform.cacheDirectory ?: return null
    val folder = File(root, "community").apply { mkdirs() }
    return File(folder, name.replace(Regex("[^A-Za-z0-9]"), "_").take(80) + "-" + name.hashCode().toUInt().toString(16) + ".json")
}
private val memoryCommunityCache = java.util.concurrent.ConcurrentHashMap<String, String>()
internal actual fun readCommunityCache(name: String): String? =
    memoryCommunityCache[name] ?: communityCacheFile(name)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
internal actual fun writeCommunityCache(name: String, value: String) {
    memoryCommunityCache[name] = value
    val file = communityCacheFile(name) ?: return
    runCatching {
        val partial = File(file.path + ".part")
        partial.writeText(value)
        if (!partial.renameTo(file)) { file.delete(); partial.renameTo(file) }
    }
}
