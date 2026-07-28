package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.data.ocr.OcrEngineType

enum class ReaderOcrSource(
    val usesMokuro: Boolean,
    val usesPersistentCache: Boolean,
    val recognitionEngine: OcrEngineType?,
) {
    AUTOMATIC(
        usesMokuro = true,
        usesPersistentCache = true,
        recognitionEngine = null,
    ),
    MOKURO(
        usesMokuro = true,
        usesPersistentCache = false,
        recognitionEngine = null,
    ),
    GOOGLE_LENS(
        usesMokuro = false,
        usesPersistentCache = false,
        recognitionEngine = OcrEngineType.CLOUD,
    ),
    LOCAL(
        usesMokuro = false,
        usesPersistentCache = false,
        recognitionEngine = OcrEngineType.LOCAL,
    ),
    ;

    companion object {
        fun availableSources(localOcrAvailable: Boolean): List<ReaderOcrSource> {
            return entries.filter { localOcrAvailable || it != LOCAL }
        }
    }
}
