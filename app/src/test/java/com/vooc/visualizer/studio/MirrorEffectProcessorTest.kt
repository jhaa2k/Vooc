package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorEffectProcessorTest {
    @Test fun neutralPositionPreservesSelectedOrigin() {
        assertEquals(0f, resolveMirrorAxisPosition(0f, 0.5f, 100f), 0.0001f)
        assertEquals(100f, resolveMirrorAxisPosition(100f, 0.5f, 100f), 0.0001f)
        assertEquals(50f, resolveMirrorAxisPosition(50f, 0.5f, 100f), 0.0001f)
    }

    @Test fun positionOffsetsFromOriginInsteadOfReplacingIt() {
        assertEquals(25f, resolveMirrorAxisPosition(0f, 0.75f, 100f), 0.0001f)
        assertEquals(75f, resolveMirrorAxisPosition(100f, 0.25f, 100f), 0.0001f)
    }

    @Test fun positionIsClampedAndNonFiniteValuesAreSafe() {
        assertEquals(100f, resolveMirrorAxisPosition(50f, 2f, 100f), 0.0001f)
        assertEquals(50f, resolveMirrorAxisPosition(50f, Float.NaN, 100f), 0.0001f)
        assertEquals(50f, resolveMirrorAxisPosition(Float.NaN, 0.5f, 100f), 0.0001f)
        assertEquals(1f, resolveMirrorAxisPosition(1f, 0.5f, Float.NaN), 0.0001f)
    }
}
