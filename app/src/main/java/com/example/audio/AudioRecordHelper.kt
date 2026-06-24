package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AudioConfig {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val BUFFER_SIZE_FACTOR = 2
}

class AudioRecordHelper {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startRecording(outputFile: File) {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT
        )

        val bufferSize = minBufferSize * AudioConfig.BUFFER_SIZE_FACTOR

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecordHelper", "AudioRecord initialization failed")
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread({
            writeAudioDataToFile(outputFile, bufferSize)
        }, "AudioRecordingThread")
        recordingThread?.start()
    }

    private fun writeAudioDataToFile(file: File, bufferSize: Int) {
        val data = ByteArray(bufferSize)
        var os: FileOutputStream? = null
        try {
            os = FileOutputStream(file)
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    os.write(data, 0, read)
                }
            }
        } catch (e: IOException) {
            Log.e("AudioRecordHelper", "Error writing audio to file", e)
        } finally {
            try {
                os?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioRecordHelper", "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
            recordingThread = null
        }
    }

    companion object {
        suspend fun readPcmData(file: File): ShortArray = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext ShortArray(0)
            val bytes = file.readBytes()
            val shorts = ShortArray(bytes.size / 2)
            for (i in shorts.indices) {
                val low = bytes[i * 2].toInt() and 0xFF
                val high = bytes[i * 2 + 1].toInt()
                shorts[i] = ((high shl 8) or low).toShort()
            }
            shorts
        }
    }
}
