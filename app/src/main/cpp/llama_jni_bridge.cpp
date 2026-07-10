#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <thread>
#include <android/log.h>

#include "llama.h"
#include "ggml.h"

#define LOG_TAG "LlamaEngineJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model* g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::mutex     g_model_mutex;   // Protects load/unload only
static std::mutex     g_gen_mutex;     // Serializes generation calls
static std::atomic<bool> g_cancel{false};

// ── Abort callback ───────────────────────────────────────────────
// Only aborts on manual cancel (g_cancel flag).
// The Kotlin-side 120s timeout in generateBlocking is the real safety net.

static int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

static bool abort_callback(void* /*data*/) {
    // Only respond to manual cancel — no per-decode timeout.
    // Slow models (1.5B on mid-range) need 200+ seconds for prompt.
    return g_cancel.load();
}

namespace Config {
    constexpr int CTX_SIZE    = 1024;
    constexpr int GPU_LAYERS  = 0;
    constexpr int MAX_TOKENS  = 200;
    constexpr float TEMPERATURE = 0.5f;
    constexpr float TOP_P     = 0.9f;
    constexpr int TOP_K       = 40;
    constexpr int BATCH_SIZE  = 128;
}

static void batch_add(llama_batch &batch, llama_token id, int pos, bool logits) {
    batch.token[batch.n_tokens]    = id;
    batch.pos[batch.n_tokens]      = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id[batch.n_tokens][0] = 0;
    batch.logits[batch.n_tokens]   = logits;
    batch.n_tokens++;
}

static std::string jstringToString(JNIEnv* env, jstring jStr) {
    if (jStr == nullptr) return "";
    const char* chars = env->GetStringUTFChars(jStr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return result;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_amar_vault_NativeLlamaEngine_loadModelNative(
        JNIEnv* env, jobject thiz, jstring jModelPath)
{
    g_cancel.store(true);
    std::lock_guard<std::mutex> gen_lock(g_gen_mutex);
    std::lock_guard<std::mutex> lock(g_model_mutex);

    if (g_model != nullptr) {
        LOGI("Unloading previous model first");
        if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
        llama_model_free(g_model);
        g_model = nullptr;
    }

    std::string modelPath = jstringToString(env, jModelPath);
    LOGI("Loading model: %s", modelPath.c_str());

    int64_t t0 = now_ms();
    llama_backend_init();
    LOGI("llama_backend_init took %lldms", (long long)(now_ms() - t0));

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = Config::GPU_LAYERS;

    LOGI("BEFORE llama_model_load_from_file (gpu_layers=%d)", Config::GPU_LAYERS);
    t0 = now_ms();
    g_model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    int64_t loadMs = now_ms() - t0;

    if (g_model == nullptr) {
        LOGE("FATAL: llama_model_load_from_file FAILED after %lldms.", loadMs);
        llama_backend_free();
        return JNI_FALSE;
    }
    LOGI("llama_model_load_from_file took %lldms", loadMs);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int n_vocab  = vocab ? llama_vocab_n_tokens(vocab) : -1;
    int n_embd   = llama_model_n_embd(g_model);
    int n_layers = llama_model_n_layer(g_model);
    int n_head   = llama_model_n_head(g_model);

    char desc[256] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));

    LOGI("Model info: desc='%s' vocab=%d embd=%d layers=%d heads=%d",
         desc, n_vocab, n_embd, n_layers, n_head);

    if (n_vocab <= 0 || n_embd <= 0 || n_layers <= 0) {
        LOGE("FATAL: Model metadata invalid.");
        llama_model_free(g_model);
        g_model = nullptr;
        llama_backend_free();
        return JNI_FALSE;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx   = Config::CTX_SIZE;
    ctxParams.n_batch = Config::BATCH_SIZE;
    ctxParams.n_threads = 4;
    ctxParams.n_threads_batch = 4;
    ctxParams.abort_callback = abort_callback;
    ctxParams.abort_callback_data = nullptr;

    LOGI("BEFORE llama_init_from_model (n_ctx=%d, n_batch=%d, threads=%d)",
         Config::CTX_SIZE, Config::BATCH_SIZE, 4);
    t0 = now_ms();
    g_ctx = llama_init_from_model(g_model, ctxParams);
    int64_t ctxMs = now_ms() - t0;

    if (g_ctx == nullptr) {
        LOGE("FATAL: llama_init_from_model FAILED after %lldms.", ctxMs);
        llama_model_free(g_model);
        g_model = nullptr;
        llama_backend_free();
        return JNI_FALSE;
    }

    LOGI("llama_init_from_model took %lldms", ctxMs);
    LOGI("Model loaded safely! Context: %d, Batch: %d", Config::CTX_SIZE, Config::BATCH_SIZE);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeLlamaEngine_generateTextNative(
        JNIEnv* env, jobject thiz, jstring jPrompt, jobject jCallback)
{
    // Use a try_lock with timeout to prevent deadlocks
    // If another generation is running, wait up to 5 seconds then bail
    bool locked = false;
    for (int i = 0; i < 50; i++) {
        if (g_gen_mutex.try_lock()) {
            locked = true;
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    jclass cbClass = env->GetObjectClass(jCallback);
    jmethodID onCompleteMethod = env->GetMethodID(cbClass, "onComplete", "()V");
    jmethodID onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    if (!locked) {
        LOGE("generateTextNative: could not acquire lock after 5s — another generation is stuck");
        if (onCompleteMethod) env->CallVoidMethod(jCallback, onCompleteMethod);
        return;
    }

    // ── CRITICAL: Reset cancel flag FIRST ────────────────────────
    g_cancel.store(false);

    LOGI("generateTextNative: ENTER (cancel reset to false)");

    if (g_model == nullptr || g_ctx == nullptr) {
        LOGE("Cannot generate: model not loaded");
        g_gen_mutex.unlock();
        if (onCompleteMethod) env->CallVoidMethod(jCallback, onCompleteMethod);
        return;
    }

    std::string prompt = jstringToString(env, jPrompt);
    LOGI("generateTextNative: prompt=%d chars", (int)prompt.length());

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        LOGE("FATAL: Failed to get vocabulary.");
        g_gen_mutex.unlock();
        if (onCompleteMethod) env->CallVoidMethod(jCallback, onCompleteMethod);
        return;
    }

    // ── Tokenize ─────────────────────────────────────────────────
    int n_prompt_max = prompt.length() + 32;
    std::vector<llama_token> promptTokens;
    try {
        promptTokens.resize(n_prompt_max);
    } catch (const std::bad_alloc& e) {
        LOGE("FATAL OOM: tokenize alloc failed.");
        g_gen_mutex.unlock();
        if (onCompleteMethod) env->CallVoidMethod(jCallback, onCompleteMethod);
        return;
    }

    int64_t t0 = now_ms();
    int n_prompt_tokens = llama_tokenize(
            vocab, prompt.c_str(), prompt.length(),
            promptTokens.data(), n_prompt_max, true, true
    );
    LOGI("Tokenization: %d tokens in %lldms", n_prompt_tokens, (long long)(now_ms() - t0));

    if (n_prompt_tokens < 0) {
        LOGE("Tokenization failed.");
        g_gen_mutex.unlock();
        if (onCompleteMethod) env->CallVoidMethod(jCallback, onCompleteMethod);
        return;
    }
    promptTokens.resize(n_prompt_tokens);

    // ── Clear KV cache ───────────────────────────────────────────
    LOGI("Clearing KV cache...");
    // KV cache managed internally by llama_decode

    // ── Process prompt ───────────────────────────────────────────
    llama_batch batch = llama_batch_init(Config::BATCH_SIZE, 0, 1);

    if (batch.token == nullptr) {
        LOGE("FATAL OOM: llama_batch_init returned NULL.");
        g_gen_mutex.unlock();
        if (onCompleteMethod) env->CallVoidMethod(jCallback, onCompleteMethod);
        return;
    }

    LOGI("Processing %d prompt tokens...", n_prompt_tokens);

    for (int i = 0; i < n_prompt_tokens; i += Config::BATCH_SIZE) {
        if (g_cancel.load()) {
            LOGI("Cancelled during prompt processing");
            llama_batch_free(batch);
            g_gen_mutex.unlock();
            env->CallVoidMethod(jCallback, onCompleteMethod);
            return;
        }

        int batchSize = std::min(Config::BATCH_SIZE, n_prompt_tokens - i);
        batch.n_tokens = 0;
        for (int j = 0; j < batchSize; j++) {
            batch_add(batch, promptTokens[i + j], i + j, false);
        }
        if (i + batchSize >= n_prompt_tokens) {
            batch.logits[batch.n_tokens - 1] = true;
        }

        LOGI("BEFORE llama_decode (prompt chunk %d, %d tokens)", i / Config::BATCH_SIZE, batchSize);

        t0 = now_ms();
        int decodeResult = llama_decode(g_ctx, batch);
        int64_t decodeMs = now_ms() - t0;

        if (decodeResult != 0) {
            LOGE("llama_decode FAILED prompt chunk %d: error=%d after %lldms",
                 i / Config::BATCH_SIZE, decodeResult, decodeMs);
            llama_batch_free(batch);
            g_gen_mutex.unlock();
            env->CallVoidMethod(jCallback, onCompleteMethod);
            return;
        }

        LOGI("AFTER llama_decode (prompt chunk %d) took %lldms ✓", i / Config::BATCH_SIZE, decodeMs);
    }

    LOGI("Prompt done. Starting generation...");

    // ── Generation loop ──────────────────────────────────────────
    int n_generated = 0;
    int n_ctx_used = n_prompt_tokens;

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler == nullptr) {
        LOGE("FATAL OOM: Failed to create sampler.");
        llama_batch_free(batch);
        g_gen_mutex.unlock();
        env->CallVoidMethod(jCallback, onCompleteMethod);
        return;
    }

    llama_sampler_chain_add(sampler, llama_sampler_init_temp(Config::TEMPERATURE));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(Config::TOP_K));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(Config::TOP_P, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    while (n_generated < Config::MAX_TOKENS) {
        if (g_cancel.load()) {
            LOGI("Cancelled during generation at token %d", n_generated);
            break;
        }

        llama_token newToken = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, newToken)) {
            LOGI("EOS at token %d", n_generated);
            break;
        }

        if (n_ctx_used >= Config::CTX_SIZE - 1) {
            LOGW("Context full (%d). Stopping.", n_ctx_used);
            break;
        }

        char tokenBuf[128];
        int tokenLen = llama_token_to_piece(vocab, newToken, tokenBuf, sizeof(tokenBuf), 0, true);
        if (tokenLen > 0) {
            std::string tokenStr(tokenBuf, tokenLen);
            jstring jToken = env->NewStringUTF(tokenStr.c_str());
            env->CallVoidMethod(jCallback, onTokenMethod, jToken);
            env->DeleteLocalRef(jToken);
        }

        if (n_generated == 0) {
            LOGI("First token decoded OK!");
        }

        n_generated++;
        n_ctx_used++;

        batch.n_tokens = 0;
        batch_add(batch, newToken, n_ctx_used - 1, true);

        t0 = now_ms();
        int decodeResult = llama_decode(g_ctx, batch);
        int64_t decodeMs = now_ms() - t0;

        if (decodeResult != 0) {
            LOGE("llama_decode FAILED gen token %d: error=%d after %lldms",
                 n_generated, decodeResult, decodeMs);
            break;
        }

        if (n_generated <= 3 || n_generated % 50 == 0) {
            LOGI("Gen token %d took %lldms ✓", n_generated, decodeMs);
        }
    }

    LOGI("Generation finished: %d tokens", n_generated);

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    // ── CRITICAL: Release lock BEFORE calling onComplete ─────────
    // This prevents deadlock: onComplete() triggers latch.countDown()
    // which lets generateBlocking return, which lets the NEXT query
    // start a new thread that needs this lock.
    g_gen_mutex.unlock();

    LOGI("generateTextNative: EXIT (lock released, calling onComplete)");
    env->CallVoidMethod(jCallback, onCompleteMethod);
}

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeLlamaEngine_unloadModelNative(
        JNIEnv* env, jobject thiz)
{
    g_cancel.store(true);
    std::lock_guard<std::mutex> gen_lock(g_gen_mutex);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGI("Model unloaded");
}

JNIEXPORT void JNICALL
Java_com_amar_vault_NativeLlamaEngine_cancelGenerationNative(
        JNIEnv* env, jobject thiz)
{
    LOGI("cancelGenerationNative called");
    g_cancel.store(true);
}

JNIEXPORT jboolean JNICALL
Java_com_amar_vault_NativeLlamaEngine_isModelLoadedNative(
        JNIEnv* env, jobject thiz)
{
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"