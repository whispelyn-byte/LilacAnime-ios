package com.lilac.anime.android.player

import android.content.Context
import java.io.File

/** iOS Anime4KMode: the desktop's Anime4K 4.0.1 modes as mpv GLSL shader chains (one 2x upscale pass). */
enum class Anime4KMode(val key: String, val title: String, val shaders: List<String>) {
    Off("off", "끄기", emptyList()),
    Light("light", "가볍게", listOf(CLAMP, "Anime4K_Restore_CNN_S", "Anime4K_Upscale_CNN_x2_S")),
    A("a", "A · 선명하게", listOf(CLAMP, "Anime4K_Restore_CNN_M", "Anime4K_Upscale_CNN_x2_M")),
    B("b", "B · 부드럽게", listOf(CLAMP, "Anime4K_Restore_CNN_Soft_M", "Anime4K_Upscale_CNN_x2_M")),
    C("c", "C · 노이즈 제거", listOf(CLAMP, "Anime4K_Upscale_Denoise_CNN_x2_M")),
    AA("aa", "A+A · 더 선명하게", listOf(CLAMP, "Anime4K_Restore_CNN_M", "Anime4K_Upscale_CNN_x2_M", "Anime4K_Restore_CNN_Soft_S")),
    BB("bb", "B+B · 더 부드럽게", listOf(CLAMP, "Anime4K_Restore_CNN_Soft_M", "Anime4K_Upscale_CNN_x2_M", "Anime4K_Restore_CNN_Soft_S")),
    CA("ca", "C+A · 노이즈 제거 후 선명하게", listOf(CLAMP, "Anime4K_Upscale_Denoise_CNN_x2_M", "Anime4K_Restore_CNN_S")),
    HQ("hq", "고화질 (고사양)", listOf(CLAMP, "Anime4K_Restore_CNN_VL", "Anime4K_Upscale_CNN_x2_VL"));

    /** The shader files, copied out of the app's assets once (mpv reads files, not assets). */
    fun paths(context: Context): List<String> {
        val folder = File(context.filesDir, "Anime4K").apply { mkdirs() }
        return shaders.map { name ->
            val file = File(folder, "$name.glsl")
            if (!file.isFile) context.assets.open("Anime4K/$name.glsl").use { input -> file.outputStream().use { input.copyTo(it) } }
            file.absolutePath
        }
    }
    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Off
    }
}
private const val CLAMP = "Anime4K_Clamp_Highlights"
