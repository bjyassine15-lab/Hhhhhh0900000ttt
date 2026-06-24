package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.Contact
import com.example.viewmodel.DialerUiState
import com.example.viewmodel.DialerViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDialerScreen(
    viewModel: DialerViewModel,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    // Animation state for pulsing recording button
    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    // Check recording permission
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
        onResult = { isGranted ->
            hasMicPermission = isGranted
        }
    )

    // Call permissions check
    var hasCallPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasCallPermission = isGranted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (!hasCallPermission) {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111318))
    ) {
        // CONTENT AREA
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Header: Minimal settings gear at the top right, zero text.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateToSetup,
                    modifier = Modifier
                        .size(60.dp)
                        .background(Color(0xFF2A2D35), CircleShape)
                        .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Setup Mode",
                        tint = Color(0xFFE2E2E6),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            if (contacts.isEmpty()) {
                // Friendly Empty State directed to caregiver
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "No contacts",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "لا توجد جهات اتصال بعد",
                            color = Color(0xFFE2E2E6),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "الرجاء الضغط على زر الإعدادات في الأعلى لإضافة جهات الاتصال وتسجيل البصمات الصوتية.",
                            color = Color(0xFFA1A2A6),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = onNavigateToSetup,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(56.dp)
                                .fillMaxWidth(0.8f)
                        ) {
                            Text("إضافة جهة اتصال", fontSize = 18.sp, color = Color.White)
                        }
                    }
                }
            } else {
                // Giant Contact Grid (2 columns, highly visible, zero text)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 180.dp), // Generous bottom padding to scroll past floating mic
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(contacts) { contact ->
                        ContactGridCard(contact = contact)
                    }
                }
            }
        }

        // FLOATING ACTION BUTTON (Center Bottom Microphone)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp)
        ) {
            val isRecording = uiState is DialerUiState.Recording

            // Pulse backgrounds for active recording
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(pulseScale)
                        .background(Color(0x22EF4444), CircleShape)
                        .align(Alignment.Center)
                )
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pulseScale * 0.9f)
                        .background(Color(0x11EF4444), CircleShape)
                        .align(Alignment.Center)
                )
            }

            FloatingActionButton(
                onClick = {
                    if (!hasMicPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.onMicrophonePressed()
                    }
                },
                shape = CircleShape,
                // Elegant Light Blue/Navy layout for accessibility, and Red when active
                containerColor = if (isRecording) Color(0xFFEF4444) else Color(0xFFD3E3FD),
                contentColor = if (isRecording) Color.White else Color(0xFF041E49),
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.Center)
                    .testTag("mic_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // OVERLAYS (Matching state, error, call countdown)
        AnimatedVisibility(
            visible = uiState !is DialerUiState.Idle,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (val state = uiState) {
                is DialerUiState.Matching -> {
                    OverlayContainer {
                        CircularProgressIndicator(
                            color = Color(0xFFD3E3FD),
                            strokeWidth = 6.dp,
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "جاري التعرف على الصوت...",
                            color = Color(0xFFE2E2E6),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is DialerUiState.MatchingFailed -> {
                    OverlayContainer {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Failed",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(96.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = state.message,
                            color = Color(0xFFE2E2E6),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                is DialerUiState.CallCountdown -> {
                    OverlayContainer {
                        // Large contact photo
                        ContactCircularImage(
                            imagePath = state.contact.imagePath,
                            modifier = Modifier.size(200.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            text = "يتم الاتصال بالصوت...",
                            color = Color(0xFFD3E3FD),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = state.secondsRemaining.toString(),
                            color = Color(0xFFF59E0B),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Black
                        )
                        
                        Spacer(modifier = Modifier.height(40.dp))
                        
                        // Giant Cancel Button using #FFDAD6 (pastel light red-pink) and #410002 (deep dark red)
                        Button(
                            onClick = { viewModel.cancelCall() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFDAD6),
                                contentColor = Color(0xFF410002)
                            ),
                            shape = RoundedCornerShape(64.dp),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(2.8f)
                                .testTag("cancel_call_button")
                        ) {
                            Text(
                                text = "إلغاء",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
                is DialerUiState.CallLaunched -> {
                    OverlayContainer {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Calling",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(120.dp)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "جاري الاتصال...",
                            color = Color(0xFFE2E2E6),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
fun ContactGridCard(
    contact: Contact,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(40.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2D35)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.0f)
            .border(2.dp, Color(0x0DFFFFFF), RoundedCornerShape(40.dp))
            .clip(RoundedCornerShape(40.dp))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (contact.imagePath != null) {
                val imageFile = File(contact.imagePath)
                if (imageFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Contact photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        PlaceholderContactImage()
                    }
                } else {
                    PlaceholderContactImage()
                }
            } else {
                PlaceholderContactImage()
            }

            // Beautiful gradient overlay to match from-blue-400/20 styling in HTML
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0x2660A5FA),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun PlaceholderContactImage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF44474E)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Default Contact",
            tint = Color(0xFFD3E3FD),
            modifier = Modifier.size(64.dp)
        )
    }
}

@Composable
fun ContactCircularImage(
    imagePath: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(8.dp, Color(0xFF3B82F6).copy(alpha = 0.2f), CircleShape)
            .background(Color(0xFF44474E)),
        contentAlignment = Alignment.Center
    ) {
        if (imagePath != null) {
            val imageFile = File(imagePath)
            if (imageFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Contact Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFD3E3FD), modifier = Modifier.size(64.dp))
                }
            } else {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFD3E3FD), modifier = Modifier.size(64.dp))
            }
        } else {
            Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFD3E3FD), modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
fun OverlayContainer(
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFC111318))
            .clickable(enabled = false) {}, // Prevent back-tap propagation
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            content()
        }
    }
}
