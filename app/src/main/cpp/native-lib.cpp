#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <deque>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "stable-diffusion.h"

namespace {
constexpr const char* TAG = "LocalFluxNative";
std::mutex g_generation_mutex;
std::mutex g_ctx_mutex;
sd_ctx_t* g_ctx = nullptr;
std::string g_ctx_key;
std::once_flag g_log_once;
std::mutex g_log_buffer_mutex;
std::deque<std::string> g_log_buffer;
constexpr size_t MAX_CONSOLE_LOG_LINES = 900;
size_t g_dropped_log_lines = 0;

enum ProgressPhase {
    PHASE_IDLE = 0,
    PHASE_LOADING = 1,
    PHASE_CONDITIONING = 2,
    PHASE_SAMPLING = 3,
    PHASE_DECODING = 4,
    PHASE_COMPLETE = 5,
    PHASE_CANCELLED = 6,
    PHASE_ERROR = 7
};

std::atomic<int> g_phase{PHASE_IDLE};
std::atomic<int> g_step{0};
std::atomic<int> g_steps{0};
std::atomic<int64_t> g_started_ms{0};
std::atomic<int> g_preview_version{0};
std::atomic<int> g_preview_step{0};
std::atomic<int> g_expected_sample_steps{0};
std::atomic<bool> g_sampling_started{false};
std::mutex g_preview_mutex;
std::vector<jint> g_preview_argb;
int g_preview_width = 0;
int g_preview_height = 0;

int64_t steady_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

int elapsed_ms() {
    const int64_t start = g_started_ms.load();
    if (start <= 0) return 0;
    const int64_t elapsed = std::max<int64_t>(0, steady_ms() - start);
    return static_cast<int>(std::min<int64_t>(elapsed, std::numeric_limits<int>::max()));
}

void set_phase(int phase, int step = 0, int steps = 0) {
    g_step.store(std::max(0, step));
    g_steps.store(std::max(0, steps));
    g_phase.store(phase);
}

void reset_progress_state() {
    g_started_ms.store(steady_ms());
    set_phase(PHASE_LOADING);
    {
        std::lock_guard<std::mutex> lock(g_preview_mutex);
        g_preview_argb.clear();
        g_preview_width = 0;
        g_preview_height = 0;
    }
    g_preview_step.store(0);
    g_preview_version.store(0);
    g_sampling_started.store(false);
}

void load_progress_cb(int step, int steps, float, void*) {
    const int safe_steps = std::max(0, steps);
    const int safe_step = std::max(0, std::min(step, safe_steps > 0 ? safe_steps : step));
    set_phase(PHASE_LOADING, safe_step, safe_steps);
}

void progress_cb(int step, int steps, float, void*) {
    const int safe_steps = std::max(0, steps);
    const int safe_step = std::max(0, std::min(step, safe_steps > 0 ? safe_steps : step));
    const int expected = g_expected_sample_steps.load();

    if (g_sampling_started.load()) {
        if (expected > 0 && safe_steps == expected) {
            set_phase(PHASE_SAMPLING, safe_step, safe_steps);
        } else {
            // Once stable-diffusion.cpp has emitted "generating image:", later
            // tensor-loading callbacks belong to diffusion staging, not Qwen.
            // Do not regress the UI back to "conditioning" while FLUX is active.
            set_phase(PHASE_SAMPLING, g_step.load(), expected);
        }
    } else {
        // Before sampling starts, callbacks are model/Qwen/LoRA preparation.
        set_phase(PHASE_CONDITIONING, safe_step, safe_steps);
    }
}

void preview_cb(int step, int frame_count, sd_image_t* frames, bool, void*) {
    if (!frames || frame_count < 1 || !frames[0].data) return;
    const sd_image_t& im = frames[0];
    const int channels = static_cast<int>(im.channel);
    if (channels < 3 || im.width == 0 || im.height == 0) return;

    const size_t pixel_count = static_cast<size_t>(im.width) * static_cast<size_t>(im.height);
    std::vector<jint> argb(pixel_count);
    for (size_t i = 0; i < pixel_count; ++i) {
        const uint8_t r = im.data[i * channels + 0];
        const uint8_t g = im.data[i * channels + 1];
        const uint8_t b = im.data[i * channels + 2];
        const uint8_t a = channels >= 4 ? im.data[i * channels + 3] : 255;
        argb[i] = static_cast<jint>((static_cast<uint32_t>(a) << 24) |
                                   (static_cast<uint32_t>(r) << 16) |
                                   (static_cast<uint32_t>(g) << 8) |
                                   static_cast<uint32_t>(b));
    }

    {
        std::lock_guard<std::mutex> lock(g_preview_mutex);
        g_preview_argb.swap(argb);
        g_preview_width = static_cast<int>(im.width);
        g_preview_height = static_cast<int>(im.height);
    }
    g_preview_step.store(std::max(0, step));
    g_preview_version.fetch_add(1);
}

std::string native_log_timestamp() {
    using namespace std::chrono;
    const auto now = system_clock::now();
    const auto ms = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
    const std::time_t tt = system_clock::to_time_t(now);
    std::tm tm{};
    localtime_r(&tt, &tm);
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%03d",
                  tm.tm_hour, tm.tm_min, tm.tm_sec, static_cast<int>(ms.count()));
    return std::string(buf);
}

const char* log_level_name(enum sd_log_level_t level) {
    switch (level) {
        case SD_LOG_ERROR: return "E";
        case SD_LOG_WARN: return "W";
        case SD_LOG_DEBUG: return "D";
        default: return "I";
    }
}

void push_console_log(enum sd_log_level_t level, const char* text) {
    if (!text || !*text) return;
    std::string raw(text);
    size_t start = 0;
    while (start <= raw.size()) {
        size_t end = raw.find('\n', start);
        if (end == std::string::npos) end = raw.size();
        std::string line = raw.substr(start, end - start);
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) line.pop_back();
        if (!line.empty()) {
            std::string formatted = native_log_timestamp() + " [NATIVE/" + log_level_name(level) + "] " + line;
            std::lock_guard<std::mutex> lock(g_log_buffer_mutex);
            while (g_log_buffer.size() >= MAX_CONSOLE_LOG_LINES) {
                g_log_buffer.pop_front();
                ++g_dropped_log_lines;
            }
            g_log_buffer.emplace_back(std::move(formatted));
        }
        if (end >= raw.size()) break;
        start = end + 1;
    }
}

void android_log_cb(enum sd_log_level_t level, const char* text, void*) {
    int prio = ANDROID_LOG_INFO;
    if (level == SD_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == SD_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == SD_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;

    if (text) {
        std::string lower(text);
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
        if (lower.find("generating image:") != std::string::npos) {
            g_sampling_started.store(true);
            set_phase(PHASE_SAMPLING, 0, g_expected_sample_steps.load());
        }

        if (lower.find("get_learned_condition completed") != std::string::npos) {
            set_phase(PHASE_CONDITIONING, 0, 0);
        }

        const int phase = g_phase.load();
        if (phase >= PHASE_CONDITIONING && phase <= PHASE_SAMPLING &&
            lower.find("decoding") != std::string::npos &&
            lower.find("latent") != std::string::npos) {
            const int total = g_steps.load();
            set_phase(PHASE_DECODING, total, total);
        }
    }

    push_console_log(level, text);
    __android_log_print(prio, TAG, "%s", text ? text : "");
}

std::string from_jstring(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

void throw_runtime(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

const char* c_or_null(const std::string& s) {
    return s.empty() ? nullptr : s.c_str();
}

struct TextEncoderStrategy {
    int min_tokens;
    bool early_layers;
    bool gpu;
    bool disk;
    const char* max_vram;
    bool diffusion_flash_attn;
    bool resident_diffusion;
    bool defer_flux_cache_free;
    const char* label;
};

TextEncoderStrategy text_encoder_strategy(int mode) {
    switch (std::max(0, std::min(16, mode))) {
        case 0: return {24,  false, false, false, "1.25", true,  false, false, "cpu-min24-mobile1250"};
        case 1: return {0,   false, false, false, "1.25", true,  false, false, "cpu-real-tokens-mobile1250"};
        case 2: return {24,  true,  false, false, "1.00", true,  false, false, "cpu-ultra-early18-mobile1000"};
        case 3: return {32,  false, false, false, "1.25", true,  false, false, "cpu-min32-mobile1250"};
        case 4: return {64,  false, false, false, "1.25", true,  false, false, "cpu-min64-mobile1250"};
        case 5: return {128, false, false, false, "1.25", true,  false, false, "cpu-min128-mobile1250"};
        case 6: return {512, false, false, false, "1.25", true,  false, false, "cpu-full512-mobile1250"};
        case 7: return {24,  false, true,  false, "0.90", true,  false, false, "vulkan-safe-min24-mobile900"};
        case 8: return {32,  false, true,  false, "1.25", true,  false, false, "vulkan-balanced-min32-mobile1250"};
        case 9: return {512, false, true,  false, "1.25", true,  false, false, "vulkan-full512-mobile1250"};
        case 10:return {512, false, false, true,  "1.00", true,  false, false, "cpu-disk-full512-mobile1000"};
        case 11:return {32,  false, true,  false, "-1",   false, false, false, "vulkan-legacy-auto-min32"};
        case 12:return {24,  false, true,  true,  "0.75", true,  false, false, "vulkan-safe-disk-min24-mobile750"};
        case 13:return {24,  false, true,  false, "0.90", true,  true,  false, "vulkan-qwen-resident-flux"};
        case 14:return {24,  false, false, false, "1.25", true,  true,  false, "cpu-qwen-resident-flux"};
        case 15:return {24,  false, true,  true,  "0.75", true,  true,  false, "vulkan-disk-qwen-resident-flux"};
        case 16:return {24,  false, true,  false, "0.75", true,  false, true,  "vulkan-qwen-deferred-cache-flux"};
        default:return {24, false, true, false, "0.75", true, false, true, "vulkan-qwen-deferred-cache-flux"};
    }
}

std::string make_key(
        const std::string& model,
        const std::string& diffusion,
        const std::string& vae,
        const std::string& clipL,
        const std::string& t5,
        const std::string& llm,
        int te_mode,
        int n_threads) {
    return model + "\n" + diffusion + "\n" + vae + "\n" + clipL + "\n" + t5 + "\n" + llm +
           "\nmobile-safe-v9-te-mode-" + std::to_string(te_mode) +
           "\nthreads-" + std::to_string(n_threads);
}

sd_ctx_t* ensure_context(
        JNIEnv* env,
        const std::string& model,
        const std::string& diffusion,
        const std::string& vae,
        const std::string& clipL,
        const std::string& t5,
        const std::string& llm,
        int te_mode,
        int requested_threads) {

    te_mode = std::max(0, std::min(16, te_mode));
    const TextEncoderStrategy strategy = text_encoder_strategy(te_mode);

    int detected_threads = sd_get_num_physical_cores();
    if (detected_threads <= 0) detected_threads = 4;
    const int effective_threads = requested_threads > 0
            ? std::max(2, std::min(8, requested_threads))
            : std::max(2, std::min(8, detected_threads));

    char min_tokens[16];
    std::snprintf(min_tokens, sizeof(min_tokens), "%d", strategy.min_tokens);
    setenv("LOCALFLUX_KLEIN_MIN_TOKENS", min_tokens, 1);
    setenv("LOCALFLUX_KLEIN_EARLY_LAYERS", strategy.early_layers ? "1" : "0", 1);
    setenv("LOCALFLUX_DIFFUSION_RESIDENT", strategy.resident_diffusion ? "1" : "0", 1);
    setenv("LOCALFLUX_DEFER_FLUX_CACHE_FREE", strategy.defer_flux_cache_free ? "1" : "0", 1);

    // Model files are mmap-backed. Sequential access is a safe Android/Linux
    // read-ahead hint and reduces first-run page-fault stalls without pinning
    // the entire multi-gigabyte stack in anonymous memory.
    setenv("SD_MMAP_FLAGS", "sequential", 1);

    const std::string key = make_key(model, diffusion, vae, clipL, t5, llm,
                                     te_mode, effective_threads);
    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    if (g_ctx && g_ctx_key == key) {
        return g_ctx;
    }

    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }

    if (model.empty() && diffusion.empty()) {
        throw_runtime(env, "Select either a full checkpoint or a diffusion/transformer model first.");
        return nullptr;
    }

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = c_or_null(model);
    p.diffusion_model_path = c_or_null(diffusion);
    p.vae_path = c_or_null(vae);
    p.clip_l_path = c_or_null(clipL);
    p.t5xxl_path = c_or_null(t5);
    p.llm_path = c_or_null(llm);
    p.n_threads = effective_threads;
    p.enable_mmap = true;
    p.eager_load = false;
    p.lora_apply_mode = LORA_APPLY_AT_RUNTIME;

    const bool split_model = !diffusion.empty();
    if (split_model) {
        // Diffusion stays on Vulkan with CPU-backed streamed parameters.
        // Qwen can run on optimized CPU, experimental Vulkan, or disk-backed
        // CPU according to the selected strategy.
        if (strategy.gpu) {
            p.backend = "diffusion=gpu,te=gpu,vae=cpu";
            p.params_backend = strategy.disk
                    ? "diffusion=cpu,te=disk,vae=cpu"
                    : "diffusion=cpu,te=cpu,vae=cpu";
        } else if (strategy.disk) {
            p.backend = "diffusion=gpu,te=cpu,vae=cpu";
            p.params_backend = "diffusion=cpu,te=disk,vae=cpu";
        } else {
            p.backend = "diffusion=gpu,te=cpu,vae=cpu";
            p.params_backend = "diffusion=cpu,te=cpu,vae=cpu";
        }

        // Do not use auto VRAM on Android unified memory by default. Auto mode
        // sees most system RAM as Vulkan memory and can effectively disable graph
        // cutting, producing multi-gigabyte allocations that Adreno cannot accept.
        p.max_vram = strategy.max_vram;
        p.stream_layers = !strategy.resident_diffusion;
        p.auto_fit = false;

        // Keep Qwen TE flash attention off on Vulkan for Adreno stability. Diffusion
        // flash attention is enabled in bounded mobile modes to reduce working-set
        // memory. Mode 11 intentionally preserves the old behavior for diagnosis.
        p.flash_attn = !strategy.gpu;
        p.diffusion_flash_attn = strategy.diffusion_flash_attn;
        // Direct Conv2D avoids large temporary im2col buffers during FLUX.2 VAE
        // decode. VAE tiling remains independently controlled per generation.
        p.vae_conv_direct = true;

        const char* te_runtime = strategy.gpu ? "gpu-experimental" : "cpu-armv8.6";
        const char* te_params = strategy.disk ? "disk" : "cpu";
        char mode_line[512];
        std::snprintf(mode_line, sizeof(mode_line),
                      "Loading split-model context "
                      "(diffusion=gpu, te=%s, vae=cpu, params diffusion=cpu te=%s, strategy=%s, "
                      "qwen_min=%d, states=%s, threads=%d, mmap=sequential, qwen_budget=%s GiB, "
                      "diffusion=%s, stream_layers=%d, diffusion_fa=%d, deferred_cache=%d; "
                      "TE runner buffers synchronized and released after conditioning)",
                      te_runtime, te_params, strategy.label, strategy.min_tokens,
                      strategy.early_layers ? "6/12/18" : "9/18/27",
                      effective_threads, strategy.max_vram,
                      strategy.resident_diffusion ? "resident/no-graph-cut" : "segmented",
                      strategy.resident_diffusion ? 0 : 1,
                      strategy.diffusion_flash_attn ? 1 : 0,
                      strategy.defer_flux_cache_free ? 1 : 0);
        push_console_log(SD_LOG_INFO, mode_line);
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s", mode_line);
    } else {
        // Preserve automatic placement for genuinely self-contained checkpoints.
        p.auto_fit = true;
        p.flash_attn = true;
        p.diffusion_flash_attn = true;
        char mode_line[192];
        std::snprintf(mode_line, sizeof(mode_line),
                      "Loading full-checkpoint context (mmap + flash attention + auto-fit, threads=%d)",
                      effective_threads);
        push_console_log(SD_LOG_INFO, mode_line);
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s", mode_line);
    }

    g_ctx = new_sd_ctx(&p);
    if (!g_ctx) {
        throw_runtime(env, split_model
                ? "Model loading failed in mobile-safe mode. Verify diffusion, VAE and text-encoder compatibility. "
                  "If Android still terminates the app, retry at 512x512 after closing other memory-heavy apps."
                : "Model loading failed. Check that the selected checkpoint is supported and leave sufficient free RAM.");
        return nullptr;
    }
    if (!sd_ctx_supports_image_generation(g_ctx)) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        throw_runtime(env, "The selected model set does not expose image generation in stable-diffusion.cpp.");
        return nullptr;
    }

    g_ctx_key = key;
    return g_ctx;
}
} // namespace

extern "C" jint JNI_OnLoad(JavaVM*, void*) {
    // Adreno/Android uses unified system memory, but Vulkan still has practical
    // per-buffer and driver-allocation limits. Keep backend allocations bounded
    // before sd_get_system_info()/sd_list_devices can initialize Vulkan.
    setenv("GGML_VK_DISABLE_ASYNC", "1", 0);
    setenv("GGML_VK_SUBALLOCATION_BLOCK_SIZE", "536870912", 0);   // 512 MiB
    setenv("GGML_VK_FORCE_MAX_BUFFER_SIZE", "1610612736", 0);    // 1.5 GiB
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_localflux_studio_MainActivity_nativeSystemInfo(JNIEnv* env, jclass) {
    std::call_once(g_log_once, [] {
        sd_set_log_callback(android_log_cb, nullptr);
    });

    std::string out = sd_get_system_info() ? sd_get_system_info() : "stable-diffusion.cpp";
    out += "\nCPU target: ARMv8.6 + DOTPROD + I8MM; KleidiAI enabled for Q4_0/Q8_0";
    out += "\nKlein conditioning: real/24/32/64/128/512-token modes + optional early 6/12/18 states";
    out += "\nAndroid Vulkan safety: async off, 512 MiB suballocations, 1.5 GiB max buffer, explicit graph budgets";
    const size_t n = sd_list_devices(nullptr, 0);
    if (n > 0) {
        std::vector<char> buf(n + 1, 0);
        sd_list_devices(buf.data(), buf.size());
        out += "\nDevices: ";
        out += buf.data();
    }
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_localflux_studio_MainActivity_nativeGenerate(
        JNIEnv* env, jclass,
        jstring jModel,
        jstring jDiffusion,
        jstring jVae,
        jstring jClipL,
        jstring jT5,
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
        jboolean livePreview,
        jint previewInterval,
        jobjectArray jLoraPaths,
        jfloatArray jLoraStrengths,
        jint textEncoderMode,
        jint cpuThreads) {

    std::call_once(g_log_once, [] {
        sd_set_log_callback(android_log_cb, nullptr);
    });

    std::lock_guard<std::mutex> generation_lock(g_generation_mutex);

    const std::string model = from_jstring(env, jModel);
    const std::string diffusion = from_jstring(env, jDiffusion);
    const std::string vae = from_jstring(env, jVae);
    const std::string clipL = from_jstring(env, jClipL);
    const std::string t5 = from_jstring(env, jT5);
    const std::string llm = from_jstring(env, jLlm);
    const std::string prompt = from_jstring(env, jPrompt);
    const std::string negative = from_jstring(env, jNegative);

    std::vector<std::string> lora_paths;
    std::vector<float> lora_strengths;
    if (jLoraPaths != nullptr && jLoraStrengths != nullptr) {
        const jsize path_count = env->GetArrayLength(jLoraPaths);
        const jsize strength_count = env->GetArrayLength(jLoraStrengths);
        const jsize count = std::min(path_count, strength_count);
        std::vector<jfloat> strengths(static_cast<size_t>(count));
        if (count > 0) {
            env->GetFloatArrayRegion(jLoraStrengths, 0, count, strengths.data());
        }
        for (jsize i = 0; i < count; ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(jLoraPaths, i));
            std::string path = from_jstring(env, value);
            env->DeleteLocalRef(value);
            if (path.empty()) continue;
            const float strength = std::max(-2.0f, std::min(2.0f, static_cast<float>(strengths[static_cast<size_t>(i)])));
            if (std::abs(strength) < 0.0001f) continue;
            lora_paths.push_back(std::move(path));
            lora_strengths.push_back(strength);
        }
    }

    if (prompt.empty()) {
        throw_runtime(env, "Prompt is empty.");
        return nullptr;
    }
    if (width < 256 || height < 256 || width > 1536 || height > 1536 ||
        width % 64 != 0 || height % 64 != 0) {
        throw_runtime(env, "Width and height must be 256..1536 and divisible by 64.");
        return nullptr;
    }
    if (steps < 1 || steps > 100) {
        throw_runtime(env, "Steps must be between 1 and 100.");
        return nullptr;
    }

    reset_progress_state();
    g_expected_sample_steps.store(steps);

    // Model-loader and sampler progress share one global callback in stable-diffusion.cpp.
    // Use a dedicated loading callback during context creation, then swap to the sampler-aware
    // callback before generation so tensor counts can never masquerade as denoising steps.
    sd_set_progress_callback(load_progress_cb, nullptr);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, true, false, nullptr);

    sd_ctx_t* ctx = ensure_context(env, model, diffusion, vae, clipL, t5, llm,
                                  static_cast<int>(textEncoderMode),
                                  static_cast<int>(cpuThreads));
    if (!ctx || env->ExceptionCheck()) {
        set_phase(PHASE_ERROR);
        return nullptr;
    }

    set_phase(PHASE_CONDITIONING, 0, 0);
    sd_set_progress_callback(progress_cb, nullptr);
    if (livePreview == JNI_TRUE) {
        sd_set_preview_callback(preview_cb, PREVIEW_PROJ,
                                std::max(1, std::min(8, static_cast<int>(previewInterval))),
                                true, false, nullptr);
    } else {
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, true, false, nullptr);
    }
    sd_cancel_generation(ctx, SD_CANCEL_RESET);

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
    gp.vae_tiling_params.enabled = (vaeTiling == JNI_TRUE);

    std::vector<sd_lora_t> loras;
    loras.reserve(lora_paths.size());
    for (size_t i = 0; i < lora_paths.size(); ++i) {
        sd_lora_t lora{};
        lora.is_high_noise = false;
        lora.multiplier = lora_strengths[i];
        lora.path = lora_paths[i].c_str();
        loras.push_back(lora);
    }
    gp.loras = loras.empty() ? nullptr : loras.data();
    gp.lora_count = static_cast<uint32_t>(loras.size());

    if (gp.sample_params.sample_method == SAMPLE_METHOD_COUNT) {
        gp.sample_params.sample_method = sd_get_default_sample_method(ctx);
    }
    if (gp.sample_params.scheduler == SCHEDULER_COUNT) {
        gp.sample_params.scheduler = sd_get_default_scheduler(ctx, gp.sample_params.sample_method);
    }

    sd_image_t* images = nullptr;
    int count = 0;
    const bool ok = generate_image(ctx, &gp, &images, &count);
    sd_cancel_generation(ctx, SD_CANCEL_RESET);

    if (!ok || !images || count < 1 || !images[0].data) {
        set_phase(PHASE_ERROR, g_step.load(), g_steps.load());
        if (images) free_sd_images(images, count);
        throw_runtime(env, "Generation failed. See Logcat tag LocalFluxNative for the model/runtime error.");
        return nullptr;
    }

    const sd_image_t& im = images[0];
    const int channels = static_cast<int>(im.channel);
    if (channels < 3) {
        set_phase(PHASE_ERROR, g_step.load(), g_steps.load());
        free_sd_images(images, count);
        throw_runtime(env, "Generator returned an unsupported image format.");
        return nullptr;
    }

    const size_t pixels_count = static_cast<size_t>(im.width) * static_cast<size_t>(im.height);
    std::vector<jint> argb(pixels_count);
    for (size_t i = 0; i < pixels_count; ++i) {
        const uint8_t r = im.data[i * channels + 0];
        const uint8_t g = im.data[i * channels + 1];
        const uint8_t b = im.data[i * channels + 2];
        const uint8_t a = channels >= 4 ? im.data[i * channels + 3] : 255;
        argb[i] = static_cast<jint>((static_cast<uint32_t>(a) << 24) |
                                   (static_cast<uint32_t>(r) << 16) |
                                   (static_cast<uint32_t>(g) << 8) |
                                   static_cast<uint32_t>(b));
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(pixels_count));
    if (result) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(pixels_count), argb.data());
    }
    free_sd_images(images, count);
    set_phase(PHASE_COMPLETE, steps, steps);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_localflux_studio_MainActivity_nativeDrainLogs(JNIEnv* env, jclass) {
    std::string joined;
    {
        std::lock_guard<std::mutex> lock(g_log_buffer_mutex);
        if (g_dropped_log_lines > 0) {
            joined += native_log_timestamp() + " [NATIVE/W] Console buffer dropped " +
                      std::to_string(g_dropped_log_lines) + " older log lines\n";
            g_dropped_log_lines = 0;
        }
        for (const auto& line : g_log_buffer) {
            joined += line;
            joined.push_back('\n');
        }
        g_log_buffer.clear();
    }
    return env->NewStringUTF(joined.c_str());
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_localflux_studio_MainActivity_nativeProgressSnapshot(JNIEnv* env, jclass) {
    jint values[6] = {
            static_cast<jint>(g_phase.load()),
            static_cast<jint>(g_step.load()),
            static_cast<jint>(g_steps.load()),
            static_cast<jint>(elapsed_ms()),
            static_cast<jint>(g_preview_version.load()),
            static_cast<jint>(g_preview_step.load())
    };
    jintArray out = env->NewIntArray(6);
    if (out) env->SetIntArrayRegion(out, 0, 6, values);
    return out;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_localflux_studio_MainActivity_nativePreviewSnapshot(
        JNIEnv* env, jclass, jint lastVersion) {
    std::lock_guard<std::mutex> lock(g_preview_mutex);
    const int version = g_preview_version.load();
    if (version <= lastVersion || g_preview_argb.empty() ||
        g_preview_width <= 0 || g_preview_height <= 0) {
        return nullptr;
    }

    const size_t pixel_count = g_preview_argb.size();
    if (pixel_count > static_cast<size_t>(std::numeric_limits<jsize>::max() - 3)) {
        return nullptr;
    }
    std::vector<jint> out_data(pixel_count + 3);
    out_data[0] = version;
    out_data[1] = g_preview_width;
    out_data[2] = g_preview_height;
    std::copy(g_preview_argb.begin(), g_preview_argb.end(), out_data.begin() + 3);

    jintArray out = env->NewIntArray(static_cast<jsize>(out_data.size()));
    if (out) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(out_data.size()), out_data.data());
    }
    return out;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_localflux_studio_MainActivity_nativeCancel(JNIEnv*, jclass) {
    set_phase(PHASE_CANCELLED, g_step.load(), g_steps.load());
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx) sd_cancel_generation(g_ctx, SD_CANCEL_ALL);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_localflux_studio_MainActivity_nativeUnload(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> generation_lock(g_generation_mutex);
    std::lock_guard<std::mutex> ctx_lock(g_ctx_mutex);
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
}
