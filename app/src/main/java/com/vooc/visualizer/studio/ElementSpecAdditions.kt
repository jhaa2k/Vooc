package com.vooc.visualizer.studio

/**
 * Additional element properties and presets taken from the per-element specification documents
 * (Shape, Blur, RGB Split, Motion Blur, Text, Particles, Image, Audio Provider, Bars/Segments).
 *
 * The element editor is generated from [ElementPropertyCatalog.forElement], so every spec added here
 * automatically gets a control, is persisted in project files and is validated/normalised like the
 * original properties. Names that already exist in the base catalog are never duplicated:
 *  - Particles: "Emitter Shape" = "Spawn Area", "Rotation Over Life" = "Rotation Speed",
 *    "Bounce Damping" = "Bounce", "Size/Velocity Randomness" = "Particle Size Random"/"Velocity Random".
 *  - Bars: "Height Scale / Amplitude" = "Amplitude Scale", "Peak Hold Fall Speed" = "Peak Fall Speed",
 *    "Bar Color Mode" = "Color Mode", "Glow / Neon Intensity" = "Glow Strength".
 *  - Audio Provider: "Spectrum Hz Range" = "Lower Hz"/"Higher Hz".
 *
 * Properties that already exist keep their stored values; options of a few enums are widened
 * (superset) via [replacements] so older projects stay valid.
 */
object ElementSpecAdditions {
    private fun t(name: String, category: String, value: String, options: List<String> = emptyList()) =
        ElementPropertySpec(
            name, category,
            if (options.isEmpty()) ElementValueType.TEXT else ElementValueType.ENUM,
            ElementValue.Text(value), options = options
        )

    private fun b(name: String, category: String, value: Boolean) =
        ElementPropertySpec(name, category, ElementValueType.BOOLEAN, ElementValue.Bool(value))

    private fun n(name: String, category: String, value: Double, min: Double, max: Double) =
        ElementPropertySpec(name, category, ElementValueType.NUMBER, ElementValue.Number(value), min, max)

    private fun i(name: String, category: String, value: Long, min: Double, max: Double) =
        ElementPropertySpec(name, category, ElementValueType.INTEGER, ElementValue.Integer(value), min, max)

    private fun v(name: String, category: String, x: Double, y: Double, min: Double, max: Double) =
        ElementPropertySpec(name, category, ElementValueType.VECTOR2, ElementValue.Vector2(x, y), min, max)

    private val blendModes = listOf(
        "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken", "Lighten", "Exclusion", "Subtractive", "Copy"
    )

    private val shapeTypes = listOf(
        "Rectangle", "Circle/Oval", "Circle", "Oval", "Line", "Triangle", "Polygon", "Star", "Heart", "Custom"
    )

    // ---- Presets (spec "Common Creative Combos") -------------------------------------------------------------
    private val shapePresets = listOf(
        "None", "Circular Progress Ring", "Soft Glow Dot", "Glass Panel", "Hexagon Grid", "Star Burst", "Audio Wave Line"
    )
    private val rgbPresets = listOf(
        "None", "Subtle Chromatic", "Classic Glitch", "3D Anaglyph", "Radial Edge Fringe", "Bass Glitch", "Psychedelic Swap"
    )
    private val motionBlurPresets = listOf(
        "None", "Classic Motion", "Speed Lines", "Ghost Echo", "Beat Shake Blur", "Spin Blur", "Glitch Trail"
    )
    private val blurPresets = listOf(
        "None", "Soft Background", "Dreamy Glow", "Motion Trail", "Bass Pulse", "Edge Blur Fill", "Glitch Blur"
    )
    private val particlePresets = listOf(
        "None", "Snow", "Sparks", "Dust", "Confetti", "Magic Orb", "Beat Explosion"
    )

    private val additions: Map<StudioElementType, List<ElementPropertySpec>> by lazy {
        mapOf(
            StudioElementType.SHAPE to shape(),
            StudioElementType.RGB_SPLIT to rgbSplit(),
            StudioElementType.MOTION_BLUR_EFFECT to motionBlur(),
            StudioElementType.BLUR_EFFECT to blur(),
            StudioElementType.TEXT to text(),
            StudioElementType.PARTICLES to particles(),
            StudioElementType.IMAGE to image(),
            StudioElementType.AUDIO_PROVIDER to audioProvider(),
            StudioElementType.BARS_SEGMENTS to bars()
        )
    }

    /** Enum specs whose option lists are widened (superset of the old options, so saved projects stay valid). */
    private val replacements: Map<StudioElementType, List<ElementPropertySpec>> by lazy {
        mapOf(
            StudioElementType.SHAPE to listOf(
                t("Shape Type", "Geometry", "Rectangle", shapeTypes)
            ),
            StudioElementType.TEXT to listOf(
                t("Font Style", "Typography", "Bold", listOf(
                    "Thin", "Light", "Regular", "Medium", "SemiBold", "Bold", "Black", "Italic", "Bold Italic"
                ))
            ),
            StudioElementType.IMAGE to listOf(
                t("Mask / Shape", "Fitting", "Rectangle", listOf(
                    "Rectangle", "None", "Circle", "Rounded Square", "Triangle", "Ellipse", "Custom"
                ))
            ),
            StudioElementType.BARS_SEGMENTS to listOf(
                t("Bar Design", "Design", "Rounded Rect", listOf(
                    "Rectangle", "Rounded Rect", "Rounded / Pill", "Capsule / Pill", "Line", "Circle / Dot",
                    "Triangle", "Diamond", "Hexagon", "Star", "Chevron", "Arrow", "Spike / Arrow", "Cross", "Wave",
                    "Hollow / Outline", "Gradient Bar", "Glow / Neon", "Dashed / Dotted", "Custom Image"
                )),
                t("Gradient Direction", "Modifiers", "Vertical", listOf(
                    "Vertical", "Horizontal", "Top-to-Bottom", "Bottom-to-Top", "Left-to-Right", "Right-to-Left"
                ))
            )
        )
    }

    /** Base catalog specs + widened enums + every missing specification property (no duplicate names). */
    fun apply(type: StudioElementType, base: List<ElementPropertySpec>): List<ElementPropertySpec> {
        val replace = replacements[type].orEmpty().associateBy { it.name }
        val merged = base.map { replace[it.name] ?: it }
        val known = HashSet<String>()
        merged.forEach { known.add(it.name) }
        return merged + additions[type].orEmpty().filter { known.add(it.name) }
    }

    // ---- SHAPE -----------------------------------------------------------------------------------------------
    private fun shape() = listOf(
        t("Preset", "Presets", "None", shapePresets),
        i("Polygon Sides", "Geometry", 6, 3.0, 64.0),
        i("Star Points", "Geometry", 5, 3.0, 64.0),
        n("Star Inner Radius", "Geometry", 0.45, 0.05, 0.95),
        n("Line Thickness", "Geometry", 4.0, 0.0, 512.0),
        t("Custom Path (SVG)", "Geometry", ""),
        b("Fill Enabled", "Fill", true),
        b("Stroke Enabled", "Stroke", false),
        n("Stroke Dash Length", "Stroke", 12.0, 0.0, 512.0),
        n("Stroke Dash Gap", "Stroke", 8.0, 0.0, 512.0),
        n("Corner Smoothing", "Effects", 0.0, 0.0, 1.0),
        b("Inner Shadow", "Effects", false),
        b("Outer Shadow", "Effects", false),
        b("Glow / Neon", "Effects", false),
        t("Glow Color", "Effects", "#FFC6F36A"),
        n("Glow Intensity", "Effects", 1.0, 0.0, 4.0),
        b("Trim Path Enabled", "Trim Path", false),
        n("Trim Start", "Trim Path", 0.0, 0.0, 1.0),
        n("Trim End", "Trim Path", 1.0, 0.0, 1.0),
        n("Trim Offset", "Trim Path", 0.0, -1.0, 1.0),
        t("Trim Mode", "Trim Path", "Clockwise", listOf("Clockwise", "Counter-Clockwise")),
        t("Shape Texture / Image Fill", "Advanced", ""),
        n("Texture Tiling", "Advanced", 1.0, 0.1, 32.0),
        b("Animate Trim on Beat", "Advanced", false),
        b("Pulse on Bass", "Advanced", false),
        b("Color Cycle", "Advanced", false),
        b("Wobble / Distort", "Advanced", false),
        n("Wobble Speed", "Advanced", 1.0, 0.0, 20.0),
        n("Wobble Amount", "Advanced", 0.1, 0.0, 2.0)
    )

    // ---- RGB SPLIT -------------------------------------------------------------------------------------------
    private fun rgbSplit() = listOf(
        t("Preset", "Presets", "None", rgbPresets),
        v("Radial Center", "Radial", 0.5, 0.5, 0.0, 1.0),
        b("Invert Radial", "Radial", false),
        b("Edge Clamp", "Radial", false),
        b("Channel Swap", "Glitch", false),
        b("Luma Split", "Glitch", false),
        n("Noise / Jitter Amount", "Glitch", 0.0, 0.0, 1.0),
        n("Noise Speed / Frequency", "Glitch", 1.0, 0.0, 100.0),
        b("Strobe / Glitch Pulse", "Glitch", false),
        n("Glitch Pulse Rate", "Glitch", 4.0, 0.0, 60.0),
        b("Scanline Offset", "Glitch", false),
        b("Pulse on Bass", "Audio Reactivity", false),
        n("Bass Sensitivity", "Audio Reactivity", 1.0, 0.0, 10.0),
        b("Beat Glitch Kick", "Audio Reactivity", false),
        n("Glitch Decay Speed", "Audio Reactivity", 4.0, 0.0, 60.0),
        b("High Freq React", "Audio Reactivity", false)
    )

    // ---- MOTION BLUR -----------------------------------------------------------------------------------------
    private fun motionBlur() = listOf(
        t("Preset", "Presets", "None", motionBlurPresets),
        n("Blur Falloff", "Core", 0.5, 0.0, 1.0),
        n("Trail Spacing", "Trails", 0.05, 0.0, 1.0),
        t("Trail Decay Curve", "Trails", "Linear", listOf("Linear", "Exponential", "Custom")),
        b("Reverse Trail", "Trails", false),
        n("Zoom Blur Amount", "Radial", 0.0, 0.0, 10.0),
        n("Radial Falloff", "Radial", 0.0, 0.0, 10.0),
        n("Velocity Multiplier", "Velocity", 1.0, 0.0, 64.0),
        b("Track Rotation", "Velocity", false),
        n("Max Speed Threshold", "Velocity", 100.0, 0.0, 10000.0),
        b("Chromatic Motion Blur", "Advanced", false),
        n("Noise / Grain in Trail", "Advanced", 0.0, 0.0, 1.0),
        b("Strobe / Stutter", "Advanced", false),
        n("Blur Saturation", "Advanced", 1.0, 0.0, 1.0),
        b("Edge Clamp", "Advanced", false)
    )

    // ---- BLUR ------------------------------------------------------------------------------------------------
    private fun blur() = listOf(
        t("Preset", "Presets", "None", blurPresets),
        t("Blur Type", "Blur Core", "Gaussian", listOf("Gaussian", "Box", "Motion", "Radial", "Stack")),
        t("Blur Curve / Falloff", "Blur Core", "Linear", listOf("Linear", "Ease-In", "Ease-Out", "Smoothstep")),
        n("Layer Opacity", "Multi-Layer", 1.0, 0.0, 1.0),
        b("Invert Mask", "Targeting", false),
        b("Blur Edges Only", "Targeting", false),
        n("Edge Blur Falloff", "Targeting", 0.3, 0.0, 1.0),
        b("Pulse on Bass", "Audio Reactivity", false),
        n("Bass Sensitivity", "Audio Reactivity", 1.0, 0.0, 10.0),
        b("Beat Blur Kick", "Audio Reactivity", false),
        n("Blur Decay Speed", "Audio Reactivity", 4.0, 0.0, 60.0),
        b("Chromatic Blur", "Advanced", false),
        n("Blur Angle", "Advanced", 0.0, 0.0, 360.0),
        n("Noise / Grain Overlay", "Advanced", 0.0, 0.0, 1.0),
        b("Vignette", "Advanced", false)
    )

    // ---- TEXT ------------------------------------------------------------------------------------------------
    private fun text() = listOf(
        t("Text Mode", "Content", "Static", listOf("Static", "Dynamic")),
        t("Dynamic Tags", "Content", "{Title} - {Artist}"),
        n("Word Spacing", "Typography", 0.0, -20.0, 100.0),
        t("Vertical Alignment", "Layout", "Middle", listOf("Top", "Middle", "Bottom")),
        i("Max Lines", "Layout", 0, 0.0, 100.0),
        b("Ellipsis / Truncate", "Layout", false),
        b("Gradient Fill", "Color & Effects", false),
        n("Text Gradient Angle", "Color & Effects", 90.0, -360.0, 360.0),
        b("Glow / Outer Glow", "Color & Effects", false),
        t("Glow Color", "Color & Effects", "#FFC6F36A"),
        n("Glow Radius", "Color & Effects", 12.0, 0.0, 128.0),
        n("Glow Intensity", "Color & Effects", 1.0, 0.0, 4.0),
        b("Background / Highlight", "Color & Effects", false),
        t("Background Color", "Color & Effects", "#66000000"),
        b("Scroll / Marquee", "Animation", false),
        n("Scroll Speed", "Animation", 60.0, 0.0, 2000.0),
        t("Scroll Direction", "Animation", "Left", listOf("Left", "Right", "Up", "Down")),
        b("Typewriter Effect", "Animation", false),
        n("Typewriter Speed", "Animation", 12.0, 0.1, 200.0),
        b("Beat Reactive Text", "Animation", false),
        b("Color Cycle", "Animation", false),
        t("Text Texture / Image Fill", "Advanced", ""),
        t("Text Mask", "Advanced", ""),
        b("Reflection", "Advanced", false),
        b("3D / Extrusion", "Advanced", false),
        b("Character Spacing Animation", "Advanced", false),
        b("Word-by-Word Reveal", "Advanced", false),
        b("Karaoke Highlight", "Advanced", false)
    )

    // ---- PARTICLES -------------------------------------------------------------------------------------------
    private fun particles() = listOf(
        t("Preset", "Presets", "None", particlePresets),
        n("Noise Speed", "Physics", 1.0, 0.0, 100.0),
        n("Vortex / Swirl", "Physics", 0.0, -2000.0, 2000.0),
        n("Attractor / Repeller", "Physics", 0.0, -2000.0, 2000.0),
        n("Texture Tiling / Stretch", "Rendering", 1.0, 0.1, 32.0),
        t("Size Over Life", "Rendering", "Shrink", listOf("Constant", "Grow", "Shrink", "Pulse", "Custom")),
        t("Particle Blend Mode", "Rendering", "Alpha", listOf("Add", "Screen", "Alpha", "Multiply")),
        b("Spawn on Beat", "Audio Reactivity", false),
        i("Beat Burst Count", "Audio Reactivity", 24, 0.0, 2000.0),
        b("Bass Reactive Emission", "Audio Reactivity", false),
        b("High Freq Reactive Size", "Audio Reactivity", false),
        b("Gravity Flip on Beat", "Advanced", false),
        b("Trail / Afterimage", "Advanced", false),
        n("Trail Length", "Advanced", 0.2, 0.0, 10.0),
        n("Color Randomness", "Advanced", 0.0, 0.0, 1.0),
        i("Random Seed", "Advanced", 1, 0.0, 2147483647.0),
        t("Simulation Quality", "Advanced", "Balanced", listOf("Low", "Balanced", "High"))
    )

    // ---- IMAGE -----------------------------------------------------------------------------------------------
    private fun image() = listOf(
        t("Dynamic Image Tag", "Source", "None", listOf("None", "Album Art", "Custom")),
        n("Overlay Opacity", "Effects", 1.0, 0.0, 1.0),
        b("Grayscale", "Effects", false),
        n("Brightness", "Effects", 0.0, -1.0, 1.0),
        n("Contrast", "Effects", 1.0, 0.0, 2.0),
        n("Image Rotation Speed (Vinyl Mode)", "Advanced", 0.0, -720.0, 720.0),
        b("Spin on Beat", "Advanced", false),
        b("Pulse on Beat", "Advanced", false),
        b("Color Grading / Duotone", "Advanced", false),
        t("Duotone Shadow Color", "Advanced", "#FF0C0E0B"),
        t("Duotone Highlight Color", "Advanced", "#FFC6F36A"),
        b("Shadow Enabled", "Advanced", false),
        t("Shadow Color", "Advanced", "#99000000"),
        v("Shadow Offset", "Advanced", 4.0, 4.0, -256.0, 256.0),
        n("Shadow Blur", "Advanced", 8.0, 0.0, 128.0),
        t("Border Color", "Advanced", "#FFFFFFFF"),
        n("Border Width", "Advanced", 0.0, 0.0, 128.0)
    )

    // ---- AUDIO PROVIDER --------------------------------------------------------------------------------------
    private fun audioProvider() = listOf(
        t("Waveform Type", "Data Source", "Spectrum2", listOf("Waveform", "Spectrum1", "Spectrum2")),
        i("Duration (Ms)", "Data Source", 30, 1.0, 1000.0),
        b("Filter Enable", "Noise Filter", true),
        n("Low Freq Magnitude", "Frequency", 1.0, 0.0, 16.0)
    )

    // ---- BARS / SEGMENTS -------------------------------------------------------------------------------------
    private fun bars() = listOf(
        t("Spectrum Shape / Type", "Shape", "Bars", listOf("Bars", "Lines", "Dots", "Circle / Radial", "Waveform", "Liquid")),
        n("Roundness / Corner Radius", "Shape", 0.22, 0.0, 1.0),
        t("Glow Color", "Color", "#FF56D8FF"),
        n("Radius / Ring Size", "Radial", 240.0, 0.0, 4096.0),
        n("Start Angle", "Radial", 0.0, 0.0, 360.0),
        n("Angle Spread", "Radial", 360.0, 0.0, 360.0),
        t("Radial Direction", "Radial", "Outward", listOf("Outward", "Inward")),
        n("Line Thickness", "Line", 3.0, 0.0, 256.0),
        n("Line Smoothing / Tension", "Line", 0.5, 0.0, 1.0),
        b("Close Path", "Line", false),
        t("Bar Texture / Image", "Advanced", ""),
        n("Texture Tiling / Scaling", "Advanced", 1.0, 0.1, 32.0),
        b("Color Over Height", "Advanced", false),
        t("Frequency Band Focus", "Advanced", "Full Range", listOf("Full Range", "Bass Only", "Mids Only", "Highs Only", "Custom Hz Range")),
        i("Hz Range Min", "Advanced", 20, 1.0, 96000.0),
        i("Hz Range Max", "Advanced", 20000, 2.0, 96000.0),
        n("Bar Jitter / Randomness", "Advanced", 0.0, 0.0, 1.0),
        t("Stereo Mode", "Advanced", "Mono", listOf("Mono", "Stereo Left", "Stereo Right", "Stereo Split"))
    )

    // ---- Preset application ----------------------------------------------------------------------------------
    private fun num(value: Double): ElementValue = ElementValue.Number(value)
    private fun int(value: Long): ElementValue = ElementValue.Integer(value)
    private fun flag(value: Boolean): ElementValue = ElementValue.Bool(value)
    private fun str(value: String): ElementValue = ElementValue.Text(value)
    private fun vec(x: Double, y: Double): ElementValue = ElementValue.Vector2(x, y)

    /** Property values a preset sets. Unknown presets and "None" change nothing. */
    fun presetValues(type: StudioElementType, preset: String): Map<String, ElementValue> = when (type) {
        StudioElementType.SHAPE -> when (preset) {
            "Circular Progress Ring" -> mapOf(
                "Shape Type" to str("Circle/Oval"), "Stroke Enabled" to flag(true), "Stroke / Border Width" to num(14.0),
                "Fill Enabled" to flag(false), "Trim Path Enabled" to flag(true), "Animate Trim on Beat" to flag(true)
            )
            "Soft Glow Dot" -> mapOf(
                "Shape Type" to str("Circle/Oval"), "Glow / Neon" to flag(true), "Fill Enabled" to flag(true),
                "Corner Radius" to num(4096.0)
            )
            "Glass Panel" -> mapOf(
                "Shape Type" to str("Rectangle"), "Fill Enabled" to flag(true), "Fill / Color" to str("#33FFFFFF"),
                "Stroke Enabled" to flag(true), "Stroke / Border Width" to num(2.0), "Stroke Color" to str("#FFFFFFFF"),
                "Outer Shadow" to flag(true), "Corner Radius" to num(24.0)
            )
            "Hexagon Grid" -> mapOf(
                "Shape Type" to str("Polygon"), "Polygon Sides" to int(6), "Stroke Enabled" to flag(true),
                "Stroke / Border Width" to num(3.0), "Fill Enabled" to flag(false)
            )
            "Star Burst" -> mapOf(
                "Shape Type" to str("Star"), "Star Points" to int(5), "Fill Enabled" to flag(true),
                "Pulse on Bass" to flag(true)
            )
            "Audio Wave Line" -> mapOf(
                "Shape Type" to str("Line"), "Stroke Enabled" to flag(true), "Stroke / Border Width" to num(4.0),
                "Wobble / Distort" to flag(true)
            )
            else -> emptyMap()
        }
        StudioElementType.RGB_SPLIT -> when (preset) {
            "Subtle Chromatic" -> mapOf(
                "Red Offset X" to num(5.0), "Blue Offset X" to num(-5.0), "Green Offset X" to num(0.0),
                "Red Offset Y" to num(0.0), "Green Offset Y" to num(0.0), "Blue Offset Y" to num(0.0),
                "Split Amount / Distance" to num(1.0)
            )
            "Classic Glitch" -> mapOf(
                "Split Amount / Distance" to num(32.0), "Noise / Jitter Amount" to num(0.1), "Strobe / Glitch Pulse" to flag(true)
            )
            "3D Anaglyph" -> mapOf(
                "Red Offset X" to num(10.0), "Green Offset X" to num(0.0), "Blue Offset X" to num(-10.0),
                "Angle / Direction" to num(0.0)
            )
            "Radial Edge Fringe" -> mapOf(
                "Falloff / Radial Split" to num(0.5), "Radial Center" to vec(0.5, 0.5), "Center / Focus Point" to vec(0.5, 0.5)
            )
            "Bass Glitch" -> mapOf(
                "Pulse on Bass" to flag(true), "Bass Sensitivity" to num(2.0), "Beat Glitch Kick" to flag(true)
            )
            "Psychedelic Swap" -> mapOf(
                "Channel Swap" to flag(true), "Noise / Jitter Amount" to num(0.3), "Angle / Direction" to num(45.0)
            )
            else -> emptyMap()
        }
        StudioElementType.MOTION_BLUR_EFFECT -> when (preset) {
            "Classic Motion" -> mapOf(
                "Blur Amount / Strength" to num(5.0), "Blur Length" to num(48.0),
                "Blur Direction / Angle" to num(0.0), "Samples / Iterations" to int(16)
            )
            "Speed Lines" -> mapOf("Radial Motion" to flag(true), "Zoom Blur Amount" to num(4.0))
            "Ghost Echo" -> mapOf(
                "Trails / Echo" to int(3), "Trail Spacing" to num(0.05), "Trail Opacity / Fade" to num(0.3),
                "Trail Decay Curve" to str("Linear")
            )
            "Beat Shake Blur" -> mapOf(
                "Measure Pos (Measure What)" to str("BeatRandom Shake"), "Blur Amount / Strength" to num(8.0)
            )
            "Spin Blur" -> mapOf(
                "Track Rotation" to flag(true), "Blur Amount / Strength" to num(10.0), "Radial Motion" to flag(true)
            )
            "Glitch Trail" -> mapOf(
                "Chromatic Motion Blur" to flag(true), "Strobe / Stutter" to flag(true), "Noise / Grain in Trail" to num(0.2)
            )
            else -> emptyMap()
        }
        StudioElementType.BLUR_EFFECT -> when (preset) {
            "Soft Background" -> mapOf(
                "Blur Radius" to num(15.0), "Blur Multiplier" to num(1.0), "1layer Scale" to vec(1.0, 1.0),
                "2layer Scale" to vec(0.0, 0.0), "3layer Scale" to vec(0.0, 0.0)
            )
            "Dreamy Glow" -> mapOf(
                "Blur Radius" to num(10.0), "Blend Mode" to str("Add"), "1layer Scale" to vec(1.0, 1.0),
                "2layer Scale" to vec(1.05, 1.05), "1layer Color" to str("#FFFFF1F8"), "2layer Color" to str("#FFE8F0FF")
            )
            "Motion Trail" -> mapOf(
                "Blur Radius" to num(5.0), "1layer Scale" to vec(1.0, 1.0), "2layer Scale" to vec(1.05, 1.05),
                "3layer Scale" to vec(1.1, 1.1), "Layer Opacity" to num(0.4)
            )
            "Bass Pulse" -> mapOf(
                "Blur Radius" to num(5.0), "Pulse on Bass" to flag(true), "Bass Sensitivity" to num(1.5)
            )
            "Edge Blur Fill" -> mapOf("Blur Edges Only" to flag(true), "Edge Blur Falloff" to num(0.3))
            "Glitch Blur" -> mapOf("Chromatic Blur" to flag(true), "Noise / Grain Overlay" to num(0.2))
            else -> emptyMap()
        }
        StudioElementType.PARTICLES -> when (preset) {
            "Snow" -> mapOf(
                "Gravity" to vec(0.0, 40.0), "Wind" to vec(15.0, 0.0), "Start Size" to num(14.0),
                "Start Velocity" to num(20.0), "Particle Blend Mode" to str("Alpha"), "Color" to str("#FFFFFFFF")
            )
            "Sparks" -> mapOf(
                "Gravity" to vec(0.0, -60.0), "Start Size" to num(3.0), "Start Velocity" to num(240.0),
                "Life Span" to num(0.8), "Particle Blend Mode" to str("Add")
            )
            "Dust" -> mapOf(
                "Start Velocity" to num(8.0), "Start Size" to num(2.0), "Emission Rate" to num(8.0),
                "Particle Blend Mode" to str("Alpha"), "Alpha Over Life" to str("Fade In Out")
            )
            "Confetti" -> mapOf(
                "Rotation Speed" to num(360.0), "Gravity" to vec(0.0, 120.0), "Color Randomness" to num(1.0),
                "Start Size" to num(10.0)
            )
            "Magic Orb" -> mapOf(
                "Spawn Area" to str("Circle"), "Vortex / Swirl" to num(80.0), "Particle Blend Mode" to str("Add")
            )
            "Beat Explosion" -> mapOf(
                "Spawn on Beat" to flag(true), "Beat Burst Count" to int(64), "Start Velocity" to num(300.0),
                "Life Span" to num(0.9), "Spread" to num(360.0)
            )
            else -> emptyMap()
        }
        else -> emptyMap()
    }
}
