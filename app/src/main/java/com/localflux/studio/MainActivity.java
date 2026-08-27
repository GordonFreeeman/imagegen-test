package com.localflux.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.pv.androidfacefusion.FaceDetector;
import com.pv.androidfacefusion.FaceEmbedder;
import com.pv.androidfacefusion.FaceFusionProcessor;
import com.pv.androidfacefusion.FaceSwapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static {
        System.loadLibrary("localflux");
    }

    private static native String nativeSystemInfo();
    private static native int[] nativeGenerate(
            String model, String diffusion, String vae, String clipL, String t5, String llm,
            String prompt, String negative, int width, int height, int steps, float guidance,
            long seed, boolean vaeTiling);
    private static native void nativeCancel();
    private static native void nativeUnload();

    private static final int BG = Color.rgb(9, 10, 16);
    private static final int CARD = Color.rgb(20, 22, 31);
    private static final int CARD_2 = Color.rgb(27, 29, 41);
    private static final int TEXT = Color.rgb(241, 242, 248);
    private static final int MUTED = Color.rgb(169, 174, 194);
    private static final int ACCENT = Color.rgb(141, 103, 255);
    private static final int ACCENT_2 = Color.rgb(82, 203, 190);
    private static final int DANGER = Color.rgb(238, 98, 112);

    private static final int REQ_MODEL = 1001;
    private static final int REQ_SOURCE = 1002;
    private static final int REQ_TARGET = 1003;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private final LinkedHashMap<String, String> roleLabels = new LinkedHashMap<>();
    private final Map<String, TextView> roleValues = new LinkedHashMap<>();
    private String pendingRole;

    private EditText promptInput;
    private EditText negativeInput;
    private Spinner sizeSpinner;
    private SeekBar stepsBar;
    private TextView stepsValue;
    private SeekBar guidanceBar;
    private TextView guidanceValue;
    private EditText seedInput;
    private CheckBox vaeTilingCheck;
    private Button generateButton;
    private Button cancelButton;
    private ProgressBar progress;
    private TextView status;
    private ImageView resultImage;
    private Button saveButton;

    private Bitmap currentBitmap;
    private Bitmap sourceBitmap;
    private Bitmap targetBitmap;
    private ImageView sourcePreview;
    private ImageView targetPreview;
    private CheckBox likenessConsent;
    private Button faceSetupButton;
    private Button faceSwapButton;
    private TextView faceStatus;

    private volatile boolean faceModelsReady = false;
    private FaceDetector faceDetector;
    private FaceEmbedder faceEmbedder;
    private FaceSwapper faceSwapper;
    private FaceFusionProcessor faceProcessor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("models", MODE_PRIVATE);

        roleLabels.put("model", "Full checkpoint");
        roleLabels.put("diffusion", "FLUX diffusion / transformer");
        roleLabels.put("vae", "VAE / AE");
        roleLabels.put("clip_l", "CLIP-L");
        roleLabels.put("t5", "T5XXL");
        roleLabels.put("llm", "LLM / Qwen encoder");

        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        setContentView(buildUi());
        refreshModelRows();
        refreshGenerateEnabled();
        refreshFaceEnabled();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = column();
        root.setPadding(dp(18), dp(24), dp(18), dp(48));
        scroll.addView(root);

        TextView eyebrow = text("LOCAL · PRIVATE · ARM64", 12, ACCENT_2, true);
        eyebrow.setLetterSpacing(0.14f);
        root.addView(eyebrow);

        TextView hero = text("Local Flux Studio", 32, TEXT, true);
        hero.setPadding(0, dp(5), 0, 0);
        root.addView(hero);

        TextView sub = text("FLUX-class image generation and self-face swapping, entirely on your phone.", 16, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        TextView runtime = text(nativeSystemInfo(), 11, MUTED, false);
        runtime.setTypeface(Typeface.MONOSPACE);
        runtime.setPadding(dp(12), dp(10), dp(12), dp(10));
        runtime.setBackground(roundRect(Color.rgb(15, 17, 24), 14, Color.rgb(47, 52, 68)));
        root.addView(runtime, matchWrap());

        root.addView(space(18));
        root.addView(buildModelCard());
        root.addView(space(14));
        root.addView(buildGenerationCard());
        root.addView(space(14));
        root.addView(buildFaceCard());

        TextView footer = text(
                "No prompt, photo, embedding, or generated image is uploaded by this app. Internet access is only used when you explicitly install the optional face models.",
                12, MUTED, false);
        footer.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(footer);

        return scroll;
    }

    private View buildModelCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("1 · Model stack"));
        card.addView(text(
                "Use a full checkpoint, or a split FLUX stack. For typical FLUX.1 GGUF: diffusion + VAE + CLIP-L + T5XXL. Qwen/LLM is optional and intended for architectures that require it.",
                13, MUTED, false));
        card.addView(space(10));

        for (Map.Entry<String, String> entry : roleLabels.entrySet()) {
            String role = entry.getKey();
            LinearLayout row = column();
            row.setPadding(0, dp(7), 0, dp(7));

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = text(entry.getValue(), 14, TEXT, true);
            line.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button choose = secondaryButton("Import");
            choose.setOnClickListener(v -> chooseModel(role));
            line.addView(choose, new LinearLayout.LayoutParams(dp(94), dp(42)));

            row.addView(line);

            TextView value = text("Not selected", 12, MUTED, false);
            value.setPadding(0, dp(3), dp(4), 0);
            value.setMaxLines(2);
            roleValues.put(role, value);
            row.addView(value);
            card.addView(row);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button unload = secondaryButton("Unload RAM");
        unload.setOnClickListener(v -> {
            nativeUnload();
            toast("Loaded diffusion context released from RAM.");
        });
        actions.addView(unload, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button clear = secondaryButton("Clear paths");
        clear.setOnClickListener(v -> confirmClearModels());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        cp.setMarginStart(dp(8));
        actions.addView(clear, cp);
        card.addView(actions);

        return card;
    }

    private View buildGenerationCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("2 · Generate"));

        promptInput = input("Prompt", 4);
        promptInput.setHint("dark fantasy knight beneath a blood-red eclipse, intricate armor, dramatic chiaroscuro…");
        card.addView(promptInput);

        negativeInput = input("Negative prompt (optional)", 2);
        negativeInput.setHint("low quality, artifacts, extra fingers…");
        card.addView(negativeInput);

        TextView sizeLabel = fieldLabel("Canvas");
        card.addView(sizeLabel);
        sizeSpinner = new Spinner(this);
        String[] sizes = {"512 × 512", "640 × 640", "768 × 768", "896 × 896", "1024 × 1024"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, sizes) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(15);
                v.setPadding(dp(12), dp(12), dp(12), dp(12));
                v.setBackground(roundRect(CARD_2, 12, Color.rgb(57, 61, 79)));
                return v;
            }
        };
        sizeSpinner.setAdapter(adapter);
        sizeSpinner.setSelection(2);
        card.addView(sizeSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        stepsValue = text("20", 13, ACCENT_2, true);
        card.addView(sliderLabel("Steps", stepsValue));
        stepsBar = new SeekBar(this);
        stepsBar.setMax(36);
        stepsBar.setProgress(16);
        stepsBar.setOnSeekBarChangeListener(simpleSeek(v -> stepsValue.setText(String.valueOf(v + 4))));
        card.addView(stepsBar);

        guidanceValue = text("3.5", 13, ACCENT_2, true);
        card.addView(sliderLabel("Distilled guidance", guidanceValue));
        guidanceBar = new SeekBar(this);
        guidanceBar.setMax(60);
        guidanceBar.setProgress(25);
        guidanceBar.setOnSeekBarChangeListener(simpleSeek(v -> guidanceValue.setText(String.format(Locale.US, "%.1f", 1.0f + v / 10f))));
        card.addView(guidanceBar);

        seedInput = input("Seed (-1 = random)", 1);
        seedInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        seedInput.setText("-1");
        card.addView(seedInput);

        vaeTilingCheck = new CheckBox(this);
        vaeTilingCheck.setText("VAE tiling · lower peak memory");
        vaeTilingCheck.setTextColor(TEXT);
        vaeTilingCheck.setChecked(true);
        vaeTilingCheck.setPadding(0, dp(6), 0, dp(8));
        card.addView(vaeTilingCheck);

        generateButton = primaryButton("Generate locally");
        generateButton.setOnClickListener(v -> startGeneration());
        card.addView(generateButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        cancelButton = dangerButton("Cancel generation");
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> {
            nativeCancel();
            status.setText("Cancellation requested…");
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        cancelLp.topMargin = dp(8);
        card.addView(cancelButton, cancelLp);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(32), dp(32));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(12);
        card.addView(progress, pp);

        status = text("Ready. A Q4-class FLUX model at 768² is the sensible starting point on a 16 GB Snapdragon flagship.", 12, MUTED, false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(8), 0, dp(10));
        card.addView(status);

        resultImage = new ImageView(this);
        resultImage.setAdjustViewBounds(true);
        resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImage.setBackground(roundRect(Color.rgb(12, 13, 19), 16, Color.rgb(45, 48, 62)));
        resultImage.setMinimumHeight(dp(260));
        card.addView(resultImage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        saveButton = secondaryButton("Save PNG to gallery");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveCurrent());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        saveLp.topMargin = dp(10);
        card.addView(saveButton, saveLp);

        return card;
    }

    private View buildFaceCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("3 · Self face swap"));
        card.addView(text(
                "On-device SCRFD detection + ArcFace identity embedding + INSwapper compositing. The source photo should be your own likeness or a person who explicitly authorized the swap.",
                13, MUTED, false));

        likenessConsent = new CheckBox(this);
        likenessConsent.setText("I confirm the source face is mine or explicitly authorized.");
        likenessConsent.setTextColor(TEXT);
        likenessConsent.setPadding(0, dp(10), 0, dp(8));
        likenessConsent.setOnCheckedChangeListener((b, checked) -> refreshFaceEnabled());
        card.addView(likenessConsent);

        faceSetupButton = primaryButton("Install / load face models");
        faceSetupButton.setOnClickListener(v -> setupFaceModels());
        card.addView(faceSetupButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        faceStatus = text("Requires ~735 MB of separately licensed InsightFace/INSwapper model weights on first setup.", 12, MUTED, false);
        faceStatus.setPadding(0, dp(7), 0, dp(10));
        card.addView(faceStatus);

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);

        sourcePreview = previewBox();
        targetPreview = previewBox();
        previewRow.addView(sourcePreview, new LinearLayout.LayoutParams(0, dp(170), 1f));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(170), 1f);
        tp.setMarginStart(dp(8));
        previewRow.addView(targetPreview, tp);
        card.addView(previewRow);

        LinearLayout pickerRow = new LinearLayout(this);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setPadding(0, dp(8), 0, 0);

        Button source = secondaryButton("Choose my face");
        source.setOnClickListener(v -> chooseImage(REQ_SOURCE));
        pickerRow.addView(source, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button target = secondaryButton("Choose artwork");
        target.setOnClickListener(v -> chooseImage(REQ_TARGET));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        tlp.setMarginStart(dp(8));
        pickerRow.addView(target, tlp);
        card.addView(pickerRow);

        Button useGenerated = secondaryButton("Use current generated image as target");
        useGenerated.setOnClickListener(v -> {
            if (currentBitmap == null) {
                toast("Generate or load a target image first.");
                return;
            }
            targetBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, false);
            targetPreview.setImageBitmap(targetBitmap);
            refreshFaceEnabled();
        });
        LinearLayout.LayoutParams ug = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        ug.topMargin = dp(8);
        card.addView(useGenerated, ug);

        faceSwapButton = primaryButton("Swap into first detected face");
        faceSwapButton.setOnClickListener(v -> runFaceSwap());
        LinearLayout.LayoutParams fs = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        fs.topMargin = dp(10);
        card.addView(faceSwapButton, fs);

        TextView note = text(
                "For sexual or otherwise sensitive artwork, only use consenting adults' likenesses. The app does not provide cloud upload or remote face processing.",
                11, MUTED, false);
        note.setPadding(0, dp(10), 0, 0);
        card.addView(note);

        return card;
    }

    private void chooseModel(String role) {
        pendingRole = role;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_MODEL);
    }

    private void chooseImage(int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        if (requestCode == REQ_MODEL && pendingRole != null) {
            importModel(pendingRole, uri);
        } else if (requestCode == REQ_SOURCE || requestCode == REQ_TARGET) {
            loadImageForSwap(requestCode, uri);
        }
    }

    private void importModel(String role, Uri uri) {
        final String roleCopy = role;
        setBusy(true, "Importing " + roleLabels.get(roleCopy) + " into app-private model storage…");
        worker.execute(() -> {
            File dest = null;
            try {
                String display = displayName(uri);
                File dir = new File(getFilesDir(), "models");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create model directory");
                String safe = display.replaceAll("[^A-Za-z0-9._-]", "_");
                dest = new File(dir, roleCopy + "-" + safe + ".partial");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dest)) {
                    if (in == null) throw new Exception("Could not open selected file");
                    byte[] buf = new byte[1024 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                }
                File finalFile = new File(dir, roleCopy + "-" + safe);
                if (finalFile.exists()) finalFile.delete();
                if (!dest.renameTo(finalFile)) throw new Exception("Could not finalize imported model");
                prefs.edit()
                        .putString(roleCopy + "_path", finalFile.getAbsolutePath())
                        .putString(roleCopy + "_name", display)
                        .putLong(roleCopy + "_bytes", finalFile.length())
                        .apply();
                runOnUiThread(() -> {
                    nativeUnload();
                    refreshModelRows();
                    refreshGenerateEnabled();
                    setBusy(false, "Model imported. Loaded diffusion context was invalidated.");
                });
            } catch (Exception e) {
                if (dest != null) dest.delete();
                runOnUiThread(() -> setBusy(false, "Import failed: " + e.getMessage()));
            }
        });
    }

    private void loadImageForSwap(int requestCode, Uri uri) {
        faceStatus.setText("Loading image…");
        worker.execute(() -> {
            try {
                Bitmap b = decodeSampled(uri, 2048);
                if (b == null) throw new Exception("Could not decode image");
                runOnUiThread(() -> {
                    if (requestCode == REQ_SOURCE) {
                        sourceBitmap = b;
                        sourcePreview.setImageBitmap(b);
                    } else {
                        targetBitmap = b;
                        targetPreview.setImageBitmap(b);
                    }
                    faceStatus.setText("Image ready.");
                    refreshFaceEnabled();
                });
            } catch (Exception e) {
                runOnUiThread(() -> faceStatus.setText("Image load failed: " + e.getMessage()));
            }
        });
    }

    private void startGeneration() {
        String model = prefPath("model");
        String diffusion = prefPath("diffusion");
        if (model.isEmpty() && diffusion.isEmpty()) {
            toast("Import a full checkpoint or diffusion model first.");
            return;
        }
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            toast("Enter a prompt first.");
            return;
        }

        int[] sizes = {512, 640, 768, 896, 1024};
        int side = sizes[Math.max(0, sizeSpinner.getSelectedItemPosition())];
        int steps = stepsBar.getProgress() + 4;
        float guidance = 1.0f + guidanceBar.getProgress() / 10f;
        long seed;
        try {
            seed = Long.parseLong(seedInput.getText().toString().trim());
        } catch (Exception e) {
            seed = -1;
        }

        warnThermalIfNeeded();
        setGenerating(true);
        status.setText("Loading/caching model stack and generating locally. First run is the slowest…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final long finalSeed = seed;
        worker.execute(() -> {
            try {
                int[] pixels = nativeGenerate(
                        model, diffusion, prefPath("vae"), prefPath("clip_l"), prefPath("t5"), prefPath("llm"),
                        prompt, negativeInput.getText().toString(), side, side, steps, guidance, finalSeed,
                        vaeTilingCheck.isChecked());
                if (pixels == null || pixels.length != side * side) {
                    throw new Exception("Native generator returned no image");
                }
                Bitmap b = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
                runOnUiThread(() -> {
                    currentBitmap = b;
                    resultImage.setImageBitmap(b);
                    saveButton.setEnabled(true);
                    status.setText("Done. Model stays cached in RAM for the next generation.");
                    setGenerating(false);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    status.setText("Generation failed: " + safeMessage(e));
                    setGenerating(false);
                });
            }
        });
    }

    private void setGenerating(boolean active) {
        generateButton.setEnabled(!active && hasGenerationModel());
        cancelButton.setVisibility(active ? View.VISIBLE : View.GONE);
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void setBusy(boolean active, String message) {
        if (status != null) status.setText(message);
        if (progress != null) progress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (generateButton != null) generateButton.setEnabled(!active && hasGenerationModel());
    }

    private void setupFaceModels() {
        if (!likenessConsent.isChecked()) {
            toast("Confirm likeness authorization first.");
            return;
        }
        faceSetupButton.setEnabled(false);
        faceSwapButton.setEnabled(false);
        faceStatus.setText("Loading face detector… first setup may download ~735 MB.");
        worker.execute(() -> {
            try {
                faceDetector = new FaceDetector(this);
                faceDetector.initialize();
                runOnUiThread(() -> faceStatus.setText("Loading ArcFace embedder…"));

                faceEmbedder = new FaceEmbedder(this);
                faceEmbedder.initialize();
                runOnUiThread(() -> faceStatus.setText("Loading INSwapper + EMAP…"));

                faceSwapper = new FaceSwapper(this);
                faceSwapper.initialize();

                faceProcessor = new FaceFusionProcessor(faceDetector, faceEmbedder, faceSwapper);
                faceModelsReady = true;
                runOnUiThread(() -> {
                    faceStatus.setText("Face models ready. Processing remains on-device.");
                    faceSetupButton.setText("Face models loaded");
                    refreshFaceEnabled();
                });
            } catch (Throwable e) {
                faceModelsReady = false;
                runOnUiThread(() -> {
                    faceStatus.setText("Face model setup failed: " + safeMessage(e));
                    faceSetupButton.setEnabled(true);
                    refreshFaceEnabled();
                });
            }
        });
    }

    private void runFaceSwap() {
        if (!likenessConsent.isChecked() || !faceModelsReady || sourceBitmap == null || targetBitmap == null) return;
        faceSwapButton.setEnabled(false);
        faceStatus.setText("Detecting, embedding and compositing locally…");
        worker.execute(() -> {
            try {
                Bitmap swapped = faceProcessor.processFaceFusion(sourceBitmap, targetBitmap, 0);
                runOnUiThread(() -> {
                    currentBitmap = swapped;
                    resultImage.setImageBitmap(swapped);
                    saveButton.setEnabled(true);
                    faceStatus.setText("Face swap complete. Result is now the current image.");
                    refreshFaceEnabled();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    faceStatus.setText("Face swap failed: " + safeMessage(e));
                    refreshFaceEnabled();
                });
            }
        });
    }

    private void refreshGenerateEnabled() {
        if (generateButton != null) generateButton.setEnabled(hasGenerationModel());
    }

    private boolean hasGenerationModel() {
        return !prefPath("model").isEmpty() || !prefPath("diffusion").isEmpty();
    }

    private void refreshFaceEnabled() {
        if (faceSetupButton != null) faceSetupButton.setEnabled(likenessConsent.isChecked() && !faceModelsReady);
        if (faceSwapButton != null) {
            faceSwapButton.setEnabled(likenessConsent.isChecked() && faceModelsReady && sourceBitmap != null && targetBitmap != null);
        }
    }

    private void refreshModelRows() {
        for (String role : roleLabels.keySet()) {
            TextView v = roleValues.get(role);
            if (v == null) continue;
            String path = prefPath(role);
            if (path.isEmpty()) {
                v.setText("Not selected");
                v.setTextColor(MUTED);
            } else {
                File f = new File(path);
                String name = prefs.getString(role + "_name", f.getName());
                long bytes = prefs.getLong(role + "_bytes", f.exists() ? f.length() : 0);
                v.setText(name + " · " + humanBytes(bytes));
                v.setTextColor(f.exists() ? ACCENT_2 : DANGER);
            }
        }
    }

    private String prefPath(String role) {
        String p = prefs.getString(role + "_path", "");
        if (p == null || p.isEmpty()) return "";
        return new File(p).exists() ? p : "";
    }

    private void confirmClearModels() {
        new AlertDialog.Builder(this)
                .setTitle("Clear model paths?")
                .setMessage("This forgets configured model slots and unloads the native context. Imported files remain in app storage so you can remove them later from Android app storage if needed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    nativeUnload();
                    SharedPreferences.Editor e = prefs.edit();
                    for (String role : roleLabels.keySet()) {
                        e.remove(role + "_path").remove(role + "_name").remove(role + "_bytes");
                    }
                    e.apply();
                    refreshModelRows();
                    refreshGenerateEnabled();
                }).show();
    }

    private void saveCurrent() {
        if (currentBitmap == null) return;
        worker.execute(() -> {
            try {
                Uri uri = saveBitmap(currentBitmap);
                runOnUiThread(() -> toast("Saved to Pictures/LocalFluxStudio"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Save failed: " + e.getMessage()));
            }
        });
    }

    private Uri saveBitmap(Bitmap bitmap) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "local-flux-" + System.currentTimeMillis() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LocalFluxStudio");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("MediaStore insert failed");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new Exception("PNG encode failed");
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
        return uri;
    }

    private Bitmap decodeSampled(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "model-" + System.currentTimeMillis();
    }

    private void warnThermalIfNeeded() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
                toast("Device is already hot. Generation may throttle heavily.");
            }
        } catch (Exception ignored) {}
    }

    private LinearLayout card() {
        LinearLayout l = column();
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(roundRect(CARD, 20, Color.rgb(43, 46, 61)));
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView v = text(s, 20, TEXT, true);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private EditText input(String label, int lines) {
        LinearLayout holder = column();
        TextView l = fieldLabel(label);
        holder.addView(l);

        EditText e = new EditText(this);
        e.setTextColor(TEXT);
        e.setHintTextColor(Color.rgb(108, 113, 132));
        e.setTextSize(15);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(lines);
        e.setMaxLines(Math.max(lines, 6));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(roundRect(CARD_2, 12, Color.rgb(57, 61, 79)));
        holder.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Return EditText while inserting holder through a lightweight wrapper trick.
        // The caller adds the EditText directly, so store the label in its contentDescription.
        e.setContentDescription(label);
        e.setTag(holder);
        return new LabeledEditTextProxy(this, holder, e);
    }

    /**
     * EditText subclass whose actual view is still a normal EditText. The proxy exists so callers can
     * add one object while we retain a label above it without XML.
     */
    private static class LabeledEditTextProxy extends EditText {
        final LinearLayout holder;
        final EditText delegate;
        LabeledEditTextProxy(MainActivity c, LinearLayout holder, EditText delegate) {
            super(c);
            this.holder = holder;
            this.delegate = delegate;
        }
        @Override public android.view.ViewParent getParent() { return super.getParent(); }
    }

    private TextView fieldLabel(String s) {
        TextView v = text(s, 12, MUTED, true);
        v.setPadding(0, dp(10), 0, dp(5));
        return v;
    }

    private LinearLayout sliderLabel(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        TextView left = text(label, 12, MUTED, true);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        return row;
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(java.util.function.IntConsumer c) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { c.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private ImageView previewBox() {
        ImageView i = new ImageView(this);
        i.setScaleType(ImageView.ScaleType.CENTER_CROP);
        i.setBackground(roundRect(Color.rgb(12, 13, 19), 14, Color.rgb(48, 51, 66)));
        return i;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(roundRect(ACCENT, 14, ACCENT));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackground(roundRect(CARD_2, 12, Color.rgb(64, 68, 88)));
        return b;
    }

    private Button dangerButton(String label) {
        Button b = secondaryButton(label);
        b.setTextColor(Color.rgb(255, 219, 223));
        b.setBackground(roundRect(Color.rgb(74, 30, 38), 12, DANGER));
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024.0;
            i++;
        }
        return String.format(Locale.US, i >= 3 ? "%.2f %s" : "%.1f %s", v, units[i]);
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return t.getClass().getSimpleName();
        return m;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
