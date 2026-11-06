package com.reddysai.app.agent

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  AgentBrain — THE SINGLE SEAM FOR AI MODEL & REASONING INTEGRATION
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Maps user input and screen context to structured AgentActions.
 * Supports:
 *   • Gemini 2.0 Flash (Cloud Brain with full screen & multi-step reasoning)
 *   • EnhancedRuleBrain (Fast, zero-latency on-device fallback)
 *   • LocalModelBrain (Qwen3 on-device GGUF / llama.cpp)
 */
interface AgentBrain {
    /**
     * Given the user's text input (voice or typed), return one structured action.
     * Non-blocking suspend function.
     */
    suspend fun decide(userInput: String, screenContext: String? = null): AgentAction
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  Action types the agent can execute
 * ═══════════════════════════════════════════════════════════════════════
 */
sealed class AgentAction {
    /** Plain text response to speak/display to the user */
    data class AnswerText(val text: String) : AgentAction()

    /** Read the user's recent emails */
    data object ReadEmail : AgentAction()

    /** Compose and save a draft email */
    data class DraftEmail(
        val to: String,
        val subject: String,
        val body: String
    ) : AgentAction()

    /** Send an email (to, subject, body) */
    data class SendEmail(
        val to: String,
        val subject: String,
        val body: String
    ) : AgentAction()

    /** Navigate somewhere via Google Maps */
    data class Navigate(val destination: String) : AgentAction()

    /** Set an alarm at a given hour:minute */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String = "") : AgentAction()

    /** Set a countdown timer for seconds */
    data class SetTimer(val seconds: Int, val label: String = "Reddy Timer") : AgentAction()

    /** Launch an installed app by name */
    data class OpenApp(val appName: String) : AgentAction()

    /** Open camera and click an image */
    data class TakePhoto(val cameraFacing: String = "back") : AgentAction()

    /** Take a full screen screenshot */
    data object TakeScreenshot : AgentAction()

    /** Send a message or open WhatsApp */
    data class SendWhatsApp(val contact: String, val message: String = "") : AgentAction()

    /** Search the web */
    data class SearchWeb(val query: String) : AgentAction()

    /** Call a contact by name or phone number */
    data class MakeCall(val target: String) : AgentAction()

    /**
     * Drive an app via accessibility automation (e.g. Zomato, Swiggy, Amazon).
     */
    data class AutomateAppTask(val appName: String, val task: String) : AgentAction()

    /**
     * Compare food price across Swiggy & Zomato and proceed with ordering
     */
    data class CompareAndOrderFood(
        val restaurant: String,
        val item: String,
        val paymentMethod: String = "Cash on Delivery"
    ) : AgentAction()
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  EnhancedRuleBrain — High-speed offline rule & intent matcher
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Provides immediate responses and tool routing even without API keys.
 */
class MockAgentBrain : AgentBrain {

    override suspend fun decide(userInput: String, screenContext: String?): AgentAction {
        val raw = userInput.trim()
        val input = raw.lowercase().trim()

        // Clean common prefixes
        var clean = input
        for (prefix in listOf("hey reddy", "ok reddy", "reddy", "please", "can you", "could you", "would you")) {
            if (clean.startsWith(prefix)) {
                clean = clean.removePrefix(prefix).trim().removePrefix(",").trim()
            }
        }

        // ── Camera & Photo ───────────────────────────────────────────
        if (clean.containsAny(
                "open camera and click", "open camera and take", "click an image", "click a picture",
                "click photo", "take a picture", "take a photo", "take photo", "take photo",
                "capture photo", "take picture", "click selfie", "take selfie", "open camera and snap"
            )) {
            val facing = if (clean.contains("selfie") || clean.contains("front")) "front" else "back"
            return AgentAction.TakePhoto(cameraFacing = facing)
        }
        if (clean == "camera" || clean == "open camera" || clean == "launch camera") {
            return AgentAction.TakePhoto()
        }

        // ── Screenshot ───────────────────────────────────────────────
        if (clean.containsAny("take screenshot", "take a screenshot", "capture screen", "screenshot")) {
            return AgentAction.TakeScreenshot
        }

        // ── WhatsApp ─────────────────────────────────────────────────
        if (clean.startsWith("whatsapp") || clean.contains("send whatsapp") || clean.contains("message on whatsapp")) {
            var target = clean
                .removePrefix("send whatsapp to")
                .removePrefix("message on whatsapp to")
                .removePrefix("whatsapp to")
                .removePrefix("whatsapp")
                .trim()
            val msg = if (target.contains(" saying ")) {
                val parts = target.split(" saying ", limit = 2)
                target = parts[0].trim()
                parts[1].trim()
            } else if (target.contains(" that ")) {
                val parts = target.split(" that ", limit = 2)
                target = parts[0].trim()
                parts[1].trim()
            } else ""
            return AgentAction.SendWhatsApp(contact = target, message = msg)
        }

        // ── Web Search ───────────────────────────────────────────────
        if (clean.startsWith("search ") || clean.startsWith("google ") || clean.startsWith("search for ") || clean.startsWith("search the web for ")) {
            val query = clean
                .removePrefix("search the web for ")
                .removePrefix("search for ")
                .removePrefix("google ")
                .removePrefix("search ")
                .trim()
            return AgentAction.SearchWeb(query = query)
        }

        // ── Phone call ───────────────────────────────────────────────
        if (clean.startsWith("call ") || clean.startsWith("dial ") || clean.contains("make a call to ") || clean.contains("phone call to ") || clean.contains("call up ")) {
            val target = clean
                .removePrefix("make a call to ")
                .removePrefix("phone call to ")
                .removePrefix("call up ")
                .removePrefix("call ")
                .removePrefix("dial ")
                .removePrefix("to ")
                .removeSuffix(" please")
                .trim()
            return AgentAction.MakeCall(target)
        }

        // ── Email ────────────────────────────────────────────────────
        if (clean.containsAny("read email", "check email", "my emails", "inbox")) {
            return AgentAction.ReadEmail
        }
        if (clean.containsAny("draft email", "compose email", "write email")) {
            val to = extractAfter(clean, "to") ?: "friend@example.com"
            val subject = extractAfter(clean, "subject") ?: "Note from Reddy"
            val body = "Hi,\n\n[Reddy drafted this message from your request: \"$raw\"]\n\nBest,\nReddy"
            return AgentAction.DraftEmail(to, subject, body)
        }
        if (clean.containsAny("send email", "email to")) {
            val to = extractAfter(clean, "to") ?: "friend@example.com"
            return AgentAction.SendEmail(to, "Message from Reddy's AI", raw)
        }

        // ── Navigation ───────────────────────────────────────────────
        if (clean.containsAny("navigate to", "take me to", "directions to", "go to")) {
            val dest = clean
                .removePrefix("navigate to").removePrefix("take me to")
                .removePrefix("directions to").removePrefix("go to").trim()
            return AgentAction.Navigate(dest.ifBlank { "nearest restaurant" })
        }

        // ── Timers & Alarms ──────────────────────────────────────────
        if (clean.containsAny("set timer", "timer for", "countdown")) {
            val seconds = parseTimerSeconds(clean)
            return AgentAction.SetTimer(seconds, "Reddy Timer")
        }
        if (clean.containsAny("set alarm", "wake me", "alarm for", "remind me at")) {
            val (h, m) = parseTime(clean)
            return AgentAction.SetAlarm(h, m, "Reddy Alarm")
        }

        // ── Food Ordering (Zomato / Swiggy) ──────────────────────────
        if (clean.containsAny("order in zomato", "order on zomato", "order from zomato", "zomato", "swiggy", "order food")) {
            if ((clean.contains("compare") && (clean.contains("zomato") || clean.contains("swiggy"))) ||
                (clean.contains("zomato") && clean.contains("swiggy"))) {
                val restaurant = extractAfter(clean, "from ")
                    ?: extractAfter(clean, "in ")
                    ?: extractAfter(clean, "at ")
                    ?: "Behrouz Biryani"
                val item = when {
                    "biryani" in clean -> "Biryani"
                    "pizza" in clean -> "Pizza"
                    "burger" in clean -> "Burger"
                    else -> "Food"
                }
                val payment = if (clean.contains("cod") || clean.contains("cash")) "Cash on Delivery" else "UPI"
                return AgentAction.CompareAndOrderFood(
                    restaurant = restaurant.split(" with", " in", " on", " and").first().trim(),
                    item = item,
                    paymentMethod = payment
                )
            }

            val app = if (clean.contains("swiggy")) "Swiggy" else "Zomato"
            return AgentAction.AutomateAppTask(app, raw)
        }

        // ── Open any app ─────────────────────────────────────────────
        if (clean.containsAny("open ", "launch ", "start ", "switch to ")) {
            val app = clean
                .removePrefix("open ").removePrefix("launch ").removePrefix("start ").removePrefix("switch to ").trim()
            return AgentAction.OpenApp(app)
        }

        // ── Payment simulation ───────────────────────────────────────
        if (clean.containsAny("simulate payment", "test payment", "payment safety")) {
            return AgentAction.AutomateAppTask("PaymentTest", "Simulate payment confirmation gate")
        }

        // ── Conversational reply fallback ─────────────────────────────
        return AgentAction.AnswerText(generateConversationalReply(clean))
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }

    private fun extractAfter(input: String, keyword: String): String? {
        val idx = input.indexOf(keyword)
        if (idx == -1) return null
        return input.substring(idx + keyword.length).trim().takeIf { it.isNotBlank() }
    }

    private fun parseTimerSeconds(input: String): Int {
        val minMatch = Regex("""(\d+)\s*(min|minute)""").find(input)
        if (minMatch != null) return (minMatch.groupValues[1].toIntOrNull() ?: 1) * 60
        val secMatch = Regex("""(\d+)\s*(sec|second)""").find(input)
        if (secMatch != null) return secMatch.groupValues[1].toIntOrNull() ?: 30
        return 60 // default 1 min
    }

    private fun parseTime(input: String): Pair<Int, Int> {
        val regex = Regex("""(\d{1,2})[:\s]?(\d{2})?\s*(am|pm)?""", RegexOption.IGNORE_CASE)
        val match = regex.find(input) ?: return Pair(7, 0)
        var hour = match.groupValues[1].toIntOrNull() ?: 7
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val amPm = match.groupValues[3].lowercase()
        if (amPm == "pm" && hour < 12) hour += 12
        if (amPm == "am" && hour == 12) hour = 0
        return Pair(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    private fun generateConversationalReply(input: String): String {
        return when {
            input.containsAny("hello", "hi", "hey") ->
                "Hey there! I'm Reddy, your AI companion. How can I help you today? 😊"
            input.containsAny("how are you", "how r u") ->
                "Running smooth and ready! What can I do for you?"
            input.containsAny("what can you do", "help", "features") ->
                "I can open any app, click photos on camera, order in Zomato, take screenshots, send WhatsApp messages, search the web, and manage alarms! 🚀"
            input.containsAny("thank", "thanks") ->
                "Glad to help! Let me know if you need anything else ✨"
            else ->
                "Got it! Let me assist you with that. Tap any quick action or tell me what to do!"
        }
    }
}
