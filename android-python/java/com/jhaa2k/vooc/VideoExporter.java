package com.jhaa2k.vooc;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Hardware video export: Python renders every frame offline (fixed time step,
 * so audio and video never drift), converts it to I420 and hands it to
 * {@link #writeFrame}. This class encodes H.264 / HEVC with MediaCodec and
 * muxes it into an MP4 together with the track's audio:
 *  - AAC sources are copied untouched,
 *  - any other format (mp3, flac, ogg, wav, opus ...) is decoded and re-encoded to AAC
 *    on a background thread before the first frame is muxed.
 * HEVC falls back to H.264 when the device has no HEVC encoder.
 */
public class VideoExporter {
    private static final String TAG = "VoocExport";
    private static final String AAC = "audio/mp4a-latm";

    private final String outPath;
    private final int width, height, fps, bitrate;
    private String mime;
    private final String audioPath;
    private final boolean withAudio;
    private final long limitBytes;
    // advanced encoder / timeline options
    private int keyframeSec = 1, bFrames = 0, profile = 0, colorStandard = 0;
    private boolean webm = false;
    private long startUs = 0, endUs = 0, fadeInUs = 0, fadeOutUs = 0;
    private String audioMime = AAC;

    private MediaCodec video;
    private MediaMuxer muxer;
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxerStarted = false;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private String codecName = "";

    private volatile MediaFormat audioFormat = null;
    private final ArrayList<byte[]> audioData = new ArrayList<>();
    private final ArrayList<long[]> audioMeta = new ArrayList<>();   // {ptsUs, flags}
    private volatile boolean audioReady = false;
    private volatile String audioError = "";
    private volatile float audioProgress = 0f;
    private volatile boolean cancelled = false;

    private String error = "";
    private long bytesWritten = 0;
    private long lastPtsUs = 0;
    private boolean limitReached = false;

    public VideoExporter(String outPath, int width, int height, int fps, int bitrate, String mime,
                         String audioPath, boolean withAudio, long limitBytes) {
        this.outPath = outPath;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.mime = mime;
        this.audioPath = audioPath;
        this.withAudio = withAudio && audioPath != null && audioPath.length() > 0;
        this.limitBytes = limitBytes;
    }

    /**
     * Full constructor. container 0 = MP4, 1 = WebM (audio becomes Opus).
     * colorStandard: MediaFormat.COLOR_STANDARD_* (0 = encoder default). profile 0 = default.
     * startUs / endUs trim the audio (endUs 0 = to the end); fades apply to re-encoded audio.
     */
    public VideoExporter(String outPath, int width, int height, int fps, int bitrate, String mime,
                         String audioPath, boolean withAudio, long limitBytes, int keyframeSec, int bFrames,
                         int profile, int colorStandard, int container, long startUs, long endUs,
                         long fadeInUs, long fadeOutUs) {
        this(outPath, width, height, fps, bitrate, mime, audioPath, withAudio, limitBytes);
        this.keyframeSec = Math.max(0, keyframeSec);
        this.bFrames = Math.max(0, bFrames);
        this.profile = profile;
        this.colorStandard = colorStandard;
        this.webm = container == 1;
        this.startUs = Math.max(0, startUs);
        this.endUs = Math.max(0, endUs);
        this.fadeInUs = Math.max(0, fadeInUs);
        this.fadeOutUs = Math.max(0, fadeOutUs);
        this.audioMime = webm ? "audio/opus" : AAC;
    }

    /** True when the device has an encoder for this MIME type (e.g. video/av01, video/hevc, audio/opus). */
    public static boolean hasEncoder(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Comma separated list of encoder names for a MIME type (diagnostics). */
    public static String encoderNames(String mime) {
        StringBuilder sb = new StringBuilder();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime)) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(ci.getName());
                    }
                }
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    public String getError() { return error; }
    public String getMime() { return mime; }
    public String getCodecName() { return codecName; }
    public long getBytesWritten() { return bytesWritten; }
    public boolean isLimitReached() { return limitReached; }
    public boolean isAudioReady() { return !withAudio || audioReady; }
    public String getAudioError() { return audioError; }
    public float getAudioProgress() { return audioProgress; }
    public void cancel() { cancelled = true; }

    // ------------------------------------------------------------------ codec selection
    private static MediaCodecInfo findEncoder(String mime, int w, int h) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo fallback = null;
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (!ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(mime)) continue;
                try {
                    MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(mime).getVideoCapabilities();
                    if (vc != null && vc.isSizeSupported(w, h)) {
                        return ci;
                    }
                } catch (Exception ignored) { }
                if (fallback == null) fallback = ci;
            }
        }
        return null;
    }

    /** Returns true when the encoder, muxer and (if needed) audio preparation started. */
    public boolean start() {
        try {
            MediaCodecInfo ci = findEncoder(mime, width, height);
            if (ci == null && !MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
                Log.w(TAG, mime + " encoder not available for " + width + "x" + height + ", using H.264");
                mime = MediaFormat.MIMETYPE_VIDEO_AVC;
                ci = findEncoder(mime, width, height);
            }
            if (ci == null) {
                error = "No video encoder supports " + width + "x" + height;
                return false;
            }
            codecName = ci.getName();
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSec);
            if (bFrames > 0 && Build.VERSION.SDK_INT >= 29) {
                fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, bFrames);
            }
            if (colorStandard > 0) {
                fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard);
                fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            video = MediaCodec.createByCodecName(codecName);
            boolean configured = false;
            if (profile > 0) {
                try {
                    MediaFormat pf = MediaFormat.createVideoFormat(mime, width, height);
                    pf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                    pf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    pf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                    pf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSec);
                    if (bFrames > 0 && Build.VERSION.SDK_INT >= 29) pf.setInteger(MediaFormat.KEY_MAX_B_FRAMES, bFrames);
                    if (colorStandard > 0) {
                        pf.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard);
                        pf.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                        pf.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                    }
                    pf.setInteger(MediaFormat.KEY_PROFILE, profile);
                    video.configure(pf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    configured = true;
                } catch (Throwable e) {
                    Log.w(TAG, "profile " + profile + " rejected, using encoder default: " + e);
                    video.release();
                    video = MediaCodec.createByCodecName(codecName);
                }
            }
            if (!configured) {
                try {
                    video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                } catch (Throwable e) {
                    // some encoders reject B-frames / color keys: retry with the basic format
                    Log.w(TAG, "advanced format rejected: " + e);
                    video.release();
                    video = MediaCodec.createByCodecName(codecName);
                    MediaFormat basic = MediaFormat.createVideoFormat(mime, width, height);
                    basic.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                    basic.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    basic.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                    basic.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Math.max(1, keyframeSec));
                    video.configure(basic, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                }
            }
            video.start();
            File parent = new File(outPath).getParentFile();
            if (parent != null) parent.mkdirs();
            muxer = new MediaMuxer(outPath, webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                                                 : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (withAudio) {
                Thread t = new Thread(new Runnable() {
                    public void run() { prepareAudio(); }
                }, "VoocExportAudio");
                t.start();
            }
            return true;
        } catch (Throwable e) {
            error = "Encoder start failed: " + e;
            Log.e(TAG, error);
            releaseQuietly();
            return false;
        }
    }

    // ------------------------------------------------------------------ audio
    private void prepareAudio() {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(audioPath);
            int track = -1;
            MediaFormat src = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; src = f; break; }
            }
            if (track < 0) throw new RuntimeException("no audio track");
            ex.selectTrack(track);
            long durUs = src.containsKey(MediaFormat.KEY_DURATION) ? src.getLong(MediaFormat.KEY_DURATION) : 0;
            String m = src.getString(MediaFormat.KEY_MIME);
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            boolean fades = fadeInUs > 0 || fadeOutUs > 0;
            if (audioMime.equals(m) && AAC.equals(m) && !fades) {
                copyAac(ex, src, durUs);
            } else {
                if (webm && !hasEncoder(audioMime)) throw new RuntimeException("no Opus encoder on this device (WebM audio)");
                transcode(ex, src, durUs);
            }
        } catch (Throwable e) {
            audioError = "Audio could not be added: " + e;
            Log.e(TAG, audioError);
            audioFormat = null;
        } finally {
            try { ex.release(); } catch (Exception ignored) { }
            audioProgress = 1f;
            audioReady = true;
        }
    }

    private void copyAac(MediaExtractor ex, MediaFormat src, long durUs) {
        int cap = src.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? src.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
        ByteBuffer buf = ByteBuffer.allocate(Math.max(cap, 256 * 1024));
        while (!cancelled) {
            buf.clear();
            int n = ex.readSampleData(buf, 0);
            if (n < 0) break;
            byte[] b = new byte[n];
            buf.position(0);
            buf.get(b, 0, n);
            long pts = ex.getSampleTime();
            if (endUs > 0 && pts > endUs) break;
            if (pts < startUs) { ex.advance(); continue; }
            pts -= startUs;
            int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            synchronized (audioData) {
                audioData.add(b);
                audioMeta.add(new long[]{pts, flags});
            }
            if (durUs > 0) audioProgress = Math.min(1f, pts / (float) durUs);
            ex.advance();
        }
        audioFormat = src;
    }

    /** Linear resampler for interleaved 16-bit PCM that keeps its phase between buffers. */
    private static final class Resampler {
        final int ch; final double step; double pos = 0; short[] last;
        Resampler(int inRate, int outRate, int ch) { this.ch = ch; this.step = inRate / (double) outRate; last = new short[ch]; }
        byte[] process(byte[] pcm) {
            ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder());
            int frames = pcm.length / (2 * ch);
            if (frames == 0) return new byte[0];
            int outMax = (int) ((frames - pos) / step) + 2;
            ByteBuffer out = ByteBuffer.allocate(Math.max(0, outMax) * 2 * ch).order(ByteOrder.nativeOrder());
            int n = 0;
            // pos is measured from the "last" sample (index -1) of the previous buffer
            while (pos < frames && n < outMax) {
                int i0 = (int) Math.floor(pos) - 1, i1 = i0 + 1;
                double f = pos - Math.floor(pos);
                for (int c = 0; c < ch; c++) {
                    int a = i0 < 0 ? last[c] : in.getShort((i0 * ch + c) * 2);
                    int b = in.getShort((i1 * ch + c) * 2);
                    out.putShort((short) Math.round(a + (b - a) * f));
                }
                n++;
                pos += step;
            }
            pos -= frames;
            for (int c = 0; c < ch; c++) last[c] = in.getShort(((frames - 1) * ch + c) * 2);
            byte[] r = new byte[n * 2 * ch];
            out.position(0);
            out.get(r);
            return r;
        }
    }

    private static boolean opusRate(int sr) {
        return sr == 8000 || sr == 12000 || sr == 16000 || sr == 24000 || sr == 48000;
    }

    /** Applies trim (frames before startUs / after endUs) and fades to 16-bit PCM in place. */
    private byte[] trimAndFade(byte[] pcm, long bufStartUs, int sr, int ch, long totalUs) {
        int frames = pcm.length / (2 * ch);
        int from = 0, to = frames;
        if (bufStartUs < startUs) from = (int) Math.min(frames, (startUs - bufStartUs) * sr / 1000000L);
        if (endUs > 0) {
            long room = (endUs - bufStartUs) * sr / 1000000L;
            to = (int) Math.max(from, Math.min(frames, room));
        }
        if (from >= to) return new byte[0];
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder());
        ByteBuffer out = ByteBuffer.allocate((to - from) * 2 * ch).order(ByteOrder.nativeOrder());
        for (int i = from; i < to; i++) {
            long t = bufStartUs + i * 1000000L / sr - startUs;    // time inside the exported clip
            float g = 1f;
            if (fadeInUs > 0 && t < fadeInUs) g = Math.max(0f, t / (float) fadeInUs);
            if (fadeOutUs > 0 && totalUs > 0 && t > totalUs - fadeOutUs) g = Math.min(g, Math.max(0f, (totalUs - t) / (float) fadeOutUs));
            for (int c = 0; c < ch; c++) {
                int v = in.getShort((i * ch + c) * 2);
                out.putShort((short) (v * g));
            }
        }
        return out.array();
    }

    private void transcode(MediaExtractor ex, MediaFormat src, long durUs) throws Exception {
        MediaCodec dec = MediaCodec.createDecoderByType(src.getString(MediaFormat.KEY_MIME));
        dec.configure(src, null, null, 0);
        dec.start();
        MediaCodec enc = null;
        MediaCodec.BufferInfo di = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo();
        int sr = src.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? src.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int ch = src.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? src.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        int pcmEncoding = 2;   // AudioFormat.ENCODING_PCM_16BIT
        long totalUs = (endUs > 0 ? endUs : durUs) - startUs;
        boolean inEos = false, decEos = false, encEos = false, encEosQueued = false;
        byte[] pending = null;
        int pendingOff = 0;
        long outFrames = 0;            // frames queued into the encoder (drives timestamps)
        int outRate = sr, outCh = 2;
        Resampler rs = null;
        long guard = 0;
        try {
            while (!encEos && !cancelled) {
                if (++guard > 50000000L) throw new RuntimeException("audio transcode stalled");
                if (!inEos) {
                    int i = dec.dequeueInputBuffer(2000);
                    if (i >= 0) {
                        ByteBuffer b = dec.getInputBuffer(i);
                        int n = ex.readSampleData(b, 0);
                        long t = n < 0 ? 0 : ex.getSampleTime();
                        if (n < 0 || (endUs > 0 && t > endUs + 200000L)) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(i, 0, n, t, 0);
                            if (durUs > 0) audioProgress = Math.min(0.99f, t / (float) durUs);
                            ex.advance();
                        }
                    }
                }
                if (pending == null && !decEos) {
                    int o = dec.dequeueOutputBuffer(di, 2000);
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = dec.getOutputFormat();
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (f.containsKey("pcm-encoding")) pcmEncoding = f.getInteger("pcm-encoding");
                    } else if (o >= 0) {
                        ByteBuffer ob = dec.getOutputBuffer(o);
                        byte[] pcm = new byte[0];
                        if (di.size > 0 && ob != null) {
                            ob.position(di.offset);
                            ob.limit(di.offset + di.size);
                            pcm = new byte[di.size];
                            ob.get(pcm);
                            if (pcmEncoding == 4) pcm = floatTo16(pcm);   // ENCODING_PCM_FLOAT
                        }
                        if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decEos = true;
                        if (endUs > 0 && di.presentationTimeUs > endUs) decEos = true;
                        if (ch > 2) pcm = downmix(pcm, ch);
                        int c2 = Math.min(2, Math.max(1, ch));
                        pcm = trimAndFade(pcm, di.presentationTimeUs, sr, c2, totalUs);
                        if (enc == null) {
                            outCh = c2;
                            outRate = (webm && !opusRate(sr)) ? 48000 : sr;
                        }
                        if (outRate != sr) {
                            if (rs == null) rs = new Resampler(sr, outRate, outCh);
                            pcm = rs.process(pcm);
                        }
                        pending = pcm;
                        pendingOff = 0;
                        dec.releaseOutputBuffer(o, false);
                    }
                }
                if (enc == null && (pending != null || decEos)) {
                    if (pending == null) outCh = Math.min(2, Math.max(1, ch));
                    if (webm && !opusRate(outRate)) outRate = 48000;
                    MediaFormat ef = MediaFormat.createAudioFormat(audioMime, outRate, outCh);
                    if (!webm) ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    ef.setInteger(MediaFormat.KEY_BIT_RATE, webm ? 128000 : (outCh == 1 ? 128000 : 192000));
                    ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024);
                    enc = MediaCodec.createEncoderByType(audioMime);
                    enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    enc.start();
                }
                if (enc != null && pending != null) {
                    int i = enc.dequeueInputBuffer(2000);
                    if (i >= 0) {
                        ByteBuffer b = enc.getInputBuffer(i);
                        b.clear();
                        int frameBytes = 2 * outCh;
                        int n = Math.min(b.remaining(), pending.length - pendingOff);
                        n -= n % frameBytes;
                        long pts = outFrames * 1000000L / outRate;
                        b.put(pending, pendingOff, n);
                        pendingOff += n;
                        outFrames += n / frameBytes;
                        boolean last = pendingOff >= pending.length - (pending.length % frameBytes);
                        int flags = (last && decEos) ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                        enc.queueInputBuffer(i, 0, n, pts, flags);
                        if (flags != 0) encEosQueued = true;
                        if (last) pending = null;
                    }
                } else if (enc != null && decEos && !encEosQueued) {
                    int i = enc.dequeueInputBuffer(2000);
                    if (i >= 0) {
                        enc.queueInputBuffer(i, 0, 0, outFrames * 1000000L / outRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encEosQueued = true;
                    }
                }
                if (enc != null) {
                    int o = enc.dequeueOutputBuffer(ei, 2000);
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioFormat = enc.getOutputFormat();
                    } else if (o >= 0) {
                        ByteBuffer ob = enc.getOutputBuffer(o);
                        if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && ei.size > 0 && ob != null) {
                            ob.position(ei.offset);
                            ob.limit(ei.offset + ei.size);
                            byte[] b = new byte[ei.size];
                            ob.get(b);
                            synchronized (audioData) {
                                audioData.add(b);
                                audioMeta.add(new long[]{ei.presentationTimeUs, ei.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME});
                            }
                        }
                        if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encEos = true;
                        enc.releaseOutputBuffer(o, false);
                    }
                } else if (decEos) {
                    break;   // decoder produced nothing
                }
            }
        } finally {
            try { dec.stop(); } catch (Exception ignored) { }
            dec.release();
            if (enc != null) {
                try { enc.stop(); } catch (Exception ignored) { }
                enc.release();
            }
        }
        if (audioFormat == null) throw new RuntimeException("audio encoder produced no format");
    }

    private static byte[] floatTo16(byte[] pcm) {
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder());
        int n = pcm.length / 4;
        ByteBuffer out = ByteBuffer.allocate(n * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < n; i++) {
            float f = Math.max(-1f, Math.min(1f, in.getFloat(i * 4)));
            out.putShort((short) (f * 32767f));
        }
        return out.array();
    }

    private static byte[] downmix(byte[] pcm, int ch) {
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder());
        int frames = pcm.length / (2 * ch);
        ByteBuffer out = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            int l = in.getShort((i * ch) * 2);
            int r = in.getShort((i * ch + 1) * 2);
            out.putShort((short) l);
            out.putShort((short) r);
        }
        return out.array();
    }

    // ------------------------------------------------------------------ video
    private void startMuxer() {
        videoTrack = muxer.addTrack(video.getOutputFormat());
        if (withAudio && audioFormat != null) {
            audioTrack = muxer.addTrack(audioFormat);
        }
        muxer.start();
        muxerStarted = true;
    }

    private void writeAudioUpTo(long ptsUs) {
        if (audioTrack < 0) return;
        MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
        synchronized (audioData) {
            while (!audioData.isEmpty()) {
                long[] meta = audioMeta.get(0);
                if (meta[0] > ptsUs) break;
                byte[] b = audioData.remove(0);
                audioMeta.remove(0);
                ai.set(0, b.length, meta[0], (int) meta[1]);
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(b), ai);
                bytesWritten += b.length;
            }
        }
    }

    private void drain(boolean endOfStream) {
        int tries = 0;
        while (true) {
            int o = video.dequeueOutputBuffer(info, endOfStream ? 10000 : 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++tries > 300) break;
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) startMuxer();
            } else if (o >= 0) {
                ByteBuffer ob = video.getOutputBuffer(o);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && ob != null) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    // interleave: audio up to this video timestamp first
                    writeAudioUpTo(info.presentationTimeUs);
                    muxer.writeSampleData(videoTrack, ob, info);
                    bytesWritten += info.size;
                    if (limitBytes > 0 && bytesWritten >= limitBytes) limitReached = true;
                }
                video.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private static void copyPlane(Image.Plane plane, byte[] src, int off, int w, int h, int srcStride) {
        ByteBuffer b = plane.getBuffer();
        int rs = plane.getRowStride();
        int ps = plane.getPixelStride();
        if (ps == 1) {
            for (int y = 0; y < h; y++) {
                b.position(y * rs);
                b.put(src, off + y * srcStride, w);
            }
        } else {
            for (int y = 0; y < h; y++) {
                int row = y * rs;
                int s = off + y * srcStride;
                for (int x = 0; x < w; x++) {
                    b.put(row + x * ps, src[s + x]);
                }
            }
        }
    }

    /** One I420 frame (Y plane, then U, then V). Returns false on error. */
    public boolean writeFrame(byte[] i420, long ptsUs) {
        if (video == null) return false;
        try {
            int idx = -1;
            for (int i = 0; i < 400 && idx < 0; i++) {
                idx = video.dequeueInputBuffer(5000);
                if (idx < 0) drain(false);
            }
            if (idx < 0) {
                error = "Encoder is not accepting frames";
                return false;
            }
            int ySize = width * height;
            int cw = width / 2, chh = height / 2;
            Image img = null;
            try { img = video.getInputImage(idx); } catch (Exception ignored) { }
            if (img != null) {
                Image.Plane[] p = img.getPlanes();
                copyPlane(p[0], i420, 0, width, height, width);
                copyPlane(p[1], i420, ySize, cw, chh, cw);
                copyPlane(p[2], i420, ySize + cw * chh, cw, chh, cw);
            } else {
                // no Image API: assume NV12 (semi-planar), the most common layout
                ByteBuffer b = video.getInputBuffer(idx);
                b.clear();
                b.put(i420, 0, ySize);
                int u = ySize, v = ySize + cw * chh;
                for (int k = 0; k < cw * chh; k++) {
                    b.put(i420[u + k]);
                    b.put(i420[v + k]);
                }
            }
            video.queueInputBuffer(idx, 0, ySize * 3 / 2, ptsUs, 0);
            lastPtsUs = ptsUs;
            drain(false);
            return true;
        } catch (Throwable e) {
            error = "Frame encode failed: " + e;
            Log.e(TAG, error);
            return false;
        }
    }

    /** Flush the encoder, write the remaining audio and close the MP4. */
    public boolean finish() {
        boolean ok = true;
        try {
            if (video != null) {
                int idx = -1;
                for (int i = 0; i < 200 && idx < 0; i++) {
                    idx = video.dequeueInputBuffer(10000);
                    if (idx < 0) drain(false);
                }
                if (idx >= 0) {
                    video.queueInputBuffer(idx, 0, 0, lastPtsUs + 1000000L / Math.max(1, fps),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drain(true);
                if (muxerStarted) writeAudioUpTo(lastPtsUs + 1000000L / Math.max(1, fps));
            }
            if (!muxerStarted) {
                ok = false;
                if (error.length() == 0) error = "No frames were encoded";
            }
        } catch (Throwable e) {
            ok = false;
            error = "Finishing the video failed: " + e;
            Log.e(TAG, error);
        }
        releaseQuietly();
        return ok;
    }

    private void releaseQuietly() {
        try { if (video != null) { video.stop(); } } catch (Exception ignored) { }
        try { if (video != null) { video.release(); } } catch (Exception ignored) { }
        video = null;
        try { if (muxer != null && muxerStarted) { muxer.stop(); } } catch (Exception e) { Log.e(TAG, "muxer stop " + e); }
        try { if (muxer != null) { muxer.release(); } } catch (Exception ignored) { }
        muxer = null;
        cancelled = true;
    }
}
