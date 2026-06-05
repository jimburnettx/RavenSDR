package com.jamesburnetthq.ravensdr;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.hardware.usb.UsbDevice;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity
        implements UsbHelper.Listener, DspEngine.FftListener, ConsoleLogger.Listener,
                   WaterfallView.AlertListener, DspEngine.StopListener,
                   ListenEngine.Listener {

    private static final String TAG = "MainActivity";

    // Active band tint colors
    private static final int TINT_ACTIVE   = 0xFF225522;
    private static final int TINT_INACTIVE = 0xFF1A1A1A;
    private static final int TEXT_ACTIVE   = 0xFFFFFFFF;
    private static final int TEXT_INACTIVE = 0xFF888888;

    private UsbHelper   usbHelper;
    private RtlSdrDriver rtlDriver;
    private DspEngine   dspEngine;

    private WaterfallView waterfallView;
    private TextView tvStatus;
    private TextView tvCenterFreq;
    private TextView tvActiveChannel;
    private Button   btnGmrs, btnGmrsR, btnFrs, btnMesh, btnAprs, btnFobs, btnMic, btnCb;
    private Button   btnGainMode;

    // Console / tab
    private View       tabControls;
    private ScrollView tabConsole;
    private View       tabAlert;
    private ScrollView tabListen;
    private TextView   tvConsole;
    private Button     btnTabControls, btnTabConsole, btnTabAlert, btnTabListen;

    // Band monitor buttons
    private Button btnMonitorGmrs, btnMonitorFrs, btnMonitorCb;

    // GMRS simplex channels (462 MHz, FM, 25 kHz spacing)
    private static final long   GMRS_CENTER   = 462_637_500L;
    private static final long[] GMRS_CHANNELS = {
        462_562_500L, 462_587_500L, 462_612_500L, 462_637_500L,
        462_662_500L, 462_687_500L, 462_712_500L };

    // FRS-only channels (467 MHz, FM, 25 kHz spacing)
    private static final long   FRS467_CENTER   = 467_637_500L;
    private static final long[] FRS467_CHANNELS = {
        467_562_500L, 467_587_500L, 467_612_500L, 467_637_500L,
        467_662_500L, 467_687_500L, 467_712_500L };

    // CB channels (27 MHz, AM, 40 channels)
    private static final long   CB_MONITOR_CENTER   = 27_185_000L;
    private static final long[] CB_MONITOR_CHANNELS = {
        26_965_000L, 26_975_000L, 26_985_000L, 27_005_000L, 27_015_000L,
        27_025_000L, 27_035_000L, 27_055_000L, 27_065_000L, 27_075_000L,
        27_085_000L, 27_105_000L, 27_115_000L, 27_125_000L, 27_135_000L,
        27_155_000L, 27_165_000L, 27_175_000L, 27_185_000L, 27_205_000L,
        27_215_000L, 27_225_000L, 27_235_000L, 27_245_000L, 27_255_000L,
        27_265_000L, 27_275_000L, 27_285_000L, 27_295_000L, 27_305_000L,
        27_315_000L, 27_325_000L, 27_335_000L, 27_345_000L, 27_355_000L,
        27_365_000L, 27_375_000L, 27_385_000L, 27_395_000L, 27_405_000L };

    // Explore tab (controls + navigation)
    private EditText etControlsFreq;
    private Spinner  spinnerSwipeStep;
    private Spinner  spinnerScanSpeed;
    private Button   btnNavScanDown, btnNavStepDown, btnNavStepUp, btnNavScanUp, btnNavStop;

    // Scan state
    private boolean  isScanning    = false;
    private int      scanDirection = 0;   // -1 = down, +1 = up
    private final android.os.Handler scanHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final Runnable scanRunnable = this::doScanStep;

    // Listen tab
    private ListenEngine listenEngine;
    private EditText   etListenFreq;
    private Spinner    spinnerModulation;
    private SeekBar    seekBarSquelch;
    private TextView   tvSquelchLabel, tvListenStatus, tvListenSignal, tvScanStatus;
    private Button     btnOpenPlaylist, btnPlayPlaylist;

    // ── Playlist ──────────────────────────────────────────────────────────────
    private final java.util.ArrayList<PlaylistEntry> playlistEntries = new java.util.ArrayList<>();
    private android.app.Dialog playlistDialog;
    private android.widget.LinearLayout playlistEntriesView;

    private static class PlaylistEntry {
        String name;
        long   freqHz;
        ListenEngine.DemodType demodType;
        boolean enabled;
        PlaylistEntry(String n, long f, ListenEngine.DemodType d, boolean e) {
            name = n; freqHz = f; demodType = d; enabled = e;
        }
        String toPrefsString() {
            return name + "|" + freqHz + "|" + demodType.name() + "|" + (enabled ? "1" : "0");
        }
        static PlaylistEntry fromString(String s) {
            String[] p = s.split("\\|", 4);
            if (p.length < 4) return null;
            try {
                ListenEngine.DemodType dt = ListenEngine.DemodType.NFM;
                try { dt = ListenEngine.DemodType.valueOf(p[2]); } catch (Exception ignored) {}
                return new PlaylistEntry(p[0], Long.parseLong(p[1]), dt, "1".equals(p[3]));
            } catch (Exception e) { return null; }
        }
    }

    // Alert tab
    private SeekBar        seekBarSensitivity;
    private TextView       tvSensitivityLabel;
    private TextView       tvAlertStatus;
    private TextView       tvLastAlert;
    private CheckBox       chkAlertSound;
    private boolean        alertSoundEnabled = false;
    private android.widget.LinearLayout llAlertHistory;
    private static final int MAX_ALERT_HISTORY = 8;
    private final java.util.ArrayDeque<long[]> alertHistory = new java.util.ArrayDeque<>();

    private static final long FREQ_MIN_HZ = 500_000L;
    private static final long FREQ_MAX_HZ = 1_766_000_000L;

    private static final int MAX_DSP_RETRIES = 3;
    private int  dspRetryCount = 0;

    private final Handler  uiHandler     = new Handler(Looper.getMainLooper());
    private long           lastAlertMs   = 0;
    private android.media.SoundPool sonarPool;
    private int            sonarSoundId  = -1;
    private boolean        sonarLoaded   = false;
    private final Runnable clearAlertUi  = () -> {
        tvAlertStatus.setTextColor(0xFF446644);
        tvAlertStatus.setText("● Monitoring");
    };

    private long    currentCenterFreq = GMRSChannels.CENTER_FREQ_LOW;
    private long    lastListenHz      = 460_400_000L;
    private boolean agcEnabled        = true;
    private boolean deviceOpen        = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        waterfallView   = findViewById(R.id.waterfallView);
        tvStatus        = findViewById(R.id.tvStatus);
        tvCenterFreq    = findViewById(R.id.tvCenterFreq);
        tvActiveChannel = findViewById(R.id.tvActiveChannel);
        btnGmrs         = findViewById(R.id.btnGmrs);
        btnGmrsR        = findViewById(R.id.btnGmrsR);
        btnFrs          = findViewById(R.id.btnFrs);
        btnMesh         = findViewById(R.id.btnMesh);
        btnAprs         = findViewById(R.id.btnAprs);
        btnFobs         = findViewById(R.id.btnFobs);
        btnMic          = findViewById(R.id.btnMic);
        btnCb           = findViewById(R.id.btnCb);
        btnGainMode     = findViewById(R.id.btnGainMode);
        tabControls     = findViewById(R.id.tabControls);
        tabConsole      = findViewById(R.id.tabConsole);
        tabAlert        = findViewById(R.id.tabAlert);
        tvConsole       = findViewById(R.id.tvConsole);
        btnTabControls  = findViewById(R.id.btnTabControls);
        btnTabConsole   = findViewById(R.id.btnTabConsole);
        btnTabAlert     = findViewById(R.id.btnTabAlert);
        btnTabListen    = findViewById(R.id.btnTabListen);
        tabListen       = findViewById(R.id.tabListen);
        etControlsFreq   = findViewById(R.id.etControlsFreq);
        spinnerSwipeStep = findViewById(R.id.spinnerSwipeStep);
        spinnerScanSpeed = findViewById(R.id.spinnerScanSpeed);
        btnNavScanDown   = findViewById(R.id.btnNavScanDown);
        btnNavStepDown   = findViewById(R.id.btnNavStepDown);
        btnNavStepUp     = findViewById(R.id.btnNavStepUp);
        btnNavScanUp     = findViewById(R.id.btnNavScanUp);
        btnNavStop       = findViewById(R.id.btnNavStop);
        etListenFreq     = findViewById(R.id.etListenFreq);
        spinnerModulation     = findViewById(R.id.spinnerModulation);
        seekBarSquelch        = findViewById(R.id.seekBarSquelch);
        tvSquelchLabel        = findViewById(R.id.tvSquelchLabel);
        tvListenStatus        = findViewById(R.id.tvListenStatus);
        tvListenSignal        = findViewById(R.id.tvListenSignal);
        tvScanStatus          = findViewById(R.id.tvScanStatus);
        btnOpenPlaylist  = findViewById(R.id.btnOpenPlaylist);
        btnPlayPlaylist  = findViewById(R.id.btnPlayPlaylist);
        llAlertHistory   = findViewById(R.id.llAlertHistory);
        btnMonitorGmrs        = findViewById(R.id.btnMonitorGmrs);
        btnMonitorFrs         = findViewById(R.id.btnMonitorFrs);
        btnMonitorCb          = findViewById(R.id.btnMonitorCb);
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity);
        tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel);
        tvAlertStatus      = findViewById(R.id.tvAlertStatus);
        tvLastAlert        = findViewById(R.id.tvLastAlert);
        chkAlertSound      = findViewById(R.id.chkAlertSound);
        chkAlertSound.setOnCheckedChangeListener((b, checked) -> alertSoundEnabled = checked);

        // Tab bar: EXPLORE(0) ALARM(1) TERM(2) LISTEN(3)
        btnTabControls.setOnClickListener(v -> showTab(0));
        btnTabAlert.setOnClickListener(v    -> showTab(1));
        btnTabConsole.setOnClickListener(v  -> showTab(2));
        btnTabListen.setOnClickListener(v   -> showTab(3));

        seekBarSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updateSensitivity(progress);
            }
        });
        updateSensitivity(seekBarSensitivity.getProgress());
        waterfallView.setAlertListener(this);

        // EXPLORE tab controls
        ArrayAdapter<String> stepAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"10 kHz", "25 kHz", "100 kHz", "500 kHz", "1 MHz", "5 MHz"});
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSwipeStep.setAdapter(stepAdapter);
        spinnerSwipeStep.setSelection(2); // default 100 kHz

        ArrayAdapter<String> scanAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Fast (0.25s)", "Normal (0.5s)", "Slow (1s)", "Careful (2s)", "Very slow (5s)"});
        scanAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerScanSpeed.setAdapter(scanAdapter);
        spinnerScanSpeed.setSelection(1); // default 0.5s

        etControlsFreq.setText(String.format("%.6f", currentCenterFreq / 1e6));
        findViewById(R.id.btnControlsTune).setOnClickListener(v -> {
            String txt = etControlsFreq.getText().toString().trim();
            if (!txt.isEmpty()) {
                try {
                    long hz = Math.round(Double.parseDouble(txt) * 1_000_000.0);
                    exploreTune(hz);
                    hideKeyboard(etControlsFreq);
                } catch (NumberFormatException ignored) {
                    Toast.makeText(this, "Invalid frequency", Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnNavStepDown.setOnClickListener(v -> { stopScan(); stepFreq(-1); });
        btnNavStepUp.setOnClickListener(  v -> { stopScan(); stepFreq(+1); });
        btnNavScanDown.setOnClickListener(v -> startScan(-1));
        btnNavScanUp.setOnClickListener(  v -> startScan(+1));
        btnNavStop.setOnClickListener(    v -> stopScan());

        ArrayAdapter<String> modAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"FM (Narrowband)", "FM (Broadcast)", "AM (Envelope)"});
        modAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModulation.setAdapter(modAdapter);

        // Listen tab wiring
        seekBarSquelch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                updateSquelchLabel(p);
                if (listenEngine != null) listenEngine.setSquelch(p);
            }
        });
        updateSquelchLabel(seekBarSquelch.getProgress());
        findViewById(R.id.btnListenStart).setOnClickListener(v -> startListenSingle());
        findViewById(R.id.btnListenStop).setOnClickListener(v  -> stopListen());
        btnOpenPlaylist.setOnClickListener(v  -> openPlaylistDialog());
        btnPlayPlaylist.setOnClickListener(v  -> startPlaylistListen());
        loadPlaylistFromPrefs();
        findViewById(R.id.btnClearAlerts).setOnClickListener(v -> {
            alertHistory.clear();
            llAlertHistory.removeAllViews();
        });

        btnMonitorGmrs.setOnClickListener(v ->
                startWidebandMonitor(GMRS_CHANNELS, GMRS_CENTER,
                        ListenEngine.DemodType.NFM, "GMRS 462"));
        btnMonitorFrs.setOnClickListener(v ->
                startWidebandMonitor(FRS467_CHANNELS, FRS467_CENTER,
                        ListenEngine.DemodType.NFM, "FRS 467"));
        btnMonitorCb.setOnClickListener(v ->
                startWidebandMonitor(CB_MONITOR_CHANNELS, CB_MONITOR_CENTER,
                        ListenEngine.DemodType.AM, "CB 27 MHz"));

        ConsoleLogger.get().setListener(this);

        rtlDriver = new RtlSdrDriver();
        if (!RtlSdrDriver.isLibraryLoaded()) {
            tvStatus.setText("Native library failed to load");
            ConsoleLogger.get().log("ERROR: native library failed to load");
            return;
        }

        btnGmrs.setOnClickListener(v  -> selectBand(GMRSChannels.CENTER_FREQ_LOW));
        btnGmrsR.setOnClickListener(v -> selectBand(GMRSChannels.CENTER_FREQ_HIGH));
        btnFrs.setOnClickListener(v   -> selectBand(GMRSChannels.CENTER_FREQ_FRS));
        btnMesh.setOnClickListener(v  -> selectBand(GMRSChannels.CENTER_FREQ_MESH));
        btnAprs.setOnClickListener(v  -> selectBand(GMRSChannels.CENTER_FREQ_APRS));
        btnFobs.setOnClickListener(v  -> selectBand(GMRSChannels.CENTER_FREQ_FOBS));
        btnMic.setOnClickListener(v   -> selectBand(GMRSChannels.CENTER_FREQ_MIC));
        btnCb.setOnClickListener(v    -> selectBand(GMRSChannels.CENTER_FREQ_CB));
        btnGainMode.setOnClickListener(v -> toggleGainMode());
        applyGainMode(agcEnabled);  // set initial button appearance

        updateBandButtons();

        // Load sonar alert sound via SoundPool (low-latency, alarm stream)
        sonarPool = new android.media.SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        sonarPool.setOnLoadCompleteListener((sp, sampleId, status) ->
                sonarLoaded = (status == 0));
        sonarSoundId = sonarPool.load(this, R.raw.sonar, 1);

        usbHelper = new UsbHelper(this, this);
        usbHelper.start();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
                .equals(intent.getAction())) {
            usbHelper.handleAttachedIntent(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConsoleLogger.get().setListener(null);
        stopScan();
        if (listenEngine != null) { listenEngine.stop(); listenEngine = null; }
        stopDsp();
        if (sonarPool != null) { sonarPool.release(); sonarPool = null; }
        if (usbHelper != null) usbHelper.stop();
        if (rtlDriver != null && deviceOpen) rtlDriver.nativeClose();
    }

    // ── Tab switching ──────────────────────────────────────────────────────

    private void showTab(int tab) {
        if (tab != 3 && listenEngine != null && listenEngine.isRunning()) {
            stopListen();
        }
        if (tab == 3) stopScan(); // entering LISTEN — stop any ongoing scan
        // Resize waterfall: LISTEN gets 35%, EXPLORE gets 65%, others get full 50/50
        setWaterfallWeight(tab == 3 ? 0.35f : tab == 0 ? 0.65f : 1.0f);
        // EXPLORE(0) ALARM(1) TERM(2) LISTEN(3)
        tabControls.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        tabAlert.setVisibility(   tab == 1 ? View.VISIBLE : View.GONE);
        tabConsole.setVisibility( tab == 2 ? View.VISIBLE : View.GONE);
        tabListen.setVisibility(  tab == 3 ? View.VISIBLE : View.GONE);
        tintTab(btnTabControls, tab == 0);
        tintTab(btnTabAlert,    tab == 1);
        tintTab(btnTabConsole,  tab == 2);
        tintTab(btnTabListen,   tab == 3);
        if (tab == 2) {
            tvConsole.setText(ConsoleLogger.get().getFullLog());
            tabConsole.post(() -> tabConsole.fullScroll(View.FOCUS_DOWN));
        }
        if (tab == 0) {
            etControlsFreq.setText(String.format("%.6f", currentCenterFreq / 1e6));
        }
        if (tab == 3) {
            etListenFreq.setText(String.format("%.6f", lastListenHz / 1e6));
        }
    }

    // ── Alert sensitivity ─────────────────────────────────────────────────

    private void updateSensitivity(int progress) {
        float threshold = 25f - progress * 0.20f;
        String level = progress < 33 ? "Low" : (progress < 67 ? "Medium" : "High");
        tvSensitivityLabel.setText(level + "  ·  " + (int) threshold + " dB threshold");
        waterfallView.setAlertThreshold(threshold);
    }

    // ── WaterfallView.AlertListener ───────────────────────────────────────

    @Override
    public void onAlert() {
        if (isScanning) {
            // Signal found — cancel the scheduled step and re-schedule after a 3s pause
            scanHandler.removeCallbacks(scanRunnable);
            scanHandler.postDelayed(scanRunnable, 3_000);
        }
        if (alertSoundEnabled) playRadarPing();
        tvAlertStatus.setTextColor(0xFF00FF44);
        tvAlertStatus.setText("● SIGNAL DETECTED");
        tvLastAlert.setText(String.format("Last: %tT", new java.util.Date()));
        uiHandler.removeCallbacks(clearAlertUi);
        uiHandler.postDelayed(clearAlertUi, 5000);

        long[] entry = { currentCenterFreq, System.currentTimeMillis() };
        alertHistory.addFirst(entry);
        while (alertHistory.size() > MAX_ALERT_HISTORY) alertHistory.removeLast();
        rebuildAlertHistory();
    }

    private void rebuildAlertHistory() {
        llAlertHistory.removeAllViews();
        for (long[] entry : alertHistory) {
            long freqHz = entry[0];
            long timeMs = entry[1];

            Button row = new Button(this);
            row.setText(String.format("%.4f MHz  %tT", freqHz / 1e6, new java.util.Date(timeMs)));
            row.setTextSize(11f);
            row.setTextColor(0xFF88FFAA);
            row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF0D1A0D));
            row.setStateListAnimator(null);
            row.setAllCaps(false);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 2, 0, 2);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                etListenFreq.setText(String.format("%.6f", freqHz / 1e6));
                showTab(3);
            });
            llAlertHistory.addView(row);
        }
        if (alertHistory.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("–");
            empty.setTextColor(0xFF335533);
            empty.setTextSize(11f);
            llAlertHistory.addView(empty);
        }
    }

    @Override
    public void onAlertCleared() {
        // status UI auto-clears via postDelayed above
    }

    // ── Radar ping sound ──────────────────────────────────────────────────

    private void playRadarPing() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAlertMs < 2000) return;
        lastAlertMs = now;
        if (sonarLoaded && sonarPool != null) {
            sonarPool.play(sonarSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        } else {
            Log.w(TAG, "sonar sound not ready — skipping alert tone");
        }
    }

    private void tintTab(Button b, boolean active) {
        b.setBackgroundTintList(ColorStateList.valueOf(active ? TINT_ACTIVE : TINT_INACTIVE));
        b.setTextColor(active ? TEXT_ACTIVE : TEXT_INACTIVE);
    }

    // ── ConsoleLogger.Listener ─────────────────────────────────────────────

    @Override
    public void onNewLine(String line) {
        runOnUiThread(() -> {
            if (tabConsole.getVisibility() == View.VISIBLE) {
                tvConsole.append(line + "\n");
                tabConsole.post(() -> tabConsole.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    // ── UsbHelper.Listener ─────────────────────────────────────────────────

    @Override
    public void onDeviceReady(int fileDescriptor, UsbDevice device) {
        Log.i(TAG, "USB device ready: fd=" + fileDescriptor);
        ConsoleLogger.get().log("USB ready fd=" + fileDescriptor
                + " VID=0x" + Integer.toHexString(device.getVendorId())
                + " PID=0x" + Integer.toHexString(device.getProductId()));
        runOnUiThread(() -> tvStatus.setText("Opening…"));

        String err = rtlDriver.nativeOpen(fileDescriptor);
        if (err != null) {
            Log.e(TAG, "nativeOpen failed: " + err);
            ConsoleLogger.get().log("nativeOpen FAILED: " + err);
            final String msg = err;
            runOnUiThread(() -> {
                tvStatus.setText("Init failed: " + msg);
                Toast.makeText(this, "RTL-SDR: " + msg, Toast.LENGTH_LONG).show();
            });
            return;
        }

        deviceOpen = true;
        String info = rtlDriver.nativeGetDeviceInfo();
        ConsoleLogger.get().log("Device info: " + info);

        runOnUiThread(() -> {
            tvStatus.setText("Connected — " + extractTuner(info));
            startDsp();
        });
    }

    @Override
    public void onDeviceDetached() {
        ConsoleLogger.get().log("USB device detached");
        dspRetryCount = 0;
        uiHandler.removeCallbacks(retryDspRunnable);
        stopScan();
        stopDsp();
        if (deviceOpen) { rtlDriver.nativeClose(); deviceOpen = false; }
        runOnUiThread(() -> tvStatus.setText("Disconnected"));
    }

    @Override
    public void onPermissionDenied() {
        ConsoleLogger.get().log("USB permission denied");
        runOnUiThread(() -> {
            tvStatus.setText("USB permission denied");
            Toast.makeText(this, "USB permission required", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onNoDeviceFound() {
        ConsoleLogger.get().log("No RTL-SDR found");
        runOnUiThread(() -> tvStatus.setText("No RTL-SDR — plug in dongle"));
    }

    // ── DspEngine.FftListener ──────────────────────────────────────────────

    @Override
    public void onFftLine(float[] dbfsLine) {
        waterfallView.addFftLine(dbfsLine);
    }

    // ── UI controls ───────────────────────────────────────────────────────

    private void selectBand(long centerHz) {
        currentCenterFreq = centerHz;
        updateBandButtons();
        updateFrequencyDisplay();

        double[] channels = GMRSChannels.getVisibleChannels(centerHz);
        waterfallView.setChannels(channels, centerHz, GMRSChannels.SAMPLE_RATE);
        tvActiveChannel.setText(GMRSChannels.getBandLabel(centerHz));
        ConsoleLogger.get().log("Band → " + GMRSChannels.getBandLabel(centerHz));

        if (deviceOpen && dspEngine != null) {
            dspEngine.retune(centerHz);
        }
    }

    private void updateBandButtons() {
        setButtonActive(btnGmrs,  currentCenterFreq == GMRSChannels.CENTER_FREQ_LOW);
        setButtonActive(btnGmrsR, currentCenterFreq == GMRSChannels.CENTER_FREQ_HIGH);
        setButtonActive(btnFrs,   currentCenterFreq == GMRSChannels.CENTER_FREQ_FRS);
        setButtonActive(btnMesh,  currentCenterFreq == GMRSChannels.CENTER_FREQ_MESH);
        setButtonActive(btnAprs,  currentCenterFreq == GMRSChannels.CENTER_FREQ_APRS);
        setButtonActive(btnFobs,  currentCenterFreq == GMRSChannels.CENTER_FREQ_FOBS);
        setButtonActive(btnMic,   currentCenterFreq == GMRSChannels.CENTER_FREQ_MIC);
        setButtonActive(btnCb,    currentCenterFreq == GMRSChannels.CENTER_FREQ_CB);
    }

    private void setButtonActive(Button b, boolean active) {
        b.setBackgroundTintList(ColorStateList.valueOf(active ? TINT_ACTIVE : TINT_INACTIVE));
        b.setTextColor(active ? TEXT_ACTIVE : TEXT_INACTIVE);
    }

    private void applyGainMode(boolean agc) {
        agcEnabled = agc;
        if (agcEnabled) {
            btnGainMode.setText("AGC");
            btnGainMode.setBackgroundTintList(ColorStateList.valueOf(0xFF1A3A1A));
            btnGainMode.setTextColor(0xFF88FF88);
        } else {
            btnGainMode.setText("Manual");
            btnGainMode.setBackgroundTintList(ColorStateList.valueOf(0xFF1A1A3A));
            btnGainMode.setTextColor(0xFF8888FF);
        }
    }

    private void toggleGainMode() {
        applyGainMode(!agcEnabled);
        if (deviceOpen) {
            if (agcEnabled) {
                rtlDriver.nativeSetAgc(true);
            } else {
                rtlDriver.nativeSetAgc(false);
                rtlDriver.nativeSetGain(300);
            }
        }
        ConsoleLogger.get().log("Gain → " + (agcEnabled ? "AGC" : "Manual 30 dB"));
    }

    private void startDsp() {
        double[] channels = GMRSChannels.getVisibleChannels(currentCenterFreq);
        waterfallView.setChannels(channels, currentCenterFreq, GMRSChannels.SAMPLE_RATE);
        updateFrequencyDisplay();
        tvActiveChannel.setText(GMRSChannels.getBandLabel(currentCenterFreq));

        ConsoleLogger.get().log("Starting DSP: " + currentCenterFreq
                + " Hz, gain=" + (agcEnabled ? "AGC" : "Manual 30dB"));
        dspEngine = new DspEngine(rtlDriver, this);
        dspEngine.setStopListener(this);
        dspEngine.start(currentCenterFreq, GMRSChannels.SAMPLE_RATE, agcEnabled, 300);
        tvStatus.setText("Scanning…");
    }

    private void stopDsp() {
        if (dspEngine != null) {
            dspEngine.stop();
            dspEngine = null;
        }
    }

    // ── Listen tab ────────────────────────────────────────────────────────────

    private void startListenSingle() {
        if (!deviceOpen) { Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show(); return; }
        String txt = etListenFreq.getText().toString().trim();
        if (txt.isEmpty()) { Toast.makeText(this, "Enter a frequency", Toast.LENGTH_SHORT).show(); return; }
        long hz;
        try { hz = Math.round(Double.parseDouble(txt) * 1_000_000.0); }
        catch (NumberFormatException e) { Toast.makeText(this, "Invalid frequency", Toast.LENGTH_SHORT).show(); return; }
        hz = Math.max(FREQ_MIN_HZ, Math.min(FREQ_MAX_HZ, hz));
        hideKeyboard(etListenFreq);
        startListenMode(hz);
    }

    private ListenEngine.DemodType getSelectedDemodType() {
        switch (spinnerModulation.getSelectedItemPosition()) {
            case 1:  return ListenEngine.DemodType.WFM;
            case 2:  return ListenEngine.DemodType.AM;
            default: return ListenEngine.DemodType.NFM;
        }
    }

    private void startListenMode(long hz) {
        if (!deviceOpen) return;
        lastListenHz = hz;
        ListenEngine.DemodType demodType = getSelectedDemodType();
        stopDsp();
        if (listenEngine != null) listenEngine.stop();
        listenEngine = new ListenEngine(rtlDriver, this);
        listenEngine.setSquelch(seekBarSquelch.getProgress());
        listenEngine.setFftListener(this);

        double[] chans = GMRSChannels.getVisibleChannels(hz);
        waterfallView.setChannels(chans, hz, ListenEngine.LISTEN_SAMPLE_RATE);

        String modLabel = demodType == ListenEngine.DemodType.AM  ? "AM"
                        : demodType == ListenEngine.DemodType.WFM ? "WFM" : "FM";
        tvListenStatus.setText("● Listening " + String.format("%.4f MHz", hz / 1e6)
                + "  [" + modLabel + "]");
        tvScanStatus.setText("");
        listenEngine.startListen(hz, demodType);
        tvStatus.setText("Listening…");
        ConsoleLogger.get().log("Listen " + String.format("%.4f MHz", hz / 1e6)
                + " [" + modLabel + "]");
    }

    private void startWidebandMonitor(long[] channels, long centerHz,
                                       ListenEngine.DemodType demodType, String label) {
        if (!deviceOpen) { Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show(); return; }
        stopDsp();
        if (listenEngine != null) listenEngine.stop();
        listenEngine = new ListenEngine(rtlDriver, this);
        listenEngine.setSquelch(seekBarSquelch.getProgress());
        listenEngine.setFftListener(this);

        double[] chans = GMRSChannels.getVisibleChannels(centerHz);
        waterfallView.setChannels(chans, centerHz, ListenEngine.LISTEN_SAMPLE_RATE);

        tvListenStatus.setText("● Monitoring " + label + "…");
        tvScanStatus.setText("Hunting across " + channels.length + " channels");
        listenEngine.startWideband(channels, centerHz, demodType);
        tvStatus.setText("Listening…");
        ConsoleLogger.get().log("Band monitor: " + label + " center=" +
                String.format("%.4f MHz", centerHz / 1e6));
    }

    private void stopListen() {
        if (listenEngine != null) { listenEngine.stop(); listenEngine = null; }
        tvListenStatus.setTextColor(0xFF446644);
        tvListenStatus.setText("● Idle");
        tvListenSignal.setText("");
        tvScanStatus.setText("");
        if (deviceOpen) startDsp();
        tvStatus.setText("Scanning…");
    }

    // ── Playlist ──────────────────────────────────────────────────────────────

    private android.content.SharedPreferences playlistPrefs() {
        return getSharedPreferences("playlist", MODE_PRIVATE);
    }

    private void loadPlaylistFromPrefs() {
        playlistEntries.clear();
        java.util.Set<String> stored = playlistPrefs().getStringSet("entries",
                new java.util.HashSet<>());
        for (String s : stored) {
            PlaylistEntry e = PlaylistEntry.fromString(s);
            if (e != null) playlistEntries.add(e);
        }
        playlistEntries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    private void savePlaylistToPrefs() {
        java.util.Set<String> stored = new java.util.HashSet<>();
        for (PlaylistEntry e : playlistEntries) stored.add(e.toPrefsString());
        playlistPrefs().edit().putStringSet("entries", stored).apply();
    }

    private void openPlaylistDialog() {
        // Build dialog content programmatically so we can refresh it in-place
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF080808);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, pad);

        playlistEntriesView = new android.widget.LinearLayout(this);
        playlistEntriesView.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(playlistEntriesView);

        // Divider
        android.view.View div = new android.view.View(this);
        android.widget.LinearLayout.LayoutParams divLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.round(getResources().getDisplayMetrics().density));
        divLp.topMargin = pad;
        divLp.bottomMargin = pad / 2;
        div.setLayoutParams(divLp);
        div.setBackgroundColor(0xFF333333);
        root.addView(div);

        // ── ADD NEW ENTRY ──────────────────────────────────────────────────
        android.widget.TextView addLabel = new android.widget.TextView(this);
        addLabel.setText("ADD ENTRY");
        addLabel.setTextColor(0xFF555555);
        addLabel.setTextSize(10f);
        addLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        addLabel.setPadding(0, 0, 0, Math.round(4 * getResources().getDisplayMetrics().density));
        root.addView(addLabel);

        int h = Math.round(38 * getResources().getDisplayMetrics().density);

        android.widget.EditText etName = new android.widget.EditText(this);
        etName.setHint("Name");
        etName.setTextColor(0xFFCCCCCC);
        etName.setHintTextColor(0xFF444444);
        etName.setTextSize(12f);
        etName.setBackgroundColor(0xFF111111);
        etName.setSingleLine(true);
        etName.setHeight(h);
        etName.setPadding(pad, 0, pad, 0);
        root.addView(etName);

        android.widget.LinearLayout freqModRow = new android.widget.LinearLayout(this);
        freqModRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams rowLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
        freqModRow.setLayoutParams(rowLp);
        freqModRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.EditText etFreq = new android.widget.EditText(this);
        etFreq.setHint("MHz");
        etFreq.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etFreq.setTextColor(0xFFCCCCCC);
        etFreq.setHintTextColor(0xFF444444);
        etFreq.setTextSize(12f);
        etFreq.setBackgroundColor(0xFF111111);
        etFreq.setSingleLine(true);
        etFreq.setHeight(h);
        etFreq.setPadding(pad, 0, pad, 0);
        android.widget.LinearLayout.LayoutParams freqLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f);
        etFreq.setLayoutParams(freqLp);
        freqModRow.addView(etFreq);

        android.widget.Spinner spMod = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> modAdp = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"FM", "FM Broadcast", "AM"});
        modAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMod.setAdapter(modAdp);
        spMod.setBackgroundColor(0xFF111111);
        android.widget.LinearLayout.LayoutParams modLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modLp.setMarginStart(Math.round(4 * getResources().getDisplayMetrics().density));
        spMod.setLayoutParams(modLp);
        freqModRow.addView(spMod);

        android.widget.Button btnAdd = new android.widget.Button(this);
        btnAdd.setText("ADD");
        btnAdd.setTextColor(0xFF00FF88);
        btnAdd.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF112211));
        btnAdd.setStateListAnimator(null);
        btnAdd.setTextSize(11f);
        android.widget.LinearLayout.LayoutParams addBtnLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, h);
        addBtnLp.setMarginStart(Math.round(4 * getResources().getDisplayMetrics().density));
        btnAdd.setLayoutParams(addBtnLp);
        freqModRow.addView(btnAdd);

        root.addView(freqModRow);
        scroll.addView(root);

        // Wire ADD button
        btnAdd.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String freqStr = etFreq.getText().toString().trim();
            if (name.isEmpty() || freqStr.isEmpty()) {
                Toast.makeText(this, "Enter name and frequency", Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.contains("|")) {
                Toast.makeText(this, "Name cannot contain '|'", Toast.LENGTH_SHORT).show();
                return;
            }
            long hz;
            try { hz = Math.round(Double.parseDouble(freqStr) * 1_000_000.0); }
            catch (NumberFormatException ex) {
                Toast.makeText(this, "Invalid frequency", Toast.LENGTH_SHORT).show();
                return;
            }
            ListenEngine.DemodType dt;
            switch (spMod.getSelectedItemPosition()) {
                case 1:  dt = ListenEngine.DemodType.WFM; break;
                case 2:  dt = ListenEngine.DemodType.AM;  break;
                default: dt = ListenEngine.DemodType.NFM; break;
            }
            playlistEntries.add(new PlaylistEntry(name, hz, dt, true));
            playlistEntries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            savePlaylistToPrefs();
            etName.setText(""); etFreq.setText("");
            refreshPlaylistEntries();
        });

        // Populate existing entries and show dialog
        refreshPlaylistEntries();

        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(this,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("PLAYLIST")
                        .setView(scroll)
                        .setPositiveButton("CLOSE", null);

        if (playlistDialog != null && playlistDialog.isShowing()) playlistDialog.dismiss();
        playlistDialog = builder.create();
        playlistDialog.show();
    }

    private void refreshPlaylistEntries() {
        if (playlistEntriesView == null) return;
        playlistEntriesView.removeAllViews();
        if (playlistEntries.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("No entries yet — add one below");
            empty.setTextColor(0xFF445544);
            empty.setTextSize(11f);
            empty.setTypeface(android.graphics.Typeface.MONOSPACE);
            playlistEntriesView.addView(empty);
            return;
        }
        int dp = Math.round(getResources().getDisplayMetrics().density);
        for (PlaylistEntry entry : playlistEntries) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout.LayoutParams rowLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = 2 * dp;
            row.setLayoutParams(rowLp);

            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setChecked(entry.enabled);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF00AA55));
            cb.setOnCheckedChangeListener((v, checked) -> {
                entry.enabled = checked;
                savePlaylistToPrefs();
            });
            row.addView(cb);

            android.widget.TextView tv = new android.widget.TextView(this);
            String modLabel = entry.demodType == ListenEngine.DemodType.AM  ? "AM"
                            : entry.demodType == ListenEngine.DemodType.WFM ? "WFM" : "FM";
            tv.setText(String.format("%s\n%.4f MHz  [%s]",
                    entry.name, entry.freqHz / 1e6, modLabel));
            tv.setTextColor(0xFFCCCCCC);
            tv.setTextSize(11f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            android.widget.LinearLayout.LayoutParams tvLp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvLp.setMarginStart(4 * dp);
            tv.setLayoutParams(tvLp);
            row.addView(tv);

            android.widget.Button del = new android.widget.Button(this);
            del.setText("✕");
            del.setTextColor(0xFFFF4444);
            del.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1A0000));
            del.setStateListAnimator(null);
            del.setTextSize(11f);
            del.setAllCaps(false);
            del.setOnClickListener(v -> {
                playlistEntries.remove(entry);
                savePlaylistToPrefs();
                refreshPlaylistEntries();
            });
            row.addView(del);
            playlistEntriesView.addView(row);
        }
    }

    private void startPlaylistListen() {
        if (!deviceOpen) { Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show(); return; }

        java.util.List<PlaylistEntry> enabled = new java.util.ArrayList<>();
        for (PlaylistEntry e : playlistEntries) { if (e.enabled) enabled.add(e); }
        if (enabled.isEmpty()) {
            Toast.makeText(this, "No frequencies checked in playlist", Toast.LENGTH_SHORT).show();
            return;
        }

        // For a single entry use the regular listen path so modulation is respected
        if (enabled.size() == 1) {
            PlaylistEntry e = enabled.get(0);
            // Set the modulation spinner to match the entry
            int pos = e.demodType == ListenEngine.DemodType.WFM ? 1
                    : e.demodType == ListenEngine.DemodType.AM  ? 2 : 0;
            spinnerModulation.setSelection(pos);
            startListenMode(e.freqHz);
            return;
        }

        long minHz = Long.MAX_VALUE, maxHz = Long.MIN_VALUE;
        for (PlaylistEntry e : enabled) {
            if (e.freqHz < minHz) minHz = e.freqHz;
            if (e.freqHz > maxHz) maxHz = e.freqHz;
        }
        long span = maxHz - minHz;
        long maxCapture = (long)(ListenEngine.LISTEN_SAMPLE_RATE * 0.9);
        if (span > maxCapture) {
            Toast.makeText(this, String.format(
                    "Checked frequencies span %.0f kHz — max simultaneous capture is %.0f kHz.\n"
                    + "Uncheck some or keep them within %.0f kHz of each other.",
                    span / 1000.0, maxCapture / 1000.0, maxCapture / 1000.0),
                    Toast.LENGTH_LONG).show();
            return;
        }

        long centerHz = (minHz + maxHz) / 2;
        long[] freqs = new long[enabled.size()];
        for (int i = 0; i < enabled.size(); i++) freqs[i] = enabled.get(i).freqHz;

        // Pick demodType: AM if any entry is AM (e.g. CB mixed with others),
        // else WFM if any, else NFM
        ListenEngine.DemodType demodType = ListenEngine.DemodType.NFM;
        for (PlaylistEntry e : enabled) {
            if (e.demodType == ListenEngine.DemodType.AM)  { demodType = ListenEngine.DemodType.AM;  break; }
            if (e.demodType == ListenEngine.DemodType.WFM) { demodType = ListenEngine.DemodType.WFM; }
        }

        startWidebandMonitor(freqs, centerHz, demodType, "Playlist");
    }

    // ── ListenEngine.Listener ─────────────────────────────────────────────────

    @Override
    public void onSignalLevel(float rms) {
        int bars = Math.min(8, (int)(rms * 40));
        String bar = "▁▂▃▄▅▆▇█".substring(0, bars) + "░░░░░░░░".substring(bars);
        tvListenSignal.setText("Sig: " + bar + "  " + String.format("%.3f", rms));
    }

    @Override
    public void onScanFrequency(long hz) {
        tvListenStatus.setTextColor(0xFF446644);
        tvListenStatus.setText("● Hunting " + String.format("%.4f MHz", hz / 1e6));
        tvScanStatus.setText("Listening across all channels…");
    }

    @Override
    public void onSignalFound(long hz) {
        tvListenStatus.setTextColor(0xFF00FF44);
        tvListenStatus.setText("● SIGNAL  " + String.format("%.4f MHz", hz / 1e6));
        tvScanStatus.setText("Locked — holding 5 s…");
    }

    @Override
    public void onSignalLost() {
        tvListenStatus.setTextColor(0xFF446644);
        tvListenStatus.setText("● Hunting…");
        tvScanStatus.setText("Resumed");
    }

    private void updateFrequencyDisplay() {
        tvCenterFreq.setText(String.format("%.3f MHz", currentCenterFreq / 1e6));
        etControlsFreq.setText(String.format("%.6f", currentCenterFreq / 1e6));
    }

    // ── DspEngine.StopListener ────────────────────────────────────────────

    private final Runnable retryDspRunnable = this::retryDsp;

    @Override
    public void onDspError(String reason) {
        dspRetryCount++;
        ConsoleLogger.get().log("DSP error: " + reason + " (attempt " + dspRetryCount + ")");

        if (dspRetryCount <= MAX_DSP_RETRIES) {
            String msg = "DSP error — retrying " + dspRetryCount + "/" + MAX_DSP_RETRIES + "…";
            tvStatus.setText(msg);
            uiHandler.postDelayed(retryDspRunnable, 1500);
        } else {
            tvStatus.setText("DSP failed — restart required");
            showDspFailureDialog();
        }
    }

    private void retryDsp() {
        if (!deviceOpen) return;
        ConsoleLogger.get().log("Restarting DSP (attempt " + dspRetryCount + ")…");
        stopDsp();
        startDsp();
    }

    private void showDspFailureDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Radio Engine Failed")
                .setMessage("The DSP engine failed to recover after " + MAX_DSP_RETRIES
                        + " attempts.\n\nUnplug the dongle and restart the app.")
                .setPositiveButton("Restart App", (d, w) -> restartApp())
                .setNegativeButton("Dismiss", null)
                .setCancelable(false)
                .show();
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ── Explore / frequency tuning ────────────────────────────────────────

    private void exploreTune(long hz) {
        hz = Math.max(FREQ_MIN_HZ, Math.min(FREQ_MAX_HZ, hz));
        currentCenterFreq = hz;
        updateBandButtons();
        updateFrequencyDisplay();

        double[] channels = GMRSChannels.getVisibleChannels(hz);
        waterfallView.setChannels(channels, hz, GMRSChannels.SAMPLE_RATE);
        tvActiveChannel.setText(GMRSChannels.getBandLabel(hz));

        if (deviceOpen && dspEngine != null) dspEngine.retune(hz);
        ConsoleLogger.get().log("Tune → " + String.format("%.4f MHz", hz / 1e6));
    }

    // ── Navigation: step and scan ─────────────────────────────────────────────

    private long getStepHz() {
        switch (spinnerSwipeStep.getSelectedItemPosition()) {
            case 0:  return     10_000L;
            case 1:  return     25_000L;
            case 2:  return    100_000L;
            case 3:  return    500_000L;
            case 4:  return  1_000_000L;
            case 5:  return  5_000_000L;
            default: return    100_000L;
        }
    }

    private long getScanIntervalMs() {
        switch (spinnerScanSpeed.getSelectedItemPosition()) {
            case 0:  return  250L;
            case 1:  return  500L;
            case 2:  return 1000L;
            case 3:  return 2000L;
            case 4:  return 5000L;
            default: return  500L;
        }
    }

    private void stepFreq(int direction) {
        long newHz = Math.max(FREQ_MIN_HZ, Math.min(FREQ_MAX_HZ,
                currentCenterFreq + direction * getStepHz()));
        exploreTune(newHz);
    }

    private void startScan(int direction) {
        stopScan();
        if (!deviceOpen) { Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show(); return; }
        isScanning    = true;
        scanDirection = direction;
        btnNavScanDown.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                direction < 0 ? 0xFF225500 : 0xFF1A1100));
        btnNavScanUp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                direction > 0 ? 0xFF225500 : 0xFF1A1100));
        btnNavStop.setVisibility(View.VISIBLE);
        scanHandler.postDelayed(scanRunnable, getScanIntervalMs());
        ConsoleLogger.get().log("Scan " + (direction > 0 ? "up" : "down") + " step=" +
                String.format("%.0f kHz", getStepHz() / 1000.0));
    }

    private void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        scanHandler.removeCallbacks(scanRunnable);
        btnNavScanDown.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1A1100));
        btnNavScanUp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1A1100));
        btnNavStop.setVisibility(View.INVISIBLE);
    }

    private void doScanStep() {
        if (!isScanning || !deviceOpen) return;
        // If a signal is currently present, extend dwell without stepping
        if (waterfallView.isSignalPresent()) {
            scanHandler.postDelayed(scanRunnable, Math.max(getScanIntervalMs(), 2_000));
            return;
        }
        stepFreq(scanDirection);
        scanHandler.postDelayed(scanRunnable, getScanIntervalMs());
    }

    private void updateSquelchLabel(int progress) {
        if (progress == 0) {
            tvSquelchLabel.setText("Squelch: OFF (always open)");
        } else {
            tvSquelchLabel.setText("Squelch: " + progress + "/20  (start at 5, raise to reduce static)");
        }
    }

    private void setWaterfallWeight(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
        waterfallView.setLayoutParams(p);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private String extractTuner(String json) {
        if (json == null) return "Unknown";
        int i = json.indexOf("\"tuner\":\"");
        if (i < 0) return "Unknown";
        int start = i + 9, end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "Unknown";
    }
}
