package com.jhaa2k.vooc;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Base64;
import android.util.Log;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes a whole audio file (mp3, m4a/aac, flac, wav, ogg/vorbis, opus ...)
 * with the platform codecs into a spectrum timeline that Python samples at the
 * player position (no microphone, no AudioRecord, always in sync).
 *
 * Analysis is configurable:
 *  - FFT size 512..8192, window Hann / Hamming / Blackman / Rectangular / Blackman-Harris
 *  - overlap 0..75 % (hop = fft * (1 - overlap)), clamped to 20..120 frames per second
 *  - channel mode: mono (L+R)/2 only, or stereo = [mono, left, right, side] spectra
 *  - decimated PCM (int8, ~8 kHz) for real waveform rendering
 *
 * Frame record: {@code nspec * bands} bytes of dB (byte = (dB + 100) / 100 * 255,
 * no tilt, peak-normalised so a full scale sine is 0 dB) then one RMS byte and
 * one peak byte (linear 0..1).
 */
public class SpectrumDecoder {
    private volatile float progress = 0f;
    private volatile boolean cancelled = false;
    private volatile String error = "";
    private volatile boolean done = false;
    private volatile int sampleRate = 44100;
    private volatile int hop = 1024;
    private volatile int frameCount = 0;
    private volatile int published = 0;
    private volatile int pcmPublished = 0;
    private volatile byte[] live = new byte[0];
    private volatile byte[] livePcm = new byte[0];
    private volatile boolean formatKnown = false;
    private int nspec = 1;
    private int bands = 64;
    private int record = 66;
    private int pcmChannels = 1;
    private int pcmRate = 8000;
    private final Object lock = new Object();

    public float getProgress() { return progress; }
    public String getError() { return error; }
    public int getSampleRate() { return sampleRate; }
    public int getHop() { return hop; }
    public double getFps() { return sampleRate / (double) Math.max(1, hop); }
    public boolean isFormatKnown() { return formatKnown; }
    public int getFrameCount() { return frameCount; }
    public int getAvailableFrames() { return published; }
    public int getAvailablePcm() { return pcmPublished; }
    public int getSpectra() { return nspec; }
    public int getPcmChannels() { return pcmChannels; }
    public int getPcmRate() { return pcmRate; }
    public boolean isDone() { return done; }
    public void cancel() { cancelled = true; }

    /** Backwards compatible entry point (64 bands, FFT 1024, Hann, mono). */
    public void start(final String path, final int bands, final int fps) {
        start(path, bands, 1024, 0, 50, false, 8000);
    }

    public void start(final String path, final int nBands, final int fft, final int windowType,
                      final int overlapPct, final boolean stereo, final int pcmRateHz) {
        this.bands = Math.max(8, Math.min(256, nBands));
        this.nspec = stereo ? 4 : 1;
        this.record = nspec * this.bands + 2;
        this.pcmChannels = stereo ? 2 : 1;
        this.pcmRate = Math.max(2000, Math.min(22050, pcmRateHz));
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { analyze(path, fft, windowType, overlapPct); }
                catch (Throwable e) { error = String.valueOf(e); Log.e("VoocSpectrum", "decode failed", e); }
                finally { done = true; }
            }
        }, "VoocSpectrum");
        t.setDaemon(true);
        t.start();
    }

    /** base64 of frame records [from, to). */
    public String readFrames(int from, int to, int ignored) {
        synchronized (lock) {
            to = Math.min(to, published);
            if (from < 0 || to <= from) return "";
            byte[] out = new byte[(to - from) * record];
            System.arraycopy(live, from * record, out, 0, out.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        }
    }

    /** base64 of decimated PCM samples [from, to) (int8, interleaved when stereo). */
    public String readPcm(int from, int to) {
        synchronized (lock) {
            to = Math.min(to, pcmPublished);
            if (from < 0 || to <= from) return "";
            byte[] out = new byte[(to - from) * pcmChannels];
            System.arraycopy(livePcm, from * pcmChannels, out, 0, out.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        }
    }

    private static int intOr(MediaFormat f, String key, int def) {
        try { return f != null && f.containsKey(key) ? f.getInteger(key) : def; } catch (Throwable t) { return def; }
    }

    static float[] makeWindow(int n, int type) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            double x = 2 * Math.PI * i / (n - 1);
            double v;
            switch (type) {
                case 1: v = 0.54 - 0.46 * Math.cos(x); break;                                       // Hamming
                case 2: v = 0.42 - 0.5 * Math.cos(x) + 0.08 * Math.cos(2 * x); break;               // Blackman
                case 3: v = 1.0; break;                                                              // Rectangular
                case 4: v = 0.35875 - 0.48829 * Math.cos(x) + 0.14128 * Math.cos(2 * x)
                        - 0.01168 * Math.cos(3 * x); break;                                          // Blackman-Harris
                default: v = 0.5 - 0.5 * Math.cos(x);                                                // Hann
            }
            w[i] = (float) v;
        }
        return w;
    }

    static int computeHop(int fft, int overlapPct, int rate) {
        int h = (int) Math.round(fft * (1.0 - Math.max(0, Math.min(90, overlapPct)) / 100.0));
        int minHop = Math.max(32, rate / 120);
        int maxHop = Math.max(minHop, rate / 20);
        return Math.max(minHop, Math.min(maxHop, h));
    }

    private void analyze(String path, int fftReq, int windowType, int overlapPct) {
        int FFT = 512;
        while (FFT < fftReq && FFT < 8192) FFT <<= 1;
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            try {
                extractor.setDataSource(path);
            } catch (Throwable first) {
                FileInputStream fis = new FileInputStream(path);
                try { extractor.setDataSource(fis.getFD()); } finally { fis.close(); }
            }
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; format = f; break; }
            }
            if (track < 0) { error = "no audio track"; return; }
            extractor.selectTrack(track);
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = intOr(format, MediaFormat.KEY_CHANNEL_COUNT, 2);
            int encoding = 2;
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0L;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            Analyzer an = null;
            byte[] data = new byte[0];
            byte[] pcm = new byte[0];
            int frames = 0, pcmCount = 0;
            double pcmStep = 1, pcmAcc = 0;
            float accM = 0, accL = 0, accR = 0;
            int accN = 0;
            boolean inputDone = false, outputDone = false;
            int idle = 0;
            while (!outputDone && !cancelled) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long t = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, size, t, 0);
                            extractor.advance();
                            if (durationUs > 0) progress = Math.min(0.99f, (float) t / durationUs);
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idle > 300) break;
                    continue;
                }
                idle = 0;
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    channels = intOr(of, MediaFormat.KEY_CHANNEL_COUNT, channels);
                    int sr = intOr(of, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                    if (sr > 0 && an == null) sampleRate = sr;
                    encoding = intOr(of, "pcm-encoding", 2);
                    continue;
                }
                if (outIndex < 0) continue;
                if (an == null) {
                    hop = computeHop(FFT, overlapPct, sampleRate);
                    an = new Analyzer(FFT, windowType, bands, nspec, sampleRate);
                    double secs = durationUs > 0 ? durationUs / 1e6 : 600;
                    int cap = (int) (secs * sampleRate / hop) + 16;
                    data = new byte[Math.max(64, cap) * record];
                    pcm = new byte[(int) (secs * pcmRate + 64) * pcmChannels];
                    pcmStep = sampleRate / (double) pcmRate;
                    synchronized (lock) { live = data; livePcm = pcm; }
                    formatKnown = true;
                }
                ByteBuffer out = codec.getOutputBuffer(outIndex);
                if (out != null && info.size > 0) {
                    out.order(ByteOrder.LITTLE_ENDIAN);
                    int bps = encoding == 4 || encoding == 22 ? 4 : encoding == 3 ? 1 : encoding == 21 ? 3 : 2;
                    int ch = Math.max(1, channels);
                    int base = info.offset;
                    int count = info.size / (bps * ch);
                    for (int i = 0; i < count; i++) {
                        float l = 0, r = 0;
                        for (int c = 0; c < ch; c++) {
                            int at = base + (i * ch + c) * bps;
                            float v;
                            if (bps == 2) v = out.getShort(at) / 32768f;
                            else if (encoding == 4) v = out.getFloat(at);
                            else if (bps == 4) v = out.getInt(at) / 2147483648f;
                            else if (bps == 1) v = ((out.get(at) & 0xff) - 128) / 128f;
                            else v = ((out.get(at) & 0xff) | ((out.get(at + 1) & 0xff) << 8) | (out.get(at + 2) << 16)) / 8388608f;
                            if (c == 0) l = v; else if (c == 1) r = v; else { l += v * 0.5f; r += v * 0.5f; }
                        }
                        if (ch == 1) r = l;
                        an.push(l, r);
                        // decimated PCM (box filter)
                        accM += (l + r) * 0.5f; accL += l; accR += r; accN++;
                        pcmAcc += 1;
                        if (pcmAcc >= pcmStep) {
                            pcmAcc -= pcmStep;
                            if ((pcmCount + 1) * pcmChannels > pcm.length) {
                                byte[] np = new byte[pcm.length * 2 + 64];
                                System.arraycopy(pcm, 0, np, 0, pcm.length);
                                synchronized (lock) { pcm = np; livePcm = np; }
                            }
                            if (pcmChannels == 1) pcm[pcmCount] = toByte(accM / accN);
                            else { pcm[pcmCount * 2] = toByte(accL / accN); pcm[pcmCount * 2 + 1] = toByte(accR / accN); }
                            pcmCount++;
                            accM = accL = accR = 0; accN = 0;
                        }
                        if (an.ready(hop)) {
                            if ((frames + 1) * record > data.length) {
                                byte[] nd = new byte[data.length * 2];
                                System.arraycopy(data, 0, nd, 0, data.length);
                                synchronized (lock) { data = nd; live = nd; }
                            }
                            an.frame(data, frames * record);
                            frames++;
                        }
                    }
                    published = frames;
                    pcmPublished = pcmCount;
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
            if (cancelled) { error = "cancelled"; return; }
            frameCount = frames;
            published = frames;
            pcmPublished = pcmCount;
            if (frames == 0) { error = "no audio decoded"; return; }
            progress = 1f;
        } catch (Throwable t) {
            error = String.valueOf(t);
            Log.e("VoocSpectrum", "analyze failed for " + path, t);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            try { extractor.release(); } catch (Throwable ignored) { }
        }
    }

    private static byte toByte(float v) {
        int q = Math.round(Math.max(-1f, Math.min(1f, v)) * 127f);
        return (byte) q;
    }

    /** Ring buffers + FFT for mono / left / right / side. */
    static final class Analyzer {
        final int n, bands, nspec;
        final float[] window;
        final float[][] ring;
        int pos = 0, since = 0, filled = 0;
        final double[] re, im, mag;
        final int[] lo, hi;
        final double[] centerBin;
        final double scale;

        Analyzer(int fft, int windowType, int bands, int nspec, int rate) {
            this.n = fft;
            this.bands = bands;
            this.nspec = nspec;
            window = makeWindow(fft, windowType);
            double sum = 0;
            for (float w : window) sum += w;
            scale = 2.0 / Math.max(1e-9, sum);
            ring = new float[nspec][fft];
            re = new double[fft];
            im = new double[fft];
            mag = new double[fft / 2 + 1];
            lo = new int[bands];
            hi = new int[bands];
            centerBin = new double[bands];
            double fmin = 30.0, fmax = 16000.0;   // fixed axis for every sample rate
            double binHz = (double) rate / fft;
            for (int b = 0; b < bands; b++) {
                double a = fmin * Math.pow(fmax / fmin, (double) b / bands);
                double z = fmin * Math.pow(fmax / fmin, (double) (b + 1) / bands);
                lo[b] = Math.max(1, (int) Math.ceil(a / binHz));
                hi[b] = Math.min(fft / 2, (int) Math.floor(z / binHz));
                centerBin[b] = Math.sqrt(a * z) / binHz;
            }
        }

        void push(float l, float r) {
            float m = (l + r) * 0.5f;
            ring[0][pos] = m;
            if (nspec == 4) {
                ring[1][pos] = l;
                ring[2][pos] = r;
                ring[3][pos] = (l - r) * 0.5f;
            }
            pos = (pos + 1) % n;
            since++;
            if (filled < n) filled++;
        }

        boolean ready(int hop) {
            if (since >= hop) { since = 0; return true; }
            return false;
        }

        void frame(byte[] out, int at) {
            double energy = 0, peak = 0;
            for (int i = 0; i < n; i++) {
                float s = ring[0][(pos + i) % n];
                energy += s * s;
                if (Math.abs(s) > peak) peak = Math.abs(s);
            }
            for (int s = 0; s < nspec; s++) {
                float[] rb = ring[s];
                for (int i = 0; i < n; i++) {
                    re[i] = rb[(pos + i) % n] * window[i];
                    im[i] = 0;
                }
                fft(re, im);
                for (int k = 0; k <= n / 2; k++) mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]) * scale;
                int o = at + s * bands;
                for (int b = 0; b < bands; b++) {
                    double v;
                    if (hi[b] >= lo[b]) {
                        v = 0;
                        for (int k = lo[b]; k <= hi[b]; k++) if (mag[k] > v) v = mag[k];
                    } else if (centerBin[b] >= n / 2) {
                        v = 0;                      // above Nyquist
                    } else {
                        // band narrower than one FFT bin: interpolate at the band center
                        double x = centerBin[b];
                        int k0 = Math.max(0, Math.min(n / 2 - 1, (int) Math.floor(x)));
                        double f = Math.max(0, Math.min(1, x - k0));
                        v = mag[k0] * (1 - f) + mag[k0 + 1] * f;
                    }
                    double db = 20 * Math.log10(v + 1e-10);
                    double q = (db + 100.0) / 100.0;
                    out[o + b] = (byte) (int) Math.round(Math.max(0, Math.min(1, q)) * 255);
                }
            }
            double rms = Math.sqrt(energy / n);
            out[at + nspec * bands] = (byte) (int) Math.round(Math.max(0, Math.min(1, rms * 2.2)) * 255);
            out[at + nspec * bands + 1] = (byte) (int) Math.round(Math.max(0, Math.min(1, peak)) * 255);
        }
    }

    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }
}
