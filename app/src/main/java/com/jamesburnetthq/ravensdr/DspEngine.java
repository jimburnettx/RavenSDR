package com.jamesburnetthq.ravensdr;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class DspEngine {

    private static final String TAG = "DspEngine";

    // IQ chunk size: 256 * 1024 bytes = 262144 bytes = 131072 IQ sample pairs
    private static final int CHUNK_BYTES = 256 * 1024;
    // FFT frame size in IQ sample pairs
    static final int FFT_SIZE = 1024;
    // Step between FFT frames (non-overlapping)
    private static final int FRAME_STEP_BYTES = FFT_SIZE * 2; // 2 bytes per IQ pair

    public interface FftListener {
        /** Called on the main thread with a new averaged FFT line ready for display. */
        void onFftLine(float[] dbfsLine);
    }

    public interface StopListener {
        /** Called on the main thread when the DSP loop exits due to a read error. */
        void onDspError(String reason);
    }

    private final RtlSdrDriver driver;
    private final FftListener  listener;
    private       StopListener stopListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Thread dspThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DspEngine(RtlSdrDriver driver, FftListener listener) {
        this.driver = driver;
        this.listener = listener;
    }

    public void setStopListener(StopListener l) { stopListener = l; }

    public void start(long centerFreqHz, int sampleRate, boolean agcEnabled, int gainTenthsDb) {
        if (running.getAndSet(true)) return;

        ConsoleLogger log = ConsoleLogger.get();

        boolean srOk = driver.nativeSetSampleRate(sampleRate);
        log.log("SetSampleRate " + sampleRate + " Hz: " + (srOk ? "OK" : "FAILED"));

        boolean fqOk = driver.nativeSetFrequency(centerFreqHz);
        log.log("SetFrequency " + centerFreqHz + " Hz: " + (fqOk ? "OK" : "FAILED"));

        if (agcEnabled) {
            boolean agcOk = driver.nativeSetAgc(true);
            log.log("AGC on: " + (agcOk ? "OK" : "FAILED"));
        } else {
            driver.nativeSetAgc(false);
            boolean gainOk = driver.nativeSetGain(gainTenthsDb);
            log.log("Manual gain " + (gainTenthsDb / 10.0) + " dB: " + (gainOk ? "OK" : "FAILED"));
        }

        driver.nativeResetDsp();

        boolean rbOk = driver.nativeResetBuffer();
        log.log("ResetBuffer (USB EP): " + (rbOk ? "OK" : "FAILED"));

        dspThread = new Thread(this::dspLoop, "DSP-Engine");
        dspThread.setPriority(Thread.MAX_PRIORITY - 1);
        dspThread.start();
        log.log("DSP thread started");
        Log.i(TAG, "DSP engine started: " + centerFreqHz + " Hz, " + sampleRate + " sps");
    }

    public void stop() {
        running.set(false);
        if (dspThread != null) {
            dspThread.interrupt();
            try { dspThread.join(2000); } catch (InterruptedException ignored) {}
            dspThread = null;
        }
        ConsoleLogger.get().log("DSP stopped");
        Log.i(TAG, "DSP engine stopped");
    }

    public void retune(long centerFreqHz) {
        boolean ok = driver.nativeSetFrequency(centerFreqHz);
        driver.nativeResetDsp();
        ConsoleLogger.get().log("Retune " + centerFreqHz + " Hz: " + (ok ? "OK" : "FAILED"));
        Log.i(TAG, "Retuned to " + centerFreqHz + " Hz");
    }

    public boolean isRunning() {
        return running.get();
    }

    private void dspLoop() {
        byte[] buffer = new byte[CHUNK_BYTES];
        float[] fftResult = new float[FFT_SIZE];
        long frameCount = 0;
        long lastLogTime = System.currentTimeMillis();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int bytesRead = driver.nativeReadSamples(buffer, buffer.length);

            if (bytesRead <= 0) {
                if (bytesRead < 0) {
                    ConsoleLogger.get().log("Read error: " + bytesRead + " — stopping DSP");
                    Log.e(TAG, "nativeReadSamples returned " + bytesRead + ", stopping");
                    running.set(false);
                    final String reason = "USB read error (" + bytesRead + ")";
                    if (stopListener != null)
                        mainHandler.post(() -> stopListener.onDspError(reason));
                    break;
                }
                continue;
            }

            // Process complete FFT frames from the chunk
            int maxOffset = bytesRead - FRAME_STEP_BYTES;
            for (int offset = 0; offset <= maxOffset; offset += FRAME_STEP_BYTES) {
                boolean frameReady = driver.nativeProcessSamples(buffer, offset, fftResult);
                if (frameReady) {
                    frameCount++;
                    final float[] line = fftResult.clone();
                    mainHandler.post(() -> listener.onFftLine(line));
                }
            }

            // Periodic status log ~ every 5 seconds
            long now = System.currentTimeMillis();
            if (now - lastLogTime >= 5000) {
                ConsoleLogger.get().log("Running — " + frameCount + " FFT frames, last chunk " + bytesRead + " bytes");
                lastLogTime = now;
            }
        }
        Log.d(TAG, "DSP loop exited");
    }
}
