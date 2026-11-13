package com.jhaa2k.vooc;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.FileInputStream;

/**
 * Decodes a whole audio file (mp3, m4a, aac, ogg, flac, wav ...) with the
 * platform codecs and turns it into a compact spectrum timeline:
 * {@code fps} frames per second, {@code bands} log-spaced bands per frame,
 * one unsigned byte (0..255) per band, followed by one RMS byte per frame.
 *
 * Runs on a background thread. Python polls {@link #getProgress()} and reads
 * the base64 result, so no microphone / RECORD_AUDIO permission is needed and
 * the spectrum is perfectly in sync with MediaPlayer's position.
 */
public class SpectrumDecoder {
    private static final int FFT = 1024;
    private volatile float progress = 0f;
    private volatile boolean cancelled = false;
    private volatile String error = "";
    private volatile boolean done = false;
    private int sampleRate = 44100;
    private volatile int frameCount = 0;
    private volatile int published = 0;   // frames safe to read by readFrames()
    private volatile byte[] liveSpec = new byte[0];
    private volatile byte[] liveRms = new byte[0];
    private final Object lock = new Object();

    public float getProgress() { return progress; }
    public String getError() { return error; }
    public int getSampleRate() { return sampleRate; }
    public int getFrameCount() { return frameCount; }
    public int getAvailableFrames() { return published; }
    public boolean isDone() { return done; }
    public void cancel() { cancelled = true; }

    /**
     * Start decoding on a Java thread (no Python thread / class-loader issues).
     * Poll {@link #getAvailableFrames()} and {@link #readFrames(int, int)} to
     * stream the spectrum while it is being decoded; {@link #isDone()} ends it.
     */
    public void start(final String path, final int bands, final int fps) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { analyze(path, bands, fps); }
                catch (Throwable e) { error = String.valueOf(e); Log.e("VoocSpectrum", "decode failed", e); }
                finally { done = true; }
            }
        }, "VoocSpectrum");
        t.setDaemon(true);
        t.start();
    }

    /** base64 of frames [from, to): for each frame {@code bands} spectrum bytes then one rms byte. */
    public String readFrames(int from, int to, int bands) {
        synchronized (lock) {
            to = Math.min(to, published);
            if (from < 0 || to <= from) return "";
            byte[] out = new byte[(to - from) * (bands + 1)];
            int o = 0;
            for (int f = from; f < to; f++) {
                System.arraycopy(liveSpec, f * bands, out, o, bands);
                o += bands;
                out[o++] = liveRms[f];
            }
            return Base64.encodeToString(out, Base64.NO_WRAP);
        }
    }

    private static int intOr(MediaFormat f, String key, int def) {
        try { return f != null && f.containsKey(key) ? f.getInteger(key) : def; } catch (Throwable t) { return def; }
    }

    /** Returns base64 of [frames*bands spectrum bytes][frames rms bytes], or null. */
    public String analyze(String path, int bands, int fps) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            try {
                extractor.setDataSource(path);
            } catch (Throwable first) {
                // some files are only readable through a descriptor
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
            if (track < 0) { error = "no audio track"; return null; }
            extractor.selectTrack(track);
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = intOr(format, MediaFormat.KEY_CHANNEL_COUNT, 2);
            int encoding = 2; // AudioFormat.ENCODING_PCM_16BIT
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
            int hop = Math.max(64, sampleRate / Math.max(1, fps));
            int capacity = durationUs > 0 ? (int) (durationUs / 1000000.0 * fps) + 8 : fps * 600;
            capacity = Math.max(16, Math.min(capacity, fps * 60 * 30));

            byte[] spec = new byte[capacity * bands];
            byte[] rms = new byte[capacity];
            synchronized (lock) { liveSpec = spec; liveRms = rms; published = 0; }

            // log band edges in FFT bins
            int[] lo = new int[bands];
            int[] hi = new int[bands];
            double fmin = 30.0, fmax = Math.min(16000.0, sampleRate / 2.0 - 1);
            double binHz = (double) sampleRate / FFT;
            for (int b = 0; b < bands; b++) {
                double a = fmin * Math.pow(fmax / fmin, (double) b / bands);
                double z = fmin * Math.pow(fmax / fmin, (double) (b + 1) / bands);
                lo[b] = Math.max(1, (int) Math.floor(a / binHz));
                hi[b] = Math.min(FFT / 2, Math.max(lo[b] + 1, (int) Math.ceil(z / binHz)));
            }
            float[] window = new float[FFT];
            for (int i = 0; i < FFT; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT - 1)));
            float[] ring = new float[FFT];
            int ringPos = 0;
            int sinceHop = 0;
            double[] re = new double[FFT];
            double[] im = new double[FFT];

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int frames = 0;
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
                    // some decoders never flag EOS: give up after ~3 s without output
                    if (inputDone && ++idle > 300) break;
                    continue;
                }
                idle = 0;
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    channels = intOr(of, MediaFormat.KEY_CHANNEL_COUNT, channels);
                    int sr = intOr(of, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                    if (sr != sampleRate && sr > 0) { sampleRate = sr; hop = Math.max(64, sampleRate / Math.max(1, fps)); }
                    encoding = intOr(of, "pcm-encoding", 2);
                } else if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        out.order(ByteOrder.LITTLE_ENDIAN);
                        // bytes per sample: 16-bit (2), float (4), 8-bit (3), 24-bit packed (21), 32-bit int (22)
                        int bps = encoding == 4 || encoding == 22 ? 4 : encoding == 3 ? 1 : encoding == 21 ? 3 : 2;
                        int ch = Math.max(1, channels);
                        int base = info.offset;
                        int count = info.size / (bps * ch);
                        for (int i = 0; i < count; i++) {
                            float m = 0f;
                            for (int c = 0; c < ch; c++) {
                                int at = base + (i * ch + c) * bps;
                                float v;
                                if (bps == 2) v = out.getShort(at) / 32768f;
                                else if (encoding == 4) v = out.getFloat(at);
                                else if (bps == 4) v = out.getInt(at) / 2147483648f;
                                else if (bps == 1) v = ((out.get(at) & 0xff) - 128) / 128f;
                                else v = ((out.get(at) & 0xff) | ((out.get(at + 1) & 0xff) << 8) | (out.get(at + 2) << 16)) / 8388608f;
                                m += v;
                            }
                            ring[ringPos] = m / ch;
                            ringPos = (ringPos + 1) % FFT;
                            if (++sinceHop >= hop) {
                                sinceHop = 0;
                                if (frames >= capacity) {
                                    int newCap = capacity * 2;
                                    byte[] ns = new byte[newCap * bands];
                                    System.arraycopy(spec, 0, ns, 0, spec.length);
                                    byte[] nr = new byte[newCap];
                                    System.arraycopy(rms, 0, nr, 0, rms.length);
                                    synchronized (lock) { spec = ns; rms = nr; liveSpec = ns; liveRms = nr; }
                                    capacity = newCap;
                                }
                                analyseFrame(ring, ringPos, window, re, im, lo, hi, bands, spec, rms, frames);
                                frames++;
                                if ((frames & 15) == 0) published = frames;
                            }
                        }
                        published = frames;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            if (cancelled) { error = "cancelled"; return null; }
            frameCount = frames;
            published = frames;
            if (frames == 0) { error = "no audio decoded"; return null; }
            byte[] result = new byte[frames * bands + frames];
            System.arraycopy(spec, 0, result, 0, frames * bands);
            System.arraycopy(rms, 0, result, frames * bands, frames);
            progress = 1f;
            return Base64.encodeToString(result, Base64.NO_WRAP);
        } catch (Throwable t) {
            error = String.valueOf(t);
            Log.e("VoocSpectrum", "analyze failed for " + path, t);
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            try { extractor.release(); } catch (Throwable ignored) { }
        }
    }

    private static void analyseFrame(float[] ring, int start, float[] window, double[] re, double[] im,
                                     int[] lo, int[] hi, int bands, byte[] spec, byte[] rms, int frame) {
        double energy = 0;
        for (int i = 0; i < FFT; i++) {
            float s = ring[(start + i) % FFT];
            energy += s * s;
            re[i] = s * window[i];
            im[i] = 0;
        }
        fft(re, im);
        double scale = 4.0 / FFT;
        for (int b = 0; b < bands; b++) {
            double peak = 0;
            for (int k = lo[b]; k < hi[b]; k++) {
                double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]) * scale;
                if (mag > peak) peak = mag;
            }
            // -66 dB .. -6 dB  ->  0 .. 1 ; tilt lifts treble a little (music falls off ~3 dB/oct)
            double db = 20 * Math.log10(peak + 1e-9) + 3.0 * (b * 8.0 / bands);
            double v = (db + 66.0) / 60.0;
            v = Math.max(0, Math.min(1, v));
            spec[frame * bands + b] = (byte) (int) Math.round(v * 255);
        }
        double r = Math.sqrt(energy / FFT);
        rms[frame] = (byte) (int) Math.round(Math.max(0, Math.min(1, r * 2.2)) * 255);
    }

    private static void fft(double[] re, double[] im) {
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
