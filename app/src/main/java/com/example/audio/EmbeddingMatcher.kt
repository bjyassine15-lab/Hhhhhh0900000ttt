package com.example.audio

import android.content.Context
import android.util.Log
import com.example.data.Contact
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * High-performance Voice Embedding Engine (EmbeddingMatcher).
 * Uses pure Kotlin speech signal processing to extract 128-dimensional
 * voice print vectors (embeddings) and matches them using Cosine Similarity.
 *
 * It contains simulated TFLite Interpreter architecture to demonstrate
 * background thread execution of Edge AI models, falling back gracefully
 * to the fully functional, deterministic spectral voice embedding system.
 */
class EmbeddingMatcher {

    companion object {
        private const val TAG = "EmbeddingMatcher"
        private const val FRAME_SIZE = 512
        private const val FRAME_STEP = 256
        private const val EMBEDDING_DIM = 128
        private const val SILENCE_THRESHOLD_FACTOR = 0.08

        /**
         * Trims silence from start and end of a ShortArray.
         */
        fun trimSilence(audio: ShortArray): ShortArray {
            if (audio.isEmpty()) return audio

            val frameSize = 400
            val frameStep = 200
            val frameCount = (audio.size - frameSize) / frameStep + 1
            if (frameCount <= 0) return audio

            val energies = DoubleArray(frameCount)
            for (f in 0 until frameCount) {
                val start = f * frameStep
                var sumSq = 0.0
                for (i in 0 until frameSize) {
                    val sample = audio[start + i].toDouble()
                    sumSq += sample * sample
                }
                energies[f] = sqrt(sumSq / frameSize)
            }

            val maxEnergy = energies.maxOrNull() ?: 0.0
            if (maxEnergy == 0.0) return audio

            val activeThreshold = maxEnergy * SILENCE_THRESHOLD_FACTOR

            var firstActiveFrame = 0
            for (f in 0 until frameCount) {
                if (energies[f] >= activeThreshold) {
                    firstActiveFrame = f
                    break
                }
            }

            var lastActiveFrame = frameCount - 1
            for (f in frameCount - 1 downTo 0) {
                if (energies[f] >= activeThreshold) {
                    lastActiveFrame = f
                    break
                }
            }

            if (firstActiveFrame > lastActiveFrame) {
                return audio
            }

            val startSample = firstActiveFrame * frameStep
            val endSample = kotlin.math.min(audio.size, (lastActiveFrame * frameStep) + frameSize)

            val trimmedSize = endSample - startSample
            if (trimmedSize <= 0) return audio

            val trimmed = ShortArray(trimmedSize)
            System.arraycopy(audio, startSample, trimmed, 0, trimmedSize)
            Log.d(TAG, "Trimmed audio from ${audio.size} to ${trimmed.size} samples")
            return trimmed
        }

        private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        private fun fft(real: DoubleArray, imag: DoubleArray) {
            val n = real.size
            if (n <= 1) return

            var j = 0
            for (i in 0 until n) {
                if (i < j) {
                    val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                    val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
                }
                var m = n shr 1
                while (m >= 1 && j >= m) {
                    j -= m
                    m = m shr 1
                }
                j += m
            }

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
         * Generates a robust 128-dimensional Speech Embedding Vector for a PCM ShortArray.
         */
        fun generateEmbedding(audio: ShortArray): DoubleArray {
            val trimmed = trimSilence(audio)
            if (trimmed.isEmpty()) return DoubleArray(EMBEDDING_DIM)

            // 1. Pre-emphasis
            val preEmphasized = DoubleArray(trimmed.size)
            preEmphasized[0] = trimmed[0].toDouble()
            for (i in 1 until trimmed.size) {
                preEmphasized[i] = trimmed[i].toDouble() - 0.97 * trimmed[i - 1].toDouble()
            }

            // 2. Framing
            val frameCount = (preEmphasized.size - FRAME_SIZE) / FRAME_STEP + 1
            if (frameCount <= 0) {
                // Return dummy default embedding if audio too short
                val fallback = DoubleArray(EMBEDDING_DIM)
                fallback[0] = 1.0
                return fallback
            }

            val hamming = DoubleArray(FRAME_SIZE) { i ->
                0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (FRAME_SIZE - 1))
            }

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
                Math.floor((FRAME_SIZE + 1) * hzPoints[i] / 16000.0).toInt().coerceIn(0, FRAME_SIZE / 2)
            }

            val fbank = Array(numFilters) { m ->
                DoubleArray(FRAME_SIZE / 2 + 1) { k ->
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

            for (f in 0 until frameCount) {
                val start = f * FRAME_STEP
                val real = DoubleArray(FRAME_SIZE)
                val imag = DoubleArray(FRAME_SIZE)

                for (i in 0 until FRAME_SIZE) {
                    real[i] = preEmphasized[start + i] * hamming[i]
                }

                fft(real, imag)

                val powerSpectrum = DoubleArray(FRAME_SIZE / 2 + 1)
                for (i in 0..FRAME_SIZE / 2) {
                    powerSpectrum[i] = (real[i] * real[i] + imag[i] * imag[i]) / FRAME_SIZE
                }

                val filterEnergies = DoubleArray(numFilters)
                for (m in 0 until numFilters) {
                    var sum = 0.0
                    for (k in 0..FRAME_SIZE / 2) {
                        sum += powerSpectrum[k] * fbank[m][k]
                    }
                    filterEnergies[m] = Math.log(sum + 1e-6)
                }

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

            // Create a 128-dimensional Speech Embedding via Temporal Partition Pooling
            // Divide the temporal sequence into 8 equal segments and average the 13 MFCC coefficients plus frame energy
            val embedding = DoubleArray(EMBEDDING_DIM)
            val segments = 8
            val coefsPerSegment = 14 // 13 MFCC + 1 energy/variance proxy
            
            var index = 0
            for (s in 0 until segments) {
                val startFrame = s * mfccSequence.size / segments
                val endFrame = (s + 1) * mfccSequence.size / segments
                val framesInSegment = endFrame - startFrame

                val avgCoeffs = DoubleArray(13)
                var avgVariance = 0.0

                if (framesInSegment > 0) {
                    for (f in startFrame until endFrame) {
                        val frame = mfccSequence[f]
                        for (c in 0 until 13) {
                            avgCoeffs[c] += frame[c]
                        }
                    }
                    for (c in 0 until 13) {
                        avgCoeffs[c] /= framesInSegment
                    }
                    
                    // Compute basic segment variance
                    for (f in startFrame until endFrame) {
                        val frame = mfccSequence[f]
                        for (c in 0 until 13) {
                            val diff = frame[c] - avgCoeffs[c]
                            avgVariance += diff * diff
                        }
                    }
                    avgVariance = sqrt(avgVariance / (framesInSegment * 13) + 1e-6)
                }

                for (c in 0 until 13) {
                    if (index < EMBEDDING_DIM) {
                        embedding[index++] = avgCoeffs[c]
                    }
                }
                if (index < EMBEDDING_DIM) {
                    embedding[index++] = avgVariance
                }
            }

            // Fill the remaining dimension elements with sequence-wide stats
            val globalMean = DoubleArray(13)
            for (frame in mfccSequence) {
                for (c in 0 until 13) {
                    globalMean[c] += frame[c]
                }
            }
            for (c in 0 until 13) {
                globalMean[c] /= mfccSequence.size
                if (index < EMBEDDING_DIM) {
                    embedding[index++] = globalMean[c]
                }
            }

            // Normalize embedding vector to unit length (L2 Normalization)
            var norm = 0.0
            for (v in embedding) {
                norm += v * v
            }
            norm = sqrt(norm)
            if (norm > 0.0) {
                for (i in embedding.indices) {
                    embedding[i] /= norm
                }
            }

            return embedding
        }

        /**
         * Computes Cosine Similarity between two L2-normalized vectors.
         * Ranges from [-1.0, 1.0]. A value of 1.0 means identical orientation.
         */
        fun cosineSimilarity(vectorA: DoubleArray, vectorB: DoubleArray): Double {
            if (vectorA.size != vectorB.size) return 0.0
            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in vectorA.indices) {
                dotProduct += vectorA[i] * vectorB[i]
                normA += vectorA[i] * vectorA[i]
                normB += vectorB[i] * vectorB[i]
            }
            if (normA == 0.0 || normB == 0.0) return 0.0
            return dotProduct / (sqrt(normA) * sqrt(normB))
        }

        /**
         * Decodes comma-separated text back into a DoubleArray embedding.
         */
        fun stringToEmbedding(str: String?): DoubleArray? {
            if (str.isNullOrBlank()) return null
            return try {
                str.split(",").map { it.toDouble() }.toDoubleArray()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Encodes a DoubleArray embedding into a comma-separated String for storage.
         */
        fun embeddingToString(embedding: DoubleArray): String {
            return embedding.joinToString(",")
        }

        /**
         * Compares query audio with stored contacts using L2 Voice Embeddings & Cosine Similarity.
         * Strictly implements:
         *   Cosine_Threshold = Sensitivity_Percentage / 100.0
         *   if (Cosine_Similarity_Score >= Cosine_Threshold) { // Match success }
         */
        suspend fun findBestMatch(
            recordedAudio: ShortArray,
            contacts: List<Contact>,
            sensitivityPercentage: Double
        ): Contact? {
            val queryEmbedding = generateEmbedding(recordedAudio)
            
            var bestContact: Contact? = null
            var maxSimilarity = -2.0 // Initialize below lower bound of cosine similarity

            val cosineThreshold = sensitivityPercentage / 100.0
            Log.d(TAG, "Cosine Threshold derived: $cosineThreshold from sensitivity $sensitivityPercentage%")

            for (contact in contacts) {
                // Try reading pre-computed embedding from the Contact entity
                var contactEmbedding = stringToEmbedding(contact.voiceEmbedding)

                if (contactEmbedding == null) {
                    // Fallback: Compute embedding dynamically from recorded voice tag
                    val voiceTagPath = contact.voiceTagPath ?: continue
                    val tagFile = File(voiceTagPath)
                    if (!tagFile.exists()) continue

                    val tagRawAudio = com.example.audio.AudioRecordHelper.readPcmData(tagFile)
                    if (tagRawAudio.isEmpty()) continue
                    contactEmbedding = generateEmbedding(tagRawAudio)
                }

                val similarity = cosineSimilarity(queryEmbedding, contactEmbedding)
                Log.d(TAG, "Contact '${contact.name}' Cosine Similarity: $similarity (Threshold: $cosineThreshold)")

                if (similarity > maxSimilarity) {
                    maxSimilarity = similarity
                    bestContact = contact
                }
            }

            Log.d(TAG, "Best match found: ${bestContact?.name} with Cosine Similarity $maxSimilarity (Threshold: $cosineThreshold)")

            return if (bestContact != null && maxSimilarity >= cosineThreshold) {
                bestContact
            } else {
                null
            }
        }
    }

    // --- TFLITE INTERPRETER ARCHITECTURE SIMULATOR ---
    // Simulates an asynchronous background TFLite interpreter for YAMNet / Speech-Embeddings
    class TFLiteInterpreterSimulator(private val context: Context, private val modelName: String) {
        
        private var isInitialized = false

        init {
            // Emulate background loading of model file
            Thread {
                try {
                    Log.d(TAG, "Loading TFLite Model '$modelName' on background thread...")
                    Thread.sleep(300) // Emulate file reading delay
                    isInitialized = true
                    Log.d(TAG, "TFLite Model '$modelName' loaded successfully into interpreter memory.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading model", e)
                }
            }.start()
        }

        fun runInference(audioData: ShortArray): FloatArray {
            if (!isInitialized) {
                Log.w(TAG, "Interpreter not fully ready. Performing fast fallback inference...")
            }
            // Real TFLite interpreters convert short audio buffer to direct ByteBuffers
            val byteBuffer = ByteBuffer.allocateDirect(audioData.size * 2).apply {
                order(ByteOrder.nativeOrder())
            }
            for (sample in audioData) {
                byteBuffer.putShort(sample)
            }

            // Compute embedding from the audio frame array
            val dEmbed = generateEmbedding(audioData)
            return FloatArray(dEmbed.size) { i -> dEmbed[i].toFloat() }
        }
    }
}
