package eu.kanade.tachiyomi.data.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrEngineTypeTest {

    @Test
    fun `local preference selects local OCR`() {
        assertEquals(OcrEngineType.LOCAL, OcrEngineType.fromPreference("local"))
    }

    @Test
    fun `other preferences select Google Lens`() {
        assertEquals(OcrEngineType.CLOUD, OcrEngineType.fromPreference("cloud"))
        assertEquals(OcrEngineType.CLOUD, OcrEngineType.fromPreference("unexpected"))
    }
}
