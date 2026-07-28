package eu.kanade.tachiyomi.stats

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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

@RunWith(AndroidJUnit4::class)
class ImmersionStatsDeviceValidationTest {

    @Test
    fun productionRepositoryPersistsAndQueriesOnDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = Injekt.get<SqlDelightImmersionRepository>()
        val now = Instant.now().toEpochMilli()
        repository.resetAllStats(
            deviceId = "release-validation-device",
            deletedAtEpochMillis = now,
        )
        val offsetSeconds = ZoneId.systemDefault().rules
            .getOffset(Instant.ofEpochMilli(now))
            .totalSeconds
        val titleId = TitleId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val sourceId = SourceUnitId(UUID.randomUUID().toString())

        val title = ImmersionTitle(
            id = titleId,
            mediaKind = MediaKind.NOVEL,
            sourceKey = "release-validation",
            languageTag = LanguageTag("ja"),
            displayTitle = "Release validation",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val start = ImmersionSessionStart(
            id = sessionId,
            deviceId = "release-validation-device",
            titleId = titleId,
            mediaKind = MediaKind.NOVEL,
            languageTag = LanguageTag("ja"),
            startedAtEpochMillis = now,
            startZoneId = "UTC",
            startOffsetSeconds = offsetSeconds,
            captureVersion = ImmersionStatsVersions.CAPTURE,
            schemaVersion = ImmersionStatsVersions.SCHEMA,
        )
        val startEvent = SessionEvent(
            id = EventId(UUID.randomUUID().toString()),
            sessionId = sessionId,
            sequence = 1,
            occurredAtEpochMillis = now,
            timezoneOffsetSeconds = offsetSeconds,
            type = EventType.SESSION_STARTED,
        )
        assertSuccessful(repository.startSession(title, start, startEvent))

        val source = ImmersionSourceUnit(
            id = sourceId,
            titleId = titleId,
            sourceKind = SourceKind.NOVEL_RANGE,
            canonicalLocator = "chapter-1:0-3",
            normalizedTextHash = "release-validation-hash",
            rawText = "日本語",
            firstExposedAtEpochMillis = now + 1,
            lastExposedAtEpochMillis = now + 1,
            characterCounts = CharacterVolume(
                gross = NonNegativeCounter(3),
                uniqueSource = NonNegativeCounter(3),
                netProgress = NetCharacterProgress(3),
            ),
        )
        val writeStarted = SystemClock.elapsedRealtimeNanos()
        assertSuccessful(repository.upsertSourceUnit(source))
        assertSuccessful(
            repository.appendExposure(
                ExposureEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = sessionId,
                    sequence = 2,
                    occurredAtEpochMillis = now + 1,
                    timezoneOffsetSeconds = offsetSeconds,
                    source = source,
                    activeDuration = MillisecondDuration(1_000),
                    grossCharacters = NonNegativeCounter(3),
                    uniqueSourceCharacters = NonNegativeCounter(3),
                    netCharacters = NetCharacterProgress(3),
                    exposurePolicy = "release-validation",
                ),
            ),
        )
        assertSuccessful(
            repository.appendEventBatch(
                listOf(
                    SessionEvent(
                        id = EventId(UUID.randomUUID().toString()),
                        sessionId = sessionId,
                        sequence = 3,
                        occurredAtEpochMillis = now + 1_000,
                        timezoneOffsetSeconds = offsetSeconds,
                        type = EventType.SESSION_FINALIZED,
                    ),
                ),
            ).single(),
        )
        assertSuccessful(
            repository.finalizeSession(
                sessionId = sessionId,
                status = SessionStatus.COMPLETED,
                endedAtEpochMillis = now + 1_000,
                elapsedDuration = MillisecondDuration(1_000),
            ),
        )
        val writeNanos = SystemClock.elapsedRealtimeNanos() - writeStarted

        val queryStarted = SystemClock.elapsedRealtimeNanos()
        val overview = repository.overview()
        val queryNanos = SystemClock.elapsedRealtimeNanos() - queryStarted
        assertEquals(3L, overview.grossCharacters.value)
        assertEquals(3L, overview.uniqueSourceCharacters.value)
        assertEquals(3L, overview.netCharacters.value)
        assertEquals(1L, overview.sessions.value)
        assertEquals(1L, overview.sourceUnits.value)

        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("appCommit", eu.kanade.tachiyomi.BuildConfig.COMMIT_SHA)
            .put("buildType", eu.kanade.tachiyomi.BuildConfig.BUILD_TYPE)
            .put("applicationId", eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID)
            .put("deviceModel", Build.MODEL)
            .put("deviceProduct", Build.PRODUCT)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("testedAtEpochMillis", now)
            .put("repositoryWriteNanos", writeNanos)
            .put("repositoryOverviewQueryNanos", queryNanos)
            .put("databaseBytes", context.databaseList().sumOf { context.getDatabasePath(it).length() })
            .put("decision", "pass")

        val outputDirectory = checkNotNull(context.getExternalFilesDir("release-evidence"))
        val output = File(outputDirectory, "device-smoke-api${Build.VERSION.SDK_INT}.json")
        output.writeText(report.toString(2))
        assertTrue(output.isFile)
    }

    private fun assertSuccessful(result: PersistenceResult) {
        assertTrue(
            "Unexpected persistence result: $result",
            result == PersistenceResult.Applied || result == PersistenceResult.AlreadyApplied,
        )
    }
}
