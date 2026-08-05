package chimahon.anki

enum class AnkiScreenshotMode(val storageValue: String) {
    FULL("full"),
    CROP("crop"),
    NONE("no_screenshot"),
    ANIMATED_SCENE("animated_scene"),
    ;

    companion object {
        fun fromStorageValue(value: String): AnkiScreenshotMode =
            entries.firstOrNull { it.storageValue == value } ?: FULL
    }
}