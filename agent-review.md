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
