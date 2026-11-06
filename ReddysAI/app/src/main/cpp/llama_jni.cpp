// llama_jni.cpp
// JNI bridge between ReddysAI (com.reddysai.app) and llama.cpp.
//
// API surface derived directly from the official llama.android example at
// commit 836d57176 (examples/llama.android/lib/src/main/cpp/ai_chat.cpp).
// All llama.cpp function calls are taken verbatim from that reference —
// nothing is inferred from general knowledge.
//
// JNI method naming convention:
//   Java_<pkg_underscored>_<class>_<method>
//   Package: com.reddysai.app.agent  →  com_reddysai_app_agent
//   Class:   LocalModelBrain         →  LocalModelBrain
//
// Three entry points match the three `external fun` declarations in LocalModelBrain.kt:
//   nativeLoad   — load model + create context + sampler
//   nativeInfer  — full prompt → completion in one blocking call
//   nativeFree   — release all resources

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <sstream>
#include <unistd.h>

// llama.cpp public headers — must be present in app/src/main/cpp/include/
// after being copied from the prebuilt artefact package.
#include "llama.h"
#include "common.h"
#include "sampling.h"
#include "chat.h"

#define LOG_TAG  "reddysai-llama"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Constants (match reference defaults) ─────────────────────────────────────

static constexpr int   N_THREADS_MIN        = 2;
static constexpr int   N_THREADS_MAX        = 4;
static constexpr int   N_THREADS_HEADROOM   = 2;
static constexpr int   DEFAULT_CTX_SIZE     = 512;   // matches -c 512 bench param
static constexpr int   BATCH_SIZE           = 512;
static constexpr float DEFAULT_TEMP         = 0.2f;  // low temp for JSON action output

// ─── Session state (one model loaded at a time) ───────────────────────────────

struct LlamaSession {
    llama_model   * model   = nullptr;
    llama_context * context = nullptr;
    common_sampler* sampler = nullptr;
};

// The handle passed to Kotlin is just a reinterpret_cast<jlong> of this pointer.
static LlamaSession * session_from_handle(jlong h) {
    return reinterpret_cast<LlamaSession *>(static_cast<uintptr_t>(h));
}

// ─── nativeLoad ───────────────────────────────────────────────────────────────
//
// Signature (Kotlin):  external fun nativeLoad(modelPath: String, nThreads: Int, useGpu: Boolean): Long
// Returns opaque Long handle (pointer), or 0 on any failure.

extern "C"
JNIEXPORT jlong JNICALL
Java_com_reddysai_app_agent_LocalModelBrain_00024Companion_nativeLoad(
        JNIEnv  *env,
        jobject  /*companion*/,
        jstring  jmodelPath,
        jint     nThreads,
        jboolean /*useGpu — reserved, not yet wired to OpenCL backend selection*/) {

    // ── 1. Initialise backend ──────────────────────────────────────────────
    llama_log_set([](enum ggml_log_level level, const char *text, void *) {
        int prio = ANDROID_LOG_DEFAULT;
        switch (level) {
            case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
            case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
            case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
            case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
            default: break;
        }
        __android_log_write(prio, LOG_TAG, text);
    }, nullptr);

    llama_backend_init();

    // ── 2. Load model ──────────────────────────────────────────────────────
    const char *model_path = env->GetStringUTFChars(jmodelPath, nullptr);
    LOGi("nativeLoad: loading model from %s", model_path);

    llama_model_params model_params = llama_model_default_params();
    // model_params.n_gpu_layers = useGpu ? 99 : 0;  // wire when GPU .so arrives

    llama_model *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodelPath, model_path);

    if (!model) {
        LOGe("nativeLoad: llama_model_load_from_file() failed");
        llama_backend_free();
        return 0L;
    }

    // ── 3. Create context ──────────────────────────────────────────────────
    // Clamp thread count same way as reference: [N_THREADS_MIN, N_THREADS_MAX]
    // but also respect the caller's explicit nThreads preference.
    const int hw_threads = (int)sysconf(_SC_NPROCESSORS_ONLN);
    int n_threads = (nThreads > 0) ? nThreads
                                   : std::max(N_THREADS_MIN,
                                              std::min(N_THREADS_MAX, hw_threads - N_THREADS_HEADROOM));
    LOGi("nativeLoad: using %d threads (hw=%d)", n_threads, hw_threads);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_ctx = llama_model_n_ctx_train(model);
    ctx_params.n_ctx          = std::min(DEFAULT_CTX_SIZE, trained_ctx);
    ctx_params.n_batch        = BATCH_SIZE;
    ctx_params.n_ubatch       = BATCH_SIZE;
    ctx_params.n_threads      = n_threads;
    ctx_params.n_threads_batch = n_threads;

    // Use llama_init_from_model — the name at this commit (not the old _new_context_with_model)
    llama_context *context = llama_init_from_model(model, ctx_params);
    if (!context) {
        LOGe("nativeLoad: llama_init_from_model() returned null");
        llama_model_free(model);
        llama_backend_free();
        return 0L;
    }

    // ── 4. Create sampler ──────────────────────────────────────────────────
    common_params_sampling sparams;
    sparams.temp = DEFAULT_TEMP;
    common_sampler *sampler = common_sampler_init(model, sparams);
    if (!sampler) {
        LOGe("nativeLoad: common_sampler_init() returned null");
        llama_free(context);
        llama_model_free(model);
        llama_backend_free();
        return 0L;
    }

    // ── 5. Return handle ───────────────────────────────────────────────────
    auto *session  = new LlamaSession{model, context, sampler};
    LOGi("nativeLoad: success, handle=%p", (void*)session);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(session));
}

// ─── nativeInfer ──────────────────────────────────────────────────────────────
//
// Signature (Kotlin):  external fun nativeInfer(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String
// Prompt is already fully formatted ChatML — do NOT re-apply a chat template here.

extern "C"
JNIEXPORT jstring JNICALL
Java_com_reddysai_app_agent_LocalModelBrain_nativeInfer(
        JNIEnv  *env,
        jobject  /*instance*/,
        jlong    handle,
        jstring  jprompt,
        jint     maxTokens,
        jfloat   temperature) {

    LlamaSession *sess = session_from_handle(handle);
    if (!sess || !sess->model || !sess->context || !sess->sampler) {
        LOGe("nativeInfer: invalid session handle");
        return env->NewStringUTF("");
    }

    // Override sampler temperature per-call if different from default
    if (std::abs(temperature - DEFAULT_TEMP) > 1e-4f) {
        common_sampler_free(sess->sampler);
        common_params_sampling sparams;
        sparams.temp = temperature;
        sess->sampler = common_sampler_init(sess->model, sparams);
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    LOGi("nativeInfer: prompt len=%d, maxTokens=%d", (int)strlen(prompt), maxTokens);

    // ── 1. Tokenize prompt ─────────────────────────────────────────────────
    // add_special=true  → BOS; parse_special=true  → honour <|im_start|> etc.
    llama_tokens tokens = common_tokenize(sess->context, prompt, /*add_special=*/true, /*parse_special=*/true);
    env->ReleaseStringUTFChars(jprompt, prompt);

    LOGi("nativeInfer: prompt tokenized to %d tokens", (int)tokens.size());

    // ── 2. Clear KV cache and reset sampler ────────────────────────────────
    llama_memory_clear(llama_get_memory(sess->context), false);
    common_sampler_reset(sess->sampler);

    // ── 3. Decode prompt in batches ────────────────────────────────────────
    common_batch batch(sess->context);
    const int n_prompt = (int)tokens.size();

    for (int i = 0; i < n_prompt; i += BATCH_SIZE) {
        const int cur = std::min(n_prompt - i, BATCH_SIZE);
        batch.clear();
        for (int j = 0; j < cur; j++) {
            const bool want_logit = (i + j == n_prompt - 1); // logit only for last token
            batch.add(tokens[i + j], /*pos=*/i + j, /*seq_id=*/0, want_logit);
        }
        if (llama_process(sess->context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("nativeInfer: llama_process failed during prompt decode");
            return env->NewStringUTF("");
        }
    }

    // ── 4. Token generation loop ───────────────────────────────────────────
    std::ostringstream result;
    std::string cached;   // accumulates partial UTF-8 sequences

    for (int n = 0; n < maxTokens; n++) {
        // Sample
        const llama_token new_token = common_sampler_sample(sess->sampler, sess->context, -1);
        common_sampler_accept(sess->sampler, new_token, /*accept_grammar=*/true);

        // EOG?
        if (llama_vocab_is_eog(llama_model_get_vocab(sess->model), new_token)) {
            LOGi("nativeInfer: EOG at step %d", n);
            break;
        }

        // Decode for next step
        batch.clear();
        batch.add(new_token, /*pos=*/n_prompt + n, /*seq_id=*/0, /*logit=*/true);
        if (llama_process(sess->context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("nativeInfer: llama_process failed at token %d", n);
            break;
        }

        // Token → text (mirrors reference UTF-8 caching approach)
        cached += common_token_to_piece(sess->context, new_token);

        // Flush once we have a valid UTF-8 boundary
        // Simple check: no continuation bytes pending
        const unsigned char last = (unsigned char)cached.back();
        if ((last & 0x80) == 0 || (last & 0xC0) == 0x80) {
            result << cached;
            cached.clear();
        }
    }

    // Flush any remaining cached bytes
    if (!cached.empty()) {
        result << cached;
    }

    const std::string output = result.str();
    LOGi("nativeInfer: generated %d chars", (int)output.size());
    return env->NewStringUTF(output.c_str());
}

// ─── nativeFree ───────────────────────────────────────────────────────────────
//
// Signature (Kotlin):  external fun nativeFree(handle: Long)

extern "C"
JNIEXPORT void JNICALL
Java_com_reddysai_app_agent_LocalModelBrain_nativeFree(
        JNIEnv  * /*env*/,
        jobject  /*instance*/,
        jlong    handle) {

    LlamaSession *sess = session_from_handle(handle);
    if (!sess) return;

    LOGi("nativeFree: releasing session %p", (void*)sess);

    if (sess->sampler) { common_sampler_free(sess->sampler); sess->sampler = nullptr; }
    if (sess->context) { llama_free(sess->context);          sess->context = nullptr; }
    if (sess->model)   { llama_model_free(sess->model);      sess->model   = nullptr; }

    delete sess;
    llama_backend_free();
}
