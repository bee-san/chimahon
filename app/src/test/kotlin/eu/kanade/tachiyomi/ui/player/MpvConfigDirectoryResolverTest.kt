package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MpvConfigDirectoryResolverTest {

    @Test
    fun `external config directory is used when it is available`() {
        assertEquals(
            "/storage/emulated/0/chimaFork/mpv",
            resolveMpvConfigDirectory(
                internalConfigDirectory = "/data/user/0/app.chimahon.dev/files",
                useExternalConfigDirectory = true,
                externalConfigDirectory = { "/storage/emulated/0/chimaFork/mpv" },
            ),
        )
    }

    @Test
    fun `missing external directory falls back to internal storage`() {
        assertEquals(
            "/data/user/0/app.chimahon.dev/files",
            resolveMpvConfigDirectory(
                internalConfigDirectory = "/data/user/0/app.chimahon.dev/files",
                useExternalConfigDirectory = true,
                externalConfigDirectory = { null },
            ),
        )
    }

    @Test
    fun `blank external path falls back to internal storage`() {
        assertEquals(
            "/data/user/0/app.chimahon.dev/files",
            resolveMpvConfigDirectory(
                internalConfigDirectory = "/data/user/0/app.chimahon.dev/files",
                useExternalConfigDirectory = true,
                externalConfigDirectory = { " " },
            ),
        )
    }

    @Test
    fun `external lookup failure falls back and reports the cause`() {
        val failure = SecurityException("Persisted URI grant is missing")
        var reportedFailure: Exception? = null

        assertEquals(
            "/data/user/0/app.chimahon.dev/files",
            resolveMpvConfigDirectory(
                internalConfigDirectory = "/data/user/0/app.chimahon.dev/files",
                useExternalConfigDirectory = true,
                externalConfigDirectory = { throw failure },
                onExternalFailure = { reportedFailure = it },
            ),
        )
        assertSame(failure, reportedFailure)
    }

    @Test
    fun `external directory is not queried without all files access`() {
        var lookupCount = 0

        assertEquals(
            "/data/user/0/app.chimahon.dev/files",
            resolveMpvConfigDirectory(
                internalConfigDirectory = "/data/user/0/app.chimahon.dev/files",
                useExternalConfigDirectory = false,
                externalConfigDirectory = {
                    lookupCount++
                    "/storage/emulated/0/chimaFork/mpv"
                },
            ),
        )
        assertEquals(0, lookupCount)
    }
}
