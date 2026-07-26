// SPDX-License-Identifier: MIT

package mihon.feature.stats.legacy

import android.app.Application
import com.canopus.chimareader.data.AnkiStats
import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.BookStorage
import com.canopus.chimareader.data.FileNames
import com.canopus.chimareader.data.MangaStats
import com.canopus.chimareader.data.Statistics
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LegacyAggregateRow
import tachiyomi.domain.immersion.model.LegacyDailyAggregate
import tachiyomi.domain.immersion.model.LegacyImportBatch
import tachiyomi.domain.immersion.model.LegacyImportIdentity
import tachiyomi.domain.immersion.model.LegacyImportIssueCode
import tachiyomi.domain.immersion.model.LegacyImportResult
import tachiyomi.domain.immersion.model.LegacyImportSourceKind
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.repository.ImmersionLegacyImportRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToLong

class LegacyStatsImporter(
    private val application: Application,
    private val repository: ImmersionLegacyImportRepository,
    private val getManga: GetManga,
    private val dictionaryPreferences: DictionaryPreferences,
    private val sourceManager: SourceManager,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun importAll(): LegacyStatsImportReport = withIOContext {
        val issues = mutableListOf<LegacyImportIssue>()
        val expected = mutableListOf<LegacyDailyAggregate>()
        val results = mutableListOf<LegacyImportResult>()

        loadNovelDocuments().forEach { document ->
            val plan = planNovel(document)
            issues += plan.issues
            expected += plan.aggregates
            results += repository.importLegacyBatch(plan.batch)
        }

        loadGlobalDocument(FileNames.MANGA_STATS, LegacyImportSourceKind.MANGA_JSON)?.let { document ->
            val plans = planManga(document)
            plans.forEach { plan ->
                issues += plan.issues
                expected += plan.aggregates
                results += repository.importLegacyBatch(plan.batch)
            }
        }

        loadGlobalDocument(FileNames.ANKI_STATS, LegacyImportSourceKind.ANKI_JSON)?.let { document ->
            val plans = planAnki(document)
            plans.forEach { plan ->
                issues += plan.issues
                expected += plan.aggregates
                results += repository.importLegacyBatch(plan.batch)
            }
        }

        LegacyStatsImportReport(
            results = results,
            issues = issues,
            reconciliation = reconcile(expected, repository.getLegacyAggregates()),
        )
    }

    fun exportReport(report: LegacyStatsImportReport, destination: File) {
        destination.parentFile?.mkdirs()
        destination.writeText(REPORT_JSON.encodeToString(report))
    }

    internal suspend fun planNovel(document: LegacySourceDocument): LegacyImportPlan {
        require(document.kind == LegacyImportSourceKind.NOVEL_JSON)
        val parsed = parseArray<Statistics>(document)
        val descriptor = requireNotNull(document.novel) { "Novel documents require title metadata" }
        val valid = parsed.values.mapNotNull { indexed ->
            indexed.value.toNovelValue(document, indexed.index, parsed.issues)
        }
        val aggregates = valid
            .groupBy(LegacyNovelValue::date)
            .map { (date, values) ->
                val readingSeconds = values.sumOf { it.stats.readingTime }
                val characters = values.sumOfExact { it.stats.charactersRead.toLong() }
                val completed = values.mapNotNull { it.stats.completedBook }.maxOrNull()?.let { it > 0 }
                aggregate(
                    document = document,
                    descriptor = descriptor,
                    mediaKind = MediaKind.NOVEL,
                    profileId = descriptor.profileId,
                    localDate = date,
                    activeDurationMillis = secondsToMillis(readingSeconds),
                    originalReadingTimeSeconds = readingSeconds,
                    characters = characters,
                    cards = 0,
                    completed = completed,
                    metadata = novelMetadata(values.map(LegacyNovelValue::stats)),
                )
            }
        return planForSingleLogicalSource(document, aggregates, parsed.issues)
    }

    internal suspend fun planManga(document: LegacySourceDocument): List<LegacyImportPlan> {
        require(document.kind == LegacyImportSourceKind.MANGA_JSON)
        val parsed = parseArray<MangaStats>(document)
        val valid = parsed.values.mapNotNull { indexed ->
            indexed.value.toMangaValue(document, indexed.index, parsed.issues)
        }
        val plans = valid
            .groupBy { it.stats.mangaId }
            .map { (mangaId, titleValues) ->
                val descriptor = resolveManga(mangaId)
                val aggregates = titleValues
                    .groupBy(LegacyMangaValue::date)
                    .map { (date, values) ->
                        aggregate(
                            document = document,
                            descriptor = descriptor,
                            mediaKind = MediaKind.MANGA,
                            profileId = descriptor.profileId,
                            localDate = date,
                            activeDurationMillis = values.sumOfExact { it.stats.readingTime },
                            characters = values.sumOfExact { it.stats.charactersRead.toLong() },
                            cards = 0,
                        )
                    }
                val logical = document.copy(sourceKey = "${document.sourceKey}#manga=$mangaId")
                planForSingleLogicalSource(logical, aggregates, emptyList())
            }
            .toMutableList()
        addParseIssuePlan(document, parsed.issues, plans)
        return plans.ifEmpty { listOf(planForSingleLogicalSource(document, emptyList(), parsed.issues)) }
    }

    internal fun planAnki(document: LegacySourceDocument): List<LegacyImportPlan> {
        require(document.kind == LegacyImportSourceKind.ANKI_JSON)
        val parsed = parseArray<AnkiStats>(document)
        val values = parsed.values.mapNotNull { indexed ->
            indexed.value.toAnkiValue(document, indexed.index, parsed.issues)
        }
        val expanded = values.flatMap { value ->
            buildList {
                if (value.stats.mangaCards > 0) add(value.toMedia(MediaKind.MANGA, value.stats.mangaCards))
                if (value.stats.novelCards > 0) add(value.toMedia(MediaKind.NOVEL, value.stats.novelCards))
            }
        }
        val plans = expanded
            .groupBy { Triple(it.mediaKind, it.stats.profileId, it.stats.titleId) }
            .map { (key, titleValues) ->
                val (mediaKind, profileId, legacyTitleId) = key
                val titleSourceKey = legacyTitleSourceKey(mediaKind, legacyTitleId)
                val descriptor = LegacyTitleDescriptor(
                    sourceKey = titleSourceKey,
                    displayTitle = legacyTitleId?.let { "Legacy ${mediaKind.name.lowercase()} $it" }
                        ?: "Legacy ${mediaKind.name.lowercase()} cards",
                    profileId = profileId,
                )
                val aggregates = titleValues
                    .groupBy(LegacyAnkiMediaValue::date)
                    .map { (date, dateValues) ->
                        aggregate(
                            document = document,
                            descriptor = descriptor,
                            mediaKind = mediaKind,
                            profileId = profileId,
                            localDate = date,
                            activeDurationMillis = 0,
                            characters = 0,
                            cards = dateValues.sumOfExact { it.cards.toLong() },
                        )
                    }
                val groupHash = stableDigest("$mediaKind\u0000$profileId\u0000${legacyTitleId.orEmpty()}")
                val logical = document.copy(sourceKey = "${document.sourceKey}#group=$groupHash")
                planForSingleLogicalSource(logical, aggregates, emptyList())
            }
            .toMutableList()
        addParseIssuePlan(document, parsed.issues, plans)
        return plans.ifEmpty { listOf(planForSingleLogicalSource(document, emptyList(), parsed.issues)) }
    }

    private fun loadNovelDocuments(): List<LegacySourceDocument> {
        val booksDirectory = BookStorage.getBooksDirectory(application)
        return booksDirectory.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.sortedBy(File::getName)
            ?.mapNotNull { bookDirectory ->
                val file = File(bookDirectory, FileNames.STATISTICS)
                if (!file.isFile) return@mapNotNull null
                val metadata = BookStorage.loadMetadata(bookDirectory)
                val novelId = metadata?.id?.takeIf(String::isNotBlank) ?: bookDirectory.name
                val profile = dictionaryPreferences.profileResolver.resolve(novelId = novelId)
                LegacySourceDocument(
                    sourceKey = "novels/${bookDirectory.name}/${FileNames.STATISTICS}",
                    kind = LegacyImportSourceKind.NOVEL_JSON,
                    bytes = file.readBytes(),
                    novel = LegacyTitleDescriptor(
                        sourceKey = legacyTitleSourceKey(MediaKind.NOVEL, novelId),
                        displayTitle = metadata?.title?.takeIf(String::isNotBlank) ?: bookDirectory.name,
                        profileId = profile.id,
                        languageTag = metadata?.canonicalLanguageTag(),
                    ),
                )
            }
            ?.toList()
            .orEmpty()
    }

    private fun loadGlobalDocument(
        fileName: String,
        kind: LegacyImportSourceKind,
    ): LegacySourceDocument? {
        val file = File(application.filesDir, fileName)
        if (!file.isFile) return null
        return LegacySourceDocument(
            sourceKey = fileName,
            kind = kind,
            bytes = file.readBytes(),
        )
    }

    private suspend fun resolveManga(mangaId: Long): LegacyTitleDescriptor {
        val manga = mangaId.takeIf { it != 0L }?.let { getManga.await(it) }
        val source = manga?.source?.let(sourceManager::getOrStub)
        val language = source?.lang?.canonicalLanguageTag()
        val profileId = if (mangaId == 0L) {
            ""
        } else {
            dictionaryPreferences.profileResolver.resolve(
                mangaId = mangaId,
                sourceId = manga?.source ?: 0,
                sourceLang = source?.lang.orEmpty(),
            ).id
        }
        return LegacyTitleDescriptor(
            sourceKey = legacyTitleSourceKey(MediaKind.MANGA, mangaId.toString()),
            displayTitle = manga?.title?.takeIf(String::isNotBlank) ?: "Legacy manga $mangaId",
            profileId = profileId,
            languageTag = language,
        )
    }

    private fun planForSingleLogicalSource(
        document: LegacySourceDocument,
        aggregates: List<LegacyDailyAggregate>,
        issues: List<LegacyImportIssue>,
    ): LegacyImportPlan {
        val issueSummary = issues.toErrorSummary()
        return LegacyImportPlan(
            batch = LegacyImportBatch(
                identity = LegacyImportIdentity(
                    sourceKey = document.sourceKey,
                    sourceVersion = LEGACY_SOURCE_VERSION,
                    contentHash = document.contentHash,
                ),
                sourceKind = document.kind,
                aggregates = aggregates,
                failedCount = NonNegativeCounter(issues.size.toLong()),
                errorSummary = issueSummary,
                importedAtEpochMillis = clock.millis(),
            ),
            aggregates = aggregates,
            issues = issues,
        )
    }

    private fun addParseIssuePlan(
        document: LegacySourceDocument,
        issues: List<LegacyImportIssue>,
        plans: MutableList<LegacyImportPlan>,
    ) {
        if (issues.isNotEmpty() && plans.isNotEmpty()) {
            plans += planForSingleLogicalSource(
                document.copy(sourceKey = "${document.sourceKey}#parse-errors"),
                emptyList(),
                issues,
            )
        }
    }

    private fun aggregate(
        document: LegacySourceDocument,
        descriptor: LegacyTitleDescriptor,
        mediaKind: MediaKind,
        profileId: String,
        localDate: LocalDate,
        activeDurationMillis: Long,
        originalReadingTimeSeconds: Double? = null,
        characters: Long,
        cards: Long,
        completed: Boolean? = null,
        metadata: JsonObject? = null,
    ): LegacyDailyAggregate {
        val zoneId = zoneIdProvider()
        val anchor = localDate.atStartOfDay(zoneId)
        val titleId = stableTitleId(mediaKind, descriptor.sourceKey, profileId)
        return LegacyDailyAggregate(
            sessionId = stableSessionId(
                "${document.kind}\u0000${document.sourceKey}\u0000$mediaKind\u0000" +
                    "${descriptor.sourceKey}\u0000$profileId\u0000$localDate",
            ),
            titleId = titleId,
            titleSourceKey = descriptor.sourceKey,
            displayTitle = descriptor.displayTitle,
            mediaKind = mediaKind,
            profileId = profileId,
            languageTag = descriptor.languageTag,
            localDate = ImmersionLocalDate.from(localDate),
            startAnchorEpochMillis = anchor.toInstant().toEpochMilli(),
            startZoneId = zoneId.id,
            startOffsetSeconds = anchor.offset.totalSeconds,
            activeDuration = MillisecondDuration(activeDurationMillis),
            originalReadingTimeSeconds = originalReadingTimeSeconds,
            characters = NonNegativeCounter(characters),
            cardsTotal = NonNegativeCounter(cards),
            completed = completed,
            metadataJson = metadata?.toString(),
        )
    }

    private fun reconcile(
        expectedAggregates: List<LegacyDailyAggregate>,
        actualRows: List<LegacyAggregateRow>,
    ): LegacyStatsReconciliation {
        val expected = expectedAggregates
            .groupBy { ReconciliationKey(it.localDate, it.mediaKind, it.profileId, it.titleId) }
            .mapValues { (_, values) ->
                ReconciliationValue(
                    activeDurationMillis = values.sumOfExact { it.activeDuration.value },
                    characters = values.sumOfExact { it.characters.value },
                    cards = values.sumOfExact { it.cardsTotal.value },
                    records = values.size.toLong(),
                )
            }
        val actual = actualRows.associate {
            ReconciliationKey(it.localDate, it.mediaKind, it.profileId, it.titleId) to
                ReconciliationValue(
                    activeDurationMillis = it.activeDuration.value,
                    characters = it.characters.value,
                    cards = it.cardsTotal.value,
                    records = it.recordCount.value,
                )
        }
        val mismatches = expected.mapNotNull { (key, expectedValue) ->
            val actualValue = actual[key] ?: ReconciliationValue()
            if (expectedValue == actualValue) null else LegacyStatsReconciliationMismatch(key, expectedValue, actualValue)
        }
        return LegacyStatsReconciliation(
            checkedRows = expected.size,
            mismatches = mismatches,
        )
    }

    private inline fun <reified T> parseArray(document: LegacySourceDocument): ParsedLegacyDocument<T> {
        val root = try {
            PARSER_JSON.parseToJsonElement(document.bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (_: Exception) {
            return ParsedLegacyDocument(
                issues = mutableListOf(
                    LegacyImportIssue(document.sourceKey, null, LegacyImportIssueCode.CORRUPT_JSON),
                ),
            )
        }
        if (root !is JsonArray) {
            return ParsedLegacyDocument(
                issues = mutableListOf(
                    LegacyImportIssue(document.sourceKey, null, LegacyImportIssueCode.CORRUPT_JSON),
                ),
            )
        }
        val result = ParsedLegacyDocument<T>()
        root.forEachIndexed { index, element ->
            try {
                result.values += IndexedLegacyValue(index, PARSER_JSON.decodeFromJsonElement<T>(element))
            } catch (_: MissingFieldException) {
                result.issues += LegacyImportIssue(
                    document.sourceKey,
                    index,
                    LegacyImportIssueCode.MISSING_REQUIRED_FIELD,
                )
            } catch (_: SerializationException) {
                result.issues += LegacyImportIssue(
                    document.sourceKey,
                    index,
                    LegacyImportIssueCode.INVALID_VALUE,
                )
            } catch (_: IllegalArgumentException) {
                result.issues += LegacyImportIssue(
                    document.sourceKey,
                    index,
                    LegacyImportIssueCode.INVALID_VALUE,
                )
            }
        }
        return result
    }

    private fun Statistics.toNovelValue(
        document: LegacySourceDocument,
        index: Int,
        issues: MutableList<LegacyImportIssue>,
    ): LegacyNovelValue? {
        val date = parseDate(document, index, dateKey, issues) ?: return null
        if (!readingTime.isFinite() || readingTime < 0 || charactersRead < 0) {
            issues += LegacyImportIssue(document.sourceKey, index, LegacyImportIssueCode.INVALID_VALUE)
            return null
        }
        return LegacyNovelValue(date, this)
    }

    private fun MangaStats.toMangaValue(
        document: LegacySourceDocument,
        index: Int,
        issues: MutableList<LegacyImportIssue>,
    ): LegacyMangaValue? {
        val date = parseDate(document, index, dateKey, issues) ?: return null
        if (readingTime < 0 || charactersRead < 0 || mangaId < 0) {
            issues += LegacyImportIssue(document.sourceKey, index, LegacyImportIssueCode.INVALID_VALUE)
            return null
        }
        return LegacyMangaValue(date, this)
    }

    private fun AnkiStats.toAnkiValue(
        document: LegacySourceDocument,
        index: Int,
        issues: MutableList<LegacyImportIssue>,
    ): LegacyAnkiValue? {
        val date = parseDate(document, index, dateKey, issues) ?: return null
        if (mangaCards < 0 || novelCards < 0 || (profileId.isBlank() && profileId.isNotEmpty())) {
            issues += LegacyImportIssue(document.sourceKey, index, LegacyImportIssueCode.INVALID_VALUE)
            return null
        }
        return LegacyAnkiValue(date, this)
    }

    private fun parseDate(
        document: LegacySourceDocument,
        index: Int,
        dateKey: String,
        issues: MutableList<LegacyImportIssue>,
    ): LocalDate? =
        runCatching { LocalDate.parse(dateKey) }.getOrElse {
            issues += LegacyImportIssue(document.sourceKey, index, LegacyImportIssueCode.INVALID_DATE)
            null
        }

    private fun secondsToMillis(seconds: Double): Long {
        val millis = seconds * 1_000.0
        require(millis.isFinite() && millis <= Long.MAX_VALUE) { "Legacy duration is outside the supported range" }
        return millis.roundToLong()
    }

    private fun novelMetadata(values: List<Statistics>): JsonObject = buildJsonObject {
        put("minimumReadingSpeed", values.map { it.minReadingSpeed }.filter { it > 0 }.minOrNull())
        put("alternateMinimumReadingSpeed", values.map { it.altMinReadingSpeed }.filter { it > 0 }.minOrNull())
        put("lastReadingSpeed", values.maxByOrNull { it.lastStatisticModified }?.lastReadingSpeed)
        put("maximumReadingSpeed", values.maxOfOrNull { it.maxReadingSpeed })
        put("lastStatisticModified", values.maxOfOrNull { it.lastStatisticModified })
    }

    private fun BookMetadata.canonicalLanguageTag(): LanguageTag? = lang?.canonicalLanguageTag()

    private fun String.canonicalLanguageTag(): LanguageTag? =
        takeIf { it.isNotBlank() && it != "all" }?.let { runCatching { LanguageTag.from(it) }.getOrNull() }

    private fun legacyTitleSourceKey(mediaKind: MediaKind, legacyId: String?): String =
        "legacy:${mediaKind.name.lowercase()}:${legacyId?.takeIf(String::isNotBlank) ?: "global"}"

    private fun stableTitleId(
        mediaKind: MediaKind,
        sourceKey: String,
        profileId: String,
    ) = TitleId(stableUuid("legacy-title\u0000$mediaKind\u0000$sourceKey\u0000$profileId"))

    private fun stableSessionId(value: String) = SessionId(stableUuid("legacy-session\u0000$value"))

    private fun stableUuid(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun stableDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private inline fun <T> Iterable<T>.sumOfExact(selector: (T) -> Long): Long =
        fold(0L) { total, value -> Math.addExact(total, selector(value)) }

    private fun List<LegacyImportIssue>.toErrorSummary(): String? =
        groupingBy(LegacyImportIssue::code)
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(",") { (code, count) -> "${code.name}:$count" }
            .ifEmpty { null }

    companion object {
        private const val LEGACY_SOURCE_VERSION = 1

        private val PARSER_JSON = Json {
            ignoreUnknownKeys = true
        }
        private val REPORT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class LegacyStatsImportReport(
    val results: List<LegacyImportResult>,
    val issues: List<LegacyImportIssue>,
    val reconciliation: LegacyStatsReconciliation,
)

@Serializable
data class LegacyImportIssue(
    val sourceKey: String,
    val recordIndex: Int?,
    val code: LegacyImportIssueCode,
)

@Serializable
data class LegacyStatsReconciliation(
    val checkedRows: Int,
    val mismatches: List<LegacyStatsReconciliationMismatch>,
) {
    val isExact: Boolean
        get() = mismatches.isEmpty()
}

@Serializable
data class LegacyStatsReconciliationMismatch(
    val key: ReconciliationKey,
    val expected: ReconciliationValue,
    val actual: ReconciliationValue,
)

@Serializable
data class ReconciliationKey(
    val localDate: ImmersionLocalDate,
    val mediaKind: MediaKind,
    val profileId: String,
    val titleId: TitleId,
)

@Serializable
data class ReconciliationValue(
    val activeDurationMillis: Long = 0,
    val characters: Long = 0,
    val cards: Long = 0,
    val records: Long = 0,
)

internal data class LegacySourceDocument(
    val sourceKey: String,
    val kind: LegacyImportSourceKind,
    val bytes: ByteArray,
    val novel: LegacyTitleDescriptor? = null,
) {
    val contentHash: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

internal data class LegacyTitleDescriptor(
    val sourceKey: String,
    val displayTitle: String,
    val profileId: String,
    val languageTag: LanguageTag? = null,
)

internal data class LegacyImportPlan(
    val batch: LegacyImportBatch,
    val aggregates: List<LegacyDailyAggregate>,
    val issues: List<LegacyImportIssue>,
)

private data class ParsedLegacyDocument<T>(
    val values: MutableList<IndexedLegacyValue<T>> = mutableListOf(),
    val issues: MutableList<LegacyImportIssue> = mutableListOf(),
)

private data class IndexedLegacyValue<T>(
    val index: Int,
    val value: T,
)

private data class LegacyNovelValue(
    val date: LocalDate,
    val stats: Statistics,
)

private data class LegacyMangaValue(
    val date: LocalDate,
    val stats: MangaStats,
)

private data class LegacyAnkiValue(
    val date: LocalDate,
    val stats: AnkiStats,
) {
    fun toMedia(mediaKind: MediaKind, cards: Int) = LegacyAnkiMediaValue(date, stats, mediaKind, cards)
}

private data class LegacyAnkiMediaValue(
    val date: LocalDate,
    val stats: AnkiStats,
    val mediaKind: MediaKind,
    val cards: Int,
)
