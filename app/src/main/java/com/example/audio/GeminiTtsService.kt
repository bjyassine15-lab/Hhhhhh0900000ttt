package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Moshi Data Classes for Gemini REST API ---

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: GeminiInlineData? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "parts") val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class PrebuiltVoiceConfig(
    @Json(name = "voiceName") val voiceName: String
)

@JsonClass(generateAdapter = true)
data class VoiceConfig(
    @Json(name = "prebuiltVoiceConfig") val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@JsonClass(generateAdapter = true)
data class SpeechConfig(
    @Json(name = "voiceConfig") val voiceConfig: VoiceConfig
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "responseModalities") val responseModalities: List<String>?,
    @Json(name = "speechConfig") val speechConfig: SpeechConfig?
)

@JsonClass(generateAdapter = true)
data class GeminiTtsRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent
)

@JsonClass(generateAdapter = true)
data class GeminiTtsResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>?
)

// --- Retrofit API Service ---

interface GeminiTtsApi {
    @POST("v1beta/models/gemini-3.1-flash-tts-preview:generateContent")
    suspend fun generateSpeech(
        @Query("key") apiKey: String,
        @Body request: GeminiTtsRequest
    ): GeminiTtsResponse
}

class GeminiTtsService(private val context: Context) : TextToSpeech.OnInitListener {
    private companion object {
        const val TAG = "GeminiTtsService"
        const val BASE_URL = "https://generativelanguage.googleapis.com/"
    }

    private var nativeTts: TextToSpeech? = null
    private var nativeTtsInitialized = false
    private var mediaPlayer: MediaPlayer? = null

    init {
        // Initialize native Text-to-Speech as local offline fallback
        nativeTts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val arabicResult = nativeTts?.setLanguage(Locale("ar"))
            if (arabicResult == TextToSpeech.LANG_MISSING_DATA || arabicResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Arabic language is not supported on this device. Trying French...")
                nativeTts?.setLanguage(Locale.FRENCH)
            }
            nativeTtsInitialized = true
            Log.d(TAG, "Native TTS initialized successfully")
        } else {
            Log.e(TAG, "Native TTS initialization failed")
        }
    }

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api = retrofit.create(GeminiTtsApi::class.java)

    /**
     * Speaks the given text. Tries Gemini 3.1 TTS first, falls back to native Android TTS if it fails.
     */
    suspend fun speak(text: String) = withContext(Dispatchers.Main) {
        stopSpeaking()

        val apiKey = BuildConfig.GEMINI_API_KEY
        val hasApiKey = apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"

        if (hasApiKey) {
            try {
                Log.d(TAG, "Attempting Gemini 3.1 Flash TTS...")
                val audioBytes = getGeminiSpeechBytes(text, apiKey)
                if (audioBytes != null) {
                    playAudioBytes(audioBytes)
                    return@withContext
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini TTS failed, falling back to local TTS...", e)
            }
        } else {
            Log.d(TAG, "No valid Gemini API Key, using local fallback directly")
        }

        // Local Fallback
        speakNative(text)
    }

    private suspend fun getGeminiSpeechBytes(text: String, apiKey: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = GeminiTtsRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = text))
                )
            ),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfig(
                    voiceConfig = VoiceConfig(
                        prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Aoede") // high quality voice
                    )
                )
            )
        )

        val response = api.generateSpeech(apiKey, request)
        val inlineData = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData
        if (inlineData != null) {
            return@withContext Base64.decode(inlineData.data, Base64.DEFAULT)
        }
        null
    }

    private fun playAudioBytes(bytes: ByteArray) {
        try {
            val tempFile = File.createTempFile("gemini_tts", ".mp3", context.cacheDir)
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing Gemini audio bytes", e)
            // Last resort fallback
            speakNative("جاري الاتصال")
        }
    }

    private fun speakNative(text: String) {
        if (nativeTtsInitialized && nativeTts != null) {
            Log.d(TAG, "Speaking via native TTS: $text")
            nativeTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "easydialer_tts_id")
        } else {
            Log.e(TAG, "Native TTS not ready, cannot speak text")
        }
    }

    fun stopSpeaking() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            mediaPlayer = null
        }

        try {
            nativeTts?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun shutdown() {
        stopSpeaking()
        try {
            nativeTts?.shutdown()
        } catch (e: Exception) {
            // ignore
        } finally {
            nativeTts = null
            nativeTtsInitialized = false
        }
    }
}
