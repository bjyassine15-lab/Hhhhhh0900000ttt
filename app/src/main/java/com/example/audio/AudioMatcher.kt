package com.example.audio

import android.util.Log
import com.example.data.Contact
import java.io.File
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

        // --- PURE KOTLIN MFCC FEATURE EXTRACTION ---

        private fun hzToMel(hz: Double): Double {
            return 2595.0 * log10(1.0 + hz / 700.0)
        }

        private fun melToHz(mel: Double): Double {
            return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)
        }

        private fun fft(real: DoubleArray, imag: DoubleArray) {
            val n = real.size
            if (n <= 1) return

            // Bit-reversal permutation
            var j = 0
            for (i in 0 until n) {
                if (i < j) {
                    val tempR = real[i]
                    real[i] = real[j]
                    real[j] = tempR
                    val tempI = imag[i]
                    imag[i] = imag[j]
                    imag[j] = tempI
                }
                var m = n shr 1
                while (m >= 1 && j >= m) {
                    j -= m
                    m = m shr 1
                }
                j += m
            }

            // Cooley-Tukey Radix-2 FFT
            var size = 2
            while (size <= n) {
                val halfSize = size shr 1
                for (i in 0 until n step size) {
                    for (k in 0 until halfSize) {
                        val angle = -2.0 * Math.PI * k / size
                        val wr = Math.cos(angle)
                        val wi = Math.sin(angle)
                        val tR = real[i + k + halfSize] * wr - imag[i + k + halfSize] * wi
                        val tI = real[i + k + halfSize] * wi + imag[i + k + halfSize] * wr
                        real[i + k + halfSize] = real[i + k] - tR
                        imag[i + k + halfSize] = imag[i + k] - tI
                        real[i + k] += tR
                        imag[i + k] += tI
                    }
                }
                size = size shl 1
            }
        }

        /**
         * Extracts high-quality 13-coefficient MFCC vectors for each overlapping frame.
         */
        fun extractMfcc(audio: ShortArray): List<DoubleArray> {
            val trimmed = trimSilence(audio)
            if (trimmed.isEmpty()) return emptyList()

            // 1. Pre-emphasis (alpha = 0.97)
            val preEmphasized = DoubleArray(trimmed.size)
            preEmphasized[0] = trimmed[0].toDouble()
            for (i in 1 until trimmed.size) {
                preEmphasized[i] = trimmed[i].toDouble() - 0.97 * trimmed[i - 1].toDouble()
            }

            // 2. Framing & Windowing (Frame size 512, step 256)
            val nfft = 512
            val frameSize = 512
            val frameStep = 256
            val frameCount = (preEmphasized.size - frameSize) / frameStep + 1
            if (frameCount <= 0) return emptyList()

            // Hamming window
            val hamming = DoubleArray(frameSize) { i ->
                0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (frameSize - 1))
            }

            // Pre-calculate Mel Filterbank weights (20 filters)
            val numFilters = 20
            val fmin = 300.0
            val fmax = 8000.0
            val melMin = hzToMel(fmin)
            val melMax = hzToMel(fmax)
            val melPoints = DoubleArray(numFilters + 2) { i ->
                melMin + i * (melMax - melMin) / (numFilters + 1)
            }
            val hzPoints = DoubleArray(numFilters + 2) { i -> melToHz(melPoints[i]) }
            val fftBins = IntArray(numFilters + 2) { i ->
                Math.floor((nfft + 1) * hzPoints[i] / 16000.0).toInt().coerceIn(0, nfft / 2)
            }

            // Filterbank weights matrix
            val fbank = Array(numFilters) { m ->
                DoubleArray(nfft / 2 + 1) { k ->
                    val fMinus = fftBins[m]
                    val fCenter = fftBins[m + 1]
                    val fPlus = fftBins[m + 2]
                    when {
                        k < fMinus -> 0.0
                        k >= fMinus && k <= fCenter -> {
                            if (fCenter == fMinus) 1.0 else (k - fMinus).toDouble() / (fCenter - fMinus)
                        }
                        k >= fCenter && k <= fPlus -> {
                            if (fPlus == fCenter) 1.0 else (fPlus - k).toDouble() / (fPlus - fCenter)
                        }
                        else -> 0.0
                    }
                }
            }

            val mfccSequence = ArrayList<DoubleArray>(frameCount)

            // Process each frame
            for (f in 0 until frameCount) {
                val start = f * frameStep
                val real = DoubleArray(nfft)
                val imag = DoubleArray(nfft)

                for (i in 0 until frameSize) {
                    real[i] = preEmphasized[start + i] * hamming[i]
                }

                // Compute FFT
                fft(real, imag)

                // Compute Power Spectrum (first half)
                val powerSpectrum = DoubleArray(nfft / 2 + 1)
                for (i in 0..nfft / 2) {
                    val p = (real[i] * real[i] + imag[i] * imag[i]) / nfft
                    powerSpectrum[i] = p
                }

                // Apply Mel Filterbank
                val filterEnergies = DoubleArray(numFilters)
                for (m in 0 until numFilters) {
                    var sum = 0.0
                    for (k in 0..nfft / 2) {
                        sum += powerSpectrum[k] * fbank[m][k]
                    }
                    filterEnergies[m] = Math.log(sum + 1e-6) // log-energy
                }

                // Compute DCT-II
                val numCoeffs = 13
                val coeffs = DoubleArray(numCoeffs)
                for (i in 0 until numCoeffs) {
                    var sum = 0.0
                    for (m in 0 until numFilters) {
                        sum += filterEnergies[m] * Math.cos(Math.PI * i * (m + 0.5) / numFilters)
                    }
                    coeffs[i] = sum
                }

                mfccSequence.add(coeffs)
            }

            // Normalization across the whole sequence (Cepstral Mean & Variance Normalization)
            if (mfccSequence.isEmpty()) return emptyList()
            val numCoeffs = 13
            val means = DoubleArray(numCoeffs)
            val stds = DoubleArray(numCoeffs)

            for (i in 0 until numCoeffs) {
                var sum = 0.0
                for (frame in mfccSequence) {
                    sum += frame[i]
                }
                means[i] = sum / mfccSequence.size

                var sumSq = 0.0
                for (frame in mfccSequence) {
                    val diff = frame[i] - means[i]
                    sumSq += diff * diff
                }
                stds[i] = sqrt(sumSq / mfccSequence.size) + 1e-6
            }

            val normalizedSequence = ArrayList<DoubleArray>(mfccSequence.size)
            for (frame in mfccSequence) {
                val normFrame = DoubleArray(numCoeffs) { i ->
                    (frame[i] - means[i]) / stds[i]
                }
                normalizedSequence.add(normFrame)
            }

            return normalizedSequence
        }

        /**
         * Computes Cosine Distance between two MFCC feature vectors.
         * Maps cosine similarity in [-1, 1] to a distance in [0, 1].
         */
        private fun cosineDistance(f1: DoubleArray, f2: DoubleArray): Double {
            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in f1.indices) {
                dotProduct += f1[i] * f2[i]
                normA += f1[i] * f1[i]
                normB += f2[i] * f2[i]
            }
            if (normA == 0.0 || normB == 0.0) return 1.0
            val similarity = dotProduct / (sqrt(normA) * sqrt(normB))
            return (1.0 - similarity) / 2.0
        }

        /**
         * Computes Dynamic Time Warping distance between two PCM audio arrays.
         * Extracts MFCCs, aligns them using DTW, and returns a normalized score [0, 1].
         */
        fun computeDTWDistance(audio1: ShortArray, audio2: ShortArray): Double {
            val seq1 = extractMfcc(audio1)
            val seq2 = extractMfcc(audio2)

            if (seq1.isEmpty() || seq2.isEmpty()) return 1.0

            val n = seq1.size
            val m = seq2.size

            val dp = Array(n) { DoubleArray(m) }

            dp[0][0] = cosineDistance(seq1[0], seq2[0])

            for (i in 1 until n) {
                dp[i][0] = dp[i - 1][0] + cosineDistance(seq1[i], seq2[0])
            }

            for (j in 1 until m) {
                dp[0][j] = dp[0][j - 1] + cosineDistance(seq1[0], seq2[j])
            }

            for (i in 1 until n) {
                for (j in 1 until m) {
                    val cost = cosineDistance(seq1[i], seq2[j])
                    dp[i][j] = cost + minOf(
                        dp[i - 1][j],      // Insertion
                        dp[i][j - 1],      // Deletion
                        dp[i - 1][j - 1]   // Match
                    )
                }
            }

            // Normalize by the path length approximation
            val normalizedDistance = dp[n - 1][m - 1] / (n + m)
            Log.d(TAG, "DTW distance computed: $normalizedDistance for sequence lengths $n and $m")
            return normalizedDistance
        }

        /**
         * Find the best matching contact from database using the DTW matcher.
         * Strictly uses formula: Distance_Threshold = 1.0 - (Sensitivity_Percentage / 100.0)
         * and condition: Calculated_DTW_Distance <= Distance_Threshold
         */
        suspend fun findBestMatch(
            recordedAudio: ShortArray,
            contacts: List<Contact>,
            sensitivityPercentage: Double
        ): Contact? {
            val queryFeatures = extractMfcc(recordedAudio)
            if (queryFeatures.isEmpty()) return null

            var bestContact: Contact? = null
            var minDistance = Double.MAX_VALUE

            // Mathematical mapping requested by the user
            val distanceThreshold = 1.0 - (sensitivityPercentage / 100.0)

            for (contact in contacts) {
                val voiceTagPath = contact.voiceTagPath ?: continue
                val tagFile = File(voiceTagPath)
                if (!tagFile.exists()) continue

                val tagRawAudio = AudioRecordHelper.readPcmData(tagFile)
                if (tagRawAudio.isEmpty()) continue

                val tagFeatures = extractMfcc(tagRawAudio)
                if (tagFeatures.isEmpty()) continue

                // Compute DTW on MFCC features
                val n = queryFeatures.size
                val m = tagFeatures.size
                val dp = Array(n) { DoubleArray(m) }

                dp[0][0] = cosineDistance(queryFeatures[0], tagFeatures[0])

                for (i in 1 until n) {
                    dp[i][0] = dp[i - 1][0] + cosineDistance(queryFeatures[i], tagFeatures[0])
                }

                for (j in 1 until m) {
                    dp[0][j] = dp[0][j - 1] + cosineDistance(queryFeatures[0], tagFeatures[j])
                }

                for (i in 1 until n) {
                    for (j in 1 until m) {
                        val cost = cosineDistance(queryFeatures[i], tagFeatures[j])
                        dp[i][j] = cost + minOf(
                            dp[i - 1][j],
                            dp[i][j - 1],
                            dp[i - 1][j - 1]
                        )
                    }
                }

                val normalizedDistance = dp[n - 1][m - 1] / (n + m)
                Log.d(TAG, "Contact ${contact.name}: DTW Cosine Distance = $normalizedDistance (Threshold = $distanceThreshold)")

                if (normalizedDistance < minDistance) {
                    minDistance = normalizedDistance
                    bestContact = contact
                }
            }

            Log.d(TAG, "Best match check: ${bestContact?.name} with distance $minDistance (Threshold: $distanceThreshold)")

            // Strict mathematical conditional decision logic
            return if (bestContact != null && minDistance <= distanceThreshold) {
                bestContact
            } else {
                null
            }
        }
    }
}
