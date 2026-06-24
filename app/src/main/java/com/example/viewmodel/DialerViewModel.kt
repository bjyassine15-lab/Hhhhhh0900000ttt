package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioMatcher
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
import java.io.File
import java.util.UUID

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
            // Read and trim query file
            val queryRawAudio = AudioRecordHelper.readPcmData(queryFile)
            if (queryRawAudio.isEmpty()) {
                _uiState.value = DialerUiState.MatchingFailed("الملف الصوتي فارغ")
                delay(2000)
                _uiState.value = DialerUiState.Idle
                return
            }

            val queryFeatures = AudioMatcher.extractFeatures(queryRawAudio)
            if (queryFeatures.isEmpty()) {
                _uiState.value = DialerUiState.MatchingFailed("لم يتم كشف كلام في التسجيل")
                delay(2000)
                _uiState.value = DialerUiState.Idle
                return
            }

            var bestContact: Contact? = null
            var minDistance = Double.MAX_VALUE

            // Compare with all stored contacts
            for (contact in currentContacts) {
                val voiceTagPath = contact.voiceTagPath ?: continue
                val tagFile = File(voiceTagPath)
                if (!tagFile.exists()) continue

                val tagRawAudio = AudioRecordHelper.readPcmData(tagFile)
                val tagFeatures = AudioMatcher.extractFeatures(tagRawAudio)
                if (tagFeatures.isEmpty()) continue

                val distance = AudioMatcher.computeDtwDistance(queryFeatures, tagFeatures)
                Log.d(TAG, "Contact ${contact.name}: DTW distance = $distance")

                if (distance < minDistance) {
                    minDistance = distance
                    bestContact = contact
                }
            }

            Log.d(TAG, "Best match found: ${bestContact?.name} with distance $minDistance (Threshold: $MAX_DTW_THRESHOLD)")

            if (bestContact != null && minDistance <= MAX_DTW_THRESHOLD) {
                startCallCountdown(bestContact)
            } else {
                Log.d(TAG, "Matching failed - minimum distance $minDistance exceeds threshold")
                _uiState.value = DialerUiState.MatchingFailed("صوت غير معروف")
                ttsService.speak("لم يتم التعرف على الصوت. الرجاء المحاولة مجددا")
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

                val contact = Contact(
                    name = name.trim(),
                    phoneNumber = phoneNumber.trim(),
                    imagePath = finalImagePath,
                    voiceTagPath = finalVoicePath
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

    override fun onCleared() {
        super.onCleared()
        audioRecordHelper.stopRecording()
        audioPlayerHelper.stopPlaying()
        ttsService.shutdown()
    }
}
