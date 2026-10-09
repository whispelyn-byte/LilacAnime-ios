package com.lilac.anime.shared
import platform.Foundation.*
internal actual fun normalizeDesktopTitle(value: String): String = NSString.create(string = value).precomposedStringWithCompatibilityMapping
internal actual fun readCommunityCache(name: String): String? = NSUserDefaults.standardUserDefaults.stringForKey("desktop.community.$name")
internal actual fun writeCommunityCache(name: String, value: String) { NSUserDefaults.standardUserDefaults.setObject(value, forKey = "desktop.community.$name") }
