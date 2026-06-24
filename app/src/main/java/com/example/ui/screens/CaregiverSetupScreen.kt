package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audio.AudioRecordHelper
import com.example.data.Contact
import com.example.viewmodel.DialerViewModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaregiverSetupScreen(
    viewModel: DialerViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()

    var name by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }

    // Media states
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var tempVoiceFile by remember { mutableStateOf<File?>(null) }
    var isRecordingVoiceTag by remember { mutableStateOf(false) }
    var hasVoiceTag by remember { mutableStateOf(false) }

    val audioRecordHelper = remember { AudioRecordHelper() }

    // Gallery Picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val pickedFile = copyUriToCache(context, it)
                selectedImageFile = pickedFile
            } catch (e: Exception) {
                Toast.makeText(context, "فشل تحميل الصورة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Mic recording permission
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasMicPermission = isGranted }
    )

    fun startRecordingVoice() {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val voiceFile = File(context.cacheDir, "temp_voice_tag_${UUID.randomUUID()}.pcm")
        tempVoiceFile = voiceFile
        isRecordingVoiceTag = true
        hasVoiceTag = false
        audioRecordHelper.startRecording(voiceFile)
    }

    fun stopRecordingVoice() {
        if (!isRecordingVoiceTag) return
        audioRecordHelper.stopRecording()
        isRecordingVoiceTag = false
        hasVoiceTag = tempVoiceFile != null && tempVoiceFile!!.exists() && tempVoiceFile!!.length() > 0
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111318)) // Caregiver elegant dark background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "إعدادات المشرف (تعديل جهات الاتصال)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E2E6)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "عودة",
                            tint = Color(0xFFE2E2E6)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF1A1B20)
                )
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
            ) {
                // ADD CONTACT FORM CARD
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2D35)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "إضافة جهة اتصال جديدة",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2E2E6),
                                modifier = Modifier.align(Alignment.Start)
                            )

                            // Contact Image Picker Area
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF44474E))
                                    .border(2.dp, Color(0xFF5A5E66), RoundedCornerShape(20.dp))
                                    .clickable { galleryLauncher.launch("image/*") }
                                    .testTag("add_photo_area"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedImageFile != null && selectedImageFile!!.exists()) {
                                    val bitmap = BitmapFactory.decodeFile(selectedImageFile!!.absolutePath)
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Selected Photo",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                                    }
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = Color(0xFFA1A2A6), modifier = Modifier.size(32.dp))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("رفع صورة كبرى", fontSize = 12.sp, color = Color(0xFFA1A2A6))
                                    }
                                }
                            }

                            // Name input (Arabic support RTL text direction)
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("اسم جهة الاتصال (سري - للنطق فقط)", color = Color(0xFFA1A2A6)) },
                                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.ContentOrRtl, color = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("name_input"),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Color(0xFF111318),
                                    unfocusedContainerColor = Color(0xFF111318),
                                    focusedIndicatorColor = Color(0xFFD3E3FD),
                                    unfocusedIndicatorColor = Color(0x33FFFFFF)
                                )
                            )

                            // Phone input
                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = { phoneNumber = it },
                                label = { Text("رقم الهاتف الفعلي", color = Color(0xFFA1A2A6)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                textStyle = LocalTextStyle.current.copy(color = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("phone_input"),
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Color(0xFF111318),
                                    unfocusedContainerColor = Color(0xFF111318),
                                    focusedIndicatorColor = Color(0xFFD3E3FD),
                                    unfocusedIndicatorColor = Color(0x33FFFFFF)
                                )
                            )

                            Divider(color = Color(0x1AFFFFFF))

                            // VOICE TAG RECORDING AREA
                            Text(
                                "تسجيل البصمة الصوتية (اللهجة المحلية)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFA1A2A6),
                                modifier = Modifier.align(Alignment.Start)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Record voice tag button
                                Button(
                                    onClick = {
                                        if (isRecordingVoiceTag) {
                                            stopRecordingVoice()
                                        } else {
                                            startRecordingVoice()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isRecordingVoiceTag) Color(0xFFEF4444) else Color(0xFF3B82F6)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("record_voice_tag_button")
                                ) {
                                    Icon(
                                        imageVector = if (isRecordingVoiceTag) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (isRecordingVoiceTag) "إيقاف التسجيل" else "سجل نطق الاسم",
                                        fontSize = 14.sp
                                    )
                                }

                                // Preview recorded voice tag button
                                if (hasVoiceTag && tempVoiceFile != null) {
                                    IconButton(
                                        onClick = { viewModel.playRecordedVoice(tempVoiceFile!!) },
                                        modifier = Modifier
                                            .size(50.dp)
                                            .background(Color(0xFF10B981), RoundedCornerShape(12.dp))
                                            .testTag("preview_voice_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play voice tag preview",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Save Contact Submit Button
                            Button(
                                onClick = {
                                    viewModel.saveContact(
                                        name = name,
                                        phoneNumber = phoneNumber,
                                        tempImageFile = selectedImageFile,
                                        tempVoiceFile = tempVoiceFile,
                                        onSuccess = {
                                            Toast.makeText(context, "تم حفظ جهة الاتصال بنجاح", Toast.LENGTH_SHORT).show()
                                            // Reset fields
                                            name = ""
                                            phoneNumber = ""
                                            selectedImageFile = null
                                            tempVoiceFile = null
                                            hasVoiceTag = false
                                        },
                                        onError = { error ->
                                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("save_contact_button")
                            ) {
                                Text("حفظ جهة الاتصال", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // LIST OF CURRENT CONTACTS (FOR MANAGEMENT/DELETION)
                item {
                    Text(
                        "جهات الاتصال المسجلة حالياً (${contacts.size})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E2E6),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (contacts.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2D35)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("لا توجد جهات اتصال بعد.", color = Color(0xFFA1A2A6), fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    items(contacts) { contact ->
                        ContactSetupItemRow(
                            contact = contact,
                            onPlayVoice = {
                                contact.voiceTagPath?.let { path ->
                                    viewModel.playRecordedVoice(File(path))
                                }
                            },
                            onDelete = { viewModel.deleteContact(contact) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactSetupItemRow(
    contact: Contact,
    onPlayVoice: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2D35)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Contact thumbnail
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF44474E))
            ) {
                if (contact.imagePath != null) {
                    val imageFile = File(contact.imagePath)
                    if (imageFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFD3E3FD), modifier = Modifier.align(Alignment.Center))
                        }
                    } else {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFD3E3FD), modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFD3E3FD), modifier = Modifier.align(Alignment.Center))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    color = Color(0xFFE2E2E6),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = contact.phoneNumber,
                    color = Color(0xFFA1A2A6),
                    fontSize = 14.sp
                )
            }

            // Audio Tag play button
            if (contact.voiceTagPath != null) {
                IconButton(
                    onClick = onPlayVoice,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .background(Color(0x223B82F6), CircleShape)
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "Play Voice Tag", tint = Color(0xFF3B82F6))
                }
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .background(Color(0x22EF4444), CircleShape)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Contact", tint = Color(0xFFEF4444))
            }
        }
    }
}

/**
 * Copies Uri data from Android Document Provider to cached temporary file.
 */
private fun copyUriToCache(context: Context, uri: Uri): File {
    val contentResolver = context.contentResolver
    val inputStream = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open input stream")
    val cachedFile = File(context.cacheDir, "picked_media_${UUID.randomUUID()}.jpg")
    FileOutputStream(cachedFile).use { fos ->
        inputStream.copyTo(fos)
    }
    inputStream.close()
    return cachedFile
}
