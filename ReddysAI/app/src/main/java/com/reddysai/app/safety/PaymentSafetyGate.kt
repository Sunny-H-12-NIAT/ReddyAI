package com.reddysai.app.safety

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  PaymentSafetyGate — NON-NEGOTIABLE hard gate before any automation action
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Every accessibility action MUST pass through [checkAndProceed] or
 * [requiresSafetyCheck] before execution. This gate cannot be bypassed
 * by the AI model — it sits at the execution layer, not the reasoning layer.
 *
 * Flow:
 *   1. Before executing any tap/type/scroll action, call [requiresSafetyCheck]
 *      with the current screen text
 *   2. If it returns true, call [triggerPaymentConfirmation] and STOP
 *   3. The [PaymentConfirmationActivity] shows a blocking dialog
 *   4. User taps CONFIRM → broadcast → automation resumes
 *   5. User taps CANCEL → broadcast → automation is permanently aborted
 */
object PaymentSafetyGate {

    /**
     * Payment-related keywords to scan for. Case-insensitive.
     * These are matched against the full visible text of the current screen.
     */
    private val PAYMENT_KEYWORDS = setOf(
        // Generic payment CTAs
        "pay now", "pay ₹", "pay rs", "pay inr",
        "place order", "confirm order", "place your order",
        "confirm payment", "confirm purchase",
        "proceed to pay", "proceed to checkout", "complete purchase",
        "buy now", "checkout",
        // Payment method identifiers
        "upi", "gpay", "phonepe", "paytm", "net banking",
        "card number", "cvv", "expiry", "debit card", "credit card",
        // Order confirmation screens
        "order confirmed", "payment successful", "order placed",
        // Zomato/Swiggy specific
        "pay using", "select payment", "total amount due"
    )

    /**
     * Returns true if [screenText] contains any payment keyword.
     * Called before every single accessibility action during automation.
     */
    fun requiresSafetyCheck(screenText: String): Boolean {
        val lower = screenText.lowercase()
        return PAYMENT_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    /**
     * Launch the payment confirmation dialog as a full-screen Activity.
     * The automation loop must STOP after calling this and wait for the
     * broadcast result before doing anything else.
     *
     * @param service  The accessibility service (used to get application context)
     * @param screenText  The screen text that triggered the gate
     * @param onConfirmed  Called if the user taps CONFIRM
     * @param onCancelled  Called if the user taps CANCEL (or dialog is dismissed)
     */
    fun triggerPaymentConfirmation(
        context: android.content.Context,
        screenText: String,
        onConfirmed: () -> Unit,
        onCancelled: () -> Unit
    ) {
        // Register a one-shot listener for the result broadcast
        PaymentConfirmationActivity.pendingConfirm = onConfirmed
        PaymentConfirmationActivity.pendingCancel = onCancelled

        val intent = Intent(context, PaymentConfirmationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PaymentConfirmationActivity.EXTRA_SCREEN_PREVIEW,
                screenText.take(300)) // pass first 300 chars as context
        }
        context.startActivity(intent)
    }

    /**
     * Convenience: extract the top N words of screen text for keyword scanning.
     * Accessibility nodes can have a lot of text — we only need visible labels.
     */
    fun summarizeForScan(texts: List<String>): String =
        texts.joinToString(" ").take(4000) // 4K chars is plenty for keyword scan
}
