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
# FLUX.2 Klein upstream pads Qwen3 to 512 input tokens before running all 36 layers.
# On mobile CPU that makes even tiny prompts unnecessarily expensive. The app can
# instead run Qwen on a smaller minimum sequence and zero-pad the resulting hidden
# states to 512 afterwards, using the runtime's existing hidden_states_min_length path.
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
            const char* localflux_cond_mode  = std::getenv("LOCALFLUX_KLEIN_COND_MODE");
            if (localflux_cond_mode && localflux_cond_mode[0] == '0') {
                // Fast mobile mode: keep a small Qwen minimum, then use the existing
                // post-encoder zero padding path so FLUX still receives 512 positions.
                min_length               = 64;
                hidden_states_min_length = 512;
                LOG_INFO("LocalFlux Klein conditioning: Qwen min 64 -> zero-pad embeddings to 512");
            } else if (localflux_cond_mode && localflux_cond_mode[0] == '1') {
                // Balanced mode retains more padded Qwen context while avoiding the
                // full 512-token transformer cost on normal short prompts.
                min_length               = 128;
                hidden_states_min_length = 512;
                LOG_INFO("LocalFlux Klein conditioning: Qwen min 128 -> zero-pad embeddings to 512");
            } else {
                // Reference/upstream behavior for quality comparisons and fallbacks.
                min_length               = 512;
                LOG_INFO("LocalFlux Klein conditioning: upstream full 512-token Qwen");
            }
            out_layers                       = {9, 18, 27};
'''
if old not in s:
    raise SystemExit("Pinned conditioner layout changed; refusing to apply LocalFlux Klein patch")
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
