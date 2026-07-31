package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurfacePlaybackLoadGateTest {

    @Test
    fun `cold start defers playback until the surface exists`() {
        val loaded = mutableListOf<String>()
        val gate = SurfacePlaybackLoadGate {
            loaded += it
            true
        }

        gate.load("content://episode")

        assertTrue(loaded.isEmpty())

        gate.onSurfaceCreated()

        assertEquals(listOf("content://episode"), loaded)
    }

    @Test
    fun `latest pending load replaces an older request`() {
        val loaded = mutableListOf<String>()
        val gate = SurfacePlaybackLoadGate {
            loaded += it
            true
        }

        gate.load("content://old")
        gate.load("content://new")
        gate.onSurfaceCreated()

        assertEquals(listOf("content://new"), loaded)
    }

    @Test
    fun `surface recreation defers new playback until reattached`() {
        val loaded = mutableListOf<String>()
        val gate = SurfacePlaybackLoadGate {
            loaded += it
            true
        }

        gate.onSurfaceCreated()
        gate.load("content://first")
        gate.onSurfaceDestroyed()
        gate.load("content://second")

        assertEquals(listOf("content://first"), loaded)

        gate.onSurfaceCreated()

        assertEquals(listOf("content://first", "content://second"), loaded)
    }

    @Test
    fun `surface recreation without a pending request does not reload`() {
        val loaded = mutableListOf<String>()
        val gate = SurfacePlaybackLoadGate {
            loaded += it
            true
        }

        gate.onSurfaceCreated()
        gate.load("content://episode")
        gate.onSurfaceDestroyed()
        gate.onSurfaceCreated()

        assertEquals(listOf("content://episode"), loaded)
    }

    @Test
    fun `closing the gate drops pending and future loads`() {
        val loaded = mutableListOf<String>()
        val gate = SurfacePlaybackLoadGate {
            loaded += it
            true
        }

        gate.load("content://pending")
        gate.close()
        gate.onSurfaceCreated()
        gate.load("content://late")

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `rejected surface load remains pending until playback resumes`() {
        val loaded = mutableListOf<String>()
        var canLoad = false
        val gate = SurfacePlaybackLoadGate {
            if (canLoad) {
                loaded += it
            }
            canLoad
        }

        gate.onSurfaceCreated()
        gate.load("content://episode")

        assertTrue(loaded.isEmpty())

        canLoad = true
        gate.retryPending()

        assertEquals(listOf("content://episode"), loaded)
    }
}
