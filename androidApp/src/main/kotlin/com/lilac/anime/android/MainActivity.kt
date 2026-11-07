package com.lilac.anime.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.lilac.anime.android.ui.*

val LocalApp = staticCompositionLocalOf<LilacApp> { error("LilacApp not provided") }

class MainActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); com.lilac.anime.android.data.DeviceSync.becameActive() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LilacApp
        com.lilac.anime.android.net.WireGuard.start()
        setContent {
            var restored by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                app.library.load()
                com.lilac.anime.android.download.DownloadStore.attach { app.library.preferences }
                com.lilac.anime.android.download.DownloadStore.load()
                restored = true
                com.lilac.anime.android.data.DeviceSync.start(app.library)
            }
            val theme = app.library.preferences.theme
            val dark = if (theme == "system") isSystemInDarkTheme() else theme != "light"
            LaunchedEffect(dark) {
                val bar = (if (dark) DarkPalette else LightPalette).background.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar),
                )
            }
            CompositionLocalProvider(LocalApp provides app) {
                LilacTheme(dark) {
                    Box(Modifier.fillMaxSize().background(LilacStyle.background)) {
                        if (!restored) LilacProgress("저장된 목록 불러오는 중…", Modifier.fillMaxSize())
                        else Workspace()
                    }
                }
            }
        }
    }
}
