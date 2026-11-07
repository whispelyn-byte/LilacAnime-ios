package com.lilac.anime.android

import android.app.Application
import com.lilac.anime.android.data.LibraryStore
import com.lilac.anime.android.data.Navigation
import com.lilac.anime.shared.AndroidPlatform

class LilacApp : Application() {
    lateinit var library: LibraryStore; private set
    lateinit var navigation: Navigation; private set
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.cacheDirectory = cacheDir.absolutePath
        com.lilac.anime.android.data.SecureKeys.init(this)
        library = LibraryStore(this)
        navigation = Navigation(this)
    }
}
