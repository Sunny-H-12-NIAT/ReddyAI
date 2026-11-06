package com.reddysai.app.brain

import android.content.Context
import android.util.Log
import com.reddysai.app.agent.AgentAction
import com.reddysai.app.agent.AgentBrain
import com.reddysai.app.agent.MockAgentBrain
import com.reddysai.app.data.ReddysAISettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  GeminiBrain — Google Gemini 2.0 Flash Cloud AI Brain
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Provides real-time reasoning, natural conversational understanding,
 * and intelligent tool calling for complex multi-step mobile automation.
 * Falls back seamlessly to offline rule-matching if offline or without an API key.
 */
class GeminiBrain(private val context: Context) : AgentBrain {

    private val settings = ReddysAISettings.get(context)
    private val fallbackBrain = MockAgentBrain()

    companion object {
        private const val TAG = "GeminiBrain"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * Test Gemini API key connection from the Dashboard
         */
        suspend fun testApiKey(apiKey: String, modelName: String = "gemini-2.0-flash"): Result<String> =
            withContext(Dispatchers.IO) {
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Gemini API key is empty"))
                }

                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${apiKey.trim()}"
                    val bodyJson = JSONObject().apply {
                        val contents = JSONArray().apply {
                            val msg = JSONObject().apply {
                                val parts = JSONArray().apply {
                                    put(JSONObject().apply { put("text", "Say 'Gemini 2.0 Flash is ready for Reddy!' in 5 words.") })
                                }
                                put("parts", parts)
                            }
                            put(msg)
                        }
                        put("contents", contents)
                    }

                    val request = Request.Builder()
                        .url(url)
                        .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val respBody = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            val json = JSONObject(respBody)
                            val reply = json.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                            Result.success(reply.trim())
                        } else {
                            Result.failure(Exception("Gemini error ${response.code}: $respBody"))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gemini API test error", e)
                    Result.failure(e)
                }
            }
    }

    override suspend fun decide(userInput: String, screenContext: String?): AgentAction =
        withContext(Dispatchers.IO) {
            val apiKey = settings.geminiApiKey.trim()
            if (apiKey.isBlank()) {
                Log.d(TAG, "No Gemini API key set; using offline rule brain")
                return@withContext fallbackBrain.decide(userInput, screenContext)
            }

            try {
                val model = settings.geminiModel.ifBlank { "gemini-2.0-flash" }
                val prompt = buildSystemPrompt(userInput, screenContext)
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val bodyJson = JSONObject().apply {
                    val contentsArray = JSONArray().apply {
                        val msg = JSONObject().apply {
                            val parts = JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            }
                            put("parts", parts)
                        }
                        put(msg)
                    }
                    put("contents", contentsArray)
                    // Request JSON response
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.2)
                    })
                }

                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()
                        Log.w(TAG, "Gemini HTTP error ${response.code}: $err — falling back")
                        return@withContext fallbackBrain.decide(userInput, screenContext)
                    }

                    val respBody = response.body?.string() ?: return@withContext fallbackBrain.decide(userInput, screenContext)
                    val json = JSONObject(respBody)
                    val text = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    parseGeminiJson(text, userInput)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini inference failed: ${e.message}", e)
                fallbackBrain.decide(userInput, screenContext)
            }
        }

    private fun buildSystemPrompt(userInput: String, screenContext: String?): String {
        val screenInfo = if (!screenContext.isNullOrBlank()) {
            "\n[Current Screen Content: $screenContext]"
        } else ""

        return """
            You are Reddy, a smart mobile phone assistant running on Android.
            Analyze the user's command and output STRICTLY a JSON object matching this schema.
            
            Action Schemas:
            1. Open Camera and Click Photo:
               {"action":"take_photo", "facing":"back" or "front"}
            2. Take Screenshot:
               {"action":"take_screenshot"}
            3. Open App:
               {"action":"open_app", "app_name":"<human app name>"}
            4. Send WhatsApp:
               {"action":"send_whatsapp", "contact":"<contact name or phone>", "message":"<message text>"}
            5. Web Search:
               {"action":"search_web", "query":"<search text>"}
            6. Phone Call:
               {"action":"make_call", "target":"<name or number>"}
            7. Set Alarm:
               {"action":"set_alarm", "hour":<0-23>, "minute":<0-59>, "label":"..."}
            8. Set Timer:
               {"action":"set_timer", "seconds":<total seconds>, "label":"..."}
            9. Navigate:
               {"action":"navigate", "destination":"..."}
            10. Food Ordering (Zomato / Swiggy):
               {"action":"automate", "app_name":"Zomato", "task":"Order <item> from <restaurant>"}
               OR
               {"action":"compare_food", "restaurant":"...", "item":"...", "payment":"Cash on Delivery"}
            11. Email:
               {"action":"read_email"} OR {"action":"draft_email", "to":"...", "subject":"...", "body":"..."}
            12. Direct Conversation / Answer:
               {"action":"answer", "text":"<friendly conversational response>"}

            $screenInfo
            User Command: "$userInput"

            Output only the single JSON object.
        """.trimIndent()
    }

    private fun parseGeminiJson(rawText: String, originalInput: String): AgentAction {
        return try {
            val clean = rawText.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(clean)
            when (json.optString("action")) {
                "take_photo" -> AgentAction.TakePhoto(json.optString("facing", "back"))
                "take_screenshot" -> AgentAction.TakeScreenshot
                "open_app" -> AgentAction.OpenApp(json.optString("app_name", "Camera"))
                "send_whatsapp" -> AgentAction.SendWhatsApp(
                    contact = json.optString("contact", ""),
                    message = json.optString("message", "")
                )
                "search_web" -> AgentAction.SearchWeb(json.optString("query", originalInput))
                "make_call" -> AgentAction.MakeCall(json.optString("target", ""))
                "set_alarm" -> AgentAction.SetAlarm(
                    hour = json.optInt("hour", 7),
                    minute = json.optInt("minute", 0),
                    label = json.optString("label", "Reddy Alarm")
                )
                "set_timer" -> AgentAction.SetTimer(
                    seconds = json.optInt("seconds", 60),
                    label = json.optString("label", "Reddy Timer")
                )
                "navigate" -> AgentAction.Navigate(json.optString("destination", ""))
                "automate" -> AgentAction.AutomateAppTask(
                    appName = json.optString("app_name", "Zomato"),
                    task = json.optString("task", originalInput)
                )
                "compare_food" -> AgentAction.CompareAndOrderFood(
                    restaurant = json.optString("restaurant", "Zomato"),
                    item = json.optString("item", "Food"),
                    paymentMethod = json.optString("payment", "Cash on Delivery")
                )
                "read_email" -> AgentAction.ReadEmail
                "draft_email" -> AgentAction.DraftEmail(
                    to = json.optString("to", ""),
                    subject = json.optString("subject", "Note"),
                    body = json.optString("body", "")
                )
                "send_email" -> AgentAction.SendEmail(
                    to = json.optString("to", ""),
                    subject = json.optString("subject", "Note"),
                    body = json.optString("body", "")
                )
                "answer" -> AgentAction.AnswerText(json.optString("text", "I'm on it!"))
                else -> AgentAction.AnswerText(rawText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini JSON: $rawText", e)
            AgentAction.AnswerText(rawText)
        }
    }
}
