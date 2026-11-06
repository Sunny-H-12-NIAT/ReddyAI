package com.reddysai.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Represents a single command executed by Reddy's AI
 */
data class CommandLog(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val actionType: String,
    val resultText: String,
    val isSuccess: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            return when {
                diff < 60_000 -> "Just now"
                diff < 3600_000 -> "${diff / 60_000}m ago"
                diff < 86400_000 -> "${diff / 3600_000}h ago"
                else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("command", command)
        put("action_type", actionType)
        put("result_text", resultText)
        put("is_success", isSuccess)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): CommandLog = CommandLog(
            id = json.optString("id", UUID.randomUUID().toString()),
            command = json.optString("command", ""),
            actionType = json.optString("action_type", "Command"),
            resultText = json.optString("result_text", ""),
            isSuccess = json.optBoolean("is_success", true),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  CommandHistoryStore — Local + Cloud Command History Logger
 * ═══════════════════════════════════════════════════════════════════════
 */
class CommandHistoryStore private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _historyFlow = MutableStateFlow<List<CommandLog>>(loadHistory())
    val historyFlow: StateFlow<List<CommandLog>> = _historyFlow.asStateFlow()

    companion object {
        private const val TAG = "CommandHistoryStore"
        private const val PREFS_NAME = "reddys_command_history"
        private const val KEY_HISTORY = "history_json"
        private const val MAX_LOGS = 100

        @Volatile
        private var instance: CommandHistoryStore? = null

        fun get(context: Context): CommandHistoryStore =
            instance ?: synchronized(this) {
                instance ?: CommandHistoryStore(context.applicationContext).also { instance = it }
            }
    }

    private fun loadHistory(): List<CommandLog> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return defaultInitialLogs()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<CommandLog>()
            for (i in 0 until array.length()) {
                list.add(CommandLog.fromJson(array.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse history", e)
            defaultInitialLogs()
        }
    }

    private fun defaultInitialLogs(): List<CommandLog> {
        return listOf(
            CommandLog(
                command = "Open camera and click picture",
                actionType = "Camera",
                resultText = "Camera launched & shutter clicked automatically",
                timestamp = System.currentTimeMillis() - 120_000
            ),
            CommandLog(
                command = "Order biryani from Behrouz on Zomato",
                actionType = "Automation",
                resultText = "Navigated Zomato, selected Biryani & opened cart",
                timestamp = System.currentTimeMillis() - 900_000
            ),
            CommandLog(
                command = "Take a screenshot",
                actionType = "System",
                resultText = "Screenshot captured successfully",
                timestamp = System.currentTimeMillis() - 3600_000
            )
        )
    }

    fun logCommand(
        command: String,
        actionType: String,
        resultText: String,
        isSuccess: Boolean = true
    ) {
        val entry = CommandLog(
            command = command,
            actionType = actionType,
            resultText = resultText,
            isSuccess = isSuccess
        )

        val updated = listOf(entry) + _historyFlow.value.take(MAX_LOGS - 1)
        _historyFlow.value = updated

        // Save locally
        scope.launch {
            try {
                val array = JSONArray()
                updated.forEach { array.put(it.toJson()) }
                prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving history locally", e)
            }

            // Sync to Supabase if configured
            val settings = ReddysAISettings.get(context)
            if (settings.supabaseUrl.isNotBlank() && settings.supabaseKey.isNotBlank()) {
                SupabaseClient.syncLog(settings.supabaseUrl, settings.supabaseKey, entry)
            }
        }
    }

    fun clearHistory() {
        _historyFlow.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }
}
