package com.jamesburnetthq.ravensdr;

import android.util.Log;

public class RtlSdrDriver {

    private static final String TAG = "RtlSdrDriver";
    private static boolean libraryLoaded = false;

    static {
        try {
            System.loadLibrary("rtlsdr_android");
            libraryLoaded = true;
            Log.i(TAG, "librtlsdr_android loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    // ---- Native methods ----

    /**
     * Open RTL-SDR device using Android-granted USB file descriptor.
     * The fd comes from UsbDeviceConnection.getFileDescriptor().
     * @return null on success, or a human-readable error string on failure.
     */
    public native String nativeOpen(int fileDescriptor);

    /** Close the device and release all resources. */
    public native void nativeClose();

    /** Set center frequency in Hz. Returns true on success. */
    public native boolean nativeSetFrequency(long freqHz);

    /** Set sample rate in Hz. Returns true on success. */
    public native boolean nativeSetSampleRate(int rateHz);

    /** Set tuner gain in tenths of dB (e.g. 200 = 20.0 dB). Returns true on success. */
    public native boolean nativeSetGain(int gainTenthsDb);

    /** Enable or disable automatic gain control. Returns true on success. */
    public native boolean nativeSetAgc(boolean enable);

    /**
     * Synchronous read of raw IQ samples into buffer.
     * @return number of bytes actually read, or -1 on error
     */
    public native int nativeReadSamples(byte[] buffer, int length);

    /**
     * Process one 1024-sample FFT frame from raw IQ bytes.
     * Internally accumulates 4 frames and averages them.
     * @param iqData    raw unsigned 8-bit IQ bytes (I0, Q0, I1, Q1, ...)
     * @param offset    byte offset into iqData to start processing
     * @param outDbfs   output float[1024] of dBFS power values (FFT-shifted, DC center)
     * @return true when an averaged result is ready in outDbfs; false when still accumulating
     */
    public native boolean nativeProcessSamples(byte[] iqData, int offset, float[] outDbfs);

    /**
     * Return JSON string with tuner information.
     * Example: {"tuner":"R820T","gains":[0,9,14,27,...]}
     */
    public native String nativeGetDeviceInfo();

    /** Reset the internal FFT averager state. */
    public native void nativeResetDsp();

    /**
     * Demodulate NFM from raw IQ bytes captured at 240 kHz.
     * squelchLevel 0 (open) – 100 (tight).
     * Returns int16 PCM at 48 kHz, length = nBytes / 10.
     */
    public native short[] nativeDemodNFM(byte[] iqBytes, int nBytes, int squelchLevel);

    /** Measure IQ RMS without demodulating. Updates internal signal-level register. */
    public native float nativeMeasureSignalLevel(byte[] iqBytes, int nBytes);

    /** Return the IQ RMS measured by the last nativeDemodNFM or nativeMeasureSignalLevel call. */
    public native float nativeGetSignalRms();

    /** Return the adaptive noise-floor estimate (same scale as nativeGetSignalRms). */
    public native float nativeGetNoiseFloor();

    /** Reset NFM demodulator state (call on frequency change). */
    public native void nativeResetDemod();

    /**
     * Reset the RTL2832U USB bulk-in endpoint.
     * MUST be called after nativeSetSampleRate/nativeSetFrequency and before
     * the first nativeReadSamples() — otherwise bulk transfers return no data.
     */
    public native boolean nativeResetBuffer();

    /** Return the FM discriminator RMS measured by the last nativeDemodNFM call. */
    public native float nativeGetFmRms();

    /**
     * Measure per-channel IQ power at specified Hz offsets from the tuned center.
     * Used by wideband monitor to find the active channel without retuning.
     * @param iqBytes   raw IQ from nativeReadSamples
     * @param nBytes    valid byte count
     * @param offsets   Hz offset of each channel from tuned center (long[])
     * @param nChannels number of channels
     * @return float[] RMS amplitude per channel (higher = stronger signal)
     */
    public native float[] nativeGetChannelPowers(byte[] iqBytes, int nBytes,
                                                  long[] offsets, int nChannels);

    /**
     * Demodulate AM from 960 kHz IQ bytes. squelchLevel 0 = open.
     * Returns int16 PCM at 48 kHz, length = nBytes / 20.
     */
    public native short[] nativeDemodAM(byte[] iqBytes, int nBytes, int squelchLevel);

    /**
     * Demodulate wideband FM (broadcast FM) from 960 kHz IQ bytes.
     * Discriminator runs at 240 kHz — no atan2 phase wrapping for ±75 kHz deviation.
     * De-emphasis (τ=75 µs, US standard) applied. Passes full mono audio 0–15 kHz.
     * Returns int16 PCM at 48 kHz, length = nBytes / 20.
     */
    public native short[] nativeDemodWFM(byte[] iqBytes, int nBytes, int squelchLevel);
}
