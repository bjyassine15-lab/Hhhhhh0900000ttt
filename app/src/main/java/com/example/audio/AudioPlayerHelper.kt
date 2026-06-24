package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class AudioPlayerHelper {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    @Suppress("DEPRECATION")
    suspend fun playPcmFile(file: File) = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            Log.e("AudioPlayerHelper", "File does not exist: ${file.absolutePath}")
            return@withContext
        }

        stopPlaying()

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioConfig.AUDIO_FORMAT
        )

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioConfig.AUDIO_FORMAT)
                        .setSampleRate(AudioConfig.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioConfig.AUDIO_FORMAT,
                minBufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e("AudioPlayerHelper", "AudioTrack state was not initialized")
            return@withContext
        }

        isPlaying = true
        audioTrack?.play()

        val buffer = ByteArray(minBufferSize)
        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(file)
            var read = 0
            while (isPlaying && fis.read(buffer).also { read = it } != -1) {
                audioTrack?.write(buffer, 0, read)
            }
        } catch (e: IOException) {
            Log.e("AudioPlayerHelper", "Error playing PCM file", e)
        } finally {
            try {
                fis?.close()
            } catch (e: Exception) {
                // ignore
            }
            stopPlaying()
        }
    }

    fun stopPlaying() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            audioTrack = null
        }
    }
}
