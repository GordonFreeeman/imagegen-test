# Agent Review Log — Local Flux Studio v1.1.0

Final reviewed implementation: `6e8b10e9c61e1a8ea3d4ec2586367d4c50b8ac4c`
Successful CI run: `33066921924`
APK version: 1.1.0 (versionCode 2), targetSdk 36, minSdk 29.

## Iteration 1

### Logic Inquisitor

**Verdict:** REJECTED

**Blocking findings:**

1. Model profiles did not validate their required split-model components.
   - Evidence/reproduction: selecting a FLUX.2 profile and importing only the diffusion/transformer file left generation available; failure would occur later during native model loading because VAE and LLM/text encoder were absent.
   - Expected behavior: the selected profile must surface missing components and prevent inference until its required stack is present.
   - Required correction: add profile-aware validation for FLUX.2 diffusion + VAE + LLM and FLUX.1 diffusion + VAE + CLIP-L + T5XXL, while retaining custom/full-checkpoint support.
   - Severity: High

**Non-blocking improvements:**
- Keep architecture detection in the native engine rather than hard-coding model identity in Java.

**Verification performed:**
- Inspected `MainActivity.java` model import, profile selection, generation enablement and native call path.
- Inspected `native-lib.cpp` context construction.

### Aesthetic Executioner

**Verdict:** REJECTED

**Blocking findings:**

1. Initial adaptive-icon foreground extended too close to the adaptive icon mask boundary.
   - Evidence/reproduction: foreground ring occupied approximately x/y 18..90 in a 108x108 viewport, leaving limited safety margin for aggressive circular/squircle OEM masks.
   - Expected behavior: primary mark remains intact under supported Android adaptive-icon masks.
   - Required correction: move the primary ring/spark inward while preserving visual balance.
   - Severity: Medium

**Non-blocking improvements:**
- Keep the three primary workflows separated into Create, Models and Face swap rather than one long developer-oriented form.

**Verification performed:**
- Inspected adaptive-icon XML geometry.
- Inspected programmatic UI hierarchy, spacing, typography sizes, navigation widths and mobile overflow behavior in `MainActivity.java`.
- No Android emulator or physical-device rendering was available in the execution environment.

## Iteration 2 — Final

### Logic Inquisitor

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Non-blocking improvements:**
- Future work could add FLUX.2 reference-image editing using stable-diffusion.cpp's `flux2` reference preset.
- Real device profiling should determine the best quantization/resolution limits for each Snapdragon/Adreno generation.

**Verification performed:**
- Inspected complete revised `MainActivity.java`.
- Verified FLUX.2 Klein 4B/Base 4B/9B/Base 9B/Dev, FLUX.1 Krea/Dev/Schnell and generic model profiles.
- Verified profile-specific missing-file validation and pre-generation routing to Models.
- Verified separate Text CFG and distilled-guidance values are passed through Java JNI into `sd_sample_params_t.guidance.txt_cfg` and `distilled_guidance`.
- Verified existing cancellation, context caching, VAE tiling, model import, MediaStore export and self-face-swap paths remain present.
- Verified manifest launcher icon declarations and v1.1.0 version metadata.
- GitHub Actions run 33066921924 completed `:app:lintDebug`, `:app:assembleDebug` and the configured test task successfully.
- CI reported `BUILD SUCCESSFUL`, compileSdk 36, targetSdk 36, minSdk 29, and successful 16 KB zipalign verification.

### Aesthetic Executioner

**Verdict:** APPROVED

**Blocking findings:**
- None identified from the available inspection surface.

**Non-blocking improvements:**
- A physical-device screenshot pass would still be useful for OEM font scaling, unusual display cutouts and landscape ergonomics.
- A generated splash screen / richer empty-state artwork could add further polish later without changing workflow.

**Verification performed:**
- Inspected complete revised UI code rather than only the diff.
- Checked header hierarchy, logo placement, status chips, tabbed navigation, card hierarchy, prompt/result emphasis, model-profile callouts, face-swap preview labeling, button hierarchy and horizontal overflow strategy.
- Checked adaptive icon v26 and v33 resources, including themed monochrome icon support.
- Confirmed corrected foreground geometry stays farther inside the 108x108 adaptive-icon viewport.
- No Android emulator or physical-device rendering was available, so this approval is based on code/resource inspection plus successful Android resource compilation and lint.

## Final status

Both reviewers approved the same v1.1.0 implementation represented by CI build commit `6e8b10e9c61e1a8ea3d4ec2586367d4c50b8ac4c`. The subsequent commit adding this document does not modify application code or resources.


# v1.1.1 Crash/FLUX.2 Fix Review

Final reviewed application commit: `c26e8cc3de45ec3636d503da126d9bbd92e31545`  
Successful CI run: `33070442058`  
APK version: 1.1.1 (versionCode 3), targetSdk 36, minSdk 29.

## Iteration 1

### Logic Inquisitor

**Verdict:** REJECTED

**Blocking findings:**

1. FLUX.2 split diffusion weights could still be stored in the Full checkpoint slot.
   - Evidence: the existing `missingProfileFiles()` returned success immediately whenever the Full checkpoint path existed, even for FLUX.2 profiles.
   - Expected behavior: FLUX.2 Klein/Dev profiles must use Diffusion / transformer plus separate VAE and text encoder.
   - Required correction: block new Full checkpoint imports for FLUX.2, validate split slots, and migrate an unambiguous previously misplaced FLUX.2 Klein/Dev file.
   - Severity: High

2. Split-model inference used `auto_fit=true` without an explicit mobile residency policy.
   - Evidence: native context setup enabled Vulkan/auto-fit but did not set `backend`, `params_backend`, `max_vram`, or `stream_layers`. In the pinned stable-diffusion.cpp API, auto-fit ignores explicit backend assignments and GPU parameter residency can materially increase peak memory.
   - Expected behavior: FLUX.2 Klein on a 16 GB Android device should minimize both Vulkan residency and process RSS.
   - Required correction: explicit mobile-safe split-model backend/parameter placement with bounded GPU residency and streamed diffusion layers.
   - Severity: Critical

3. A previous force termination could not be distinguished from an ordinary recoverable generation error.
   - Evidence: native/OOM process termination bypasses the Java exception path and the app returned to the launcher with no persistent generation state.
   - Expected behavior: a subsequent launch should recognize an interrupted native generation and guide the user toward the safe first-run configuration.
   - Required correction: persist a generation-in-progress marker synchronously before entering native inference and clear it on normal completion/teardown.
   - Severity: Medium

**Verification performed:**
- Inspected `MainActivity.java` slot validation, import flow, profile state, generation lifecycle, and migration behavior.
- Inspected `native-lib.cpp` context construction.
- Inspected the pinned `stable-diffusion.h` fields for `backend`, `params_backend`, `max_vram`, `stream_layers`, `eager_load`, and `auto_fit`.
- Inspected the pinned stable-diffusion.cpp backend documentation for module/backend syntax and CPU/disk parameter residency.

### Aesthetic Executioner

**Verdict:** APPROVED

**Blocking findings:**
- None introduced by the fix.

**Non-blocking improvements:**
- The Full checkpoint control remains visually present for FLUX.2 profiles, but now gives an immediate explanatory message rather than accepting the wrong file. Dynamically hiding irrelevant rows could be a later polish improvement.

**Verification performed:**
- Inspected the complete revised Models/Create UI code.
- Confirmed the fix only changes guidance/status behavior and the FLUX.2 first-run canvas default; the previously approved tabbed layout, spacing, icon and hierarchy remain unchanged.
- No Android emulator or physical-device screenshot was available in the build environment.

## Iteration 2 — Final

### Logic Inquisitor

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Non-blocking improvements:**
- Real-device profiling can later tune the exact GPU budget for Adreno 830 instead of relying on the engine's automatic `-1` headroom budget.
- Capturing Android tombstone/Logcat output from a real device would provide definitive evidence if a vendor Vulkan driver still terminates under an unusual model/quantization combination.

**Verification performed:**
- Verified FLUX.2 profiles reject new Full checkpoint imports.
- Verified an existing unambiguously named FLUX.2 Klein/Dev file in Full checkpoint is migrated to Diffusion / transformer on startup when no diffusion path is already set.
- Verified obvious Qwen3.5 imports are rejected for FLUX.2 profiles.
- Verified Klein profiles default to 512×512 for the first run.
- Verified split-model native policy:
  - runtime backend: `diffusion=gpu,te=cpu,vae=cpu`
  - parameter backend: `diffusion=cpu,te=disk,vae=cpu`
  - `max_vram=-1`
  - `stream_layers=true`
  - `auto_fit=false`
  - `eager_load=false`
  - mobile Vulkan flash attention disabled for stability
- Verified full-checkpoint mode retains the prior auto-fit behavior.
- Verified native context cache key changes so an older residency policy cannot be reused.
- Verified persistent generation marker is committed before native inference, cleared on normal completion/failure/teardown, and produces a recovery message after unexpected process termination.
- GitHub Actions run 33070442058 completed successfully:
  - `:app:lintDebug`
  - `:app:assembleDebug`
  - configured unit-test task (no test sources)
  - compileSdk 36
  - targetSdk 36
  - minSdk 29
  - 16 KB alignment verification

### Aesthetic Executioner

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Verification performed:**
- Re-inspected the final UI code after the startup migration correction.
- Confirmed no layout/resource changes after the already-approved v1.1 visual design.
- Confirmed the recovery status message and FLUX.2 profile description fit the existing scroll/card UI without introducing fixed-size clipping.
- No emulator or physical-device rendering was available.

## Final status

Both reviewers approved application commit `c26e8cc3de45ec3636d503da126d9bbd92e31545`. The subsequent review-log commit only updates this document and does not alter the APK source or resources.


# v1.2.0 Progress / Preview / Resolution / Signing Review

Final reviewed application commit: `cc5aae7292258a9016db9ba15e3be5c2c10dbbb1`  
Successful CI run: `33073349244`  
APK version: 1.2.0 (versionCode 4), targetSdk 36, minSdk 29.

## Iteration 1

### Logic Inquisitor

**Verdict:** REJECTED

**Blocking findings:**

1. VAE-related log messages emitted during model initialization could be misclassified as the final VAE decode phase.
   - Evidence: `android_log_cb` switched to `PHASE_DECODING` whenever a line contained both a decode marker and VAE/first-stage marker, regardless of the current generation phase.
   - Expected behavior: final decode status may only be entered after conditioning/sampling has begun.
   - Required correction: constrain decode-log classification to phases CONDITIONING through DECODING.
   - Severity: Medium

**Non-blocking improvements:**
- Preview rendering could later be moved off the UI thread for very large custom resolutions, although it is opt-in and projected previews are already relatively lightweight.

**Verification performed:**
- Inspected the complete JNI progress/preview state flow in `native-lib.cpp`.
- Inspected Java polling, custom-resolution validation, preview rendering, stall detection, cancellation and final-image handoff in `MainActivity.java`.
- Inspected signing configuration and CI certificate verification.

### Aesthetic Executioner

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Non-blocking improvements:**
- A future physical-device pass could refine spacing around the two custom dimension fields at extreme Android font scaling.

**Verification performed:**
- Inspected the complete revised Create screen.
- Checked the custom width/height row, preview toggle/interval control, horizontal progress bar, progress title, status text and result preview area for fixed-size overflow risks.
- Confirmed all controls remain inside the existing vertical ScrollView and rectangular presets remain available.
- No Android emulator or physical-device renderer was available in the build environment.

## Iteration 2 — Final

### Logic Inquisitor

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Non-blocking improvements:**
- True percentage progress during model-weight loading is not available from stable-diffusion.cpp's public callback API; the implementation correctly reports it as an indeterminate named phase with elapsed/stall time instead of fabricating a percentage.
- Intermediate preview creation is optional because it adds some runtime and memory overhead.

**Verification performed:**
- Verified native progress phases: loading, conditioning, sampling, VAE decode, complete, cancelled, error.
- Verified real stable-diffusion.cpp `sd_set_progress_callback` integration.
- Verified optional `sd_set_preview_callback` integration using projected denoised previews with configurable intervals of 1/2/4 steps.
- Verified thread-safe native preview copying and Java polling through `nativeProgressSnapshot` and `nativePreviewSnapshot`.
- Verified stall detection reports unchanged native phase/step after three minutes without declaring a false freeze.
- Verified custom resolutions support rectangular width/height from 256 to 1536, requiring multiples of 64.
- Verified preset square and portrait/landscape sizes remain available.
- Verified versionCode 4 and versionName 1.2.0.
- Verified persistent sideload signing configuration and CI certificate pinning.
- GitHub Actions run 33073349244 passed build, lint, APK assembly, targetSdk 36 checks, 16 KB alignment and signing-certificate verification.

### Aesthetic Executioner

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Verification performed:**
- Re-inspected the complete final Create screen after the decode-phase correction.
- Confirmed no UI/resource changes after the already-approved layout review.
- Confirmed progress/status hierarchy remains readable and live previews reuse the existing result frame instead of adding another large competing surface.
- No emulator or physical-device rendering was available.

## Final status

Both reviewers approved application commit `cc5aae7292258a9016db9ba15e3be5c2c10dbbb1`. The subsequent review-log commit only updates this document and does not change the APK source/resources.

The v1.2.0 APK is signed with persistent sideload certificate SHA-256:
`6c4c89639285c16e367171b085115e436459644714eb81d4c22fd6e2164e879c`.

Because v1.1.1 was signed by a different ephemeral GitHub Actions debug key, Android cannot update v1.1.1 in place. One final uninstall is required for the transition to v1.2.0. All future APKs signed with the persistent v1.2.0 key can update in place when versionCode increases.


# v1.2.1 Progress Semantics / LoRA / Profile Cleanup Review

Final reviewed implementation commit: `c5213f72ea790e8ccdf4b84481f7c08d7f63ec13`  
Successful CI run: `33075469107`  
APK version: 1.2.1 (versionCode 5), targetSdk 36, minSdk 29.

## Iteration 1

### Logic Inquisitor

**Verdict:** REJECTED

**Blocking findings:**

1. The v1.2.0 progress callback conflated model tensor loading with diffusion sampling.
   - Evidence/reproduction: stable-diffusion.cpp's `pretty_bytes_progress()` and `pretty_progress()` both call the same global `sd_progress_cb_t`. v1.2.0 registered the sampling callback before `new_sd_ctx()`, so tensor counts from model loading could appear as apparent denoising steps; completion of those tensor counters could then lead the UI into a misleading decode state.
   - Expected behavior: model loading, lazy/runtime adapter loading, sampling and VAE decode must be distinct observable phases.
   - Required correction: use a dedicated model-loading callback during context creation, only treat a callback as a sample step after the engine logs that sampling actually started, and enter decode only on the engine's latent-decoding log.
   - Severity: High

2. Profile-hidden component paths could still be passed to native inference.
   - Evidence/reproduction: hiding CLIP-L/T5XXL for FLUX.2 did not clear their persisted paths, and the old generation call always forwarded every stored path.
   - Expected behavior: changing profiles should preserve saved files for later reuse but pass only components relevant to the selected architecture.
   - Required correction: compute profile-specific effective paths at generation time.
   - Severity: High

**Non-blocking improvements:**
- Sampling-step detection still depends on stable-diffusion.cpp's current log wording plus expected step count. This is substantially safer than v1.2.0, but an upstream dedicated phase callback would be preferable if added later.

**Verification performed:**
- Inspected stable-diffusion.cpp `pretty_progress()`, `pretty_bytes_progress()`, model-loader tensor progress, sampler start/completion logs, and latent-decode logs at the pinned runtime commit.
- Inspected the complete JNI callback state machine and profile-to-native component mapping.

### Aesthetic Executioner

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Non-blocking improvements:**
- Four LoRA rows lengthen the Models tab, but they remain inside the existing vertical ScrollView and keep the Create screen uncluttered.

**Verification performed:**
- Inspected the complete Models and Create screen code.
- Checked FLUX.2 hiding of Full checkpoint / CLIP-L / T5XXL, FLUX.1 hiding of Full checkpoint / LLM, and generic-profile visibility.
- Checked each LoRA row's fixed button widths, flexible label width, strength slider and filename line for narrow mobile layouts.
- No Android emulator or physical-device renderer was available in the build environment.

## Iteration 2 — Final

### Logic Inquisitor

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Non-blocking improvements:**
- Real-device timing will determine whether the safe CPU VAE backend is acceptably fast; the corrected phase display now makes that measurement meaningful.
- LoRA compatibility is architecture-dependent and cannot be inferred reliably from arbitrary filenames, so incompatible LoRAs are allowed to fail through the engine rather than being falsely classified by the UI.

**Verification performed:**
- Verified context/model loading uses a dedicated `load_progress_cb` and exposes true tensor X/Y progress.
- Verified sampling callback is installed only after context creation.
- Verified actual sampler start is gated by the engine's `sampling using ... method` log and requested sampling-step count.
- Verified runtime/lazy tensor progress is reported separately as conditioning/adapter loading.
- Verified VAE decode is entered only after the engine logs that latent decoding has begun; last sample step no longer guesses that decode started.
- Verified optional projected live previews remain wired through `sd_set_preview_callback`.
- Verified four runtime LoRA slots:
  - copied to app-private storage,
  - independent 0.00–2.00 multipliers,
  - passed as `sd_lora_t` arrays,
  - `LORA_APPLY_AT_RUNTIME` enabled,
  - no base-model context reload required when changing a LoRA.
- Verified FLUX.2 profiles pass diffusion + VAE + LLM only; CLIP-L, T5XXL and Full checkpoint are hidden and not forwarded.
- Verified FLUX.1 profiles retain CLIP-L/T5XXL and omit LLM/full-checkpoint inputs.
- Verified generic/auto profiles retain Full checkpoint capability because it can represent smaller self-contained SD/SDXL/other supported checkpoints, not only huge original FLUX weights.
- Verified versionCode 5 / versionName 1.2.1 and persistent signing certificate are unchanged from v1.2.0.
- GitHub Actions run 33075469107 passed native/Java compilation, lint, APK assembly, targetSdk 36 metadata, 16 KB alignment, version checks and signer fingerprint verification.

### Aesthetic Executioner

**Verdict:** APPROVED

**Blocking findings:**
- None.

**Verification performed:**
- Re-inspected the complete final Models/Create UI after profile-aware visibility and LoRA additions.
- Confirmed irrelevant FLUX.2 controls disappear rather than leaving disabled clutter.
- Confirmed LoRA controls remain grouped under Models and preserve the existing visual hierarchy.
- Confirmed progress text now distinguishes model tensors, lazy tensors/adapters, sampling, sampling-complete/pre-decode, and actual decode.
- Android resource compilation and lint passed.
- No emulator or physical-device rendering was available, so the visual verdict is based on source/resource inspection rather than a fabricated device screenshot.

## Final status

Both reviewers approved the same v1.2.1 implementation at commit `c5213f72ea790e8ccdf4b84481f7c08d7f63ec13`. The subsequent commit adding this review record changes documentation only.

CI run `33075469107` verified:
- package `com.localflux.studio`
- versionCode `5`
- versionName `1.2.1`
- compileSdk / targetSdk `36`
- minSdk `29`
- 16 KB alignment
- persistent sideload signer SHA-256 `6c4c89639285c16e367171b085115e436459644714eb81d4c22fd6e2164e879c`

Because v1.2.1 uses the same signer as v1.2.0 and has a higher versionCode, Android can install it directly over v1.2.0 while preserving app-private model files and settings.
