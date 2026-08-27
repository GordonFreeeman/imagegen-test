# Local Flux Studio

Local Flux Studio is an arm64 Android app for fully local image generation and self-face swapping on modern Snapdragon-class phones.

## What is local

After the required model files are installed/imported, image generation and face swapping run entirely on-device. The app does not upload prompts, source faces, embeddings, generated images, or model files.

## Android target

- compileSdk: 36
- targetSdk: 36
- minSdk: 29
- ABI: arm64-v8a
- 16 KB page-size verification in CI

## Image generation runtime

The app embeds a pinned build of [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), compiled with the Vulkan backend. It accepts either:

1. a compatible full checkpoint, or
2. a split FLUX-class stack with:
   - diffusion / transformer model
   - VAE / AE
   - CLIP-L
   - T5XXL
   - optional LLM / Qwen encoder for model families that actually require one

The intended starting point on a 16 GB Snapdragon flagship is a quantized Q4-class FLUX model at 512 to 768 px with VAE tiling enabled. Larger resolutions and heavier quantizations can exceed practical memory or thermal limits.

The native runtime caches the loaded diffusion context between generations and exposes explicit unload and cancellation controls.

## Face swap runtime

Face swapping uses a pinned Android pipeline based on:

- InsightFace/SCRFD-style face detection
- ArcFace identity embedding
- INSwapper 128
- the model's EMAP transformation
- local alignment and compositing

The three face-model weights are not redistributed inside the APK. The in-app setup step obtains them separately. Their upstream licenses and usage terms remain applicable.

A likeness authorization checkbox is required before setup or swapping. The feature is intended for your own likeness or people who explicitly authorized its use.

## Model import

Large diffusion weights are intentionally excluded from the APK. Android's document picker imports a selected model into app-private storage so the native runtime can mmap it safely.

Changing a model slot invalidates the cached native context.

## Output

Generated and face-swapped PNG files can be written to:

`Pictures/LocalFluxStudio`

using Android MediaStore.

## Reproducible build

The build vendors pinned inference-engine commits via `scripts/vendor-deps.sh` and then runs:

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI additionally checks target SDK 36 and 16 KB ZIP/native-library alignment.

## Important runtime limitation

CI can verify source compilation, native linking, APK packaging, SDK metadata, lint, and page alignment. It cannot prove real-world FLUX throughput or Adreno driver compatibility without running the APK on the target Snapdragon device with the exact model files. Those measurements must be performed on the phone.
