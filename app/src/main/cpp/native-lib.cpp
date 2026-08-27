#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstdint>
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
}

void progress_cb(int step, int steps, float, void*) {
    const int safe_steps = std::max(0, steps);
    const int safe_step = std::max(0, std::min(step, safe_steps > 0 ? safe_steps : step));
    set_phase((safe_steps > 0 && safe_step >= safe_steps) ? PHASE_DECODING : PHASE_SAMPLING,
              safe_step, safe_steps);
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

void android_log_cb(enum sd_log_level_t level, const char* text, void*) {
    int prio = ANDROID_LOG_INFO;
    if (level == SD_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == SD_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == SD_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;

    if (text) {
        std::string lower(text);
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
        if (lower.find("decode") != std::string::npos &&
            (lower.find("vae") != std::string::npos || lower.find("first_stage") != std::string::npos)) {
            const int total = g_steps.load();
            set_phase(PHASE_DECODING, total, total);
        }
    }

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

std::string make_key(
        const std::string& model,
        const std::string& diffusion,
        const std::string& vae,
        const std::string& clipL,
        const std::string& t5,
        const std::string& llm) {
    return model + "\n" + diffusion + "\n" + vae + "\n" + clipL + "\n" + t5 + "\n" + llm +
           "\nmobile-safe-v2";
}

sd_ctx_t* ensure_context(
        JNIEnv* env,
        const std::string& model,
        const std::string& diffusion,
        const std::string& vae,
        const std::string& clipL,
        const std::string& t5,
        const std::string& llm) {

    const std::string key = make_key(model, diffusion, vae, clipL, t5, llm);
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
    p.n_threads = std::max(2, std::min(8, sd_get_num_physical_cores()));
    p.enable_mmap = true;
    p.eager_load = false;

    const bool split_model = !diffusion.empty();
    if (split_model) {
        // Mobile-safe policy for FLUX-class split stacks:
        // - run the heavy diffusion graph on the first available GPU (Vulkan on Adreno)
        // - run text encoding + VAE on CPU
        // - keep diffusion parameters in CPU RAM and stream transformer layers to GPU
        // - keep the large LLM/text encoder disk-backed to reduce Android process RSS
        // - reserve ~1 GiB GPU headroom via graph-cut segmentation
        p.backend = "diffusion=gpu,te=cpu,vae=cpu";
        p.params_backend = "diffusion=cpu,te=disk,vae=cpu";
        p.max_vram = "-1";
        p.stream_layers = true;
        p.auto_fit = false;

        // Vulkan flash-attention support varies by driver/model. Streaming+segmentation is the
        // primary memory strategy here; disable FA for stability on mobile Vulkan.
        p.flash_attn = false;
        p.diffusion_flash_attn = false;

        __android_log_print(
                ANDROID_LOG_INFO, TAG,
                "Loading split-model context in mobile-safe mode "
                "(diffusion=gpu, te/vae=cpu, params diffusion=cpu te=disk, max_vram=-1, stream_layers=1)");
    } else {
        // Preserve automatic placement for genuinely self-contained checkpoints.
        p.auto_fit = true;
        p.flash_attn = true;
        p.diffusion_flash_attn = true;
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "Loading full-checkpoint context (mmap + flash attention + auto-fit)");
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

extern "C"
JNIEXPORT jstring JNICALL
Java_com_localflux_studio_MainActivity_nativeSystemInfo(JNIEnv* env, jclass) {
    std::call_once(g_log_once, [] {
        sd_set_log_callback(android_log_cb, nullptr);
    });

    std::string out = sd_get_system_info() ? sd_get_system_info() : "stable-diffusion.cpp";
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
        jint previewInterval) {

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
    sd_set_progress_callback(progress_cb, nullptr);
    if (livePreview == JNI_TRUE) {
        sd_set_preview_callback(preview_cb, PREVIEW_PROJ,
                                std::max(1, std::min(8, static_cast<int>(previewInterval))),
                                true, false, nullptr);
    } else {
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, true, false, nullptr);
    }

    sd_ctx_t* ctx = ensure_context(env, model, diffusion, vae, clipL, t5, llm);
    if (!ctx || env->ExceptionCheck()) {
        set_phase(PHASE_ERROR);
        return nullptr;
    }

    set_phase(PHASE_CONDITIONING, 0, steps);
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
