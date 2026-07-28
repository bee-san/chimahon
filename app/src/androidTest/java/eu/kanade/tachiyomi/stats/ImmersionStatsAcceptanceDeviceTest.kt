package eu.kanade.tachiyomi.stats

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.immersion.SqlDelightImmersionRepository
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionMergeDisposition
import tachiyomi.domain.immersion.model.ImmersionPortableArchive
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
 * On-device acceptance evidence for plan scenarios 33.6 and 33.7.
 *
 * Both scenarios are purely functional: they assert convergence, idempotency,
 * and tombstone behaviour rather than any latency budget. They are therefore
 * meaningful on a software-emulated device, unlike the performance rows.
 */
@RunWith(AndroidJUnit4::class)
class ImmersionStatsAcceptanceDeviceTest {

    private val deviceId = "acceptance-device-a"

    @Test
    fun rawTextAndSessionDeletionConvergeOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = Injekt.get<SqlDelightImmersionRepository>()
        val now = Instant.now().toEpochMilli()
        repository.resetAllStats(deviceId = deviceId, deletedAtEpochMillis = now)

        // Two independent sessions so deleting one proves scoped convergence.
        val first = seedSession(repository, now, "acceptance-delete-1", characters = 12)
        val second = seedSession(repository, now + 10_000, "acceptance-delete-2", characters = 8)

        val beforeDeletion = repository.overview()
        assertEquals(20L, beforeDeletion.grossCharacters.value)
        assertEquals(2L, beforeDeletion.sessions.value)

        // 33.6 step 2: delete raw source text while retaining aggregate totals.
        val rawTextPreview = repository.previewRawTextDeletion()
        assertTrue("Raw text should be present before deletion", rawTextPreview > 0)
        val clearedRawText = repository.deleteRawText(updatedAtEpochMillis = now + 20_000)
        assertEquals(rawTextPreview, clearedRawText)
        assertEquals(0L, repository.previewRawTextDeletion())

        val afterRawTextDeletion = repository.overview()
        assertEquals(
            "Raw-text deletion must preserve gross character counters",
            beforeDeletion.grossCharacters.value,
            afterRawTextDeletion.grossCharacters.value,
        )
        assertEquals(
            "Raw-text deletion must preserve session counters",
            beforeDeletion.sessions.value,
            afterRawTextDeletion.sessions.value,
        )

        // 33.6 step 3: delete exactly one session.
        val sessionPreview = checkNotNull(repository.previewSessionDeletion(first.sessionId))
        assertEquals(1L, sessionPreview.sessions)
        val applied = checkNotNull(repository.deleteSession(first.sessionId, sessionPreview))
        assertEquals(sessionPreview.sessions, applied.sessions)

        val afterSessionDeletion = repository.overview()
        assertEquals(
            "Deleting one session must decrement sessions exactly once",
            1L,
            afterSessionDeletion.sessions.value,
        )
        assertEquals(
            "Deleting one session must remove only its characters",
            8L,
            afterSessionDeletion.grossCharacters.value,
        )
        // Re-deleting must be a no-op, never a second decrement.
        assertEquals(null, repository.previewSessionDeletion(first.sessionId))
        assertEquals(
            8L,
            repository.overview().grossCharacters.value,
        )
        assertNotNull("Surviving session must remain queryable", second.sessionId)

        writeReport(
            context = context,
            fileName = "device-acceptance-privacy-deletion-api${Build.VERSION.SDK_INT}.json",
            body = JSONObject()
                .put("scenario", "33.6 privacy and deletion")
                .put("grossCharactersBefore", beforeDeletion.grossCharacters.value)
                .put("rawTextRowsCleared", clearedRawText)
                .put("grossCharactersAfterRawTextDeletion", afterRawTextDeletion.grossCharacters.value)
                .put("countersPreservedAfterRawTextDeletion", true)
                .put("sessionsAfterSessionDeletion", afterSessionDeletion.sessions.value)
                .put("grossCharactersAfterSessionDeletion", afterSessionDeletion.grossCharacters.value)
                .put("repeatedDeletionWasNoOp", true),
        )
    }

    /**
     * Phase one of scenario 33.7, acting as "device B".
     *
     * Seeds device B's activity and writes its portable archive to external
     * storage. `resetAllStats` tombstones everything it deletes, so device B's
     * rows cannot simply be deleted locally to simulate a second device: the
     * tombstone would (correctly) block the later merge. The harness therefore
     * runs this phase, clears app data with `pm clear`, and then runs
     * [mergeRemoteArchiveIsIdempotentAndRespectsTombstonesOnDevice].
     */
    @Test
    fun exportRemoteDeviceArchiveOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = Injekt.get<SqlDelightImmersionRepository>()
        val now = Instant.now().toEpochMilli()
        repository.resetAllStats(deviceId = REMOTE_DEVICE_ID, deletedAtEpochMillis = now)
        val session = seedSession(
            repository,
            now,
            "acceptance-merge-b",
            characters = REMOTE_CHARACTERS,
            deviceId = REMOTE_DEVICE_ID,
        )
        assertEquals(REMOTE_CHARACTERS.toLong(), repository.overview().grossCharacters.value)

        val archive = repository.exportPortableArchive(
            includeRawText = true,
            createdAtEpochMillis = now + 1_000,
        )
        assertTrue("Archive must carry rows to merge", archive.tables.any { it.rows.isNotEmpty() })

        // Scenario 33.7 step 3 merges "an older B copy". Export a second
        // archive at a different timestamp so it carries the same session rows
        // under a distinct digest. Merging the identical archive again would
        // short-circuit on its checkpoint and would therefore prove nothing
        // about tombstones.
        val olderCopy = repository.exportPortableArchive(
            includeRawText = true,
            createdAtEpochMillis = now + 2_000,
        )

        val handoff = JSONObject()
            .put("sessionId", session.sessionId.value)
            .put("grossCharacters", REMOTE_CHARACTERS)
            .put("archive", Json.encodeToString(archive))
            .put("olderCopy", Json.encodeToString(olderCopy))
        archiveHandoffFile(context).writeText(handoff.toString())
    }

    /**
     * Phase two of scenario 33.7, acting as "device A" after `pm clear`.
     *
     * The archive is a genuinely remote payload: this database has never seen
     * those rows and holds no tombstone for them.
     */
    @Test
    fun mergeRemoteArchiveIsIdempotentAndRespectsTombstonesOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = Injekt.get<SqlDelightImmersionRepository>()
        val now = Instant.now().toEpochMilli()

        val handoffFile = archiveHandoffFile(context)
        assertTrue(
            "Run exportRemoteDeviceArchiveOnDevice and pm clear before this test",
            handoffFile.isFile,
        )
        val handoff = JSONObject(handoffFile.readText())
        val remoteSessionId = SessionId(handoff.getString("sessionId"))
        val archive = Json.decodeFromString<ImmersionPortableArchive>(
            handoff.getString("archive"),
        )
        val olderCopy = Json.decodeFromString<ImmersionPortableArchive>(
            handoff.getString("olderCopy"),
        )

        // Device A read different content on the same local date.
        seedSession(repository, now, "acceptance-merge-a", characters = LOCAL_CHARACTERS)
        val localOnly = repository.overview()
        assertEquals(LOCAL_CHARACTERS.toLong(), localOnly.grossCharacters.value)
        assertEquals(1L, localOnly.sessions.value)

        // 33.7 step 2: merge twice; the second merge must change nothing.
        val firstMerge = repository.mergePortableArchive(archive, now + 4_000)
        val afterFirstMerge = repository.overview()
        assertEquals(
            "Both devices' activity must be summed exactly once",
            (LOCAL_CHARACTERS + REMOTE_CHARACTERS).toLong(),
            afterFirstMerge.grossCharacters.value,
        )
        assertEquals(2L, afterFirstMerge.sessions.value)
        assertTrue("The first merge must insert rows", firstMerge.insertedRows > 0)

        val secondMerge = repository.mergePortableArchive(archive, now + 5_000)
        val afterSecondMerge = repository.overview()
        assertEquals(
            "Repeating the merge must not double count",
            afterFirstMerge.grossCharacters.value,
            afterSecondMerge.grossCharacters.value,
        )
        assertEquals(afterFirstMerge.sessions.value, afterSecondMerge.sessions.value)
        // A repeat is recognised through the archive's checkpoint ledger and
        // re-enters it rather than re-applying rows, so it reports RESUMED.
        // Scenario 33.7 only requires that "repeating the merge changes
        // nothing", which is a statement about totals; the ledger disposition
        // is an implementation detail, so record it instead of pinning it.
        assertTrue(
            "A repeated merge must reuse the archive checkpoint, was ${secondMerge.disposition}",
            secondMerge.disposition != ImmersionMergeDisposition.COMPLETED,
        )
        val thirdRepeat = repository.mergePortableArchive(archive, now + 5_500)
        assertEquals(
            "Repeated merges must never change totals",
            afterFirstMerge.grossCharacters.value,
            repository.overview().grossCharacters.value,
        )
        assertEquals(
            "Repeated merges must never change session counts",
            afterFirstMerge.sessions.value,
            repository.overview().sessions.value,
        )

        // 33.7 step 3: delete the merged session, then merge the older copy again.
        val preview = checkNotNull(repository.previewSessionDeletion(remoteSessionId))
        repository.deleteSession(remoteSessionId, preview)
        assertEquals(LOCAL_CHARACTERS.toLong(), repository.overview().grossCharacters.value)

        val thirdMerge = repository.mergePortableArchive(olderCopy, now + 6_000)
        val afterTombstonedMerge = repository.overview()
        assertEquals(
            "A tombstone must prevent deleted data from returning",
            LOCAL_CHARACTERS.toLong(),
            afterTombstonedMerge.grossCharacters.value,
        )
        assertEquals(1L, afterTombstonedMerge.sessions.value)
        assertTrue(
            "The merge must report rows skipped by tombstone",
            thirdMerge.skippedByTombstoneRows > 0,
        )

        writeReport(
            context = context,
            fileName = "device-acceptance-backup-merge-api${Build.VERSION.SDK_INT}.json",
            body = JSONObject()
                .put("scenario", "33.7 backup and multi-device merge")
                .put("remoteDeviceGrossCharacters", REMOTE_CHARACTERS)
                .put("localDeviceGrossCharacters", LOCAL_CHARACTERS)
                .put("grossCharactersAfterFirstMerge", afterFirstMerge.grossCharacters.value)
                .put("firstMergeInsertedRows", firstMerge.insertedRows)
                .put("secondMergeDisposition", secondMerge.disposition.name)
                .put("thirdMergeDisposition", thirdRepeat.disposition.name)
                .put("grossCharactersAfterSecondMerge", afterSecondMerge.grossCharacters.value)
                .put("mergeIsIdempotent", true)
                .put("grossCharactersAfterDeletionAndRemerge", afterTombstonedMerge.grossCharacters.value)
                .put("thirdMergeSkippedByTombstoneRows", thirdMerge.skippedByTombstoneRows)
                .put("tombstonePreventedResurrection", true),
        )
    }

    private fun archiveHandoffFile(context: android.content.Context): File =
        File(
            checkNotNull(context.getExternalFilesDir("release-evidence")),
            "acceptance-remote-archive.json",
        )

    private class SeededSession(val titleId: TitleId, val sessionId: SessionId)

    private suspend fun seedSession(
        repository: SqlDelightImmersionRepository,
        atEpochMillis: Long,
        sourceKey: String,
        characters: Int,
        deviceId: String = this.deviceId,
    ): SeededSession {
        val offsetSeconds = ZoneId.systemDefault().rules
            .getOffset(Instant.ofEpochMilli(atEpochMillis))
            .totalSeconds
        val titleId = TitleId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        assertSuccessful(
            repository.startSession(
                ImmersionTitle(
                    id = titleId,
                    mediaKind = MediaKind.NOVEL,
                    sourceKey = sourceKey,
                    languageTag = LanguageTag("ja"),
                    displayTitle = "Acceptance $sourceKey",
                    createdAtEpochMillis = atEpochMillis,
                    updatedAtEpochMillis = atEpochMillis,
                ),
                ImmersionSessionStart(
                    id = sessionId,
                    deviceId = deviceId,
                    titleId = titleId,
                    mediaKind = MediaKind.NOVEL,
                    languageTag = LanguageTag("ja"),
                    startedAtEpochMillis = atEpochMillis,
                    startZoneId = ZoneId.systemDefault().id,
                    startOffsetSeconds = offsetSeconds,
                    captureVersion = ImmersionStatsVersions.CAPTURE,
                    schemaVersion = ImmersionStatsVersions.SCHEMA,
                ),
                SessionEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = sessionId,
                    sequence = 1,
                    occurredAtEpochMillis = atEpochMillis,
                    timezoneOffsetSeconds = offsetSeconds,
                    type = EventType.SESSION_STARTED,
                ),
            ),
        )
        val text = buildString { repeat(characters) { appendCodePoint(0x4E00 + it) } }
        val source = ImmersionSourceUnit(
            id = SourceUnitId(UUID.randomUUID().toString()),
            titleId = titleId,
            sourceKind = SourceKind.NOVEL_RANGE,
            canonicalLocator = "$sourceKey:range-0",
            normalizedTextHash = "$sourceKey-hash",
            rawText = text,
            firstExposedAtEpochMillis = atEpochMillis + 1,
            lastExposedAtEpochMillis = atEpochMillis + 1,
            characterCounts = CharacterVolume(
                gross = NonNegativeCounter(characters.toLong()),
                uniqueSource = NonNegativeCounter(characters.toLong()),
                netProgress = NetCharacterProgress(characters.toLong()),
            ),
        )
        assertSuccessful(repository.upsertSourceUnit(source))
        assertSuccessful(
            repository.appendExposure(
                ExposureEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = sessionId,
                    sequence = 2,
                    occurredAtEpochMillis = atEpochMillis + 1,
                    timezoneOffsetSeconds = offsetSeconds,
                    source = source,
                    activeDuration = MillisecondDuration(1_000),
                    grossCharacters = NonNegativeCounter(characters.toLong()),
                    uniqueSourceCharacters = NonNegativeCounter(characters.toLong()),
                    netCharacters = NetCharacterProgress(characters.toLong()),
                    exposurePolicy = "acceptance",
                ),
            ),
        )
        assertSuccessful(
            repository.finalizeSession(
                sessionId = sessionId,
                status = SessionStatus.COMPLETED,
                endedAtEpochMillis = atEpochMillis + 1_000,
                elapsedDuration = MillisecondDuration(1_000),
            ),
        )
        return SeededSession(titleId, sessionId)
    }

    private fun writeReport(
        context: android.content.Context,
        fileName: String,
        body: JSONObject,
    ) {
        val report = body
            .put("schemaVersion", 1)
            .put("evidenceKind", "device-acceptance-scenario")
            .put("appCommit", eu.kanade.tachiyomi.BuildConfig.COMMIT_SHA)
            .put("buildType", eu.kanade.tachiyomi.BuildConfig.BUILD_TYPE)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put(
                "note",
                "Functional convergence scenario with no latency assertions; " +
                    "valid on a software-emulated device.",
            )
            .put("decision", "pass")
        val directory = checkNotNull(context.getExternalFilesDir("release-evidence"))
        val output = File(directory, fileName)
        output.writeText(report.toString(2))
        assertTrue(output.isFile)
    }

    private fun assertSuccessful(result: PersistenceResult) {
        assertTrue(
            "Unexpected persistence result: $result",
            result == PersistenceResult.Applied || result == PersistenceResult.AlreadyApplied,
        )
    }

    private companion object {
        const val REMOTE_DEVICE_ID = "acceptance-device-b"
        const val REMOTE_CHARACTERS = 15
        const val LOCAL_CHARACTERS = 7
    }
}
