package com.jamesburnetthq.ravensdr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Arrays;
import java.util.HashMap;

public class WaterfallView extends View {

    private static final int FFT_SIZE = DspEngine.FFT_SIZE;

    private static final float DB_NOISE = -90f;
    private static final float DB_MID1  = -60f;
    private static final float DB_MID2  = -45f;
    private static final float DB_MID3  = -30f;
    private static final float DB_MID4  = -15f;
    private static final float DB_MAX   =  -5f;

    // Slow noise-floor EMA: ~67 frames ≈ 2 seconds at typical rates
    private static final float EMA_ALPHA = 0.015f;

    public interface AlertListener {
        void onAlert();
        void onAlertCleared();
    }

    // Called each time the user's drag crosses a step threshold.
    // direction: +1 = dragged left (higher frequency), -1 = dragged right (lower frequency).
    public interface SwipeListener {
        void onSwipeStep(int direction);
    }

    private static final class BandState {
        Bitmap  bitmap;
        float   zoomLevel = 1f;
        int     panBins   = 0;
        float[] emaFloor;
        boolean alertActive;

        BandState(Bitmap b, float z, int p, float[] e, boolean a) {
            bitmap = b; zoomLevel = z; panBins = p; emaFloor = e; alertActive = a;
        }
    }

    private final HashMap<Long, BandState> bandStates   = new HashMap<>();
    private long                           currentBandKey = Long.MIN_VALUE;

    private Bitmap     waterfall;
    private final Paint bitmapPaint    = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint channelPaint   = new Paint();
    private final Paint chanLabelPaint = new Paint();
    private final Paint axisPaint      = new Paint();
    private final Paint hdrBgPaint     = new Paint();
    private final Paint hdrEdgePaint   = new Paint();
    private final Paint hdrCtrPaint    = new Paint();
    private final Paint hdrRightPaint  = new Paint();
    private float hdrHeight  = 40f;
    private float baseLabelPx = 13f;

    private double[] channelFrequencies = new double[0];
    private long     centerFreqHz       = GMRSChannels.CENTER_FREQ_LOW;
    private int      sampleRate         = GMRSChannels.SAMPLE_RATE;

    private volatile float[] latestLine;
    private boolean pendingInvalidate = false;

    private float zoomLevel = 1.0f;
    private int   panBins   = 0;

    // Alert detection state
    private float[]       emaFloor;
    private boolean       alertActive      = false;
    private float         alertThresholdDb = 12f;
    private AlertListener alertListener;
    private long          alertWarmupUntilMs = 0;  // suppress alerts while EMA settles
    private SwipeListener swipeListener;
    private float         swipeAccumX = 0f;
    // Pixels the user must drag before one frequency step fires.
    // ~50 px ≈ 8 mm on a typical 160 dpi phone — feels like a tuning dial.
    private static final float SWIPE_PX_PER_STEP = 50f;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;

    public WaterfallView(Context ctx)                              { super(ctx);           init(); }
    public WaterfallView(Context ctx, AttributeSet a)             { super(ctx, a);        init(); }
    public WaterfallView(Context ctx, AttributeSet a, int style)  { super(ctx, a, style); init(); }

    private void init() {
        channelPaint.setColor(Color.argb(200, 255, 255, 0));
        channelPaint.setStrokeWidth(1.5f);
        channelPaint.setStyle(Paint.Style.STROKE);

        chanLabelPaint.setAntiAlias(true);
        chanLabelPaint.setColor(Color.argb(230, 255, 255, 80));
        chanLabelPaint.setTextAlign(Paint.Align.CENTER);

        axisPaint.setColor(Color.argb(180, 160, 160, 160));
        axisPaint.setTextSize(13f);
        axisPaint.setAntiAlias(true);
        axisPaint.setStrokeWidth(1f);

        float sp     = getResources().getDisplayMetrics().scaledDensity;
        float edgeSp = 16f * sp;
        float ctrSp  = 20f * sp;
        hdrHeight    = ctrSp * 1.7f;
        baseLabelPx  = 13f * sp;

        hdrBgPaint.setColor(Color.argb(185, 0, 0, 0));
        hdrBgPaint.setStyle(Paint.Style.FILL);

        hdrEdgePaint.setColor(Color.argb(230, 100, 220, 100));
        hdrEdgePaint.setTextSize(edgeSp);
        hdrEdgePaint.setAntiAlias(true);
        hdrEdgePaint.setTextAlign(Paint.Align.LEFT);

        hdrRightPaint.setColor(Color.argb(230, 100, 220, 100));
        hdrRightPaint.setTextSize(edgeSp);
        hdrRightPaint.setAntiAlias(true);
        hdrRightPaint.setTextAlign(Paint.Align.RIGHT);

        hdrCtrPaint.setColor(Color.argb(255, 0, 255, 130));
        hdrCtrPaint.setTextSize(ctrSp);
        hdrCtrPaint.setAntiAlias(true);
        hdrCtrPaint.setFakeBoldText(true);
        hdrCtrPaint.setTextAlign(Paint.Align.CENTER);

        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float focusFrac = getWidth() > 0 ? d.getFocusX() / getWidth() : 0.5f;
                        int   prevVis   = visibleBins();
                        int   prevStart = viewStartBin();
                        float focusBin  = prevStart + focusFrac * prevVis;

                        zoomLevel = Math.max(1f, Math.min(16f, zoomLevel * d.getScaleFactor()));

                        int newVis = visibleBins();
                        panBins = Math.round(focusBin - focusFrac * newVis)
                                - (FFT_SIZE / 2 - newVis / 2);
                        clampPan();
                        invalidate();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        swipeAccumX = 0f;  // reset accumulator on each new touch
                        return false;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distX, float distY) {
                        if (scaleDetector.isInProgress()) return false;
                        if (swipeListener != null) {
                            // Accumulate horizontal drag; fire a step each time
                            // the threshold is crossed.  distX > 0 = dragged left
                            // = viewing higher frequencies = step +1.
                            swipeAccumX += distX;
                            while (swipeAccumX >= SWIPE_PX_PER_STEP) {
                                swipeListener.onSwipeStep(+1);
                                swipeAccumX -= SWIPE_PX_PER_STEP;
                            }
                            while (swipeAccumX <= -SWIPE_PX_PER_STEP) {
                                swipeListener.onSwipeStep(-1);
                                swipeAccumX += SWIPE_PX_PER_STEP;
                            }
                            return true;
                        }
                        // No swipe listener — fall back to within-band pan.
                        int w = getWidth();
                        if (w == 0) return false;
                        panBins += Math.round(distX * visibleBins() / (float) w);
                        clampPan();
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        applyAutoZoom(channelFrequencies, centerFreqHz, sampleRate);
                        invalidate();
                        return true;
                    }
                });

        setClickable(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean s = scaleDetector.onTouchEvent(e);
        boolean g = gestureDetector.onTouchEvent(e);
        return s || g || super.onTouchEvent(e);
    }

    // ── Zoom/pan helpers ──────────────────────────────────────────────────

    private int visibleBins() {
        return Math.max(16, Math.round(FFT_SIZE / zoomLevel));
    }

    private int viewStartBin() {
        int vis = visibleBins();
        int raw = FFT_SIZE / 2 - vis / 2 + panBins;
        return Math.max(0, Math.min(FFT_SIZE - vis, raw));
    }

    private void clampPan() {
        int vis    = visibleBins();
        int maxPan = (FFT_SIZE - vis) / 2;
        panBins = Math.max(-maxPan, Math.min(maxPan, panBins));
    }

    // 10% margin each side — channels fill ~91% of the waterfall width
    private void applyAutoZoom(double[] frequencies, long center, int rate) {
        if (frequencies != null && frequencies.length >= 2) {
            double minHz = Double.MAX_VALUE, maxHz = -Double.MAX_VALUE;
            for (double f : frequencies) {
                if (f < minHz) minHz = f;
                if (f > maxHz) maxHz = f;
            }
            double spanHz     = Math.max(maxHz - minHz, 10_000);
            double targetBwHz = spanHz * 1.1;
            zoomLevel = (float) Math.max(1.0, Math.min(16.0, rate / targetBwHz));
            panBins   = (int) Math.round((((minHz + maxHz) / 2.0) - center) * FFT_SIZE / rate);
        } else {
            zoomLevel = 1f;
            panBins   = 0;
        }
        clampPan();
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void setAlertListener(AlertListener l) { alertListener = l; }
    public void setAlertThreshold(float db)       { alertThresholdDb = db; }
    public void setSwipeListener(SwipeListener l) { swipeListener    = l; }
    // Returns true when the current view has a signal above the alert threshold.
    // The alertActive flag is set immediately on detection (before the 5s warmup),
    // making it usable for scan-pause without waiting for the full warmup.
    public boolean isSignalPresent()              { return alertActive; }

    public void setChannels(double[] frequencies, long center, int rate) {
        // Save current band state before switching
        if (currentBandKey != Long.MIN_VALUE) {
            bandStates.put(currentBandKey,
                    new BandState(waterfall, zoomLevel, panBins, emaFloor, alertActive));
        }

        channelFrequencies = frequencies;
        centerFreqHz       = center;
        sampleRate         = rate;
        long newKey        = center;

        BandState saved = bandStates.get(newKey);
        if (saved != null) {
            waterfall   = saved.bitmap;
            zoomLevel   = saved.zoomLevel;
            panBins     = saved.panBins;
            emaFloor    = saved.emaFloor;
            alertActive = saved.alertActive;
            if (waterfall == null && getWidth() > 0 && getHeight() > 0) {
                waterfall = Bitmap.createBitmap(FFT_SIZE, waterfallBitmapHeight(), Bitmap.Config.ARGB_8888);
                waterfall.eraseColor(Color.BLACK);
            }
        } else {
            if (getWidth() > 0 && getHeight() > 0) {
                waterfall = Bitmap.createBitmap(FFT_SIZE, waterfallBitmapHeight(), Bitmap.Config.ARGB_8888);
                waterfall.eraseColor(Color.BLACK);
            } else {
                waterfall = null;
            }
            emaFloor    = null;
            alertActive = false;
            applyAutoZoom(frequencies, center, rate);
        }

        currentBandKey     = newKey;
        alertWarmupUntilMs = android.os.SystemClock.elapsedRealtime() + 5_000;
        alertActive        = false;  // reset edge so a signal present before warmup ends re-triggers
        invalidate();
    }

    public void addFftLine(float[] dbfsLine) {
        latestLine = dbfsLine;
        checkAlert(dbfsLine);
        if (!pendingInvalidate) {
            pendingInvalidate = true;
            postInvalidate();
        }
    }

    // ── Alert detection ───────────────────────────────────────────────────

    private void checkAlert(float[] dbfs) {
        if (emaFloor == null) {
            emaFloor = new float[FFT_SIZE];
            Arrays.fill(emaFloor, -90f);
        }

        int   start     = viewStartBin();
        int   vis       = visibleBins();
        float maxExcess = -100f;

        for (int i = 0; i < FFT_SIZE; i++) {
            emaFloor[i] = emaFloor[i] * (1f - EMA_ALPHA) + dbfs[i] * EMA_ALPHA;
            if (i >= start && i < start + vis) {
                float excess = dbfs[i] - emaFloor[i];
                if (excess > maxExcess) maxExcess = excess;
            }
        }

        boolean warmedUp = android.os.SystemClock.elapsedRealtime() >= alertWarmupUntilMs;

        if (!alertActive && maxExcess > alertThresholdDb) {
            alertActive = true;
            if (warmedUp && alertListener != null) alertListener.onAlert();
        } else if (alertActive && maxExcess < alertThresholdDb - 3f) {
            alertActive = false;
            if (warmedUp && alertListener != null) alertListener.onAlertCleared();
        }
    }

    // ── View callbacks ────────────────────────────────────────────────────

    private int waterfallBitmapHeight() {
        return Math.max(1, getHeight() - (int) hdrHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w > 0 && h > 0) {
            for (BandState s : bandStates.values()) {
                if (s.bitmap != null) { s.bitmap.recycle(); s.bitmap = null; }
            }
            if (waterfall != null) waterfall.recycle();
            waterfall = Bitmap.createBitmap(FFT_SIZE, Math.max(1, h - (int) hdrHeight),
                    Bitmap.Config.ARGB_8888);
            waterfall.eraseColor(Color.BLACK);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (BandState s : bandStates.values()) {
            if (s.bitmap != null) { s.bitmap.recycle(); s.bitmap = null; }
        }
        bandStates.clear();
        if (waterfall != null) { waterfall.recycle(); waterfall = null; }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        pendingInvalidate = false;

        int w = getWidth(), h = getHeight();
        if (waterfall == null || w == 0 || h == 0) return;

        float[] line = latestLine;
        if (line != null) appendRow(line);

        int vis   = visibleBins();
        int start = viewStartBin();
        Rect  src = new Rect(start, 0, start + vis, waterfall.getHeight());
        RectF dst = new RectF(0, hdrHeight, w, h);
        canvas.drawBitmap(waterfall, src, dst, bitmapPaint);

        double binsToHz = (double) sampleRate / FFT_SIZE;
        double leftHz   = centerFreqHz + (start       - FFT_SIZE / 2.0) * binsToHz;
        double rightHz  = centerFreqHz + (start + vis - FFT_SIZE / 2.0) * binsToHz;

        drawChannelMarkers(canvas, w, h, leftHz, rightHz);
        drawFrequencyAxis(canvas, w, h, leftHz, rightHz);
        drawFrequencyHeader(canvas, w, leftHz, rightHz);
    }

    // ── Private drawing helpers ───────────────────────────────────────────

    private void appendRow(float[] dbfs) {
        int bW = waterfall.getWidth(), bH = waterfall.getHeight();
        int[] px = new int[bW * bH];
        waterfall.getPixels(px, 0, bW, 0, 0, bW, bH);
        System.arraycopy(px, 0, px, bW, bW * (bH - 1));
        for (int i = 0; i < FFT_SIZE; i++) px[i] = dbToColor(dbfs[i]);
        waterfall.setPixels(px, 0, bW, 0, 0, bW, bH);
    }

    private void drawFrequencyHeader(Canvas canvas, int w, double leftHz, double rightHz) {
        canvas.drawRect(0, 0, w, hdrHeight, hdrBgPaint);

        Paint.FontMetrics fm   = hdrCtrPaint.getFontMetrics();
        float textY = hdrHeight / 2f - (fm.ascent + fm.descent) / 2f;

        Paint.FontMetrics fmE = hdrEdgePaint.getFontMetrics();
        float edgeY = hdrHeight / 2f - (fmE.ascent + fmE.descent) / 2f;

        double centerHz = (leftHz + rightHz) / 2.0;
        canvas.drawText(String.format("%.3f", leftHz  / 1e6),      6,      edgeY, hdrEdgePaint);
        canvas.drawText(String.format("%.3f MHz", centerHz / 1e6), w/2f,   textY, hdrCtrPaint);
        canvas.drawText(String.format("%.3f", rightHz / 1e6),      w - 6,  edgeY, hdrRightPaint);
    }

    private void drawChannelMarkers(Canvas canvas, int w, int h,
                                    double leftHz, double rightHz) {
        if (channelFrequencies == null || channelFrequencies.length == 0) return;
        double bw = rightHz - leftHz;

        // Label scales from baseLabelPx at zoom=1 up to 2× baseLabelPx at zoom=16
        float labelPx = baseLabelPx * (1f + (zoomLevel - 1f) / 15f);
        chanLabelPaint.setTextSize(labelPx);

        Paint.FontMetrics fm  = chanLabelPaint.getFontMetrics();
        float textH    = fm.descent - fm.ascent;
        // Baseline so top of text sits flush with top of waterfall area
        float baselineY = hdrHeight - fm.ascent;

        int row = 0;
        for (double freq : channelFrequencies) {
            if (freq < leftHz || freq > rightHz) continue;
            float x = (float)((freq - leftHz) / bw) * w;
            canvas.drawLine(x, hdrHeight, x, h, channelPaint);
            String label = GMRSChannels.identifyChannel(freq);
            if (label != null) {
                float yOff = baselineY + (row % 2) * (textH + 2f);
                canvas.drawText(label, x, yOff, chanLabelPaint);
                row++;
            }
        }
    }

    private void drawFrequencyAxis(Canvas canvas, int w, int h,
                                   double leftHz, double rightHz) {
        double bwHz = rightHz - leftHz;
        double stepMHz;
        if      (bwHz > 1.5e6)  stepMHz = 0.5;
        else if (bwHz > 0.75e6) stepMHz = 0.25;
        else if (bwHz > 0.3e6)  stepMHz = 0.1;
        else if (bwHz > 0.1e6)  stepMHz = 0.05;
        else                    stepMHz = 0.025;

        double leftMHz  = leftHz  / 1e6;
        double rightMHz = rightHz / 1e6;
        double first    = Math.ceil(leftMHz / stepMHz) * stepMHz;

        for (double mhz = first; mhz <= rightMHz + 1e-9; mhz += stepMHz) {
            float frac = (float)((mhz - leftMHz) / (rightMHz - leftMHz));
            if (frac < 0 || frac > 1) continue;
            float x = frac * w;
            canvas.drawLine(x, h - 20, x, h, axisPaint);
            canvas.drawText(String.format("%.3f", mhz), x - 14, h - 4, axisPaint);
        }
    }

    // ── SDRTrunk-style heat palette ───────────────────────────────────────

    private static int dbToColor(float db) {
        if (db <= DB_NOISE) return Color.BLACK;
        float t;
        if (db < DB_MID1) {
            t = (db - DB_NOISE) / (DB_MID1 - DB_NOISE);
            return Color.rgb(0, 0, (int)(60 * t));
        } else if (db < DB_MID2) {
            t = (db - DB_MID1) / (DB_MID2 - DB_MID1);
            return Color.rgb(0, (int)(30 * t), (int)(60 + 195 * t));
        } else if (db < DB_MID3) {
            t = (db - DB_MID2) / (DB_MID3 - DB_MID2);
            return Color.rgb(0, (int)(30 + 225 * t), 255);
        } else if (db < DB_MID4) {
            t = (db - DB_MID3) / (DB_MID4 - DB_MID3);
            return Color.rgb((int)(255 * t), 255, (int)(255 * (1f - t)));
        } else {
            t = Math.min(1f, (db - DB_MID4) / (DB_MAX - DB_MID4));
            return Color.rgb(255,
                    (int)(255 * (1f - t * 0.8f)),
                    (int)(255 * Math.max(0f, t - 0.5f) * 2f));
        }
    }
}
