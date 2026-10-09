package com.lilac.anime.shared

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** electron/oped-fingerprint.cjs: 30 s seed, 1 s search, 5 s extension. */
object DesktopAudioFingerprint {
    fun region(a: FloatArray, b: FloatArray): AudioMatch? {
        val an = a.size / 32; val bn = b.size / 32
        if (an < 300 || bn < 300) return null
        fun score(ai: Int, bi: Int, length: Int, stride: Int = 10): Double {
            var sum = 0.0; var count = 0
            for (frame in 0 until length step stride) {
                var dot = 0.0; var aa = 0.0; var bb = 0.0
                for (bin in 0 until 32) {
                    val x = a[(ai + frame) * 32 + bin].toDouble()
                    val y = b[(bi + frame) * 32 + bin].toDouble()
                    dot += x * y; aa += x * x; bb += y * y
                }
                sum += if (aa > 1e-9 && bb > 1e-9) dot / sqrt(aa * bb) else 0.0
                count++
            }
            return if (count > 0) sum / count else 0.0
        }
        var best = -1.0; var ai = 0; var bi = 0
        for (x in 0..an - 300 step 10) for (y in 0..bn - 300 step 10) {
            val value = score(x, y, 300)
            if (value > best) { best = value; ai = x; bi = y }
        }
        if (best < .82) return null
        var left = 0; var right = 300
        // Keep the desktop boundary test, including its seed-adjacent left block.
        while (ai - left >= 50 && bi - left >= 50 && score(ai - left, bi - left, 50, 5) >= .72) left += 50
        while (ai + right + 50 <= an && bi + right + 50 <= bn && score(ai + right, bi + right, 50, 5) >= .72) right += 50
        if ((left + right) * .1 < 45) return null
        return AudioMatch((ai - left) * .1, min((ai + right) * .1, (ai - left) * .1 + 85), best)
    }

    fun consensus(matches: List<AudioMatch>, offset: Double = 0.0): AudioMatch? {
        val seed = matches.maxByOrNull { it.score } ?: return null
        val near = matches.filter { abs(it.startSeconds - seed.startSeconds) <= 20 }
        if (near.size < 2 && seed.score < .9) return null
        return AudioMatch(offset + near.map { it.startSeconds }.average(), offset + near.map { it.endSeconds }.average(), near.map { it.score }.average())
    }
}
