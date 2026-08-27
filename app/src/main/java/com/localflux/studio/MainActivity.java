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
            String prompt, String negative, int width, int height, int steps,
            float textCfg, float distilledGuidance, long seed, boolean vaeTiling);
    private static native void nativeCancel();
    private static native void nativeUnload();

    private static final int BG = Color.rgb(8, 8, 14);
    private static final int BG_2 = Color.rgb(20, 14, 31);
    private static final int CARD = Color.rgb(22, 21, 33);
    private static final int CARD_2 = Color.rgb(31, 29, 46);
    private static final int TEXT = Color.rgb(246, 245, 252);
    private static final int MUTED = Color.rgb(176, 178, 198);
    private static final int ACCENT = Color.rgb(150, 108, 255);
    private static final int ACCENT_2 = Color.rgb(91, 218, 196);
    private static final int GOLD = Color.rgb(255, 193, 105);
    private static final int DANGER = Color.rgb(238, 98, 112);

    private static final int REQ_MODEL = 1001;
    private static final int REQ_SOURCE = 1002;
    private static final int REQ_TARGET = 1003;

    private static final String[] MODEL_PROFILES = {
            "Auto / custom",
            "FLUX.2 Klein 4B · recommended",
            "FLUX.2 Klein Base 4B · quality",
            "FLUX.2 Klein 9B · experimental",
            "FLUX.2 Klein Base 9B · quality / heavy",
            "FLUX.2 Dev 32B · impractical on 16 GB",
            "FLUX.1 Krea / Dev",
            "FLUX.1 Schnell",
            "Other stable-diffusion.cpp model"
    };

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
    private SeekBar cfgBar;
    private TextView cfgValue;
    private SeekBar distilledBar;
    private TextView distilledValue;
    private EditText seedInput;
    private CheckBox vaeTilingCheck;
    private Button generateButton;
    private Button cancelButton;
    private ProgressBar progress;
    private TextView status;
    private ImageView resultImage;
    private Button saveButton;
    private Spinner profileSpinner;
    private TextView profileInfo;
    private TextView modelSummary;
    private LinearLayout generationCard;
    private LinearLayout modelCard;
    private LinearLayout faceCard;
    private Button createTab;
    private Button modelsTab;
    private Button faceTab;

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
        roleLabels.put("diffusion", "Diffusion / transformer");
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

        if (prefs.getBoolean("generation_in_progress", false)) {
            prefs.edit().putBoolean("generation_in_progress", false).apply();
            if (sizeSpinner != null) sizeSpinner.setSelection(0);
            if (status != null) {
                status.setText("The previous generation ended with an unexpected process termination. "
                        + "Mobile-safe memory mode is enabled; retry starts at 512 × 512.");
                status.setTextColor(GOLD);
            }
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(backgroundGradient());

        LinearLayout root = column();
        root.setPadding(dp(18), dp(22), dp(18), dp(52));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.localflux.studio.R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout titles = column();
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.setMarginStart(dp(12));
        TextView eyebrow = text("ON-DEVICE CREATIVE AI", 11, ACCENT_2, true);
        eyebrow.setLetterSpacing(0.12f);
        titles.addView(eyebrow);
        TextView hero = text("Local Flux Studio", 29, TEXT, true);
        hero.setPadding(0, dp(2), 0, 0);
        titles.addView(hero);
        titles.addView(text("Private generation · modern model stacks · self face swap", 13, MUTED, false));
        header.addView(titles, titlesLp);
        root.addView(header);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(14), 0, dp(16));
        chips.addView(chip("VULKAN"));
        LinearLayout.LayoutParams chipGap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipGap.setMarginStart(dp(7));
        chips.addView(chip("100% LOCAL"), chipGap);
        LinearLayout.LayoutParams chipGap2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipGap2.setMarginStart(dp(7));
        chips.addView(chip("SDK 36"), chipGap2);
        root.addView(chips);

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(3), dp(3), dp(3), dp(3));
        nav.setBackground(roundRect(Color.rgb(16, 15, 25), 17, Color.rgb(47, 42, 66)));

        createTab = tabButton("Create");
        modelsTab = tabButton("Models");
        faceTab = tabButton("Face swap");
        createTab.setOnClickListener(v -> switchTab(0));
        modelsTab.setOnClickListener(v -> switchTab(1));
        faceTab.setOnClickListener(v -> switchTab(2));
        nav.addView(createTab, new LinearLayout.LayoutParams(dp(112), dp(46)));
        nav.addView(modelsTab, new LinearLayout.LayoutParams(dp(112), dp(46)));
        nav.addView(faceTab, new LinearLayout.LayoutParams(dp(118), dp(46)));
        navScroll.addView(nav);
        root.addView(navScroll, matchWrap());

        root.addView(space(16));

        // Build Create first so model presets can safely tune its sampling controls.
        generationCard = (LinearLayout) buildGenerationCard();
        modelCard = (LinearLayout) buildModelCard();
        faceCard = (LinearLayout) buildFaceCard();

        root.addView(generationCard);
        root.addView(modelCard);
        root.addView(faceCard);

        TextView footer = text(
                "Your prompts, source photos, face embeddings and generated images stay on this device. Network access is only used when you explicitly install optional face models.",
                11, MUTED, false);
        footer.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(footer);

        switchTab(0);
        return scroll;
    }

    private View buildModelCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("Model library"));
        card.addView(text(
                "The engine auto-detects architecture. Profiles below simply tell the UI which files belong together and apply sensible sampling defaults.",
                13, MUTED, false));

        card.addView(fieldLabel("Model profile"));
        profileSpinner = styledSpinner(MODEL_PROFILES);
        card.addView(profileSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        profileInfo = text("", 12, TEXT, false);
        profileInfo.setPadding(dp(13), dp(11), dp(13), dp(11));
        profileInfo.setBackground(roundRect(Color.rgb(27, 23, 42), 14, Color.rgb(75, 61, 105)));
        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        profileLp.topMargin = dp(9);
        card.addView(profileInfo, profileLp);

        profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt("model_profile", position).apply();
                applyProfile(position, true);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        int savedProfile = Math.max(0, Math.min(MODEL_PROFILES.length - 1, prefs.getInt("model_profile", 1)));
        profileSpinner.setSelection(savedProfile);

        TextView filesTitle = text("MODEL FILES", 11, ACCENT_2, true);
        filesTitle.setLetterSpacing(0.1f);
        filesTitle.setPadding(0, dp(18), 0, dp(3));
        card.addView(filesTitle);

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
            line.addView(choose, new LinearLayout.LayoutParams(dp(92), dp(42)));
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
        actions.setPadding(0, dp(10), 0, 0);

        Button unload = secondaryButton("Unload RAM");
        unload.setOnClickListener(v -> {
            nativeUnload();
            toast("Loaded inference context released from RAM.");
        });
        actions.addView(unload, new LinearLayout.LayoutParams(0, dp(47), 1f));

        Button clear = secondaryButton("Clear paths");
        clear.setOnClickListener(v -> confirmClearModels());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(47), 1f);
        cp.setMarginStart(dp(8));
        actions.addView(clear, cp);
        card.addView(actions);

        TextView runtimeTitle = text("RUNTIME", 11, GOLD, true);
        runtimeTitle.setLetterSpacing(0.1f);
        runtimeTitle.setPadding(0, dp(18), 0, dp(5));
        card.addView(runtimeTitle);

        TextView runtime = text(nativeSystemInfo(), 10, MUTED, false);
        runtime.setTypeface(Typeface.MONOSPACE);
        runtime.setPadding(dp(12), dp(10), dp(12), dp(10));
        runtime.setBackground(roundRect(Color.rgb(13, 13, 21), 13, Color.rgb(49, 46, 65)));
        card.addView(runtime, matchWrap());

        return card;
    }

    private View buildGenerationCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("Create"));

        modelSummary = text("No model configured", 12, TEXT, true);
        modelSummary.setPadding(dp(12), dp(9), dp(12), dp(9));
        modelSummary.setBackground(roundRect(Color.rgb(35, 29, 53), 14, Color.rgb(79, 62, 116)));
        card.addView(modelSummary, matchWrap());

        card.addView(fieldLabel("Prompt"));
        promptInput = input(4);
        promptInput.setHint("dark fantasy knight beneath a blood-red eclipse, intricate armor, cinematic chiaroscuro…");
        card.addView(promptInput);

        card.addView(fieldLabel("Negative prompt · optional"));
        negativeInput = input(2);
        negativeInput.setHint("low quality, artifacts, malformed anatomy…");
        card.addView(negativeInput);

        card.addView(fieldLabel("Canvas"));
        sizeSpinner = styledSpinner(new String[]{"512 × 512", "640 × 640", "768 × 768", "896 × 896", "1024 × 1024"});
        sizeSpinner.setSelection(2);
        card.addView(sizeSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        TextView samplingTitle = text("SAMPLING", 11, ACCENT_2, true);
        samplingTitle.setLetterSpacing(0.1f);
        samplingTitle.setPadding(0, dp(18), 0, dp(2));
        card.addView(samplingTitle);

        stepsValue = text("20", 13, ACCENT_2, true);
        card.addView(sliderLabel("Steps", stepsValue));
        stepsBar = new SeekBar(this);
        stepsBar.setMax(36);
        stepsBar.setProgress(16);
        stepsBar.setOnSeekBarChangeListener(simpleSeek(v -> stepsValue.setText(String.valueOf(v + 4))));
        card.addView(stepsBar);

        cfgValue = text("1.0", 13, ACCENT_2, true);
        card.addView(sliderLabel("Text CFG", cfgValue));
        cfgBar = new SeekBar(this);
        cfgBar.setMax(90);
        cfgBar.setProgress(5);
        cfgBar.setOnSeekBarChangeListener(simpleSeek(v -> cfgValue.setText(String.format(Locale.US, "%.1f", 0.5f + v / 10f))));
        card.addView(cfgBar);

        distilledValue = text("3.5", 13, ACCENT_2, true);
        card.addView(sliderLabel("Distilled guidance", distilledValue));
        distilledBar = new SeekBar(this);
        distilledBar.setMax(70);
        distilledBar.setProgress(35);
        distilledBar.setOnSeekBarChangeListener(simpleSeek(v -> distilledValue.setText(String.format(Locale.US, "%.1f", v / 10f))));
        card.addView(distilledBar);

        TextView guidanceHint = text(
                "FLUX.2 mainly uses Text CFG. FLUX.1 Dev/Krea commonly uses distilled guidance. Model profiles set both values independently.",
                11, MUTED, false);
        guidanceHint.setPadding(dp(2), 0, dp(2), dp(5));
        card.addView(guidanceHint);

        card.addView(fieldLabel("Seed · -1 = random"));
        seedInput = input(1);
        seedInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        seedInput.setText("-1");
        card.addView(seedInput);

        vaeTilingCheck = new CheckBox(this);
        vaeTilingCheck.setText("Memory saver · VAE tiling");
        vaeTilingCheck.setTextColor(TEXT);
        vaeTilingCheck.setChecked(true);
        vaeTilingCheck.setPadding(0, dp(7), 0, dp(10));
        card.addView(vaeTilingCheck);

        generateButton = primaryButton("✦  Generate locally");
        generateButton.setOnClickListener(v -> startGeneration());
        card.addView(generateButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

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
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(34), dp(34));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(13);
        card.addView(progress, pp);

        status = text("Ready. FLUX.2 Klein 4B Q4 at 768² is the recommended starting point for a 16 GB phone.", 12, MUTED, false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(dp(5), dp(9), dp(5), dp(12));
        card.addView(status);

        resultImage = new ImageView(this);
        resultImage.setAdjustViewBounds(true);
        resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImage.setBackground(roundRect(Color.rgb(11, 11, 18), 18, Color.rgb(52, 48, 69)));
        resultImage.setMinimumHeight(dp(280));
        card.addView(resultImage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        saveButton = secondaryButton("Save PNG to gallery");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveCurrent());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        saveLp.topMargin = dp(10);
        card.addView(saveButton, saveLp);

        return card;
    }

    private View buildFaceCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("Face swap"));
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

        LinearLayout previewLabels = new LinearLayout(this);
        previewLabels.setOrientation(LinearLayout.HORIZONTAL);
        TextView myFaceLabel = text("MY FACE", 11, ACCENT_2, true);
        TextView artworkLabel = text("ARTWORK / TARGET", 11, ACCENT_2, true);
        previewLabels.addView(myFaceLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams artworkLabelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        artworkLabelLp.setMarginStart(dp(8));
        previewLabels.addView(artworkLabel, artworkLabelLp);
        card.addView(previewLabels);

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
        int profile = currentProfile();
        if ("model".equals(role) && isFlux2Profile(profile)) {
            toast("FLUX.2 uses split weights. Import the Klein/Dev GGUF under Diffusion / transformer.");
            return;
        }
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
                String fileProblem = modelFileProblem(roleCopy, display);
                if (!fileProblem.isEmpty()) throw new Exception(fileProblem);
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
                SharedPreferences.Editor editor = prefs.edit()
                        .putString(roleCopy + "_path", finalFile.getAbsolutePath())
                        .putString(roleCopy + "_name", display)
                        .putLong(roleCopy + "_bytes", finalFile.length());
                // A full checkpoint and a split diffusion/transformer are alternative roots.
                // Clear the other root to prevent an invalid mixed context.
                if ("model".equals(roleCopy)) {
                    editor.remove("diffusion_path").remove("diffusion_name").remove("diffusion_bytes");
                } else if ("diffusion".equals(roleCopy)) {
                    editor.remove("model_path").remove("model_name").remove("model_bytes");
                }
                editor.apply();
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
        String missing = missingProfileFiles();
        if (!missing.isEmpty()) {
            toast("Model setup incomplete: " + missing);
            switchTab(1);
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
        float textCfg = 0.5f + cfgBar.getProgress() / 10f;
        float distilledGuidance = distilledBar.getProgress() / 10f;
        long seed;
        try {
            seed = Long.parseLong(seedInput.getText().toString().trim());
        } catch (Exception e) {
            seed = -1;
        }

        warnThermalIfNeeded();
        prefs.edit().putBoolean("generation_in_progress", true).commit();
        setGenerating(true);
        status.setText(diffusion.isEmpty()
                ? "Loading/caching model stack and generating locally. First run is the slowest…"
                : "Mobile-safe mode: loading text/VAE on CPU and streaming diffusion weights to Vulkan…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final long finalSeed = seed;
        worker.execute(() -> {
            try {
                int[] pixels = nativeGenerate(
                        model, diffusion, prefPath("vae"), prefPath("clip_l"), prefPath("t5"), prefPath("llm"),
                        prompt, negativeInput.getText().toString(), side, side, steps,
                        textCfg, distilledGuidance, finalSeed, vaeTilingCheck.isChecked());
                if (pixels == null || pixels.length != side * side) {
                    throw new Exception("Native generator returned no image");
                }
                Bitmap b = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
                runOnUiThread(() -> {
                    prefs.edit().putBoolean("generation_in_progress", false).apply();
                    currentBitmap = b;
                    resultImage.setImageBitmap(b);
                    saveButton.setEnabled(true);
                    status.setText("Done. Model stays cached in RAM for the next generation.");
                    setGenerating(false);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    prefs.edit().putBoolean("generation_in_progress", false).apply();
                    status.setText("Generation failed: " + safeMessage(e));
                    setGenerating(false);
                });
            }
        });
    }

    private void setGenerating(boolean active) {
        generateButton.setEnabled(!active && missingProfileFiles().isEmpty());
        cancelButton.setVisibility(active ? View.VISIBLE : View.GONE);
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void setBusy(boolean active, String message) {
        if (status != null) status.setText(message);
        if (progress != null) progress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (generateButton != null) generateButton.setEnabled(!active && missingProfileFiles().isEmpty());
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
        if (generateButton != null) generateButton.setEnabled(missingProfileFiles().isEmpty());
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
        refreshModelSummary();
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

    private Spinner styledSpinner(String[] entries) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, entries) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(14);
                v.setPadding(dp(13), dp(12), dp(13), dp(12));
                v.setBackground(roundRect(CARD_2, 13, Color.rgb(62, 57, 82)));
                return v;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(14);
                v.setPadding(dp(15), dp(14), dp(15), dp(14));
                v.setBackgroundColor(Color.rgb(35, 32, 49));
                return v;
            }
        };
        spinner.setAdapter(adapter);
        return spinner;
    }

    private TextView chip(String label) {
        TextView v = text(label, 10, TEXT, true);
        v.setLetterSpacing(0.08f);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(6), dp(10), dp(6));
        v.setBackground(roundRect(Color.rgb(28, 25, 42), 20, Color.rgb(66, 57, 91)));
        return v;
    }

    private Button tabButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setStateListAnimator(null);
        return b;
    }

    private void switchTab(int tab) {
        if (generationCard == null || modelCard == null || faceCard == null) return;
        generationCard.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        modelCard.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        faceCard.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        setTabState(createTab, tab == 0);
        setTabState(modelsTab, tab == 1);
        setTabState(faceTab, tab == 2);
    }

    private void setTabState(Button b, boolean active) {
        if (b == null) return;
        b.setTextColor(active ? Color.WHITE : MUTED);
        b.setBackground(active
                ? gradientRect(new int[]{Color.rgb(123, 83, 242), Color.rgb(157, 103, 255)}, 14, Color.rgb(166, 127, 255))
                : roundRect(Color.TRANSPARENT, 14, Color.TRANSPARENT));
    }

    private void applyProfile(int p, boolean tuneSampling) {
        if (p < 0 || p >= MODEL_PROFILES.length) p = 0;
        if (profileInfo != null) profileInfo.setText(profileDescription(p));

        repairFlux2SlotAssignment(p);

        int steps = 20;
        float cfg = 7.0f;
        float distilled = 3.5f;
        int canvasIndex = 2;
        switch (p) {
            case 1:
                steps = 4; cfg = 1.0f; distilled = 3.5f; canvasIndex = 0; break;
            case 2:
                steps = 20; cfg = 4.0f; distilled = 3.5f; canvasIndex = 0; break;
            case 3:
                steps = 4; cfg = 1.0f; distilled = 3.5f; canvasIndex = 0; break;
            case 4:
                steps = 20; cfg = 4.0f; distilled = 3.5f; canvasIndex = 0; break;
            case 5:
                steps = 20; cfg = 1.0f; distilled = 3.5f; canvasIndex = 0; break;
            case 6:
                steps = 20; cfg = 1.0f; distilled = 3.5f; break;
            case 7:
                steps = 4; cfg = 1.0f; distilled = 3.5f; break;
            default:
                break;
        }
        if (tuneSampling && stepsBar != null && cfgBar != null && distilledBar != null) {
            setSteps(steps);
            setTextCfg(cfg);
            setDistilledGuidance(distilled);
            if (sizeSpinner != null && isFlux2Profile(p)) sizeSpinner.setSelection(canvasIndex);
        }
        if (!roleValues.isEmpty()) refreshModelRows();
        else refreshModelSummary();
        refreshGenerateEnabled();
    }

    private String profileDescription(int p) {
        switch (p) {
            case 1:
                return "BEST PHONE FIT · FLUX.2 Klein 4B\nImport the Klein GGUF under Diffusion / transformer, plus FLUX.2 VAE/AE and Qwen3 4B under LLM. The app uses mobile-safe streamed Vulkan residency and starts at 512².";
            case 2:
                return "QUALITY PHONE PROFILE · FLUX.2 Klein Base 4B\nSame files as Klein 4B, but the base model is intended for a fuller ~20-step sampling run and higher CFG.";
            case 3:
                return "EXPERIMENTAL · FLUX.2 Klein 9B\nImport: diffusion + FLUX.2 VAE/AE + Qwen3 8B. A 16 GB phone may run a strongly quantized stack, but memory pressure and thermals will be much higher.";
            case 4:
                return "EXPERIMENTAL QUALITY · FLUX.2 Klein Base 9B\nThe heavier 9B base model plus Qwen3 8B is a tight fit on 16 GB. Use aggressive quantization and VAE tiling.";
            case 5:
                return "TECHNICALLY SUPPORTED, NOT A PHONE TARGET · FLUX.2 Dev\nThe diffusion model is 32B and uses Mistral Small 3.2 24B as text encoder. Import remains possible, but practical local generation on 16 GB is unlikely.";
            case 6:
                return "FLUX.1 KREA / DEV\nImport: diffusion + VAE + CLIP-L + T5XXL. The engine auto-detects Krea/Dev weights. Distilled guidance remains available separately from text CFG.";
            case 7:
                return "FLUX.1 SCHNELL\nImport the normal FLUX.1 split stack. Tuned here for a fast 4-step run.";
            case 8:
                return "GENERIC ENGINE MODE\nCurrent stable-diffusion.cpp also supports Qwen Image, Z-Image, Krea2, Chroma, SD3.x and more. Use the slots required by that architecture and tune sampling manually.";
            default:
                return "AUTO / CUSTOM\nNo assumptions are made. stable-diffusion.cpp detects the architecture from the imported weights; all sampling controls remain manual.";
        }
    }

    private void setSteps(int steps) {
        int clamped = Math.max(4, Math.min(40, steps));
        stepsBar.setProgress(clamped - 4);
        stepsValue.setText(String.valueOf(clamped));
    }

    private void setTextCfg(float value) {
        float clamped = Math.max(0.5f, Math.min(9.5f, value));
        cfgBar.setProgress(Math.round((clamped - 0.5f) * 10f));
        cfgValue.setText(String.format(Locale.US, "%.1f", clamped));
    }

    private void setDistilledGuidance(float value) {
        float clamped = Math.max(0f, Math.min(7f, value));
        distilledBar.setProgress(Math.round(clamped * 10f));
        distilledValue.setText(String.format(Locale.US, "%.1f", clamped));
    }

    private int currentProfile() {
        return Math.max(0, Math.min(MODEL_PROFILES.length - 1, prefs.getInt("model_profile", 1)));
    }

    private boolean isFlux2Profile(int profile) {
        return profile >= 1 && profile <= 5;
    }

    private String storedName(String role) {
        String path = prefPath(role);
        if (path.isEmpty()) return "";
        return prefs.getString(role + "_name", new File(path).getName());
    }

    private boolean looksLikeFlux2Diffusion(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        boolean flux2 = n.contains("flux-2") || n.contains("flux2") || n.contains("flux.2");
        return flux2 && (n.contains("klein") || n.contains("dev"));
    }

    private boolean looksLikeQwen35(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US)
                .replace("_", "").replace("-", "").replace(".", "");
        return n.contains("qwen35");
    }

    private String modelFileProblem(String role, String displayName) {
        int profile = currentProfile();
        if (isFlux2Profile(profile) && "model".equals(role)) {
            return "FLUX.2 is a split model in this app. Put the diffusion GGUF under Diffusion / transformer.";
        }
        if (isFlux2Profile(profile) && "llm".equals(role) && looksLikeQwen35(displayName)) {
            return "Qwen3.5 is not the FLUX.2 Klein text encoder supported by this runtime. Use Qwen3 4B for Klein 4B or Qwen3 8B for Klein 9B.";
        }
        return "";
    }

    private void repairFlux2SlotAssignment(int profile) {
        if (!isFlux2Profile(profile)) return;
        String modelPath = prefPath("model");
        if (modelPath.isEmpty() || !prefPath("diffusion").isEmpty()) return;

        String modelName = prefs.getString("model_name", new File(modelPath).getName());
        if (!looksLikeFlux2Diffusion(modelName)) return;

        long bytes = prefs.getLong("model_bytes", new File(modelPath).length());
        prefs.edit()
                .putString("diffusion_path", modelPath)
                .putString("diffusion_name", modelName)
                .putLong("diffusion_bytes", bytes)
                .remove("model_path").remove("model_name").remove("model_bytes")
                .apply();
        nativeUnload();
    }

    private String missingProfileFiles() {
        int profile = currentProfile();

        // Only Auto/custom and generic modes may treat a Full checkpoint as self-contained.
        if (!isFlux2Profile(profile) && !prefPath("model").isEmpty()) return "";

        java.util.ArrayList<String> missing = new java.util.ArrayList<>();

        if (isFlux2Profile(profile) && !prefPath("model").isEmpty()) {
            missing.add("move FLUX.2 from Full checkpoint to Diffusion / transformer");
        }
        if (prefPath("diffusion").isEmpty()) missing.add("diffusion / transformer");

        if (isFlux2Profile(profile)) {
            if (prefPath("vae").isEmpty()) missing.add("FLUX.2 VAE / AE");
            if (prefPath("llm").isEmpty()) {
                if (profile == 5) missing.add("Mistral text encoder in LLM slot");
                else if (profile == 3 || profile == 4) missing.add("Qwen3 8B in LLM slot");
                else missing.add("Qwen3 4B in LLM slot");
            } else if (looksLikeQwen35(storedName("llm"))) {
                missing.add("replace Qwen3.5 with the required Qwen3 encoder");
            }
        } else if (profile == 6 || profile == 7) {
            if (prefPath("vae").isEmpty()) missing.add("VAE / AE");
            if (prefPath("clip_l").isEmpty()) missing.add("CLIP-L");
            if (prefPath("t5").isEmpty()) missing.add("T5XXL");
        }

        return String.join(", ", missing);
    }

    private void refreshModelSummary() {
        if (modelSummary == null) return;
        int profile = prefs.getInt("model_profile", 1);
        profile = Math.max(0, Math.min(MODEL_PROFILES.length - 1, profile));
        String root = prefPath("model");
        String diffusion = prefPath("diffusion");
        String name;
        if (!root.isEmpty()) name = prefs.getString("model_name", new File(root).getName());
        else if (!diffusion.isEmpty()) name = prefs.getString("diffusion_name", new File(diffusion).getName());
        else name = "No model imported";
        String missing = missingProfileFiles();
        if (missing.isEmpty()) {
            modelSummary.setText("✓  " + MODEL_PROFILES[profile] + "  ·  " + name);
            modelSummary.setTextColor(ACCENT_2);
        } else {
            modelSummary.setText("⚠  " + MODEL_PROFILES[profile] + "  ·  Missing: " + missing);
            modelSummary.setTextColor(GOLD);
        }
    }

    private GradientDrawable backgroundGradient() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG, Color.rgb(13, 10, 21), BG_2});
        return d;
    }

    private GradientDrawable gradientRect(int[] colors, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        l.setPadding(dp(17), dp(17), dp(17), dp(17));
        l.setBackground(gradientRect(new int[]{CARD, Color.rgb(25, 22, 38)}, 22, Color.rgb(51, 46, 69)));
        l.setElevation(dp(2));
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView v = text(s, 22, TEXT, true);
        v.setPadding(0, 0, 0, dp(10));
        return v;
    }

    private EditText input(int lines) {
        EditText e = new EditText(this);
        e.setTextColor(TEXT);
        e.setHintTextColor(Color.rgb(108, 113, 132));
        e.setTextSize(15);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(lines);
        e.setMaxLines(Math.max(lines, 6));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(roundRect(CARD_2, 14, Color.rgb(66, 59, 89)));
        return e;
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
        b.setStateListAnimator(null);
        b.setElevation(dp(2));
        b.setBackground(gradientRect(new int[]{Color.rgb(116, 78, 239), Color.rgb(163, 104, 255)}, 15, Color.rgb(174, 130, 255)));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setBackground(roundRect(CARD_2, 13, Color.rgb(70, 63, 94)));
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
        prefs.edit().putBoolean("generation_in_progress", false).apply();
        nativeCancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}
