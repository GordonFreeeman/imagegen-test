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
