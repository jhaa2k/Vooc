package com.vooc.visualizer.export

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.vooc.visualizer.studio.StudioProject
import com.vooc.visualizer.studio.LayerKind

/** Encodes H.264 video and muxes a compatible AAC source track into one MP4 timeline. */
object VideoExportEngine {
    data class Result(val frames: Int, val durationMs: Long, val audioMuxed: Boolean)

    fun export(resolver: ContentResolver, project: StudioProject, destination: Uri, onProgress: (Float) -> Unit = {}): Result {
        val width = project.width.coerceIn(320, 3840)
        val height = project.height.coerceIn(320, 3840)
        val fps = project.fps.coerceIn(15, 60)
        val audioUri = project.document.audioUri.takeIf { project.document.export.includeAudio && it.isNotBlank() }?.let(Uri::parse)
        val extractor = audioUri?.let { uri ->
            val afd = resolver.openAssetFileDescriptor(uri, "r") ?: throw IllegalStateException("Cannot open selected music.")
            MediaExtractor().apply { setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
                .also { afd.close() }
        }
        var sourceAudioTrack = -1
        var audioFormat: MediaFormat? = null
        if (extractor != null) {
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    sourceAudioTrack = i
                    audioFormat = candidate
                    break
                }
            }
            if (sourceAudioTrack < 0) {
                extractor.release()
                throw IllegalStateException("Selected music has no readable audio track.")
            }
            if (audioFormat?.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") {
                extractor.release()
                throw IllegalStateException("For this export build, select an AAC/M4A audio file so its audio can be muxed without quality loss.")
            }
            extractor.selectTrack(sourceAudioTrack)
        }
        val trimStartUs = project.document.trimStartMs.coerceAtLeast(0L) * 1000L
        val sourceDurationUs = audioFormat?.let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L } ?: 0L
        val sourceDurationMs = ((sourceDurationUs - trimStartUs).coerceAtLeast(0L)) / 1000L
        val trimDuration = project.document.trimEndMs.takeIf { it > project.document.trimStartMs }?.minus(project.document.trimStartMs)
        val durationMs = min(project.document.durationMs.takeIf { it > 0L } ?: sourceDurationMs.takeIf { it > 0L } ?: 10_000L,
            trimDuration ?: Long.MAX_VALUE).coerceIn(1000L, 300_000L)
        val frameCount = (durationMs * fps / 1000L).toInt().coerceAtLeast(1)
        val output = resolver.openFileDescriptor(destination, "w") ?: throw IllegalStateException("Cannot open destination file.")
        val muxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val info = MediaCodec.BufferInfo()
        var audioTrack = -1
        var videoTrack = -1
        var muxerStarted = false
        var audioDone = extractor == null
        var submittedFrames = 0
        try {
            if (extractor != null) {
                audioTrack = muxer.addTrack(audioFormat!!)
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                setInteger(MediaFormat.KEY_BIT_RATE, project.document.export.videoBitrate.coerceIn(1_000_000, 24_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = encoder.getInputBuffer(inputIndex) ?: throw IllegalStateException("H.264 input buffer unavailable.")
                        if (submittedFrames < frameCount) {
                            val timeMs = submittedFrames * 1000L / fps
                            drawFrame(canvas, paint, project, width, height, timeMs)
                            input.clear()
                            writeYuv420(bitmap, input)
                            encoder.queueInputBuffer(inputIndex, 0, input.position(), submittedFrames * 1_000_000L / fps, 0)
                            submittedFrames++
                            onProgress(submittedFrames.toFloat() / frameCount * 0.94f)
                        } else {
                            encoder.queueInputBuffer(inputIndex, 0, 0, frameCount * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        }
                    }
                }
                when (val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outputIndex >= 0) {
                        val encoded = encoder.getOutputBuffer(outputIndex)
                        if (encoded != null && info.size > 0 && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, encoded, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (eos) outputEnded = true
                    }
                }
                if (muxerStarted && extractor != null && !audioDone) {
                    val videoClockUs = submittedFrames * 1_000_000L / fps
                    audioDone = copyAudioUntil(extractor, muxer, audioTrack, trimStartUs, videoClockUs, false)
                }
            }
            if (!muxerStarted) throw IllegalStateException("This device could not initialize its H.264 encoder.")
            if (extractor != null && !audioDone) {
                copyAudioUntil(extractor, muxer, audioTrack, trimStartUs, durationMs * 1000L, true)
            }
            onProgress(1f)
            return Result(submittedFrames, durationMs, extractor != null)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            bitmap.recycle()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            extractor?.release()
            output.close()
        }
    }

    /** Copies AAC packets up to the video clock. Returns true when the source is exhausted or trimmed. */
    private fun copyAudioUntil(extractor: MediaExtractor, muxer: MediaMuxer, track: Int, trimStartUs: Long, untilUs: Long, drain: Boolean): Boolean {
        val buffer = ByteBuffer.allocateDirect(512 * 1024)
        while (true) {
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0L) return true
            val relativeTime = sampleTime - trimStartUs
            if (relativeTime < 0L) {
                if (!extractor.advance()) return true
                continue
            }
            if (relativeTime > untilUs) return false
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return true
            val sampleInfo = MediaCodec.BufferInfo().apply { set(0, size, relativeTime, extractor.sampleFlags) }
            buffer.position(0)
            buffer.limit(size)
            muxer.writeSampleData(track, buffer, sampleInfo)
            if (!extractor.advance()) return true
        }
    }

    private fun drawFrame(canvas: Canvas, paint: Paint, project: StudioProject, width: Int, height: Int, timeMs: Long) {
        canvas.drawColor((project.document.backgroundArgb or (0xFFL shl 24)).toInt())
        val seconds = timeMs / 1000f
        project.layers.filter { it.enabled }.forEach { layer ->
            val color = layer.color.toInt()
            paint.color = color
            paint.alpha = (layer.opacity.coerceIn(0f, 100f) * 2.55f).toInt()
            paint.style = Paint.Style.FILL
            val cx = width * (0.5f + layer.x / 200f)
            val cy = height * (0.5f + layer.y / 200f)
            val scale = layer.scale.coerceIn(1f, 400f) / 100f
            val count = layer.barCount.coerceIn(8, 256)
            val pulse = 0.12f + 0.38f * (0.5f + 0.5f * sin(seconds * (2.2f + layer.sensitivity / 100f) * 2f * PI.toFloat()))
            canvas.save()
            canvas.rotate(layer.rotation, cx, cy)
            when (layer.kind) {
                LayerKind.BACKGROUND -> canvas.drawColor(color)
                LayerKind.BARS, LayerKind.BARSH, LayerKind.BLOCKS, LayerKind.GRID -> {
                    val gap = layer.spacing.coerceAtLeast(0f) * width / 1080f
                    val bw = ((width * 0.82f - gap * (count - 1)) / count).coerceAtLeast(1f)
                    val baseY = cy + height * 0.34f * scale
                    for (i in 0 until count) {
                        val amplitude = 0.18f + 0.82f * abs(sin(i * 0.21f + seconds * 3.2f)) * pulse
                        val barHeight = height * 0.42f * amplitude * scale
                        val x = cx - (bw * count + gap * (count - 1)) / 2f + i * (bw + gap)
                        if (layer.kind == LayerKind.BARSH) canvas.drawRect(x, cy - barHeight / 2f, x + bw, cy + barHeight / 2f, paint)
                        else canvas.drawRect(x, baseY - barHeight, x + bw, baseY, paint)
                    }
                }
                LayerKind.CIRCLE, LayerKind.RING, LayerKind.MULTIRING, LayerKind.CONCENTRIC, LayerKind.PULSE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = layer.thickness.coerceAtLeast(1f) * width / 1080f
                    val radius = min(width, height) * 0.2f * scale
                    val rings = if (layer.kind == LayerKind.MULTIRING || layer.kind == LayerKind.CONCENTRIC) 4 else 1
                    repeat(rings) { index -> canvas.drawCircle(cx, cy, radius * (1f + index * 0.18f) * (1f + pulse * 0.08f), paint) }
                    paint.style = Paint.Style.FILL
                }
                LayerKind.WAVE, LayerKind.WAVEC, LayerKind.SCOPE, LayerKind.LINES -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = layer.thickness.coerceAtLeast(1f) * width / 1080f
                    val path = android.graphics.Path()
                    for (i in 0..240) {
                        val x = width * 0.08f + width * 0.84f * i / 240f
                        val y = cy + sin(i * 0.075f + seconds * 4f) * height * 0.12f * pulse * scale
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                }
                LayerKind.DOTS, LayerKind.PARTICLES -> {
                    val dots = layer.particleCount.coerceIn(4, 256)
                    repeat(dots) { i ->
                        val angle = i * (2f * PI.toFloat() / dots) + seconds * 0.35f
                        val radius = min(width, height) * (0.12f + 0.06f * abs(sin(i * 1.7f + seconds * 2f))) * scale
                        canvas.drawCircle(cx + cos(angle) * radius, cy + sin(angle) * radius, max(1f, width / 320f), paint)
                    }
                }
                LayerKind.TEXT -> {
                    paint.textSize = min(width, height) * 0.1f * scale
                    paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(layer.textContent.take(80), cx, cy, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
                LayerKind.SHAPE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = layer.thickness.coerceAtLeast(1f) * width / 1080f
                    val radius = min(width, height) * 0.18f * scale
                    canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, paint)
                    paint.style = Paint.Style.FILL
                }
                LayerKind.IMAGE -> Unit // image URI rendering is added in the asset decoder stage
            }
            canvas.restore()
        }
    }

    private fun writeYuv420(bitmap: Bitmap, output: ByteBuffer) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val frameSize = width * height
        val chromaSize = frameSize / 4
        if (output.remaining() < frameSize + chromaSize * 2) throw IllegalStateException("Encoder input buffer is too small for this resolution.")
        val yPlane = ByteArray(frameSize)
        val uPlane = ByteArray(chromaSize)
        val vPlane = ByteArray(chromaSize)
        var yi = 0
        var ui = 0
        var vi = 0
        for (row in 0 until height) for (col in 0 until width) {
            val argb = pixels[row * width + col]
            val r = argb shr 16 and 0xFF
            val g = argb shr 8 and 0xFF
            val b = argb and 0xFF
            yPlane[yi++] = ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)).toByte()
            if (row % 2 == 0 && col % 2 == 0) {
                uPlane[ui++] = ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
                vPlane[vi++] = ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
            }
        }
        output.put(yPlane)
        output.put(uPlane)
        output.put(vPlane)
    }
}
