package com.reddysai.app.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  LocalModelBrain — Qwen3 1.7B via llama.cpp JNI
 * ═══════════════════════════════════════════════════════════════════════
 *
 * STATUS: STUB — waiting for libllama.so from the native build.
 *
 * HOW TO ACTIVATE (once the .so is ready):
 *   1. Copy the .so into  app/src/main/jniLibs/arm64-v8a/libllama.so
 *   2. Copy llama.h (and ggml.h if needed) into app/src/main/cpp/
 *   3. Wire the native function signatures below to the real llama.cpp C API
 *      (see comments on each `external fun`)
 *   4. In OverlayService, replace:
 *          private val brain: AgentBrain = MockAgentBrain()
 *      with:
 *          private val brain: AgentBrain =
 *              LocalModelBrain.create(applicationContext.filesDir.path + "/models/Qwen3-1.7B-Q4_K_M.gguf")
 *              ?: MockAgentBrain()
 *   5. Delete nothing else — AgentBrain / AgentAction / AgentExecutor are untouched.
 *
 * SYSTEM PROMPT DESIGN
 *   The prompt instructs the model to reply with exactly one JSON object matching
 *   the AgentAction schema. Output parsing is intentionally lenient — if the model
 *   doesn't produce valid JSON, the call degrades gracefully to AnswerText so the
 *   user always gets a response, never a crash.
 *
 * THREADING
 *   All JNI calls are dispatched on Dispatchers.IO so inference never blocks the
 *   main thread or the accessibility service's coroutine scope.
 */
class LocalModelBrain private constructor(private val modelHandle: Long) : AgentBrain {

    // ─── AgentBrain interface ──────────────────────────────────────────────────

    override suspend fun decide(userInput: String, screenContext: String?): AgentAction =
        withContext(Dispatchers.IO) {
            try {
                val prompt = buildPrompt(userInput, screenContext)
                val raw = nativeInfer(modelHandle, prompt, maxTokens = 256, temperature = 0.2f)
                parseModelOutput(raw, userInput)
            } catch (e: Exception) {
                Log.e(TAG, "Inference error — falling back to AnswerText", e)
                AgentAction.AnswerText("Sorry, I hit a snag thinking about that. Try again?")
            }
        }

    /** Release the native model handle. Call from OverlayService.onDestroy(). */
    fun release() {
        nativeFree(modelHandle)
    }

    // ─── Prompt construction ───────────────────────────────────────────────────

    private fun buildPrompt(userInput: String, screenContext: String?): String {
        val screenPart = if (!screenContext.isNullOrBlank())
            "\n<screen>\n$screenContext\n</screen>" else ""

        // Qwen3 ChatML format. Instructs the model to emit one strict JSON object
        // matching the AgentAction sealed class schema — no prose, no markdown.
        return """<|im_start|>system
You are Reddy, a concise on-device assistant. Reply with ONLY a single JSON object.
Schema (use exactly one):
  {"action":"answer",       "text":"..."}
  {"action":"read_email"}
  {"action":"draft_email",  "to":"...", "subject":"...", "body":"..."}
  {"action":"send_email",   "to":"...", "subject":"...", "body":"..."}
  {"action":"navigate",     "destination":"..."}
  {"action":"set_alarm",    "hour":H,   "minute":M, "label":"..."}
  {"action":"open_app",     "app_name":"..."}
  {"action":"make_call",    "target":"..."}
  {"action":"automate",     "app_name":"...", "task":"..."}
  {"action":"compare_food", "restaurant":"...", "item":"...", "payment":"..."}
No prose, no markdown, no explanation — JSON only.<|im_end|>
<|im_start|>user$screenPart
$userInput<|im_end|>
<|im_start|>assistant
"""
    }

    // ─── Output parsing ────────────────────────────────────────────────────────

    /**
     * Parses the raw model output into an [AgentAction].
     * Extracts the first balanced {…} block so leading think-tokens from
     * Qwen3's reasoning mode don't break parsing.
     */
    private fun parseModelOutput(raw: String, originalInput: String): AgentAction {
        val json = extractJson(raw) ?: return AgentAction.AnswerText(raw.trim())
        return try {
            when (val action = json.getString("action")) {
                "answer"      -> AgentAction.AnswerText(json.getString("text"))
                "read_email"  -> AgentAction.ReadEmail
                "draft_email" -> AgentAction.DraftEmail(
                    to      = json.getString("to"),
                    subject = json.getString("subject"),
                    body    = json.getString("body")
                )
                "send_email"  -> AgentAction.SendEmail(
                    to      = json.getString("to"),
                    subject = json.getString("subject"),
                    body    = json.getString("body")
                )
                "navigate"    -> AgentAction.Navigate(json.getString("destination"))
                "set_alarm"   -> AgentAction.SetAlarm(
                    hour   = json.getInt("hour"),
                    minute = json.getInt("minute"),
                    label  = json.optString("label", "Reddy Alarm")
                )
                "open_app"    -> AgentAction.OpenApp(json.getString("app_name"))
                "make_call"   -> AgentAction.MakeCall(json.getString("target"))
                "automate"    -> AgentAction.AutomateAppTask(
                    appName = json.getString("app_name"),
                    task    = json.getString("task")
                )
                "take_photo" -> AgentAction.TakePhoto(json.optString("facing", "back"))
                "take_screenshot" -> AgentAction.TakeScreenshot
                "send_whatsapp" -> AgentAction.SendWhatsApp(
                    contact = json.optString("contact", ""),
                    message = json.optString("message", "")
                )
                "search_web" -> AgentAction.SearchWeb(json.optString("query", originalInput))
                "set_timer" -> AgentAction.SetTimer(
                    seconds = json.optInt("seconds", 60),
                    label = json.optString("label", "Reddy Timer")
                )
                "compare_food" -> AgentAction.CompareAndOrderFood(
                    restaurant    = json.optString("restaurant", "Restaurant"),
                    item          = json.optString("item", "Biryani"),
                    paymentMethod = json.optString("payment", "Cash on Delivery")
                )
                else -> {
                    Log.w(TAG, "Unknown action from model: $action")
                    AgentAction.AnswerText(raw.trim())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed on: $raw", e)
            AgentAction.AnswerText(raw.trim().ifBlank { originalInput })
        }
    }

    /**
     * Finds the first balanced { } block in [text], returns it as a JSONObject.
     * Returns null if no valid JSON object is found.
     */
    private fun extractJson(text: String): org.json.JSONObject? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return try {
                            org.json.JSONObject(text.substring(start, i + 1))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    // ─── JNI declarations ──────────────────────────────────────────────────────
    //
    // These map to C functions in app/src/main/cpp/llama_jni.cpp.
    // The wrapper calls into llama.cpp's public C API:
    //
    // nativeLoad:   llama_model_load_from_file() + llama_new_context_with_model()
    //               Returns opaque Long (jlong-cast pointer), 0 on failure.
    //
    // nativeInfer:  Builds a llama_batch, runs llama_decode() in a token loop,
    //               accumulates token text via llama_token_to_piece().
    //               Returns the full generated string.
    //
    // nativeFree:   llama_free_context() + llama_model_free()

    private external fun nativeInfer(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String

    private external fun nativeFree(handle: Long)

    // ─── Companion / factory ───────────────────────────────────────────────────

    companion object {
        private const val TAG = "LocalModelBrain"

        /**
         * Loads the native library and model file. Returns **null** if libllama.so
         * is not yet present (i.e. before the native build is delivered) — callers
         * can safely do `?: MockAgentBrain()` as a zero-crash fallback.
         *
         * @param modelPath  Absolute path to the .gguf on device, e.g.
         *                   `context.filesDir.path + "/models/Qwen3-1.7B-Q4_K_M.gguf"`
         * @param nThreads   CPU threads for inference. 4 is a safe default for
         *                   Snapdragon 4 Gen 2 (6×A55 + 2×A78).
         * @param useGpu     Set true to request GPU offload (OpenCL) once the
         *                   GPU build of libllama.so is wired in. Ignored until then.
         */
        fun create(modelPath: String, nThreads: Int = 4, useGpu: Boolean = false): LocalModelBrain? {
            try {
                System.loadLibrary("llama")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libllama.so not found — LocalModelBrain unavailable", e)
                return null
            }
            val handle = nativeLoad(modelPath, nThreads, useGpu)
            if (handle == 0L) {
                Log.e(TAG, "nativeLoad returned 0 — check model path: $modelPath")
                return null
            }
            return LocalModelBrain(handle)
        }

        @JvmStatic
        private external fun nativeLoad(
            modelPath: String,
            nThreads: Int,
            useGpu: Boolean
        ): Long
    }
}
