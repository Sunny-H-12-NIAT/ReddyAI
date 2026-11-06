package com.reddysai.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.reddysai.app.MainActivity
import com.reddysai.app.ReddysAIApplication
import com.reddysai.app.agent.AgentExecutor
import com.reddysai.app.agent.AgentBrain
import com.reddysai.app.agent.MockAgentBrain
import com.reddysai.app.brain.GeminiBrain
import com.reddysai.app.data.CommandHistoryStore
import com.reddysai.app.data.ReddysAISettings
import com.reddysai.app.ui.overlay.OverlayContent
import com.reddysai.app.ui.overlay.OverlayState
import com.reddysai.app.ui.theme.ReddysAITheme
import kotlinx.coroutines.*
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  OverlayService — Floating AI Companion Service
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Runs as a foreground service with microphone + overlay capabilities.
 * Manages:
 *   • Floating Jetpack Compose overlay window
 *   • Continuous "Hey Reddy" wake-word detection or Calm Tap-to-Talk mode
 *   • Active voice transcription & Text-to-Speech responses
 *   • Dynamic focus management (enables software keyboard when expanded)
 *   • Butter-smooth dragging and edge snapping
 *   • Agent loop: User Input → AgentBrain.decide() → AgentExecutor.execute()
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Agent components
    private lateinit var brain: AgentBrain
    private lateinit var executor: AgentExecutor

    // SpeechRecognizer & TTS
    private var wakeWordRecognizer: SpeechRecognizer? = null
    private var activeSpeechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false

    // Wake-word ("Hey Reddy") state
    private var isWakeWordListening = false
    private var wakeWordJob: Job? = null

    // Overlay state
    private val overlayState = mutableStateOf(OverlayState.Idle)
    private val responseText = mutableStateOf("")
    private val userInput = mutableStateOf("")

    // Drag accumulation to preserve floating point accuracy
    private var accumulatedDeltaX = 0f
    private var accumulatedDeltaY = 0f

    // Window layout params
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = 24
        y = 120
    }

    // ─────────────────────────────────────────────────────────────────
    //  Service Lifecycle
    // ─────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        brain = GeminiBrain(applicationContext)
        executor = AgentExecutor(applicationContext)

        val settings = ReddysAISettings.get(this)
        startForegroundCompat()
        createOverlay()
        initTts()
        if (settings.wakeWordMode == ReddysAISettings.WAKE_MODE_ALWAYS) {
            restartWakeWordDelayed(1500)
        }

        Log.i(TAG, "OverlayService initialized with Gemini Brain & Calm Voice Engine")
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, fgsTypes)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWakeWordListening()
        stopActiveListening()
        stopSpeaking()
        safeDestroyRecognizers()

        try {
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}

        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "OverlayService destroyed")
    }

    // ─────────────────────────────────────────────────────────────────
    //  Overlay Creation & Focus Management
    // ─────────────────────────────────────────────────────────────────

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot create overlay window — SYSTEM_ALERT_WINDOW not granted")
            return
        }

        try {
            val lifecycleOwner = OverlayLifecycleOwner()
            lifecycleOwner.start() // Sets Lifecycle.State.RESUMED for 60/120fps animations

            composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                setContent {
                    ReddysAITheme {
                        val currentState = overlayState.value

                        // Dynamically toggle window focus so soft keyboard appears when typing
                        LaunchedEffect(currentState) {
                            updateWindowFocusability(currentState)
                        }

                        OverlayContent(
                            state             = currentState,
                            responseText      = responseText.value,
                            userInput         = userInput.value,
                            onUserInputChange = { userInput.value = it },
                            onSubmit          = { handleUserInput(it) },
                            onDismiss         = {
                                stopActiveListening()
                                stopSpeaking()
                                overlayState.value = OverlayState.Idle
                                restartWakeWordDelayed(600)
                            },
                            onMicClick        = { handleMicClick() },
                            onMascotTap       = {
                                overlayState.value = OverlayState.Listening
                                startActiveListening()
                            },
                            onDragDelta       = { dx, dy -> updatePosition(dx, dy) },
                            onDragEnd         = { snapToEdge() }
                        )
                    }
                }
            }

            // Collapse overlay to Idle if user taps anywhere outside the floating card
            composeView?.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                    if (overlayState.value != OverlayState.Idle && overlayState.value != OverlayState.Executing) {
                        stopActiveListening()
                        stopSpeaking()
                        overlayState.value = OverlayState.Idle
                        restartWakeWordDelayed(600)
                    }
                    false
                } else {
                    false
                }
            }

            windowManager.addView(composeView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay view to WindowManager: ${e.message}", e)
        }
    }

    /**
     * Toggles FLAG_NOT_FOCUSABLE dynamically.
     * When Expanded / Listening / Result, window accepts touch focus and soft keyboard.
     * When Idle / Executing, window is NOT_FOCUSABLE so taps pass through to background apps.
     */
    private fun updateWindowFocusability(state: OverlayState) {
        val shouldBeFocusable = state != OverlayState.Idle && state != OverlayState.Executing
        val hasNotFocusable = (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0

        // Crucial: ALWAYS maintain FLAG_NOT_TOUCH_MODAL so touches outside the overlay
        // bubble/card never get blocked across the rest of the phone screen!
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        if (shouldBeFocusable && hasNotFocusable) {
            layoutParams.flags = (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or baseFlags
            layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            composeView?.let { windowManager.updateViewLayout(it, layoutParams) }
        } else if (!shouldBeFocusable && !hasNotFocusable) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or baseFlags
            composeView?.let { windowManager.updateViewLayout(it, layoutParams) }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Text-To-Speech (Reddy's Voice)
    // ─────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                val settings = ReddysAISettings.get(this@OverlayService)
                tts?.setPitch(settings.ttsPitch)
                tts?.setSpeechRate(settings.ttsRate)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        stopWakeWordListening()
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        serviceScope.launch {
                            delay(400)
                            val currentSettings = ReddysAISettings.get(this@OverlayService)
                            if (overlayState.value == OverlayState.Idle && currentSettings.wakeWordMode == ReddysAISettings.WAKE_MODE_ALWAYS) {
                                startWakeWordListening()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        val currentSettings = ReddysAISettings.get(this@OverlayService)
                        if (overlayState.value == OverlayState.Idle && currentSettings.wakeWordMode == ReddysAISettings.WAKE_MODE_ALWAYS) {
                            restartWakeWordDelayed(1200)
                        }
                    }
                })
            } else {
                Log.w(TAG, "TTS Initialization failed with status: $status")
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank() || !isTtsReady) return
        try {
            stopActiveListening()
            stopWakeWordListening()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reddy_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
        }
    }

    private fun stopSpeaking() {
        try {
            tts?.stop()
            isSpeaking = false
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────
    //  SpeechRecognizer Factory (On-device / System Service Priority)
    // ─────────────────────────────────────────────────────────────────

    private fun createSafeSpeechRecognizer(): SpeechRecognizer {
        // 1. Try on-device speech recognizer on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    Log.i(TAG, "Creating On-Device SpeechRecognizer")
                    return SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-device speech recognizer init skipped: ${e.message}")
            }
        }

        // 2. Try binding to system default voice recognition service
        try {
            val serviceSetting = Settings.Secure.getString(contentResolver, "voice_recognition_service")
            if (!serviceSetting.isNullOrBlank()) {
                val component = ComponentName.unflattenFromString(serviceSetting)
                if (component != null) {
                    Log.i(TAG, "Binding to system voice recognition service: $component")
                    return SpeechRecognizer.createSpeechRecognizer(this, component)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "System recognition service binding skipped: ${e.message}")
        }

        // 3. Fallback to default
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    // ─────────────────────────────────────────────────────────────────
    //  "Hey Reddy" Wake-Word Detection
    // ─────────────────────────────────────────────────────────────────

    private fun startWakeWordListening() {
        val settings = ReddysAISettings.get(this)
        if (settings.wakeWordMode != ReddysAISettings.WAKE_MODE_ALWAYS) return
        if (isSpeaking || overlayState.value != OverlayState.Idle) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        wakeWordJob?.cancel()
        wakeWordJob = serviceScope.launch {
            delay(350)
            if (overlayState.value != OverlayState.Idle || isSpeaking) return@launch

            try {
                safeResetWakeWordRecognizer()
                wakeWordRecognizer = createSafeSpeechRecognizer()

                isWakeWordListening = true
                wakeWordRecognizer?.setRecognitionListener(wakeWordListener)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                wakeWordRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.d(TAG, "Wake-word start paused: ${e.message}")
                isWakeWordListening = false
                safeResetWakeWordRecognizer()
                restartWakeWordDelayed(2500)
            }
        }
    }

    private fun stopWakeWordListening() {
        wakeWordJob?.cancel()
        wakeWordJob = null
        if (isWakeWordListening) {
            isWakeWordListening = false
            try {
                wakeWordRecognizer?.stopListening()
            } catch (_: Exception) {}
        }
    }

    private fun safeResetWakeWordRecognizer() {
        try {
            wakeWordRecognizer?.cancel()
            wakeWordRecognizer?.destroy()
        } catch (_: Exception) {}
        wakeWordRecognizer = null
    }

    private fun restartWakeWordDelayed(delayMs: Long = 1500) {
        val settings = ReddysAISettings.get(this)
        if (settings.wakeWordMode != ReddysAISettings.WAKE_MODE_ALWAYS) return
        if (overlayState.value == OverlayState.Idle && !isSpeaking) {
            wakeWordJob?.cancel()
            wakeWordJob = serviceScope.launch {
                delay(delayMs)
                startWakeWordListening()
            }
        }
    }

    private val wakeWordListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            isWakeWordListening = false
            safeResetWakeWordRecognizer()
            val settings = ReddysAISettings.get(this@OverlayService)
            if (overlayState.value == OverlayState.Idle && !isSpeaking && settings.wakeWordMode == ReddysAISettings.WAKE_MODE_ALWAYS) {
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT) 3000L else 2000L
                restartWakeWordDelayed(delay)
            }
        }

        override fun onResults(results: Bundle?) {
            isWakeWordListening = false
            safeResetWakeWordRecognizer()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            if (!checkWakeWordMatches(matches)) {
                val settings = ReddysAISettings.get(this@OverlayService)
                if (overlayState.value == OverlayState.Idle && !isSpeaking && settings.wakeWordMode == ReddysAISettings.WAKE_MODE_ALWAYS) {
                    restartWakeWordDelayed(1500)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            checkWakeWordMatches(matches)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun checkWakeWordMatches(matches: List<String>): Boolean {
        for (phrase in matches) {
            val lower = phrase.lowercase().trim()
            val wakePrefix = when {
                lower.contains("hey reddy")   -> "hey reddy"
                lower.contains("hey ready")   -> "hey ready"
                lower.contains("ok reddy")    -> "ok reddy"
                lower.contains("okay reddy")  -> "okay reddy"
                lower.contains("hi reddy")    -> "hi reddy"
                lower.contains("hi ready")    -> "hi ready"
                lower.contains("hello reddy") -> "hello reddy"
                lower.contains("hello ready") -> "hello ready"
                lower.contains("hey red")     -> "hey red"
                lower.startsWith("reddy")     -> "reddy"
                lower.startsWith("ready")     -> "ready"
                else -> null
            }

            if (wakePrefix != null) {
                Log.i(TAG, "Wake-word triggered with phrase: '$phrase'")
                stopWakeWordListening()
                triggerHapticFeedback()

                val command = lower.substringAfter(wakePrefix).trim().removePrefix(",").trim()
                if (command.isNotBlank() && command.length > 2) {
                    // Direct one-shot: "Hey Reddy, open YouTube"
                    handleUserInput(command)
                } else {
                    // Conversational wake: "Hey Reddy" -> opens listening dialog
                    overlayState.value = OverlayState.Listening
                    speak("I'm listening!")
                    serviceScope.launch {
                        delay(900)
                        startActiveListening()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun triggerHapticFeedback() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────
    //  Active Voice Input
    // ─────────────────────────────────────────────────────────────────

    private fun handleMicClick() {
        if (overlayState.value == OverlayState.Listening) {
            stopActiveListening()
        } else {
            stopWakeWordListening()
            startActiveListening()
        }
    }

    private fun startActiveListening() {
        stopWakeWordListening()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            responseText.value = "Microphone permission is required for voice commands"
            overlayState.value = OverlayState.Result
            return
        }

        try {
            safeResetActiveRecognizer()
            activeSpeechRecognizer = createSafeSpeechRecognizer()

            activeSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    overlayState.value = OverlayState.Listening
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "Active SpeechRecognizer error: $error")
                    safeResetActiveRecognizer()
                    if (overlayState.value == OverlayState.Listening) {
                        overlayState.value = OverlayState.Expanded
                    }
                }

                override fun onResults(results: Bundle?) {
                    safeResetActiveRecognizer()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    if (text.isNotBlank()) {
                        handleUserInput(text)
                    } else {
                        overlayState.value = OverlayState.Expanded
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let {
                        userInput.value = it
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            overlayState.value = OverlayState.Listening
            activeSpeechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Active speech recognition error", e)
            overlayState.value = OverlayState.Expanded
            safeResetActiveRecognizer()
        }
    }

    private fun stopActiveListening() {
        try {
            activeSpeechRecognizer?.stopListening()
        } catch (_: Exception) {}
        if (overlayState.value == OverlayState.Listening) {
            overlayState.value = OverlayState.Expanded
        }
    }

    private fun safeResetActiveRecognizer() {
        try {
            activeSpeechRecognizer?.cancel()
            activeSpeechRecognizer?.destroy()
        } catch (_: Exception) {}
        activeSpeechRecognizer = null
    }

    private fun safeDestroyRecognizers() {
        safeResetWakeWordRecognizer()
        safeResetActiveRecognizer()
    }

    // ─────────────────────────────────────────────────────────────────
    //  Agent Execution Loop
    // ─────────────────────────────────────────────────────────────────

    private fun handleUserInput(input: String) {
        if (input.isBlank()) return
        stopActiveListening()
        stopWakeWordListening()
        userInput.value = ""

        serviceScope.launch {
            // Thinking state
            overlayState.value = OverlayState.Thinking
            responseText.value = ""

            // Read live screen context on demand
            val screenCtx = ReddyAccessibilityService.instance
                ?.getScreenState()?.fullText?.take(1000)

            // Decide action via brain
            val action = brain.decide(input, screenCtx)

            // Executing state
            overlayState.value = OverlayState.Executing
            responseText.value = "Working on it..."

            // Execute action
            val result = executor.execute(action)

            // Log command & result to local and cloud history
            CommandHistoryStore.get(applicationContext).logCommand(
                command = input,
                actionType = action.javaClass.simpleName,
                resultText = result,
                isSuccess = !result.startsWith("Oops") && !result.startsWith("Couldn't")
            )

            // Show result & speak
            responseText.value = result
            overlayState.value = OverlayState.Result
            speak(result)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Butter-Smooth Drag & Edge Snapping
    // ─────────────────────────────────────────────────────────────────

    private fun updatePosition(dx: Float, dy: Float) {
        accumulatedDeltaX += dx
        accumulatedDeltaY += dy

        val deltaXi = accumulatedDeltaX.toInt()
        val deltaYi = accumulatedDeltaY.toInt()

        if (deltaXi == 0 && deltaYi == 0) return

        accumulatedDeltaX -= deltaXi
        accumulatedDeltaY -= deltaYi

        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val currentWidth = composeView?.width?.takeIf { it > 0 } ?: (80 * dm.density).toInt()
        val currentHeight = composeView?.height?.takeIf { it > 0 } ?: (80 * dm.density).toInt()

        val maxX = (screenWidth - currentWidth - 16).coerceAtLeast(16)
        val maxY = (screenHeight - currentHeight - 64).coerceAtLeast(16)

        // With BOTTOM or END gravity, moving right decreases X from right edge
        layoutParams.x = (layoutParams.x - deltaXi).coerceIn(16, maxX)
        layoutParams.y = (layoutParams.y - deltaYi).coerceIn(16, maxY)

        composeView?.let { windowManager.updateViewLayout(it, layoutParams) }
    }

    private fun snapToEdge() {
        accumulatedDeltaX = 0f
        accumulatedDeltaY = 0f

        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val currentWidth = composeView?.width?.takeIf { it > 0 } ?: (80 * dm.density).toInt()
        val viewX = layoutParams.x

        val midScreen = screenWidth / 2
        val viewCenter = screenWidth - viewX - currentWidth / 2

        layoutParams.x = if (viewCenter < midScreen) {
            (screenWidth - currentWidth - 16).coerceAtLeast(16)
        } else {
            16
        }

        composeView?.let { windowManager.updateViewLayout(it, layoutParams) }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Foreground Notification
    // ─────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, ReddysAIApplication.CHANNEL_OVERLAY)
            .setContentTitle("Reddy's AI")
            .setContentText("Say \"Hey Reddy\" anytime to talk")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}

// ═══════════════════════════════════════════════════════════════
//  LifecycleOwner for ComposeView in a Service (State.RESUMED)
// ═══════════════════════════════════════════════════════════════

internal class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun start() {
        savedStateRegistryController.performRestore(null)
        // Setting RESUMED enables 60/120fps infinite transitions and Compose effects
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
