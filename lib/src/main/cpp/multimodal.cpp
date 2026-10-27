/**
 * Multimodal inference via libmtmd.
 *
 * This file is completely independent of ai_chat.cpp.
 * It loads its own model + mmproj and manages its own context lifecycle.
 * Existing text-only inference in ai_chat.cpp is NOT affected.
 */

#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <unistd.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"

// ──────────────────── Multimodal Resources ────────────────────

static llama_model            * g_mm_model   = nullptr;
static llama_context          * g_mm_lctx    = nullptr;
static mtmd_context           * g_mm_vision  = nullptr;
static common_sampler         * g_mm_sampler = nullptr;
static common_batch           * g_mm_batch   = nullptr; // for token generation only
static common_chat_templates_ptr g_mm_tmpls;

static const llama_vocab      * g_mm_vocab   = nullptr;
static std::vector<common_chat_msg> g_mm_chat_history;
static llama_pos               g_mm_n_past   = 0;
static bool                    g_mm_backend_initialized = false;

static std::string             g_mm_cached_token_chars;
static std::ostringstream      g_mm_assistant_ss;
static volatile bool           g_mm_cancel = false;

constexpr int  MM_N_THREADS_MIN    = 1;
constexpr int  MM_N_THREADS_MAX    = 2;
constexpr int  MM_N_THREADS_HEADROOM = 2;
constexpr int  MM_CONTEXT_SIZE     = 8192;
constexpr int  MM_BATCH_SIZE       = 512;
constexpr float MM_SAMPLER_TEMP    = 0.3f;

// 可配置推理参数（通过 JNI 设置）
static int   g_mm_config_context_size = MM_CONTEXT_SIZE;
static float g_mm_config_temperature  = MM_SAMPLER_TEMP;
static int   g_mm_config_n_threads    = 0; // 0 = 自动计算

// ──────────────────── Internal Helpers ────────────────────

static void mm_init_backend(const char * nativeLibDir) {
    if (g_mm_backend_initialized) return;
    LOGi("[mtmd] Loading backends from %s", nativeLibDir);
    ggml_backend_load_all_from_path(nativeLibDir);
    llama_backend_init();
    g_mm_backend_initialized = true;
}

static void mm_cleanup_resources() {
    if (g_mm_sampler)  { common_sampler_free(g_mm_sampler); g_mm_sampler = nullptr; }
    g_mm_tmpls.reset();
    if (g_mm_batch)    { delete g_mm_batch; g_mm_batch = nullptr; }
    if (g_mm_vision)   { mtmd_free(g_mm_vision); g_mm_vision = nullptr; }
    if (g_mm_lctx)     { llama_free(g_mm_lctx); g_mm_lctx = nullptr; }
    if (g_mm_model)    { llama_model_free(g_mm_model); g_mm_model = nullptr; }
    g_mm_vocab = nullptr;
}

static void mm_reset_conversation() {
    g_mm_chat_history.clear();
    g_mm_n_past = 0;
    g_mm_cached_token_chars.clear();
    g_mm_assistant_ss.str("");
    if (g_mm_lctx) {
        llama_memory_clear(llama_get_memory(g_mm_lctx), true);
    }
}

static bool mm_is_valid_utf8(const char * str) {
    if (!str) return true;
    const auto * bytes = (const unsigned char *) str;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00)      { num = 1; }
        else if ((*bytes & 0xE0) == 0xC0)  { num = 2; }
        else if ((*bytes & 0xF0) == 0xE0)  { num = 3; }
        else if ((*bytes & 0xF8) == 0xF0)  { num = 4; }
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

// ──────────────────── JNI: loadMultimodal ────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_multimodal_MultimodalEngineImpl_loadMultimodal(
        JNIEnv * env, jobject /*unused*/,
        jstring jnative_lib_dir,
        jstring jmodel_path,
        jstring jmmproj_path
) {
    try {
        const auto * nativeLibDir = env->GetStringUTFChars(jnative_lib_dir, nullptr);
        const auto * model_path   = env->GetStringUTFChars(jmodel_path, nullptr);
        const auto * mmproj_path  = env->GetStringUTFChars(jmmproj_path, nullptr);

        // 1. Init backend (idempotent)
        mm_init_backend(nativeLibDir);
        env->ReleaseStringUTFChars(jnative_lib_dir, nativeLibDir);

        // 2. Cleanup previous session
        mm_cleanup_resources();

        // 3. Load text model
        LOGi("[mtmd] Loading model: %s", model_path);
        auto model_params = llama_model_default_params();
        g_mm_model = llama_model_load_from_file(model_path, model_params);
        env->ReleaseStringUTFChars(jmodel_path, model_path);
        if (!g_mm_model) {
            LOGe("[mtmd] Failed to load model");
            mm_cleanup_resources();
            return 1;
        }
        g_mm_vocab = llama_model_get_vocab(g_mm_model);

        // 4. Create llama context
        int n_threads;
        if (g_mm_config_n_threads > 0) {
            n_threads = g_mm_config_n_threads;
        } else {
            n_threads = std::max(MM_N_THREADS_MIN,
                std::min(MM_N_THREADS_MAX,
                    (int) sysconf(_SC_NPROCESSORS_ONLN) - MM_N_THREADS_HEADROOM));
        }
        LOGi("[mtmd] Using %d threads, context size %d", n_threads, g_mm_config_context_size);

        auto ctx_params = llama_context_default_params();
        ctx_params.n_ctx        = g_mm_config_context_size;
        ctx_params.n_batch      = MM_BATCH_SIZE;
        ctx_params.n_ubatch     = MM_BATCH_SIZE;
        ctx_params.n_threads    = n_threads;
        ctx_params.n_threads_batch = n_threads;

        g_mm_lctx = llama_init_from_model(g_mm_model, ctx_params);
        if (!g_mm_lctx) {
            LOGe("[mtmd] Failed to create context");
            mm_cleanup_resources();
            return 2;
        }

        // 5. Create mtmd vision context
        auto mparams = mtmd_context_params_default();
        mparams.use_gpu       = false;  // Android: CPU only for vision encoder
        mparams.print_timings = true;
        mparams.n_threads     = n_threads;
        mparams.warmup        = false;

        LOGi("[mtmd] Loading mmproj: %s", mmproj_path);
        g_mm_vision = mtmd_init_from_file(mmproj_path, g_mm_model, mparams);
        env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);
        if (!g_mm_vision) {
            LOGe("[mtmd] Failed to load mmproj");
            mm_cleanup_resources();
            return 3;
        }

        if (!mtmd_support_vision(g_mm_vision)) {
            LOGe("[mtmd] Model does not support vision input");
            mm_cleanup_resources();
            return 4;
        }

        // 6. Create sampler + batch
        auto sparams = common_params_sampling();
        sparams.temp = g_mm_config_temperature;
        g_mm_sampler = common_sampler_init(g_mm_model, sparams);
        g_mm_batch   = new common_batch(g_mm_lctx);

        // 7. Chat templates
        g_mm_tmpls = common_chat_templates_init(g_mm_model, "");
        g_mm_chat_history.clear();
        g_mm_n_past = 0;
        g_mm_cancel = false;

        LOGi("[mtmd] Multimodal model ready!");
        return 0;

    } catch (const std::exception & e) {
        LOGe("[mtmd] loadMultimodal exception: %s", e.what());
        mm_cleanup_resources();
        return -1;
    }
}

// ──────────────────── JNI: processMultimodalPrompt ────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_multimodal_MultimodalEngineImpl_processMultimodalPrompt(
        JNIEnv * env, jobject /*unused*/,
        jstring jtext,
        jobjectArray jimage_paths
) {
    if (!g_mm_vision || !g_mm_lctx || !g_mm_model) {
        LOGe("[mtmd] Model not loaded");
        return -1;
    }

    // Reset per-generation state
    g_mm_cached_token_chars.clear();
    g_mm_assistant_ss.str("");
    g_mm_cancel = false;

    const auto * text = env->GetStringUTFChars(jtext, nullptr);
    std::string user_text(text);
    env->ReleaseStringUTFChars(jtext, text);

    int n_images = env->GetArrayLength(jimage_paths);
    LOGi("[mtmd] Processing prompt: text='%s', images=%d", user_text.c_str(), n_images);

    // 1. Build user message content with media markers
    const std::string marker = mtmd_default_marker();
    std::string user_content;
    for (int i = 0; i < n_images; i++) {
        user_content += marker;
    }
    user_content += user_text;

    // 2. Format via chat template
    common_chat_msg msg;
    msg.role    = "user";
    msg.content = user_content;

    bool add_bos = g_mm_chat_history.empty();
    std::string formatted;
    try {
        formatted = common_chat_format_single(
            g_mm_tmpls.get(), g_mm_chat_history, msg, true, /* use_jinja */ true);
    } catch (const std::exception & e) {
        LOGe("[mtmd] Chat template formatting failed: %s", e.what());
        formatted = user_content; // fallback to raw content with media markers
    }

    // 3. Split formatted text on media markers
    std::vector<std::string> segments;
    size_t start = 0, pos;
    while ((pos = formatted.find(marker, start)) != std::string::npos) {
        segments.push_back(formatted.substr(start, pos - start));
        start = pos + marker.size();
    }
    segments.push_back(formatted.substr(start));

    // 4. Load images into bitmaps
    mtmd_helper_init_opt init_opt = mtmd_helper_init_opt_default();
    std::vector<mtmd::bitmap> bitmaps;
    for (int i = 0; i < n_images; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(jimage_paths, i);
        const char * path = env->GetStringUTFChars(jstr, nullptr);
        LOGi("[mtmd] Loading image: %s", path);

        auto res = mtmd_helper_bitmap_init_from_file(g_mm_vision, path, false, init_opt);
        env->ReleaseStringUTFChars(jstr, path);
        if (!res.bitmap) {
            LOGe("[mtmd] Failed to load image: %d", i);
            return 2;
        }
        bitmaps.emplace_back(res.bitmap);
    }

    // 5. Build input parts (interleaving text + image)
    std::vector<mtmd_input_text> texts(segments.size());
    std::vector<mtmd_input_part> parts;
    for (size_t i = 0; i < segments.size(); i++) {
        texts[i] = { segments[i].data(), segments[i].size(), false, true };
        parts.push_back({ &texts[i], nullptr });
        if (i < bitmaps.size()) {
            parts.push_back({ nullptr, bitmaps[i].ptr.get() });
        }
    }
    std::vector<const mtmd_input_part *> parts_ptr;
    for (const auto & p : parts) {
        parts_ptr.push_back(&p);
    }

    // 6. Tokenize
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    int32_t res = mtmd_tokenize_from_parts(
        g_mm_vision, chunks.ptr.get(),
        parts_ptr.data(), parts_ptr.size(), add_bos);
    if (res != 0) {
        LOGe("[mtmd] Tokenize failed: %d", res);
        return 3;
    }

    // 7. Evaluate all chunks (text decode + image encode/decode)
    llama_pos new_n_past = g_mm_n_past;
    res = mtmd_helper_eval_chunks(
        g_mm_vision, g_mm_lctx, chunks.ptr.get(),
        g_mm_n_past, 0, MM_BATCH_SIZE, true, &new_n_past);
    if (res != 0) {
        LOGe("[mtmd] Eval chunks failed: %d", res);
        return 4;
    }
    g_mm_n_past = new_n_past;

    // 8. Add user message to chat history
    g_mm_chat_history.push_back(msg);

    LOGi("[mtmd] Prompt processed, n_past=%d, ready for generation", (int) g_mm_n_past);
    return 0;
}

// ──────────────────── JNI: generateNextMultimodalToken ────────────────────

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_multimodal_MultimodalEngineImpl_generateNextMultimodalToken(
        JNIEnv * env, jobject /*unused*/
) {
    if (!g_mm_lctx || !g_mm_sampler) return nullptr;

    // Context overflow protection
    if (g_mm_n_past >= g_mm_config_context_size - 4) {
        LOGw("[mtmd] Context full");
        return nullptr;
    }
    if (g_mm_cancel) {
        LOGi("[mtmd] Generation cancelled");
        return nullptr;
    }

    // 1. Sample next token
    const auto new_token_id = common_sampler_sample(g_mm_sampler, g_mm_lctx, -1);
    common_sampler_accept(g_mm_sampler, new_token_id, true);

    // 2. Check end-of-generation
    if (llama_vocab_is_eog(g_mm_vocab, new_token_id)) {
        LOGd("[mtmd] EOG token: %d", new_token_id);
        // Save assistant message to chat history
        g_mm_chat_history.push_back({"assistant", g_mm_assistant_ss.str()});
        g_mm_assistant_ss.str("");
        return nullptr;
    }

    // 3. Decode token and feed back to context
    g_mm_batch->clear();
    g_mm_batch->add(new_token_id, g_mm_n_past++, 0, true);
    if (llama_process(g_mm_lctx, LLAMA_PROCESS_TYPE_DECODE, g_mm_batch->get()) != 0) {
        LOGe("[mtmd] llama_process failed");
        return nullptr;
    }

    // 4. Convert to text
    auto new_token_chars = common_token_to_piece(g_mm_lctx, new_token_id);
    g_mm_cached_token_chars += new_token_chars;

    // 5. Return valid UTF-8 string
    jstring result = nullptr;
    if (mm_is_valid_utf8(g_mm_cached_token_chars.c_str())) {
        result = env->NewStringUTF(g_mm_cached_token_chars.c_str());
        g_mm_assistant_ss << g_mm_cached_token_chars;
        g_mm_cached_token_chars.clear();
    } else {
        result = env->NewStringUTF("");
    }
    return result;
}

// ──────────────────── JNI: setMultimodalParams ────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_multimodal_MultimodalEngineImpl_setMultimodalParams(
        JNIEnv *, jobject, jfloat temperature, jint contextSize, jint nThreads) {
    g_mm_config_temperature = temperature;
    g_mm_config_context_size = (contextSize > 0) ? contextSize : MM_CONTEXT_SIZE;
    g_mm_config_n_threads = nThreads; // 0 = auto
    LOGi("[mtmd] Params updated: temp=%.2f, ctx=%d, threads=%d",
         g_mm_config_temperature, g_mm_config_context_size, g_mm_config_n_threads);
}

// ──────────────────── JNI: stopMultimodalGeneration ────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_multimodal_MultimodalEngineImpl_stopMultimodalGeneration(
        JNIEnv *, jobject /*unused*/
) {
    LOGi("[mtmd] Stop generation requested");
    g_mm_cancel = true;
}

// ──────────────────── JNI: resetMultimodalConversation ────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_multimodal_MultimodalEngineImpl_resetMultimodalConversation(
        JNIEnv *, jobject /*unused*/
) {
    LOGi("[mtmd] Resetting conversation history");
    mm_reset_conversation();
    LOGi("[mtmd] Conversation reset");
}

// ──────────────────── JNI: cleanUpMultimodal ────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_multimodal_MultimodalEngineImpl_cleanUpMultimodal(
        JNIEnv *, jobject /*unused*/
) {
    LOGi("[mtmd] Cleaning up multimodal resources");
    g_mm_cancel = true;
    mm_reset_conversation();
    mm_cleanup_resources();
    LOGi("[mtmd] Cleanup done");
}
