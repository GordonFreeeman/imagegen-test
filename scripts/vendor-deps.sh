#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SD_COMMIT="50d640568388f876b0d63ee6ddb6bc86d997ec64"
BONSAI_COMMIT="932ff747077f47bcf27b7de4f38724b9ccaa2616"
FACE_COMMIT="f38a70e4bacaab4132538421c471f9d4d3ccac00"

rm -rf vendor/stable-diffusion.cpp vendor/bonsai-cpp vendor/android-face-fusion
mkdir -p vendor

git clone --recursive https://github.com/leejet/stable-diffusion.cpp vendor/stable-diffusion.cpp
git -C vendor/stable-diffusion.cpp checkout "$SD_COMMIT"
git -C vendor/stable-diffusion.cpp submodule update --init --recursive

# FLUX.2 Android/Adreno production backend. This fork is kept separate from the
# generic pinned stable-diffusion.cpp runtime so existing model compatibility is
# preserved. It contains the Snapdragon 8 Elite / Adreno 830 KGSL watchdog and
# Vulkan dispatch fixes validated by Duration AI on a Galaxy S25+.
git clone https://github.com/duration-ai/bonsai-cpp.git vendor/bonsai-cpp
git -C vendor/bonsai-cpp checkout "$BONSAI_COMMIT"

# Keep the short-token Klein conditioning optimization in the Adreno runtime.
# The diffusion-facing embedding remains padded to 512 positions, but Qwen only
# computes the prompt plus a small configurable minimum.
python3 - <<'PY'
from pathlib import Path

p = Path("vendor/bonsai-cpp/src/conditioner.hpp")
s = p.read_text()
if "#include <cstdlib>" not in s:
    anchor = "#include <"
    # conditioner.hpp includes transitively, but make getenv/strtol explicit.
    s = "#include <cstdlib>\n" + s

old = '''        } else if (version == VERSION_FLUX2_KLEIN) {
            prompt_template_encode_start_idx = 0;
            min_length                       = 512;
            out_layers                       = {9, 18, 27};
'''
new = '''        } else if (version == VERSION_FLUX2_KLEIN) {
            prompt_template_encode_start_idx = 0;

            int localflux_min_tokens = 24;
            if (const char* value = std::getenv("LOCALFLUX_KLEIN_MIN_TOKENS")) {
                char* end = nullptr;
                long parsed = std::strtol(value, &end, 10);
                if (end != value) {
                    if (parsed < 0) parsed = 0;
                    if (parsed > 512) parsed = 512;
                    localflux_min_tokens = static_cast<int>(parsed);
                }
            }
            min_length                       = localflux_min_tokens;
            hidden_states_min_length         = 512;

            const bool localflux_early_layers = [] {
                const char* value = std::getenv("LOCALFLUX_KLEIN_EARLY_LAYERS");
                return value != nullptr && value[0] == '1';
            }();
            if (localflux_early_layers) {
                out_layers = {6, 12, 18};
            } else {
                out_layers = {9, 18, 27};
            }

            LOG_INFO("LocalFlux Adreno Klein conditioning: Qwen min=%d, embeddings>=512, states=%s",
                     min_length,
                     localflux_early_layers ? "6/12/18 (experimental)" : "9/18/27");
'''
if old not in s:
    raise SystemExit("Duration conditioner layout changed; refusing to apply LocalFlux Klein patch")
s = s.replace(old, new, 1)
p.write_text(s)
PY

# Local Flux Studio mobile performance patch for the pinned runtime.
# FLUX.2 Klein upstream pads Qwen3 to 512 input tokens before running the
# transformer. The app exposes selectable mobile strategies that can instead
# run Qwen on the prompt's real length or a smaller minimum, then use the
# runtime's existing hidden_states_min_length path to restore a 512-position
# conditioning tensor before FLUX diffusion. An optional early-state preset
# also requests layers 6/12/18 instead of the trained 9/18/27 states.
python3 - <<'PY'
from pathlib import Path

p = Path("vendor/stable-diffusion.cpp/src/conditioning/conditioner.hpp")
s = p.read_text()
if "#include <cstdlib>" not in s:
    s = s.replace("#include <cmath>\n", "#include <cmath>\n#include <cstdlib>\n", 1)

old = '''        } else if (version == VERSION_FLUX2_KLEIN) {
            prompt_template_encode_start_idx = 0;
            min_length                       = 512;
            out_layers                       = {9, 18, 27};
'''
new = '''        } else if (version == VERSION_FLUX2_KLEIN) {
            prompt_template_encode_start_idx = 0;

            // Local Flux Studio: the upstream Klein path always expands the Qwen
            // sequence to 512 tokens before transformer compute. On a phone this
            // dominates latency. Keep the diffusion-facing tensor at >=512, but
            // allow the Qwen graph itself to use the real prompt length or a
            // smaller configurable minimum.
            int localflux_min_tokens = 512;
            if (const char* value = std::getenv("LOCALFLUX_KLEIN_MIN_TOKENS")) {
                char* end = nullptr;
                long parsed = std::strtol(value, &end, 10);
                if (end != value) {
                    if (parsed < 0) parsed = 0;
                    if (parsed > 512) parsed = 512;
                    localflux_min_tokens = static_cast<int>(parsed);
                }
            }
            min_length               = localflux_min_tokens;
            hidden_states_min_length = 512;

            const bool localflux_early_layers = [] {
                const char* value = std::getenv("LOCALFLUX_KLEIN_EARLY_LAYERS");
                return value != nullptr && value[0] == '1';
            }();
            if (localflux_early_layers) {
                out_layers = {6, 12, 18};
            } else {
                out_layers = {9, 18, 27};
            }

            LOG_INFO("LocalFlux Klein conditioning: Qwen min=%d, embeddings>=512, states=%s",
                     min_length,
                     localflux_early_layers ? "6/12/18 (experimental)" : "9/18/27");
'''
if old not in s:
    raise SystemExit("Pinned conditioner layout changed; refusing to apply LocalFlux Klein patch")
s = s.replace(old, new, 1)
p.write_text(s)
PY

# Local Flux Studio: allow the Android app to keep Qwen graph-cut segmented
# while disabling graph cutting specifically for the FLUX diffusion runner.
# FLUX keeps its compute/parameter buffers alive across denoising steps, so a
# resident diffusion model avoids the cache-rotation path that has been crashing
# on Adreno while still releasing Qwen before diffusion starts.
python3 - <<'PY'
from pathlib import Path

p = Path("vendor/stable-diffusion.cpp/src/stable-diffusion.cpp")
s = p.read_text()
old = '''    size_t max_graph_vram_bytes_for_module(SDBackendModule module) {
        return max_vram_assignment.bytes_for_backend(backend_for(module));
    }
'''
new = '''    size_t max_graph_vram_bytes_for_module(SDBackendModule module) {
        if (module == SDBackendModule::DIFFUSION) {
            const char* resident = std::getenv("LOCALFLUX_DIFFUSION_RESIDENT");
            if (resident != nullptr && resident[0] == '1') {
                LOG_INFO("LocalFlux: diffusion graph cutting disabled; keeping diffusion resident");
                return 0;
            }
        }
        return max_vram_assignment.bytes_for_backend(backend_for(module));
    }
'''
if old not in s:
    raise SystemExit("Pinned StableDiffusionGGML layout changed; refusing to apply resident diffusion patch")
s = s.replace(old, new, 1)
p.write_text(s)
PY

# Adreno can crash while graph-cut FLUX rotates a successfully synchronized
# cache generation. Keep old FLUX cache buffers/contexts alive until the whole
# forward pass is finished instead of destroying them between segments.
python3 - <<'PY'
from pathlib import Path

p = Path("vendor/stable-diffusion.cpp/src/core/ggml_extend.hpp")
s = p.read_text()

old_members = '''    ggml_context* cache_ctx            = nullptr;
    ggml_backend_buffer_t cache_buffer = nullptr;
'''
new_members = '''    ggml_context* cache_ctx            = nullptr;
    ggml_backend_buffer_t cache_buffer = nullptr;
    std::vector<ggml_context*> localflux_retired_cache_ctxs;
    std::vector<ggml_backend_buffer_t> localflux_retired_cache_buffers;
'''
if old_members not in s:
    raise SystemExit("Pinned GGMLRunner cache members changed")
s = s.replace(old_members, new_members, 1)

old_free = '''    void free_cache_ctx() {
        if (cache_ctx != nullptr) {
            ggml_free(cache_ctx);
            cache_ctx = nullptr;
        }
    }
'''
new_free = '''    bool localflux_defer_flux_cache_free() {
        const char* value = std::getenv("LOCALFLUX_DEFER_FLUX_CACHE_FREE");
        return value != nullptr && value[0] == '1' && get_desc() == "flux";
    }

    void localflux_retire_cache_generation(ggml_context* ctx, ggml_backend_buffer_t buffer) {
        if (!localflux_defer_flux_cache_free()) {
            if (buffer != nullptr) {
                ggml_backend_buffer_free(buffer);
            }
            if (ctx != nullptr) {
                ggml_free(ctx);
            }
            return;
        }
        if (buffer != nullptr) {
            localflux_retired_cache_buffers.push_back(buffer);
        }
        if (ctx != nullptr) {
            localflux_retired_cache_ctxs.push_back(ctx);
        }
        LOG_DEBUG("%s LocalFlux deferred cache free: generations=%zu buffers=%zu",
                  get_desc().c_str(),
                  localflux_retired_cache_ctxs.size(),
                  localflux_retired_cache_buffers.size());
    }

    void localflux_free_retired_cache_generations() {
        if (localflux_retired_cache_buffers.empty() && localflux_retired_cache_ctxs.empty()) {
            return;
        }
        if (sched != nullptr) {
            ggml_backend_sched_synchronize(sched);
        }
        if (runtime_backend != nullptr) {
            ggml_backend_synchronize(runtime_backend);
        }
        for (ggml_backend_buffer_t buffer : localflux_retired_cache_buffers) {
            if (buffer != nullptr) {
                ggml_backend_buffer_free(buffer);
            }
        }
        for (ggml_context* ctx : localflux_retired_cache_ctxs) {
            if (ctx != nullptr) {
                ggml_free(ctx);
            }
        }
        LOG_DEBUG("%s LocalFlux released %zu deferred cache generations",
                  get_desc().c_str(),
                  localflux_retired_cache_ctxs.size());
        localflux_retired_cache_buffers.clear();
        localflux_retired_cache_ctxs.clear();
    }

    void free_cache_ctx() {
        if (cache_ctx != nullptr) {
            ggml_free(cache_ctx);
            cache_ctx = nullptr;
        }
    }
'''
if old_free not in s:
    raise SystemExit("Pinned GGMLRunner free_cache_ctx changed")
s = s.replace(old_free, new_free, 1)

old_empty = '''        if (merged_cache_sources.empty()) {
            if (old_cache_buffer != nullptr) {
                ggml_backend_buffer_free(old_cache_buffer);
            }
            if (old_cache_ctx != nullptr) {
                ggml_free(old_cache_ctx);
            }
            return true;
        }
'''
new_empty = '''        if (merged_cache_sources.empty()) {
            localflux_retire_cache_generation(old_cache_ctx, old_cache_buffer);
            return true;
        }
'''
if old_empty not in s:
    raise SystemExit("Pinned empty cache rotation block changed")
s = s.replace(old_empty, new_empty, 1)

old_rotate = '''        if (old_cache_buffer != nullptr) {
            ggml_backend_buffer_free(old_cache_buffer);
        }
        if (old_cache_ctx != nullptr) {
            ggml_free(old_cache_ctx);
        }
        return true;
'''
new_rotate = '''        localflux_retire_cache_generation(old_cache_ctx, old_cache_buffer);
        return true;
'''
if old_rotate not in s:
    raise SystemExit("Pinned cache rotation block changed")
s = s.replace(old_rotate, new_rotate, 1)

old_all = '''    void free_cache_ctx_and_buffer() {
        free_cache_buffer();
        free_cache_ctx();
    }
'''
new_all = '''    void free_cache_ctx_and_buffer() {
        if (sched != nullptr) {
            ggml_backend_sched_synchronize(sched);
        }
        if (runtime_backend != nullptr) {
            ggml_backend_synchronize(runtime_backend);
        }
        free_cache_buffer();
        free_cache_ctx();
        localflux_free_retired_cache_generations();
    }
'''
if old_all not in s:
    raise SystemExit("Pinned free_cache_ctx_and_buffer changed")
s = s.replace(old_all, new_all, 1)

p.write_text(s)
PY

# The pinned runner can free Vulkan scheduler/parameter buffers immediately after
# Qwen conditioning. Force completion of queued backend work first. This is
# especially important on Android where native process aborts bypass Java error
# handling and Adreno drivers may still own resources after the compute call.
python3 - <<'PY'
from pathlib import Path

p = Path("vendor/stable-diffusion.cpp/src/core/ggml_extend.hpp")
s = p.read_text()
old = '''public:
    void runner_done() {
        free_compute_buffer();
'''
new = '''public:
    void runner_done() {
        // Local Flux Studio: make the Qwen -> diffusion handoff synchronous
        // before tearing down scheduler and staged parameter buffers.
        if (sched != nullptr) {
            ggml_backend_sched_synchronize(sched);
        }
        if (runtime_backend != nullptr) {
            ggml_backend_synchronize(runtime_backend);
        }
        for (ggml_backend_t backend : extra_runtime_backends) {
            if (backend != nullptr) {
                ggml_backend_synchronize(backend);
            }
        }
        free_compute_buffer();
'''
if old not in s:
    raise SystemExit("Pinned GGMLRunner layout changed; refusing to apply Vulkan handoff patch")
s = s.replace(old, new, 1)
p.write_text(s)
PY

git clone https://github.com/Parasaran-Python/android-face-fusion vendor/android-face-fusion
git -C vendor/android-face-fusion checkout "$FACE_COMMIT"

FACE_DST="app/src/main/java/com/pv/androidfacefusion"
mkdir -p "$FACE_DST" app/src/main/assets
for f in FaceDetector.java FaceEmbedder.java FaceSwapper.java FaceFusionProcessor.java ImageUtils.java OrtSessionHelper.java ModelDownloader.java; do
  cp "vendor/android-face-fusion/app/src/main/java/com/pv/androidfacefusion/$f" "$FACE_DST/$f"
done
cp vendor/android-face-fusion/app/src/main/assets/emap.bin app/src/main/assets/emap.bin

echo "Pinned stable-diffusion.cpp: $SD_COMMIT"
echo "Pinned Adreno FLUX runtime: $BONSAI_COMMIT"
echo "Pinned Android Face Fusion: $FACE_COMMIT"
