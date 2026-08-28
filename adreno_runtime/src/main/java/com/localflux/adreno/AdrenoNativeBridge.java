package com.localflux.adreno;

public final class AdrenoNativeBridge {
    static {
        System.loadLibrary("localflux_adreno");
    }

    private AdrenoNativeBridge() {}

    public static native String systemInfo();

    public static native int[] generate(
            String diffusion,
            String vae,
            String llm,
            String prompt,
            String negative,
            int width,
            int height,
            int steps,
            float textCfg,
            float distilledGuidance,
            long seed,
            boolean vaeTiling,
            String[] loraPaths,
            float[] loraStrengths,
            int qwenMode,
            int cpuThreads);

    public static native String drainLogs();
    public static native int[] progressSnapshot();
    public static native void unload();
}
