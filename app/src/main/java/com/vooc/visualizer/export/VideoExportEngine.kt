package com.vooc.visualizer.export

import android.content.ContentResolver
import android.content.Context
import com.vooc.visualizer.analyzer.decode.PcmDecoder
import com.vooc.visualizer.analyzer.AudioAnalyzer
import com.vooc.visualizer.analyzer.AnalyzerConfig
import com.vooc.visualizer.analyzer.FftSize
import com.vooc.visualizer.analyzer.AudioProviderProcessor
import com.vooc.visualizer.analyzer.ChannelSelect
import com.vooc.visualizer.analyzer.SpectrumBarsRuntime
import com.vooc.visualizer.analyzer.toAudioProviderConfig
import com.vooc.visualizer.analyzer.applyTo
import com.vooc.visualizer.studio.ElementPropertyCatalog
import com.vooc.visualizer.studio.ElementValue
import com.vooc.visualizer.studio.StudioElementType
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.DashPathEffect
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow
import com.vooc.visualizer.studio.StudioProject
import com.vooc.visualizer.studio.LayerKind
import com.vooc.visualizer.studio.MeasureWhatEngine
import com.vooc.visualizer.studio.AudioReactivityRuntime
import com.vooc.visualizer.studio.audioReactivitySources
import com.vooc.visualizer.studio.audioReactivityColor
import com.vooc.visualizer.studio.processBarSpectrum
import com.vooc.visualizer.studio.audioReactivityTransform

/** Encodes H.264 video and muxes a compatible AAC source track into one MP4 timeline. */
object VideoExportEngine {
    data class Result(val frames: Int, val durationMs: Long, val audioMuxed: Boolean)

    fun export(context: Context, resolver: ContentResolver, project: StudioProject, destination: Uri, onProgress: (Float) -> Unit = {}): Result {
        val width = (project.width.coerceIn(320, 3840) / 2) * 2
        val height = (project.height.coerceIn(320, 3840) / 2) * 2
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
        // Decode audio once for deterministic offline spectrum analysis. The original AAC
        // packets are muxed untouched below; analysis only drives rendered visual frames.
        val audioEnvelope = FloatArray(frameCount)
        val providerElement = project.document.elements.firstOrNull {
            it.type == StudioElementType.AUDIO_PROVIDER && it.visible
        } ?: ElementPropertyCatalog.create(StudioElementType.AUDIO_PROVIDER, "audio-provider-export-default")
        val providerConfig = providerElement.toAudioProviderConfig()
        val needsStereoReactivity = project.document.elements.any { element ->
            val source = (element.value("Audio Reactivity Source") as? ElementValue.Text)?.value
            (element.value("Audio Reactivity Enabled") as? ElementValue.Bool)?.value == true &&
                (source == "Left Channel" || source == "Right Channel")
        }
        val providerSamplesByFrame = Array(frameCount) { FloatArray(providerConfig.sampleOutCount) }
        val providerBeatByFrame = FloatArray(frameCount)
        val leftLevelByFrame = FloatArray(frameCount)
        val rightLevelByFrame = FloatArray(frameCount)
        val waveformLevelByFrame = FloatArray(frameCount)
        // Keep the analyzer's measured tempo alongside each offline visual frame.
        val providerBpmByFrame = FloatArray(frameCount) { 120f }
        val providerFrameAssigned = BooleanArray(frameCount)
        if (audioUri != null) {
            PcmDecoder(context, audioUri).use { decoder ->
                val stream = decoder.open()
                val energy = DoubleArray(frameCount)
                val weights = IntArray(frameCount)
                val offlineAnalyzer = AudioAnalyzer(
                    stream.sampleRate,
                    providerConfig.applyTo(AnalyzerConfig(fftSize = FftSize.fromSize(providerConfig.fftSize))).let { config ->
                        if (needsStereoReactivity) config.copy(channelMode = com.vooc.visualizer.analyzer.ChannelMode.STEREO_SPLIT) else config
                    }
                )
                val providerProcessor = AudioProviderProcessor()
                decoder.decode { samples, frames, ptsUs ->
                    if (frames > 0) {
                        offlineAnalyzer.pushInterleaved(samples, frames, stream.channels)
                        val chunkDurationUs = frames * 1_000_000L / stream.sampleRate
                        val endTimeSec = (ptsUs + chunkDurationUs) / 1_000_000.0
                        val analysis = offlineAnalyzer.analyzeLive(endTimeSec, alignToClock = false)
                        val shaped = providerProcessor.process(analysis, providerConfig)
                        val relativeStartUs = ptsUs - trimStartUs
                        val relativeEndUs = ptsUs + chunkDurationUs - trimStartUs
                        if (relativeEndUs >= 0L) {
                            val firstFrame = ((relativeStartUs.coerceAtLeast(0L) * fps) / 1_000_000L).toInt().coerceIn(0, frameCount - 1)
                            val lastFrame = ((relativeEndUs.coerceAtLeast(0L) * fps) / 1_000_000L).toInt().coerceIn(0, frameCount - 1)
                            for (frameIndex in firstFrame..lastFrame) {
                                shaped.samples.copyInto(providerSamplesByFrame[frameIndex])
                                providerBeatByFrame[frameIndex] = shaped.beatValue
                                providerBpmByFrame[frameIndex] = analysis.beat.bpm.takeIf { it in 40f..240f } ?: 120f
                                leftLevelByFrame[frameIndex] = analysis.channel(ChannelSelect.LEFT).rms
                                rightLevelByFrame[frameIndex] = analysis.channel(ChannelSelect.RIGHT).rms
                                waveformLevelByFrame[frameIndex] = if (analysis.waveform.isEmpty()) analysis.rms else kotlin.math.sqrt(
                                    analysis.waveform.sumOf { sample -> sample.toDouble() * sample.toDouble() } / analysis.waveform.size
                                ).toFloat()
                                providerFrameAssigned[frameIndex] = true
                            }
                        }
                        val relativeUs = ptsUs - trimStartUs
                        if (relativeUs >= 0L) {
                            val index = ((relativeUs * fps) / 1_000_000L).toInt()
                            if (index in 0 until frameCount) {
                                var sum = 0.0
                                val sampleCount = frames * stream.channels
                                for (i in 0 until sampleCount.coerceAtMost(samples.size)) {
                                    val v = samples[i].toDouble()
                                    sum += v * v
                                }
                                val rms = kotlin.math.sqrt(sum / sampleCount.coerceAtLeast(1)).toFloat()
                                energy[index] += rms.toDouble()
                                weights[index]++
                            }
                        }
                    }
                    true
                }
                var last = 0f
                for (i in audioEnvelope.indices) {
                    if (weights[i] > 0) last = (energy[i] / weights[i]).toFloat()
                    audioEnvelope[i] = last.coerceIn(0f, 1f)
                }
                var lastSamples: FloatArray? = null
                var lastBeat = 0f
                var lastBpm = 120f
                var lastLeft = 0f
                var lastRight = 0f
                var lastWaveform = 0f
                for (i in providerSamplesByFrame.indices) {
                    if (providerFrameAssigned[i]) {
                        lastSamples = providerSamplesByFrame[i]
                        lastBeat = providerBeatByFrame[i]
                        lastBpm = providerBpmByFrame[i]
                        lastLeft = leftLevelByFrame[i]
                        lastRight = rightLevelByFrame[i]
                        lastWaveform = waveformLevelByFrame[i]
                    } else if (lastSamples != null) {
                        lastSamples.copyInto(providerSamplesByFrame[i])
                        providerBeatByFrame[i] = lastBeat
                        providerBpmByFrame[i] = lastBpm
                        leftLevelByFrame[i] = lastLeft
                        rightLevelByFrame[i] = lastRight
                        waveformLevelByFrame[i] = lastWaveform
                    }
                }
                val firstAssigned = providerFrameAssigned.indexOfFirst { it }
                if (firstAssigned > 0) {
                    for (i in 0 until firstAssigned) {
                        providerSamplesByFrame[firstAssigned].copyInto(providerSamplesByFrame[i])
                        providerBeatByFrame[i] = providerBeatByFrame[firstAssigned]
                        providerBpmByFrame[i] = providerBpmByFrame[firstAssigned]
                        leftLevelByFrame[i] = leftLevelByFrame[firstAssigned]
                        rightLevelByFrame[i] = rightLevelByFrame[firstAssigned]
                        waveformLevelByFrame[i] = waveformLevelByFrame[firstAssigned]
                    }
                }
            }
        }
        val output = resolver.openFileDescriptor(destination, "w") ?: throw IllegalStateException("Cannot open destination file.")
        val muxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val imageCache = mutableMapOf<String, Bitmap>()
        val barRuntimes = mutableMapOf<String, SpectrumBarsRuntime>()
        val barLastUpdateMs = mutableMapOf<String, Long>()
        val audioReactivityRuntimes = mutableMapOf<String, AudioReactivityRuntime>()
        project.layers.filter { it.kind == LayerKind.IMAGE && it.imageUri.isNotBlank() }.forEach { layer ->
            runCatching {
                resolver.openInputStream(Uri.parse(layer.imageUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { imageCache[layer.imageUri] = it }
                }
            }
        }
        project.document.elements.filter { it.type in setOf(StudioElementType.IMAGE, StudioElementType.BARS_SEGMENTS, StudioElementType.PARTICLES, StudioElementType.RGB_SPLIT, StudioElementType.BLUR_EFFECT, StudioElementType.MOTION_BLUR_EFFECT) }.forEach { element ->
            val uriText = (if (element.type == StudioElementType.IMAGE) listOf("Import Image", "Custom Image", "Image Source / File") else listOf("Custom Bar Image", "Custom Image", "Image Source / File", "Target Image"))
                .firstNotNullOfOrNull { key -> (element.value(key) as? ElementValue.Text)?.value?.takeIf { it.isNotBlank() } }.orEmpty()
            if (uriText.isNotBlank()) runCatching {
                resolver.openInputStream(Uri.parse(uriText))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { source ->
                        if (element.type == StudioElementType.IMAGE) {
                            val maskUri = (element.value("Mask Image") as? ElementValue.Text)?.value.orEmpty()
                            val maskBitmap = if (maskUri.isNotBlank()) {
                                resolver.openInputStream(Uri.parse(maskUri))?.use { BitmapFactory.decodeStream(it) }
                            } else null
                            val processed = processImageForExport(source, element, maskBitmap)
                            imageCache["${uriText}#${element.id}"] = processed
                            imageCache[uriText] = source
                            maskBitmap?.recycle()
                        } else if (element.type == StudioElementType.BLUR_EFFECT) {
                            val blurred = processBlurEffectForExport(source, element)
                            imageCache["${uriText}#${element.id}"] = blurred
                            imageCache[uriText] = source
                        } else if (element.type == StudioElementType.MOTION_BLUR_EFFECT || element.type == StudioElementType.RGB_SPLIT) {
                            val maskUri = (element.value("Mask Image") as? ElementValue.Text)?.value.orEmpty()
                            val maskBitmap = if (maskUri.isNotBlank()) {
                                resolver.openInputStream(Uri.parse(maskUri))?.use { BitmapFactory.decodeStream(it) }
                            } else null
                            val processed = processImageForExport(source, element, maskBitmap)
                            imageCache["${uriText}#${element.id}"] = processed
                            imageCache[uriText] = source
                            maskBitmap?.recycle()
                        } else imageCache[uriText] = source
                    }
                }
            }
        }
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
                            drawFrame(context, canvas, paint, project, width, height, timeMs, imageCache, barRuntimes, barLastUpdateMs, audioReactivityRuntimes, audioEnvelope.getOrElse(submittedFrames) { 0f }, providerSamplesByFrame.getOrNull(submittedFrames), providerBeatByFrame.getOrElse(submittedFrames) { 0f }, providerBpmByFrame.getOrElse(submittedFrames) { 120f }, leftLevelByFrame.getOrElse(submittedFrames) { 0f }, rightLevelByFrame.getOrElse(submittedFrames) { 0f }, waveformLevelByFrame.getOrElse(submittedFrames) { 0f })
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
            imageCache.values.forEach { runCatching { it.recycle() } }
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

    private fun processBlurEffectForExport(source: Bitmap, element: com.vooc.visualizer.studio.ElementDocument): Bitmap {
        fun number(name: String, fallback: Float): Float = when (val value = element.value(name)) {
            is ElementValue.Number -> value.value.toFloat()
            is ElementValue.Integer -> value.value.toFloat()
            else -> fallback
        }
        fun text(name: String, fallback: String): String = (element.value(name) as? ElementValue.Text)?.value ?: fallback
        fun vector(name: String): ElementValue.Vector2 =
            element.value(name) as? ElementValue.Vector2 ?: ElementValue.Vector2(1.0, 1.0)
        val radius = (number("Blur Radius", 2f) * number("Blur Multiplier", 4.1f)).coerceIn(0f, 512f)
        val blurred = if (radius <= 0.1f) source else {
            val reduction = (1f / (1f + radius / 3f)).coerceIn(0.05f, 1f)
            val reduced = Bitmap.createScaledBitmap(source,
                (source.width * reduction).toInt().coerceAtLeast(1),
                (source.height * reduction).toInt().coerceAtLeast(1), true)
            val result = Bitmap.createScaledBitmap(reduced, source.width, source.height, true)
            if (reduced !== source) reduced.recycle()
            result
        }
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val mode = when (text("Blend Mode Content", "Alpha")) {
            "Add" -> android.graphics.PorterDuff.Mode.ADD
            "Screen" -> android.graphics.PorterDuff.Mode.SCREEN
            "Multiply", "Subtractive" -> android.graphics.PorterDuff.Mode.MULTIPLY
            "Darken" -> android.graphics.PorterDuff.Mode.DARKEN
            "Lighten" -> android.graphics.PorterDuff.Mode.LIGHTEN
            "Exclusion" -> android.graphics.PorterDuff.Mode.XOR
            "Copy" -> android.graphics.PorterDuff.Mode.SRC
            else -> android.graphics.PorterDuff.Mode.SRC_OVER
        }
        for (index in 1..3) {
            val scale = vector("${index}layer Scale")
            val sx = scale.x.toFloat().coerceIn(0f, 10f)
            val sy = scale.y.toFloat().coerceIn(0f, 10f)
            if (sx <= 0f || sy <= 0f) continue
            val color = runCatching { android.graphics.Color.parseColor(text("${index}layer Color", "#FFFFFFFF")) }
                .getOrDefault(android.graphics.Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                if (color != android.graphics.Color.WHITE) {
                    colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY)
                }
                if (mode != android.graphics.PorterDuff.Mode.SRC_OVER) xfermode = android.graphics.PorterDuffXfermode(mode)
            }
            val halfW = source.width * sx / 2f
            val halfH = source.height * sy / 2f
            canvas.drawBitmap(blurred, null, RectF(
                source.width / 2f - halfW, source.height / 2f - halfH,
                source.width / 2f + halfW, source.height / 2f + halfH
            ), paint)
        }
        if (blurred !== source) blurred.recycle()
        return output
    }

    private fun processImageForExport(source: Bitmap, element: com.vooc.visualizer.studio.ElementDocument, maskSource: Bitmap? = null): Bitmap {
        fun number(name: String, fallback: Float): Float = when (val value = element.value(name)) {
            is ElementValue.Number -> value.value.toFloat()
            is ElementValue.Integer -> value.value.toFloat()
            else -> fallback
        }
        fun text(name: String, fallback: String = ""): String = (element.value(name) as? ElementValue.Text)?.value ?: fallback
        fun enabled(name: String): Boolean = (element.value(name) as? ElementValue.Bool)?.value == true
        val blurRadius = if (enabled("Blur Enabled")) number("Blur Radius", 0f).coerceIn(0f, 128f) else 0f
        val blurDivider = number("Blur Divider", 2f).coerceIn(1f, 32f)
        val edgeBlur = number("Edge Blur", 0f).coerceIn(0f, 128f)
        val blurredBorder = enabled("Blurred Border")
        val keyEnabled = enabled("Color Key Enabled")
        val brightness = number("Image Brightness", 1f).coerceIn(0f, 4f)
        val contrast = number("Image Contrast", 1f).coerceIn(0f, 4f)
        val hueShift = number("Image Hue", 0f).coerceIn(-360f, 360f)
        val gamma = number("Image Gamma", 1f).coerceIn(0.1f, 4f)
        val temperature = number("Image Color Temperature", 6500f).coerceIn(1000f, 12000f)
        val warm = ((temperature - 6500f) / 5500f).coerceIn(-1f, 1f)
        val adjustImageColor = brightness != 1f || contrast != 1f || hueShift != 0f || gamma != 1f || temperature != 6500f
        if (blurRadius <= 0f && edgeBlur <= 0f && !keyEnabled && maskSource == null) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val w = output.width
        val h = output.height
        val pixels = IntArray(w * h)
        output.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = maskSource?.let { if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true) }
        val maskPixels = mask?.let { IntArray(w * h).also { values -> it.getPixels(values, 0, w, 0, 0, w, h) } }
        val maskMode = (element.value("Mask Mode") as? ElementValue.Text)?.value ?: "Alpha Mask"
        val keyColor = if (enabled("Auto Detect Color Key")) {
            val samples = intArrayOf(pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1])
            android.graphics.Color.rgb(
                samples.map { android.graphics.Color.red(it) }.average().toInt(),
                samples.map { android.graphics.Color.green(it) }.average().toInt(),
                samples.map { android.graphics.Color.blue(it) }.average().toInt()
            )
        } else runCatching { android.graphics.Color.parseColor(text("Color Key", "#FF000000")) }.getOrDefault(android.graphics.Color.BLACK)
        val transparency = number("Transparency Strength", 1f).coerceIn(0f, 1f)
        val keyR = android.graphics.Color.red(keyColor).toFloat()
        val keyG = android.graphics.Color.green(keyColor).toFloat()
        val keyB = android.graphics.Color.blue(keyColor).toFloat()
        val edge = edgeBlur * min(w, h) / 1080f
        if (keyEnabled || edge > 0f || maskPixels != null || adjustImageColor) {
            for (y in 0 until h) for (x in 0 until w) {
                val index = y * w + x
                val pixel = pixels[index]
                var alpha = android.graphics.Color.alpha(pixel).toFloat()
                if (maskPixels != null) {
                    val maskPixel = maskPixels[index]
                    val maskAlpha = android.graphics.Color.alpha(maskPixel).toFloat() / 255f
                    val luminance = (0.2126f * android.graphics.Color.red(maskPixel) + 0.7152f * android.graphics.Color.green(maskPixel) + 0.0722f * android.graphics.Color.blue(maskPixel)) / 255f
                    val maskFactor = when (maskMode) {
                        "Luminance Mask" -> luminance
                        "Inverted Mask" -> 1f - maskAlpha
                        "Normal Mask" -> (luminance * maskAlpha).coerceIn(0f, 1f)
                        else -> maskAlpha
                    }
                    val maskStrength = number("Mask Strength", 1f).coerceIn(0f, 1f)
                    alpha *= 1f - (1f - maskFactor) * maskStrength
                }
                if (keyEnabled) {
                    val dr = android.graphics.Color.red(pixel) - keyR
                    val dg = android.graphics.Color.green(pixel) - keyG
                    val db = android.graphics.Color.blue(pixel) - keyB
                    val distance = kotlin.math.sqrt(dr * dr + dg * dg + db * db) / 441.673f
                    val tolerance = number("Color Key Tolerance", 0.16f).coerceIn(0.001f, 1f)
                    alpha *= 1f - (1f - (distance / tolerance).coerceIn(0f, 1f)) * transparency
                }
                if (edge > 0f) {
                    val edgeLinear = (min(min(x + 1f, w - x.toFloat()), min(y + 1f, h - y.toFloat())) / edge).coerceIn(0f, 1f)
                    val edgeFactor = if (blurredBorder) edgeLinear * edgeLinear * (3f - 2f * edgeLinear) else edgeLinear
                    alpha *= edgeFactor
                }
                if (adjustImageColor) {
                    var red = android.graphics.Color.red(pixel).toFloat() / 255f
                    var green = android.graphics.Color.green(pixel).toFloat() / 255f
                    var blue = android.graphics.Color.blue(pixel).toFloat() / 255f
                    val pivot = 0.5f
                    red = ((red - pivot) * contrast + pivot) * brightness * (1f + warm * 0.10f)
                    green = ((green - pivot) * contrast + pivot) * brightness * (1f + warm * 0.025f)
                    blue = ((blue - pivot) * contrast + pivot) * brightness * (1f - warm * 0.10f)
                    red = kotlin.math.pow(red.coerceAtLeast(0f), 1f / gamma).coerceIn(0f, 1f)
                    green = kotlin.math.pow(green.coerceAtLeast(0f), 1f / gamma).coerceIn(0f, 1f)
                    blue = kotlin.math.pow(blue.coerceAtLeast(0f), 1f / gamma).coerceIn(0f, 1f)
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV((red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(), hsv)
                    hsv[0] = (hsv[0] + hueShift + 360f) % 360f
                    pixels[index] = android.graphics.Color.HSVToColor(alpha.toInt().coerceIn(0, 255), hsv)
                } else pixels[index] = (pixel and 0x00FFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24)
            }
        }
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        if (mask != null && mask !== maskSource) mask.recycle()
        if (blurRadius <= 0f) return output
        val scale = (1f / (1f + blurRadius / blurDivider)).coerceIn(0.08f, 1f)
        val smallW = (w * scale).toInt().coerceAtLeast(1)
        val smallH = (h * scale).toInt().coerceAtLeast(1)
        val reduced = Bitmap.createScaledBitmap(output, smallW, smallH, true)
        val blurred = Bitmap.createScaledBitmap(reduced, w, h, true)
        if (reduced !== output) reduced.recycle()
        output.recycle()
        return blurred
    }

    private fun drawFrame(context: Context, canvas: Canvas, paint: Paint, project: StudioProject, width: Int, height: Int, timeMs: Long, imageCache: Map<String, Bitmap>, barRuntimes: MutableMap<String, SpectrumBarsRuntime>, barLastUpdateMs: MutableMap<String, Long>, audioReactivityRuntimes: MutableMap<String, AudioReactivityRuntime>, audioLevel: Float, providerSamples: FloatArray?, providerBeat: Float, providerBpm: Float, leftLevel: Float, rightLevel: Float, waveformLevel: Float) {
        val poppinsTypeface = runCatching { ResourcesCompat.getFont(context, com.vooc.visualizer.R.font.poppins_variable) }.getOrNull()
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor((project.document.backgroundArgb or (0xFFL shl 24)).toInt())
        val seconds = timeMs / 1000f
        project.layers.filter { it.enabled }.map { evaluateLayer(project, it, timeMs) }.forEach { layer ->
            val color = layer.color.toInt()
            paint.color = color
            paint.alpha = (layer.opacity.coerceIn(0f, 100f) * 2.55f).toInt()
            paint.style = Paint.Style.FILL
            val bassLevel = providerSamples?.take((providerSamples.size / 5).coerceAtLeast(1))
                ?.average()?.toFloat() ?: audioLevel
            val trebleLevel = providerSamples?.takeLast((providerSamples.size / 5).coerceAtLeast(1))
                ?.average()?.toFloat() ?: audioLevel
            val motion = MeasureWhatEngine.evaluate(
                mode = layer.measureWhat,
                runtimeSeconds = seconds,
                trackPositionSeconds = seconds,
                playing = true,
                beatPulse = providerBeat,
                bass = bassLevel,
                treble = trebleLevel,
                bpm = providerBpm,
                seed = layer.id
            )
            val cx = width * (0.5f + layer.x / 200f + motion.offsetX)
            val cy = height * (0.5f + layer.y / 200f + motion.offsetY)
            val scale = (layer.scale.coerceIn(1f, 400f) / 100f * motion.scale).coerceIn(0.01f, 4f)
            val count = layer.barCount.coerceIn(8, 256)
            val pulse = if (providerSamples != null) (0.08f + providerBeat.coerceIn(0f, 1f) * 0.92f) else (0.08f + audioLevel.coerceIn(0f, 1f) * (0.35f + layer.sensitivity.coerceIn(0f, 200f) / 100f)).coerceIn(0.08f, 1f)
            canvas.save()
            canvas.rotate(layer.rotation + motion.rotation, cx, cy)
            when (layer.kind) {
                LayerKind.BACKGROUND -> canvas.drawColor(color)
                LayerKind.BARS, LayerKind.BARSH, LayerKind.BLOCKS, LayerKind.GRID -> {
                    val gap = layer.spacing.coerceAtLeast(0f) * width / 1080f
                    val bw = ((width * 0.82f - gap * (count - 1)) / count).coerceAtLeast(1f)
                    val baseY = cy + height * 0.34f * scale
                    for (i in 0 until count) {
                        val frequencyShape = 0.18f + 0.82f * abs(sin(i * 0.21f + seconds * 3.2f))
                        val providerIndex = if ((providerSamples == null || providerSamples.isEmpty())) -1 else (i.toLong() * providerSamples.size / count).toInt().coerceIn(0, providerSamples.lastIndex)
                        val providerAmplitude = if (providerIndex >= 0) providerSamples!![providerIndex].coerceIn(0f, 1f) else 0f
                        val amplitude = if (providerIndex >= 0) providerAmplitude * (0.5f + layer.sensitivity.coerceIn(0f, 200f) / 100f) else frequencyShape * (0.12f + audioLevel.coerceIn(0f, 1f) * (0.45f + layer.sensitivity.coerceIn(0f, 200f) / 100f))
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
                        val waveLevel = if ((providerSamples == null || providerSamples.isEmpty())) (0.08f + audioLevel.coerceIn(0f, 1f)) else providerSamples[(i * providerSamples.size / 241).coerceIn(0, providerSamples.lastIndex)].coerceIn(0f, 1f)
                        val y = cy + sin(i * 0.075f + seconds * 4f) * height * 0.12f * waveLevel * scale
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
                LayerKind.IMAGE -> imageCache[layer.imageUri]?.let { image ->
                    val drawWidth = width * 0.42f * scale
                    val drawHeight = drawWidth * image.height.toFloat() / image.width.coerceAtLeast(1)
                    val destination = RectF(cx - drawWidth / 2f, cy - drawHeight / 2f, cx + drawWidth / 2f, cy + drawHeight / 2f)
                    canvas.drawBitmap(image, null, destination, paint)
                }
            }
            canvas.restore()
        }
        project.document.elements.filter { it.visible && it.type != StudioElementType.AUDIO_PROVIDER }.forEach { element ->
            val props = element.properties
            fun number(name: String, fallback: Float): Float = when (val value = props[name]) {
                is ElementValue.Number -> value.value.toFloat()
                is ElementValue.Integer -> value.value.toFloat()
                else -> fallback
            }
            fun textValue(name: String, fallback: String = ""): String = (props[name] as? ElementValue.Text)?.value ?: fallback
            val position = props["Position"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.0, 0.0)
            val safeElementSamples = providerSamples?.takeIf { it.isNotEmpty() } ?: floatArrayOf(audioLevel)
            val reactivitySources = audioReactivitySources(
                samples = safeElementSamples,
                overall = audioLevel,
                beat = providerBeat,
                peak = audioLevel,
                rms = audioLevel,
                waveform = waveformLevel,
                left = leftLevel,
                right = rightLevel
            )
            val reactivityValue = audioReactivityRuntimes.getOrPut(element.id) { AudioReactivityRuntime() }
                .update(props, reactivitySources, 1f / project.fps.coerceIn(15, 60))
            val reactivity = audioReactivityTransform(props, reactivityValue)
            val elementBandSize = (safeElementSamples.size / 5).coerceAtLeast(1)
            fun motionFor(property: String) = MeasureWhatEngine.evaluate(
                mode = textValue(property, "Nothing"),
                runtimeSeconds = seconds,
                trackPositionSeconds = seconds,
                playing = true,
                beatPulse = providerBeat,
                bass = safeElementSamples.take(elementBandSize).average().toFloat(),
                treble = safeElementSamples.takeLast(elementBandSize).average().toFloat(),
                bpm = 120f,
                seed = element.id.hashCode().toLong()
            )
            val posMotion = motionFor("Measure Pos (Measure What)")
            val scaleMotion = motionFor("Measure Scale (Measure What)")
            val rotMotion = motionFor("Measure Rot (Measure What)")
            val cx = width * (0.5f + (position.x.toFloat() + reactivity.xOffset) / 200f + posMotion.offsetX)
            val cy = height * (0.5f + (position.y.toFloat() + reactivity.yOffset) / 200f + posMotion.offsetY)
            val scale = (number("Scale", 100f) / 100f * scaleMotion.scale * reactivity.scaleMultiplier).coerceIn(0f, 10f)
            val rotation = number("Rotation", 0f) + posMotion.rotation + scaleMotion.rotation + rotMotion.rotation + reactivity.rotationOffset
            val opacityMode = textValue("Measure Opacity (Measure What)", "Nothing")
            val opacityMotion = motionFor("Measure Opacity (Measure What)")
            val measuredOpacity = if (opacityMode == "Nothing") 1f else maxOf(
                kotlin.math.abs(opacityMotion.scale - 1f) / 0.32f,
                kotlin.math.abs(opacityMotion.rotation) / 8f,
                kotlin.math.abs(opacityMotion.offsetX) / 0.05f,
                kotlin.math.abs(opacityMotion.offsetY) / 0.05f
            ).coerceIn(0f, 1f)
            val alpha = (number("Opacity", 100f).coerceIn(0f, 100f) * measuredOpacity * reactivity.opacityMultiplier * 2.55f).toInt()
            val baseColor = runCatching { android.graphics.Color.parseColor(textValue("Color", "#FFFFFFFF")) }.getOrDefault(android.graphics.Color.WHITE)
            val color = audioReactivityColor(baseColor, reactivity.colorMultiplier)
            paint.color = color
            paint.alpha = alpha
            paint.style = Paint.Style.FILL
            canvas.save()
            canvas.rotate(rotation, cx, cy)
            when (element.type) {
                StudioElementType.TEXT -> {
                    val caseMode = textValue("Case", "Original")
                    val rawText = textValue("Text", element.tag).take(2000)
                    val displayText = when (caseMode) {
                        "Uppercase" -> rawText.uppercase()
                        "Lowercase" -> rawText.lowercase()
                        "Capitalize" -> rawText.split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                        else -> rawText
                    }
                    val fontStyle = when (textValue("Font Style", "Bold")) {
                        "Regular" -> Typeface.NORMAL
                        "Medium", "SemiBold", "Bold" -> Typeface.BOLD
                        "Italic" -> Typeface.ITALIC
                        "Bold Italic" -> Typeface.BOLD_ITALIC
                        else -> Typeface.BOLD
                    }
                    val family = textValue("Font Family", "Poppins").ifBlank { "Poppins" }
                    val resolvedTypeface = if (family.equals("Poppins", ignoreCase = true)) {
                        poppinsTypeface?.let { Typeface.create(it, fontStyle) } ?: Typeface.create("sans-serif", fontStyle)
                    } else Typeface.create(family, fontStyle)
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                        this.color = color
                        this.alpha = alpha
                        textSize = (number("Font Size", 48f) * width / 1080f * scale).coerceAtLeast(6f)
                        typeface = resolvedTypeface
                        textAlign = when (textValue("Alignment", "Center")) {
                            "Left" -> Paint.Align.LEFT
                            "Right" -> Paint.Align.RIGHT
                            "Justified" -> Paint.Align.LEFT
                            else -> Paint.Align.CENTER
                        }
                        letterSpacing = number("Letter Spacing", 0f) / 100f
                        isUnderlineText = (element.value("Underline") as? ElementValue.Bool)?.value == true
                    }
                    val maxTextWidth = number("Max Width", 1000f) * width / 1080f
                    val wrapText = (element.value("Wrap Text") as? ElementValue.Bool)?.value != false
                    val textLines = if (!wrapText) listOf(displayText) else buildList {
                        displayText.split("\n").forEach { paragraph ->
                            var line = ""
                            paragraph.split(Regex("\\s+")).forEach { word ->
                                val candidate = if (line.isEmpty()) word else "$line $word"
                                if (line.isNotEmpty() && textPaint.measureText(candidate) > maxTextWidth) {
                                    add(line)
                                    line = word
                                } else line = candidate
                            }
                            add(line)
                        }
                    }.ifEmpty { listOf("") }
                    if ((element.value("Auto-Scale") as? ElementValue.Bool)?.value == true) {
                        val widest = textLines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
                        if (widest > maxTextWidth && widest > 0f) textPaint.textScaleX = maxTextWidth / widest
                    }
                    val lineHeight = textPaint.textSize * number("Line Spacing", 1f).coerceIn(0.5f, 4f)
                    val firstBaseline = cy - (textLines.size - 1) * lineHeight * 0.5f
                    val textX = when (textValue("Alignment", "Center")) {
                        "Left" -> cx - maxTextWidth * 0.5f
                        "Right" -> cx + maxTextWidth * 0.5f
                        "Justified" -> cx - maxTextWidth * 0.5f
                        else -> cx
                    }
                    fun drawTextLine(targetPaint: Paint, line: String, baseline: Float, index: Int) {
                        if (textValue("Alignment", "Center") == "Justified" && index < textLines.lastIndex) {
                            val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                            if (words.size > 1) {
                                val naturalWidth = words.sumOf { targetPaint.measureText(it).toDouble() }.toFloat()
                                val gap = ((maxTextWidth - naturalWidth) / (words.size - 1)).coerceAtLeast(0f)
                                var x = textX
                                words.forEach { word ->
                                    canvas.drawText(word, x, baseline, targetPaint)
                                    x += targetPaint.measureText(word) + gap
                                }
                                return
                            }
                        }
                        canvas.drawText(line, textX, baseline, targetPaint)
                    }
                    val shadowEnabled = (element.value("Shadow Enabled") as? ElementValue.Bool)?.value == true
                    if (shadowEnabled) {
                        val shadowColor = runCatching { android.graphics.Color.parseColor(textValue("Shadow Color", "#99000000")) }.getOrDefault(android.graphics.Color.BLACK)
                        textPaint.setShadowLayer(
                            number("Shadow Blur", 4f) * width / 1080f,
                            number("Shadow Offset X", 2f) * width / 1080f,
                            number("Shadow Offset Y", 2f) * width / 1080f,
                            shadowColor
                        )
                    }
                    val strokeEnabled = (element.value("Stroke Enabled") as? ElementValue.Bool)?.value == true
                    val strokeWidth = number("Stroke Thickness", 0f) * width / 1080f
                    if (strokeEnabled && strokeWidth > 0f) {
                        val strokePaint = Paint(textPaint).apply {
                            this.color = runCatching { android.graphics.Color.parseColor(textValue("Stroke Color", "#FF000000")) }.getOrDefault(android.graphics.Color.BLACK)
                            style = Paint.Style.STROKE
                            strokeJoin = Paint.Join.ROUND
                            this.strokeWidth = strokeWidth
                            clearShadowLayer()
                        }
                        textLines.forEachIndexed { index, line -> drawTextLine(strokePaint, line, firstBaseline + index * lineHeight, index) }
                    }
                    val gradientStart = runCatching { android.graphics.Color.parseColor(textValue("Text Gradient Start", "#FFFFFFFF")) }.getOrDefault(android.graphics.Color.WHITE)
                    val gradientEnd = runCatching { android.graphics.Color.parseColor(textValue("Text Gradient End", "#FFFFFFFF")) }.getOrDefault(android.graphics.Color.WHITE)
                    if (gradientStart != gradientEnd) {
                        val widest = textLines.maxOfOrNull { textPaint.measureText(it) } ?: textPaint.textSize
                        textPaint.shader = LinearGradient(cx - widest / 2f, cy, cx + widest / 2f, cy, gradientStart, gradientEnd, Shader.TileMode.CLAMP)
                    }
                    textLines.forEachIndexed { index, line -> drawTextLine(textPaint, line, firstBaseline + index * lineHeight, index) }
                    textPaint.clearShadowLayer()
                    textPaint.shader = null
                    textPaint.style = Paint.Style.FILL
                }
                StudioElementType.BARS_SEGMENTS -> {
                    val count = number("Bar Count", 64f).toInt().coerceIn(1, 512)
                    val source = providerSamples?.takeIf { it.isNotEmpty() } ?: floatArrayOf(audioLevel)
                    val input = processBarSpectrum(
                        samples = source,
                        amplitudeScale = number("Amplitude Scale", 1f),
                        gamma = number("Amplitude Gamma", 1f),
                        amplitudeMode = textValue("Amplitude Mode", "Magnitude"),
                        threshold = number("Amplitude Threshold", 0f),
                        offset = number("Amplitude Offset", 0f),
                        mappingCurve = number("Frequency Mapping Curve", 1f),
                        mappingBias = number("Frequency Mapping Bias", 0f),
                        bandOffset = number("Frequency Band Offset", 0f).toInt()
                    )
                    val previous = barLastUpdateMs.put(element.id, timeMs) ?: (timeMs - 1000L / project.fps.coerceIn(15, 60))
                    val delta = ((timeMs - previous).coerceIn(1L, 100L) / 1000f)
                    val runtime = barRuntimes.getOrPut(element.id) { SpectrumBarsRuntime() }
                    val barFrame = runtime.update(
                        input, count, delta, number("Smoothing / Damping", 0.72f),
                        number("Peak Hold Time", number("Peak Hold", 0.65f)),
                        number("Peak Fall Speed", 0.8f),
                        logarithmicInput = providerSamples == null && textValue("Frequency Scale", "Logarithmic") == "Logarithmic",
                        reactionDelayMs = number("Reaction Delay", 0f),
                        accumulatedDelayMs = number("Accumulated Delay", 0f),
                            attackSmoothing = number("Attack Smoothing", 0.12f),
                            releaseSmoothing = number("Release Smoothing", number("Smoothing / Damping", 0.72f))
                    )
                    val levels = barFrame.levels
                    val peaks = barFrame.peaks
                    val gap = number("Spacing / Gap", 3f).coerceAtLeast(0f) * width / 1080f
                    val scaleVector = element.value("Scale") as? ElementValue.Vector2
                    val scaleX = ((scaleVector?.x ?: 1.0).toFloat() * scale).coerceIn(0f, 10f)
                    val scaleYRaw = (scaleVector?.y ?: 1.0).toFloat()
                    val scaleY = ((if ((element.value("Scale Is Uniform") as? ElementValue.Bool)?.value == true) (scaleVector?.x ?: 1.0).toFloat() else scaleYRaw) * scale).coerceIn(0f, 10f)
                    val totalWidth = min(width * 0.86f * scaleX, width.toFloat())
                    val slot = totalWidth / count
                    val barWidth = (number("Bar Width", 8f) * width / 1080f).coerceIn(0.5f, (slot - gap).coerceAtLeast(0.5f))
                    val maxHeight = (number("Max Height", height * 0.35f) * width / 1080f * scaleY).coerceIn(1f, height * 0.95f)
                    val minHeight = (number("Min Height", 0f) * width / 1080f).coerceIn(0f, maxHeight)
                    val design = textValue("Bar Design", "Rounded Rect")
                    val alignment = textValue("Alignment", "Bottom")
                    val mirrorMode = textValue("Mirror Mode", "Off")
                    val mirrorX = mirrorMode == "Horizontal" || mirrorMode == "Both" || (element.value("Mirror") as? ElementValue.Bool)?.value == true
                    val mirrorY = mirrorMode == "Vertical" || mirrorMode == "Both" || (element.value("Center Symmetry") as? ElementValue.Bool)?.value == true
                    val outline = (element.value("Outline / Stroke") as? ElementValue.Bool)?.value == true
                    val strokeWidth = (number("Stroke Width", 1.5f) * width / 1080f).coerceAtLeast(0.5f)
                    val strokeColor = runCatching { audioReactivityColor(android.graphics.Color.parseColor(textValue("Stroke Color", "#FFFFFFFF")), reactivity.colorMultiplier) }.getOrDefault(color)
                    val colorA = runCatching { audioReactivityColor(android.graphics.Color.parseColor(textValue("Bar Color A", "#FF56D8FF")), reactivity.colorMultiplier) }.getOrDefault(color)
                    val colorB = runCatching { audioReactivityColor(android.graphics.Color.parseColor(textValue("Bar Color B", "#FF9B5CFF")), reactivity.colorMultiplier) }.getOrDefault(color)
                    val capColor = runCatching { audioReactivityColor(android.graphics.Color.parseColor(textValue("Cap Color", "#FFFFFFFF")), reactivity.colorMultiplier) }.getOrDefault(strokeColor)
                    val peakColor = runCatching { audioReactivityColor(android.graphics.Color.parseColor(textValue("Peak Color", "#FFFFFFFF")), reactivity.colorMultiplier) }.getOrDefault(strokeColor)
                    val colorMode = if ((element.value("Gradient Fill") as? ElementValue.Bool)?.value == true) "Gradient" else textValue("Color Mode", "Alternating")
                    val capMode = textValue("Top/Bottom Cap", "None")
                    val capPlacement = textValue("Cap Placement", "Top")
                    val capSize = (number("Cap Size", 4f) * width / 1080f).coerceAtLeast(1f)
                    val barOffset = number("Bar Offset", 0f) * width / 1080f
                    val customBitmap = imageCache[textValue("Custom Bar Image", "")]
                    val corner = when (design) {
                        "Capsule / Pill" -> barWidth / 2f
                        "Rounded Rect" -> barWidth * 0.22f
                        else -> 0f
                    }
                    val dash = when (textValue("Stroke Style", "Solid")) {
                        "Dashed" -> DashPathEffect(floatArrayOf(strokeWidth * 3f, strokeWidth * 2f), 0f)
                        "Dotted" -> DashPathEffect(floatArrayOf(strokeWidth, strokeWidth * 1.8f), 0f)
                        else -> null
                    }
                    val extrusion = (element.value("3D / Extrusion") as? ElementValue.Bool)?.value == true
                    val extrusionDepth = number("Extrusion Depth", 5f) * width / 1080f
                    val glow = (element.value("Glow / Shadow") as? ElementValue.Bool)?.value == true
                    val glowStrength = number("Glow Strength", 0.35f).coerceIn(0f, 1f)
                    val peakEnabled = (element.value("Peak / Cap") as? ElementValue.Bool)?.value == true
                    val peakWidth = (number("Peak Width", 2f) * width / 1080f).coerceAtLeast(0.5f)
                    fun lerpColor(a: Int, b: Int, t: Float): Int = android.graphics.Color.argb(
                        alpha, (android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * t).toInt(),
                        (android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * t).toInt(),
                        (android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * t).toInt()
                    )
                    repeat(count) { index ->
                        val sourceIndex = if (mirrorX) minOf(index, count - 1 - index) else index
                        var level = levels[sourceIndex].coerceIn(0f, 1f)
                        if ((element.value("Flip Input") as? ElementValue.Bool)?.value == true) level = 1f - level
                        val barHeight = (minHeight + level * maxHeight).coerceIn(0f, height * 0.95f)
                        val x = cx - totalWidth / 2f + index * slot + (slot - barWidth) / 2f + barOffset
                        val baseline = when (alignment) {
                            "Top" -> cy - height * 0.38f
                            "Center" -> cy
                            "Stretch" -> cy + height * 0.44f
                            else -> cy + height * 0.38f
                        }
                        val drawHeight = if (mirrorY) barHeight / 2f else barHeight
                        val h = if (alignment == "Stretch") (drawHeight * 1.25f).coerceAtMost(height * 0.95f) else drawHeight
                        val top = if (mirrorY) baseline - h else if (alignment == "Center") baseline - barHeight / 2f else if (alignment == "Top") baseline else baseline - h
                        val bottom = top + h
                        val gradientT = if (textValue("Gradient Direction", "Vertical") == "Horizontal") index.toFloat() / (count - 1).coerceAtLeast(1) else level
                        val fill = when (colorMode) {
                            "Alternating" -> if (index % 2 == 0) colorA else colorB
                            "Gradient" -> lerpColor(colorA, colorB, gradientT)
                            "By Amplitude" -> lerpColor(colorA, colorB, level)
                            else -> color
                        }
                        fun drawShape(yTop: Float, yBottom: Float) {
                            val rect = RectF(x, yTop, x + barWidth, yBottom)
                            val path = android.graphics.Path().apply {
                                when (design) {
                                    "Triangle" -> { moveTo(x + barWidth / 2f, yTop); lineTo(x + barWidth, yBottom); lineTo(x, yBottom); close() }
                                    "Diamond" -> { moveTo(x + barWidth / 2f, yTop); lineTo(x + barWidth, (yTop + yBottom) / 2f); lineTo(x + barWidth / 2f, yBottom); lineTo(x, (yTop + yBottom) / 2f); close() }
                                    "Hexagon" -> { moveTo(x + barWidth * 0.22f, yTop); lineTo(x + barWidth * 0.78f, yTop); lineTo(x + barWidth, (yTop + yBottom) / 2f); lineTo(x + barWidth * 0.78f, yBottom); lineTo(x + barWidth * 0.22f, yBottom); lineTo(x, (yTop + yBottom) / 2f); close() }
                                    "Star" -> { for (point in 0 until 10) { val angle = (point * 36f - 90f) * PI.toFloat() / 180f; val radius = if (point % 2 == 0) 0.5f else 0.22f; val px = x + barWidth * (0.5f + cos(angle) * radius); val py = yTop + (yBottom - yTop) * (0.5f + sin(angle) * radius); if (point == 0) moveTo(px, py) else lineTo(px, py) }; close() }
                                    "Chevron" -> { moveTo(x, yTop + h * 0.28f); lineTo(x + barWidth * 0.5f, yTop); lineTo(x + barWidth, yTop + h * 0.28f); lineTo(x + barWidth, yTop + h * 0.72f); lineTo(x + barWidth * 0.5f, yBottom); lineTo(x, yTop + h * 0.72f); close() }
                                    "Arrow" -> { moveTo(x + barWidth * 0.35f, yTop); lineTo(x + barWidth, yTop + h * 0.5f); lineTo(x + barWidth * 0.35f, yBottom); lineTo(x + barWidth * 0.35f, yTop + h * 0.68f); lineTo(x, yTop + h * 0.68f); lineTo(x, yTop + h * 0.32f); lineTo(x + barWidth * 0.35f, yTop + h * 0.32f); close() }
                                    "Cross" -> { moveTo(x + barWidth * 0.35f, yTop); lineTo(x + barWidth * 0.65f, yTop); lineTo(x + barWidth * 0.65f, yTop + h * 0.35f); lineTo(x + barWidth, yTop + h * 0.35f); lineTo(x + barWidth, yTop + h * 0.65f); lineTo(x + barWidth * 0.65f, yTop + h * 0.65f); lineTo(x + barWidth * 0.65f, yBottom); lineTo(x + barWidth * 0.35f, yBottom); lineTo(x + barWidth * 0.35f, yTop + h * 0.65f); lineTo(x, yTop + h * 0.65f); lineTo(x, yTop + h * 0.35f); lineTo(x + barWidth * 0.35f, yTop + h * 0.35f); close() }
                                    "Wave" -> { moveTo(x, yTop + h * 0.2f); lineTo(x + barWidth * 0.5f, yTop); lineTo(x + barWidth, yTop + h * 0.2f); lineTo(x + barWidth, yBottom); lineTo(x + barWidth * 0.5f, yTop + h * 0.8f); lineTo(x, yBottom); close() }
                                    else -> { addRoundRect(rect, corner, corner, android.graphics.Path.Direction.CW) }
                                }
                            }
                            paint.color = if (outline) strokeColor else fill
                            paint.alpha = alpha
                            paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
                            paint.strokeWidth = strokeWidth
                            paint.pathEffect = dash
                            if (glow) {
                                paint.style = Paint.Style.STROKE
                                paint.strokeWidth = strokeWidth + capSize * 2f
                                paint.alpha = (alpha * glowStrength * 0.2f).toInt()
                                canvas.drawPath(path, paint)
                                paint.alpha = alpha
                                paint.strokeWidth = strokeWidth
                            }
                            paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
                            if (extrusion) {
                                canvas.save()
                                canvas.translate(extrusionDepth, extrusionDepth)
                                paint.alpha = (alpha * 0.32f).toInt()
                                canvas.drawPath(path, paint)
                                canvas.restore()
                                paint.alpha = alpha
                            }
                            if (design == "Custom Image" && customBitmap != null && !outline) {
                                canvas.drawBitmap(customBitmap, null, rect, paint)
                            } else if (design == "Circle / Dot") {
                                paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
                                canvas.drawCircle(x + barWidth / 2f, (yTop + yBottom) / 2f, max(1f, h / 2f), paint)
                            } else if (design == "Line") {
                                paint.style = Paint.Style.STROKE
                                paint.strokeWidth = if (outline) strokeWidth else barWidth
                                canvas.drawLine(x + barWidth / 2f, yTop, x + barWidth / 2f, yBottom, paint)
                            } else {
                                canvas.drawPath(path, paint)
                            }
                            paint.pathEffect = null
                            paint.style = Paint.Style.FILL
                            paint.shader = null
                            if (capMode != "None") {
                                paint.color = capColor
                                paint.alpha = alpha
                                val cxBar = x + barWidth / 2f
                                fun cap(y: Float) {
                                    when (capMode) {
                                        "Circle" -> canvas.drawCircle(cxBar, y, capSize, paint)
                                        "Square" -> canvas.drawRect(cxBar - capSize, y - capSize, cxBar + capSize, y + capSize, paint)
                                        "Diamond" -> {
                                            val cp = android.graphics.Path().apply { moveTo(cxBar, y - capSize); lineTo(cxBar + capSize, y); lineTo(cxBar, y + capSize); lineTo(cxBar - capSize, y); close() }
                                            canvas.drawPath(cp, paint)
                                        }
                                    }
                                }
                                if (capPlacement != "Bottom") cap(yTop)
                                if (capPlacement != "Top") cap(yBottom)
                            }
                            if (peakEnabled) {
                                paint.color = peakColor
                                paint.alpha = alpha
                                paint.strokeWidth = peakWidth
                                paint.style = Paint.Style.STROKE
                                val py = baseline - peaks[index] * maxHeight
                                canvas.drawLine(x - peakWidth, py, x + barWidth + peakWidth, py, paint)
                                paint.style = Paint.Style.FILL
                            }
                        }
                        canvas.save()
                        canvas.rotate(number("Bar Rotation", 0f), x + barWidth / 2f, baseline)
                        drawShape(top, bottom)
                        if (mirrorY) drawShape(baseline, baseline + h)
                        canvas.restore()
                    }
                    paint.color = color
                    paint.alpha = alpha
                    paint.style = Paint.Style.FILL
                    paint.shader = null
                    paint.pathEffect = null
                }
                StudioElementType.PARTICLES -> {
                    val count = number("Count Limit", number("Particle Count", 128f)).toInt().coerceIn(1, 10000)
                    val lifetime = number("Lifetime", number("Life Span", 3f)).coerceIn(0.01f, 3600f)
                    val spawnInterval = number("Spawn Time", 0.02f).coerceIn(0f, 10f)
                    val spawnWidth = number("Spawn Area Width", 1f).coerceIn(0f, 10000f) * width * 0.25f
                    val spawnHeight = number("Spawn Area Height", 1f).coerceIn(0f, 10000f) * height * 0.25f
                    val spawnArea = textValue("Particle Emitter Shape", textValue("Spawn Area", "Point"))
                    val emitterPosition = element.value("Emitter Position") as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                    val emitterX = cx + (emitterPosition.x.toFloat() - 0.5f) * width
                    val emitterY = cy + (emitterPosition.y.toFloat() - 0.5f) * height
                    val emitterRotation = number("Emitter Rotation", 0f) * PI.toFloat() / 180f
                    val emitterRadius = number("Emitter Radius", 100f).coerceAtLeast(0f) * width / 1080f
                    val emitterInnerRadius = number("Emitter Inner Radius", 0f).coerceIn(0f, number("Emitter Radius", 100f)) * width / 1080f
                    val emitterArcStart = number("Emitter Arc Start", 0f) * PI.toFloat() / 180f
                    val emitterArcSweep = number("Emitter Arc Sweep", 360f).coerceIn(-360f, 360f) * PI.toFloat() / 180f
                    val emitterBurstCount = number("Emitter Burst Count", 0f).toInt().coerceAtLeast(0)
                    val emitterBurstInterval = number("Emitter Burst Interval", 1f).coerceAtLeast(0.001f)
                    val emitterLoop = (element.value("Emitter Loop") as? ElementValue.Bool)?.value != false
                    val particleMass = number("Particle Mass", 1f).coerceAtLeast(0.001f)
                    val particleDamping = number("Particle Damping", 0f).coerceIn(0f, 1f)
                    val angularVelocity = number("Particle Angular Velocity", number("Rotation Speed", 0f))
                    val angularVelocityRandom = number("Particle Angular Velocity Random", 0f).coerceAtLeast(0f)
                    val relativeToCenter = (element.value("Vector Relative To Center") as? ElementValue.Bool)?.value == true
                    val randomVector = (element.value("Use Random Vector Instead") as? ElementValue.Bool)?.value == true
                    val baseAngle = Math.toRadians(number("Vector Angle", number("Direction / Angle", -90f)).toDouble()).toFloat()
                    val spreadAngle = (number("Spread", 360f) + number("Direction Random", 0f)).coerceIn(0f, 720f) * PI.toFloat() / 180f
                    val gravity = element.value("Gravity") as? ElementValue.Vector2 ?: ElementValue.Vector2(0.0, 0.0)
                    val forcePosition = element.value("Force Field Position") as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                    val forceMode = textValue("Force Field", "None")
                    val forceStrength = number("Force Field Strength", 0f)
                    val sampleCount = providerSamples?.size ?: 0
                    val bassValue = providerSamples?.take((sampleCount / 5).coerceAtLeast(1))?.average()?.toFloat() ?: audioLevel
                    val trebleValue = providerSamples?.takeLast((sampleCount / 5).coerceAtLeast(1))?.average()?.toFloat() ?: audioLevel
                    val measuredSpeed = when (textValue("Measure Overall Speed (Measure What)", "Nothing")) {
                        "Beat" -> providerBeat
                        "Bass" -> bassValue
                        "Treble" -> trebleValue
                        "RMS", "Overall Audio Level" -> audioLevel
                        "Peak" -> audioLevel
                        else -> 0f
                    }.coerceIn(0f, 1f)
                    val baseSpeed = number("Speed", number("Start Velocity", 80f)) +
                        measuredSpeed * (number("Measure Overall Speed A (X Amount)", 0f) + number("Measure Overall Speed B (Y Amount)", 0f))
                    val speedRandom = number("Speed Random", number("Velocity Random", 0f)).coerceIn(0f, 1f)
                    val startSize = number("Start Size", 8f).coerceAtLeast(0f) * width / 1080f
                    val endSize = number("End Size", 2f).coerceAtLeast(0f) * width / 1080f
                    val endRandom = number("End Size Random", number("Particle Size Random", 0f)).coerceIn(0f, 1f)
                    val fadeTime = number("Fade In And Out Time", 0.25f).coerceIn(0f, lifetime * 0.5f)
                    val trailLength = number("Trail Length", 0f).coerceIn(0f, 256f) * width / 1080f
                    val particleScale = number("Particle Scale", 100f).coerceAtLeast(0f) / 100f * scale
                    val aspect = number("Size Aspect Ratio", 1f).coerceIn(0.01f, 20f)
                    val perspectiveDepth = number("Perspective Depth", 0f).coerceAtLeast(0f)
                    val perspectiveFov = number("Perspective Fov", 60f).coerceIn(1f, 179f)
                    val nearFadeDistance = number("Near Camera Fade Out Distance", 0f).coerceAtLeast(0f)
                    val sineFrequency = number("Side Sine Wave Freq", 0f)
                    val sineFrequencyRandom = number("Side Sine Wave Freq Random", 0f).coerceIn(0f, 1f)
                    val sineMagnitude = number("Side Sine Wave Mag", 0f) * width / 1080f
                    val sineMagnitudeRandom = number("Side Sine Wave Mag Random", 0f).coerceIn(0f, 1f)
                    val colorFrom = runCatching { audioReactivityColor(android.graphics.Color.parseColor(textValue("Color From", textValue("Color", "#FFFFFFFF"))), reactivity.colorMultiplier) }.getOrDefault(color)
                    val colorTo = runCatching { audioReactivityColor(android.graphics.Color.parseColor(textValue("Color To", textValue("Color", "#FFFFFFFF"))), reactivity.colorMultiplier) }.getOrDefault(color)
                    val customParticle = imageCache[textValue("Custom Image")]
                    val mirrorX = (element.value("Mirror X") as? ElementValue.Bool)?.value == true
                    val mirrorY = (element.value("Mirror Y") as? ElementValue.Bool)?.value == true
                    val velocityAngle = (element.value("Velocity Angle") as? ElementValue.Bool)?.value == true
                    repeat(count) { index ->
                        val seed = index + 1f
                        val randomA = abs(sin(seed * 12.9898f) * 43758.5453f) % 1f
                        val randomB = abs(sin(seed * 78.233f + 4.17f) * 24634.6345f) % 1f
                        val randomC = abs(sin(seed * 39.425f + 9.31f) * 56445.234f) % 1f
                        val age = ((seconds + index * spawnInterval) % lifetime).coerceIn(0f, lifetime)
                        val lifeT = (age / lifetime).coerceIn(0f, 1f)
                        val emitterAngle = emitterArcStart + randomA * emitterArcSweep
                        val radialDistance = emitterInnerRadius + (emitterRadius - emitterInnerRadius) * randomB
                        val localX: Float
                        val localY: Float
                        when (spawnArea) {
                            "Rectangle" -> { localX = (randomA * 2f - 1f) * spawnWidth; localY = (randomB * 2f - 1f) * spawnHeight }
                            "Ellipse", "Circle" -> { localX = cos(emitterAngle) * radialDistance.coerceAtLeast(spawnWidth * randomB); localY = sin(emitterAngle) * (if (spawnArea == "Circle") radialDistance else emitterRadius * spawnHeight / spawnWidth.coerceAtLeast(1f)) }
                            "Ring" -> { localX = cos(emitterAngle) * radialDistance; localY = sin(emitterAngle) * radialDistance }
                            "Line" -> { localX = (randomA * 2f - 1f) * spawnWidth; localY = 0f }
                            "Edge" -> { localX = if (randomA < 0.5f) -spawnWidth else spawnWidth; localY = (randomB * 2f - 1f) * spawnHeight }
                            else -> { localX = 0f; localY = 0f }
                        }
                        val spawnX = emitterX + localX * cos(emitterRotation) - localY * sin(emitterRotation)
                        val spawnY = emitterY + localX * sin(emitterRotation) + localY * cos(emitterRotation)
                        val radialAngle = kotlin.math.atan2(spawnY - cy, spawnX - cx)
                        val angle = when {
                            randomVector -> randomA * 2f * PI.toFloat()
                            relativeToCenter -> radialAngle + number("Vector Angle Z", 0f) * PI.toFloat() / 180f + (randomA - 0.5f) * spreadAngle
                            else -> baseAngle + (randomA - 0.5f) * spreadAngle
                        }
                        val velocity = baseSpeed * (1f + (randomC * 2f - 1f) * speedRandom) / particleMass
                        var vx = cos(angle) * velocity
                        var vy = sin(angle) * velocity
                        val burstIndex = if (emitterBurstCount > 0) (seconds / emitterBurstInterval).toInt() else 0
                        val inBurst = emitterBurstCount <= 0 || (index % count.coerceAtLeast(1)) < emitterBurstCount
                        if (emitterBurstCount > 0 && (!inBurst || (!emitterLoop && burstIndex > 0))) { vx = 0f; vy = 0f }
                        val fieldX = forcePosition.x.toFloat() * width
                        val fieldY = forcePosition.y.toFloat() * height
                        val dx = fieldX - spawnX
                        val dy = fieldY - spawnY
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        when (forceMode) {
                            "Attract", "Radial" -> { vx += dx / distance * forceStrength * age; vy += dy / distance * forceStrength * age }
                            "Repel" -> { vx -= dx / distance * forceStrength * age; vy -= dy / distance * forceStrength * age }
                            "Directional" -> { vx += cos(baseAngle) * forceStrength * age; vy += sin(baseAngle) * forceStrength * age }
                        }
                        val frequency = sineFrequency * (1f + randomB * sineFrequencyRandom)
                        val wave = sin(age * frequency * 2f * PI.toFloat() + randomC * 2f * PI.toFloat()) *
                            sineMagnitude * (1f + randomA * sineMagnitudeRandom)
                        val dampingFactor = (1f - particleDamping).coerceAtLeast(0f)
                        val travelTime = if (particleDamping > 0f) (1f - kotlin.math.exp(-particleDamping * age)) / particleDamping else age
                        val px = spawnX + vx * travelTime * 0.01f + gravity.x.toFloat() * age * age * 0.005f + wave * dampingFactor
                        val py = spawnY + vy * travelTime * 0.01f + gravity.y.toFloat() * age * age * 0.005f
                        val depth = randomB * perspectiveDepth
                        val perspective = (perspectiveFov / (perspectiveFov + depth * 0.1f)).coerceIn(0.05f, 1f)
                        val depthScale = (1f + number("End Size ZFactor", 0f) * depth / 1000f).coerceAtLeast(0.05f)
                        val endVariation = 1f + (randomC * 2f - 1f) * endRandom
                        val fadeInScale = if (fadeTime > 0f) (age / fadeTime).coerceIn(0f, 1f) else 1f
                        val sizeT = (startSize + (endSize * endVariation - startSize) * lifeT) *
                            (1f + (fadeInScale - 1f) * number("Fade In Size", 1f).coerceIn(0f, 10f))
                        val fadeOut = if (fadeTime > 0f) ((lifetime - age) / fadeTime).coerceIn(0f, 1f) else 1f
                        val nearFade = if (nearFadeDistance > 0f) (1f - depth / nearFadeDistance).coerceIn(0f, 1f) else 1f
                        val particleAlpha = (alpha * fadeInScale * fadeOut * nearFade).toInt().coerceIn(0, 255)
                        val colorT = lifeT.coerceIn(0f, 1f)
                        val particleColor = android.graphics.Color.argb(
                            (particleAlpha * (android.graphics.Color.alpha(colorFrom) / 255f)).toInt(),
                            (android.graphics.Color.red(colorFrom) + (android.graphics.Color.red(colorTo) - android.graphics.Color.red(colorFrom)) * colorT).toInt(),
                            (android.graphics.Color.green(colorFrom) + (android.graphics.Color.green(colorTo) - android.graphics.Color.green(colorFrom)) * colorT).toInt(),
                            (android.graphics.Color.blue(colorFrom) + (android.graphics.Color.blue(colorTo) - android.graphics.Color.blue(colorFrom)) * colorT).toInt()
                        )
                        val particleWidth = (sizeT * particleScale * perspective * depthScale * aspect).coerceAtLeast(0.5f)
                        val particleHeight = (sizeT * particleScale * perspective * depthScale).coerceAtLeast(0.5f)
                        val randomAngularVelocity = angularVelocity + (randomB * 2f - 1f) * angularVelocityRandom
                        val visualAngle = if (velocityAngle) Math.toDegrees(kotlin.math.atan2(vy.toDouble(), vx.toDouble())).toFloat() else number("Particle Rotation Random", 0f) * randomC + randomAngularVelocity * age
                        val trailX = vx * trailLength * 0.001f
                        val trailY = vy * trailLength * 0.001f
                        fun drawParticleAt(x: Float, y: Float) {
                            paint.color = particleColor
                            paint.alpha = particleAlpha
                            paint.style = Paint.Style.FILL
                            if (trailLength > 0f) {
                                paint.strokeWidth = (particleHeight * 0.35f).coerceAtLeast(0.5f)
                                paint.strokeCap = Paint.Cap.ROUND
                                canvas.drawLine(x - trailX, y - trailY, x, y, paint)
                            }
                            if (customParticle != null) {
                                canvas.save()
                                canvas.rotate(visualAngle, x, y)
                                canvas.drawBitmap(customParticle, null,
                                    RectF(x - particleWidth / 2f, y - particleHeight / 2f, x + particleWidth / 2f, y + particleHeight / 2f), paint)
                                canvas.restore()
                            } else {
                                canvas.drawCircle(x, y, particleWidth * 0.5f, paint)
                            }
                        }
                        if (mirrorX) drawParticleAt(width - px, py)
                        if (mirrorY) drawParticleAt(px, height - py)
                        if (mirrorX && mirrorY) drawParticleAt(width - px, height - py)
                        drawParticleAt(px, py)
                    }
                    paint.color = color
                    paint.alpha = alpha
                    paint.style = Paint.Style.FILL
                }
                StudioElementType.IMAGE -> {
                    val uriText = listOf("Import Image", "Custom Image", "Image Source / File", "Target Image")
                        .firstNotNullOfOrNull { key -> (element.value(key) as? ElementValue.Text)?.value?.takeIf { it.isNotBlank() } }.orEmpty()
                    val image = imageCache["${uriText}#${element.id}"] ?: imageCache[uriText]
                    if (image != null) {
                        val imageScale = element.value("Scale") as? ElementValue.Vector2 ?: ElementValue.Vector2(1.0, 1.0)
                        val imageScaleX = imageScale.x.toFloat().coerceIn(0f, 10f)
                        val imageScaleY = (if ((element.value("Scale Is Uniform") as? ElementValue.Bool)?.value == true) imageScaleX else imageScale.y.toFloat()).coerceIn(0f, 10f)
                        val frameWidth = width * 0.45f * scale * imageScaleX
                        val frameHeight = height * 0.45f * scale * imageScaleY
                        val fitMode = if ((element.value("Keep Aspect Ratio And Crop To Fit") as? ElementValue.Bool)?.value == true) "Fill (Cover)" else textValue("Fit Mode (Scale Type)", "Fit (Contain)")
                        val keepRatio = (element.value("Keep Aspect Ratio") as? ElementValue.Bool)?.value != false
                        val leftCrop = (number("Crop Left", 0f).coerceIn(0f, 0.95f) * image.width).toInt()
                        val topCrop = (number("Crop Top", 0f).coerceIn(0f, 0.95f) * image.height).toInt()
                        val rightCrop = (number("Crop Right", 0f).coerceIn(0f, 0.95f) * image.width).toInt()
                        val bottomCrop = (number("Crop Bottom", 0f).coerceIn(0f, 0.95f) * image.height).toInt()
                        val src = android.graphics.Rect(leftCrop, topCrop,
                            (image.width - rightCrop).coerceAtLeast(leftCrop + 1),
                            (image.height - bottomCrop).coerceAtLeast(topCrop + 1))
                        val ratio = src.width().toFloat() / src.height().coerceAtLeast(1).toFloat()
                        val frameRatio = frameWidth / frameHeight.coerceAtLeast(1f)
                        val dst = when {
                            fitMode == "Stretch" || !keepRatio -> RectF(cx - frameWidth / 2f, cy - frameHeight / 2f, cx + frameWidth / 2f, cy + frameHeight / 2f)
                            fitMode == "Fill (Cover)" -> {
                                val dw = if (ratio > frameRatio) frameHeight * ratio else frameWidth
                                val dh = if (ratio > frameRatio) frameHeight else frameWidth / ratio
                                RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
                            }
                            else -> {
                                val dw = if (ratio > frameRatio) frameWidth else frameHeight * ratio
                                val dh = if (ratio > frameRatio) frameWidth / ratio else frameHeight
                                RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
                            }
                        }
                        val shape = textValue("Shape", "Rectangle").takeIf { it != "Rectangle" } ?: textValue("Mask / Shape", "Rectangle")
                        val clip = android.graphics.Path().apply {
                            when (shape) {
                                "Circle", "Ellipse", "Circle/Oval" -> addOval(dst, android.graphics.Path.Direction.CW)
                                "Square" -> {
                                    val side = minOf(dst.width(), dst.height())
                                    addRect(RectF(dst.centerX() - side / 2f, dst.centerY() - side / 2f, dst.centerX() + side / 2f, dst.centerY() + side / 2f), android.graphics.Path.Direction.CW)
                                }
                                "Triangle" -> { moveTo(dst.centerX(), dst.top); lineTo(dst.right, dst.bottom); lineTo(dst.left, dst.bottom); close() }
                                "Rounded Rectangle", "Rounded Rect" -> addRoundRect(dst, (number("Corner Radius", 0f) * width / 1080f).coerceAtLeast(0f), (number("Corner Radius", 0f) * width / 1080f).coerceAtLeast(0f), android.graphics.Path.Direction.CW)
                                else -> addRect(dst, android.graphics.Path.Direction.CW)
                            }
                        }
                        val overlay = runCatching { android.graphics.Color.parseColor(textValue("Color Overlay", "#00FFFFFF")) }.getOrDefault(android.graphics.Color.TRANSPARENT)
                        val layerBlend = when (textValue("Blend Mode", "Alpha")) {
                            "Add" -> android.graphics.PorterDuff.Mode.ADD
                            "Screen" -> android.graphics.PorterDuff.Mode.SCREEN
                            "Multiply", "Subtractive" -> android.graphics.PorterDuff.Mode.MULTIPLY
                            "Darken" -> android.graphics.PorterDuff.Mode.DARKEN
                            "Lighten" -> android.graphics.PorterDuff.Mode.LIGHTEN
                            "Exclusion" -> android.graphics.PorterDuff.Mode.XOR
                            "Copy" -> android.graphics.PorterDuff.Mode.SRC
                            else -> android.graphics.PorterDuff.Mode.SRC_OVER
                        }
                        val imagePaint = Paint(paint).apply {
                            this.alpha = (alpha * number("Opacity / Alpha", 1f).coerceIn(0f, 1f) * number("Opacity Strength", 1f).coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
                            val saturation = number("Saturation", 1f).coerceIn(0f, 2f)
                            val colorMatrix = ColorMatrix().apply {
                                setSaturation(saturation)
                                val layerTint = runCatching { android.graphics.Color.parseColor(textValue("Color", "#FFFFFFFF")) }.getOrDefault(android.graphics.Color.WHITE)
                                val tintValues = array
                                val tintChannels = floatArrayOf(
                                    android.graphics.Color.red(layerTint) / 255f,
                                    android.graphics.Color.green(layerTint) / 255f,
                                    android.graphics.Color.blue(layerTint) / 255f
                                )
                                for (row in 0..2) {
                                    val base = row * 5
                                    for (column in 0..3) tintValues[base + column] *= tintChannels[row]
                                    tintValues[base + 4] *= tintChannels[row]
                                }
                            }
                            if (android.graphics.Color.alpha(overlay) > 0) {
                                val a = android.graphics.Color.alpha(overlay) / 255f
                                val values = colorMatrix.array
                                val channels = intArrayOf(android.graphics.Color.red(overlay), android.graphics.Color.green(overlay), android.graphics.Color.blue(overlay))
                                for (row in 0..2) {
                                    val base = row * 5
                                    for (column in 0..3) values[base + column] *= (1f - a)
                                    values[base + 4] = values[base + 4] * (1f - a) + channels[row] * a
                                }
                            }
                            val blendMode = textValue("Measure Color Blend (Measure What)", "Nothing")
                            val blendMotion = motionFor("Measure Color Blend (Measure What)")
                            val blendResponse = maxOf(
                                kotlin.math.abs(blendMotion.scale - 1f) / 0.32f,
                                kotlin.math.abs(blendMotion.rotation) / 8f,
                                kotlin.math.abs(blendMotion.offsetX) / 0.05f,
                                kotlin.math.abs(blendMotion.offsetY) / 0.05f
                            ).coerceIn(0f, 1f)
                            val measuredBlend = if (blendMode == "Nothing") 0f else {
                                val minBlend = number("Measure Color Blend Min", 0f).coerceIn(0f, 1f)
                                val maxBlend = number("Measure Color Blend Max", 1f).coerceIn(0f, 1f)
                                minBlend + (maxBlend - minBlend) * blendResponse
                            }
                            val blendAmount = maxOf(number("Measure Color Blend", 0f).coerceIn(0f, 1f), measuredBlend)
                            if (blendAmount > 0f) {
                                val target = runCatching { android.graphics.Color.parseColor(textValue("Color To", "#FFFFFFFF")) }.getOrDefault(android.graphics.Color.WHITE)
                                val values = colorMatrix.array
                                val channels = intArrayOf(android.graphics.Color.red(target), android.graphics.Color.green(target), android.graphics.Color.blue(target))
                                for (row in 0..2) {
                                    val base = row * 5
                                    for (column in 0..3) values[base + column] *= (1f - blendAmount)
                                    values[base + 4] = values[base + 4] * (1f - blendAmount) + channels[row] * blendAmount
                                }
                            }
                            colorFilter = ColorMatrixColorFilter(colorMatrix)
                            val contentBlend = when (textValue("Blend Mode Content", "Alpha")) {
                                "Add" -> android.graphics.PorterDuff.Mode.ADD
                                "Screen" -> android.graphics.PorterDuff.Mode.SCREEN
                                "Multiply", "Subtractive" -> android.graphics.PorterDuff.Mode.MULTIPLY
                                "Darken" -> android.graphics.PorterDuff.Mode.DARKEN
                                "Lighten" -> android.graphics.PorterDuff.Mode.LIGHTEN
                                "Exclusion" -> android.graphics.PorterDuff.Mode.XOR
                                "Copy" -> android.graphics.PorterDuff.Mode.SRC
                                else -> layerBlend
                            }
                            xfermode = if (contentBlend == android.graphics.PorterDuff.Mode.SRC_OVER) {
                                null
                            } else {
                                android.graphics.PorterDuffXfermode(contentBlend)
                            }
                        }
                        val original = imageCache[uriText]
                        val showUnblurredUnder = (element.value("Show Unblurred Content Under") as? ElementValue.Bool)?.value == true
                        val showUnblurredOver = (element.value("Show Unblurred Content") as? ElementValue.Bool)?.value == true
                        // The unblurred source uses the layer's general blend mode, not the
                        // processed image's independent content blend mode (matches live preview).
                        val unblurredPaint = Paint(imagePaint).apply {
                            colorFilter = null
                            xfermode = if (layerBlend == android.graphics.PorterDuff.Mode.SRC_OVER) {
                                null
                            } else {
                                android.graphics.PorterDuffXfermode(layerBlend)
                            }
                        }
                        canvas.save()
                        canvas.clipPath(clip)
                        if (showUnblurredUnder && original != null) {
                            canvas.drawBitmap(original, src, dst, unblurredPaint)
                        }
                        canvas.drawBitmap(image, src, dst, imagePaint)
                        if (showUnblurredOver && original != null) {
                            canvas.drawBitmap(original, src, dst, unblurredPaint)
                        }
                        canvas.restore()
                    }
                }
                StudioElementType.RGB_SPLIT -> {
                    val targetUri = textValue("Target Image")
                    val image = imageCache["${targetUri}#${element.id}"] ?: imageCache[targetUri]
                    if (image != null) {
                        val frameWidth = width * 0.45f * scale
                        val frameHeight = frameWidth * image.height.toFloat() / image.width.coerceAtLeast(1)
                        val amount = number("Split Amount", number("Split Amount / Distance", 4f)) * number("Split Multiplier", 1f) * reactivity.effectStrengthMultiplier * width / 1080f
                        val angle = (number("Angle / Direction", 0f) + seconds * number("Speed / Frequency", 0f)) * PI.toFloat() / 180f
                        val focus = element.value("Center / Focus Point") as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                        val falloff = number("Falloff / Radial Split", 0f).coerceAtLeast(0f)
                        val radialScale = 1f + falloff * kotlin.math.sqrt(
                            (focus.x.toFloat() - 0.5f) * (focus.x.toFloat() - 0.5f) +
                                (focus.y.toFloat() - 0.5f) * (focus.y.toFloat() - 0.5f)
                        )
                        val contentColor = runCatching {
                            audioReactivityColor(android.graphics.Color.parseColor(textValue("Color", "#FFFFFFFF")), reactivity.colorMultiplier)
                        }.getOrDefault(android.graphics.Color.WHITE)
                        val channelColors = listOf("Split Color 0", "Split Color 1", "Split Color 2").mapIndexed { index, key ->
                            val channel = runCatching { android.graphics.Color.parseColor(textValue(key, listOf("#FFFF0000", "#FF00FF00", "#FF0000FF")[index])) }.getOrDefault(listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE)[index])
                            android.graphics.Color.argb(
                                android.graphics.Color.alpha(channel) * android.graphics.Color.alpha(contentColor) / 255,
                                android.graphics.Color.red(channel) * android.graphics.Color.red(contentColor) / 255,
                                android.graphics.Color.green(channel) * android.graphics.Color.green(contentColor) / 255,
                                android.graphics.Color.blue(channel) * android.graphics.Color.blue(contentColor) / 255
                            )
                        }
                        val channelOffsets = listOf(
                            number("Red Offset X", -4f) to number("Red Offset Y", 0f),
                            number("Green Offset X", 0f) to number("Green Offset Y", 0f),
                            number("Blue Offset X", 4f) to number("Blue Offset Y", 0f)
                        )
                        val contentBlend = when (textValue("Blend Mode Content", "Alpha")) {
                            "Add" -> android.graphics.PorterDuff.Mode.ADD
                            "Screen" -> android.graphics.PorterDuff.Mode.SCREEN
                            "Multiply", "Subtractive" -> android.graphics.PorterDuff.Mode.MULTIPLY
                            "Darken" -> android.graphics.PorterDuff.Mode.DARKEN
                            "Lighten" -> android.graphics.PorterDuff.Mode.LIGHTEN
                            "Exclusion", "Difference" -> android.graphics.PorterDuff.Mode.XOR
                            "Copy" -> android.graphics.PorterDuff.Mode.SRC
                            else -> android.graphics.PorterDuff.Mode.SRC_OVER
                        }
                        val original = imageCache[targetUri]
                        val showUnder = (element.value("Show Unblurred Content Under") as? ElementValue.Bool)?.value == true
                        val showOver = (element.value("Show Unblurred Content") as? ElementValue.Bool)?.value == true
                        val imageRect = RectF(cx - frameWidth / 2f, cy - frameHeight / 2f, cx + frameWidth / 2f, cy + frameHeight / 2f)
                        if (showUnder && original != null) canvas.drawBitmap(original, null, imageRect, paint)
                        channelColors.forEachIndexed { index, tint ->
                            val base = channelOffsets[index]
                            val dx = (base.first * scale + cos(angle) * amount * (index - 1)) * radialScale * width / 1080f
                            val dy = (base.second * scale + sin(angle) * amount * (index - 1)) * radialScale * width / 1080f
                            val channelPaint = Paint(paint).apply {
                                this.alpha = (alpha * 0.72f).toInt().coerceIn(0, 255)
                                colorFilter = android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SCREEN)
                                if (contentBlend != android.graphics.PorterDuff.Mode.SRC_OVER) {
                                    xfermode = android.graphics.PorterDuffXfermode(contentBlend)
                                }
                            }
                            canvas.drawBitmap(image, null, RectF(cx - frameWidth / 2f + dx, cy - frameHeight / 2f + dy,
                                cx + frameWidth / 2f + dx, cy + frameHeight / 2f + dy), channelPaint)
                        }
                        if (showOver && original != null) canvas.drawBitmap(original, null, imageRect, paint)
                    }
                }
                StudioElementType.BLUR_EFFECT -> {
                    val sourceUri = textValue("Target Image")
                    val image = imageCache["${sourceUri}#${element.id}"] ?: imageCache[sourceUri]
                    if (image != null) {
                        val frameWidth = width * 0.45f * scale
                        val frameHeight = frameWidth * image.height.toFloat() / image.width.coerceAtLeast(1)
                        val destination = RectF(cx - frameWidth / 2f, cy - frameHeight / 2f, cx + frameWidth / 2f, cy + frameHeight / 2f)
                        val original = imageCache[sourceUri]
                        val showUnder = (element.value("Show Unblurred Content Under") as? ElementValue.Bool)?.value == true
                        val showOver = (element.value("Show Unblurred Content") as? ElementValue.Bool)?.value == true
                        if (showUnder && original != null) canvas.drawBitmap(original, null, destination, paint)
                        val effectPaint = Paint(paint).apply { this.alpha = (this.alpha * reactivity.effectStrengthMultiplier.coerceIn(0f, 1f)).toInt().coerceIn(0, 255) }
                        canvas.drawBitmap(image, null, destination, effectPaint)
                        if (showOver && original != null) canvas.drawBitmap(original, null, destination, paint)
                    }
                }
                StudioElementType.MOTION_BLUR_EFFECT -> {
                    val sourceUri = textValue("Target Image")
                    val image = imageCache["${sourceUri}#${element.id}"] ?: imageCache[sourceUri]
                    if (image != null) {
                        val original = imageCache[sourceUri]
                        val frameWidth = width * 0.45f * scale
                        val frameHeight = frameWidth * image.height.toFloat() / image.width.coerceAtLeast(1)
                        val destination = RectF(cx - frameWidth / 2f, cy - frameHeight / 2f, cx + frameWidth / 2f, cy + frameHeight / 2f)
                        val samples = number("Samples / Iterations", 8f).toInt().coerceIn(1, 64)
                        val strength = number("Blur Amount / Strength", 1f).coerceIn(0f, 64f)
                        val lengthBase = number("Blur Length", 24f) * number("Blur Amount Multiplier", 1f) * strength * reactivity.effectStrengthMultiplier * width / 1080f
                        val focus = element.value("Center / Focus Point") as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                        val focusX = focus.x.toFloat() * width
                        val focusY = focus.y.toFloat() * height
                        val radialAngle = kotlin.math.atan2(cy - focusY, cx - focusX)
                        val motionSource = textValue("Motion Source", "Transform Movement")
                        val relativeMode = textValue("Relative Motion Mode", "Composition")
                        val radial = element.value("Radial Motion") == ElementValue.Bool(true) || motionSource == "Target Motion" || relativeMode == "Target Relative"
                        val measuredAngle = number("Blur Direction / Angle", 0f) + if (motionSource == "Measured Movement") sin(seconds * 2.4f) * 45f else 0f
                        val resolvedAngle = if (radial) radialAngle else (measuredAngle + if (relativeMode == "Relative") number("Rotation", 0f) else 0f) * PI.toFloat() / 180f
                        val velocity = if (element.value("Velocity Influence") == ElementValue.Bool(false)) 1f else (0.25f + audioLevel.coerceIn(0f, 1f) * 0.75f)
                        val length = lengthBase * velocity
                        val angle = resolvedAngle
                        val trails = number("Trails / Echo", 0f).toInt().coerceIn(0, 32)
                        val fade = number("Trail Opacity / Fade", 0.75f).coerceIn(0f, 1f)
                        val showUnder = (element.value("Show Unblurred Content Under") as? ElementValue.Bool)?.value == true
                        val showOver = (element.value("Show Unblurred Content") as? ElementValue.Bool)?.value == true
                        if (showUnder && original != null) canvas.drawBitmap(original, null, destination, paint)
                        val contentColor = runCatching { android.graphics.Color.parseColor(textValue("Color", "#FFFFFFFF")) }.getOrDefault(android.graphics.Color.WHITE)
                        val contentMode = when (textValue("Blend Mode Content", "Alpha")) {
                            "Add" -> android.graphics.PorterDuff.Mode.ADD
                            "Screen" -> android.graphics.PorterDuff.Mode.SCREEN
                            "Multiply" -> android.graphics.PorterDuff.Mode.MULTIPLY
                            "Darken" -> android.graphics.PorterDuff.Mode.DARKEN
                            "Lighten" -> android.graphics.PorterDuff.Mode.LIGHTEN
                            "Exclusion" -> android.graphics.PorterDuff.Mode.XOR
                            "Copy" -> android.graphics.PorterDuff.Mode.SRC
                            else -> android.graphics.PorterDuff.Mode.SRC_OVER
                        }
                        fun contentPaint(a: Int): Paint = Paint(paint).apply {
                            this.alpha = a.coerceIn(0, 255)
                            if (contentColor != android.graphics.Color.WHITE) {
                                colorFilter = android.graphics.PorterDuffColorFilter(contentColor, android.graphics.PorterDuff.Mode.MULTIPLY)
                            }
                            if (contentMode != android.graphics.PorterDuff.Mode.SRC_OVER) {
                                xfermode = android.graphics.PorterDuffXfermode(contentMode)
                            }
                        }
                        for (sample in samples downTo 1) {
                            val t = sample.toFloat() / samples
                            val dx = cos(angle) * length * t
                            val dy = sin(angle) * length * t
                            canvas.drawBitmap(image, null, RectF(destination.left + dx, destination.top + dy, destination.right + dx, destination.bottom + dy),
                                contentPaint((alpha * fade * (1f - t * 0.55f)).toInt()))
                        }
                        repeat(trails) { index ->
                            val t = (index + 1f) / (trails + 1f)
                            val dx = -cos(angle) * length * t
                            val dy = -sin(angle) * length * t
                            canvas.drawBitmap(image, null, RectF(destination.left + dx, destination.top + dy, destination.right + dx, destination.bottom + dy),
                                contentPaint((alpha * fade * (1f - t)).toInt()))
                        }
                        if (showOver && original != null) canvas.drawBitmap(original, null, destination, paint)
                        else canvas.drawBitmap(image, null, destination, contentPaint(alpha))
                    }
                }
                StudioElementType.SHAPE -> {
                    val shapeWidth = number("Width", 300f) * scale * width / 1080f
                    val shapeHeight = number("Height", 300f) * scale * width / 1080f
                    val fill = runCatching {
                        audioReactivityColor(android.graphics.Color.parseColor(textValue("Fill / Color", textValue("Color", "#FFFFFFFF"))), reactivity.colorMultiplier)
                    }.getOrDefault(color)
                    paint.color = fill
                    paint.alpha = (alpha * number("Opacity / Alpha", 1f).coerceIn(0f, 1f)).toInt()
                    val rect = RectF(cx - shapeWidth / 2f, cy - shapeHeight / 2f, cx + shapeWidth / 2f, cy + shapeHeight / 2f)
                    when (textValue("Shape Type", "Rectangle")) {
                        "Circle/Oval" -> canvas.drawOval(rect, paint)
                        "Line" -> {
                            paint.strokeWidth = max(1f, number("Stroke / Border Width", 3f) * width / 1080f)
                            canvas.drawLine(rect.left, cy, rect.right, cy, paint)
                        }
                        "Triangle" -> {
                            val path = android.graphics.Path().apply {
                                moveTo(cx, rect.top); lineTo(rect.right, rect.bottom); lineTo(rect.left, rect.bottom); close()
                            }
                            canvas.drawPath(path, paint)
                        }
                        else -> canvas.drawRoundRect(rect, number("Corner Radius", 0f) * width / 1080f,
                            number("Corner Radius", 0f) * width / 1080f, paint)
                    }
                    paint.color = color
                    paint.alpha = alpha
                    paint.style = Paint.Style.FILL
                }
                StudioElementType.BACKGROUND -> Unit
                StudioElementType.AUDIO_PROVIDER,
                StudioElementType.MIRROR_EFFECT,
                StudioElementType.CUSTOM_SHADER,
                StudioElementType.COMPOSITION -> Unit
            }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun writeYuv420(bitmap: Bitmap, output: ByteBuffer) {
        val width = bitmap.width
        val height = bitmap.height
        val frameSize = width * height
        val chromaSize = frameSize / 4
        if (output.remaining() < frameSize + chromaSize * 2) throw IllegalStateException("Encoder input buffer is too small for this resolution.")
        val yPlane = ByteArray(frameSize)
        val uPlane = ByteArray(chromaSize)
        val vPlane = ByteArray(chromaSize)
        val rowPixels = IntArray(width)
        var yi = 0
        var ui = 0
        var vi = 0
        for (row in 0 until height) {
            bitmap.getPixels(rowPixels, 0, width, 0, row, width, 1)
            for (col in 0 until width) {
                val argb = rowPixels[col]
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                yPlane[yi++] = ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    uPlane[ui++] = ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
                    vPlane[vi++] = ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
                }
            }
        }
        output.put(yPlane)
        output.put(uPlane)
        output.put(vPlane)
    }

    private fun evaluateLayer(project: StudioProject, layer: com.vooc.visualizer.studio.VisualLayer, timeMs: Long): com.vooc.visualizer.studio.VisualLayer {
        fun value(property: String, base: Float): Float {
            val points = project.document.keyframes.filter { it.layerId == layer.id && it.property == property }.sortedBy { it.timeMs }
            if (points.isEmpty()) return base
            if (timeMs <= points.first().timeMs) return points.first().value
            if (timeMs >= points.last().timeMs) return points.last().value
            val rightIndex = points.indexOfFirst { it.timeMs >= timeMs }.coerceAtLeast(1)
            val left = points[rightIndex - 1]
            val right = points[rightIndex]
            var t = ((timeMs - left.timeMs).toFloat() / (right.timeMs - left.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            t = when (right.interpolation) {
                "STEP" -> 0f
                "EASE_IN_OUT" -> t * t * (3f - 2f * t)
                else -> t
            }
            return left.value + (right.value - left.value) * t
        }
        return layer.copy(
            x = value("X", layer.x).coerceIn(-200f, 200f),
            y = value("Y", layer.y).coerceIn(-200f, 200f),
            scale = value("SCALE", layer.scale).coerceIn(0f, 400f),
            rotation = value("ROTATION", layer.rotation).coerceIn(-3600f, 3600f),
            opacity = value("OPACITY", layer.opacity).coerceIn(0f, 100f)
        )
    }
}