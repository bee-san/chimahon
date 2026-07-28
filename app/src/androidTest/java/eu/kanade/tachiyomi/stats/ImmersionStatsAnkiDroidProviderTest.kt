package eu.kanade.tachiyomi.stats

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chimahon.anki.AnkiDroidInventoryProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.immersion.model.CapabilityState
import java.io.File

/**
 * Release evidence for the real AnkiDroid flashcards ContentProvider.
 *
 * This test runs against the officially published AnkiDroid APK installed on the
 * device. It deliberately does not mock the provider: the point of this row is to
 * prove the production probe classifies the live provider correctly, including
 * its declared capability limits.
 */
@RunWith(AndroidJUnit4::class)
class ImmersionStatsAnkiDroidProviderTest {

    @Test
    fun productionProbeClassifiesLiveAnkiDroidProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AnkiDroidInventoryProvider(context)

        val installedVersion = try {
            context.packageManager.getPackageInfo(ANKIDROID_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        assertNotNull(
            "Official AnkiDroid must be installed for this evidence row",
            installedVersion,
        )
        val permissionGranted =
            context.checkSelfPermission(ANKIDROID_PERMISSION) == PackageManager.PERMISSION_GRANTED
        assertTrue(
            "The app must hold AnkiDroid's read permission for this evidence row",
            permissionGranted,
        )

        // Disabled integration must report Unavailable rather than "all unknown".
        val disabled = provider.probe(enabled = false)
        assertEquals(CapabilityState.UNAVAILABLE, disabled.state)

        val probeStarted = SystemClock.elapsedRealtimeNanos()
        val capability = provider.probe(enabled = true)
        val probeNanos = SystemClock.elapsedRealtimeNanos() - probeStarted

        assertEquals(
            "Live AnkiDroid provider should be reachable: ${capability.failure}",
            CapabilityState.AVAILABLE,
            capability.state,
        )
        // The public provider exposes note mod time but no card mod time or
        // review history; the probe must report those limits honestly.
        assertTrue(capability.noteModificationTime)
        assertEquals(false, capability.cardModificationTime)
        assertEquals(false, capability.reviewHistory)

        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("evidenceKind", "live-ankidroid-provider-probe")
            .put("appCommit", eu.kanade.tachiyomi.BuildConfig.COMMIT_SHA)
            .put("buildType", eu.kanade.tachiyomi.BuildConfig.BUILD_TYPE)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("ankiDroidPackage", ANKIDROID_PACKAGE)
            .put("ankiDroidVersion", installedVersion)
            .put("permissionGranted", permissionGranted)
            .put("mocked", false)
            .put("capabilityState", capability.state.name)
            .put("reportedPackageVersion", capability.packageVersion)
            .put("noteModificationTime", capability.noteModificationTime)
            .put("cardModificationTime", capability.cardModificationTime)
            .put("reviewHistory", capability.reviewHistory)
            .put("disabledProbeState", disabled.state.name)
            .put("disabledProbeFailure", disabled.failure?.name)
            .put("probeNanos", probeNanos)
            .put("decision", "pass")

        val directory = checkNotNull(context.getExternalFilesDir("release-evidence"))
        val output = File(directory, "device-ankidroid-api${Build.VERSION.SDK_INT}.json")
        output.writeText(report.toString(2))
        assertTrue(output.isFile)
    }

    private companion object {
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    }
}
