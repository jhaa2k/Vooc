package com.vooc.visualizer.studio

import kotlin.math.PI
import kotlin.math.sin

/** Small deterministic motion result shared by live preview and offline export. */
data class MeasureMotion(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f
)

object MeasureWhatEngine {
    val options: List<String> = listOf(
        "Nothing", "Beat", "Total Time", "Total Time Backward", "TotalTimeWhen Playing",
        "Total TimeAndBeat", "TrackPosition", "TrackPosition Backward", "TrackPosition Smooth",
        "BeatRandom Shake", "BeatCamShake RotMore", "BeatCamShakeRotLess", "BeatCamShake PosOnly",
        "BeatZoom Shake", "Constant Shake RotMore", "Constant Shake RotLess", "Constant Shake Smooth",
        "Beat Trigger Anim", "Beat Trigger Reverse", "Beat PingPong", "HighFreq React", "BassPulse Smooth"
    )

    fun evaluate(
        mode: String,
        runtimeSeconds: Float,
        trackPositionSeconds: Float,
        playingSeconds: Float = runtimeSeconds,
        playing: Boolean,
        beatPulse: Float,
        bass: Float,
        treble: Float,
        bpm: Float,
        seed: Long
    ): MeasureMotion {
        val beat = beatPulse.coerceIn(0f, 1f)
        val low = bass.coerceIn(0f, 1f)
        val high = treble.coerceIn(0f, 1f)
        val runtime = runtimeSeconds.coerceAtLeast(0f)
        val track = trackPositionSeconds.coerceAtLeast(0f)
        val rate = (bpm.takeIf { it in 40f..240f } ?: 120f) / 60f
        val beatIndex = (track * rate).toLong()
        val phase = (track * rate * 2f * PI.toFloat())
        val continuous = sin(runtime * 2f * PI.toFloat() * 0.45f)
        val trackWave = sin(phase)
        val deterministic = sin((beatIndex * 12.9898 + seed * 0.071).toFloat() * 78.233f)
        val shake = deterministic * beat
        return when (mode) {
            "Nothing" -> MeasureMotion()
            "Beat" -> MeasureMotion(scale = 1f + beat * 0.18f)
            "Total Time" -> MeasureMotion(offsetX = continuous * 0.025f, rotation = continuous * 2.5f)
            "Total Time Backward" -> MeasureMotion(offsetX = -continuous * 0.025f, rotation = -continuous * 2.5f)
            "TotalTimeWhen Playing" -> {
                val playingPhase = sin(playingSeconds.coerceAtLeast(0f) * 2f * PI.toFloat() * 0.45f)
                val p = if (playing) playingPhase else 0f
                MeasureMotion(offsetY = p * 0.025f, scale = 1f + p * 0.035f)
            }
            "Total TimeAndBeat" -> MeasureMotion(
                offsetX = continuous * 0.02f, scale = 1f + beat * 0.2f, rotation = continuous * 2f + shake * 1.5f
            )
            "TrackPosition" -> MeasureMotion(offsetX = trackWave * 0.035f, rotation = trackWave * 4f)
            "TrackPosition Backward" -> MeasureMotion(offsetX = -trackWave * 0.035f, rotation = -trackWave * 4f)
            "TrackPosition Smooth" -> {
                val smooth = sin(phase * 0.5f)
                MeasureMotion(offsetY = smooth * 0.025f, scale = 1f + (smooth + 1f) * 0.025f)
            }
            "BeatRandom Shake" -> MeasureMotion(offsetX = shake * 0.045f, offsetY = sin(shake * 8f) * 0.045f)
            "BeatCamShake RotMore" -> MeasureMotion(offsetX = shake * 0.018f, offsetY = -shake * 0.018f, rotation = shake * 8f)
            "BeatCamShakeRotLess" -> MeasureMotion(offsetX = shake * 0.04f, offsetY = sin(shake * 5f) * 0.04f, rotation = shake * 2f)
            "BeatCamShake PosOnly" -> MeasureMotion(offsetX = shake * 0.05f, offsetY = -shake * 0.04f)
            "BeatZoom Shake" -> MeasureMotion(scale = 1f + beat * 0.22f)
            "Constant Shake RotMore" -> MeasureMotion(offsetX = continuous * 0.012f, offsetY = sin(runtime * 31f) * 0.012f, rotation = continuous * 5f)
            "Constant Shake RotLess" -> MeasureMotion(offsetX = continuous * 0.02f, offsetY = sin(runtime * 23f) * 0.02f, rotation = continuous * 1.2f)
            "Constant Shake Smooth" -> MeasureMotion(offsetX = continuous * 0.025f, offsetY = sin(runtime * 0.7f) * 0.025f, scale = 1f + sin(runtime * 0.55f) * 0.025f)
            "Beat Trigger Anim" -> MeasureMotion(scale = 1f + beat * 0.28f, rotation = beat * 3f)
            "Beat Trigger Reverse" -> MeasureMotion(scale = 1f + beat * 0.28f, rotation = -beat * 3f)
            "Beat PingPong" -> {
                val direction = if (beatIndex % 2L == 0L) 1f else -1f
                MeasureMotion(offsetX = direction * beat * 0.035f, scale = 1f + beat * 0.16f)
            }
            "HighFreq React" -> MeasureMotion(scale = 1f + high * 0.28f, rotation = high * 2f)
            "BassPulse Smooth" -> MeasureMotion(scale = 1f + low * 0.32f)
            // Legacy source selectors remain valid for projects created by earlier versions.
            else -> MeasureMotion()
        }
    }
}