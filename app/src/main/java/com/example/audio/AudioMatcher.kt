package com.example.audio

import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

class AudioMatcher {

    data class FrameFeatures(
        val energy: Double,
        val zcr: Double
    )

    companion object {
        private const val TAG = "AudioMatcher"
        private const val FRAME_SIZE = 400 // 25ms at 16kHz
        private const val FRAME_STEP = 200 // 12.5ms overlap at 16kHz
        private const val SILENCE_THRESHOLD_FACTOR = 0.08 // 8% of max energy is considered active speech

        /**
         * Trims silence from start and end of a ShortArray.
         */
        fun trimSilence(audio: ShortArray): ShortArray {
            if (audio.isEmpty()) return audio

            // 1. Compute frame energies to find max
            val frameCount = (audio.size - FRAME_SIZE) / FRAME_STEP + 1
            if (frameCount <= 0) return audio

            val energies = DoubleArray(frameCount)
            for (f in 0 until frameCount) {
                val start = f * FRAME_STEP
                var sumSq = 0.0
                for (i in 0 until FRAME_SIZE) {
                    val sample = audio[start + i].toDouble()
                    sumSq += sample * sample
                }
                energies[f] = sqrt(sumSq / FRAME_SIZE)
            }

            val maxEnergy = energies.maxOrNull() ?: 0.0
            if (maxEnergy == 0.0) return audio

            val activeThreshold = maxEnergy * SILENCE_THRESHOLD_FACTOR

            // Find first frame above threshold
            var firstActiveFrame = 0
            for (f in 0 until frameCount) {
                if (energies[f] >= activeThreshold) {
                    firstActiveFrame = f
                    break
                }
            }

            // Find last frame above threshold
            var lastActiveFrame = frameCount - 1
            for (f in frameCount - 1 downTo 0) {
                if (energies[f] >= activeThreshold) {
                    lastActiveFrame = f
                    break
                }
            }

            if (firstActiveFrame > lastActiveFrame) {
                return audio // Fallback
            }

            val startSample = firstActiveFrame * FRAME_STEP
            val endSample = kotlin.math.min(audio.size, (lastActiveFrame * FRAME_STEP) + FRAME_SIZE)

            val trimmedSize = endSample - startSample
            if (trimmedSize <= 0) return audio

            val trimmed = ShortArray(trimmedSize)
            System.arraycopy(audio, startSample, trimmed, 0, trimmedSize)
            Log.d(TAG, "Trimmed audio from ${audio.size} to ${trimmed.size} samples (Frames: $firstActiveFrame to $lastActiveFrame)")
            return trimmed
        }

        /**
         * Extracts feature sequence (energy, zero crossing rate) from trimmed audio.
         */
        fun extractFeatures(audio: ShortArray): List<FrameFeatures> {
            val trimmed = trimSilence(audio)
            val frameCount = (trimmed.size - FRAME_SIZE) / FRAME_STEP + 1
            if (frameCount <= 0) return emptyList()

            val rawFeatures = ArrayList<FrameFeatures>(frameCount)

            for (f in 0 until frameCount) {
                val start = f * FRAME_STEP
                var sumSq = 0.0
                var zeroCrossings = 0
                var prevSample = 0.0

                for (i in 0 until FRAME_SIZE) {
                    val sample = trimmed[start + i].toDouble()
                    sumSq += sample * sample

                    if (i > 0) {
                        if ((sample >= 0 && prevSample < 0) || (sample < 0 && prevSample >= 0)) {
                            zeroCrossings++
                        }
                    }
                    prevSample = sample
                }

                val rms = sqrt(sumSq / FRAME_SIZE)
                // Log-energy
                val energy = log10(rms + 1.0)
                val zcr = zeroCrossings.toDouble() / FRAME_SIZE

                rawFeatures.add(FrameFeatures(energy, zcr))
            }

            // Normalize features (Z-Score)
            val energyMean = rawFeatures.map { it.energy }.average()
            val energyStd = sqrt(rawFeatures.map { (it.energy - energyMean) * (it.energy - energyMean) }.average()) + 1e-6

            val zcrMean = rawFeatures.map { it.zcr }.average()
            val zcrStd = sqrt(rawFeatures.map { (it.zcr - zcrMean) * (it.zcr - zcrMean) }.average()) + 1e-6

            return rawFeatures.map {
                FrameFeatures(
                    energy = (it.energy - energyMean) / energyStd,
                    zcr = (it.zcr - zcrMean) / zcrStd
                )
            }
        }

        /**
         * Computes Dynamic Time Warping distance between two feature sequences.
         * Returns a normalized distance score. Lower means more similar.
         */
        fun computeDtwDistance(seq1: List<FrameFeatures>, seq2: List<FrameFeatures>): Double {
            if (seq1.isEmpty() || seq2.isEmpty()) return Double.MAX_VALUE

            val n = seq1.size
            val m = seq2.size

            // DP Matrix
            val dp = Array(n) { DoubleArray(m) }

            // Feature distance helper
            fun featureDist(f1: FrameFeatures, f2: FrameFeatures): Double {
                val eDiff = f1.energy - f2.energy
                val zDiff = f1.zcr - f2.zcr
                return sqrt(eDiff * eDiff + zDiff * zDiff)
            }

            // Initialize base cell
            dp[0][0] = featureDist(seq1[0], seq2[0])

            // Initialize first column
            for (i in 1 until n) {
                dp[i][0] = dp[i - 1][0] + featureDist(seq1[i], seq2[0])
            }

            // Initialize first row
            for (j in 1 until m) {
                dp[0][j] = dp[0][j - 1] + featureDist(seq1[0], seq2[j])
            }

            // Fill matrix
            for (i in 1 until n) {
                for (j in 1 until m) {
                    val cost = featureDist(seq1[i], seq2[j])
                    dp[i][j] = cost + minOf(
                        dp[i - 1][j],      // Insertion
                        dp[i][j - 1],      // Deletion
                        dp[i - 1][j - 1]   // Match
                    )
                }
            }

            // Normalize the distance by the path length (approximated by sequence length sum)
            val pathLength = (n + m).toDouble()
            val normalizedDistance = dp[n - 1][m - 1] / pathLength

            Log.d(TAG, "DTW compared sequences of len ($n, $m). Raw cost: ${dp[n-1][m-1]}, Normalized: $normalizedDistance")
            return normalizedDistance
        }
    }
}
