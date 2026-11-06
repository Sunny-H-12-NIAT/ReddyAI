package com.reddysai.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  SupabaseClient — Lightweight REST integration for Supabase
 * ═══════════════════════════════════════════════════════════════════════
 */
object SupabaseClient {

    private const val TAG = "SupabaseClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Test connection to Supabase project
     */
    suspend fun testConnection(url: String, key: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim().removeSuffix("/")
        if (cleanUrl.isBlank() || key.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("URL and Anon Key cannot be empty"))
        }

        try {
            // Check Supabase REST endpoint root
            val request = Request.Builder()
                .url("$cleanUrl/rest/v1/")
                .addHeader("apikey", key.trim())
                .addHeader("Authorization", "Bearer ${key.trim()}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404 || response.code == 200) {
                    Result.success("Connected to Supabase successfully! (HTTP ${response.code})")
                } else {
                    Result.failure(Exception("Supabase returned HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Supabase connection test failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sync a command log entry to Supabase 'command_logs' table
     */
    suspend fun syncLog(url: String, key: String, logEntry: CommandLog): Boolean = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim().removeSuffix("/")
        if (cleanUrl.isBlank() || key.isBlank()) return@withContext false

        try {
            val jsonPayload = logEntry.toJson().toString()
            val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$cleanUrl/rest/v1/command_logs")
                .addHeader("apikey", key.trim())
                .addHeader("Authorization", "Bearer ${key.trim()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Command log synced to Supabase")
                    true
                } else {
                    Log.w(TAG, "Failed to sync log to Supabase: HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing to Supabase: ${e.message}")
            false
        }
    }
}
