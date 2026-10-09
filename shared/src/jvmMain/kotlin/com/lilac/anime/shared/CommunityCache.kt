package com.lilac.anime.shared
internal actual fun normalizeDesktopTitle(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
private val communityCache = java.util.concurrent.ConcurrentHashMap<String, String>()
internal actual fun readCommunityCache(name: String): String? = communityCache[name]
internal actual fun writeCommunityCache(name: String, value: String) { communityCache[name] = value }
