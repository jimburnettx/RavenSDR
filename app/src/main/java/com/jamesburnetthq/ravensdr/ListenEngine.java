package com.jamesburnetthq.ravensdr;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class ListenEngine {

    private static final String TAG = "ListenEngine";

    // 960 kHz is reliable on all RTL-SDR hardware; 240 kHz often drops samples.
    // NFM_DECIM = 20 in native code: 960000 / 20 = 48000 Hz audio out.
    public static final int LISTEN_SAMPLE_RATE = 960_000;
    public static final int AUDIO_RATE         = 48_000;

    // 20 ms of IQ at 960 kHz: 960000 * 0.020 * 2 = 38400 bytes
    private static final int CHUNK_BYTES   = 38_400;
    // Audio samples per chunk: 38400 / (2*20) = 960
    private static final int AUDIO_SAMPLES = CHUNK_BYTES / (2 * 20); // 960

    // FFT frame step (1024 IQ pairs = 2048 bytes)
    private static final int FFT_FRAME_BYTES = 1024 * 2;

    // Dwell at each scan frequency before stepping
    private static final int DWELL_CHUNKS = 5;   // 5 × 20 ms = 100 ms

    public interface Listener {
        void onSignalLevel(float rms);
        void onScanFrequency(long hz);
        void onSignalFound(long hz);
        void onSignalLost();
    }

    public  enum DemodType { NFM, AM, WFM }
    private enum Mode      { LISTEN, SCAN, WIDEBAND }
    private enum ScanState { SCANNING, LOCKED }

    private final RtlSdrDriver  driver;
    private final Listener      listener;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread    listenThread;
    private AudioTrack audioTrack;

    // Optional waterfall callback (uses same DspEngine.FftListener interface)
    private DspEngine.FftListener fftListener;

    // Listen params
    private volatile long      listenFreqHz    = 460_400_000L;
    private volatile DemodType listenDemodType = DemodType.NFM;
    private volatile int       squelchLevel    = 30;
    private volatile Mode      mode            = Mode.LISTEN;

    // Scan params
    private volatile long scanStartHz;
    private volatile long scanEndHz;
    private volatile long scanStepHz;
    private static final long HOLD_MS = 5_000;

    // Wideband params
    private volatile long[]     widebandChannels;
    private volatile long[]     widebandOffsets;
    private volatile long       widebandCenter;
    private volatile DemodType  widebandDemodType;

    public ListenEngine(RtlSdrDriver driver, Listener listener) {
        this.driver   = driver;
        this.listener = listener;
    }

    public void setSquelch(int level)                   { squelchLevel = level; }
    public void setFftListener(DspEngine.FftListener l) { fftListener  = l; }
    public boolean isRunning()                          { return running.get(); }

    public void startListen(long freqHz, DemodType demodType) {
        listenFreqHz    = freqHz;
        listenDemodType = demodType;
        mode            = Mode.LISTEN;
        launch();
    }

    public void startScan(long startHz, long endHz, long stepHz, DemodType demodType) {
        scanStartHz     = startHz;
        scanEndHz       = endHz;
        scanStepHz      = stepHz;
        listenDemodType = demodType;
        mode            = Mode.SCAN;
        launch();
    }

    public void startWideband(long[] channels, long centerHz, DemodType demodType) {
        widebandChannels  = channels;
        widebandCenter    = centerHz;
        widebandDemodType = demodType;
        long[] offsets    = new long[channels.length];
        for (int i = 0; i < channels.length; i++) offsets[i] = channels[i] - centerHz;
        widebandOffsets   = offsets;
        mode              = Mode.WIDEBAND;
        launch();
    }

    public void stop() {
        running.set(false);
        if (listenThread != null) {
            listenThread.interrupt();
            try { listenThread.join(2_000); } catch (InterruptedException ignored) {}
            listenThread = null;
        }
        releaseAudio();
        driver.nativeResetDemod();
        Log.i(TAG, "stopped");
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void launch() {
        // Stop any previous run first
        if (running.getAndSet(true)) {
            running.set(false);
            if (listenThread != null) {
                listenThread.interrupt();
                try { listenThread.join(2_000); } catch (InterruptedException ignored) {}
                listenThread = null;
            }
            releaseAudio();
        }
        running.set(true);

        long initialHz;
        Runnable loop;
        switch (mode) {
            case SCAN:     initialHz = scanStartHz;   loop = this::scanLoop;      break;
            case WIDEBAND: initialHz = widebandCenter; loop = this::widebandLoop; break;
            default:       initialHz = listenFreqHz;  loop = this::listenLoop;    break;
        }
        setupHardware(initialHz);
        createAudioTrack();

        listenThread  = new Thread(loop, "ListenEngine");
        listenThread.setPriority(Thread.MAX_PRIORITY - 2);
        listenThread.start();
        Log.i(TAG, "started mode=" + mode + " freq=" + initialHz);
    }

    private void setupHardware(long freqHz) {
        driver.nativeSetSampleRate(LISTEN_SAMPLE_RATE);
        driver.nativeSetFrequency(freqHz);
        driver.nativeResetDsp();
        driver.nativeResetBuffer();
        driver.nativeResetDemod();
    }

    private void createAudioTrack() {
        releaseAudio();
        int minBuf = AudioTrack.getMinBufferSize(
                AUDIO_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, AUDIO_SAMPLES * 2 * 15); // ~300 ms buffer absorbs USB jitter
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            audioTrack.play();
            Log.i(TAG, "AudioTrack playing, bufSize=" + bufSize);
        } else {
            Log.e(TAG, "AudioTrack init failed, state=" + audioTrack.getState());
        }
    }

    private void releaseAudio() {
        AudioTrack at = audioTrack;
        audioTrack = null;
        if (at != null) {
            try { at.pause(); at.flush(); at.release(); }
            catch (Exception ignored) {}
        }
    }

    // ── Listen loop ───────────────────────────────────────────────────────────

    private void listenLoop() {
        byte[]  buf     = new byte[CHUNK_BYTES];
        short[] silence = new short[AUDIO_SAMPLES];
        DemodType demod = listenDemodType;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int n = driver.nativeReadSamples(buf, buf.length);
            if (n <= 0) { writeAudio(silence); continue; }

            processWaterfallFft(buf, n);

            short[] pcm;
            if (demod == DemodType.AM) {
                pcm = driver.nativeDemodAM(buf, n, squelchLevel);
            } else if (demod == DemodType.WFM) {
                pcm = driver.nativeDemodWFM(buf, n, 0);
            } else {
                pcm = driver.nativeDemodNFM(buf, n, squelchLevel);
            }
            writeAudio(pcm != null ? pcm : silence);

            final float rms = driver.nativeGetSignalRms();
            mainHandler.post(() -> listener.onSignalLevel(rms));
        }
    }

    // ── Scan loop ─────────────────────────────────────────────────────────────

    private void scanLoop() {
        byte[]    buf        = new byte[CHUNK_BYTES];
        short[]   silence    = new short[AUDIO_SAMPLES];
        long      currentHz  = scanStartHz;
        ScanState state      = ScanState.SCANNING;
        int       dwellCount = 0;
        long      holdUntil  = 0;
        boolean   needTune   = false; // already set up in setupHardware
        DemodType scanDemod  = listenDemodType;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (needTune) {
                driver.nativeSetFrequency(currentHz);
                driver.nativeResetDemod();
                final long hz = currentHz;
                mainHandler.post(() -> listener.onScanFrequency(hz));
                needTune   = false;
                dwellCount = 0;
                try { Thread.sleep(80); } catch (InterruptedException e) { break; }
            }

            int n = driver.nativeReadSamples(buf, buf.length);
            if (n <= 0) { writeAudio(silence); continue; }

            float rms        = driver.nativeMeasureSignalLevel(buf, n);
            int   sq         = squelchLevel;
            float noiseFloor = driver.nativeGetNoiseFloor();
            float thresh     = (sq <= 0 || noiseFloor <= 0f) ? 0f
                               : noiseFloor * (float) Math.pow(10.0, sq / 20.0);
            boolean sig  = (thresh <= 0f) || (rms > thresh);

            final float fRms = rms;
            mainHandler.post(() -> listener.onSignalLevel(fRms));

            if (state == ScanState.SCANNING) {
                if (sig) {
                    state     = ScanState.LOCKED;
                    holdUntil = System.currentTimeMillis() + HOLD_MS;
                    final long hz = currentHz;
                    mainHandler.post(() -> listener.onSignalFound(hz));
                    driver.nativeResetDemod();
                    processWaterfallFft(buf, n);
                    short[] pcm = demodChunk(scanDemod, buf, n, 0);
                    writeAudio(pcm != null ? pcm : silence);
                } else {
                    writeAudio(silence);
                    dwellCount++;
                    if (dwellCount >= DWELL_CHUNKS) {
                        currentHz = advance(currentHz);
                        needTune  = true;
                    }
                }
            } else { // LOCKED
                if (sig) holdUntil = System.currentTimeMillis() + HOLD_MS;
                processWaterfallFft(buf, n);
                short[] pcm = demodChunk(scanDemod, buf, n, 0);
                writeAudio(pcm != null ? pcm : silence);

                if (System.currentTimeMillis() >= holdUntil) {
                    state     = ScanState.SCANNING;
                    currentHz = advance(currentHz);
                    needTune  = true;
                    mainHandler.post(listener::onSignalLost);
                }
            }
        }
    }

    // ── Wideband monitor loop ─────────────────────────────────────────────────
    // Tunes to the band center; uses nativeGetChannelPowers to find the active
    // channel without retuning (HUNTING), then locks to it and demodulates until
    // the signal drops for HOLD_MS (LOCKED → back to HUNTING).

    private void widebandLoop() {
        byte[]    buf       = new byte[CHUNK_BYTES];
        short[]   silence   = new short[AUDIO_SAMPLES];
        long[]    channels  = widebandChannels;
        long[]    offsets   = widebandOffsets;
        int       nCh       = channels.length;
        boolean   isFm      = (widebandDemodType == DemodType.NFM);
        ScanState state     = ScanState.SCANNING;
        int       activeIdx = 0;
        long      holdUntil = 0;

        final long centerHz = widebandCenter;
        mainHandler.post(() -> listener.onScanFrequency(centerHz));

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int n = driver.nativeReadSamples(buf, buf.length);
            if (n <= 0) { writeAudio(silence); continue; }

            if (state == ScanState.SCANNING) {
                float[] powers = driver.nativeGetChannelPowers(buf, n, offsets, nCh);
                writeAudio(silence);
                processWaterfallFft(buf, n); // keep waterfall live while hunting
                if (powers == null) continue;

                // Noise floor = minimum channel power; look for 3 dB above it.
                float floor = Float.MAX_VALUE;
                for (float p : powers) if (p < floor) floor = p;
                float thresh = floor * 1.41f;

                int   bestIdx = -1;
                float bestPow = thresh;
                for (int i = 0; i < nCh; i++) {
                    if (powers[i] > bestPow) { bestPow = powers[i]; bestIdx = i; }
                }

                if (bestIdx >= 0) {
                    activeIdx = bestIdx;
                    state     = ScanState.LOCKED;
                    holdUntil = System.currentTimeMillis() + HOLD_MS;
                    final long hz = channels[activeIdx];
                    mainHandler.post(() -> listener.onSignalFound(hz));
                    driver.nativeSetFrequency(hz);
                    driver.nativeResetDemod();
                    try { Thread.sleep(80); } catch (InterruptedException e) { break; }
                }
            } else { // LOCKED
                short[] pcm = isFm
                        ? driver.nativeDemodNFM(buf, n, 0)
                        : driver.nativeDemodAM(buf, n, 0);
                writeAudio(pcm != null ? pcm : silence);
                processWaterfallFft(buf, n);

                // Extend hold while signal is present.
                // FM disc RMS at 48 kHz output: voice ~0.2–0.5, noise ~0.8+
                boolean sigPresent = isFm
                        ? driver.nativeGetFmRms() < 0.6f
                        : driver.nativeGetSignalRms() > 0.15f;
                if (sigPresent) holdUntil = System.currentTimeMillis() + HOLD_MS;

                final float rms = driver.nativeGetSignalRms();
                mainHandler.post(() -> listener.onSignalLevel(rms));

                if (System.currentTimeMillis() >= holdUntil) {
                    state = ScanState.SCANNING;
                    mainHandler.post(listener::onSignalLost);
                    driver.nativeSetFrequency(centerHz);
                    driver.nativeResetDemod();
                    try { Thread.sleep(80); } catch (InterruptedException e) { break; }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private short[] demodChunk(DemodType demod, byte[] buf, int n, int sq) {
        if (demod == DemodType.AM)  return driver.nativeDemodAM(buf, n, sq);
        if (demod == DemodType.WFM) return driver.nativeDemodWFM(buf, n, 0);
        return driver.nativeDemodNFM(buf, n, sq);
    }

    private long advance(long hz) {
        hz += scanStepHz;
        return (hz > scanEndHz) ? scanStartHz : hz;
    }

    private void writeAudio(short[] pcm) {
        AudioTrack at = audioTrack;
        if (at == null || at.getState() != AudioTrack.STATE_INITIALIZED) return;
        try { at.write(pcm, 0, pcm.length); }
        catch (Exception ignored) {}
    }

    private final float[] fftScratch = new float[1024];

    // Process at most AVG_FRAMES frames (= one averaged result) per chunk to
    // keep CPU overhead low. At 20 ms/chunk this gives 50 Hz waterfall update.
    private static final int MAX_FFT_FRAMES_PER_CHUNK = 4;

    private void processWaterfallFft(byte[] buf, int n) {
        DspEngine.FftListener cb = fftListener;
        if (cb == null) return;
        int frames = 0;
        for (int off = 0; off + FFT_FRAME_BYTES <= n && frames < MAX_FFT_FRAMES_PER_CHUNK;
             off += FFT_FRAME_BYTES, frames++) {
            if (driver.nativeProcessSamples(buf, off, fftScratch)) {
                float[] line = fftScratch.clone();
                mainHandler.post(() -> cb.onFftLine(line));
            }
        }
    }
}
