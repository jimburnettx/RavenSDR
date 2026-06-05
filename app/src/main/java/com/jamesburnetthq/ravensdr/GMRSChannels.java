package com.jamesburnetthq.ravensdr;

import java.util.Arrays;

public final class GMRSChannels {

    // Channels 1–7: simplex/output (462 MHz range)
    public static final double[] CH1_7 = {
        462.5625e6, 462.5875e6, 462.6125e6, 462.6375e6,
        462.6625e6, 462.6875e6, 462.7125e6
    };

    // Channels 8–14: low-power interstitial (467 MHz range)
    public static final double[] CH8_14 = {
        467.5625e6, 467.5875e6, 467.6125e6, 467.6375e6,
        467.6625e6, 467.6875e6, 467.7125e6
    };

    // Channels 15–22: high power (462 MHz range)
    public static final double[] CH15_22 = {
        462.5500e6, 462.5750e6, 462.6000e6, 462.6250e6,
        462.6500e6, 462.6750e6, 462.7000e6, 462.7250e6
    };

    // Repeater inputs for channels 15–22 (467 MHz range)
    public static final double[] CH15_22_REPEATER_IN = {
        467.5500e6, 467.5750e6, 467.6000e6, 467.6250e6,
        467.6500e6, 467.6750e6, 467.7000e6, 467.7250e6
    };

    // US CB radio channels 1–40
    public static final double[] CB_CHANNELS = {
        26.965e6, 26.975e6, 26.985e6, 27.005e6, 27.015e6,   //  1– 5
        27.025e6, 27.035e6, 27.055e6, 27.065e6, 27.075e6,   //  6–10
        27.085e6, 27.105e6, 27.115e6, 27.125e6, 27.135e6,   // 11–15
        27.155e6, 27.165e6, 27.175e6, 27.185e6, 27.205e6,   // 16–20
        27.215e6, 27.225e6, 27.255e6, 27.235e6, 27.245e6,   // 21–25 (24 & 25 frequencies are transposed per FCC)
        27.265e6, 27.275e6, 27.285e6, 27.295e6, 27.305e6,   // 26–30
        27.315e6, 27.325e6, 27.335e6, 27.345e6, 27.355e6,   // 31–35
        27.365e6, 27.375e6, 27.385e6, 27.395e6, 27.405e6    // 36–40
    };

    // APRS 2m
    public static final double[] APRS_CHANNELS = { 145.000e6 };

    // Center frequencies for all monitoring bands
    public static final long CENTER_FREQ_LOW  = 462_625_000L;  // 462.625 MHz  GMRS
    public static final long CENTER_FREQ_HIGH = 467_600_000L;  // 467.600 MHz  GMRS repeater inputs
    public static final long CENTER_FREQ_MESH = 915_000_000L;  // 915.000 MHz  ISM / Meshtastic
    public static final long CENTER_FREQ_FOBS = 434_000_000L;  // 434.000 MHz  FOBS / LoRa 433
    public static final long CENTER_FREQ_MIC  = 650_000_000L;  // 650.000 MHz  wireless mics
    public static final long CENTER_FREQ_CB   =  27_185_000L;  //  27.185 MHz  CB center (CH19, trucker channel)
    public static final long CENTER_FREQ_APRS = 145_000_000L;  // 145.000 MHz  APRS 2m
    // FRS uses 467.6375 MHz center (midpoint of CH 8–14) — distinct key from GMRS-R
    public static final long CENTER_FREQ_FRS  = 467_637_500L;  // 467.6375 MHz FRS CH 8–14
    public static final int  SAMPLE_RATE      = 2_048_000;     // 2.048 MSPS

    public static double[] getVisibleChannels(long centerHz) {
        if (centerHz == CENTER_FREQ_LOW) {
            double[] r = new double[CH1_7.length + CH15_22.length];
            System.arraycopy(CH1_7,   0, r, 0,           CH1_7.length);
            System.arraycopy(CH15_22, 0, r, CH1_7.length, CH15_22.length);
            return r;
        } else if (centerHz == CENTER_FREQ_HIGH) {
            double[] r = new double[CH8_14.length + CH15_22_REPEATER_IN.length];
            System.arraycopy(CH8_14,             0, r, 0,             CH8_14.length);
            System.arraycopy(CH15_22_REPEATER_IN, 0, r, CH8_14.length, CH15_22_REPEATER_IN.length);
            return r;
        } else if (centerHz == CENTER_FREQ_CB) {
            return CB_CHANNELS.clone();
        } else if (centerHz == CENTER_FREQ_APRS) {
            return APRS_CHANNELS.clone();
        } else if (centerHz == CENTER_FREQ_FRS) {
            return CH8_14.clone();
        } else {
            return new double[0];
        }
    }

    public static String getBandLabel(long centerHz) {
        if (centerHz == CENTER_FREQ_LOW)  return "GMRS · CH 1–7 / 15–22";
        if (centerHz == CENTER_FREQ_HIGH) return "GMRSR · Repeater inputs";
        if (centerHz == CENTER_FREQ_MESH) return "MESH · 915 MHz ISM";
        if (centerHz == CENTER_FREQ_FOBS) return "FOBS · 434 MHz";
        if (centerHz == CENTER_FREQ_MIC)  return "MIC · 650 MHz";
        if (centerHz == CENTER_FREQ_CB)   return "CB · Channels 1–40";
        if (centerHz == CENTER_FREQ_APRS) return "APRS · 145.000 MHz";
        if (centerHz == CENTER_FREQ_FRS)  return "FRS · CH 8–14 (467 MHz)";
        return String.format("%.3f MHz", centerHz / 1e6);
    }

    /** Returns a short channel label (just the number) for display on the waterfall. */
    public static String identifyChannel(double freqHz) {
        for (int i = 0; i < CH1_7.length; i++)
            if (Math.abs(freqHz - CH1_7[i]) < 5000) return String.valueOf(i + 1);
        for (int i = 0; i < CH8_14.length; i++)
            if (Math.abs(freqHz - CH8_14[i]) < 5000) return String.valueOf(i + 8);
        for (int i = 0; i < CH15_22.length; i++)
            if (Math.abs(freqHz - CH15_22[i]) < 5000) return String.valueOf(i + 15);
        for (int i = 0; i < CH15_22_REPEATER_IN.length; i++)
            if (Math.abs(freqHz - CH15_22_REPEATER_IN[i]) < 5000) return "R" + (i + 15);
        for (int i = 0; i < CB_CHANNELS.length; i++)
            if (Math.abs(freqHz - CB_CHANNELS[i]) < 2500) return String.valueOf(i + 1);
        if (Math.abs(freqHz - APRS_CHANNELS[0]) < 5000) return "APRS";
        return null;
    }

    private GMRSChannels() {}
}
