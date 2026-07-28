package eu.kanade.tachiyomi.stats

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.immersion.SqlDelightImmersionRepository
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.SessionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.service.ImmersionStatsVersions
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Device-scale release evidence for the immersion statistics store.
 *
 * This test persists a large source fixture through the production SQLDelight
 * repository, then measures overview/timeline query cost and on-device database
 * and raw-text growth. It writes a privacy-safe JSON report containing only
 * counts, byte sizes, and durations.
 */
@RunWith(AndroidJUnit4::class)
class ImmersionStatsDeviceScaleTest {

    private val deviceId = "release-validation-scale-device"

    @Test
    fun largeSourceFixtureGrowthAndTimelineOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = Injekt.get<SqlDelightImmersionRepository>()
        val now = Instant.now().toEpochMilli()
        repository.resetAllStats(deviceId = deviceId, deletedAtEpochMillis = now)

        val databaseBytesBefore = context.databaseBytes()
        val offsetSeconds = ZoneId.systemDefault().rules
            .getOffset(Instant.ofEpochMilli(now))
            .totalSeconds

        val titleId = TitleId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        assertSuccessful(
            repository.startSession(
                ImmersionTitle(
                    id = titleId,
                    mediaKind = MediaKind.NOVEL,
                    sourceKey = "release-validation-scale",
                    languageTag = LanguageTag("ja"),
                    displayTitle = "Release validation scale",
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
                ImmersionSessionStart(
                    id = sessionId,
                    deviceId = deviceId,
                    titleId = titleId,
                    mediaKind = MediaKind.NOVEL,
                    languageTag = LanguageTag("ja"),
                    startedAtEpochMillis = now,
                    startZoneId = ZoneId.systemDefault().id,
                    startOffsetSeconds = offsetSeconds,
                    captureVersion = ImmersionStatsVersions.CAPTURE,
                    schemaVersion = ImmersionStatsVersions.SCHEMA,
                ),
                SessionEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = sessionId,
                    sequence = 1,
                    occurredAtEpochMillis = now,
                    timezoneOffsetSeconds = offsetSeconds,
                    type = EventType.SESSION_STARTED,
                ),
            ),
        )

        // Each source unit carries a distinct 40-character Japanese line so the
        // fixture exercises realistic raw-text growth, not repeated identical rows.
        val lineLength = 40
        var sequence = 2L
        var expectedGross = 0L
        val writeStarted = SystemClock.elapsedRealtimeNanos()
        repeat(SOURCE_UNIT_COUNT) { index ->
            val text = syntheticLine(index, lineLength)
            val countable = text.codePointCount(0, text.length).toLong()
            val source = ImmersionSourceUnit(
                id = SourceUnitId(UUID.randomUUID().toString()),
                titleId = titleId,
                sourceKind = SourceKind.NOVEL_RANGE,
                canonicalLocator = "chapter-${index / 200}:range-$index",
                normalizedTextHash = "scale-hash-$index",
                rawText = text,
                firstExposedAtEpochMillis = now + index + 1,
                lastExposedAtEpochMillis = now + index + 1,
                characterCounts = CharacterVolume(
                    gross = NonNegativeCounter(countable),
                    uniqueSource = NonNegativeCounter(countable),
                    netProgress = NetCharacterProgress(countable),
                ),
            )
            assertSuccessful(repository.upsertSourceUnit(source))
            assertSuccessful(
                repository.appendExposure(
                    ExposureEvent(
                        id = EventId(UUID.randomUUID().toString()),
                        sessionId = sessionId,
                        sequence = sequence++,
                        occurredAtEpochMillis = now + index + 1,
                        timezoneOffsetSeconds = offsetSeconds,
                        source = source,
                        activeDuration = MillisecondDuration(1_000),
                        grossCharacters = NonNegativeCounter(countable),
                        uniqueSourceCharacters = NonNegativeCounter(countable),
                        netCharacters = NetCharacterProgress(countable),
                        exposurePolicy = "release-validation-scale",
                    ),
                ),
            )
            expectedGross += countable
        }
        val writeNanos = SystemClock.elapsedRealtimeNanos() - writeStarted

        assertSuccessful(
            repository.finalizeSession(
                sessionId = sessionId,
                status = SessionStatus.COMPLETED,
                endedAtEpochMillis = now + SOURCE_UNIT_COUNT + 1,
                elapsedDuration = MillisecondDuration(SOURCE_UNIT_COUNT * 1_000L),
            ),
        )

        val overviewStarted = SystemClock.elapsedRealtimeNanos()
        val overview = repository.overview()
        val overviewNanos = SystemClock.elapsedRealtimeNanos() - overviewStarted
        assertEquals(expectedGross, overview.grossCharacters.value)
        assertEquals(SOURCE_UNIT_COUNT.toLong(), overview.sourceUnits.value)

        val timelineStarted = SystemClock.elapsedRealtimeNanos()
        val detail = repository.sessionDetail(sessionId, TIMELINE_BUCKETS)
        val timelineNanos = SystemClock.elapsedRealtimeNanos() - timelineStarted
        val timeline = checkNotNull(detail).timeline
        assertTrue("Timeline should be bounded", timeline.size <= TIMELINE_BUCKETS)
        assertTrue("Timeline should not be empty", timeline.isNotEmpty())
        assertEquals(
            "Timeline gross characters must reconcile with the overview",
            expectedGross,
            timeline.sumOf { it.grossCharacters },
        )

        val databaseBytesAfter = context.databaseBytes()
        val rawTextBytes = expectedGross * 3 // UTF-8 bytes per CJK code point

        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("evidenceKind", "device-scale-growth-and-timeline")
            .put("appCommit", eu.kanade.tachiyomi.BuildConfig.COMMIT_SHA)
            .put("buildType", eu.kanade.tachiyomi.BuildConfig.BUILD_TYPE)
            .put("applicationId", eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID)
            .put("deviceModel", Build.MODEL)
            .put("deviceProduct", Build.PRODUCT)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("testedAtEpochMillis", now)
            .put("sourceUnitCount", SOURCE_UNIT_COUNT)
            .put("charactersPerSourceUnit", lineLength)
            .put("grossCharacters", expectedGross)
            .put("estimatedRawTextBytes", rawTextBytes)
            .put("databaseBytesBefore", databaseBytesBefore)
            .put("databaseBytesAfter", databaseBytesAfter)
            .put("databaseGrowthBytes", databaseBytesAfter - databaseBytesBefore)
            .put(
                "databaseBytesPerSourceUnit",
                (databaseBytesAfter - databaseBytesBefore) / SOURCE_UNIT_COUNT,
            )
            .put("fixtureWriteNanos", writeNanos)
            .put("overviewQueryNanos", overviewNanos)
            .put("timelineQueryNanos", timelineNanos)
            .put("timelineBucketsRequested", TIMELINE_BUCKETS)
            .put("timelineBucketsReturned", timeline.size)
            .put(
                "timelineKnownnessAvailable",
                timeline.any { it.knownness != null },
            )
            .put("measurementCaveats", JSONArray().put(EMULATION_CAVEAT))
            .put("decision", "measured")

        val directory = checkNotNull(context.getExternalFilesDir("release-evidence"))
        val output = File(directory, "device-scale-api${Build.VERSION.SDK_INT}.json")
        output.writeText(report.toString(2))
        assertTrue(output.isFile)
    }

    private fun android.content.Context.databaseBytes(): Long =
        databaseList().sumOf { getDatabasePath(it).length() }

    /** Builds a deterministic, distinct CJK line so fixtures are reproducible. */
    private fun syntheticLine(index: Int, length: Int): String {
        val builder = StringBuilder(length)
        var codePoint = CJK_BLOCK_START + (index * length) % CJK_BLOCK_SIZE
        repeat(length) {
            builder.appendCodePoint(CJK_BLOCK_START + (codePoint - CJK_BLOCK_START) % CJK_BLOCK_SIZE)
            codePoint++
        }
        return builder.toString()
    }

    private fun assertSuccessful(result: PersistenceResult) {
        assertTrue(
            "Unexpected persistence result: $result",
            result == PersistenceResult.Applied || result == PersistenceResult.AlreadyApplied,
        )
    }

    private companion object {
        const val SOURCE_UNIT_COUNT = 2_500
        const val TIMELINE_BUCKETS = 120
        const val CJK_BLOCK_START = 0x4E00
        const val CJK_BLOCK_SIZE = 0x51A5 - 0x4E00
        const val EMULATION_CAVEAT =
            "Durations are indicative only unless the host reports hardware acceleration; " +
                "software-emulated devices are not a representative performance target."
    }
}
