package com.reddysai.app.gmail

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Draft
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  GmailClient — wraps the Gmail REST API
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Uses GoogleAccountCredential with the signed-in account from
 * GoogleSignIn. Requires the Gmail scopes to have been requested at sign-in.
 *
 * All methods are suspend functions that run on Dispatchers.IO.
 * Returns null if the user isn't signed in.
 */
class GmailClient private constructor(private val service: Gmail) {

    // ─── Message listing ─────────────────────────────────────────────

    data class EmailSummary(
        val id: String,
        val subject: String,
        val from: String,
        val snippet: String,
        val date: String
    )

    suspend fun listMessages(
        maxResults: Long = 10,
        query: String = "in:inbox"
    ): List<EmailSummary> = withContext(Dispatchers.IO) {
        val listResponse = service.users().messages().list("me")
            .setQ(query)
            .setMaxResults(maxResults)
            .execute()

        listResponse.messages?.mapNotNull { ref ->
            try {
                val msg = service.users().messages().get("me", ref.id)
                    .setFormat("metadata")
                    .setMetadataHeaders(listOf("Subject", "From", "Date"))
                    .execute()

                val headers = msg.payload?.headers?.associateBy { it.name } ?: emptyMap()
                EmailSummary(
                    id = ref.id,
                    subject = headers["Subject"]?.value ?: "(no subject)",
                    from = headers["From"]?.value ?: "unknown",
                    snippet = msg.snippet ?: "",
                    date = headers["Date"]?.value ?: ""
                )
            } catch (_: Exception) { null }
        } ?: emptyList()
    }

    // ─── Get full message body ────────────────────────────────────────

    suspend fun getMessageBody(messageId: String): String = withContext(Dispatchers.IO) {
        val msg = service.users().messages().get("me", messageId)
            .setFormat("full")
            .execute()

        // Try text/plain first, fall back to snippet
        val part = msg.payload?.parts?.firstOrNull { it.mimeType == "text/plain" }
        val encoded = part?.body?.data ?: msg.payload?.body?.data
        if (encoded != null) {
            String(Base64.getUrlDecoder().decode(encoded))
        } else {
            msg.snippet ?: ""
        }
    }

    // ─── Create draft ─────────────────────────────────────────────────

    suspend fun createDraft(to: String, subject: String, body: String): Draft =
        withContext(Dispatchers.IO) {
            val raw = buildRawGmailMessage(to = to, from = "me", subject = subject, body = body)
            val draft = Draft().setMessage(Message().setRaw(raw))
            service.users().drafts().create("me", draft).execute()
        }

    // ─── Send email ───────────────────────────────────────────────────

    suspend fun sendEmail(to: String, subject: String, body: String): Message =
        withContext(Dispatchers.IO) {
            val raw = buildRawGmailMessage(to = to, from = "me", subject = subject, body = body)
            val message = Message().setRaw(raw)
            service.users().messages().send("me", message).execute()
        }

    // ─── Raw message builder ──────────────────────────────────────────

    fun buildRawGmailMessage(to: String, from: String, subject: String, body: String): String {
        val message = """
            |To: $to
            |From: $from
            |Subject: $subject
            |Content-Type: text/plain; charset="UTF-8"
            |
            |$body
        """.trimMargin()
        return android.util.Base64.encodeToString(
            message.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
    }

    // ─────────────────────────────────────────────────────────────────
    //  Singleton factory
    // ─────────────────────────────────────────────────────────────────

    companion object {
        private const val APP_NAME = "Reddy's AI"

        @Volatile private var _instance: GmailClient? = null

        /**
         * Returns a GmailClient if the user is signed in with Gmail scopes,
         * or null if not signed in.
         */
        fun getInstance(context: Context): GmailClient? {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null

            return _instance ?: synchronized(this) {
                _instance ?: run {
                    val credential = GoogleAccountCredential.usingOAuth2(
                        context,
                        listOf(
                            GmailScopes.GMAIL_READONLY,
                            GmailScopes.GMAIL_SEND,
                            GmailScopes.GMAIL_COMPOSE
                        )
                    ).apply { selectedAccount = account.account }

                    val transport = NetHttpTransport()
                    val jsonFactory = GsonFactory.getDefaultInstance()

                    val service = Gmail.Builder(transport, jsonFactory, credential)
                        .setApplicationName(APP_NAME)
                        .build()

                    GmailClient(service).also { _instance = it }
                }
            }
        }

        /** Call when user signs out */
        fun clear() { _instance = null }
    }
}
