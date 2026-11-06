package com.reddysai.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  ReddysAISettings — Persistent configuration for AI, Mic & Storage
 * ═══════════════════════════════════════════════════════════════════════
 */
class ReddysAISettings private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "reddys_ai_settings"

        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_AI_BRAIN_MODE = "ai_brain_mode"
        const val KEY_WAKE_WORD_MODE = "wake_word_mode"
        const val KEY_SUPABASE_URL = "supabase_url"
        const val KEY_SUPABASE_KEY = "supabase_key"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_SAFETY_ENABLED = "payment_safety_enabled"

        // Wake word modes
        const val WAKE_MODE_CALM = "calm_tap"        // Calm: mic off when idle, tap mascot to talk
        const val WAKE_MODE_ALWAYS = "always_listen" // Always listening for "Hey Reddy"

        // Brain modes
        const val BRAIN_MODE_GEMINI = "gemini"
        const val BRAIN_MODE_LOCAL = "local"
        const val BRAIN_MODE_AUTO = "auto"

        // Default Gemini model
        const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"

        @Volatile
        private var instance: ReddysAISettings? = null

        fun get(context: Context): ReddysAISettings =
            instance ?: synchronized(this) {
                instance ?: ReddysAISettings(context.applicationContext).also { instance = it }
            }
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    var aiBrainMode: String
        get() = prefs.getString(KEY_AI_BRAIN_MODE, BRAIN_MODE_AUTO) ?: BRAIN_MODE_AUTO
        set(value) = prefs.edit().putString(KEY_AI_BRAIN_MODE, value).apply()

    var wakeWordMode: String
        get() = prefs.getString(KEY_WAKE_WORD_MODE, WAKE_MODE_CALM) ?: WAKE_MODE_CALM
        set(value) = prefs.edit().putString(KEY_WAKE_WORD_MODE, value).apply()

    var supabaseUrl: String
        get() = prefs.getString(KEY_SUPABASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_URL, value.trim()).apply()

    var supabaseKey: String
        get() = prefs.getString(KEY_SUPABASE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_KEY, value.trim()).apply()

    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 1.05f)
        set(value) = prefs.edit().putFloat(KEY_TTS_PITCH, value).apply()

    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, 1.02f)
        set(value) = prefs.edit().putFloat(KEY_TTS_RATE, value).apply()

    var isSafetyGateEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAFETY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SAFETY_ENABLED, value).apply()
}
