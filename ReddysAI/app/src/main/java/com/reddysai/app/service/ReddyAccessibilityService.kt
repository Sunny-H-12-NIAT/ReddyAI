package com.reddysai.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reddysai.app.safety.PaymentSafetyGate
import kotlinx.coroutines.*

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  ReddyAccessibilityService
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Provides two responsibilities:
 *
 * 1. OBSERVE — dumps the current screen's node tree as structured data
 *    (element type, text, content description, bounds, clickable/editable state)
 *    and exposes it via [ScreenState].
 *
 * 2. ACT — exposes [tap], [typeText], [scroll], [pressBack] as clean
 *    callable functions. Every action goes through [PaymentSafetyGate]
 *    before execution — this is non-negotiable and cannot be bypassed.
 *
 * Singleton pattern: [instance] is set on [onServiceConnected] and cleared
 * on [onUnbind]. Other components (OverlayService) use [instance] to drive
 * the screen.
 */
class ReddyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReddyA11y"

        /** Live singleton — null when service is not running */
        var instance: ReddyAccessibilityService? = null
            private set

        /** Most recent snapshot of the screen's text content */
        val currentScreenState get() = _currentScreenState

        @Volatile
        private var _currentScreenState = ScreenState.empty()
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "Accessibility service disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // ─────────────────────────────────────────────────────────────────
    //  Screen observation
    // ─────────────────────────────────────────────────────────────────

    @Volatile
    private var lastActivePackage: String = ""
    @Volatile
    private var lastSnapshotTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Only track window state changes (e.g. app switch).
        // NEVER dump node trees on high-frequency scroll or content change events!
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotBlank()) {
                lastActivePackage = pkg
            }
        }
    }

    /** Returns current screen state — refreshes on-demand if cache is older than 350ms */
    fun getScreenState(): ScreenState {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotTime > 350 || _currentScreenState.nodes.isEmpty()) {
            refreshScreenState()
            lastSnapshotTime = now
        }
        return _currentScreenState
    }

    private fun refreshScreenState() {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val nodes = mutableListOf<NodeInfo>()
            val texts = mutableListOf<String>()
            dumpNode(root, nodes, texts)
            _currentScreenState = ScreenState(
                nodes = nodes,
                fullText = texts.joinToString(" "),
                packageName = currentPackageName() ?: lastActivePackage
            )
        } catch (e: Exception) {
            Log.w(TAG, "Screen dump failed gracefully: ${e.message}")
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo,
        out: MutableList<NodeInfo>,
        texts: MutableList<String>,
        depth: Int = 0
    ) {
        if (depth > 30) return // prevent stack overflow on deep trees

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val label = text.ifBlank { desc }

        if (label.isNotBlank()) texts.add(label)

        if (bounds.width() > 0 && bounds.height() > 0) {
            out.add(
                NodeInfo(
                    className = node.className?.toString() ?: "",
                    text = text,
                    contentDescription = desc,
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    resourceId = node.viewIdResourceName ?: ""
                )
            )
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                dumpNode(child, out, texts, depth + 1)
                child.recycle()
            }
        }
    }

    private fun currentPackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    // ─────────────────────────────────────────────────────────────────
    //  Actions — ALL go through the safety gate
    // ─────────────────────────────────────────────────────────────────

    /**
     * Tap a node. Returns false if blocked by safety gate.
     */
    suspend fun tap(node: NodeInfo): ActionResult {
        val screenState = getScreenState()
        if (PaymentSafetyGate.requiresSafetyCheck(screenState.fullText)) {
            return ActionResult.BlockedByPaymentGate(screenState.fullText)
        }
        return withContext(Dispatchers.Main) {
            performTap(node.bounds.centerX().toFloat(), node.bounds.centerY().toFloat())
        }
    }

    /**
     * Tap by coordinates. Returns false if blocked by safety gate.
     */
    suspend fun tapAt(x: Float, y: Float): ActionResult {
        val screenState = getScreenState()
        if (PaymentSafetyGate.requiresSafetyCheck(screenState.fullText)) {
            return ActionResult.BlockedByPaymentGate(screenState.fullText)
        }
        return withContext(Dispatchers.Main) {
            performTap(x, y)
        }
    }

    /**
     * Type text into the currently focused editable field.
     */
    suspend fun typeText(text: String): ActionResult {
        val screenState = getScreenState()
        if (PaymentSafetyGate.requiresSafetyCheck(screenState.fullText)) {
            return ActionResult.BlockedByPaymentGate(screenState.fullText)
        }
        return withContext(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@withContext ActionResult.Failure("No root window")
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root)
            focused?.let {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                val success = it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                it.recycle()
                root.recycle()
                if (success) ActionResult.Success else ActionResult.Failure("SET_TEXT failed")
            } ?: run {
                root.recycle()
                ActionResult.Failure("No editable field found")
            }
        }
    }

    /**
     * Scroll a scrollable node up or down.
     */
    suspend fun scroll(node: NodeInfo, direction: ScrollDirection): ActionResult {
        val screenState = getScreenState()
        if (PaymentSafetyGate.requiresSafetyCheck(screenState.fullText)) {
            return ActionResult.BlockedByPaymentGate(screenState.fullText)
        }
        return withContext(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@withContext ActionResult.Failure("No root")
            val found = findNodeByBounds(root, node.bounds)
            val actionId = if (direction == ScrollDirection.DOWN)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val success = found?.performAction(actionId) ?: false
            found?.recycle()
            root.recycle()
            if (success) ActionResult.Success else ActionResult.Failure("Scroll failed")
        }
    }

    /** Press the system Back button */
    suspend fun pressBack(): ActionResult {
        return withContext(Dispatchers.Main) {
            val success = performGlobalAction(GLOBAL_ACTION_BACK)
            if (success) ActionResult.Success else ActionResult.Failure("Back failed")
        }
    }

    /** Press Home */
    suspend fun pressHome(): ActionResult {
        return withContext(Dispatchers.Main) {
            val success = performGlobalAction(GLOBAL_ACTION_HOME)
            if (success) ActionResult.Success else ActionResult.Failure("Home failed")
        }
    }

    /** Take full screen screenshot via Android Accessibility service (API 28+) */
    suspend fun takeScreenshot(): ActionResult {
        return withContext(Dispatchers.Main) {
            val success = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (success) ActionResult.Success else ActionResult.Failure("Screenshot failed")
        }
    }

    /** Find a node containing specific text — used by automation loop */
    fun findNodeWithText(text: String, exactMatch: Boolean = false): NodeInfo? {
        val state = getScreenState()
        return state.nodes.firstOrNull { node ->
            if (exactMatch) node.text.equals(text, ignoreCase = true)
            else node.text.contains(text, ignoreCase = true) ||
                    node.contentDescription.contains(text, ignoreCase = true)
        }
    }

    /** Find clickable nodes — useful for the automation loop to enumerate buttons */
    fun findClickableNodes(): List<NodeInfo> =
        getScreenState().nodes.filter { it.isClickable && it.text.isNotBlank() }

    // ─────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────

    private fun performTap(x: Float, y: Float): ActionResult {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var result: ActionResult = ActionResult.Failure("Gesture dispatch pending")
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = ActionResult.Success
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result = ActionResult.Failure("Gesture cancelled")
            }
        }, null)
        // Small delay to let gesture settle
        Thread.sleep(150)
        return result
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null && found != child) child.recycle()
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (bounds == target) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByBounds(child, target)
            if (found != null && found != child) child.recycle()
            if (found != null) return found
            child.recycle()
        }
        return null
    }
}

// ═══════════════════════════════════════════════════════════════
//  Data classes
// ═══════════════════════════════════════════════════════════════

data class NodeInfo(
    val className: String,
    val text: String,
    val contentDescription: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val resourceId: String
)

data class ScreenState(
    val nodes: List<NodeInfo>,
    val fullText: String,
    val packageName: String
) {
    companion object {
        fun empty() = ScreenState(emptyList(), "", "")
    }
}

enum class ScrollDirection { UP, DOWN }

sealed class ActionResult {
    data object Success : ActionResult()
    data class Failure(val reason: String) : ActionResult()
    data class BlockedByPaymentGate(val screenText: String) : ActionResult()
}
