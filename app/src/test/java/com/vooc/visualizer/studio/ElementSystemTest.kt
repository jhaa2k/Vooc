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
            setOf("Audio Provider", "Image", "Background", "Rgb Split", "Blur Effect", "Bars/Segments", "Particles", "Text", "Motion Blur Effect", "Shape"),
            StudioElementType.entries.map { it.displayName }.toSet()
        )
        StudioElementType.entries.forEach { type ->
            assertTrue("$type should expose a property schema", ElementPropertyCatalog.forElement(type).isNotEmpty())
        }
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
            "Scale", "Measure Scale (Measure What)", "Rotation", "Measure Rot (Measure What)"
        )
        val visualTypes = StudioElementType.entries.filter { it != StudioElementType.AUDIO_PROVIDER }
        visualTypes.forEach { type ->
            assertTrue("$type is missing shared properties", ElementPropertyCatalog.forElement(type).map { it.name }.containsAll(expected))
        }
    }

    @Test
    fun propertyValuesAreTypeCheckedAndClamped() {
        val element = ElementPropertyCatalog.create(StudioElementType.BARS_SEGMENTS, "bars-1")
        val bounded = element.withValue("Bar Count", ElementValue.Number(5000.0))
        assertEquals(ElementValue.Integer(2048L), bounded.value("Bar Count"))
        val invalid = element.withValue("Visible", ElementValue.Text("yes"))
        assertEquals(ElementValue.Bool(true), invalid.value("Visible"))
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
        assertEquals(ElementValue.Text("Rectangle"), image.value("Shape"))
        assertNotNull(image.value("Custom Image"))
        assertFalse(image.locked)
    }
}
