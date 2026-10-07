package com.lilac.anime.shared
import kotlin.math.max
import kotlin.math.min
import com.lilac.anime.shared.compat.Log

data class AudioMatch(val startSeconds: Double, val endSeconds: Double, val score: Double)
object AudioFingerprint {
    private const val TAG = "AudioFingerprint"
    private const val FINGERPRINT_BINS = 32
    private const val FINGERPRINT_FRAME_SECONDS = 0.1
    private const val FFT_SIZE = 512
    fun compute(samples: FloatArray, sampleRate: Int): FloatArray {
        require(sampleRate >= 5120) { "Sample rate must be at least 5120 Hz" }
        val fingerprint = StreamingLogMelFingerprint(sampleRate, (samples.size / (sampleRate * 0.1)).toInt())
        samples.forEach(fingerprint::append)
        return fingerprint.finish() ?: FloatArray(0)
    }
    fun repeated(a: FloatArray, b: FloatArray): List<AudioMatch> =
        findTopRepeatedRegions(a, b, 12).filter { it.endSeconds - it.startSeconds in 45.0..85.0 && it.score >= 0.70 }
    private class StreamingLogMelFingerprint(
        sampleRate: Int,
        estimatedFrames: Int
    ) {
        private val hop = max(1, (sampleRate * FINGERPRINT_FRAME_SECONDS).toInt())
        private val frameSize = FFT_SIZE
        private val melBank = buildMelFilterBank(sampleRate, frameSize, FINGERPRINT_BINS)
        private val window = DoubleArray(frameSize) { i -> 0.5 - kotlin.math.cos(2.0 * kotlin.math.PI * i / (frameSize - 1)) * 0.5 }
        private val real = DoubleArray(frameSize)
        private val imag = DoubleArray(frameSize)
        private val mel = DoubleArray(FINGERPRINT_BINS)
        private var pending = FloatArray(frameSize)
        private var pendingSize = 0
        private var skip = 0
        private var output = FloatArray(max(FINGERPRINT_BINS * 16, estimatedFrames * FINGERPRINT_BINS))
        private var outputSize = 0

        fun append(sample: Float) {
            if (skip > 0) { skip--; return }
            if (pendingSize == pending.size) pending = pending.copyOf(pending.size * 2)
            pending[pendingSize++] = sample
            if (pendingSize >= frameSize) {
                appendFrame(pending)
                pendingSize = 0
                skip = max(0, hop - frameSize)
            }
        }

        private fun appendFrame(samples: FloatArray) {
            for (i in 0 until frameSize) { real[i] = samples[i].toDouble() * window[i]; imag[i] = 0.0 }
            fft(real, imag)
            mel.fill(0.0)
            for (bin in 0..frameSize / 2) {
                val power = real[bin] * real[bin] + imag[bin] * imag[bin]
                for (m in 0 until FINGERPRINT_BINS) mel[m] += power * melBank[m][bin]
            }
            var mean = 0.0
            for (m in 0 until FINGERPRINT_BINS) { mel[m] = kotlin.math.ln(mel[m] + 1e-10); mean += mel[m] }
            mean /= FINGERPRINT_BINS
            var variance = 0.0
            for (m in 0 until FINGERPRINT_BINS) { mel[m] -= mean; variance += mel[m] * mel[m] }
            val scale = kotlin.math.sqrt(variance / FINGERPRINT_BINS + 1e-8)
            ensureCapacity(outputSize + FINGERPRINT_BINS)
            for (m in 0 until FINGERPRINT_BINS) output[outputSize++] = (mel[m] / scale).toFloat()
        }

        private fun ensureCapacity(required: Int) {
            if (required <= output.size) return
            var capacity = output.size
            while (capacity < required) capacity *= 2
            output = output.copyOf(capacity)
        }

        fun finish(): FloatArray? = if (outputSize < FINGERPRINT_BINS * 10) null else output.copyOf(outputSize)
    }

    private fun buildMelFilterBank(sampleRate: Int, fftSize: Int, bands: Int): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
        val low = hzToMel(20.0)
        val high = hzToMel(sampleRate / 2.0)
        val points = IntArray(bands + 2) { i ->
            val hz = melToHz(low + (high - low) * i / (bands + 1))
            ((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, fftSize / 2)
        }
        return Array(bands) { m ->
            val row = DoubleArray(fftSize / 2 + 1)
            val left = points[m]
            val center = points[m + 1]
            val right = points[m + 2]
            for (k in left until center) if (center > left) row[k] = (k - left).toDouble() / (center - left)
            for (k in center..right) if (right > center && k < row.size) row[k] = (right - k).toDouble() / (right - center)
            row
        }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * kotlin.math.PI / len
            val wLenR = kotlin.math.cos(angle)
            val wLenI = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wr - imag[i + k + len / 2] * wi
                    val vI = real[i + k + len / 2] * wi + imag[i + k + len / 2] * wr
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun Double.pow(exp: Double): Double = kotlin.math.exp(exp * kotlin.math.ln(this))

    private fun cosine(a: FloatArray, ai: Int, b: FloatArray, bi: Int, length: Int): Double {
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (k in 0 until length) {
            val x = a[ai + k].toDouble(); val y = b[bi + k].toDouble()
            dot += x * y; aa += x * x; bb += y * y
        }
        return if (aa <= 1e-9 || bb <= 1e-9) 0.0 else dot / kotlin.math.sqrt(aa * bb)
    }

    private fun findTopRepeatedRegions(a: FloatArray, b: FloatArray, maxResults: Int): List<AudioMatch> {
        // Work in fingerprint frames, not flattened mel-bin samples.
        // A seed is a 20-second sequence (200 x 0.1s frames).  The score is the
        // average cosine similarity of corresponding 32-bin frames.  This makes
        // the meaning of the score independent of FINGERPRINT_BINS and avoids
        // treating a flattened 454,752-element array as a single frame sequence.
        val seedBlock = 200 // 20 seconds
        val extensionBlock = 50 // 5 seconds
        val step = 20 // 2 seconds during discovery
        val aFrames = fingerprintFrames(a)
        val bFrames = fingerprintFrames(b)
        if (aFrames < seedBlock || bFrames < seedBlock) return emptyList()

        val seeds = ArrayList<AudioMatch>()
        var globalBest = -1.0
        var globalBestA = -1
        var globalBestB = -1

        var ai = 0
        while (ai + seedBlock <= aFrames) {
            var bestBi = -1
            var bestScore = -1.0
            var bi = 0
            while (bi + seedBlock <= bFrames) {
                val score = sequenceCosineFrames(a, ai, b, bi, seedBlock)
                if (score > bestScore) {
                    bestScore = score
                    bestBi = bi
                }
                bi += step
            }

            if (bestScore > globalBest) {
                globalBest = bestScore
                globalBestA = ai
                globalBestB = bestBi
            }

            // Do not require an unrealistically high score at the discovery stage.
            // The later full-template validation decides whether the repeated audio
            // is strong enough to become a persisted template.
            if (bestBi >= 0 && bestScore >= 0.55) {
                var leftA = ai
                var leftB = bestBi
                var rightA = ai + seedBlock
                var rightB = bestBi + seedBlock
                var goodBlocks = 1
                var totalBlocks = 1
                var extensionScoreSum = bestScore

                while (leftA - extensionBlock >= 0 && leftB - extensionBlock >= 0) {
                    val score = sequenceCosineFrames(a, leftA - extensionBlock, b, leftB - extensionBlock, extensionBlock)
                    totalBlocks++
                    if (score < 0.55) break
                    goodBlocks++
                    extensionScoreSum += score
                    leftA -= extensionBlock
                    leftB -= extensionBlock
                }

                while (rightA + extensionBlock <= aFrames && rightB + extensionBlock <= bFrames) {
                    val score = sequenceCosineFrames(a, rightA, b, rightB, extensionBlock)
                    totalBlocks++
                    if (score < 0.55) break
                    goodBlocks++
                    extensionScoreSum += score
                    rightA += extensionBlock
                    rightB += extensionBlock
                }

                val continuity = goodBlocks.toDouble() / totalBlocks.coerceAtLeast(1)
                val average = extensionScoreSum / totalBlocks.coerceAtLeast(1)
                val score = bestScore * 0.45 + average * 0.35 + continuity * 0.20
                val duration = (rightA - leftA) * FINGERPRINT_FRAME_SECONDS
                if (score >= 0.60 && continuity >= 0.50 && duration >= 40.0) {
                    seeds += AudioMatch(
                        leftA * FINGERPRINT_FRAME_SECONDS,
                        rightA * FINGERPRINT_FRAME_SECONDS,
                        score
                    )
                }
            }
            ai += step
        }

        // Always expose the best pair score.  This is diagnostic information, not
        // a persisted template: it lets us distinguish "no similarity" from a
        // candidate being rejected by a later validation rule.
        Log.d(
            TAG,
            "FINGERPRINT_PAIR_BEST score=$globalBest " +
                "aStart=${globalBestA * FINGERPRINT_FRAME_SECONDS} " +
                "bStart=${globalBestB * FINGERPRINT_FRAME_SECONDS}"
        )

        val selected = ArrayList<AudioMatch>()
        for (candidate in seeds.sortedByDescending { it.score }) {
            if (selected.none {
                    kotlin.math.abs(it.startSeconds - candidate.startSeconds) < 30.0 ||
                        rangesOverlap(it.startSeconds, it.endSeconds, candidate.startSeconds, candidate.endSeconds)
                }) {
                selected += candidate
                if (selected.size >= maxResults) break
            }
        }
        return selected.sortedBy { it.startSeconds }
    }

    private fun rangesOverlap(aStart: Double, aEnd: Double, bStart: Double, bEnd: Double): Boolean =
        aStart < bEnd && bStart < aEnd

    private fun FloatArray.sliceFingerprint(startSeconds: Double, endSeconds: Double): FloatArray {
        val frameCount = size / FINGERPRINT_BINS
        val sFrame = (startSeconds / FINGERPRINT_FRAME_SECONDS).toInt().coerceIn(0, frameCount)
        val eFrame = (endSeconds / FINGERPRINT_FRAME_SECONDS).toInt().coerceIn(sFrame, frameCount)
        return copyOfRange(sFrame * FINGERPRINT_BINS, eFrame * FINGERPRINT_BINS)
    }

    private fun fingerprintFrames(values: FloatArray): Int = values.size / FINGERPRINT_BINS

    private fun cosineFrames(a: FloatArray, aFrame: Int, b: FloatArray, bFrame: Int, frames: Int): Double =
        cosine(a, aFrame * FINGERPRINT_BINS, b, bFrame * FINGERPRINT_BINS, frames * FINGERPRINT_BINS)

    private fun sequenceCosineFrames(
        a: FloatArray,
        aFrame: Int,
        b: FloatArray,
        bFrame: Int,
        frames: Int
    ): Double {
        val availableA = fingerprintFrames(a) - aFrame
        val availableB = fingerprintFrames(b) - bFrame
        val count = minOf(frames, availableA, availableB)
        if (count <= 0 || aFrame < 0 || bFrame < 0) return 0.0

        var sum = 0.0
        var valid = 0
        for (frame in 0 until count) {
            val score = cosine(
                a,
                (aFrame + frame) * FINGERPRINT_BINS,
                b,
                (bFrame + frame) * FINGERPRINT_BINS,
                FINGERPRINT_BINS
            )
            sum += score
            valid++
        }
        return if (valid == 0) 0.0 else sum / valid
    }

    /**
     * Searches the complete current-episode fingerprint for the stored audio template.
     * The template length is authoritative: a match is never expanded beyond the
     * fingerprint that was actually persisted.  This avoids turning a short OP/ED
     * template into an arbitrary 90/400-second chapter because adjacent audio also
     * happens to correlate.
     */

}
