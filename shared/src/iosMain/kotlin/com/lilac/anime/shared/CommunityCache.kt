@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.lilac.anime.shared
import platform.Foundation.*
internal actual fun compareJimakuNames(left: String, right: String): Int = NSString.create(string = left).localizedCompare(right).toInt()
internal actual fun normalizeDesktopTitle(value: String): String = NSString.create(string = value).precomposedStringWithCompatibilityMapping
/** A subtitle blog's posts with their whole pages (thousands of them) are a file in Caches: kept in UserDefaults they
 *  made the app's preferences tens of megabytes, all read into memory whenever any preference was. */
private fun communityCacheFile(name: String): String? {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return null
    val folder = "$caches/community"
    NSFileManager.defaultManager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
    return folder + "/" + name.replace(Regex("[^A-Za-z0-9]"), "_").take(80) + "-" + name.hashCode().toUInt().toString(16) + ".json"
}
private fun legacyCommunityKey(name: String) = "desktop.community.$name"
internal actual fun readCommunityCache(name: String): String? {
    val file = communityCacheFile(name)
    if (file != null) NSString.stringWithContentsOfFile(file, encoding = NSUTF8StringEncoding, error = null)?.let { return it }
    // Saved by an earlier version: moved out of UserDefaults on first read.
    val defaults = NSUserDefaults.standardUserDefaults
    val legacy = defaults.stringForKey(legacyCommunityKey(name)) ?: return null
    if (file != null && NSString.create(string = legacy).writeToFile(file, atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
        defaults.removeObjectForKey(legacyCommunityKey(name))
    }
    return legacy
}
internal actual fun writeCommunityCache(name: String, value: String) {
    val file = communityCacheFile(name) ?: return
    if (NSString.create(string = value).writeToFile(file, atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(legacyCommunityKey(name))
    }
}
