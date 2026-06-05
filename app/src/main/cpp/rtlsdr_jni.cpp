/**
 * rtlsdr_jni.cpp  — JNI bridge between Android Java and librtlsdr.
 *
 * rtlsdr_open_android() is compiled into librtlsdr.c itself (appended at
 * build time) so it has access to all private static helpers.  We just call
 * it from here via the public header declaration.
 *
 * DSP pipeline (windowing → FFT → accumulate → dBFS) runs in native code
 * and is called per-frame from DspEngine's background thread.
 */

#include <jni.h>
#include <android/log.h>

#include <rtl-sdr.h>       // includes rtlsdr_open_android() declaration
#include <libusb.h>

#include <cmath>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <mutex>

#include "kissfft/kiss_fft.h"

#define LOG_TAG "rtlsdr_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Global device state (single-device model; access guarded by g_dev_mutex)
// ─────────────────────────────────────────────────────────────────────────────
static rtlsdr_dev_t *g_dev = nullptr;
static std::mutex    g_dev_mutex;

// ─────────────────────────────────────────────────────────────────────────────
// DSP state: KissFFT instance + averaging accumulator
// ─────────────────────────────────────────────────────────────────────────────
static constexpr int FFT_SIZE   = 1024;
static constexpr int AVG_FRAMES = 4;

static kiss_fft_cfg g_fft_cfg   = nullptr;
static kiss_fft_cpx g_fin [FFT_SIZE];
static kiss_fft_cpx g_fout[FFT_SIZE];
static float        g_accum[FFT_SIZE];
static int          g_frame_count = 0;
static std::mutex   g_dsp_mutex;

// ── NFM demodulator state ─────────────────────────────────────────────────
static float g_nfm_prev_i    = 0.0f;
static float g_nfm_prev_q    = 0.0f;
static float g_nfm_deemph    = 0.0f;
// 3-point causal median filter state (previous 2 discriminator samples).
// Eliminates single-sample ±π click spikes from phase-wrap / amplitude nulls.
static float g_nfm_med_p1    = 0.0f;
static float g_nfm_med_p2    = 0.0f;
static float g_last_sig_rms  = 0.0f;   // IQ RMS for display
static float g_fm_rms_smooth = 0.0f;   // IIR-smoothed FM discriminator RMS (wideband monitor)
static float g_noise_floor   = 0.0f;   // adaptive IQ noise floor (scan mode)
static float g_am_dc         = 0.0f;   // AM envelope DC removal state
static float g_filt_floor    = 0.0f;   // adaptive noise floor of FIR-filtered amplitude
static int   g_sq_hang       = 0;      // squelch hangtime countdown (output samples)
// De-emphasis: τ=75µs, fs=48kHz → α=exp(-1/(48000·75e-6)) ≈ 0.758
static constexpr float DEEMPH_A    = 0.758f;
// atan2 discriminator output (radians): ±5 kHz dev at 48 kHz → peak ≈ 0.654 rad
// Scale to ±5 kHz → ~full scale (≈32700); police NFM ±2.5 kHz → -6 dBFS.
static constexpr float AUDIO_SCALE     = 50000.0f;
// WFM: discriminator at 240 kHz, peak phase step = 2π×75k/240k ≈ 1.96 rad (no atan2 wrap).
// Scale maps typical 65% modulation (1.27 rad peak) to ~80% of full scale.
static constexpr float AUDIO_SCALE_WFM = 20000.0f;
static constexpr int   HANG_SAMP   = 48000 / 4; // 250 ms hang = 12000 samples
static constexpr int   NFM_DECIM   = 20;   // 960 kHz → 48 kHz (4×5)
static constexpr int   DECIM1      = 4;    // stage-1 box filter
static constexpr int   DECIM2      = 5;    // stage-2 FIR
static constexpr int   LPF_TAPS    = 551;

// ── Voice-band audio filters (applied after demodulation at 48 kHz) ──────────
// sdrtrunk applies equivalent filtering:
//   • Polyphase resampler to 8 kHz → implicit 4 kHz LPF cuts FM noise above voice band
//   • Remez FIR HPF (stop=200 Hz, pass=300 Hz) in AudioModule removes CTCSS tones
//
// 2-pole Butterworth LPF at fc=3500 Hz, fs=48000 Hz (Audio EQ Cookbook).
// Eliminates FM discriminator noise and AM envelope noise above the voice band.
// At 7 kHz: −12 dB; at 15 kHz: −24 dB combined with 75 µs de-emphasis.
static constexpr float AFILT_LPF_B0 =  0.03927f;
static constexpr float AFILT_LPF_B1 =  0.07853f;
static constexpr float AFILT_LPF_B2 =  0.03927f;
static constexpr float AFILT_LPF_A1 = -1.36647f;
static constexpr float AFILT_LPF_A2 =  0.52394f;
//
// 2-pole Butterworth HPF at fc=250 Hz, fs=48000 Hz.
// Removes CTCSS sub-audio tones (67–254 Hz), DC, and 60 Hz hum.
static constexpr float AFILT_HPF_B0 =  0.97714f;
static constexpr float AFILT_HPF_B1 = -1.95428f;
static constexpr float AFILT_HPF_B2 =  0.97714f;
static constexpr float AFILT_HPF_A1 = -1.95366f;
static constexpr float AFILT_HPF_A2 =  0.95479f;

static float g_lpf_s1 = 0.0f, g_lpf_s2 = 0.0f;  // LPF biquad state
static float g_hpf_s1 = 0.0f, g_hpf_s2 = 0.0f;  // HPF biquad state

// 551-tap Kaiser-windowed channel filter for NFM/AM — matches sdrtrunk's NBFMDecoder spec:
//   pass band : 0 – 10 kHz  (80% of 12.5 kHz NFM channel, sdrtrunk: passBandStop = bandwidth*0.8)
//   stop band : 12.5 kHz    (channel edge,                 sdrtrunk: stopBandStart = bandwidth)
//   attenuation: 90 dB      (Kaiser β=8.96,                sdrtrunk: stopBandRipple = 0.005)
//   fs input  : 240 kHz     (after 4:1 box filter from 960 kHz)
//   decimation: 5:1 → 48 kHz audio output
// Adjacent-channel signals at ±12.5 kHz are attenuated by 90 dB before the discriminator.
// This is the primary fix for static — the old 63-tap filter passed everything up to 15 kHz,
// letting adjacent-channel interference directly into the FM discriminator.
static const float NFM_LPF[LPF_TAPS] = {
    -0.0000006978f, -0.0000010565f, -0.0000013917f, -0.0000016515f, -0.0000017827f,
    -0.0000017369f, -0.0000014785f, -0.0000009905f, -0.0000002806f, +0.0000006154f,
    +0.0000016333f, +0.0000026828f, +0.0000036538f, +0.0000044259f, +0.0000048799f,
    +0.0000049119f, +0.0000044470f, +0.0000034517f, +0.0000019443f, -0.0000000000f,
    -0.0000022486f, -0.0000046175f, -0.0000068836f, -0.0000088031f, -0.0000101335f,
    -0.0000106595f, -0.0000102188f, -0.0000087254f, -0.0000061882f, -0.0000027217f,
    +0.0000014519f, +0.0000060128f, +0.0000105639f, +0.0000146610f, +0.0000178523f,
    +0.0000197223f, +0.0000199369f, +0.0000182856f, +0.0000147164f, +0.0000093575f,
    +0.0000025247f, -0.0000052899f, -0.0000134470f, -0.0000212073f, -0.0000277928f,
    -0.0000324585f, -0.0000345674f, -0.0000336626f, -0.0000295295f, -0.0000222394f,
    -0.0000121703f, -0.0000000000f, +0.0000133307f, +0.0000266831f, +0.0000388108f,
    +0.0000484683f, +0.0000545295f, +0.0000561051f, +0.0000526474f, +0.0000440326f,
    +0.0000306087f, +0.0000132036f, -0.0000069118f, -0.0000281060f, -0.0000485098f,
    -0.0000661717f, -0.0000792341f, -0.0000861150f, -0.0000856778f, -0.0000773731f,
    -0.0000613367f, -0.0000384309f, -0.0000102208f, +0.0000211168f, +0.0000529489f,
    +0.0000823954f, +0.0001065783f, +0.0001228878f, +0.0001292444f, +0.0001243299f,
    +0.0001077655f, +0.0000802140f, +0.0000433948f, -0.0000000000f, -0.0000464842f,
    -0.0000920428f, -0.0001324639f, -0.0001637130f, -0.0001823152f, -0.0001857128f,
    -0.0001725621f, -0.0001429383f, -0.0000984238f, -0.0000420633f, +0.0000218186f,
    +0.0000879285f, +0.0001504253f, +0.0002034183f, +0.0002415016f, +0.0002602786f,
    +0.0002568263f, +0.0002300548f, +0.0001809210f, +0.0001124689f, +0.0000296807f,
    -0.0000608566f, -0.0001514528f, -0.0002339461f, -0.0003004161f, -0.0003439166f,
    -0.0003591643f, -0.0003431158f, -0.0002953746f, -0.0002183819f, -0.0001173601f,
    +0.0000000000f, +0.0001240930f, +0.0002441589f, +0.0003491900f, +0.0004289118f,
    +0.0004747523f, +0.0004807102f, +0.0004440394f, +0.0003656757f, +0.0002503544f,
    +0.0001063900f, -0.0000548786f, -0.0002199470f, -0.0003742446f, -0.0005033898f,
    -0.0005944937f, -0.0006373983f, -0.0006257334f, -0.0005576867f, -0.0004364038f,
    -0.0002699624f, -0.0000709002f, +0.0001446814f, +0.0003583808f, +0.0005510299f,
    +0.0007043747f, +0.0008027570f, +0.0008346482f, +0.0007938872f, +0.0006804989f,
    +0.0005009974f, +0.0002681215f, -0.0000000000f, -0.0002812074f, -0.0005510961f,
    -0.0007850898f, -0.0009606291f, -0.0010592831f, -0.0010685938f, -0.0009834723f,
    -0.0008070028f, -0.0005505532f, -0.0002331509f, +0.0001198557f, +0.0004787612f,
    +0.0008119498f, +0.0010886232f, +0.0012815836f, +0.0013698220f, +0.0013406739f,
    +0.0011913294f, +0.0009295339f, +0.0005733798f, +0.0001501678f, -0.0003056042f,
    -0.0007549827f, -0.0011578202f, -0.0014762896f, -0.0016783493f, -0.0017408479f,
    -0.0016519783f, -0.0014128320f, -0.0010378765f, -0.0005542669f, +0.0000000000f,
    +0.0005789746f, +0.0011324769f, +0.0016103569f, +0.0019669405f, +0.0021652733f,
    +0.0021807782f, +0.0020039767f, +0.0016419933f, +0.0011186549f, +0.0004731181f,
    -0.0002429198f, -0.0009692412f, -0.0016420562f, -0.0021994833f, -0.0025871006f,
    -0.0027630818f, -0.0027024451f, -0.0024000016f, -0.0018716891f, -0.0011541005f,
    -0.0003021731f, +0.0006148406f, +0.0015188394f, +0.0023293594f, +0.0029705560f,
    +0.0033780887f, +0.0035053013f, +0.0033281252f, +0.0028482183f, +0.0020939890f,
    +0.0011193195f, -0.0000000000f, -0.0011719180f, -0.0022954487f, -0.0032691160f,
    -0.0039998280f, -0.0044114303f, -0.0044521765f, -0.0041004206f, -0.0033679517f,
    -0.0023005768f, -0.0009757700f, +0.0005025428f, +0.0020117474f, +0.0034203044f,
    +0.0045987629f, +0.0054311385f, +0.0058256928f, +0.0057241618f, +0.0051085656f,
    +0.0040048989f, +0.0024832313f, +0.0006540307f, -0.0013391685f, -0.0033303172f,
    -0.0051438930f, -0.0066094633f, -0.0075766200f, -0.0079290563f, -0.0075965601f,
    -0.0065638158f, -0.0048751101f, -0.0026343308f, +0.0000000000f, +0.0028245268f,
    +0.0056051660f, +0.0080946171f, +0.0100520624f, +0.0112636077f, +0.0115618873f,
    +0.0108432484f, +0.0090810437f, +0.0063337969f, +0.0027473429f, -0.0014495328f,
    -0.0059559574f, -0.0104158484f, -0.0144398436f, -0.0176309093f, -0.0196119238f,
    -0.0200533285f, -0.0186988634f, -0.0153874771f, -0.0100697178f, -0.0028172559f,
    +0.0061753582f, +0.0165970686f, +0.0280346202f, +0.0399940352f, +0.0519279760f,
    +0.0632672765f, +0.0734546038f, +0.0819780341f, +0.0884023043f, +0.0923956358f,
    +0.0937503158f, +0.0923956358f, +0.0884023043f, +0.0819780341f, +0.0734546038f,
    +0.0632672765f, +0.0519279760f, +0.0399940352f, +0.0280346202f, +0.0165970686f,
    +0.0061753582f, -0.0028172559f, -0.0100697178f, -0.0153874771f, -0.0186988634f,
    -0.0200533285f, -0.0196119238f, -0.0176309093f, -0.0144398436f, -0.0104158484f,
    -0.0059559574f, -0.0014495328f, +0.0027473429f, +0.0063337969f, +0.0090810437f,
    +0.0108432484f, +0.0115618873f, +0.0112636077f, +0.0100520624f, +0.0080946171f,
    +0.0056051660f, +0.0028245268f, +0.0000000000f, -0.0026343308f, -0.0048751101f,
    -0.0065638158f, -0.0075965601f, -0.0079290563f, -0.0075766200f, -0.0066094633f,
    -0.0051438930f, -0.0033303172f, -0.0013391685f, +0.0006540307f, +0.0024832313f,
    +0.0040048989f, +0.0051085656f, +0.0057241618f, +0.0058256928f, +0.0054311385f,
    +0.0045987629f, +0.0034203044f, +0.0020117474f, +0.0005025428f, -0.0009757700f,
    -0.0023005768f, -0.0033679517f, -0.0041004206f, -0.0044521765f, -0.0044114303f,
    -0.0039998280f, -0.0032691160f, -0.0022954487f, -0.0011719180f, -0.0000000000f,
    +0.0011193195f, +0.0020939890f, +0.0028482183f, +0.0033281252f, +0.0035053013f,
    +0.0033780887f, +0.0029705560f, +0.0023293594f, +0.0015188394f, +0.0006148406f,
    -0.0003021731f, -0.0011541005f, -0.0018716891f, -0.0024000016f, -0.0027024451f,
    -0.0027630818f, -0.0025871006f, -0.0021994833f, -0.0016420562f, -0.0009692412f,
    -0.0002429198f, +0.0004731181f, +0.0011186549f, +0.0016419933f, +0.0020039767f,
    +0.0021807782f, +0.0021652733f, +0.0019669405f, +0.0016103569f, +0.0011324769f,
    +0.0005789746f, +0.0000000000f, -0.0005542669f, -0.0010378765f, -0.0014128320f,
    -0.0016519783f, -0.0017408479f, -0.0016783493f, -0.0014762896f, -0.0011578202f,
    -0.0007549827f, -0.0003056042f, +0.0001501678f, +0.0005733798f, +0.0009295339f,
    +0.0011913294f, +0.0013406739f, +0.0013698220f, +0.0012815836f, +0.0010886232f,
    +0.0008119498f, +0.0004787612f, +0.0001198557f, -0.0002331509f, -0.0005505532f,
    -0.0008070028f, -0.0009834723f, -0.0010685938f, -0.0010592831f, -0.0009606291f,
    -0.0007850898f, -0.0005510961f, -0.0002812074f, -0.0000000000f, +0.0002681215f,
    +0.0005009974f, +0.0006804989f, +0.0007938872f, +0.0008346482f, +0.0008027570f,
    +0.0007043747f, +0.0005510299f, +0.0003583808f, +0.0001446814f, -0.0000709002f,
    -0.0002699624f, -0.0004364038f, -0.0005576867f, -0.0006257334f, -0.0006373983f,
    -0.0005944937f, -0.0005033898f, -0.0003742446f, -0.0002199470f, -0.0000548786f,
    +0.0001063900f, +0.0002503544f, +0.0003656757f, +0.0004440394f, +0.0004807102f,
    +0.0004747523f, +0.0004289118f, +0.0003491900f, +0.0002441589f, +0.0001240930f,
    +0.0000000000f, -0.0001173601f, -0.0002183819f, -0.0002953746f, -0.0003431158f,
    -0.0003591643f, -0.0003439166f, -0.0003004161f, -0.0002339461f, -0.0001514528f,
    -0.0000608566f, +0.0000296807f, +0.0001124689f, +0.0001809210f, +0.0002300548f,
    +0.0002568263f, +0.0002602786f, +0.0002415016f, +0.0002034183f, +0.0001504253f,
    +0.0000879285f, +0.0000218186f, -0.0000420633f, -0.0000984238f, -0.0001429383f,
    -0.0001725621f, -0.0001857128f, -0.0001823152f, -0.0001637130f, -0.0001324639f,
    -0.0000920428f, -0.0000464842f, -0.0000000000f, +0.0000433948f, +0.0000802140f,
    +0.0001077655f, +0.0001243299f, +0.0001292444f, +0.0001228878f, +0.0001065783f,
    +0.0000823954f, +0.0000529489f, +0.0000211168f, -0.0000102208f, -0.0000384309f,
    -0.0000613367f, -0.0000773731f, -0.0000856778f, -0.0000861150f, -0.0000792341f,
    -0.0000661717f, -0.0000485098f, -0.0000281060f, -0.0000069118f, +0.0000132036f,
    +0.0000306087f, +0.0000440326f, +0.0000526474f, +0.0000561051f, +0.0000545295f,
    +0.0000484683f, +0.0000388108f, +0.0000266831f, +0.0000133307f, -0.0000000000f,
    -0.0000121703f, -0.0000222394f, -0.0000295295f, -0.0000336626f, -0.0000345674f,
    -0.0000324585f, -0.0000277928f, -0.0000212073f, -0.0000134470f, -0.0000052899f,
    +0.0000025247f, +0.0000093575f, +0.0000147164f, +0.0000182856f, +0.0000199369f,
    +0.0000197223f, +0.0000178523f, +0.0000146610f, +0.0000105639f, +0.0000060128f,
    +0.0000014519f, -0.0000027217f, -0.0000061882f, -0.0000087254f, -0.0000102188f,
    -0.0000106595f, -0.0000101335f, -0.0000088031f, -0.0000068836f, -0.0000046175f,
    -0.0000022486f, -0.0000000000f, +0.0000019443f, +0.0000034517f, +0.0000044470f,
    +0.0000049119f, +0.0000048799f, +0.0000044259f, +0.0000036538f, +0.0000026828f,
    +0.0000016333f, +0.0000006154f, -0.0000002806f, -0.0000009905f, -0.0000014785f,
    -0.0000017369f, -0.0000017827f, -0.0000016515f, -0.0000013917f, -0.0000010565f,
    -0.0000006978f
};

// WFM uses a separate 63-tap LPF tuned for audio bandwidth (fc=15 kHz at 240 kHz).
// FM broadcast audio extends to 15 kHz; channel selectivity is not needed for WFM
// because broadcast stations are 200 kHz apart.
static constexpr int   WFM_LPF_TAPS = 63;
static const float WFM_LPF[WFM_LPF_TAPS] = {
    -0.000314353f, -0.000617918f, -0.000906742f, -0.001149280f, -0.001277390f,
    -0.001192924f, -0.000791528f,  0.000000000f,  0.001181444f,  0.002643976f,
     0.004154602f,  0.005370185f,  0.005885308f,  0.005310614f,  0.003370201f,
    -0.000000000f, -0.004574432f, -0.009801499f, -0.014823897f, -0.018561722f,
    -0.019852573f, -0.017630972f, -0.011119652f,  0.000000000f,  0.015470614f,
     0.034420263f,  0.055416400f,  0.076610871f,  0.095958698f,  0.111479842f,
     0.121524430f,  0.125000000f,  0.121524430f,  0.111479842f,  0.095958698f,
     0.076610871f,  0.055416400f,  0.034420263f,  0.015470614f,  0.000000000f,
    -0.011119652f, -0.017630972f, -0.019852573f, -0.018561722f, -0.014823897f,
    -0.009801499f, -0.004574432f, -0.000000000f,  0.003370201f,  0.005310614f,
     0.005885308f,  0.005370185f,  0.004154602f,  0.002643976f,  0.001181444f,
     0.000000000f, -0.000791528f, -0.001192924f, -0.001277390f, -0.001149280f,
    -0.000906742f, -0.000617918f, -0.000314353f,
};

// Circular history buffer: holds the last LPF_TAPS complex samples at 240 kHz.
static float g_lpf_i[LPF_TAPS];
static float g_lpf_q[LPF_TAPS];
static int   g_lpf_idx = 0;   // next write position

// FM discriminator RMS scratch buffer (nOut = 960 samples per 20 ms chunk).
static float s_fm_buf[960];

// ── WFM demodulator state ─────────────────────────────────────────────────────
// Discriminator runs at 240 kHz (4:1 box filter only; no FIR IQ decimation).
// At 240 kHz, max FM broadcast phase step = 2π×75k/240k ≈ 1.96 rad — safely < π, no wrapping.
static float g_wfm_prev_i   = 0.0f;
static float g_wfm_prev_q   = 0.0f;
static float g_wfm_deemph   = 0.0f;
static float g_wfm_hist[WFM_LPF_TAPS];  // real FM audio history for FIR decimation at 240 kHz
static int   g_wfm_hist_idx = 0;

// Update IQ noise floor for scan mode: fast down, very slow up.
static inline void update_noise_floor(float rms) {
    if (g_noise_floor <= 0.0f) { g_noise_floor = rms; return; }
    if (rms < g_noise_floor)
        g_noise_floor = g_noise_floor * 0.90f + rms * 0.10f;
    else
        g_noise_floor = g_noise_floor * 0.9997f + rms * 0.0003f;
}

// Sorting-network median of 3 values — removes single-sample impulse noise.
static inline float median3(float a, float b, float c) {
    if (a > b) { float t = a; a = b; b = t; }
    if (b > c) { float t = b; b = c; c = t; }
    if (a > b) { float t = a; a = b; b = t; }
    return b;
}

// Direct-form II biquad; processes buf[0..n-1] in-place.
static inline void biquad_df2(float *buf, int n,
                               float b0, float b1, float b2,
                               float a1, float a2,
                               float *s1, float *s2) {
    for (int i = 0; i < n; i++) {
        float w = buf[i] - a1 * (*s1) - a2 * (*s2);
        buf[i]  = b0 * w + b1 * (*s1) + b2 * (*s2);
        *s2 = *s1;
        *s1 = w;
    }
}

// Apply voice-band filters: LPF 3.5 kHz then HPF 250 Hz.
static inline void audio_filter(float *buf, int n) {
    biquad_df2(buf, n, AFILT_LPF_B0, AFILT_LPF_B1, AFILT_LPF_B2,
               AFILT_LPF_A1, AFILT_LPF_A2, &g_lpf_s1, &g_lpf_s2);
    biquad_df2(buf, n, AFILT_HPF_B0, AFILT_HPF_B1, AFILT_HPF_B2,
               AFILT_HPF_A1, AFILT_HPF_A2, &g_hpf_s1, &g_hpf_s2);
}

static void dsp_init_locked() {
    if (!g_fft_cfg)
        g_fft_cfg = kiss_fft_alloc(FFT_SIZE, 0, nullptr, nullptr);
    memset(g_accum, 0, sizeof(g_accum));
    g_frame_count = 0;
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI function table
// ─────────────────────────────────────────────────────────────────────────────
extern "C" {

// ---------------------------------------------------------------------------
// Returns null on success; a human-readable error string on failure.
// This lets Java display the exact failure reason rather than "init failed".
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeOpen(JNIEnv *env, jobject, jint fileDescriptor)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (g_dev) {
        LOGW("nativeOpen: closing stale device before re-open");
        rtlsdr_close(g_dev);
        g_dev = nullptr;
    }

    int fd = (int)fileDescriptor;
    LOGI("nativeOpen: fd=%d", fd);

    if (fd < 0) {
        const char *msg = "invalid fd (-1): UsbDeviceConnection.getFileDescriptor() failed";
        LOGE("%s", msg);
        return env->NewStringUTF(msg);
    }

    char errmsg[256] = "rtlsdr_open_android returned unknown error";
    int r = rtlsdr_open_android(&g_dev, fd, errmsg, sizeof(errmsg));
    if (r < 0) {
        LOGE("nativeOpen FAILED (code %d): %s", r, errmsg);
        g_dev = nullptr;
        return env->NewStringUTF(errmsg); // non-null signals failure to Java
    }

    // Enable offset tuning: shifts the IF away from DC on E4000 tuners.
    // On R820T the call succeeds but has no effect; it never breaks the device.
    rtlsdr_set_offset_tuning(g_dev, 1);

    {
        std::lock_guard<std::mutex> dsp_lock(g_dsp_mutex);
        dsp_init_locked();
    }
    LOGI("nativeOpen: SUCCESS — device open");
    return nullptr; // null = success
}

// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeClose(JNIEnv *, jobject)
{
    {
        std::lock_guard<std::mutex> lock(g_dev_mutex);
        if (g_dev) {
            rtlsdr_close(g_dev);
            g_dev = nullptr;
            LOGI("nativeClose: device closed");
        }
    }
    {
        std::lock_guard<std::mutex> dsp_lock(g_dsp_mutex);
        if (g_fft_cfg) {
            kiss_fft_free(g_fft_cfg);
            g_fft_cfg = nullptr;
        }
    }
}

// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeSetFrequency(JNIEnv *, jobject, jlong freqHz)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (!g_dev) return JNI_FALSE;
    int r = rtlsdr_set_center_freq(g_dev, (uint32_t)freqHz);
    if (r < 0) { LOGE("set_center_freq(%lld) failed: %d", (long long)freqHz, r); return JNI_FALSE; }
    LOGI("Frequency: %lld Hz", (long long)freqHz);
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeSetSampleRate(JNIEnv *, jobject, jint rateHz)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (!g_dev) return JNI_FALSE;
    int r = rtlsdr_set_sample_rate(g_dev, (uint32_t)rateHz);
    if (r < 0) { LOGE("set_sample_rate(%d) failed: %d", rateHz, r); return JNI_FALSE; }
    LOGI("Sample rate: %d Hz", rateHz);
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeSetGain(JNIEnv *, jobject, jint gainTenthsDb)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (!g_dev) return JNI_FALSE;
    rtlsdr_set_tuner_gain_mode(g_dev, 1); // manual
    int r = rtlsdr_set_tuner_gain(g_dev, gainTenthsDb);
    if (r < 0) { LOGE("set_tuner_gain(%d) failed: %d", gainTenthsDb, r); return JNI_FALSE; }
    LOGI("Gain: %d (%.1f dB)", gainTenthsDb, gainTenthsDb / 10.0);
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeSetAgc(JNIEnv *, jobject, jboolean enable)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (!g_dev) return JNI_FALSE;
    int en = enable ? 1 : 0;
    rtlsdr_set_tuner_gain_mode(g_dev, en ? 0 : 1); // 0=AGC, 1=manual
    rtlsdr_set_agc_mode(g_dev, en);
    LOGI("AGC %s", en ? "on" : "off");
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeReadSamples(JNIEnv *env, jobject,
                                                     jbyteArray buffer, jint length)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (!g_dev) return -1;

    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -1;

    int n_read = 0;
    int r = rtlsdr_read_sync(g_dev, (unsigned char *)buf, length, &n_read);
    env->ReleaseByteArrayElements(buffer, buf, 0);

    if (r < 0) { LOGE("rtlsdr_read_sync error: %d", r); return -1; }
    return n_read;
}

// ---------------------------------------------------------------------------
// Process one 1024-sample FFT frame. Returns true every AVG_FRAMES calls
// when an averaged dBFS result is ready in outDbfs[1024].
// Bins are FFT-shifted so DC is at index 512 (display centre).
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeProcessSamples(JNIEnv *env, jobject,
                                                        jbyteArray iqData,
                                                        jint offset,
                                                        jfloatArray outDbfs)
{
    std::lock_guard<std::mutex> dsp_lock(g_dsp_mutex);
    if (!g_fft_cfg) return JNI_FALSE;
    if (env->GetArrayLength(outDbfs) < FFT_SIZE) return JNI_FALSE;

    jbyte *iq = env->GetByteArrayElements(iqData, nullptr);
    if (!iq) return JNI_FALSE;

    const uint8_t *s = reinterpret_cast<const uint8_t *>(iq) + offset;

    // Compute per-frame DC offset and subtract it before the FFT.
    float sum_i = 0, sum_q = 0;
    for (int i = 0; i < FFT_SIZE; i++) {
        sum_i += (float)s[i*2]   - 127.5f;
        sum_q += (float)s[i*2+1] - 127.5f;
    }
    float mean_i = sum_i / FFT_SIZE;
    float mean_q = sum_q / FFT_SIZE;

    // Convert uint8 IQ → DC-free complex float with Hann window.
    for (int i = 0; i < FFT_SIZE; i++) {
        float hann = 0.5f * (1.0f - cosf(2.0f * (float)M_PI * i / (FFT_SIZE - 1)));
        g_fin[i].r = ((float)s[i*2]   - 127.5f - mean_i) / 127.5f * hann;
        g_fin[i].i = ((float)s[i*2+1] - 127.5f - mean_q) / 127.5f * hann;
    }
    env->ReleaseByteArrayElements(iqData, iq, JNI_ABORT);

    kiss_fft(g_fft_cfg, g_fin, g_fout);

    // Accumulate power with FFT-shift (DC → display centre).
    for (int i = 0; i < FFT_SIZE; i++) {
        float re = g_fout[i].r, im = g_fout[i].i;
        g_accum[(i + FFT_SIZE / 2) % FFT_SIZE] += re * re + im * im;
    }
    g_frame_count++;

    if (g_frame_count < AVG_FRAMES) return JNI_FALSE;

    // Average → dBFS
    jfloat *out = env->GetFloatArrayElements(outDbfs, nullptr);
    if (!out) {
        memset(g_accum, 0, sizeof(g_accum));
        g_frame_count = 0;
        return JNI_FALSE;
    }
    // Normalize to 0 dBFS for a full-scale Hann-windowed CW input.
    // Maximum coherent bin power = (FFT_SIZE/2)^2 = 512^2 = 262144.
    // This places the RTL-SDR noise floor at roughly -45 to -55 dBFS.
    static constexpr float FS_REF = (float)(FFT_SIZE / 2) * (float)(FFT_SIZE / 2);
    const float inv = 1.0f / (AVG_FRAMES * FS_REF);
    for (int i = 0; i < FFT_SIZE; i++)
        out[i] = 10.0f * log10f(g_accum[i] * inv + 1e-12f);

    // DC spike suppression: bin 512 is the exact center frequency; the RTL-SDR
    // LO leakage creates an artifact there even after time-domain mean removal.
    // Interpolate linearly over the contaminated window (bins 510–513) from
    // clean neighbors 509 and 514.
    {
        float lo = out[509], hi = out[514];
        for (int i = 510; i <= 513; i++) {
            float t = (i - 509.0f) / (514.0f - 509.0f);
            out[i] = lo + t * (hi - lo);
        }
    }

    env->ReleaseFloatArrayElements(outDbfs, out, 0);
    memset(g_accum, 0, sizeof(g_accum));
    g_frame_count = 0;
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeGetDeviceInfo(JNIEnv *env, jobject)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (!g_dev) return env->NewStringUTF("{\"error\":\"no device\"}");

    const char *tuner_name;
    switch (rtlsdr_get_tuner_type(g_dev)) {
        case RTLSDR_TUNER_E4000:  tuner_name = "E4000";  break;
        case RTLSDR_TUNER_FC0012: tuner_name = "FC0012"; break;
        case RTLSDR_TUNER_FC0013: tuner_name = "FC0013"; break;
        case RTLSDR_TUNER_FC2580: tuner_name = "FC2580"; break;
        case RTLSDR_TUNER_R820T:  tuner_name = "R820T";  break;
        case RTLSDR_TUNER_R828D:  tuner_name = "R828D";  break;
        default:                  tuner_name = "Unknown"; break;
    }

    int gains[100] = {};
    int n = rtlsdr_get_tuner_gains(g_dev, gains);
    if (n < 0) n = 0;

    char json[1024];
    int pos = snprintf(json, sizeof(json), "{\"tuner\":\"%s\",\"gains\":[", tuner_name);
    for (int i = 0; i < n && pos < (int)sizeof(json) - 10; i++)
        pos += snprintf(json + pos, sizeof(json) - pos, "%s%d", i ? "," : "", gains[i]);
    snprintf(json + pos, sizeof(json) - pos, "]}");

    return env->NewStringUTF(json);
}

// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeResetDsp(JNIEnv *, jobject)
{
    std::lock_guard<std::mutex> lock(g_dsp_mutex);
    memset(g_accum, 0, sizeof(g_accum));
    g_frame_count = 0;
}

// ---------------------------------------------------------------------------
// Reset the RTL2832U USB bulk-in endpoint so that rtlsdr_read_sync can
// start returning IQ data.  Must be called after setting sample rate and
// frequency, before the first nativeReadSamples() call.
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeResetBuffer(JNIEnv *, jobject)
{
    std::lock_guard<std::mutex> lock(g_dev_mutex);
    if (!g_dev) return JNI_FALSE;
    int r = rtlsdr_reset_buffer(g_dev);
    if (r < 0) { LOGE("rtlsdr_reset_buffer failed: %d", r); return JNI_FALSE; }
    LOGI("USB bulk-in endpoint reset OK");
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// NFM demodulator: 960 kHz IQ → 48 kHz PCM int16.
//
// Discriminator: atan2(Im(z·z*_prev), Re(z·z*_prev)) — bounded ±π radians,
// amplitude-independent (unlike disc/mag², which blows up on amplitude nulls).
//
// Squelch: FM disc RMS.  With atan2:
//   Signal (voice ±2.5–5 kHz dev at 48 kHz):  fm_rms ≈ 0.2–0.5
//   Noise / no carrier:                        fm_rms ≈ 0.8–2.0+
// Gate opens when fm_rms < threshold.
// squelchLevel 0 = always open; 1–20 → threshold 0.92–0.35.
// Start at 5 (threshold=0.80) — adjust up if noise leaks through.
// ---------------------------------------------------------------------------
JNIEXPORT jshortArray JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeDemodNFM(
        JNIEnv *env, jobject, jbyteArray iqArr, jint nBytes, jint squelchLevel)
{
    jbyte *raw = env->GetByteArrayElements(iqArr, nullptr);
    if (!raw) return nullptr;
    const uint8_t *iq = reinterpret_cast<const uint8_t *>(raw);
    int nSamp = nBytes / 2;
    int nOut  = nSamp  / NFM_DECIM;

    // Pass 1: IQ RMS for the signal-level indicator.
    double sumSq = 0.0;
    for (int i = 0; i < nSamp; i++) {
        float fi = ((int)iq[i*2]   - 127.5f) / 127.5f;
        float fq = ((int)iq[i*2+1] - 127.5f) / 127.5f;
        sumSq += (double)(fi*fi + fq*fq);
    }
    g_last_sig_rms = (nSamp > 0) ? sqrtf((float)(sumSq / nSamp)) : 0.0f;

    jshortArray outArr = env->NewShortArray(nOut);
    if (!outArr) { env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT); return nullptr; }
    jshort *out = env->GetShortArrayElements(outArr, nullptr);
    if (!out)    { env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT); return outArr; }

    // Pass 2: two-stage decimation + atan2 FM discriminator.
    //   Stage 1: 4:1 box filter  (960 kHz → 240 kHz)
    //   Stage 2: 5:1 FIR (63 tap, 240 kHz → 48 kHz)
    // fm_sum2 is accumulated here on the RAW discriminator output so the
    // squelch RMS sees the full noise energy (including any spikes).
    // Clipping the discriminator before this computation would reduce noise
    // RMS below the squelch threshold, causing noise to pass through as
    // constant static — so no clip here.
    double fm_sum2  = 0.0;
    int nComputed   = 0;
    for (int blk = 0; blk < nOut; blk++) {
        int base = blk * NFM_DECIM;

        // Stage 1: 4:1 box filter → DECIM2 samples at 240 kHz
        for (int j = 0; j < DECIM2; j++) {
            float si = 0.0f, sq_acc = 0.0f;
            int s = base + j * DECIM1;
            for (int k = 0; k < DECIM1; k++) {
                si     += ((int)iq[(s+k)*2]   - 127.5f) / 127.5f;
                sq_acc += ((int)iq[(s+k)*2+1] - 127.5f) / 127.5f;
            }
            g_lpf_i[g_lpf_idx] = si / (float)DECIM1;
            g_lpf_q[g_lpf_idx] = sq_acc / (float)DECIM1;
            g_lpf_idx = (g_lpf_idx + 1) % LPF_TAPS;
        }

        // Stage 2: 63-tap FIR
        float ci = 0.0f, cq = 0.0f;
        for (int k = 0; k < LPF_TAPS; k++) {
            int bidx = (g_lpf_idx - 1 - k + LPF_TAPS * 2) % LPF_TAPS;
            ci += NFM_LPF[k] * g_lpf_i[bidx];
            cq += NFM_LPF[k] * g_lpf_q[bidx];
        }

        // atan2 FM discriminator: phase difference = arg(z[n] · conj(z[n-1])).
        // Bounded ±π; immune to amplitude changes (no division by mag²).
        float di = ci * g_nfm_prev_i + cq * g_nfm_prev_q;  // Re(z · conj(z_prev))
        float dq = cq * g_nfm_prev_i - ci * g_nfm_prev_q;  // Im(z · conj(z_prev))
        g_nfm_prev_i = ci;
        g_nfm_prev_q = cq;
        float fm = atan2f(dq, di);
        s_fm_buf[nComputed++] = fm;
        fm_sum2 += (double)(fm * fm);
    }

    // 3-point causal median filter applied AFTER fm_sum2, so squelch sees the
    // raw discriminator energy.  A single ±π spike from a phase-wrap looks like
    // (normal, spike, normal) → median picks the two "normal" neighbours →
    // spike is replaced without any effect on adjacent samples.
    {
        float p2 = g_nfm_med_p2, p1 = g_nfm_med_p1;
        for (int i = 0; i < nComputed; i++) {
            float curr  = s_fm_buf[i];
            s_fm_buf[i] = median3(p2, p1, curr);
            p2 = p1;
            p1 = curr;
        }
        g_nfm_med_p2 = p2;
        g_nfm_med_p1 = p1;
    }

    // FM disc RMS for squelch (computed on raw output above).
    float fm_rms = (nComputed > 0) ? sqrtf((float)(fm_sum2 / nComputed)) : 2.0f;
    if (g_fm_rms_smooth <= 0.0f) g_fm_rms_smooth = fm_rms;
    else g_fm_rms_smooth = 0.85f * g_fm_rms_smooth + 0.15f * fm_rms;

    // Squelch: open when fm_rms < threshold (signal present).
    // squelchLevel 0 = always open; 1–20 → threshold 0.92 down to 0.35.
    float sq_thresh = (squelchLevel <= 0) ? 999.0f
                    : (0.95f - squelchLevel * 0.03f);
    bool sig_now  = (fm_rms < sq_thresh);
    if (sig_now) g_sq_hang = HANG_SAMP;
    bool gate_open = sig_now || (g_sq_hang > 0);
    if (!sig_now && g_sq_hang > 0) g_sq_hang -= nComputed;
    if (g_sq_hang < 0) g_sq_hang = 0;

    // Pass 3: squelch gate → float in s_fm_buf.
    // NFM transmitters use flat (no pre-emphasis) audio — de-emphasis is NOT applied here;
    // it would muffle voice and make noise relatively louder.  WFM (broadcast FM) uses
    // de-emphasis, handled in nativeDemodWFM.
    for (int o = 0; o < nComputed; o++) {
        s_fm_buf[o] = gate_open ? s_fm_buf[o] : 0.0f;
    }

    // Pass 4: voice-band filter — LPF at 3.5 kHz cuts FM noise, HPF at 250 Hz removes CTCSS.
    audio_filter(s_fm_buf, nComputed);

    // Pass 5: scale and clip to int16.
    for (int o = 0; o < nComputed; o++) {
        float a = s_fm_buf[o] * AUDIO_SCALE;
        if (a >  32767.0f) a =  32767.0f;
        if (a < -32768.0f) a = -32768.0f;
        out[o] = (jshort)a;
    }

    env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT);
    env->ReleaseShortArrayElements(outArr, out, 0);
    return outArr;
}

// ---------------------------------------------------------------------------
// Measure IQ RMS without demodulating (cheap squelch check for scan mode).
// ---------------------------------------------------------------------------
JNIEXPORT jfloat JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeMeasureSignalLevel(
        JNIEnv *env, jobject, jbyteArray iqArr, jint nBytes)
{
    jbyte *raw = env->GetByteArrayElements(iqArr, nullptr);
    if (!raw) return 0.0f;
    const uint8_t *iq = reinterpret_cast<const uint8_t *>(raw);
    int nSamp = nBytes / 2;
    double sumSq = 0.0;
    for (int i = 0; i < nSamp; i++) {
        float fi = ((int)iq[i*2]   - 127.5f) / 127.5f;
        float fq = ((int)iq[i*2+1] - 127.5f) / 127.5f;
        sumSq += (double)(fi*fi + fq*fq);
    }
    env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT);
    float rms = (nSamp > 0) ? sqrtf((float)(sumSq / nSamp)) : 0.0f;
    g_last_sig_rms = rms;
    update_noise_floor(rms);
    return (jfloat)rms;
}

// ---------------------------------------------------------------------------
JNIEXPORT jfloat JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeGetSignalRms(JNIEnv *, jobject)
{
    return (jfloat)g_last_sig_rms;
}

// ---------------------------------------------------------------------------
JNIEXPORT jfloat JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeGetNoiseFloor(JNIEnv *, jobject)
{
    return (jfloat)g_noise_floor;
}

// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeResetDemod(JNIEnv *, jobject)
{
    g_nfm_prev_i = g_nfm_prev_q = g_nfm_deemph = g_last_sig_rms = 0.0f;
    g_nfm_med_p1 = g_nfm_med_p2 = 0.0f;
    g_fm_rms_smooth = g_noise_floor = g_am_dc = 0.0f;
    g_filt_floor = 0.0f;
    g_sq_hang    = 0;
    memset(g_lpf_i, 0, sizeof(g_lpf_i));
    memset(g_lpf_q, 0, sizeof(g_lpf_q));
    g_lpf_idx = 0;
    g_lpf_s1 = g_lpf_s2 = g_hpf_s1 = g_hpf_s2 = 0.0f;
    g_wfm_prev_i = g_wfm_prev_q = g_wfm_deemph = 0.0f;
    memset(g_wfm_hist, 0, sizeof(g_wfm_hist));
    g_wfm_hist_idx = 0;
}

// ---------------------------------------------------------------------------
// FM discriminator RMS (low = signal present, high = noise). Used by wideband
// loop to decide whether to extend hold on the locked channel.
// ---------------------------------------------------------------------------
JNIEXPORT jfloat JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeGetFmRms(JNIEnv *, jobject)
{
    return (jfloat)g_fm_rms_smooth;
}

// ---------------------------------------------------------------------------
// Measure per-channel IQ power at specified offsets from the tuned center.
// Used by wideband monitor to find the active channel without retuning.
// offsetsHz: Hz offset of each channel from center (can be negative)
// Returns float[] of RMS amplitude per channel (higher = more signal).
// ---------------------------------------------------------------------------
JNIEXPORT jfloatArray JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeGetChannelPowers(
        JNIEnv *env, jobject,
        jbyteArray iqArr, jint nBytes,
        jlongArray offsetsArr, jint nChannels)
{
    jbyte *raw = env->GetByteArrayElements(iqArr, nullptr);
    if (!raw) return nullptr;
    const uint8_t *iq = reinterpret_cast<const uint8_t *>(raw);
    int nSamp = nBytes / 2;

    jlong *offsets = env->GetLongArrayElements(offsetsArr, nullptr);
    if (!offsets) { env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT); return nullptr; }

    jfloatArray outArr = env->NewFloatArray(nChannels);
    jfloat *out = outArr ? env->GetFloatArrayElements(outArr, nullptr) : nullptr;
    if (!out) {
        env->ReleaseLongArrayElements(offsetsArr, offsets, JNI_ABORT);
        env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT);
        return outArr;
    }

    // For each channel: mix IQ to that channel's centre (incremental rotation),
    // then box-average over BOX samples to get 960kHz/BOX = 15 kHz bandwidth.
    // RMS of the averaged blocks = narrowband signal amplitude at that channel.
    static constexpr int BOX = 64;

    for (int ch = 0; ch < nChannels; ch++) {
        float step  = -2.0f * (float)M_PI * (float)offsets[ch] / 960000.0f;
        float cos_s = cosf(step), sin_s = sinf(step);
        float cos_c = 1.0f,      sin_c = 0.0f;

        double sum_pow = 0.0;
        int    n_blk   = 0;
        for (int i = 0; i + BOX <= nSamp; i += BOX) {
            float re = 0.0f, im = 0.0f;
            for (int k = 0; k < BOX; k++) {
                int s  = i + k;
                float fi = ((int)iq[s*2]   - 127.5f) / 127.5f;
                float fq = ((int)iq[s*2+1] - 127.5f) / 127.5f;
                re += fi * cos_c - fq * sin_c;
                im += fi * sin_c + fq * cos_c;
                float nc = cos_c * cos_s - sin_c * sin_s;
                float ns = cos_c * sin_s + sin_c * cos_s;
                cos_c = nc;  sin_c = ns;
            }
            float br = re / (float)BOX, bi = im / (float)BOX;
            sum_pow += (double)(br * br + bi * bi);
            n_blk++;
        }
        out[ch] = (n_blk > 0) ? sqrtf((float)(sum_pow / n_blk)) : 0.0f;
    }

    env->ReleaseLongArrayElements(offsetsArr, offsets, JNI_ABORT);
    env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT);
    env->ReleaseFloatArrayElements(outArr, out, 0);
    return outArr;
}

// ---------------------------------------------------------------------------
// AM envelope demodulator: 960 kHz IQ → 48 kHz PCM int16.
// Uses the same two-stage FIR decimation as NFM; demodulates by taking the
// IQ envelope (sqrt I²+Q²) and removing the carrier DC via a slow high-pass.
// squelchLevel 0 = open; 1–20 uses relative IQ-RMS squelch (for standalone use).
// ---------------------------------------------------------------------------
JNIEXPORT jshortArray JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeDemodAM(
        JNIEnv *env, jobject, jbyteArray iqArr, jint nBytes, jint squelchLevel)
{
    jbyte *raw = env->GetByteArrayElements(iqArr, nullptr);
    if (!raw) return nullptr;
    const uint8_t *iq = reinterpret_cast<const uint8_t *>(raw);
    int nSamp = nBytes / 2;
    int nOut  = nSamp  / NFM_DECIM;

    // IQ RMS for signal level display + squelch
    double sumSq = 0.0;
    for (int i = 0; i < nSamp; i++) {
        float fi = ((int)iq[i*2]   - 127.5f) / 127.5f;
        float fq = ((int)iq[i*2+1] - 127.5f) / 127.5f;
        sumSq += (double)(fi*fi + fq*fq);
    }
    g_last_sig_rms = (nSamp > 0) ? sqrtf((float)(sumSq / nSamp)) : 0.0f;
    update_noise_floor(g_last_sig_rms);

    jshortArray outArr = env->NewShortArray(nOut);
    if (!outArr) { env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT); return nullptr; }
    jshort *out = env->GetShortArrayElements(outArr, nullptr);
    if (!out)    { env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT); return outArr; }

    // Relative IQ-RMS squelch (carrier detect for standalone AM use)
    float floor  = (g_noise_floor > 0.0f) ? g_noise_floor : g_last_sig_rms;
    float thresh = (squelchLevel <= 0 || floor <= 0.0f) ? 0.0f
                   : floor * powf(10.0f, squelchLevel / 20.0f);
    bool squelched = (thresh > 0.0f) && (g_last_sig_rms < thresh);

    // Two-stage decimation + AM envelope → float audio in s_fm_buf.
    for (int blk = 0; blk < nOut; blk++) {
        int base = blk * NFM_DECIM;
        for (int j = 0; j < DECIM2; j++) {
            float si = 0.0f, sq_acc = 0.0f;
            int s = base + j * DECIM1;
            for (int k = 0; k < DECIM1; k++) {
                si     += ((int)iq[(s+k)*2]   - 127.5f) / 127.5f;
                sq_acc += ((int)iq[(s+k)*2+1] - 127.5f) / 127.5f;
            }
            g_lpf_i[g_lpf_idx] = si / (float)DECIM1;
            g_lpf_q[g_lpf_idx] = sq_acc / (float)DECIM1;
            g_lpf_idx = (g_lpf_idx + 1) % LPF_TAPS;
        }
        float ci = 0.0f, cq = 0.0f;
        for (int k = 0; k < LPF_TAPS; k++) {
            int bidx = (g_lpf_idx - 1 - k + LPF_TAPS * 2) % LPF_TAPS;
            ci += NFM_LPF[k] * g_lpf_i[bidx];
            cq += NFM_LPF[k] * g_lpf_q[bidx];
        }
        // Envelope detection + carrier DC removal (τ ≈ 200 ms at 48 kHz).
        float env_val = sqrtf(ci * ci + cq * cq);
        g_am_dc = 0.9999f * g_am_dc + 0.0001f * env_val;
        s_fm_buf[blk] = squelched ? 0.0f : (env_val - g_am_dc);
    }

    // Voice-band filter (LPF 3.5 kHz, HPF 250 Hz).
    audio_filter(s_fm_buf, nOut);

    // Scale and clip to int16.
    for (int blk = 0; blk < nOut; blk++) {
        float a = s_fm_buf[blk] * 200000.0f;
        if (a >  32767.0f) a =  32767.0f;
        if (a < -32768.0f) a = -32768.0f;
        out[blk] = (jshort)a;
    }

    env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT);
    env->ReleaseShortArrayElements(outArr, out, 0);
    return outArr;
}

// ---------------------------------------------------------------------------
// WFM (broadcast FM) demodulator: 960 kHz IQ → 48 kHz PCM int16.
//
// Pipeline:
//   Stage 1: 4:1 box filter (960 kHz → 240 kHz IQ)
//   Stage 2: atan2 discriminator at 240 kHz
//            Max phase step = 2π × 75000/240000 ≈ 1.96 rad < π → no wrapping.
//   Stage 3: 63-tap FIR LPF + 5:1 decimate (240 kHz → 48 kHz audio, fc=15 kHz)
//            Reuses NFM_LPF coefficients — same fc/fs ratio.
//            Passes full stereo-pilot-free mono audio (50 Hz – 15 kHz).
//   Stage 4: de-emphasis τ=75 µs at 48 kHz (US broadcast standard).
//
// No squelch gating — broadcast FM always outputs audio.
// No HPF: FM music has content down to ~50 Hz; the FIR already handles LPF.
// ---------------------------------------------------------------------------
JNIEXPORT jshortArray JNICALL
Java_com_jamesburnetthq_ravensdr_RtlSdrDriver_nativeDemodWFM(
        JNIEnv *env, jobject, jbyteArray iqArr, jint nBytes, jint /*squelchLevel*/)
{
    jbyte *raw = env->GetByteArrayElements(iqArr, nullptr);
    if (!raw) return nullptr;
    const uint8_t *iq = reinterpret_cast<const uint8_t *>(raw);
    int nSamp = nBytes / 2;
    int nOut  = nSamp / NFM_DECIM;   // 960 kHz / 20 = 48 kHz output samples

    // IQ RMS for signal-level display
    double sumSq = 0.0;
    for (int i = 0; i < nSamp; i++) {
        float fi = ((int)iq[i*2]   - 127.5f) / 127.5f;
        float fq = ((int)iq[i*2+1] - 127.5f) / 127.5f;
        sumSq += (double)(fi*fi + fq*fq);
    }
    g_last_sig_rms = (nSamp > 0) ? sqrtf((float)(sumSq / nSamp)) : 0.0f;

    jshortArray outArr = env->NewShortArray(nOut);
    if (!outArr) { env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT); return nullptr; }
    jshort *out = env->GetShortArrayElements(outArr, nullptr);
    if (!out)    { env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT); return outArr; }

    for (int blk = 0; blk < nOut; blk++) {
        int base = blk * NFM_DECIM;

        // Stage 1+2: 4:1 box filter → 5 IQ samples at 240 kHz → discriminator → 5 FM samples.
        for (int j = 0; j < DECIM2; j++) {
            float si = 0.0f, sq_v = 0.0f;
            int s = base + j * DECIM1;
            for (int k = 0; k < DECIM1; k++) {
                si   += ((int)iq[(s+k)*2]   - 127.5f) / 127.5f;
                sq_v += ((int)iq[(s+k)*2+1] - 127.5f) / 127.5f;
            }
            float ci = si / (float)DECIM1;
            float cq = sq_v / (float)DECIM1;

            float di = ci * g_wfm_prev_i + cq * g_wfm_prev_q;
            float dq = cq * g_wfm_prev_i - ci * g_wfm_prev_q;
            g_wfm_prev_i = ci;
            g_wfm_prev_q = cq;

            g_wfm_hist[g_wfm_hist_idx] = atan2f(dq, di);
            g_wfm_hist_idx = (g_wfm_hist_idx + 1) % WFM_LPF_TAPS;
        }

        // Stage 3: 63-tap LPF on FM history (passes 0–15 kHz, rejects 19 kHz pilot+stereo).
        float audio = 0.0f;
        for (int k = 0; k < WFM_LPF_TAPS; k++) {
            int bidx = (g_wfm_hist_idx - 1 - k + WFM_LPF_TAPS * 2) % WFM_LPF_TAPS;
            audio += WFM_LPF[k] * g_wfm_hist[bidx];
        }

        // Stage 4: de-emphasis τ=75 µs (broadcast standard).
        g_wfm_deemph = DEEMPH_A * g_wfm_deemph + (1.0f - DEEMPH_A) * audio;

        float a = g_wfm_deemph * AUDIO_SCALE_WFM;
        if (a >  32767.0f) a =  32767.0f;
        if (a < -32768.0f) a = -32768.0f;
        out[blk] = (jshort)a;
    }

    env->ReleaseByteArrayElements(iqArr, raw, JNI_ABORT);
    env->ReleaseShortArrayElements(outArr, out, 0);
    return outArr;
}

} // extern "C"
