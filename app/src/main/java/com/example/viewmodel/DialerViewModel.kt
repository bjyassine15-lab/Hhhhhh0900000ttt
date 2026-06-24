package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.EmbeddingMatcher
import com.example.audio.AudioPlayerHelper
import com.example.audio.AudioRecordHelper
import com.example.audio.GeminiTtsService
import com.example.data.Contact
import com.example.data.ContactDatabase
import com.example.data.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed interface DialerUiState {
    object Idle : DialerUiState
    object Recording : DialerUiState
    object Matching : DialerUiState
    data class MatchingFailed(val message: String) : DialerUiState
    data class CallCountdown(val contact: Contact, val secondsRemaining: Int) : DialerUiState
    data class CallLaunched(val contact: Contact) : DialerUiState
}

class DialerViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "DialerViewModel"
        const val MAX_DTW_THRESHOLD = 3.5 // Similarity ceiling for a match
    }

    private val repository = ContactRepository(ContactDatabase.getDatabase(application).contactDao())
    val contacts: StateFlow<List<Contact>> = repository.allContacts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val prefs = application.getSharedPreferences("mima_prefs", Context.MODE_PRIVATE)
    private val _sensitivity = MutableStateFlow(prefs.getFloat("sensitivity", 70f))
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    fun updateSensitivity(value: Float) {
        _sensitivity.value = value
        prefs.edit().putFloat("sensitivity", value).apply()
    }

    private val _uiState = MutableStateFlow<DialerUiState>(DialerUiState.Idle)
    val uiState: StateFlow<DialerUiState> = _uiState.asStateFlow()

    private val audioRecordHelper = AudioRecordHelper()
    private val audioPlayerHelper = AudioPlayerHelper()
    private val ttsService = GeminiTtsService(application)

    private var currentRecordingFile: File? = null
    private var countdownJob: Job? = null

    // --- MAIN SCREEN ACTIONS ---

    private val stateInScope = viewModelScope

    fun onMicrophonePressed() {
        val currentState = _uiState.value
        if (currentState is DialerUiState.Idle) {
            startLiveRecording()
        } else if (currentState is DialerUiState.Recording) {
            stopLiveRecordingAndMatch()
        }
    }

    private fun startLiveRecording() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val tempFile = File(context.cacheDir, "live_query_${UUID.randomUUID()}.pcm")
                currentRecordingFile = tempFile
                _uiState.value = DialerUiState.Recording
                audioRecordHelper.startRecording(tempFile)
                Log.d(TAG, "Started live recording to ${tempFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _uiState.value = DialerUiState.Idle
            }
        }
    }

    private fun stopLiveRecordingAndMatch() {
        viewModelScope.launch {
            _uiState.value = DialerUiState.Matching
            audioRecordHelper.stopRecording()
            Log.d(TAG, "Stopped live recording, starting matching process...")

            val queryFile = currentRecordingFile
            if (queryFile == null || !queryFile.exists() || queryFile.length() <= 0) {
                _uiState.value = DialerUiState.MatchingFailed("لم يتم تسجيل أي صوت")
                delay(2000)
                _uiState.value = DialerUiState.Idle
                return@launch
            }

            matchAudio(queryFile)
        }
    }

    private suspend fun matchAudio(queryFile: File) {
        val currentContacts = contacts.value
        if (currentContacts.isEmpty()) {
            _uiState.value = DialerUiState.MatchingFailed("لا توجد جهات اتصال مخزنة")
            delay(2000)
            _uiState.value = DialerUiState.Idle
            return
        }

        try {
            // Read query file PCM
            val queryRawAudio = AudioRecordHelper.readPcmData(queryFile)
            if (queryRawAudio.isEmpty()) {
                _uiState.value = DialerUiState.MatchingFailed("الملف الصوتي فارغ")
                delay(2000)
                _uiState.value = DialerUiState.Idle
                return
            }

            // Perform matching using high-fidelity EmbeddingMatcher
            val bestContact = EmbeddingMatcher.findBestMatch(
                recordedAudio = queryRawAudio,
                contacts = currentContacts,
                sensitivityPercentage = sensitivity.value.toDouble()
            )

            if (bestContact != null) {
                startCallCountdown(bestContact)
            } else {
                Log.d(TAG, "Matching failed - no contact matched current sensitivity threshold of ${sensitivity.value}%")
                _uiState.value = DialerUiState.MatchingFailed("الاسم غير موجود، يرجى إعادة المحاولة")
                ttsService.speak("الاسم غير موجود، يرجى إعادة المحاولة")
                delay(3000)
                _uiState.value = DialerUiState.Idle
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching voice tag", e)
            _uiState.value = DialerUiState.MatchingFailed("خطأ أثناء مطابقة الصوت")
            delay(2000)
            _uiState.value = DialerUiState.Idle
        } finally {
            try {
                queryFile.delete()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun startCallCountdown(contact: Contact) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            // Read announcement using Gemini / Local TTS
            val ArabicTtsText = "يتم الاتصال بـ ${contact.name} الآن"
            ttsService.speak(ArabicTtsText)

            var remaining = 3
            while (remaining > 0) {
                _uiState.value = DialerUiState.CallCountdown(contact, remaining)
                delay(1000)
                remaining--
            }

            _uiState.value = DialerUiState.CallLaunched(contact)
            performActualCall(contact)
            delay(1500)
            _uiState.value = DialerUiState.Idle
        }
    }

    fun cancelCall() {
        countdownJob?.cancel()
        countdownJob = null
        ttsService.stopSpeaking()
        _uiState.value = DialerUiState.Idle
        Log.d(TAG, "Call cancelled by user")
    }

    private fun performActualCall(contact: Contact) {
        val context = getApplication<Application>()
        val phoneUri = Uri.parse("tel:${contact.phoneNumber}")

        // Attempt direct call first, fallback to dial screen if permission issues or not available
        val intent = Intent(Intent.ACTION_CALL, phoneUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "ACTION_CALL permission not granted. Falling back to ACTION_DIAL", e)
            val fallbackIntent = Intent(Intent.ACTION_DIAL, phoneUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error performing call", e)
        }
    }

    // --- CAREGIVER SETUP ACTIONS ---

    fun saveContact(
        name: String,
        phoneNumber: String,
        tempImageFile: File?,
        tempVoiceFile: File?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isBlank() || phoneNumber.isBlank()) {
                launch(Dispatchers.Main) { onError("يرجى ملء جميع الحقول") }
                return@launch
            }

            if (tempVoiceFile == null || !tempVoiceFile.exists()) {
                launch(Dispatchers.Main) { onError("يرجى تسجيل البصمة الصوتية") }
                return@launch
            }

            try {
                val context = getApplication<Application>()
                val internalFilesDir = context.filesDir

                // Save image file
                var finalImagePath: String? = null
                if (tempImageFile != null && tempImageFile.exists()) {
                    val destImageFile = File(internalFilesDir, "contact_img_${UUID.randomUUID()}.jpg")
                    tempImageFile.copyTo(destImageFile, overwrite = true)
                    finalImagePath = destImageFile.absolutePath
                }

                // Save voice tag file
                val destVoiceFile = File(internalFilesDir, "contact_voice_${UUID.randomUUID()}.pcm")
                tempVoiceFile.copyTo(destVoiceFile, overwrite = true)
                val finalVoicePath = destVoiceFile.absolutePath

                // Generate Embedding from saved voice tag
                val rawAudio = AudioRecordHelper.readPcmData(destVoiceFile)
                val embedding = EmbeddingMatcher.generateEmbedding(rawAudio)
                val embeddingString = EmbeddingMatcher.embeddingToString(embedding)

                val contact = Contact(
                    name = name.trim(),
                    phoneNumber = phoneNumber.trim(),
                    imagePath = finalImagePath,
                    voiceTagPath = finalVoicePath,
                    voiceEmbedding = embeddingString
                )

                repository.insert(contact)

                // Clean up temporary files
                try {
                    tempImageFile?.delete()
                    tempVoiceFile.delete()
                } catch (e: Exception) {
                    // ignore
                }

                launch(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving contact", e)
                launch(Dispatchers.Main) {
                    onError("حدث خطأ أثناء حفظ جهة الاتصال")
                }
            }
        }
    }

    fun updateContact(
        contactId: Int,
        name: String,
        phoneNumber: String,
        tempImageFile: File?,
        keepOldImage: Boolean,
        tempVoiceFile: File?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isBlank() || phoneNumber.isBlank()) {
                launch(Dispatchers.Main) { onError("يرجى ملء جميع الحقول") }
                return@launch
            }

            try {
                val context = getApplication<Application>()
                val internalFilesDir = context.filesDir
                val existingContact = repository.getById(contactId) ?: run {
                    launch(Dispatchers.Main) { onError("جهة الاتصال غير موجودة") }
                    return@launch
                }

                // Handle Image
                var finalImagePath = existingContact.imagePath
                if (tempImageFile != null && tempImageFile.exists()) {
                    // Delete old image if it exists
                    existingContact.imagePath?.let { oldPath ->
                        val oldFile = File(oldPath)
                        if (oldFile.exists()) oldFile.delete()
                    }
                    val destImageFile = File(internalFilesDir, "contact_img_${UUID.randomUUID()}.jpg")
                    tempImageFile.copyTo(destImageFile, overwrite = true)
                    finalImagePath = destImageFile.absolutePath
                } else if (!keepOldImage) {
                    existingContact.imagePath?.let { oldPath ->
                        val oldFile = File(oldPath)
                        if (oldFile.exists()) oldFile.delete()
                    }
                    finalImagePath = null
                }

                // Handle Voice
                var finalVoicePath = existingContact.voiceTagPath
                var finalVoiceEmbedding = existingContact.voiceEmbedding
                if (tempVoiceFile != null && tempVoiceFile.exists()) {
                    // Delete old voice tag if it exists
                    existingContact.voiceTagPath?.let { oldPath ->
                        val oldFile = File(oldPath)
                        if (oldFile.exists()) oldFile.delete()
                    }
                    val destVoiceFile = File(internalFilesDir, "contact_voice_${UUID.randomUUID()}.pcm")
                    tempVoiceFile.copyTo(destVoiceFile, overwrite = true)
                    finalVoicePath = destVoiceFile.absolutePath

                    // Generate Embedding from saved voice tag
                    val rawAudio = AudioRecordHelper.readPcmData(destVoiceFile)
                    val embedding = EmbeddingMatcher.generateEmbedding(rawAudio)
                    finalVoiceEmbedding = EmbeddingMatcher.embeddingToString(embedding)
                }

                if (finalVoicePath == null) {
                    launch(Dispatchers.Main) { onError("البصمة الصوتية مطلوبة") }
                    return@launch
                }

                val updatedContact = existingContact.copy(
                    name = name.trim(),
                    phoneNumber = phoneNumber.trim(),
                    imagePath = finalImagePath,
                    voiceTagPath = finalVoicePath,
                    voiceEmbedding = finalVoiceEmbedding
                )

                repository.update(updatedContact)

                // Clean up temporary files
                try {
                    tempImageFile?.delete()
                    tempVoiceFile?.delete()
                } catch (e: Exception) {
                    // ignore
                }

                launch(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating contact", e)
                launch(Dispatchers.Main) {
                    onError("حدث خطأ أثناء تعديل جهة الاتصال")
                }
            }
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.delete(contact)
                // Delete physical files
                contact.imagePath?.let { File(it).delete() }
                contact.voiceTagPath?.let { File(it).delete() }
                Log.d(TAG, "Deleted contact and files for ${contact.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting contact", e)
            }
        }
    }

    fun playRecordedVoice(file: File) {
        viewModelScope.launch {
            audioPlayerHelper.playPcmFile(file)
        }
    }

    fun stopPlayingVoice() {
        audioPlayerHelper.stopPlaying()
    }

    fun backupData(onSuccess: (File) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                
                // Flush database transactions by shutting it down temporarily
                ContactDatabase.getDatabase(context).close()
                
                val backupFile = File(context.cacheDir, "mima_backup.zip")
                if (backupFile.exists()) backupFile.delete()

                ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile))).use { out ->
                    // Zip Room Database files
                    val dbFile = context.getDatabasePath("contact_database")
                    val dbParent = dbFile.parentFile
                    if (dbParent != null && dbParent.exists()) {
                        val dbFiles = dbParent.listFiles { _, name -> name.startsWith("contact_database") } ?: emptyArray()
                        for (file in dbFiles) {
                            if (file.exists() && file.isFile) {
                                out.putNextEntry(ZipEntry("db/${file.name}"))
                                BufferedInputStream(FileInputStream(file)).use { input ->
                                    input.copyTo(out)
                                }
                                out.closeEntry()
                            }
                        }
                    }

                    // Zip internal files (voice tags and contact photos)
                    val files = context.filesDir.listFiles() ?: emptyArray()
                    for (file in files) {
                        if (file.exists() && file.isFile) {
                            out.putNextEntry(ZipEntry("files/${file.name}"))
                            BufferedInputStream(FileInputStream(file)).use { input ->
                                input.copyTo(out)
                            }
                            out.closeEntry()
                        }
                    }
                }

                launch(Dispatchers.Main) {
                    onSuccess(backupFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating backup zip", e)
                launch(Dispatchers.Main) {
                    onError("فشل إنشاء نسخة احتياطية: ${e.localizedMessage}")
                }
            }
        }
    }

    fun restoreData(zipUri: Uri, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                
                // Close current connection
                ContactDatabase.getDatabase(context).close()

                val contentResolver = context.contentResolver
                contentResolver.openInputStream(zipUri)?.use { inputStream ->
                    ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (!entry.isDirectory) {
                                if (name.startsWith("db/")) {
                                    val dbFileName = name.substringAfter("db/")
                                    val destDbFile = context.getDatabasePath(dbFileName)
                                    destDbFile.parentFile?.mkdirs()
                                    FileOutputStream(destDbFile).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                } else if (name.startsWith("files/")) {
                                    val fileName = name.substringAfter("files/")
                                    val destFile = File(context.filesDir, fileName)
                                    destFile.parentFile?.mkdirs()
                                    FileOutputStream(destFile).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }

                // Re-open/initialize database
                ContactDatabase.getDatabase(context)

                launch(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring backup", e)
                launch(Dispatchers.Main) {
                    onError("فشل استعادة البيانات: ${e.localizedMessage}")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecordHelper.stopRecording()
        audioPlayerHelper.stopPlaying()
        ttsService.shutdown()
    }
}
