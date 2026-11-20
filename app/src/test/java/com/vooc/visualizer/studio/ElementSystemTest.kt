package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementSystemTest {
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
        assertFalse(LayerAddMenuCatalog.elementTypes.contains(StudioElementType.AUDIO_PROVIDER))
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
    fun audioProviderExposesAnalysisAndBeatConfigurationControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.AUDIO_PROVIDER).associateBy { it.name }
        val required = setOf(
            "Tag", "Provider Enabled", "Sample Provider", "Sample Out Count", "Mirror Samples",
            "Repeat Samples", "Star And End Gap", "Smooth", "Pre Smooth", "Filter Radius",
            "Filter Strength", "AWeight", "Output Multiplier", "Lower Hz", "Higher Hz",
            "Hz Linear Factor", "Freq Shift", "Beat Smooth", "Beat Range Bar First",
            "Beat Range Bar Last", "Beat Range Value Lower", "Beat Range Value Higher",
            "Analysis Mode", "Channel Mode", "Window Function", "Beat Detection Enabled",
            "Beat Sensitivity", "Beat Threshold", "Beat Release", "Fft Size"
        )
        assertTrue("Missing audio provider controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(listOf("256", "512", "1024", "2048", "4096", "8192"), specs.getValue("Fft Size").options)
        assertEquals(listOf("Rectangular", "Hann", "Hamming", "Blackman"), specs.getValue("Window Function").options)
    }

    @Test
    fun audioProviderExposesInputSpectrumFrequencyAndAdvancedBeatControls() {
        val specs = ElementPropertyCatalog.forElement(StudioElementType.AUDIO_PROVIDER).associateBy { it.name }
        val required = setOf(
            "Audio Input Source", "Input Channel", "Input Gain", "Input Offset",
            "Input Auto Normalize", "Input Noise Gate", "Input Noise Gate Smoothing",
            "Input DC Removal", "Stereo Width", "Stereo Balance",
            "Spectrum Output Mode", "Spectrum Scale", "Spectrum Floor", "Spectrum Ceiling",
            "Spectrum Compression", "Spectrum Noise Reduction", "Spectrum Averaging Frames",
            "Spectrum Peak Normalize", "Spectrum Logarithmic Smoothing",
            "Frequency Mapping Bias", "Frequency Mapping Curve", "Frequency Mapping Bands",
            "Frequency Mapping Overlap", "Beat Detection Mode", "Beat Frequency Range",
            "Beat Minimum Interval", "Beat Adaptive Sensitivity", "Beat Onset Strength",
            "Beat Decay", "Beat Quantize", "Beat Quantize Strength", "Beat Ignore Low Energy",
            "Beat Low Energy Threshold", "Audio Output Signal", "Audio Output Smoothing",
            "Audio Output Clamp", "Audio Output Hold Peak", "Audio Output Peak Hold Time",
            "Analysis Update Rate", "Analysis Buffer Size", "Analysis Multithreaded"
        )
        assertTrue("Missing advanced audio provider controls: ${required - specs.keys}", specs.keys.containsAll(required))
        assertEquals(listOf("Playback Capture", "Imported Track", "Microphone", "Test Signal"), specs.getValue("Audio Input Source").options)
        assertEquals(listOf("Linear", "Logarithmic", "Mel", "Bark"), specs.getValue("Spectrum Scale").options)
        assertEquals(listOf("Adaptive", "Fixed Threshold", "Spectral Flux", "Energy"), specs.getValue("Beat Detection Mode").options)
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
            "Target Image", "Blend Mode Content", "Color", "Source Composition Index",
            "Mask Image", "Show Unblurred Content", "Show Unblurred Content Under",
            "Split Amount", "Split Amount / Distance", "Split Multiplier",
            "Angle / Direction", "Speed / Frequency", "Center / Focus Point",
            "Falloff / Radial Split", "Red Offset X", "Red Offset Y",
            "Green Offset X", "Green Offset Y", "Blue Offset X", "Blue Offset Y",
            "Split Color 0", "Split Color 1", "Split Color 2"
        )
        assertTrue("Missing RGB Split controls: ${required - specs.keys}", specs.keys.containsAll(required))
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
            "Custom Image", "Image Source / File", "Fit Mode (Scale Type)",
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
            "Generated Album Art Color", "Blend Mode Content", "Source Composition Index"
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
        assertNotNull(image.value("Image Source / File"))
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
        assertEquals(ElementValue.Text("content://media/image/8"), image.value("Image Source / File"))
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

}
