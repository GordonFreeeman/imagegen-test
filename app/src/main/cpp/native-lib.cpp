#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
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

void android_log_cb(enum sd_log_level_t level, const char* text, void*) {
    int prio = ANDROID_LOG_INFO;
    if (level == SD_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == SD_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == SD_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;
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
    return model + "\n" + diffusion + "\n" + vae + "\n" + clipL + "\n" + t5 + "\n" + llm;
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
    p.flash_attn = true;
    p.diffusion_flash_attn = true;
    p.auto_fit = true;

    __android_log_print(ANDROID_LOG_INFO, TAG, "Loading inference context (mmap + flash attention + auto-fit)");
    g_ctx = new_sd_ctx(&p);
    if (!g_ctx) {
        throw_runtime(env, "Model loading failed. Check that the selected files belong to the same supported FLUX/checkpoint family and leave sufficient free RAM.");
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
        jfloat guidance,
        jlong seed,
        jboolean vaeTiling) {

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

    sd_ctx_t* ctx = ensure_context(env, model, diffusion, vae, clipL, t5, llm);
    if (!ctx || env->ExceptionCheck()) return nullptr;

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
    gp.sample_params.guidance.distilled_guidance = guidance;
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
        if (images) free_sd_images(images, count);
        throw_runtime(env, "Generation failed. See Logcat tag LocalFluxNative for the model/runtime error.");
        return nullptr;
    }

    const sd_image_t& im = images[0];
    const int channels = static_cast<int>(im.channel);
    if (channels < 3) {
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
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_localflux_studio_MainActivity_nativeCancel(JNIEnv*, jclass) {
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
