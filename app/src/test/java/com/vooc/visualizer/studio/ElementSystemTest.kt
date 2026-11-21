package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementSystemTest {
    @Test
    fun everyCatalogHasUniqueNamesValidDefaultsAndConsistentBounds() {
        val invalidDefaults = mutableListOf<String>()
        val invalidEnumDefaults = mutableListOf<String>()
        StudioElementType.entries.forEach { type ->
            val specs = ElementPropertyCatalog.forElement(type)
            val duplicateNames = specs.groupBy { it.name }.filterValues { it.size > 1 }.keys
            assertTrue("$type has duplicate property names: $duplicateNames", duplicateNames.isEmpty())
            specs.forEach { spec ->
                assertTrue("$type/${spec.name} has a blank category", spec.category.isNotBlank())
                if (spec.validate(spec.defaultValue) == null) invalidDefaults += "$type/${spec.name}: type=${spec.type}, default=${spec.defaultValue}, options=${spec.options}"
                if (spec.type == ElementValueType.ENUM) {
                    val default = (spec.defaultValue as ElementValue.Text).value
                    if (default !in spec.options) invalidEnumDefaults += "$type/${spec.name}: default='$default', options=${spec.options}"
                }
                if (spec.min != null && spec.max != null) {
                    assertTrue("$type/${spec.name} has inverted bounds", spec.min <= spec.max)
                }
                when (val value = spec.defaultValue) {
                    is ElementValue.Number -> {
                        spec.min?.let { assertTrue("$type/${spec.name} default below minimum", value.value >= it) }
                        spec.max?.let { assertTrue("$type/${spec.name} default above maximum", value.value <= it) }
                    }
                    is ElementValue.Integer -> {
                        spec.min?.let { assertTrue("$type/${spec.name} default below minimum", value.value.toDouble() >= it) }
                        spec.max?.let { assertTrue("$type/${spec.name} default above maximum", value.value.toDouble() <= it) }
                    }
                    else -> Unit
                }
            }
        }
        assertTrue("Invalid catalog defaults: " + invalidDefaults.joinToString("; "), invalidDefaults.isEmpty())
        assertTrue("Invalid enum defaults: " + invalidEnumDefaults.joinToString("; "), invalidEnumDefaults.isEmpty())
    }
    @Test
    fun editingAnElementPreservesItsCompositionStackPosition() {
        val first = ElementPropertyCatalog.create(StudioElementType.IMAGE, "first")
        val middle = ElementPropertyCatalog.create(StudioElementType.TEXT, "middle")
        val last = ElementPropertyCatalog.create(StudioElementType.SHAPE, "last")
        val changed = middle.withValue("Text", ElementValue.Text("Updated"))
        val result = listOf(first, middle, last).replacePreservingOrder(changed)
        assertEquals(listOf("first", "middle", "last"), result.map { it.id })
        assertEquals(ElementValue.Text("Updated"), result[1].value("Text"))
    }

    @Test
    fun catalogContainsAllSpecificationElementTypes() {
        assertEquals(
            setOf("Audio Provider", "Image", "Background", "Rgb Split", "Blur Effect", "Bars/Segments", "Particles", "Text", "Motion Blur Effect", "Shape", "Mirror Effect", "Custom Shader", "Composition"),
            StudioElementType.entries.map { it.displayName }.toSet()
        )
        StudioElementType.entries.forEach { type ->
            assertTrue("$type should expose a property schema", ElementPropertyCatalog.forElement(type).isNotEmpty())
        }
    }

    @Test
    fun mirrorShaderAndCompositionExposeTheirFullControlGroups() {
        val mirror = ElementPropertyCatalog.forElement(StudioElementType.MIRROR_EFFECT).map { it.name }.toSet()
        val shader = ElementPropertyCatalog.forElement(StudioElementType.CUSTOM_SHADER).map { it.name }.toSet()
        val composition = ElementPropertyCatalog.forElement(StudioElementType.COMPOSITION).map { it.name }.toSet()
        assertTrue(mirror.containsAll(setOf("Target Image", "Mirror Axis", "Mirror Position", "Mirror Offset X", "Mirror Offset Y", "Mirror Gap", "Mirror Feather", "Reflection Count", "Reflection Opacity", "Mirror Original", "Mirror Flip", "Mirror Blend Mode")))
        assertTrue(shader.containsAll(setOf("Shader Name", "Shader Source", "Shader Enabled", "Shader Language", "Shader Entry Point", "Shader Version", "Shader Time Scale", "Shader Amount", "Shader Input Scale", "Shader Output Opacity", "Shader Uniform 1 Name", "Shader Uniform 1 Value", "Shader Uniform 2 Name", "Shader Uniform 2 Value", "Shader Uniform 3 Name", "Shader Uniform 3 Value", "Shader Blend Mode", "Shader Use Audio", "Shader Audio Source")))
        assertTrue(composition.containsAll(setOf("Composition Name", "Composition Enabled", "Composition Width", "Composition Height", "Composition FPS", "Composition Background", "Composition Blend Mode", "Composition Opacity", "Composition Source Index", "Parent Composition Index", "Composition Start Time", "Composition Duration", "Composition Time Offset", "Composition Loop", "Composition Isolated Audio", "Composition Export Enabled")))
    }

    @Test
    fun addLayerCatalogContainsNoDuplicateCommonElementTypes() {
        val names = LayerAddMenuCatalog.spectrumStyles.map { it.title } +
            LayerAddMenuCatalog.elementTypes.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
        listOf("Image", "Text", "Shape", "Background", "Particles").forEach { name ->
            assertEquals("Expected exactly one $name entry", 1, names.count { it == name })
        }
        assertTrue(LayerAddMenuCatalog.elementTypes.contains(StudioElementType.BARS_SEGMENTS))
        assertTrue(LayerAddMenuCatalog.elementTypes.contains(StudioElementType.AUDIO_PROVIDER))
        assertEquals(1, LayerAddMenuCatalog.elementTypes.count { it == StudioElementType.AUDIO_PROVIDER })
    }

    @Test
    fun sharedGeneralPropertiesAreConsistentAcrossVisualElements() {
        val expected = setOf(
            "Tag", "Visible", "Blend Mode", "Position", "Anchor X", "Anchor Y",
            "Measure Pos (Measure What)", "Alignment Position", "Scale Is Uniform",
            "Scale", "Measure Scale (Measure What)", "Rotation", "Measure Rot (Measure What)",
            "Opacity", "Measure Opacity (Measure What)"
        )
        val visualTypes = StudioElementType.entries.filter { it != StudioElementType.AUDIO_PROVIDER }
        visualTypes.forEach { type ->
            assertTrue("$type is missing shared properties", ElementPropertyCatalog.forElement(type).map { it.name }.containsAll(expected))
        }
    }

    @Test
    fun everyVisualLayerExposesTheSharedAudioReactivityControlSet() {
        val required = setOf(
            "Audio Reactivity Enabled", "Audio Reactivity Source", "Audio Reactivity Apply To",
            "Audio Reactivity Input Min", "Audio Reactivity Input Max",
            "Audio Reactivity Output Min", "Audio Reactivity Output Max",
            "Audio Reactivity Multiplier", "Audio Reactivity Offset",
            "Audio Reactivity Smoothing", "Audio Reactivity Attack",
            "Audio Reactivity Release", "Audio Reactivity Response Curve",
            "Audio Reactivity Invert", "Audio Reactivity Clamp Output",
            "Audio Reactivity Beat Trigger", "Audio Reactivity Beat Amount",
            "Audio Reactivity Frequency Band Start", "Audio Reactivity Frequency Band End",
            "Audio Reactivity Threshold"
        )
        StudioElementType.entries.filter { it != StudioElementType.AUDIO_PROVIDER }.forEach { type ->
            val names = ElementPropertyCatalog.forElement(type).map { it.name }.toSet()
            assertTrue("$type missing shared audio-reactivity controls: ${required - names}", names.containsAll(required))
        }
    }

    @Test
    fun everyVisualLayerExposesTheSharedAnimationControlSet() {
        val required = setOf(
            "Animation Enabled", "Animation Start Delay", "Animation Duration",
            "Animation Playback Mode", "Animation Time Mode", "Animation Time Offset",
            "Animation Speed", "Animation Easing", "Animation Interpolation",
            "Animation Loop Count", "Animation Start On Beat", "Animation Beat Offset",
            "Animation Reset On Track Change", "Animation Hold First Frame",
            "Animation Hold Last Frame"
        )
        StudioElementType.entries.filter { it != StudioElementType.AUDIO_PROVIDER }.forEach { type ->
            val names = ElementPropertyCatalog.forElement(type).map { it.name }.toSet()
            assertTrue("$type missing animation controls: ${required - names}", names.containsAll(required))
        }
    }

    @Test
    fun propertyValuesAreTypeCheckedAndClamped() {
        val element = ElementPropertyCatalog.create(StudioElementType.BARS_SEGMENTS, "bars-1")
        val bounded = element.withValue("Bar Count", ElementValue.Number(5000.0))
        assertEquals(ElementValue.Integer(256L), bounded.value("Bar Count"))
        val invalid = element.withValue("Visible", ElementValue.Text("yes"))
        assertEquals(ElementValue.Bool(true), invalid.value("Visible"))
    }

    @Test
    fun spectrumBarsExposeTheRequestedLayoutAudioAndAppearanceControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.BARS_SEGMENTS).associateBy { it.name }
        val required = setOf(
            "Opacity", "Measure Opacity (Measure What)", "Bar Count", "Segments Per Bar",
            "Orientation", "Shape Path", "Corner Radius", "Sensitivity", "Attack Smoothing",
            "Release Smoothing", "Frequency Scale", "Mirror Mode", "Color Mode",
            "Per-Bar Color", "Peak Indicator Style", "Peak Indicator Color"
        )
        assertTrue("Missing spectrum controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(8.0, specs.getValue("Bar Count").min!!, 0.0)
        assertEquals(256.0, specs.getValue("Bar Count").max!!, 0.0)
        assertEquals(
            listOf("Horizontal", "Vertical", "Radial", "Mirror"),
            specs.getValue("Orientation").options
        )
        assertEquals(
            MeasureWhatEngine.options,
            specs.getValue("Measure Opacity (Measure What)").options
        )
    }

    @Test
    fun particleEmitterDropdownOnlyOffersImplementedShapesAndDefaultsToVisibleSpread() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.PARTICLES).associateBy { it.name }
        assertEquals(
            listOf("Point", "HorizontalLine", "Rectangle", "Ellipse", "Circle", "Line", "Edge"),
            specs.getValue("Spawn Area").options
        )
        assertEquals(
            listOf("Point", "Line", "HorizontalLine", "Rectangle", "Ellipse", "Circle", "Ring", "Edge"),
            specs.getValue("Particle Emitter Shape").options
        )
        assertEquals(ElementValue.Text("Circle"), specs.getValue("Particle Emitter Shape").defaultValue)
        assertEquals(
            ElementValue.Text("Circle"),
            ElementPropertyCatalog.create(StudioElementType.PARTICLES, "particles-default").value("Particle Emitter Shape")
        )
    }

    @Test
    fun particleSpeedMeasurementDropdownOnlyOffersImplementedSources() {
        val spec = ElementPropertyCatalog.forElement(StudioElementType.PARTICLES)
            .single { it.name == "Measure Overall Speed (Measure What)" }
        assertEquals(
            listOf("Nothing", "Beat", "Bass", "Mid", "Treble", "RMS", "Peak", "Overall Audio Level"),
            spec.options
        )
    }

    @Test
    fun audioProviderExposesOnlyImplementedAnalysisControls() {
        val names = ElementPropertyCatalog.forElement(StudioElementType.AUDIO_PROVIDER).map { it.name }.toSet()
        val required = setOf(
            "Tag", "Provider Enabled", "Sample Out Count", "Mirror Samples",
            "Repeat Samples", "Star And End Gap", "Smooth", "Pre Smooth", "Filter Radius",
            "Filter Strength", "AWeight", "Output Multiplier", "Lower Hz", "Higher Hz",
            "Hz Linear Factor", "Freq Shift", "Beat Smooth", "Beat Range Bar First",
            "Beat Range Bar Last", "Beat Range Value Lower", "Beat Range Value Higher",
            "Analysis Mode", "Channel Mode", "Beat Detection Enabled", "Window Function",
            "Beat Sensitivity", "Beat Threshold", "Beat Release", "Fft Size"
        )
        assertTrue("Missing audio provider controls: ${required - names}", names.containsAll(required))
        assertEquals(listOf("256", "512", "1024", "2048", "4096", "8192"), ElementPropertyCatalog.forElement(StudioElementType.AUDIO_PROVIDER).first { it.name == "Fft Size" }.options)
        val unsupported = setOf(
            "Audio Input Source", "Input Gain", "Spectrum Scale", "Spectrum Compression",
            "Beat Detection Mode", "Audio Output Signal", "Analysis Update Rate"
        )
        assertTrue("Inactive provider controls must not be exposed: ${names intersect unsupported}", (names intersect unsupported).isEmpty())
    }

    @Test
    fun shapeLayerExposesAdvancedGeometryPathStrokeAndFillControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.SHAPE).associateBy { it.name }
        val required = setOf(
            "Polygon Sides", "Star Points", "Star Inner Radius", "Ellipse Start Angle",
            "Ellipse Sweep Angle", "Custom Path Data", "Fill Rule", "Stroke Cap",
            "Stroke Join", "Stroke Alignment", "Stroke Miter Limit", "Stroke Dash Pattern",
            "Stroke Dash Offset", "Trim Start", "Trim End", "Trim Offset", "Gradient Spread",
            "Gradient Center", "Gradient Radius", "Shape Shadow Enabled", "Shape Shadow Color",
            "Shape Shadow Blur", "Shape Shadow Offset X", "Shape Shadow Offset Y",
            "Shape Blur", "Skew X", "Skew Y"
        )
        assertTrue("Missing advanced shape controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(listOf("Non Zero", "Even Odd"), specs.getValue("Fill Rule").options)
        assertEquals(listOf("Inside", "Center", "Outside"), specs.getValue("Stroke Alignment").options)
        assertFalse("PreMulAlpha has no distinct renderer implementation", specs.getValue("Blend Mode Content").options.contains("PreMulAlpha"))
    }

    @Test
    fun audioProviderDoesNotExposeUnimplementedAdvancedControls() {
        val names = ElementPropertyCatalog.forElement(StudioElementType.AUDIO_PROVIDER).map { it.name }.toSet()
        val unsupported = setOf(
            "Audio Input Source", "Input Channel", "Input Gain", "Input Offset",
            "Input Auto Normalize", "Input Noise Gate", "Input Noise Gate Smoothing",
            "Input DC Removal", "Stereo Width", "Stereo Balance", "Spectrum Output Mode",
            "Spectrum Scale", "Spectrum Floor", "Spectrum Ceiling", "Spectrum Compression",
            "Spectrum Noise Reduction", "Spectrum Averaging Frames", "Spectrum Peak Normalize",
            "Spectrum Logarithmic Smoothing", "Frequency Mapping Bands", "Frequency Mapping Overlap",
            "Beat Detection Mode", "Beat Frequency Range", "Beat Minimum Interval",
            "Beat Adaptive Sensitivity", "Beat Onset Strength", "Beat Decay", "Beat Quantize",
            "Beat Quantize Strength", "Beat Ignore Low Energy", "Beat Low Energy Threshold",
            "Audio Output Signal", "Audio Output Smoothing", "Audio Output Clamp",
            "Audio Output Hold Peak", "Audio Output Peak Hold Time", "Analysis Update Rate",
            "Analysis Buffer Size", "Analysis Multithreaded"
        )
        assertTrue("Inactive advanced provider controls must not be exposed: ${names intersect unsupported}", (names intersect unsupported).isEmpty())
    }

    @Test
    fun blurAndRgbSplitExposeTheirCompleteEffectControlGroups() {
        val blur = ElementPropertyCatalog.forElement(StudioElementType.BLUR_EFFECT).map { it.name }.toSet()
        val rgb = ElementPropertyCatalog.forElement(StudioElementType.RGB_SPLIT).map { it.name }.toSet()
        assertTrue(blur.containsAll(setOf(
            "Blur Radius", "Blur Multiplier", "1layer Scale", "2layer Scale", "3layer Scale",
            "1layer Color", "2layer Color", "3layer Color", "Show Unblurred Content",
            "Show Unblurred Content Under", "Opacity", "Measure Opacity (Measure What)"
        )))
        assertTrue(rgb.containsAll(setOf(
            "Red Offset X", "Red Offset Y", "Green Offset X", "Green Offset Y",
            "Blue Offset X", "Blue Offset Y", "Split Amount / Distance", "Angle / Direction",
            "Center / Focus Point", "Falloff / Radial Split", "Speed / Frequency",
            "Split Multiplier", "Split Color 0", "Split Color 1", "Split Color 2",
            "Opacity", "Measure Opacity (Measure What)"
        )))
    }

    @Test
    fun rgbSplitExposesMaskBlendAndOriginalContentControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.RGB_SPLIT).associateBy { it.name }
        val required = setOf(
            "Target Image", "Blend Mode Content", "Color",
            "Mask Image", "Show Unblurred Content", "Show Unblurred Content Under",
            "Split Amount", "Split Amount / Distance", "Split Multiplier",
            "Angle / Direction", "Speed / Frequency", "Center / Focus Point",
            "Falloff / Radial Split", "Red Offset X", "Red Offset Y",
            "Green Offset X", "Green Offset Y", "Blue Offset X", "Blue Offset Y",
            "Split Color 0", "Split Color 1", "Split Color 2"
        )
        assertTrue("Missing RGB Split controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertFalse("Source Composition Index" in specs)
        assertEquals(
            listOf("Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken", "Lighten", "Exclusion", "Subtractive", "Copy"),
            specs.getValue("Blend Mode Content").options
        )
    }

    @Test
    fun blurEffectExposesThreeLayerScaleColorAndIndependentOriginalControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.BLUR_EFFECT).associateBy { it.name }
        val required = setOf(
            "Target Image", "Blend Mode Content", "Color", "Source Composition Index",
            "Mask Image", "Show Unblurred Content", "Show Unblurred Content Under",
            "Blur Radius", "Blur Multiplier", "1layer Scale", "2layer Scale",
            "3layer Scale", "1layer Color", "2layer Color", "3layer Color"
        )
        assertTrue("Missing Blur Effect controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(ElementValue.Vector2(1.0, 1.0), specs.getValue("1layer Scale").defaultValue)
        assertEquals(ElementValue.Vector2(0.0, 0.0), specs.getValue("2layer Scale").defaultValue)
        assertEquals(ElementValue.Vector2(0.0, 0.0), specs.getValue("3layer Scale").defaultValue)
    }

    @Test
    fun textLayerExposesAdvancedTypographyLayoutAndEffectsControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.TEXT).associateBy { it.name }
        val required = setOf(
            "Font Asset", "Font File", "Font Fallback", "Measure Font Size",
            "Measure Font Size (Measure What)", "Vertical Alignment", "Text Direction",
            "Maximum Lines", "Overflow Mode", "Paragraph Spacing", "Line Height Mode",
            "Fixed Line Height", "Text Background Enabled", "Text Background Color",
            "Text Background Padding X", "Text Background Padding Y",
            "Text Background Corner Radius", "Text Gradient Type", "Text Gradient Angle",
            "Text Gradient Midpoint", "Stroke Join", "Shadow Spread", "Shadow Distance",
            "Shadow Angle"
        )
        assertTrue("Missing advanced text controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(listOf("Top", "Middle", "Bottom"), specs.getValue("Vertical Alignment").options)
        assertEquals(listOf("Visible", "Clip", "Ellipsis", "Scale Down"), specs.getValue("Overflow Mode").options)
        assertFalse("Source Composition Index" in specs)
        assertFalse("Unsupported PreMulAlpha content blend mode must not be exposed", "PreMulAlpha" in specs.getValue("Blend Mode Content").options)
    }

    @Test
    fun textTypographyOptionsAndDefaultsStayRendererCompatible() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.TEXT).associateBy { it.name }
        assertEquals(
            listOf("Regular", "Medium", "SemiBold", "Bold", "Italic", "Bold Italic"),
            specs.getValue("Font Style").options
        )
        assertEquals(listOf("Sans Serif", "Serif", "Monospace", "Cursive"),
            specs.getValue("Font Fallback").options)
        assertEquals(listOf("Auto", "Left To Right", "Right To Left"),
            specs.getValue("Text Direction").options)
        assertEquals(listOf("Linear", "Radial"), specs.getValue("Text Gradient Type").options)
        assertEquals(listOf("Miter", "Round", "Bevel"), specs.getValue("Stroke Join").options)
        assertEquals(0.0, specs.getValue("Text Gradient Midpoint").min!!, 0.0)
        assertEquals(1.0, specs.getValue("Text Gradient Midpoint").max!!, 0.0)
        assertEquals(ElementValue.Text("Bold"), specs.getValue("Font Style").defaultValue)
        assertEquals(ElementValue.Text("Poppins"), specs.getValue("Font Family").defaultValue)
        assertEquals(ElementValue.Text("System Font"), specs.getValue("Font Asset").defaultValue)

        val text = ElementPropertyCatalog.create(StudioElementType.TEXT, "typography")
        assertEquals(ElementValue.Text("Bold"), text.withValue("Font Style", ElementValue.Text("Heavy")).value("Font Style"))
        assertEquals(ElementValue.Text("Auto"), text.withValue("Text Direction", ElementValue.Text("Automatic")).value("Text Direction"))
    }

    @Test
    fun backgroundMirrorShaderAndCompositionExposeExpandedControlCatalogs() {
        val background = ElementPropertyCatalog.forElement(StudioElementType.BACKGROUND).map { it.name }.toSet()
        val mirror = ElementPropertyCatalog.forElement(StudioElementType.MIRROR_EFFECT).map { it.name }.toSet()
        val shader = ElementPropertyCatalog.forElement(StudioElementType.CUSTOM_SHADER).map { it.name }.toSet()
        val composition = ElementPropertyCatalog.forElement(StudioElementType.COMPOSITION).map { it.name }.toSet()
        assertEquals(listOf("Solid Color", "Gradient", "Image"), ElementPropertyCatalog.forElement(StudioElementType.BACKGROUND).first { it.name == "Source Type" }.options)
        val requiredBackgroundControls = setOf(
            "Source Type", "Image File / Source", "Fit Mode (Scale Type)",
            "Background Fit Alignment", "Background Tile", "Background Tile Scale",
            "Background Image Offset X", "Background Image Offset Y",
            "Background Gradient Type", "Background Gradient Start",
            "Background Gradient End", "Background Gradient Angle"
        )
        assertTrue(
            "Missing background controls: ${requiredBackgroundControls - background}",
            background.containsAll(requiredBackgroundControls)
        )
        assertFalse(background.containsAll(setOf(
            "Edge Blur (Image Fill)", "Background Blur Quality", "Background Brightness",
            "Background Contrast", "Background Saturation", "Background Grayscale", "Background Invert"
        )))
        assertTrue(mirror.containsAll(setOf(
            "Mirror Enabled", "Mirror Origin", "Mirror Center", "Mirror Rotation",
            "Mirror Scale", "Mirror Spacing", "Mirror Color Tint", "Mirror Tint Color",
            "Mirror Use Source Alpha", "Mirror Seamless Edge", "Mirror Edge Softness"
        )))
        assertTrue(shader.containsAll(setOf(
            "Shader Precision", "Shader Use Alpha", "Shader Premultiplied Alpha",
            "Shader Use Time", "Shader Use Resolution", "Shader Use Mouse",
            "Shader Audio Sensitivity", "Shader Audio Smoothing", "Shader Audio Band",
            "Shader Render Scale", "Shader Frame Skip", "Shader Debug Preview",
            "Shader Error Policy"
        )))
        assertTrue(composition.containsAll(setOf(
            "Composition Resolution Preset", "Composition Frame Rate Mode",
            "Composition Pixel Format", "Composition Color Space",
            "Composition Transparent Background", "Composition Use Audio",
            "Composition Audio Gain", "Composition Audio Offset",
            "Composition Preserve Aspect Ratio", "Composition Scaling Mode",
            "Composition Layer Limit"
        )))
    }

    @Test
    fun imageBarsParticlesAndEffectsExposeExpandedControlCatalogs() {
        val image = ElementPropertyCatalog.forElement(StudioElementType.IMAGE).map { it.name }.toSet()
        val bars = ElementPropertyCatalog.forElement(StudioElementType.BARS_SEGMENTS).map { it.name }.toSet()
        val particles = ElementPropertyCatalog.forElement(StudioElementType.PARTICLES).map { it.name }.toSet()
        val blur = ElementPropertyCatalog.forElement(StudioElementType.BLUR_EFFECT).map { it.name }.toSet()
        val rgb = ElementPropertyCatalog.forElement(StudioElementType.RGB_SPLIT).map { it.name }.toSet()
        val motion = ElementPropertyCatalog.forElement(StudioElementType.MOTION_BLUR_EFFECT).map { it.name }.toSet()

        assertTrue(image.containsAll(setOf(
            "Image Sampling", "Image Tile Mode X", "Image Tile Mode Y", "Image Brightness",
            "Image Contrast", "Image Hue", "Image Gamma", "Image Color Temperature",
            "Image Flip Horizontal", "Image Flip Vertical", "Image Parallax X",
            "Image Parallax Y", "Image Border Color", "Image Border Width", "Image Custom Shape Path"
        )))
        assertTrue(bars.containsAll(setOf(
            "Frequency Range Start", "Frequency Range End", "Frequency Band Offset",
            "Frequency Mapping Curve", "Frequency Mapping Bias", "Audio Channel",
            "Amplitude Mode", "Amplitude Threshold", "Amplitude Offset", "Bar Height Random",
            "Bar Width Random", "Bar Spacing Random", "Alternate Bar Direction",
            "Use Audio Provider Smoothing", "Peak Hold Enabled", "Peak Reset Threshold",
            "Peak Fall Delay", "Bar Glow Radius", "Bar Glow Color Strength"
        )))
        assertTrue(particles.containsAll(setOf(
            "Particle Emitter Shape", "Emitter Position", "Emitter Rotation", "Emitter Radius",
            "Emitter Inner Radius", "Emitter Arc Start", "Emitter Arc Sweep", "Emitter Loop",
            "Emitter Burst Count", "Emitter Burst Interval", "Particle Rotation Random",
            "Particle Angular Velocity", "Particle Angular Velocity Random", "Particle Mass",
            "Particle Damping", "Particle Screen Blend", "Particle Additive Blend",
            "Particle Trail Width", "Particle Trail Fade", "Particle Shape",
            "Particle Opacity Random", "Particle Color Random"
        )))
        assertTrue(blur.containsAll(setOf(
            "Blur Algorithm", "Blur Passes", "Blur Quality", "Blur Direction", "Blur Zoom Amount",
            "Blur Center", "Blur Alpha Only", "Blur Preserve Edges", "Blur Mask Mode",
            "Blur Mask Strength", "Blur Threshold", "Blur Spread"
        )))
        assertTrue(rgb.containsAll(setOf(
            "RGB Split Mode", "RGB Split Enabled", "Red Channel Opacity",
            "Green Channel Opacity", "Blue Channel Opacity", "RGB Split Angle Variation",
            "RGB Split Audio Amount", "RGB Split Audio Source", "RGB Split Edge Softness",
            "RGB Split Preserve Luminance"
        )))
        assertTrue(motion.containsAll(setOf(
            "Motion Blur Mode", "Motion Blur Shutter Angle", "Motion Blur Exposure",
            "Motion Blur Sample Jitter", "Motion Blur Velocity Scale", "Motion Blur Use Alpha",
            "Motion Blur Normalize Samples", "Motion Blur Edge Mode"
        )))
    }

    @Test
    fun motionBlurExposesMaskAndContentAppearanceControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.MOTION_BLUR_EFFECT).associateBy { it.name }
        val required = setOf(
            "Blend Mode Content", "Color", "Source Composition Index", "Mask Image",
            "Mask Mode", "Mask Strength", "Show Unblurred Content",
            "Show Unblurred Content Under", "Blur Amount / Strength", "Blur Length",
            "Blur Direction / Angle", "Samples / Iterations", "Trails / Echo",
            "Trail Opacity / Fade", "Center / Focus Point", "Radial Motion",
            "Velocity Influence", "Motion Source", "Relative Motion Mode",
            "Blur Amount Multiplier"
        )
        assertTrue("Missing Motion Blur controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(
            listOf("Alpha Mask", "Luminance Mask", "Inverted Mask", "Normal Mask"),
            specs.getValue("Mask Mode").options
        )
        assertEquals(0.0, specs.getValue("Mask Strength").min!!, 0.0)
        assertEquals(1.0, specs.getValue("Mask Strength").max!!, 0.0)
    }

    @Test
    fun imageLayerExposesTransformMaskColorAndAnimationControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.IMAGE).associateBy { it.name }
        val required = setOf(
            "Import Image", "Fit Mode (Scale Type)",
            "Keep Aspect Ratio And Crop To Fit", "Keep Aspect Ratio", "Shape",
            "Crop Left", "Crop Top", "Crop Right", "Crop Bottom", "Corner Radius",
            "Mask / Shape", "Edge Blur", "Color Overlay", "Opacity / Alpha",
            "Measure Animation Speed", "Measure Animation Speed (Measure What)",
            "Measure Animation Speed A (Speed)", "Measure Animation Speed B (Beat Amount)",
            "Measure Color Blend (Measure What)", "Measure Color Blend Min",
            "Measure Color Blend Max", "Blurred Border", "Mask Image", "Mask Mode",
            "Mask Strength", "Color To", "Measure Color Blend", "Saturation",
            "Color Key Enabled", "Auto Detect Color Key", "Color Key",
            "Color Key Tolerance", "Transparency Strength", "Opacity Strength",
            "Blur Enabled", "Blur Divider", "Blur Radius", "Generated Album Art Hint Shift Hue",
            "Generated Album Art Hint No Text", "Generated Album Art Hint Force Gen",
            "Generated Album Art Color", "Blend Mode Content"
        )
        assertTrue("Missing Image controls: " + (required - specs.keys), specs.keys.containsAll(required))
        assertEquals(ElementValue.Vector2(1.0, 1.0), specs.getValue("Scale").defaultValue)
        assertEquals(listOf("Fit (Contain)", "Fill (Cover)", "Stretch"), specs.getValue("Fit Mode (Scale Type)").options)
    }


    @Test
    fun textLayerExposesTypographyAndGradientControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.TEXT).associateBy { it.name }
        val required = setOf(
            "Text", "Font Family", "Font Size", "Font Style", "Underline",
            "Letter Spacing", "Line Spacing", "Alignment", "Text Gradient Start",
            "Text Gradient End", "Stroke Enabled", "Stroke Color", "Stroke Thickness",
            "Shadow Enabled", "Shadow Color", "Shadow Offset X", "Shadow Offset Y",
            "Shadow Blur", "Max Width", "Wrap Text", "Auto-Scale", "Case"
        )
        assertTrue("Missing text typography controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(listOf("Left", "Center", "Right", "Justified"), specs.getValue("Alignment").options)
        assertEquals(listOf("Original", "Uppercase", "Lowercase", "Capitalize"), specs.getValue("Case").options)
    }

    @Test
    fun everyMeasureWhatOptionHasAWorkingDeterministicMotionResponse() {
        val runtime = 1.23f
        val track = 1.37f
        val beat = 0.8f
        val bass = 0.55f
        val treble = 0.75f
        val bpm = 120f
        val seed = 123L
        assertEquals(22, MeasureWhatEngine.options.size)
        MeasureWhatEngine.options.filter { it != "Nothing" }.forEach { mode ->
            val first = MeasureWhatEngine.evaluate(
                mode, runtime, track,
                true, beat, bass,
                treble, bpm, seed,
                runtime
            )
            val second = MeasureWhatEngine.evaluate(
                mode, runtime, track,
                true, beat, bass,
                treble, bpm, seed,
                runtime
            )
            assertEquals("$mode should be deterministic", first, second)
            assertTrue("$mode should produce a response", first != MeasureMotion())
            assertTrue("$mode should return finite offsets", first.offsetX.isFinite() && first.offsetY.isFinite())
            assertTrue("$mode should return finite transform values", first.scale.isFinite() && first.rotation.isFinite())
        }
    }

    @Test
    fun audioProviderConfigurationValuesAreValidatedAndRetainedInDocument() {
        val provider = ElementPropertyCatalog.create(StudioElementType.AUDIO_PROVIDER, "audio-provider")
            .withValue("Analysis Mode", ElementValue.Text("Beat"))
            .withValue("Channel Mode", ElementValue.Text("Stereo Average"))
            .withValue("Window Function", ElementValue.Text("Blackman"))
            .withValue("Fft Size", ElementValue.Text("4096"))
            .withValue("Beat Sensitivity", ElementValue.Number(2.5))
        assertEquals(ElementValue.Text("Beat"), provider.value("Analysis Mode"))
        assertEquals(ElementValue.Text("Stereo Average"), provider.value("Channel Mode"))
        assertEquals(ElementValue.Text("Blackman"), provider.value("Window Function"))
        assertEquals(ElementValue.Text("4096"), provider.value("Fft Size"))
        assertEquals(ElementValue.Number(2.5), provider.value("Beat Sensitivity"))
    }
    @Test
    fun measurementMappingHandlesRangesCurvesAndInvalidInput() {
        val mapping = MeasurementMapping(
            inputMin = 0f, inputMax = 1f, outputMin = 10f, outputMax = 20f,
            multiplier = 2f, offset = 1f, responseCurve = "SQUARE"
        )
        assertEquals(11f, mapping.normalized(0f), 0.0001f)
        assertEquals(31f, mapping.normalized(1f), 0.0001f)
        assertEquals(0f, MeasurementMapping(inputMin = 2f, inputMax = 2f).normalized(0.5f), 0.0001f)
        assertTrue(MeasurementMapping(inputMin = 1f, inputMax = 0f).validated().inputMax > 1f)
    }

    @Test
    fun normalizationPreservesUnknownPropertiesForForwardCompatibility() {
        val element = ElementPropertyCatalog.create(StudioElementType.IMAGE, "image-1")
            .copy(properties = ElementPropertyCatalog.create(StudioElementType.IMAGE, "image-1").properties +
                ("Future Property" to ElementValue.Text("keep-me")))
        val normalized = element.normalized()
        assertEquals(ElementValue.Text("keep-me"), normalized.value("Future Property"))
    }

    @Test
    fun elementFactoryPopulatesSpecificationDefaults() {
        val image = ElementPropertyCatalog.create(StudioElementType.IMAGE, "image-1", "Album Art")
        assertEquals("Album Art", image.tag)
        assertEquals(ElementValue.Bool(true), image.value("Visible"))
        assertEquals(ElementValue.Text("Rectangle"), image.value("Mask / Shape"))
        assertNotNull(image.value("Import Image"))
        assertFalse(image.locked)
    }
    @Test
    fun legacyLayersConvertToStableSpecificationElements() {
        val oldBars = VisualLayer(
            id = 42L,
            kind = LayerKind.BARSH,
            name = "Main Spectrum",
            enabled = false,
            barCount = 96,
            thickness = 3f,
            spacing = 2f,
            sensitivity = 150f,
            smoothing = 35f
        )
        val converted = LegacyLayerAdapter.toElement(oldBars)
        assertEquals("legacy-layer-42", converted.id)
        assertEquals(StudioElementType.BARS_SEGMENTS, converted.type)
        assertEquals("Main Spectrum", converted.tag)
        assertEquals(ElementValue.Bool(false), converted.value("Visible"))
        assertEquals(ElementValue.Integer(96L), converted.value("Bar Count"))
        assertEquals(ElementValue.Bool(true), converted.value("Mirror"))
        assertEquals(ElementValue.Text("Logarithmic"), converted.value("Frequency Scale"))
        assertEquals(converted, LegacyLayerAdapter.toElement(oldBars))
    }

    @Test
    fun legacyTextImageAndBackgroundKeepTheirContentWhenAdapted() {
        val text = LegacyLayerAdapter.toElement(
            VisualLayer(7L, LayerKind.TEXT, textContent = "VOOC Studio")
        )
        val image = LegacyLayerAdapter.toElement(
            VisualLayer(8L, LayerKind.IMAGE, imageUri = "content://media/image/8")
        )
        val background = LegacyLayerAdapter.toElement(
            VisualLayer(9L, LayerKind.BACKGROUND, imageUri = "content://media/image/9")
        )
        assertEquals(StudioElementType.TEXT, text.type)
        assertEquals(ElementValue.Text("VOOC Studio"), text.value("Text"))
        assertEquals(ElementValue.Text("content://media/image/8"), image.value("Import Image"))
        assertEquals(ElementValue.Text("content://media/image/9"), background.value("Image File / Source"))
    }

    @Test
    fun legacyLayerConversionDoesNotMutateOriginalProjectLayers() {
        val layers = listOf(VisualLayer(1L, LayerKind.BARS), VisualLayer(2L, LayerKind.PARTICLES))
        val before = layers.toList()
        val converted = LegacyLayerAdapter.toElements(layers)
        assertEquals(before, layers)
        assertEquals(2, converted.size)
        assertEquals(listOf("legacy-layer-1", "legacy-layer-2"), converted.map { it.id })
    }

    @Test
    fun spectrumStyleMenuCreatesCanonicalElementsInsteadOfLegacyLayers() {
        val styles = LayerAddMenuCatalog.spectrumStyles
        assertTrue(styles.isNotEmpty())
        styles.forEachIndexed { index, kind ->
            val element = LegacyLayerAdapter.createSpectrumElement(kind, "spectrum-$index")
            assertEquals(kind.title, element.tag)
            assertEquals(StudioElementType.BARS_SEGMENTS, element.type)
            assertNotNull(element.value("Bar Design"))
            assertTrue(element.value("Bar Count") is ElementValue.Integer)
        }
    }

    @Test
    fun spectrumStyleMappingUsesSupportedBarDesignOptions() {
        val expected = mapOf(
            LayerKind.BARSH to "Rounded Rect",
            LayerKind.CIRCLE to "Circle / Dot",
            LayerKind.WAVE to "Wave",
            LayerKind.SCOPE to "Line",
            LayerKind.DOTS to "Circle / Dot",
            LayerKind.BLOCKS to "Rectangle"
        )
        expected.forEach { (kind, design) ->
            val element = LegacyLayerAdapter.createSpectrumElement(kind, "mapping-${kind.name}")
            assertEquals(ElementValue.Text(design), element.value("Bar Design"))
        }
    }

    @Test
    fun imageLayerCatalogExposesSupportedControlsWithoutInertCompositionIndex() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.IMAGE).associateBy { it.name }
        val required = setOf(
            "Import Image", "Fit Mode (Scale Type)", "Keep Aspect Ratio And Crop To Fit",
            "Keep Aspect Ratio", "Shape", "Crop Left", "Crop Top", "Crop Right", "Crop Bottom",
            "Corner Radius", "Mask / Shape", "Edge Blur", "Color Overlay", "Opacity / Alpha",
            "Mask Image", "Mask Mode", "Mask Strength", "Color Key Enabled", "Auto Detect Color Key",
            "Color Key", "Color Key Tolerance", "Transparency Strength", "Opacity Strength",
            "Blur Enabled", "Blur Divider", "Blur Radius", "Image Sampling", "Image Tile Mode X",
            "Image Tile Mode Y", "Image Tile Scale X", "Image Tile Scale Y", "Image Brightness",
            "Image Contrast", "Image Hue", "Image Gamma", "Image Color Temperature",
            "Image Flip Horizontal", "Image Flip Vertical", "Image Parallax X", "Image Parallax Y",
            "Image Border Color", "Image Border Width", "Image Custom Shape Path",
            "Generated Album Art Hint Shift Hue", "Generated Album Art Hint No Text",
            "Generated Album Art Hint Force Gen", "Generated Album Art Color"
        )
        assertTrue("Missing Image controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertFalse("Image must not expose an unsupported composition-surface selector",
            specs.containsKey("Source Composition Index"))
        assertEquals(listOf("Alpha Mask", "Luminance Mask", "Inverted Mask", "Normal Mask"),
            specs.getValue("Mask Mode").options)
        assertEquals(listOf("Nearest", "Linear"), specs.getValue("Image Sampling").options)
        assertEquals(ElementValue.Number(1.0), specs.getValue("Mask Strength").defaultValue)
    }

    @Test
    fun backgroundAndShapeCatalogsHideUnsupportedCompositionSurfaceControls() {
        val background = ElementPropertyCatalog.forElement(StudioElementType.BACKGROUND).map { it.name }.toSet()
        val shape = ElementPropertyCatalog.forElement(StudioElementType.SHAPE).map { it.name }.toSet()
        assertFalse(background.contains("Source Composition Index"))
        assertEquals(listOf("Solid Color", "Gradient", "Image"), ElementPropertyCatalog.forElement(StudioElementType.BACKGROUND).first { it.name == "Source Type" }.options)
        assertTrue(background.containsAll(setOf("Mask Image", "Show Unblurred Content", "Show Unblurred Content Under")))
        assertFalse(shape.contains("Source Composition Index"))
        assertFalse(shape.contains("Mask Image"))
        assertFalse(shape.contains("Show Unblurred Content"))
        assertFalse(shape.contains("Show Unblurred Content Under"))
    }

    @Test
    fun particleCatalogHidesControlsNotImplementedByParticleRenderer() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.PARTICLES).associateBy { it.name }
        assertTrue(specs.containsKey("Texture / Image"))
        assertTrue(specs.containsKey("Emitter Burst Count"))
        assertTrue(specs.containsKey("Emitter Burst Interval"))
        assertTrue(specs.getValue("Particle Emitter Shape").options.contains("HorizontalLine"))
        assertFalse(specs.containsKey("Source Composition Index"))
        assertFalse(specs.containsKey("Mask Image"))
        assertFalse(specs.containsKey("Show Unblurred Content"))
        assertFalse(specs.containsKey("Show Unblurred Content Under"))
    }

    @Test
    fun imageSamplingDoesNotOfferUnsupportedCubicMode() {
        val image = ElementPropertyCatalog.create(StudioElementType.IMAGE, "sampling")
        val spec = ElementPropertyCatalog.forElement(StudioElementType.IMAGE).first { it.name == "Image Sampling" }
        assertEquals(listOf("Nearest", "Linear"), spec.options)
        val legacy = image.copy(properties = image.properties + ("Image Sampling" to ElementValue.Text("Cubic"))).normalized()
        assertEquals(ElementValue.Text("Linear"), legacy.value("Image Sampling"))
        assertEquals(ElementValue.Text("Linear"), image.value("Image Sampling"))
    }

    @Test
    fun imageShapePathValidationRejectsUnsupportedCommandsAndMalformedCharacters() {
        assertTrue(isSupportedNormalizedImageShapePath("M 0 0 L 1 0 L 1 1 Z"))
        assertFalse(isSupportedNormalizedImageShapePath("M 0 0 A 1 1 0 0 1 1 1 Z"))
        assertFalse(isSupportedNormalizedImageShapePath("M 0 0 L 1 1 @ Z"))
    }

}
