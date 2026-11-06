package com.reddysai.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.reddysai.app.agent.AgentAction
import com.reddysai.app.agent.AgentExecutor
import com.reddysai.app.brain.GeminiBrain
import com.reddysai.app.data.CommandHistoryStore
import com.reddysai.app.data.CommandLog
import com.reddysai.app.data.ReddysAISettings
import com.reddysai.app.data.SupabaseClient
import com.reddysai.app.service.OverlayService
import com.reddysai.app.ui.theme.ReddysAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ── Design tokens — ChatGPT-inspired matte dark with futuristic teal ─────
private val BG          = Color(0xFF090A0F)
private val Panel       = Color(0xFF13151D)
private val Panel2      = Color(0xFF1B1E2B)
private val PanelBorder = Color(0xFF262A3B)
private val Teal        = Color(0xFF10A37F)
private val TealLt      = Color(0xFF19C37D)
private val CyanGlow    = Color(0xFF00E5FF)
private val TextHi      = Color(0xFFF3F4F6)
private val TextMed     = Color(0xFFC5C8D4)
private val TextLo      = Color(0xFF7E849B)
private val IconGrey    = Color(0xFF565C73)
private val ErrorRed    = Color(0xFFEF4444)

class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionGranted.value = Settings.canDrawOverlays(this)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        micPermissionGranted.value = isGranted
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionGranted.value = isGranted
    }

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        micPermissionGranted.value = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted.value = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(Exception::class.java)
            gmailConnected.value = account != null
        } catch (_: Exception) {
            gmailConnected.value = false
        }
    }

    private val overlayPermissionGranted = mutableStateOf(false)
    private val accessibilityEnabled     = mutableStateOf(false)
    private val micPermissionGranted     = mutableStateOf(false)
    private val cameraPermissionGranted  = mutableStateOf(false)
    private val gmailConnected           = mutableStateOf(false)
    private val overlayRunning           = mutableStateOf(false)

    private var localTts: TextToSpeech? = null
    private lateinit var directExecutor: AgentExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestAppRuntimePermissions()
        directExecutor = AgentExecutor(applicationContext)

        localTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                localTts?.language = Locale.US
            }
        }

        setContent {
            ReddysAITheme {
                updatePermissionStates()

                val settings = remember { ReddysAISettings.get(this@MainActivity) }
                val historyStore = remember { CommandHistoryStore.get(this@MainActivity) }
                val historyList by historyStore.historyFlow.collectAsState()

                DashboardScreen(
                    settings = settings,
                    historyList = historyList,
                    overlayGranted = overlayPermissionGranted.value,
                    accessibilityEnabled = accessibilityEnabled.value,
                    micGranted = micPermissionGranted.value,
                    cameraGranted = cameraPermissionGranted.value,
                    gmailConnected = gmailConnected.value,
                    overlayRunning = overlayRunning.value,
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestAccessibility = { openAccessibilitySettings() },
                    onRequestMic = { micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                    onRequestCamera = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                    onConnectGmail = { signInWithGoogle() },
                    onLaunchOverlay = { launchOverlay() },
                    onStopOverlay = { stopOverlay() },
                    onExecuteDirectAction = { action, commandLabel ->
                        lifecycleScope.launch {
                            val res = directExecutor.execute(action)
                            historyStore.logCommand(commandLabel, action.javaClass.simpleName, res)
                            Toast.makeText(this@MainActivity, res, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTestVoice = { pitch, rate ->
                        localTts?.setPitch(pitch)
                        localTts?.setSpeechRate(rate)
                        localTts?.speak("Hello! I am Reddy, your autonomous AI companion.", TextToSpeech.QUEUE_FLUSH, null, "test_voice")
                    },
                    onClearHistory = { historyStore.clearHistory() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    override fun onDestroy() {
        localTts?.shutdown()
        localTts = null
        super.onDestroy()
    }

    private fun updatePermissionStates() {
        overlayPermissionGranted.value = Settings.canDrawOverlays(this)
        accessibilityEnabled.value     = isAccessibilityServiceEnabled()
        micPermissionGranted.value     = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted.value  = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        gmailConnected.value           = GoogleSignIn.getLastSignedInAccount(this) != null
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun signInWithGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope("https://www.googleapis.com/auth/gmail.readonly"),
                Scope("https://www.googleapis.com/auth/gmail.send"),
                Scope("https://www.googleapis.com/auth/gmail.compose")
            ).build()
        signInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun requestAppRuntimePermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) runtimePermissionsLauncher.launch(needed.toTypedArray())
    }

    private fun launchOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        requestAppRuntimePermissions()
        startForegroundService(Intent(this, OverlayService::class.java))
        overlayRunning.value = true
        moveTaskToBack(true)
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        overlayRunning.value = false
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${com.reddysai.app.service.ReddyAccessibilityService::class.java.name}"
        return try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            enabled.split(':').any { it.equals(serviceName, ignoreCase = true) }
        } catch (_: Exception) { false }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Dashboard Screen — Complete Navigation & Management UI
// ═══════════════════════════════════════════════════════════════

@Composable
fun DashboardScreen(
    settings: ReddysAISettings,
    historyList: List<CommandLog>,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    cameraGranted: Boolean,
    gmailConnected: Boolean,
    overlayRunning: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestCamera: () -> Unit,
    onConnectGmail: () -> Unit,
    onLaunchOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onExecuteDirectAction: (AgentAction, String) -> Unit,
    onTestVoice: (Float, Float) -> Unit,
    onClearHistory: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        containerColor = BG,
        bottomBar = {
            NavigationBar(
                containerColor = Panel,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = TealLt,
                        indicatorColor = Teal,
                        unselectedIconColor = IconGrey,
                        unselectedTextColor = IconGrey
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "AI & Cloud") },
                    label = { Text("AI Brain", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = TealLt,
                        indicatorColor = Teal,
                        unselectedIconColor = IconGrey,
                        unselectedTextColor = IconGrey
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Mic, contentDescription = "Voice & Mic") },
                    label = { Text("Voice & Mic", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = TealLt,
                        indicatorColor = Teal,
                        unselectedIconColor = IconGrey,
                        unselectedTextColor = IconGrey
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Security, contentDescription = "Setup") },
                    label = { Text("Setup", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = TealLt,
                        indicatorColor = Teal,
                        unselectedIconColor = IconGrey,
                        unselectedTextColor = IconGrey
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeDashboardTab(
                    settings = settings,
                    historyList = historyList,
                    overlayGranted = overlayGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    micGranted = micGranted,
                    overlayRunning = overlayRunning,
                    onLaunchOverlay = onLaunchOverlay,
                    onStopOverlay = onStopOverlay,
                    onExecuteDirectAction = onExecuteDirectAction,
                    onClearHistory = onClearHistory
                )
                1 -> AiAndCloudTab(settings = settings)
                2 -> VoiceAndMicTab(settings = settings, onTestVoice = onTestVoice)
                3 -> SetupAndPermissionsTab(
                    settings = settings,
                    overlayGranted = overlayGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    micGranted = micGranted,
                    cameraGranted = cameraGranted,
                    gmailConnected = gmailConnected,
                    onRequestOverlay = onRequestOverlay,
                    onRequestAccessibility = onRequestAccessibility,
                    onRequestMic = onRequestMic,
                    onRequestCamera = onRequestCamera,
                    onConnectGmail = onConnectGmail
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 0: Home Dashboard
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HomeDashboardTab(
    settings: ReddysAISettings,
    historyList: List<CommandLog>,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    overlayRunning: Boolean,
    onLaunchOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onExecuteDirectAction: (AgentAction, String) -> Unit,
    onClearHistory: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ── Top Header Brand ─────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.reddy_logo),
                    contentDescription = "Reddy AI",
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Teal, CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Reddy's AI",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextHi
                    )
                    Text(
                        text = "Autonomous Companion",
                        fontSize = 12.sp,
                        color = TextLo
                    )
                }
            }

            // Live status badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (overlayRunning) Teal.copy(alpha = 0.15f) else Panel2,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (overlayRunning) Teal else PanelBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (overlayRunning) TealLt else IconGrey)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (overlayRunning) "ACTIVE" else "STANDBY",
                        color = if (overlayRunning) TealLt else TextLo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ── Main Launch Card ─────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (overlayRunning) "Floating Mascot Active" else "Ready to Launch",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextHi
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (overlayRunning)
                                "Tap mascot anytime on screen to speak"
                            else
                                "Tap below to summon floating companion",
                            fontSize = 12.sp,
                            color = TextLo
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = if (overlayRunning) onStopOverlay else onLaunchOverlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (overlayRunning) Color(0xFF3B1E2B) else Teal
                    )
                ) {
                    Icon(
                        imageVector = if (overlayRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (overlayRunning) "Stop Floating Companion" else "Launch Reddy Assistant",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Quick Action Launcher Grid ───────────────────────────────
        Text(
            text = "QUICK ACTIONS",
            color = IconGrey,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            QuickActionItem(
                icon = Icons.Default.CameraAlt,
                title = "Camera & Photo",
                subtitle = "Open & auto click",
                modifier = Modifier.weight(1f),
                onClick = { onExecuteDirectAction(AgentAction.TakePhoto(), "Open camera and click picture") }
            )
            Spacer(Modifier.width(10.dp))
            QuickActionItem(
                icon = Icons.Default.Fastfood,
                title = "Order Zomato",
                subtitle = "Find Biryani & cart",
                modifier = Modifier.weight(1f),
                onClick = {
                    onExecuteDirectAction(
                        AgentAction.AutomateAppTask("Zomato", "Order Biryani on Zomato"),
                        "Order Biryani on Zomato"
                    )
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            QuickActionItem(
                icon = Icons.Default.Screenshot,
                title = "Screenshot",
                subtitle = "Instant screen capture",
                modifier = Modifier.weight(1f),
                onClick = { onExecuteDirectAction(AgentAction.TakeScreenshot, "Take a screenshot") }
            )
            Spacer(Modifier.width(10.dp))
            QuickActionItem(
                icon = Icons.Default.Chat,
                title = "WhatsApp",
                subtitle = "Quick message chat",
                modifier = Modifier.weight(1f),
                onClick = { onExecuteDirectAction(AgentAction.SendWhatsApp(""), "Open WhatsApp") }
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            QuickActionItem(
                icon = Icons.Default.Search,
                title = "Web Search",
                subtitle = "Google search query",
                modifier = Modifier.weight(1f),
                onClick = { onExecuteDirectAction(AgentAction.SearchWeb("today top news"), "Search top news") }
            )
            Spacer(Modifier.width(10.dp))
            QuickActionItem(
                icon = Icons.Default.Timer,
                title = "Set Timer",
                subtitle = "1-minute countdown",
                modifier = Modifier.weight(1f),
                onClick = { onExecuteDirectAction(AgentAction.SetTimer(60, "Reddy Quick Timer"), "Set 1 minute timer") }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Recent Activity / History Feed ───────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECENT ACTIVITY",
                color = IconGrey,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            if (historyList.isNotEmpty()) {
                Text(
                    text = "Clear All",
                    color = TextLo,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onClearHistory() }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No commands executed yet.\nTry a quick action above or say \"Hey Reddy\"!",
                    color = TextLo,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                historyList.take(15).forEach { item ->
                    CommandHistoryRow(item)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel2),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = TealLt, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextLo, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CommandHistoryRow(item: CommandLog) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Teal.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = item.actionType,
                        color = TealLt,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = item.formattedTime,
                    color = IconGrey,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.command,
                color = TextHi,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (item.resultText.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.resultText,
                    color = TextLo,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 1: AI Brain & Cloud (Gemini & Supabase)
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AiAndCloudTab(settings: ReddysAISettings) {
    var apiKey by remember { mutableStateOf(settings.geminiApiKey) }
    var selectedModel by remember { mutableStateOf(settings.geminiModel) }
    var showApiKey by remember { mutableStateOf(false) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    var supabaseUrl by remember { mutableStateOf(settings.supabaseUrl) }
    var supabaseKey by remember { mutableStateOf(settings.supabaseKey) }
    var supabaseStatus by remember { mutableStateOf<String?>(null) }
    var isTestingSupabase by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "AI Brain Configuration",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextHi
        )
        Text(
            text = "Google Gemini 2.0 Flash powers natural language and visual reasoning",
            fontSize = 12.5.sp,
            color = TextLo
        )

        Spacer(Modifier.height(18.dp))

        // Gemini Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CyanGlow)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Google Gemini 2.0 Flash",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextHi
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle visibility",
                                tint = IconGrey
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal,
                        unfocusedBorderColor = PanelBorder,
                        focusedTextColor = TextHi,
                        unfocusedTextColor = TextHi
                    )
                )

                Spacer(Modifier.height(12.dp))

                // Model Selection Chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedModel == "gemini-2.0-flash",
                        onClick = {
                            selectedModel = "gemini-2.0-flash"
                            settings.geminiModel = "gemini-2.0-flash"
                        },
                        label = { Text("Gemini 2.0 Flash (Recommended)", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Teal,
                            selectedLabelColor = Color.White
                        )
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            settings.geminiApiKey = apiKey
                            testStatus = "API Key saved successfully! ✅"
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Key")
                    }

                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            testStatus = "Connecting to Gemini 2.0 Flash..."
                            coroutineScope.launch {
                                val result = GeminiBrain.testApiKey(apiKey, selectedModel)
                                isTesting = false
                                testStatus = if (result.isSuccess) {
                                    "Connected! Response: \"${result.getOrNull()}\" ✨"
                                } else {
                                    "Error: ${result.exceptionOrNull()?.message?.take(80)} ❌"
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test Key")
                    }
                }

                testStatus?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = msg,
                        color = if (msg.contains("Connected") || msg.contains("saved")) TealLt else ErrorRed,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Supabase Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = TealLt)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Supabase Storage & Sync",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextHi
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Syncs history, user profiles & multi-device memory",
                    fontSize = 11.5.sp,
                    color = TextLo
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = supabaseUrl,
                    onValueChange = { supabaseUrl = it },
                    label = { Text("Supabase Project URL") },
                    placeholder = { Text("https://abcdefgh.supabase.co") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal,
                        unfocusedBorderColor = PanelBorder,
                        focusedTextColor = TextHi,
                        unfocusedTextColor = TextHi
                    )
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = supabaseKey,
                    onValueChange = { supabaseKey = it },
                    label = { Text("Anon Public Key") },
                    placeholder = { Text("eyJhbGciOi...") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal,
                        unfocusedBorderColor = PanelBorder,
                        focusedTextColor = TextHi,
                        unfocusedTextColor = TextHi
                    )
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            settings.supabaseUrl = supabaseUrl
                            settings.supabaseKey = supabaseKey
                            supabaseStatus = "Supabase credentials saved! ✅"
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Supabase")
                    }

                    OutlinedButton(
                        onClick = {
                            isTestingSupabase = true
                            supabaseStatus = "Verifying Supabase connection..."
                            coroutineScope.launch {
                                val res = SupabaseClient.testConnection(supabaseUrl, supabaseKey)
                                isTestingSupabase = false
                                supabaseStatus = if (res.isSuccess) {
                                    res.getOrNull() ?: "Success!"
                                } else {
                                    "Error: ${res.exceptionOrNull()?.message?.take(80)}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test Connect")
                    }
                }

                supabaseStatus?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = msg,
                        color = if (msg.contains("Connected") || msg.contains("saved")) TealLt else ErrorRed,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 2: Voice & Mic Control
// ═══════════════════════════════════════════════════════════════

@Composable
private fun VoiceAndMicTab(
    settings: ReddysAISettings,
    onTestVoice: (Float, Float) -> Unit
) {
    var wakeMode by remember { mutableStateOf(settings.wakeWordMode) }
    var pitch by remember { mutableFloatStateOf(settings.ttsPitch) }
    var rate by remember { mutableFloatStateOf(settings.ttsRate) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Voice & Wake Word Control",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextHi
        )
        Text(
            text = "Choose your listening style and customize Reddy's voice tone",
            fontSize = 12.5.sp,
            color = TextLo
        )

        Spacer(Modifier.height(18.dp))

        // Mic Listening Mode
        Text(
            text = "LISTENING BEHAVIOR",
            color = IconGrey,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(10.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (wakeMode == ReddysAISettings.WAKE_MODE_CALM) Teal else PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    wakeMode = ReddysAISettings.WAKE_MODE_CALM
                    settings.wakeWordMode = ReddysAISettings.WAKE_MODE_CALM
                }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = wakeMode == ReddysAISettings.WAKE_MODE_CALM,
                    onClick = {
                        wakeMode = ReddysAISettings.WAKE_MODE_CALM
                        settings.wakeWordMode = ReddysAISettings.WAKE_MODE_CALM
                    },
                    colors = RadioButtonDefaults.colors(selectedColor = TealLt)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Calm Mode (Recommended)",
                        fontWeight = FontWeight.Bold,
                        color = TextHi,
                        fontSize = 14.5.sp
                    )
                    Text(
                        text = "Zero microphone on when idle. No green status bar dot, zero battery drain. Tap floating mascot or mic to talk.",
                        color = TextLo,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (wakeMode == ReddysAISettings.WAKE_MODE_ALWAYS) Teal else PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    wakeMode = ReddysAISettings.WAKE_MODE_ALWAYS
                    settings.wakeWordMode = ReddysAISettings.WAKE_MODE_ALWAYS
                }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = wakeMode == ReddysAISettings.WAKE_MODE_ALWAYS,
                    onClick = {
                        wakeMode = ReddysAISettings.WAKE_MODE_ALWAYS
                        settings.wakeWordMode = ReddysAISettings.WAKE_MODE_ALWAYS
                    },
                    colors = RadioButtonDefaults.colors(selectedColor = TealLt)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Always Listening (\"Hey Reddy\")",
                        fontWeight = FontWeight.Bold,
                        color = TextHi,
                        fontSize = 14.5.sp
                    )
                    Text(
                        text = "Listens continuously for \"Hey Reddy\". Mic indicator will show.",
                        color = TextLo,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Voice Tone Customization
        Text(
            text = "REDDY'S VOICE SYNTHESIS",
            color = IconGrey,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(10.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Pitch: ${String.format(Locale.US, "%.2f", pitch)}x",
                    color = TextHi,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = pitch,
                    onValueChange = {
                        pitch = it
                        settings.ttsPitch = it
                    },
                    valueRange = 0.8f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = TealLt, activeTrackColor = Teal)
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Speech Rate: ${String.format(Locale.US, "%.2f", rate)}x",
                    color = TextHi,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = rate,
                    onValueChange = {
                        rate = it
                        settings.ttsRate = it
                    },
                    valueRange = 0.8f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = TealLt, activeTrackColor = Teal)
                )

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = { onTestVoice(pitch, rate) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Test Reddy's Voice")
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TAB 3: Setup & Permissions
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SetupAndPermissionsTab(
    settings: ReddysAISettings,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    micGranted: Boolean,
    cameraGranted: Boolean,
    gmailConnected: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestCamera: () -> Unit,
    onConnectGmail: () -> Unit
) {
    val scrollState = rememberScrollState()
    var isSafetyGateEnabled by remember { mutableStateOf(settings.isSafetyGateEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Permissions & Security",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextHi
        )
        Text(
            text = "Essential Android privileges required for automation and floating UI",
            fontSize = 12.5.sp,
            color = TextLo
        )

        Spacer(Modifier.height(18.dp))

        PermissionCard(
            icon = Icons.Default.Layers,
            title = "Display Over Other Apps",
            description = if (overlayGranted) "Active — Reddy floats over apps" else "Required for floating mascot",
            granted = overlayGranted,
            onGrant = onRequestOverlay
        )
        Spacer(Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Default.Accessibility,
            title = "Accessibility Service",
            description = if (accessibilityEnabled) "Active — Screen reading & automation enabled" else "Required for Zomato ordering & Camera shutter tap",
            granted = accessibilityEnabled,
            onGrant = onRequestAccessibility
        )
        Spacer(Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Default.Mic,
            title = "Microphone Access",
            description = if (micGranted) "Active — Voice recognition enabled" else "Required for voice commands",
            granted = micGranted,
            onGrant = onRequestMic
        )
        Spacer(Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Default.CameraAlt,
            title = "Camera Permission",
            description = if (cameraGranted) "Active — Photo snapping enabled" else "Required to click pictures on command",
            granted = cameraGranted,
            onGrant = onRequestCamera
        )
        Spacer(Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Default.Email,
            title = "Google Account / Gmail",
            description = if (gmailConnected) "Connected — Email drafting & reading active" else "Optional: connect for email actions",
            granted = gmailConnected,
            onGrant = onConnectGmail,
            optional = true
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "SAFETY GATE",
            color = IconGrey,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(10.dp))

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Payment Safety Confirmation", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text("Always pause and request user confirmation before completing any payment or checkout.", color = TextLo, fontSize = 11.5.sp)
                }
                Switch(
                    checked = isSafetyGateEnabled,
                    onCheckedChange = {
                        isSafetyGateEnabled = it
                        settings.isSafetyGateEnabled = it
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Teal)
                )
            }
        }
    }
}

// ── Generic Permission Card ──────────────────────────────────────────────────

@Composable
fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    optional: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel2),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) TealLt else if (optional) IconGrey else TextLo,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = TextHi,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp
                )
                if (optional) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Panel2)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text("optional", color = IconGrey, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(description, color = TextLo, fontSize = 11.5.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.width(8.dp))

        if (granted) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = TealLt,
                modifier = Modifier.size(22.dp)
            )
        } else {
            TextButton(
                onClick = onGrant,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "Enable",
                    color = Teal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
