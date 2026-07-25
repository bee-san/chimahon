package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.scene.mergeSceneHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SceneHeaderMergeTest {
    @Test
    fun `video headers override source headers case insensitively while preserving the rest`() {
        val merged = mergeSceneHeaders(
            sourceHeaders = listOf(
                "User-Agent" to "source-agent",
                "Cookie" to "first=1",
                "Cookie" to "second=2",
                "Referer" to "https://source.test/",
            ),
            videoHeaders = listOf(
                "user-agent" to "video-agent",
                "Referer" to "https://video.test/",
            ),
        )

        assertEquals(
            listOf(
                "Cookie" to "first=1",
                "Cookie" to "second=2",
                "user-agent" to "video-agent",
                "Referer" to "https://video.test/",
            ),
            merged,
        )
    }
}
