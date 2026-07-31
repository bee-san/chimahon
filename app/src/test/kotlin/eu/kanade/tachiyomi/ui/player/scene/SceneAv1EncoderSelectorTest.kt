package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SceneAv1EncoderSelectorTest {
    @Test
    fun `landscape input checks an aspect preserving codec aligned output`() {
        val checkedSizes = mutableListOf<SceneVideoDimensions>()
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 320, height = 180),
            candidates = sequenceOf(
                candidate { size, _ ->
                    checkedSizes += size
                    size == SceneVideoDimensions(width = 320, height = 180)
                },
            ),
        )

        assertEquals(
            Av1EncoderSelection(
                name = ENCODER_NAME,
                contentSize = SceneVideoDimensions(width = 320, height = 180),
                outputSize = SceneVideoDimensions(width = 320, height = 180),
            ),
            selection,
        )
        assertEquals(listOf(SceneVideoDimensions(width = 320, height = 180)), checkedSizes)
    }

    @Test
    fun `square input lowers its cap until it fits the codec block budget`() {
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 640, height = 640),
            candidates = sequenceOf(
                candidate { size, _ ->
                    size.width.ceilDiv(16) * size.height.ceilDiv(16) <= 1_350
                },
            ),
        )

        assertEquals(
            Av1EncoderSelection(
                name = ENCODER_NAME,
                contentSize = SceneVideoDimensions(width = 576, height = 576),
                outputSize = SceneVideoDimensions(width = 576, height = 576),
            ),
            selection,
        )
    }

    @Test
    fun `common sixteen by nine sources select the exact same output geometry`() {
        listOf(
            SceneVideoDimensions(width = 640, height = 360),
            SceneVideoDimensions(width = 1248, height = 702),
        ).forEach { source ->
            assertEquals(
                SceneVideoDimensions(width = 640, height = 360),
                selectAv1Encoder(
                    source = source,
                    candidates = sequenceOf(candidate()),
                )?.outputSize,
            )
        }
    }

    @Test
    fun `portrait input lowers both dimensions until the codec block budget fits`() {
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 536, height = 640),
            candidates = sequenceOf(
                candidate { size, _ ->
                    size.width.ceilDiv(16) * size.height.ceilDiv(16) <= 1_350
                },
            ),
        )

        assertEquals(SceneVideoDimensions(width = 528, height = 630), selection?.outputSize)
    }

    @Test
    fun `codec alignment expands the canvas instead of squashing the source`() {
        val checkedSizes = mutableListOf<SceneVideoDimensions>()
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 320, height = 180),
            candidates = sequenceOf(
                candidate(
                    widthAlignment = 16,
                    heightAlignment = 16,
                    supportsSizeAndRate = { size, _ ->
                        checkedSizes += size
                        true
                    },
                ),
            ),
        )

        assertEquals(SceneVideoDimensions(width = 320, height = 180), selection?.contentSize)
        assertEquals(SceneVideoDimensions(width = 320, height = 192), selection?.outputSize)
        assertEquals(listOf(SceneVideoDimensions(width = 320, height = 192)), checkedSizes)
    }

    @Test
    fun `codec minimum dimensions expand only the canvas`() {
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 32, height = 18),
            candidates = sequenceOf(
                candidate(
                    minimumWidth = 64,
                    minimumHeight = 64,
                    supportsSizeAndRate = { size, _ ->
                        size.width >= 64 && size.height >= 64
                    },
                ),
            ),
        )

        assertEquals(SceneVideoDimensions(width = 32, height = 18), selection?.contentSize)
        assertEquals(SceneVideoDimensions(width = 64, height = 64), selection?.outputSize)
    }

    @Test
    fun `conditional codec dimensions add padding instead of reducing content`() {
        val checkedSizes = mutableListOf<SceneVideoDimensions>()
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 64, height = 64),
            candidates = sequenceOf(
                candidate(
                    supportedWidthsForHeight = { height ->
                        if (height == 64) 128..640 else null
                    },
                    supportsSizeAndRate = { size, _ ->
                        checkedSizes += size
                        size == SceneVideoDimensions(width = 128, height = 64)
                    },
                ),
            ),
        )

        assertEquals(SceneVideoDimensions(width = 64, height = 64), selection?.contentSize)
        assertEquals(SceneVideoDimensions(width = 128, height = 64), selection?.outputSize)
        assertEquals(listOf(SceneVideoDimensions(width = 128, height = 64)), checkedSizes)
    }

    @Test
    fun `conditional codec range checks wider canvases until the frame rate is supported`() {
        val checkedSizes = mutableListOf<SceneVideoDimensions>()
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 64, height = 64),
            candidates = sequenceOf(
                candidate(
                    widthAlignment = 64,
                    heightAlignment = 64,
                    supportedWidthsForHeight = { height ->
                        if (height == 64) 64..128 else null
                    },
                    supportsSizeAndRate = { size, _ ->
                        checkedSizes += size
                        size == SceneVideoDimensions(width = 128, height = 64)
                    },
                ),
            ),
        )

        assertEquals(SceneVideoDimensions(width = 64, height = 64), selection?.contentSize)
        assertEquals(SceneVideoDimensions(width = 128, height = 64), selection?.outputSize)
        assertEquals(
            listOf(
                SceneVideoDimensions(width = 64, height = 64),
                SceneVideoDimensions(width = 128, height = 64),
            ),
            checkedSizes,
        )
    }

    @Test
    fun `invalid conditional height query does not reject a later padded canvas`() {
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 64, height = 64),
            candidates = sequenceOf(
                candidate(
                    widthAlignment = 64,
                    heightAlignment = 64,
                    supportedWidthsForHeight = { height ->
                        if (height == 64) {
                            throw IllegalArgumentException("unsupported height")
                        }
                        if (height == 128) 64..64 else null
                    },
                    supportsSizeAndRate = { size, _ ->
                        size == SceneVideoDimensions(width = 64, height = 128)
                    },
                ),
            ),
        )

        assertEquals(SceneVideoDimensions(width = 64, height = 64), selection?.contentSize)
        assertEquals(SceneVideoDimensions(width = 64, height = 128), selection?.outputSize)
    }

    @Test
    fun `highest resolution wins across compatible encoders`() {
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 640, height = 360),
            candidates = sequenceOf(
                candidate(
                    name = "limited.encoder",
                    supportsSizeAndRate = { size, _ -> size.width <= 320 },
                ),
                candidate(name = "full.encoder"),
            ),
        )

        assertEquals("full.encoder", selection?.name)
        assertEquals(SceneVideoDimensions(width = 640, height = 360), selection?.contentSize)
    }

    @Test
    fun `selection never exceeds the production output bound`() {
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 1_600, height = 900),
            candidates = sequenceOf(candidate()),
        )

        assertEquals(SceneVideoDimensions(width = 640, height = 360), selection?.outputSize)
    }

    @Test
    fun `narrow inputs reduce the long edge instead of visibly changing aspect`() {
        val selection = selectAv1Encoder(
            source = SceneVideoDimensions(width = 3, height = 640),
            candidates = sequenceOf(candidate()),
        )

        assertEquals(SceneVideoDimensions(width = 2, height = 426), selection?.contentSize)
        assertNull(
            selectAv1Encoder(
                source = SceneVideoDimensions(width = 1, height = 640),
                candidates = sequenceOf(candidate()),
            ),
        )
        assertNull(
            selectAv1Encoder(
                source = SceneVideoDimensions(width = Int.MAX_VALUE, height = 1),
                candidates = sequenceOf(candidate()),
            ),
        )
    }

    @Test
    fun `required MediaCodec format and quality capabilities remain enforced`() {
        val unsupported = sequenceOf(
            candidate(supportsPlanarYuv420 = false),
            candidate(supportsConstantQuality = false),
            candidate(supportsTargetQuality = false),
        )

        assertNull(
            selectAv1Encoder(
                source = SceneVideoDimensions(width = 1920, height = 1080),
                candidates = unsupported,
            ),
        )
    }

    private fun candidate(
        name: String = ENCODER_NAME,
        supportsPlanarYuv420: Boolean = true,
        supportsConstantQuality: Boolean = true,
        supportsTargetQuality: Boolean = true,
        widthAlignment: Int = 2,
        heightAlignment: Int = 2,
        minimumWidth: Int = 2,
        minimumHeight: Int = 2,
        maximumWidth: Int = Int.MAX_VALUE,
        maximumHeight: Int = Int.MAX_VALUE,
        supportedWidthsForHeight: (Int) -> IntRange? = {
            minimumWidth..maximumWidth
        },
        supportsSizeAndRate: (SceneVideoDimensions, Double) -> Boolean = { _, _ -> true },
    ) = Av1EncoderCandidate(
        name = name,
        supportsPlanarYuv420 = supportsPlanarYuv420,
        supportsConstantQuality = supportsConstantQuality,
        supportsTargetQuality = supportsTargetQuality,
        widthAlignment = widthAlignment,
        heightAlignment = heightAlignment,
        minimumWidth = minimumWidth,
        minimumHeight = minimumHeight,
        maximumWidth = maximumWidth,
        maximumHeight = maximumHeight,
        supportedWidthsForHeight = supportedWidthsForHeight,
        supportsSizeAndRate = supportsSizeAndRate,
    )

    private fun Int.ceilDiv(divisor: Int): Int = (this + divisor - 1) / divisor

    private companion object {
        const val ENCODER_NAME = "c2.android.av1.encoder"
    }
}
