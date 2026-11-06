package com.reddysai.app.safety

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reddysai.app.ui.theme.ReddysAITheme

/**
 * Full-screen blocking activity shown when the payment safety gate fires.
 *
 * Design goals:
 * - Appears instantly over any app, looks unmistakably like a security alert
 * - Cannot be accidentally dismissed by back gesture (back = CANCEL, always)
 * - Communicates exactly what was about to happen
 * - Two buttons only: CONFIRM (proceed) and CANCEL (abort permanently)
 *
 * MIUI note: Set theme to Theme.ReddysAI.PaymentAlert which has
 * windowIsTranslucent=false so MIUI doesn't kill it as a background overlay.
 */
class PaymentConfirmationActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SCREEN_PREVIEW = "screen_preview"

        // One-shot callbacks set by PaymentSafetyGate before launching this activity
        var pendingConfirm: (() -> Unit)? = null
        var pendingCancel: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screenPreview = intent.getStringExtra(EXTRA_SCREEN_PREVIEW) ?: ""

        // Override back gesture — treat it as CANCEL
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleCancel()
        })

        setContent {
            ReddysAITheme {
                PaymentAlertScreen(
                    screenPreview = screenPreview,
                    onConfirm = { handleConfirm() },
                    onCancel = { handleCancel() }
                )
            }
        }
    }

    private fun handleConfirm() {
        val cb = pendingConfirm
        pendingConfirm = null
        pendingCancel = null
        cb?.invoke()
        finish()
    }

    private fun handleCancel() {
        val cb = pendingCancel
        pendingConfirm = null
        pendingCancel = null
        cb?.invoke()
        finish()
    }
}

@Composable
fun PaymentAlertScreen(
    screenPreview: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(2.dp, Color(0xFFFF1744), RoundedCornerShape(24.dp))
                .background(Color(0xFF1A0505), RoundedCornerShape(24.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Alert icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color(0x33FF1744), RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Payment Warning",
                    tint = Color(0xFFFF1744),
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "⚠️ Payment Detected",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF1744),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Reddy detected a payment-related screen.\nDo you want to proceed?",
                fontSize = 15.sp,
                color = Color(0xFFECEFF1),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            if (screenPreview.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Screen context:\n\"${screenPreview.take(200)}…\"",
                        fontSize = 12.sp,
                        color = Color(0xFF78909C),
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // CANCEL — primary prominent button (safer default)
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
            ) {
                Text("CANCEL — Stop Automation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(12.dp))

            // CONFIRM — secondary, less prominent
            OutlinedButton(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF78909C)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37474F))
            ) {
                Text("CONFIRM — Yes, proceed with payment", fontSize = 14.sp)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Reddy will NEVER complete a payment\nwithout your explicit confirmation.",
                fontSize = 11.sp,
                color = Color(0xFF546E7A),
                textAlign = TextAlign.Center
            )
        }
    }
}
