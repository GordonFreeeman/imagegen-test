#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SD_COMMIT="50d640568388f876b0d63ee6ddb6bc86d997ec64"
FACE_COMMIT="f38a70e4bacaab4132538421c471f9d4d3ccac00"

rm -rf vendor/stable-diffusion.cpp vendor/android-face-fusion
mkdir -p vendor

git clone --recursive https://github.com/leejet/stable-diffusion.cpp vendor/stable-diffusion.cpp
git -C vendor/stable-diffusion.cpp checkout "$SD_COMMIT"
git -C vendor/stable-diffusion.cpp submodule update --init --recursive

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
echo "Pinned Android Face Fusion: $FACE_COMMIT"
