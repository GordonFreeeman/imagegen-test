package com.localflux.studio;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import com.localflux.adreno.AdrenoNativeBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the FLUX.2 Adreno backend in a dedicated app process. Qualcomm driver
 * aborts, native SIGABRTs and GPU deadlocks therefore cannot take the Studio UI
 * process down with them.
 */
public class GenerationWorkerService extends Service {
    public static final int MSG_GENERATE = 1;
    public static final int MSG_CANCEL = 2;

    public static final int MSG_READY = 100;
    public static final int MSG_PROGRESS = 101;
    public static final int MSG_LOG = 102;
    public static final int MSG_RESULT = 103;
    public static final int MSG_ERROR = 104;

    public static final String K_DIFFUSION = "diffusion";
    public static final String K_VAE = "vae";
    public static final String K_LLM = "llm";
    public static final String K_PROMPT = "prompt";
    public static final String K_NEGATIVE = "negative";
    public static final String K_WIDTH = "width";
    public static final String K_HEIGHT = "height";
    public static final String K_STEPS = "steps";
    public static final String K_TEXT_CFG = "text_cfg";
    public static final String K_DISTILLED = "distilled";
    public static final String K_SEED = "seed";
    public static final String K_VAE_TILING = "vae_tiling";
    public static final String K_LORA_PATHS = "lora_paths";
    public static final String K_LORA_STRENGTHS = "lora_strengths";
    public static final String K_RUNTIME_MODE = "runtime_mode";
    public static final String K_TEXT_ENCODER_MODE = "text_encoder_mode";
    public static final String K_THREADS = "threads";
    public static final String K_RESULT_PATH = "result_path";
    public static final String K_ERROR = "error";
    public static final String K_LOG = "log";
    public static final String K_PHASE = "phase";
    public static final String K_STEP = "step";
    public static final String K_TOTAL = "total";
    public static final String K_ELAPSED = "elapsed";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Messenger client;
    private volatile boolean running;

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                int[] p = AdrenoNativeBridge.progressSnapshot();
                if (p != null && p.length >= 4) {
                    Bundle b = new Bundle();
                    b.putInt(K_PHASE, p[0]);
                    b.putInt(K_STEP, p[1]);
                    b.putInt(K_TOTAL, p[2]);
                    b.putInt(K_ELAPSED, p[3]);
                    send(MSG_PROGRESS, b);
                }
                String log = AdrenoNativeBridge.drainLogs();
                if (log != null && !log.isEmpty()) {
                    // Stay comfortably below Binder's transaction limit.
                    if (log.length() > 48_000) log = log.substring(log.length() - 48_000);
                    Bundle b = new Bundle();
                    b.putString(K_LOG, log);
                    send(MSG_LOG, b);
                }
            } catch (Throwable ignored) {
                // A native worker failure is detected by ServiceConnection in the UI process.
            }
            if (running) mainHandler.postDelayed(this, 500);
        }
    };

    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.replyTo != null) client = msg.replyTo;
        if (msg.what == MSG_GENERATE) {
            if (!running) startGeneration(new Bundle(msg.getData()));
            return true;
        }
        if (msg.what == MSG_CANCEL) {
            // A hard process restart is the only reliable cancellation mechanism
            // when a vendor Vulkan driver is blocked inside ioctl/queue submit.
            running = false;
            mainHandler.removeCallbacks(monitor);
            Process.killProcess(Process.myPid());
            return true;
        }
        return false;
    }));

    @Override public void onCreate() {
        super.onCreate();
    }

    @Override public IBinder onBind(Intent intent) {
        Bundle ready = new Bundle();
        ready.putInt("pid", Process.myPid());
        try {
            ready.putString("runtime", AdrenoNativeBridge.systemInfo());
        } catch (Throwable t) {
            ready.putString("runtime", "Adreno runtime initialization failed: " + safeMessage(t));
        }
        // Client Messenger is not known until its first command; MainActivity
        // also treats onServiceConnected as ready.
        return incoming.getBinder();
    }

    private void startGeneration(Bundle args) {
        running = true;
        mainHandler.removeCallbacks(monitor);
        mainHandler.post(monitor);

        executor.execute(() -> {
            try {
                int width = args.getInt(K_WIDTH);
                int height = args.getInt(K_HEIGHT);
                int[] pixels = AdrenoNativeBridge.generate(
                        args.getString(K_DIFFUSION, ""),
                        args.getString(K_VAE, ""),
                        args.getString(K_LLM, ""),
                        args.getString(K_PROMPT, ""),
                        args.getString(K_NEGATIVE, ""),
                        width,
                        height,
                        args.getInt(K_STEPS, 4),
                        args.getFloat(K_TEXT_CFG, 1.0f),
                        args.getFloat(K_DISTILLED, 3.5f),
                        args.getLong(K_SEED, -1L),
                        args.getBoolean(K_VAE_TILING, true),
                        args.getStringArray(K_LORA_PATHS),
                        args.getFloatArray(K_LORA_STRENGTHS),
                        args.getInt(K_RUNTIME_MODE, 0),
                        args.getInt(K_TEXT_ENCODER_MODE, 16),
                        args.getInt(K_THREADS, 8));

                if (pixels == null || pixels.length != width * height) {
                    throw new IllegalStateException("Adreno runtime returned no image");
                }

                File dir = new File(getCacheDir(), "diffusion-worker");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("Could not create worker result directory");
                }
                File out = new File(dir, "result-" + System.currentTimeMillis() + ".png");
                Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IllegalStateException("Could not encode worker PNG");
                    }
                } finally {
                    bitmap.recycle();
                }

                flushLogs();
                Bundle result = new Bundle();
                result.putString(K_RESULT_PATH, out.getAbsolutePath());
                result.putInt(K_WIDTH, width);
                result.putInt(K_HEIGHT, height);
                send(MSG_RESULT, result);
            } catch (Throwable t) {
                flushLogs();
                Bundle error = new Bundle();
                error.putString(K_ERROR, safeMessage(t));
                send(MSG_ERROR, error);
            } finally {
                running = false;
                mainHandler.removeCallbacks(monitor);
                try { AdrenoNativeBridge.unload(); } catch (Throwable ignored) {}
                stopSelf();
            }
        });
    }

    private void flushLogs() {
        try {
            String log = AdrenoNativeBridge.drainLogs();
            if (log != null && !log.isEmpty()) {
                if (log.length() > 48_000) log = log.substring(log.length() - 48_000);
                Bundle b = new Bundle();
                b.putString(K_LOG, log);
                send(MSG_LOG, b);
            }
        } catch (Throwable ignored) {}
    }

    private void send(int what, Bundle data) {
        Messenger target = client;
        if (target == null) return;
        Message m = Message.obtain(null, what);
        m.setData(data);
        try {
            target.send(m);
        } catch (RemoteException ignored) {}
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown error";
        String message = t.getMessage();
        return message == null || message.trim().isEmpty()
                ? t.getClass().getSimpleName()
                : message;
    }

    @Override public void onDestroy() {
        running = false;
        mainHandler.removeCallbacks(monitor);
        executor.shutdownNow();
        super.onDestroy();
    }
}
