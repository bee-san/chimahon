package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SceneMpvSnapshotReaderTest {
    @Test
    fun `reader snapshots exact fractional clock subtitle and selected audio properties`() {
        val properties = FakeProperties(
            doubles = mapOf(
                "time-pos" to 12.375,
                "duration" to 120.75,
                "sub-start/full" to 10.125,
                "sub-end/full" to 13.875,
                "sub-speed" to 0.95,
                "sub-delay" to -0.225,
            ),
            strings = mapOf(
                "path" to "https://cdn.test/video.m3u8",
                "aid" to "42",
                "track-list/0/type" to "video",
                "track-list/1/type" to "audio",
                "track-list/1/external-filename" to "https://cdn.test/audio-ja.m4a",
            ),
            booleans = mapOf("seekable" to true),
            ints = mapOf(
                "track-list/count" to 2,
                "track-list/0/id" to 1,
                "track-list/1/id" to 42,
                "track-list/1/ff-index" to 7,
            ),
        )

        val snapshot = SceneMpvSnapshotReader(properties).read()

        assertEquals(
            SceneMpvSnapshot(
                anchorMediaSeconds = 12.375,
                mediaDurationSeconds = 120.75,
                subtitleStartSeconds = 10.125,
                subtitleEndSeconds = 13.875,
                subtitleSpeed = 0.95,
                subtitleDelaySeconds = -0.225,
                playableValue = "https://cdn.test/video.m3u8",
                selectedAudioId = 42,
                selectedAudioFfmpegIndex = 7,
                selectedExternalAudioValue = "https://cdn.test/audio-ja.m4a",
                selectedAudioIsExternal = true,
                seekable = true,
            ),
            snapshot,
        )
    }

    @Test
    fun `reader does not silently substitute missing subtitle transform properties`() {
        val base = mapOf(
            "time-pos" to 4.5,
            "sub-speed" to 1.0,
            "sub-delay" to 0.0,
        )

        assertNull(
            SceneMpvSnapshotReader(
                FakeProperties(doubles = base - "sub-speed"),
            ).read(),
        )
        assertNull(
            SceneMpvSnapshotReader(
                FakeProperties(doubles = base - "sub-delay"),
            ).read(),
        )
    }

    @Test
    fun `reader never guesses the first external audio track when aid is unavailable`() {
        val properties = FakeProperties(
            doubles = mapOf(
                "time-pos" to 4.5,
                "sub-speed" to 1.0,
                "sub-delay" to 0.0,
            ),
            strings = mapOf(
                "track-list/0/type" to "audio",
                "track-list/0/external-filename" to "https://cdn.test/wrong-language.m4a",
            ),
            ints = mapOf(
                "track-list/count" to 1,
                "track-list/0/id" to 5,
            ),
        )

        assertNull(SceneMpvSnapshotReader(properties).read()?.selectedExternalAudioValue)
        assertNull(SceneMpvSnapshotReader(properties).read()?.selectedAudioId)
        assertNull(SceneMpvSnapshotReader(properties).read()?.selectedAudioFfmpegIndex)
        assertEquals(false, SceneMpvSnapshotReader(properties).read()?.selectedAudioIsExternal)
    }

    private class FakeProperties(
        private val doubles: Map<String, Double> = emptyMap(),
        private val strings: Map<String, String> = emptyMap(),
        private val booleans: Map<String, Boolean> = emptyMap(),
        private val ints: Map<String, Int> = emptyMap(),
    ) : SceneMpvPropertyReader {
        override fun double(name: String): Double? = doubles[name]

        override fun string(name: String): String? = strings[name]

        override fun boolean(name: String): Boolean? = booleans[name]

        override fun int(name: String): Int? = ints[name]
    }
}
