package com.jhaa2k.vooc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.util.Base64;
import android.util.Log;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Decodes an animated GIF into RGBA frames for the IMAGE layer.
 *
 * Uses android.graphics.Movie (available on every API level) to render the
 * GIF at fixed time steps. Frames are scaled so the longest side is at most
 * {@code maxSide} pixels and returned as base64 RGBA (top row first).
 */
public class GifFrames {
    private int width = 0, height = 0, count = 0, durationMs = 0, stepMs = 50;
    private String[] frames = new String[0];
    private String error = "";

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getCount() { return count; }
    public int getDurationMs() { return durationMs; }
    public int getStepMs() { return stepMs; }
    public String getError() { return error; }
    public String getFrame(int i) { return (i >= 0 && i < count) ? frames[i] : null; }

    public boolean decode(String path, int maxFrames, int maxSide) {
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] data = readAll(in);
            Movie movie = Movie.decodeByteArray(data, 0, data.length);
            if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
                error = "not an animated image";
                return false;
            }
            int mw = movie.width(), mh = movie.height();
            float scale = Math.min(1f, (float) maxSide / Math.max(mw, mh));
            width = Math.max(1, Math.round(mw * scale));
            height = Math.max(1, Math.round(mh * scale));
            durationMs = Math.max(0, movie.duration());
            int n = durationMs > 0 ? Math.max(1, Math.min(maxFrames, durationMs / 40)) : 1;
            stepMs = durationMs > 0 ? Math.max(20, durationMs / n) : 100;
            frames = new String[n];
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.scale(scale, scale);
            ByteBuffer buf = ByteBuffer.allocate(width * height * 4);
            for (int i = 0; i < n; i++) {
                bmp.eraseColor(0);
                movie.setTime(Math.min(durationMs, i * stepMs));
                movie.draw(canvas, 0, 0);
                buf.rewind();
                bmp.copyPixelsToBuffer(buf);       // RGBA byte order, premultiplied
                frames[i] = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
            }
            count = n;
            bmp.recycle();
            return true;
        } catch (Throwable t) {
            error = String.valueOf(t);
            Log.w("VoocGif", "decode failed: " + error);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int r;
        while ((r = in.read(b)) > 0) out.write(b, 0, r);
        return out.toByteArray();
    }
}
