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
        com.lilac.anime.android.subtitle.SubtitleFiles.init(this)
        com.lilac.anime.android.subtitle.EpisodeSubtitleStore.init(this)
        com.lilac.anime.android.data.DesktopCatalog.init(this)
        com.lilac.anime.android.download.DownloadStore.init(this)
        // Miruro's API answers inside its own page (iOS MiruroTransportRegistry.transport = MiruroBrowser.shared).
        com.lilac.anime.shared.MiruroTransportRegistry.transport = com.lilac.anime.android.player.MiruroBrowser(this)
        library = LibraryStore(this)
        navigation = Navigation(this)
    }
}
