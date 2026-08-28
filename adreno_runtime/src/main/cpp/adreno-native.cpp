#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

namespace {
constexpr const char* TAG = "LocalFluxAdreno";
std::mutex g_mutex;
sd_ctx_t* g_ctx = nullptr;
std::string g_ctx_key;

std::mutex g_log_mutex;
std::deque<std::string> g_logs;
constexpr size_t MAX_LOG_LINES = 1200;

std::atomic<int> g_phase{0};
std::atomic<int> g_step{0};
std::atomic<int> g_steps{0};
std::atomic<int64_t> g_started_ms{0};
std::atomic<bool> g_sampling_started{false};
std::atomic<bool> g_sampling_finished{false};

int64_t steady_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

int elapsed_ms() {
    const int64_t start = g_started_ms.load();
    if (start <= 0) return 0;
    return static_cast<int>(std::min<int64_t>(
            std::numeric_limits<int>::max(),
            std::max<int64_t>(0, steady_ms() - start)));
}

std::string from_jstring(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

const char* c_or_null(const std::string& value) {
    return value.empty() ? nullptr : value.c_str();
}

void push_log(enum sd_log_level_t level, const char* text) {
    if (!text || !*text) return;
    const char level_char = level == SD_LOG_ERROR ? 'E' :
                            level == SD_LOG_WARN ? 'W' :
                            level == SD_LOG_DEBUG ? 'D' : 'I';
    std::string raw(text);
    size_t pos = 0;
    while (pos <= raw.size()) {
        size_t end = raw.find('\n', pos);
        if (end == std::string::npos) end = raw.size();
        std::string line = raw.substr(pos, end - pos);
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) line.pop_back();
        if (!line.empty()) {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            while (g_logs.size() >= MAX_LOG_LINES) g_logs.pop_front();
            g_logs.emplace_back(std::string("[ADRENO/") + level_char + "] " + line);
        }
        if (end >= raw.size()) break;
        pos = end + 1;
    }
}

void log_cb(enum sd_log_level_t level, const char* text, void*) {
    int prio = ANDROID_LOG_INFO;
    if (level == SD_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == SD_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == SD_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;

    if (text) {
        std::string lower(text);
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });

        if (lower.find("generating image:") != std::string::npos) {
            g_sampling_started.store(true);
            g_sampling_finished.store(false);
            g_phase.store(3);
            g_step.store(0);
        }
        if (lower.find("sampling completed") != std::string::npos) {
            g_sampling_finished.store(true);
            g_step.store(g_steps.load());
            g_phase.store(4);
        }
        if (lower.find("decoding ") != std::string::npos ||
            lower.find("decode_first_stage") != std::string::npos) {
            g_phase.store(4);
        }
    }

    push_log(level, text);
    __android_log_print(prio, TAG, "%s", text ? text : "");
}

void progress_cb(int step, int steps, float, void*) {
    if (!g_sampling_started.load() || g_sampling_finished.load()) return;
    const int expected = g_steps.load();
    if (expected <= 0 || steps != expected) return;
    g_phase.store(3);
    g_step.store(std::max(0, std::min(step, expected)));
}

void reset_progress(int steps) {
    g_started_ms.store(steady_ms());
    g_phase.store(1);
    g_step.store(0);
    g_steps.store(std::max(0, steps));
    g_sampling_started.store(false);
    g_sampling_finished.store(false);
}

void configure_adreno_environment(bool q1_model, int runtime_mode) {
    // These are taken from the Duration AI Galaxy S25+ / Adreno 830 validation
    // branch. The central fix is watchdog-aware command submission: no single
    // Vulkan command buffer should monopolize KGSL for ~2 seconds.
    if (runtime_mode == 1) {
        setenv("GGML_VK_GFLOPS_PER_SUBMIT", "1", 1);
        setenv("GGML_VK_NODES_PER_SUBMIT", "8", 1);
        setenv("GGML_VK_SPLIT_BIG", "16", 1);
    } else if (q1_model) {
        setenv("GGML_VK_GFLOPS_PER_SUBMIT", "5", 1);
        setenv("GGML_VK_NODES_PER_SUBMIT", "32", 1);
        setenv("GGML_VK_SPLIT_BIG", "4", 1);
    } else {
        // Q4_K/Q4_0 carries more work per block than the validated q1_0 model.
        // Use a deliberately smaller submit budget until device telemetry proves
        // the specific phone can tolerate a more aggressive value.
        setenv("GGML_VK_GFLOPS_PER_SUBMIT", "2", 1);
        setenv("GGML_VK_NODES_PER_SUBMIT", "16", 1);
        setenv("GGML_VK_SPLIT_BIG", "16", 1);
    }
    setenv("GGML_VK_CONT_INPUT", "1", 1);
    setenv("GGML_VK_DISABLE_FUSION", "rms_norm_mul", 1);
    setenv("GGML_VK_FA_TUNE", "4,8,0,0", 1);
    setenv("GGML_VK_DISABLE_ASYNC", "1", 1);

    // Conservative allocator bounds for unified-memory Android GPUs.
    setenv("GGML_VK_SUBALLOCATION_BLOCK_SIZE", "268435456", 1);
    setenv("GGML_VK_FORCE_MAX_BUFFER_SIZE", "1073741824", 1);

    // Only engage the fork's q1-specific kernels for an actual q1_0 Bonsai DiT.
    if (q1_model) {
        setenv("GGML_VK_Q1_SHADER_SIZE", "medium", 1);
        setenv("GGML_VK_Q1_DIRECT", "1", 1);
        setenv("GGML_VK_Q1_DIRECT_Q8", "1", 1);
    } else {
        unsetenv("GGML_VK_Q1_SHADER_SIZE");
        unsetenv("GGML_VK_Q1_DIRECT");
        unsetenv("GGML_VK_Q1_DIRECT_Q8");
    }
}

bool is_q1_model(const std::string& path) {
    std::string lower = path;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return lower.find("q1_0") != std::string::npos ||
           lower.find("q1-0") != std::string::npos;
}

struct QwenStrategy {
    int min_tokens;
    bool early_layers;
    bool gpu;
    bool disk_requested;
    const char* label;
};

QwenStrategy qwen_strategy(int mode) {
    switch (std::max(0, std::min(16, mode))) {
        case 0: return {24,  false, false, false, "cpu-min24"};
        case 1: return {0,   false, false, false, "cpu-real-tokens"};
        case 2: return {24,  true,  false, false, "cpu-min24-early6-12-18"};
        case 3: return {32,  false, false, false, "cpu-min32"};
        case 4: return {64,  false, false, false, "cpu-min64"};
        case 5: return {128, false, false, false, "cpu-min128"};
        case 6: return {512, false, false, false, "cpu-full512"};
        case 7: return {24,  false, true,  false, "vulkan-min24"};
        case 8: return {32,  false, true,  false, "vulkan-min32"};
        case 9: return {512, false, true,  false, "vulkan-full512"};
        case 10:return {512, false, false, true,  "cpu-full512-disk-requested"};
        case 11:return {32,  false, true,  false, "vulkan-min32-legacy"};
        case 12:return {24,  false, true,  true,  "vulkan-min24-disk-requested"};
        case 13:return {24,  false, true,  false, "vulkan-min24-resident-legacy"};
        case 14:return {24,  false, false, false, "cpu-min24-resident-legacy"};
        case 15:return {24,  false, true,  true,  "vulkan-min24-disk-resident-legacy"};
        case 16:return {24,  false, true,  false, "vulkan-min24-streamsafe"};
        default:return {24, false, true, false, "vulkan-min24-streamsafe"};
    }
}

std::string make_key(const std::string& diffusion,
                     const std::string& vae,
                     const std::string& llm,
                     int runtime_mode,
                     int text_encoder_mode,
                     int threads) {
    return diffusion + "\n" + vae + "\n" + llm +
           "\nadreno-watchdog-v3\nruntime-" + std::to_string(runtime_mode) +
           "\nte-mode-" + std::to_string(text_encoder_mode) +
           "\nthreads-" + std::to_string(threads);
}

sd_ctx_t* ensure_context(JNIEnv* env,
                         const std::string& diffusion,
                         const std::string& vae,
                         const std::string& llm,
                         int runtime_mode,
                         int text_encoder_mode,
                         int requested_threads) {
    if (diffusion.empty() || vae.empty() || llm.empty()) {
        jclass cls = env->FindClass("java/lang/RuntimeException");
        if (cls) env->ThrowNew(cls, "Adreno FLUX runtime requires diffusion, FLUX.2 VAE and Qwen3 LLM paths.");
        return nullptr;
    }

    const int detected = std::max(2, sd_get_num_physical_cores());
    const int threads = requested_threads > 0
            ? std::max(2, std::min(8, requested_threads))
            : std::max(2, std::min(8, detected));

    const bool q1 = is_q1_model(diffusion);
    const QwenStrategy qwen = qwen_strategy(text_encoder_mode);
    configure_adreno_environment(q1, runtime_mode);

    char qwen_min_tokens[16];
    std::snprintf(qwen_min_tokens, sizeof(qwen_min_tokens), "%d", qwen.min_tokens);
    setenv("LOCALFLUX_KLEIN_MIN_TOKENS", qwen_min_tokens, 1);
    setenv("LOCALFLUX_KLEIN_EARLY_LAYERS", qwen.early_layers ? "1" : "0", 1);

    const std::string key = make_key(diffusion, vae, llm, runtime_mode, text_encoder_mode, threads);
    if (g_ctx && g_ctx_key == key) return g_ctx;

    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.diffusion_model_path = diffusion.c_str();
    p.vae_path = vae.c_str();
    p.llm_path = llm.c_str();
    p.n_threads = threads;
    p.enable_mmap = true;
    p.vae_decode_only = true;
    p.free_params_immediately = true;
    p.offload_params_to_cpu = true;
    p.keep_vae_on_cpu = true;
    p.lora_apply_mode = LORA_APPLY_AT_RUNTIME;

    // The validated Duration-AI layout keeps parameters in host RAM/mmap and
    // executes the DiT on Vulkan. Qwen can stay on Vulkan for the fast 24-token
    // path, with CPU available as the compatibility option.
    if (runtime_mode == 2) {
        p.backend = qwen.gpu
                ? "te=vulkan0,vae=cpu,diffusion=cpu"
                : "te=cpu,vae=cpu,diffusion=cpu";
    } else {
        p.backend = qwen.gpu
                ? "te=vulkan0,vae=cpu,diffusion=vulkan0"
                : "te=cpu,vae=cpu,diffusion=vulkan0";
    }
    // This fork exposes normal GGML backends only. Legacy disk-staging presets
    // are mapped to mmap-backed CPU parameter storage here.
    p.params_backend = "te=cpu,vae=cpu,diffusion=cpu";

    // Do not graph-cut this fork. Its proven Adreno strategy instead bounds
    // individual Vulkan submissions and splits the pathological projections.
    p.max_vram = 0.0f;
    p.flash_attn = false;
    p.diffusion_flash_attn = true;
    p.vae_conv_direct = false;

    // Duration's validated Adreno path keeps numerically sensitive tensors f32.
    p.tensor_type_rules = "norm=f32,_in.=f32,modulation=f32,final_layer=f32";

    {
        char runtime_line[512];
        std::snprintf(runtime_line, sizeof(runtime_line),
                      "Creating Duration-AI runtime: mode=%d, DiT=%s, qwen=%s, qwen_min=%d, states=%s, "
                      "params=CPU/mmap%s, watchdog=%s",
                      runtime_mode,
                      runtime_mode == 2 ? "CPU" : "Vulkan",
                      qwen.gpu ? "Vulkan" : "CPU",
                      qwen.min_tokens,
                      qwen.early_layers ? "6/12/18 EXP" : "9/18/27",
                      qwen.disk_requested ? " (disk preset mapped to CPU/mmap)" : "",
                      runtime_mode == 1 ? "ultra-safe 1-GFLOP/split16"
                                        : (q1 ? "validated q1 bounded-submit" : "conservative Q4 2-GFLOP/split16"));
        push_log(SD_LOG_INFO, runtime_line);
    }

    g_ctx = new_sd_ctx(&p);
    if (!g_ctx || !sd_ctx_supports_image_generation(g_ctx)) {
        if (g_ctx) {
            free_sd_ctx(g_ctx);
            g_ctx = nullptr;
        }
        jclass cls = env->FindClass("java/lang/RuntimeException");
        if (cls) env->ThrowNew(cls,
                "The Adreno-safe FLUX.2 runtime could not load this model stack. "
                "Use FLUX.2 Klein-compatible diffusion GGUF, FLUX.2 VAE and Qwen3 4B.");
        return nullptr;
    }

    g_ctx_key = key;
    return g_ctx;
}

void free_images(sd_image_t* images, int count) {
    if (!images) return;
    for (int i = 0; i < count; ++i) {
        free(images[i].data);
        images[i].data = nullptr;
    }
    free(images);
}

} // namespace

extern "C" jint JNI_OnLoad(JavaVM*, void*) {
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
    configure_adreno_environment(false, 0);
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_localflux_adreno_AdrenoNativeBridge_systemInfo(JNIEnv* env, jclass) {
    std::string info = sd_get_system_info() ? sd_get_system_info() : "Duration Adreno FLUX runtime";
    info += "\nRuntime fork: duration-ai/bonsai-cpp @ 932ff747";
    info += "\nAdreno policy: watchdog-bounded submits, split-big projection slicing, cont-input, rms_norm_mul fusion gate";
    return env->NewStringUTF(info.c_str());
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_localflux_adreno_AdrenoNativeBridge_generate(
        JNIEnv* env, jclass,
        jstring jDiffusion,
        jstring jVae,
        jstring jLlm,
        jstring jPrompt,
        jstring jNegative,
        jint width,
        jint height,
        jint steps,
        jfloat textCfg,
        jfloat distilledGuidance,
        jlong seed,
        jboolean vaeTiling,
        jobjectArray jLoraPaths,
        jfloatArray jLoraStrengths,
        jint runtimeMode,
        jint textEncoderMode,
        jint cpuThreads) {

    std::lock_guard<std::mutex> lock(g_mutex);
    reset_progress(steps);

    const std::string diffusion = from_jstring(env, jDiffusion);
    const std::string vae = from_jstring(env, jVae);
    const std::string llm = from_jstring(env, jLlm);
    const std::string prompt = from_jstring(env, jPrompt);
    const std::string negative = from_jstring(env, jNegative);

    if (prompt.empty()) {
        jclass cls = env->FindClass("java/lang/RuntimeException");
        if (cls) env->ThrowNew(cls, "Prompt is empty.");
        g_phase.store(7);
        return nullptr;
    }

    sd_set_progress_callback(progress_cb, nullptr);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);

    const int safe_runtime_mode = std::max(0, std::min(2, static_cast<int>(runtimeMode)));
    const int safe_text_encoder_mode = std::max(0, std::min(16, static_cast<int>(textEncoderMode)));
    sd_ctx_t* ctx = ensure_context(env, diffusion, vae, llm,
                                   safe_runtime_mode, safe_text_encoder_mode, cpuThreads);
    if (!ctx || env->ExceptionCheck()) {
        g_phase.store(7);
        return nullptr;
    }

    g_phase.store(2);

    std::vector<std::string> lora_paths;
    std::vector<float> lora_strengths;
    if (jLoraPaths && jLoraStrengths) {
        const jsize path_count = env->GetArrayLength(jLoraPaths);
        const jsize strength_count = env->GetArrayLength(jLoraStrengths);
        const jsize count = std::min(path_count, strength_count);
        std::vector<jfloat> strengths(static_cast<size_t>(count));
        if (count > 0) env->GetFloatArrayRegion(jLoraStrengths, 0, count, strengths.data());
        for (jsize i = 0; i < count; ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(jLoraPaths, i));
            std::string path = from_jstring(env, value);
            env->DeleteLocalRef(value);
            if (path.empty()) continue;
            const float strength = std::max(-2.0f, std::min(2.0f, static_cast<float>(strengths[i])));
            if (std::abs(strength) < 0.0001f) continue;
            lora_paths.push_back(std::move(path));
            lora_strengths.push_back(strength);
        }
    }

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt = prompt.c_str();
    gp.negative_prompt = negative.empty() ? "" : negative.c_str();
    gp.width = width;
    gp.height = height;
    gp.seed = static_cast<int64_t>(seed);
    gp.batch_count = 1;
    gp.sample_params.sample_steps = steps;
    gp.sample_params.guidance.txt_cfg = textCfg;
    gp.sample_params.guidance.distilled_guidance = distilledGuidance;
    gp.vae_tiling_params.enabled = vaeTiling == JNI_TRUE;

    if (gp.sample_params.sample_method == SAMPLE_METHOD_COUNT) {
        gp.sample_params.sample_method = sd_get_default_sample_method(ctx);
    }
    if (gp.sample_params.scheduler == SCHEDULER_COUNT) {
        gp.sample_params.scheduler = sd_get_default_scheduler(ctx, gp.sample_params.sample_method);
    }

    std::vector<sd_lora_t> loras;
    loras.reserve(lora_paths.size());
    for (size_t i = 0; i < lora_paths.size(); ++i) {
        sd_lora_t item{};
        item.path = lora_paths[i].c_str();
        item.multiplier = lora_strengths[i];
        item.is_high_noise = false;
        loras.push_back(item);
    }
    gp.loras = loras.empty() ? nullptr : loras.data();
    gp.lora_count = static_cast<uint32_t>(loras.size());

    sd_image_t* images = generate_image(ctx, &gp);
    if (!images || !images[0].data) {
        free_images(images, 1);
        g_phase.store(7);
        jclass cls = env->FindClass("java/lang/RuntimeException");
        if (cls) env->ThrowNew(cls,
                "Adreno-safe generation failed. The worker process survived; inspect its native log for the exact Vulkan/driver failure.");
        return nullptr;
    }

    const sd_image_t& im = images[0];
    if (im.width == 0 || im.height == 0 || im.channel < 3) {
        free_images(images, 1);
        g_phase.store(7);
        jclass cls = env->FindClass("java/lang/RuntimeException");
        if (cls) env->ThrowNew(cls, "Adreno runtime returned an invalid image.");
        return nullptr;
    }

    const size_t n = static_cast<size_t>(im.width) * static_cast<size_t>(im.height);
    std::vector<jint> pixels(n);
    for (size_t i = 0; i < n; ++i) {
        const uint8_t r = im.data[i * im.channel + 0];
        const uint8_t g = im.data[i * im.channel + 1];
        const uint8_t b = im.data[i * im.channel + 2];
        const uint8_t a = im.channel >= 4 ? im.data[i * im.channel + 3] : 255;
        pixels[i] = static_cast<jint>(
                (static_cast<uint32_t>(a) << 24) |
                (static_cast<uint32_t>(r) << 16) |
                (static_cast<uint32_t>(g) << 8) |
                static_cast<uint32_t>(b));
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(n));
    if (out) env->SetIntArrayRegion(out, 0, static_cast<jsize>(n), pixels.data());
    free_images(images, 1);
    g_phase.store(5);
    g_step.store(steps);
    return out;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_localflux_adreno_AdrenoNativeBridge_drainLogs(JNIEnv* env, jclass) {
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        while (!g_logs.empty()) {
            out += g_logs.front();
            out.push_back('\n');
            g_logs.pop_front();
        }
    }
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_localflux_adreno_AdrenoNativeBridge_progressSnapshot(JNIEnv* env, jclass) {
    jint values[4] = {
        static_cast<jint>(g_phase.load()),
        static_cast<jint>(g_step.load()),
        static_cast<jint>(g_steps.load()),
        static_cast<jint>(elapsed_ms())
    };
    jintArray out = env->NewIntArray(4);
    if (out) env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_localflux_adreno_AdrenoNativeBridge_unload(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
}
