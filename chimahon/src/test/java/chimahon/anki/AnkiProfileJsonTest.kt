package chimahon.anki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnkiProfileJsonTest {

    @Test
    fun `default profile keeps full screenshot mode`() {
        val profile = AnkiProfile.createDefault()

        assertEquals(AnkiScreenshotMode.FULL.storageValue, profile.ankiCropMode)
    }

    @Test
    fun `animated scene screenshot mode survives json round trip`() {
        val profile = AnkiProfile.createDefault()
            .copy(
                ankiCropMode = AnkiScreenshotMode.ANIMATED_SCENE.storageValue,
                dictionaryOrder = listOf("Jitendex", "JMdict"),
                enabledDictionaries = setOf("Jitendex"),
            )

        val restored = AnkiProfile.fromJson(profile.toJson())

        assertEquals(profile, restored)
        assertEquals(AnkiScreenshotMode.ANIMATED_SCENE.storageValue, restored.ankiCropMode)
    }
}
