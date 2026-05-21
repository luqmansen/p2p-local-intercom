package com.example.ui

import android.Manifest
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

// --- High Density Color Palette (MeshVoice Pro Theme) ---
val SlateBackground = Color(0xFFFDFBFF)      // Whole container background (off-white blue shimmer)
val SlateSurface = Color(0xFFFFFFFF)         // Pure white cards / paper surfaces
val SlateSurfaceVariant = Color(0xFFF1F0F4)  // Light gray containers / nav backgrounds
val TacticalGreen = Color(0xFF0061A4)        // Rich primary M3 brand blue
val TacticalAmber = Color(0xFF0061A4)        // Standard active blue for high density
val TacticalMuted = Color(0xFF74777F)        // Classy slate-gray muted text
val TacticalCrimson = Color(0xFFBA1A1A)      // Red warning / actions from design
val TacticalBorder = Color(0xFFC4C6CF)       // Light outlined borders from design
val CombatWhite = Color(0xFF1A1C1E)          // Deep charcoal text for readability

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VoxApp(viewModel: VoxViewModel) {
    // Standard tactical deep canvas wrapper
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SlateBackground
    ) {
        val micPermissionState = rememberPermissionState(permission = Manifest.permission.RECORD_AUDIO)

        if (micPermissionState.status.isGranted) {
            VoxMainContent(viewModel)
        } else {
            VoxPermissionScreen(
                onRequestAction = { micPermissionState.launchPermissionRequest() }
            )
        }
    }
}

@Composable
fun VoxPermissionScreen(onRequestAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(SlateSurface, CircleShape)
                .border(2.dp, TacticalMuted, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Mic Permission Needed",
                tint = TacticalAmber,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Microphone Access Required",
            color = CombatWhite,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "VoxLnk operates entirely as a local voice-activated walkie-talkie server and terminal. To capture sound for group chat streaming, we require hardware audio access.",
            color = TacticalMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestAction,
            colors = ButtonDefaults.buttonColors(containerColor = TacticalGreen),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.testTag("request_permission_button")
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("GRANT MIC PERMISSION", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun VoxMainContent(viewModel: VoxViewModel) {
    val mode by viewModel.appMode.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            slideInVertically { height -> height } + fadeIn() togetherWith
                    slideOutVertically { height -> -height } + fadeOut()
        },
        label = "mode_switch"
    ) { currentMode ->
        when (currentMode) {
            AppMode.IDLE -> VoxIdleSetupScreen(viewModel)
            AppMode.SERVER, AppMode.CLIENT -> VoxActiveSessionScreen(viewModel)
        }
    }
}

@Composable
fun VoxIdleSetupScreen(viewModel: VoxViewModel) {
    val nick by viewModel.nickname.collectAsStateWithLifecycle()
    val targetIp by viewModel.targetServerIp.collectAsStateWithLifecycle()
    val targetPort by viewModel.serverLaunchPort.collectAsStateWithLifecycle()
    val localIp by viewModel.localIpAddress.collectAsStateWithLifecycle()

    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanTimeRemaining by viewModel.scanTimeRemaining.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    val discoveredServersByUdp by viewModel.discoveredServers.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { focusManager.clearFocus() } // Tap outside clears keyboard
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Title with MeshVoice Pro styling and pulsating IP pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LOCAL HOTSPOT SERVER",
                        color = TacticalGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "MeshVoice Pro",
                        color = CombatWhite,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                    )
                }

                // High Density animated IP badge
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_alpha"
                )

                Row(
                    modifier = Modifier
                        .background(Color(0xFFD1E4FF), RoundedCornerShape(100.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(TacticalGreen.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (localIp == "Unknown") "Offline" else localIp,
                        color = TacticalGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }

            // Central Setup Terminal Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                border = BorderStroke(1.dp, TacticalBorder),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "AUDIO TERMINAL CONFIG",
                        color = TacticalGreen,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Nickname Input Box
                    OutlinedTextField(
                        value = nick,
                        onValueChange = { viewModel.nickname.value = it },
                        label = { Text("Call Sign (Nickname)", color = TacticalMuted, fontFamily = FontFamily.SansSerif) },
                        textStyle = LocalTextStyle.current.copy(color = CombatWhite, fontFamily = FontFamily.SansSerif),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TacticalGreen,
                            unfocusedBorderColor = TacticalBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("id_nickname_input")
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Local Network IP Status Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateSurfaceVariant, RoundedCornerShape(16.dp))
                            .border(1.dp, TacticalBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkWifi,
                            contentDescription = "Local WiFi Status",
                            tint = if (localIp == "Unknown" || localIp == "Disconnected") TacticalMuted else TacticalGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "LOCAL DEVICE IP",
                                color = TacticalMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = localIp,
                                color = CombatWhite,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "SELECT TRANSCEIVER STATE:",
                        color = TacticalMuted,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Server mode init
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.startServerMode()
                            },
                            modifier = Modifier
                                .weight(1.8f)
                                .height(56.dp)
                                .testTag("start_server_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = TacticalGreen),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("HOST SERVER Mode", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                                Text("Be the network hub", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontFamily = FontFamily.SansSerif)
                            }
                        }

                        // Client mode init
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.startClientMode()
                            },
                            modifier = Modifier
                                .weight(1.8f)
                                .height(56.dp)
                                .testTag("connect_client_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = SlateSurfaceVariant),
                            border = BorderStroke(1.dp, TacticalGreen),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("JOIN CLIENT Mode", color = TacticalGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                                Text("Connect to Host", color = TacticalMuted, fontSize = 10.sp, fontFamily = FontFamily.SansSerif)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Client Connection configurations
                    AnimatedVisibility(visible = true) {
                        Column {
                            Text(
                                text = "DESTINATION HOST LINK (If Joining):",
                                color = TacticalMuted,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                              ) {
                                OutlinedTextField(
                                    value = targetIp,
                                    onValueChange = { viewModel.targetServerIp.value = it },
                                    label = { Text("Server IP Address", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) },
                                    textStyle = LocalTextStyle.current.copy(color = CombatWhite, fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = TacticalGreen,
                                        unfocusedBorderColor = TacticalBorder
                                    ),
                                    modifier = Modifier
                                        .weight(1.8f)
                                        .testTag("target_ip_input")
                                )

                                OutlinedTextField(
                                    value = targetPort.toString(),
                                    onValueChange = { value ->
                                        value.toIntOrNull()?.let { viewModel.serverLaunchPort.value = it }
                                    },
                                    label = { Text("Port", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) },
                                    textStyle = LocalTextStyle.current.copy(color = CombatWhite, fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = TacticalGreen,
                                        unfocusedBorderColor = TacticalBorder
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("target_port_input")
                                )
                            }
                        }
                    }

                    // --- Auto Discovered Servers Section ---
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { viewModel.toggleDiscoveryScan() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScanning) TacticalCrimson else TacticalGreen
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("toggle_scan_btn")
                    ) {
                        Icon(
                            imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.Search,
                            contentDescription = "Scan Toggle",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isScanning) "STOP DISCOVERY SCAN (${scanTimeRemaining}s)" else "SCAN FOR LOCAL CHANNELS",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "DETECTED CO-PEER CHANNELS:",
                        color = TacticalGreen,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (discoveredServersByUdp.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            discoveredServersByUdp.forEach { server ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SlateSurfaceVariant),
                                    border = BorderStroke(1.dp, TacticalGreen.copy(alpha = 0.4f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.targetServerIp.value = server.ip
                                            viewModel.serverLaunchPort.value = server.port
                                            viewModel.startClientMode()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Radio,
                                                contentDescription = "Mesh Channel",
                                                tint = TacticalGreen,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = server.hostname,
                                                    color = CombatWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.SansSerif
                                                )
                                                Text(
                                                    text = "${server.ip}:${server.port}",
                                                    color = TacticalMuted,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.SansSerif
                                                )
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "JOIN",
                                                color = TacticalGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.SansSerif
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.ArrowForward,
                                                contentDescription = null,
                                                tint = TacticalGreen,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SlateSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isScanning) Icons.Default.Sync else Icons.Default.Search,
                                contentDescription = "Scanning status",
                                tint = TacticalMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isScanning) "Listening for active hosts on port 50006..." else "No scans active. Tap SCAN above to search.",
                                color = TacticalMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                }
            }

            // Quick network hint
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TacticalMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "No Internet Required. Relying purely on Hotspot Wi-Fi",
                    color = TacticalMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun VoxActiveSessionScreen(viewModel: VoxViewModel) {
    val mode by viewModel.appMode.collectAsStateWithLifecycle()
    val isConnectedState by viewModel.isConnected.collectAsStateWithLifecycle()
    val localIp by viewModel.localIpAddress.collectAsStateWithLifecycle()
    val targetIp by viewModel.targetServerIp.collectAsStateWithLifecycle()
    val targetPort by viewModel.serverLaunchPort.collectAsStateWithLifecycle()

    val amplitude by viewModel.realtimeAmplitude.collectAsStateWithLifecycle()
    val isTransmittingState by viewModel.isTransmitting.collectAsStateWithLifecycle()
    val activeSpeaker by viewModel.activeSpeakerIdName.collectAsStateWithLifecycle()

    val peersList by viewModel.peersState.collectAsStateWithLifecycle()
    val logsList by viewModel.statusLog.collectAsStateWithLifecycle()

    // Config Panel Collapse state
    var isConfigExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = SlateBackground,
        bottomBar = {
            // High Density Design Footer
            Surface(
                color = SlateBackground,
                border = BorderStroke(1.dp, TacticalBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Refresh button matching the design HTML's footer asset button layout
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(SlateSurfaceVariant, RoundedCornerShape(16.dp))
                            .clickable { viewModel.detectLocalIp() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh connection status",
                            tint = CombatWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Deep primary action button with warning dot
                    val infiniteTransition = rememberInfiniteTransition(label = "btn_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_alpha"
                    )

                    Button(
                        onClick = { viewModel.stopAll() },
                        colors = ButtonDefaults.buttonColors(containerColor = TacticalCrimson),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("disconnect_session_btn")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.White.copy(alpha = pulseAlpha), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (mode == AppMode.SERVER) "STOP SERVER" else "LEAVE CHANNEL",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Screen Title indicating Hub vs Node
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(TacticalGreen, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (mode == AppMode.SERVER) "TRANSCEIVER STATE: HOST" else "TRANSCEIVER STATE: CLIENT",
                                color = CombatWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (mode == AppMode.SERVER) "Host IP: $localIp:$targetPort" else "Connected: $targetIp:$targetPort",
                            color = TacticalMuted,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }

                    IconButton(
                        onClick = { viewModel.detectLocalIp() },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = TacticalGreen)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh connection stats")
                    }
                }
            }

            // Big tactical Walkie-Talkie Button
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Circular glowing Canvas
                    CircularAudioVisualizer(
                        amplitude = amplitude,
                        isTransmitting = isTransmittingState,
                        isReceiving = activeSpeaker != null
                    )

                    // Core Button
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .background(SlateSurface, CircleShape)
                            .border(
                                width = 3.dp,
                                color = when {
                                    isTransmittingState -> TacticalGreen
                                    activeSpeaker != null -> TacticalAmber
                                    else -> TacticalBorder
                                },
                                shape = CircleShape
                            )
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    isTransmittingState -> Icons.Default.Mic
                                    activeSpeaker != null -> Icons.Default.VolumeUp
                                    else -> Icons.Default.MicNone
                                },
                                contentDescription = null,
                                tint = when {
                                    isTransmittingState -> TacticalGreen
                                    activeSpeaker != null -> TacticalAmber
                                    else -> CombatWhite
                                },
                                modifier = Modifier.size(36.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = when {
                                    isTransmittingState -> "TALKING"
                                    activeSpeaker != null -> "HEARING"
                                    else -> "STANDBY"
                                },
                                color = when {
                                    isTransmittingState -> TacticalGreen
                                    activeSpeaker != null -> TacticalAmber
                                    else -> TacticalMuted
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                letterSpacing = 1.5.sp
                            )

                            // Numerical peak amplitude indicator
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(amplitude * 100).toInt()}%",
                                color = TacticalMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Speaking State indicator
            item {
                AnimatedVisibility(
                    visible = activeSpeaker != null || isTransmittingState,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateSurface, RoundedCornerShape(16.dp))
                            .border(1.dp, TacticalBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (isTransmittingState) TacticalGreen else TacticalAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when {
                                    isTransmittingState -> "You are speaking... (VOX triggered)"
                                    activeSpeaker != null -> "🎙️ ${activeSpeaker?.second} is speaking..."
                                    else -> ""
                                },
                                color = if (isTransmittingState) TacticalGreen else TacticalAmber,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                }
            }

            // Server-specific broadcast control card
            if (mode == AppMode.SERVER) {
                item {
                    val isBroadcasting by viewModel.isBroadcasting.collectAsStateWithLifecycle()
                    val broadcastTimeRemaining by viewModel.broadcastTimeRemaining.collectAsStateWithLifecycle()

                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        border = BorderStroke(1.dp, TacticalGreen.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.SettingsInputAntenna,
                                        contentDescription = "Beacon",
                                        tint = if (isBroadcasting) TacticalAmber else TacticalMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "DISCOVERY BEACON",
                                        color = CombatWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (isBroadcasting) Color(0xFFE6F4EA) else Color(0xFFF1F3F4),
                                            shape = RoundedCornerShape(100.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isBroadcasting) "LIVE BROADCAST (${broadcastTimeRemaining}s)" else "MUTED",
                                        color = if (isBroadcasting) Color(0xFF137333) else TacticalMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }
                            }

                            Text(
                                text = "Enables automatic node discovery inside your coverage cell. Broadcasting is battery efficient and automatically sleeps.",
                                fontSize = 11.sp,
                                color = TacticalMuted,
                                fontFamily = FontFamily.SansSerif,
                                lineHeight = 16.sp
                            )

                            Button(
                                onClick = { viewModel.toggleBroadcast() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isBroadcasting) TacticalCrimson else TacticalGreen
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (isBroadcasting) Icons.Default.LeakAdd else Icons.Default.Sensors,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isBroadcasting) "STOP ADVERTISING DISCOVERY" else "EMIT DISCOVERY SIGNALS (1 Min)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }
                    }
                }
            }

            // Accordion Title for Configuration Panel
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isConfigExpanded = !isConfigExpanded }
                        .background(SlateSurface, RoundedCornerShape(16.dp))
                        .border(1.dp, TacticalBorder, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = TacticalGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "VOX & AUDIO TRANSMIT CONFIGS",
                            color = CombatWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Icon(
                        imageVector = if (isConfigExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle hardware options",
                        tint = CombatWhite
                    )
                }
            }

            // Collapsible Content
            item {
                AnimatedVisibility(
                    visible = isConfigExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    VoxSettingsSection(viewModel)
                }
            }

            // Peer List Section
            item {
                Text(
                    text = "WALKIE PEERS (${peersList.size}):",
                    color = TacticalMuted,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (peersList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateSurface, RoundedCornerShape(16.dp))
                            .border(1.dp, TacticalBorder, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Group, contentDescription = null, tint = TacticalMuted, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No peers detected yet", color = TacticalMuted, fontSize = 12.sp, fontFamily = FontFamily.SansSerif)
                        }
                    }
                }
            } else {
                items(peersList, key = { it.id }) { peer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateSurface, RoundedCornerShape(16.dp))
                            .border(
                                width = 1.dp,
                                color = if (peer.isSpeaking) TacticalGreen else TacticalBorder,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (peer.isSelf) Icons.Default.ContactPage else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (peer.isSpeaking) TacticalGreen else TacticalMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = peer.nickname + if (peer.isSelf) " (You)" else "",
                                color = if (peer.isSpeaking) TacticalGreen else CombatWhite,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = if (peer.isSpeaking) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }

                        // Speaking status animation indicator
                        if (peer.isSpeaking) {
                            Row(
                                modifier = Modifier.padding(end = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                repeat(3) { index ->
                                    val heightAnimation = remember { Animatable(4f) }
                                    LaunchedEffect(Unit) {
                                        heightAnimation.animateTo(
                                            targetValue = 14f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(durationMillis = 300 + (index * 100), easing = LinearEasing),
                                                repeatMode = RepeatMode.Reverse
                                            )
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(heightAnimation.value.dp)
                                            .background(TacticalGreen)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "STANDBY",
                                color = TacticalMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Radio Log Card
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CHANNELS SYSTEM LOGS:",
                        color = TacticalMuted,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "CLEAR LOG",
                        color = TacticalCrimson,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier
                            .clickable { viewModel.clearLogs() }
                            .padding(4.dp)
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, TacticalBorder),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .padding(top = 6.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logsList) { logLine ->
                            Text(
                                text = "> $logLine",
                                color = TacticalGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TacticalVUMeter(
    amplitude: Float,
    threshold: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "REAL-TIME MIC INPUT LEVEL",
                color = TacticalMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (amplitude >= threshold) "ACTIVE (TRANSMITTING)" else "STANDBY (SILENT)",
                color = if (amplitude >= threshold) TacticalGreen else TacticalMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(SlateBackground, RoundedCornerShape(4.dp))
                .border(BorderStroke(1.dp, TacticalBorder), RoundedCornerShape(4.dp))
        ) {
            // Draw current amplitude level (normalizing it to make it visible up to 0.3)
            val scaledAmplitude = (amplitude / 0.30f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = scaledAmplitude)
                    .fillMaxHeight()
                    .background(
                        color = if (amplitude >= threshold) TacticalGreen else TacticalGreen.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(3.dp)
                    )
            )

            // Draw threshold tick divider
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val xPos = size.width * (threshold / 0.30f).coerceIn(0f, 1f)
                drawLine(
                    color = TacticalCrimson,
                    start = androidx.compose.ui.geometry.Offset(xPos, 0f),
                    end = androidx.compose.ui.geometry.Offset(xPos, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

@Composable
fun VoxSettingsSection(viewModel: VoxViewModel) {
    val isVoxOn by viewModel.voxEnabled.collectAsStateWithLifecycle()
    val threshold by viewModel.voxThreshold.collectAsStateWithLifecycle()
    val hangover by viewModel.voxHangoverMs.collectAsStateWithLifecycle()
    val rawAmplitude by viewModel.realtimeAmplitude.collectAsStateWithLifecycle()

    val micBoost by viewModel.micBoost.collectAsStateWithLifecycle()
    val playbackBoost by viewModel.playbackBoost.collectAsStateWithLifecycle()
    val isSpeakerphoneOn by viewModel.isSpeakerphoneOn.collectAsStateWithLifecycle()

    val ns by viewModel.noiseSuppressorEnabled.collectAsStateWithLifecycle()
    val ec by viewModel.echoCancelerEnabled.collectAsStateWithLifecycle()
    val agc by viewModel.agcEnabled.collectAsStateWithLifecycle()

    val hpfEnabled by viewModel.hpfEnabled.collectAsStateWithLifecycle()
    val hpfCutoff by viewModel.hpfCutoff.collectAsStateWithLifecycle()
    val limiterEnabled by viewModel.limiterEnabled.collectAsStateWithLifecycle()
    val limiterThreshold by viewModel.limiterThreshold.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlateSurface, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .border(
                BorderStroke(1.dp, TacticalBorder),
                RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Switch for VOX
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VOICE-ACTIVATED MODE (VOX)",
                    color = CombatWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "If disabled, streams constantly",
                    color = TacticalMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Switch(
                checked = isVoxOn,
                onCheckedChange = { viewModel.toggleVox(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TacticalGreen,
                    checkedTrackColor = TacticalGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = TacticalMuted,
                    uncheckedTrackColor = SlateBackground
                )
            )
        }

        // --- Hardware routing controls ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AUDIO OUTPUT OUTLET",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Route voice packets via loudspeaker or receiver earpiece",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Button(
                onClick = { viewModel.toggleSpeakerphone() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpeakerphoneOn) TacticalGreen else SlateBackground
                ),
                border = BorderStroke(1.dp, if (isSpeakerphoneOn) TacticalGreen else TacticalBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = if (isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = "Speakerphone routing",
                    tint = if (isSpeakerphoneOn) CombatWhite else TacticalMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isSpeakerphoneOn) "LOUDSPEAKER" else "EARPIECE",
                    fontSize = 10.sp,
                    color = if (isSpeakerphoneOn) CombatWhite else TacticalMuted,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Divider(color = TacticalBorder)

        // --- Software Signal Gain Boosters ---
        Text(
            text = "DIGITAL SOFTWARE AMPLIFICATION BOOSTERS",
            color = TacticalAmber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )

        // Mic Signal Boost Slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MICROPHONE RECORD GAIN (MIC BOOST)",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = String.format("%.1fx", micBoost),
                    color = TacticalGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Slider(
                value = micBoost,
                onValueChange = { viewModel.setMicBoost(it) },
                valueRange = 1.0f..5.0f,
                colors = SliderDefaults.colors(
                    thumbColor = TacticalAmber,
                    activeTrackColor = TacticalAmber,
                    inactiveTrackColor = TacticalBorder
                )
            )
            Text(
                text = "Multiplies microphone input amplitude digitally. Use higher boosts to speak further away from mic.",
                color = TacticalMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.SansSerif
            )
        }

        // Speaker Playback Volume Boost Slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "INCOMING PLAYBACK MASTER BOOST",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = String.format("%.1fx", playbackBoost),
                    color = TacticalGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Slider(
                value = playbackBoost,
                onValueChange = { viewModel.setPlaybackBoost(it) },
                valueRange = 1.0f..5.0f,
                colors = SliderDefaults.colors(
                    thumbColor = TacticalAmber,
                    activeTrackColor = TacticalAmber,
                    inactiveTrackColor = TacticalBorder
                )
            )
            Text(
                text = "Amplifies incoming PCM streams directly in software. Overrides hardware volume upper limits.",
                color = TacticalMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.SansSerif
            )
        }

        Divider(color = TacticalBorder)

        if (isVoxOn) {
            // VOX Threshold setting
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VOX THRESHOLD (SENSITIVITY)",
                        color = CombatWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = String.format("%.1f%%", threshold * 100),
                        color = TacticalGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                TacticalVUMeter(amplitude = rawAmplitude, threshold = threshold)
                Spacer(modifier = Modifier.height(6.dp))

                // Slider
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Slider(
                        value = threshold,
                        onValueChange = { viewModel.setVoxThreshold(it) },
                        valueRange = 0.005f..0.30f,
                        colors = SliderDefaults.colors(
                            thumbColor = TacticalGreen,
                            activeTrackColor = TacticalGreen,
                            inactiveTrackColor = TacticalBorder
                        )
                    )
                }

                Text(
                    text = "Move slider above ambient outdoor noise (silent level) to prevent false triggering.",
                    color = TacticalMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }

            // VOX Hangover delay setting
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SPEECH HANGOVER RETENTION",
                        color = CombatWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = "${hangover} ms",
                        color = TacticalGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
                Slider(
                    value = hangover.toFloat(),
                    onValueChange = { viewModel.setVoxHangover(it.toLong()) },
                    valueRange = 200f..2000f,
                    colors = SliderDefaults.colors(
                        thumbColor = TacticalGreen,
                        activeTrackColor = TacticalGreen,
                        inactiveTrackColor = TacticalBorder
                    )
                )
                Text(
                    text = "Keeps connection open briefly after you finish talking to prevent word truncation.",
                    color = TacticalMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }

        Divider(color = TacticalBorder)

        // Outdoor Hardware Accessories (Acoustic effects)
        Text(
            text = "HARDWARE INTEGRATION (OUTDOOR NOISE PROTECTION)",
            color = TacticalMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )

        // Noise suppression
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "HARDWARE NOISE SUPPRESSION",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Filters steady background wind & engine sounds",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Switch(
                checked = ns,
                onCheckedChange = { viewModel.toggleNoiseSuppressor(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TacticalGreen,
                    checkedTrackColor = TacticalGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = TacticalMuted,
                    uncheckedTrackColor = SlateBackground
                )
            )
        }

        // Acoustic Echo canceler
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ACOUSTIC ECHO CANCELLER",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Avoids feedback loops if speaker is loud",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Switch(
                checked = ec,
                onCheckedChange = { viewModel.toggleEchoCanceler(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TacticalGreen,
                    checkedTrackColor = TacticalGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = TacticalMuted,
                    uncheckedTrackColor = SlateBackground
                )
            )
        }

        // Automatic gain control
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AUTOMATIC GAIN CONTROL (AGC)",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Boosts distant whispering or low-level talks",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Switch(
                checked = agc,
                onCheckedChange = { viewModel.toggleAgc(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TacticalGreen,
                    checkedTrackColor = TacticalGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = TacticalMuted,
                    uncheckedTrackColor = SlateBackground
                )
            )
        }

        Divider(color = TacticalBorder)

        // Software-Side DSP Signal Conditioning
        Text(
            text = "TACTICAL SOFTWARE DSP CONDITIONING",
            color = TacticalAmber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )

        // High-Pass Filter Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "HIGH-PASS FILTER (HPF)",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Cuts low-frequency wind rumbling and vehicle engine hums",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Switch(
                checked = hpfEnabled,
                onCheckedChange = { viewModel.toggleHpf(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TacticalGreen,
                    checkedTrackColor = TacticalGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = TacticalMuted,
                    uncheckedTrackColor = SlateBackground
                )
            )
        }

        // HPF Cutoff Frequency Slider
        if (hpfEnabled) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HPF CUTOFF FREQUENCY",
                        color = CombatWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = "${hpfCutoff.toInt()} Hz",
                        color = TacticalGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
                Slider(
                    value = hpfCutoff,
                    onValueChange = { viewModel.setHpfCutoff(it) },
                    valueRange = 60f..300f,
                    colors = SliderDefaults.colors(
                        thumbColor = TacticalGreen,
                        activeTrackColor = TacticalGreen,
                        inactiveTrackColor = TacticalBorder
                    )
                )
                Text(
                    text = "Frequencies below this cutoff value are attenuated at 6 dB/octave.",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Soft Limiter Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DYNAMIC SOFT LIMITER",
                    color = CombatWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Gradually compresses peaks above threshold to prevent digital clipping",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
            Switch(
                checked = limiterEnabled,
                onCheckedChange = { viewModel.toggleLimiter(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TacticalGreen,
                    checkedTrackColor = TacticalGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor = TacticalMuted,
                    uncheckedTrackColor = SlateBackground
                )
            )
        }

        // Soft Limiter Threshold Slider
        if (limiterEnabled) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIMITER THRESHOLD (COMPRESSION RATIO)",
                        color = CombatWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = String.format("%.0f%%", limiterThreshold * 100),
                        color = TacticalGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
                Slider(
                    value = limiterThreshold,
                    onValueChange = { viewModel.setLimiterThreshold(it) },
                    valueRange = 0.5f..0.99f,
                    colors = SliderDefaults.colors(
                        thumbColor = TacticalGreen,
                        activeTrackColor = TacticalGreen,
                        inactiveTrackColor = TacticalBorder
                    )
                )
                Text(
                    text = "Signals exceeding this limit are softly saturated. Low values create warmer, denser talk; high values retain dynamic range.",
                    color = TacticalMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun CircularAudioVisualizer(
    amplitude: Float,
    isTransmitting: Boolean,
    isReceiving: Boolean
) {
    // Generate organic breathing pulse
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radius_breathing"
    )

    // Select color depending on current active state
    val coreColor = when {
        isTransmitting -> TacticalGreen
        isReceiving -> TacticalAmber
        else -> TacticalMuted.copy(alpha = 0.4f)
    }

    // Amplify ripple using mic input amplitude
    val currentVisFactor = 1.0f + amplitude * 1.6f

    Canvas(
        modifier = Modifier.size(230.dp)
    ) {
        val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val baseRadius = 85.dp.toPx()

        if (isTransmitting || isReceiving) {
            // Draw beautiful concentric expanding ripples
            drawCircle(
                color = coreColor.copy(alpha = 0.12f),
                radius = baseRadius * pulseScale * currentVisFactor,
                center = centerOffset
            )

            drawCircle(
                color = coreColor.copy(alpha = 0.25f),
                radius = (baseRadius + 22.dp.toPx()) * (1f + amplitude * 0.8f),
                center = centerOffset,
                style = Stroke(width = 2.dp.toPx())
            )

            drawCircle(
                color = coreColor.copy(alpha = 0.06f),
                radius = (baseRadius + 44.dp.toPx()) * pulseScale * currentVisFactor,
                center = centerOffset
            )
        } else {
            // Idle state: Draw very peaceful subtle outline indicator
            drawCircle(
                color = TacticalBorder.copy(alpha = 0.5f),
                radius = baseRadius * pulseScale,
                center = centerOffset,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}
