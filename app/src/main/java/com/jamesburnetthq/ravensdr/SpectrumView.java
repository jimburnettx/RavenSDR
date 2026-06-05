package com.jamesburnetthq.ravensdr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Displays a live power spectrum (bar graph style) beneath the waterfall. */
public class SpectrumView extends View {

    private static final int FFT_SIZE = DspEngine.FFT_SIZE;
    private static final float DB_MIN = -100f;
    private static final float DB_MAX = -10f;

    private float[] currentLine;
    private final Paint barPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint textPaint = new Paint();

    public SpectrumView(Context context) { super(context); init(); }
    public SpectrumView(Context context, AttributeSet a) { super(context, a); init(); }
    public SpectrumView(Context context, AttributeSet a, int s) { super(context, a, s); init(); }

    private void init() {
        barPaint.setColor(Color.rgb(0, 200, 80));
        barPaint.setStrokeWidth(1f);

        gridPaint.setColor(Color.argb(100, 100, 100, 100));
        gridPaint.setStrokeWidth(1f);

        textPaint.setColor(Color.argb(180, 160, 160, 160));
        textPaint.setTextSize(14f);
        textPaint.setAntiAlias(true);
    }

    public void updateSpectrum(float[] dbfsLine) {
        currentLine = dbfsLine;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(Color.rgb(10, 10, 10));

        // Grid lines at -90, -70, -50, -30 dBFS
        float[] gridDb = { -90f, -70f, -50f, -30f };
        for (float db : gridDb) {
            float y = dbToY(db, h);
            canvas.drawLine(0, y, w, y, gridPaint);
            canvas.drawText((int)db + " dB", 4, y - 2, textPaint);
        }

        float[] line = currentLine;
        if (line == null) return;

        float binW = (float) w / FFT_SIZE;
        for (int i = 0; i < FFT_SIZE; i++) {
            float x = i * binW;
            float y = dbToY(line[i], h);
            int r = Math.min(255, Math.max(0, (int)((line[i] - DB_MIN) / (DB_MAX - DB_MIN) * 255)));
            barPaint.setColor(Color.rgb(r, Math.max(0, 200 - r / 2), 80));
            canvas.drawLine(x, y, x, h, barPaint);
        }
    }

    private float dbToY(float db, int h) {
        float norm = (db - DB_MIN) / (DB_MAX - DB_MIN);
        return h * (1f - Math.max(0f, Math.min(1f, norm)));
    }
}
