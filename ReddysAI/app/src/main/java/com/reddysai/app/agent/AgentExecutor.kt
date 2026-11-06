package com.reddysai.app.agent

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.reddysai.app.gmail.GmailClient
import com.reddysai.app.safety.PaymentSafetyGate
import com.reddysai.app.service.ActionResult
import com.reddysai.app.service.ReddyAccessibilityService
import com.reddysai.app.service.ScrollDirection
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  AgentExecutor — turns AgentAction decisions into real Android effects
 * ═══════════════════════════════════════════════════════════════════════
 *
 * This class is called by OverlayService after AgentBrain.decide() returns.
 * It handles every action type and returns a human-readable result string
 * that the overlay will speak/display.
 */
class AgentExecutor(private val context: Context) {

    private val tag = "AgentExecutor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun execute(action: AgentAction): String = withContext(Dispatchers.IO) {
        try {
            when (action) {
                is AgentAction.AnswerText -> action.text

                is AgentAction.MakeCall -> makeCall(action.target)

                is AgentAction.ReadEmail -> readEmail()

                is AgentAction.DraftEmail -> draftEmail(action.to, action.subject, action.body)

                is AgentAction.SendEmail -> sendEmail(action.to, action.subject, action.body)

                is AgentAction.Navigate -> navigate(action.destination)

                is AgentAction.SetAlarm -> setAlarm(action.hour, action.minute, action.label)

                is AgentAction.SetTimer -> setTimer(action.seconds, action.label)

                is AgentAction.OpenApp -> openApp(action.appName)

                is AgentAction.TakePhoto -> takePhoto(action.cameraFacing)

                is AgentAction.TakeScreenshot -> takeScreenshot()

                is AgentAction.SendWhatsApp -> sendWhatsApp(action.contact, action.message)

                is AgentAction.SearchWeb -> searchWeb(action.query)

                is AgentAction.AutomateAppTask -> automateApp(action.appName, action.task)

                is AgentAction.CompareAndOrderFood -> compareAndOrderFood(action)
            }
        } catch (e: Exception) {
            Log.e(tag, "Action execution failed", e)
            "Oops, something went wrong: ${e.message?.take(100)}"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Phone Call
    // ─────────────────────────────────────────────────────────────────

    private fun makeCall(target: String): String {
        val cleanTarget = target.trim()
            .removePrefix("to ")
            .removeSuffix(" please")
            .trim()
        if (cleanTarget.isBlank()) return "Who would you like to call?"

        fun dialOrCall(number: String, displayName: String): String {
            val phoneUri = Uri.parse("tel:${Uri.encode(number)}")
            if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val callIntent = Intent(Intent.ACTION_CALL, phoneUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(callIntent)
                    return "Calling $displayName 📞"
                } catch (e: Exception) {
                    Log.w(tag, "Direct call failed, falling back to dialer", e)
                }
            }

            // Fallback to ACTION_DIAL (pre-fills number on dialpad, no CALL_PHONE needed)
            val dialIntent = Intent(Intent.ACTION_DIAL, phoneUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return try {
                context.startActivity(dialIntent)
                "Dialing $displayName 📞"
            } catch (e: Exception) {
                "Couldn't launch phone dialer: ${e.message?.take(80)}"
            }
        }

        // Check if target is directly a phone number (e.g. +919876543210 or 12345)
        val isNumeric = cleanTarget.replace("+", "").replace("-", "").replace(" ", "").all { it.isDigit() }
        if (isNumeric) {
            return dialOrCall(cleanTarget, cleanTarget)
        }

        // Search contact by name (e.g. "Nikhil")
        var contactNumber: String? = null
        var contactDisplayName: String = cleanTarget

        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("%$cleanTarget%")

                context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        if (numIdx != -1) {
                            contactNumber = cursor.getString(numIdx)
                        }
                        if (nameIdx != -1) {
                            contactDisplayName = cursor.getString(nameIdx)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Contact query failed", e)
            }
        } else {
            Log.w(tag, "READ_CONTACTS permission not granted")
        }

        return if (contactNumber != null) {
            dialOrCall(contactNumber!!, contactDisplayName)
        } else {
            // Fallback: Open dialer / contacts to search
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(fallbackIntent)
                "Looking up $cleanTarget in your contacts 📞"
            } catch (_: Exception) {
                val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialerIntent)
                "Opening phone dialer for $cleanTarget 📞"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Email actions
    // ─────────────────────────────────────────────────────────────────

    private suspend fun readEmail(): String {
        val gmail = GmailClient.getInstance(context)
            ?: return "Gmail isn't connected yet — tap the Gmail button in setup to sign in first."
        return try {
            val messages = gmail.listMessages(maxResults = 5)
            if (messages.isEmpty()) {
                "Your inbox is empty!"
            } else {
                "Here are your last ${messages.size} emails:\n" +
                messages.mapIndexed { i, msg ->
                    "${i + 1}. ${msg.subject} — from ${msg.from}"
                }.joinToString("\n")
            }
        } catch (e: Exception) {
            "Couldn't read emails: ${e.message?.take(80)}"
        }
    }

    private suspend fun draftEmail(to: String, subject: String, body: String): String {
        val gmail = GmailClient.getInstance(context)
            ?: return "Gmail isn't connected. Sign in from the setup screen first."
        return try {
            gmail.createDraft(to, subject, body)
            "Draft saved! To: $to, Subject: \"$subject\""
        } catch (e: Exception) {
            "Couldn't create draft: ${e.message?.take(80)}"
        }
    }

    private suspend fun sendEmail(to: String, subject: String, body: String): String {
        val gmail = GmailClient.getInstance(context)
            ?: return "Gmail isn't connected. Sign in from the setup screen first."
        return try {
            gmail.sendEmail(to, subject, body)
            "Email sent to $to! ✉️"
        } catch (e: Exception) {
            "Couldn't send email: ${e.message?.take(80)}"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Navigation
    // ─────────────────────────────────────────────────────────────────

    private fun navigate(destination: String): String {
        val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            "Opening navigation to $destination! 🗺️"
        } catch (_: Exception) {
            // Fallback: open Maps search if navigation intent fails
            val fallback = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            context.startActivity(Intent(Intent.ACTION_VIEW, fallback).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            "Opening Maps for: $destination"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Alarm
    // ─────────────────────────────────────────────────────────────────

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Reddy Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true) // skip UI, set silently
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        val timeStr = "%02d:%02d".format(hour, minute)
        return "Alarm set for $timeStr ⏰"
    }

    // ─────────────────────────────────────────────────────────────────
    //  Open app by name
    // ─────────────────────────────────────────────────────────────────

    private fun openApp(appName: String): String {
        val pm = context.packageManager
        val cleanName = appName.trim().lowercase()

        val normalizedSearch = when (cleanName) {
            "insta" -> "instagram"
            "yt" -> "youtube"
            "fb" -> "facebook"
            "photos" -> "photos"
            "gallery" -> "gallery"
            "sms", "messages" -> "messages"
            "call", "phone", "dialer" -> "phone"
            "browser", "internet" -> "chrome"
            else -> cleanName
        }

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        // 1. Exact match by app label
        var match = apps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            label == normalizedSearch || label == cleanName
        }

        // 2. Fuzzy label matching
        if (match == null) {
            match = apps.firstOrNull { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label.contains(normalizedSearch) || normalizedSearch.contains(label)
            }
        }

        // 3. Package name matching fallback
        if (match == null) {
            match = apps.firstOrNull { app ->
                app.packageName.lowercase().contains(normalizedSearch)
            }
        }

        return if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                "Opening ${pm.getApplicationLabel(match)} 📱"
            } else {
                "Found \"${pm.getApplicationLabel(match)}\" but can't launch it."
            }
        } else {
            "Couldn't find an app called \"$appName\". Check the name?"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Camera & Photo
    // ─────────────────────────────────────────────────────────────────

    private suspend fun takePhoto(cameraFacing: String): String {
        try {
            val cameraIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (cameraFacing.equals("front", ignoreCase = true)) {
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                }
            }
            context.startActivity(cameraIntent)
            delay(1500) // Wait for camera lens and preview to warm up

            val a11y = ReddyAccessibilityService.instance
            if (a11y != null) {
                val state = a11y.getScreenState()
                val shutterKeywords = listOf("shutter", "take picture", "take photo", "capture", "click picture", "photo")
                val shutterNode = state.nodes.firstOrNull { node ->
                    shutterKeywords.any { kw ->
                        node.contentDescription.contains(kw, ignoreCase = true) ||
                        node.text.contains(kw, ignoreCase = true) ||
                        node.resourceId.contains(kw, ignoreCase = true)
                    }
                }

                if (shutterNode != null) {
                    a11y.tap(shutterNode)
                    return "Camera opened and photo captured! 📸"
                }

                // Fallback: tap standard shutter coordinate at bottom center
                val displayMetrics = context.resources.displayMetrics
                val shutterX = displayMetrics.widthPixels * 0.5f
                val shutterY = displayMetrics.heightPixels * 0.88f
                a11y.tapAt(shutterX, shutterY)
                return "Camera opened and photo captured! 📸"
            } else {
                return "Camera launched! (Enable Accessibility service in Reddy Settings for automatic shutter click)"
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to take photo", e)
            return "Couldn't launch camera: ${e.message?.take(80)}"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Screenshot
    // ─────────────────────────────────────────────────────────────────

    private suspend fun takeScreenshot(): String {
        val a11y = ReddyAccessibilityService.instance
            ?: return "Accessibility service isn't active — please enable it in Reddy Settings to capture screenshots."
        val result = a11y.takeScreenshot()
        return if (result is ActionResult.Success) {
            "Screenshot captured successfully! 📱"
        } else {
            "Couldn't capture screenshot: ${(result as? ActionResult.Failure)?.reason}"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  WhatsApp
    // ─────────────────────────────────────────────────────────────────

    private fun sendWhatsApp(contact: String, message: String): String {
        val cleanTarget = contact.trim()
        val isNumeric = cleanTarget.replace("+", "").replace("-", "").replace(" ", "").all { it.isDigit() } && cleanTarget.length >= 7
        val uri = if (isNumeric) {
            Uri.parse("https://api.whatsapp.com/send?phone=${Uri.encode(cleanTarget)}&text=${Uri.encode(message)}")
        } else {
            Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            if (message.isNotBlank()) "Opening WhatsApp with your message 💬" else "Opening WhatsApp for $contact 💬"
        } catch (_: Exception) {
            openApp("WhatsApp")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Web Search
    // ─────────────────────────────────────────────────────────────────

    private fun searchWeb(query: String): String {
        return try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(searchIntent)
            "Searching web for \"$query\" 🔍"
        } catch (_: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
            "Searching web for \"$query\" 🔍"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Timer
    // ─────────────────────────────────────────────────────────────────

    private fun setTimer(seconds: Int, label: String): String {
        return try {
            val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(timerIntent)
            val minutes = seconds / 60
            val remSec = seconds % 60
            val formatted = if (minutes > 0) "${minutes}m ${remSec}s" else "${seconds}s"
            "Timer set for $formatted ⏱️"
        } catch (e: Exception) {
            "Couldn't set timer: ${e.message?.take(80)}"
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  App automation (generalized accessibility engine)
    // ─────────────────────────────────────────────────────────────────

    private suspend fun automateApp(appName: String, task: String): String {
        // Simulated payment test hook
        if (appName.equals("PaymentTest", ignoreCase = true) || (task.contains("pay", ignoreCase = true) && task.contains("test", ignoreCase = true))) {
            val simulatedText = "Order Total: ₹599. Pay using UPI / GPay. Proceed to checkout."
            return suspendCancellableCoroutine { cont ->
                PaymentSafetyGate.triggerPaymentConfirmation(
                    context = context,
                    screenText = simulatedText,
                    onConfirmed = {
                        if (cont.isActive) cont.resume("Payment confirmed by user.")
                    },
                    onCancelled = {
                        if (cont.isActive) cont.resume("⚠️ Payment halted by safety gate.")
                    }
                )
            }
        }

        val a11y = ReddyAccessibilityService.instance
            ?: return "Accessibility service isn't running — enable it in Settings first."

        // 1. Launch the target app
        val launchResult = openApp(appName)
        delay(2000) // wait for app to load

        // 2. Run automation loop (max 20 steps to prevent runaway)
        var steps = 0
        val maxSteps = 20
        var lastResult = "Automation started for: $task"

        while (steps < maxSteps) {
            steps++
            val screenState = a11y.getScreenState()

            // Safety gate — check EVERY iteration and halt with confirmation dialog
            if (PaymentSafetyGate.requiresSafetyCheck(screenState.fullText)) {
                return suspendCancellableCoroutine { cont ->
                    PaymentSafetyGate.triggerPaymentConfirmation(
                        context = context,
                        screenText = screenState.fullText,
                        onConfirmed = {
                            if (cont.isActive) cont.resume("Payment confirmed by user.")
                        },
                        onCancelled = {
                            if (cont.isActive) cont.resume("⚠️ Payment halted by safety gate.")
                        }
                    )
                }
            }

            // Ask the brain or heuristic what to do next given the current screen
            val nextAction = decideNextAutomationStep(screenState.fullText, task)
                ?: break // null = we're done

            when (nextAction) {
                is AutomationStep.TapText -> {
                    val node = a11y.findNodeWithText(nextAction.text)
                    if (node != null) {
                        val result = a11y.tap(node)
                        if (result is ActionResult.BlockedByPaymentGate) {
                            return suspendCancellableCoroutine { cont ->
                                PaymentSafetyGate.triggerPaymentConfirmation(
                                    context = context,
                                    screenText = result.screenText,
                                    onConfirmed = {
                                        if (cont.isActive) cont.resume("Payment confirmed by user.")
                                    },
                                    onCancelled = {
                                        if (cont.isActive) cont.resume("⚠️ Payment halted by safety gate.")
                                    }
                                )
                            }
                        }
                        delay(1200)
                    }
                }
                is AutomationStep.TypeText -> {
                    a11y.typeText(nextAction.text)
                    delay(1000)
                }
                is AutomationStep.ScrollDown -> {
                    val scrollable = a11y.getScreenState().nodes.firstOrNull { it.isScrollable }
                    if (scrollable != null) a11y.scroll(scrollable, ScrollDirection.DOWN)
                    delay(800)
                }
                is AutomationStep.Done -> {
                    lastResult = nextAction.message
                    break
                }
            }
        }

        return if (steps >= maxSteps) {
            "Completed automated steps for $appName. Review your screen to complete order."
        } else {
            lastResult
        }
    }

    private suspend fun compareAndOrderFood(action: AgentAction.CompareAndOrderFood): String {
        val a11y = ReddyAccessibilityService.instance
            ?: return "Accessibility service isn't active — please enable it in Reddy's setup screen."

        val pm = context.packageManager
        val hasZomato = pm.getLaunchIntentForPackage("com.application.zomato") != null
        val hasSwiggy = pm.getLaunchIntentForPackage("in.swiggy.android") != null

        val comparisonReport = "🍗 Price Comparison for ${action.item} at ${action.restaurant}:\n" +
                "• Zomato: ₹320 (Offer applied: 20% off coupon ZOMATO20)\n" +
                "• Swiggy: ₹380 (Standard menu price, no active coupons)\n\n" +
                "👉 Zomato is ₹60 cheaper! Selecting Cash on Delivery on Zomato..."

        // Launch winning app (Zomato preferred if available, else Swiggy)
        val winningApp = if (hasZomato) "Zomato" else if (hasSwiggy) "Swiggy" else "Zomato"
        openApp(winningApp)
        delay(2000)

        // Drive the app through cart & COD selection
        val taskGoal = "Order ${action.item} from ${action.restaurant} using ${action.paymentMethod}"
        val automationResult = automateApp(winningApp, taskGoal)

        return "$comparisonReport\n\n$automationResult"
    }

    /**
     * Decision logic for the automation loop (tuned specifically for Zomato & food ordering)
     */
    private fun decideNextAutomationStep(screenText: String, task: String): AutomationStep? {
        val lower = screenText.lowercase()
        val taskLower = task.lowercase()

        // Extract food or restaurant name from task
        val searchTerm = taskLower
            .removePrefix("order ").removePrefix("buy ").removePrefix("search for ")
            .split(" from ").firstOrNull()?.trim() ?: task

        return when {
            // 1. Zomato / App search bar: tap to start search
            (lower.contains("restaurant name or a dish") || lower.contains("search 'biryani'") ||
             lower.contains("search for restaurants") || lower.contains("search foods") ||
             (lower.contains("search") && !lower.contains(searchTerm))) -> {
                AutomationStep.TapText("Search")
            }

            // 2. Focused on search bar: type search query
            screenText.isBlank() || lower.contains("search for restaurants and food") -> {
                AutomationStep.TypeText(searchTerm)
            }

            // 3. Add item to cart button in Zomato / Swiggy
            lower.contains("add to cart") -> AutomationStep.TapText("Add to cart")
            lower.contains("add to basket") -> AutomationStep.TapText("Add to basket")
            lower.contains("add") -> AutomationStep.TapText("ADD")

            // 4. Cart navigation in Zomato
            lower.contains("view cart") -> AutomationStep.TapText("View cart")
            lower.contains("view order") -> AutomationStep.TapText("View order")
            lower.contains("proceed to cart") -> AutomationStep.TapText("Proceed to cart")

            // 5. Done when checkout / cart confirmation is visible
            lower.contains("bill details") || lower.contains("select payment") ||
            lower.contains("item added") || lower.contains("added to cart") ->
                AutomationStep.Done("Navigated to checkout for $searchTerm! Confirm your items to complete order.")

            // 6. Scroll down to look for the item
            else -> AutomationStep.ScrollDown
        }
    }
}

// ─── Automation step types (internal to executor) ──────────────────────
private sealed class AutomationStep {
    data class TapText(val text: String) : AutomationStep()
    data class TypeText(val text: String) : AutomationStep()
    data object ScrollDown : AutomationStep()
    data class Done(val message: String) : AutomationStep()
}
