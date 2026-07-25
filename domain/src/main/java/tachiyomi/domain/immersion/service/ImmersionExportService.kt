// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository

class ImmersionExportService(
    private val analytics: ImmersionAnalyticsService,
    private val maintenance: ImmersionMaintenanceRepository,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    },
) {
    suspend fun aggregateJson(
        filter: StatsFilter,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
    ): ImmersionExportDocument {
        val overview = analytics.overview(filter).value
        val trends = analytics.trends(filter, AnalyticsBucketScale.DAY).value
        val titles = analytics.titles(filter, AnalyticsSort.MOST_TIME).value
        val goals = analytics.goals(filter).value
        val anki = analytics.anki(filter).value
        val payload = ImmersionAggregateExport(
            createdAtEpochMillis = createdAtEpochMillis,
            filter = filter,
            overview = overview,
            trends = trends,
            titles = titles,
            goals = goals,
            anki = anki,
        )
        return document("chimahon-stats-aggregate.json", JSON_MIME_TYPE, json.encodeToString(payload))
    }

    suspend fun aggregateCsv(
        filter: StatsFilter,
    ): ImmersionExportDocument {
        val trends = analytics.trends(filter, AnalyticsBucketScale.DAY).value
        val rows = buildList {
            add(
                listOf(
                    "schema_version",
                    "period_start",
                    "period_end",
                    "active_duration_ms",
                    "gross_characters",
                    "unique_source_characters",
                    "net_characters",
                    "distinct_characters",
                    "new_characters",
                    "words_encountered",
                    "unique_words",
                    "new_words",
                    "source_units",
                    "sessions",
                    "successful_lookups",
                    "cards_created",
                    "cards_updated",
                ),
            )
            trends.points.forEach { point ->
                val metrics = point.metrics
                add(
                    listOf(
                        EXPORT_SCHEMA_VERSION.toString(),
                        point.range.start.toString(),
                        point.range.endInclusive.toString(),
                        metrics.activeTime.value.toString(),
                        metrics.characters.gross.value.toString(),
                        metrics.characters.uniqueSource.value.toString(),
                        metrics.characters.netProgress.value.toString(),
                        metrics.distinctCharacters.value.toString(),
                        metrics.newCharacters.value.toString(),
                        metrics.wordsEncountered.value.toString(),
                        metrics.uniqueWords.value.toString(),
                        metrics.newWords.value.toString(),
                        metrics.sourceUnits.value.toString(),
                        metrics.sessions.value.toString(),
                        metrics.successfulLookups.value.toString(),
                        metrics.cardsCreated.value.toString(),
                        metrics.cardsUpdated.value.toString(),
                    ),
                )
            }
        }
        return document("chimahon-stats-aggregate.csv", CSV_MIME_TYPE, rows.toCsv())
    }

    suspend fun eventJson(
        includeRawText: Boolean = false,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
    ): ImmersionExportDocument {
        val archive = maintenance.exportPortableArchive(includeRawText, createdAtEpochMillis)
        return document("chimahon-stats-events.json", JSON_MIME_TYPE, json.encodeToString(archive))
    }

    suspend fun vocabularyCsv(filter: StatsFilter): ImmersionExportDocument {
        val rows = mutableListOf(
            listOf(
                "schema_version",
                "id",
                "language",
                "headword",
                "reading",
                "part_of_speech",
                "occurrences",
                "titles",
                "first_seen_at_ms",
                "last_seen_at_ms",
                "frequency_rank",
                "maturity",
                "match_confidence",
            ),
        )
        var offset = 0L
        do {
            val page = analytics.vocabulary(
                filter = filter,
                sort = AnalyticsSort.MOST_OCCURRENCES,
                offset = offset,
                limit = EXPORT_PAGE_SIZE,
            ).value
            page.items.forEach { word ->
                rows += listOf(
                    EXPORT_SCHEMA_VERSION.toString(),
                    word.id,
                    word.languageTag.value,
                    word.headword,
                    word.reading.orEmpty(),
                    word.partOfSpeech.orEmpty(),
                    word.occurrenceCount.toString(),
                    word.titleCount.toString(),
                    word.firstSeenAtEpochMillis.toString(),
                    word.lastSeenAtEpochMillis.toString(),
                    word.frequencyRank?.toString().orEmpty(),
                    word.maturity.name,
                    word.matchConfidence?.name.orEmpty(),
                )
            }
            offset = page.nextOffset ?: break
        } while (true)
        return document("chimahon-stats-vocabulary.csv", CSV_MIME_TYPE, rows.toCsv())
    }

    suspend fun charactersCsv(filter: StatsFilter): ImmersionExportDocument {
        val rows = mutableListOf(
            listOf(
                "schema_version",
                "code_point",
                "character",
                "unicode_name",
                "unicode_script",
                "occurrences",
                "words",
                "titles",
                "first_seen_at_ms",
                "last_seen_at_ms",
                "frequency_rank",
                "maturity",
            ),
        )
        var offset = 0L
        do {
            val page = analytics.characters(
                filter = filter,
                sort = AnalyticsSort.MOST_OCCURRENCES,
                offset = offset,
                limit = EXPORT_PAGE_SIZE,
            ).value
            page.items.forEach { character ->
                rows += listOf(
                    EXPORT_SCHEMA_VERSION.toString(),
                    character.codePoint.value.toString(),
                    character.rendered,
                    character.unicodeName.orEmpty(),
                    character.unicodeScript,
                    character.occurrenceCount.toString(),
                    character.wordCount.toString(),
                    character.titleCount.toString(),
                    character.firstSeenAtEpochMillis.toString(),
                    character.lastSeenAtEpochMillis.toString(),
                    character.frequencyRank?.toString().orEmpty(),
                    character.maturity.name,
                )
            }
            offset = page.nextOffset ?: break
        } while (true)
        return document("chimahon-stats-characters.csv", CSV_MIME_TYPE, rows.toCsv())
    }

    private fun document(
        fileName: String,
        mimeType: String,
        content: String,
    ) = ImmersionExportDocument(fileName, mimeType, content.encodeToByteArray())

    companion object {
        const val EXPORT_SCHEMA_VERSION = 1
        const val CSV_MIME_TYPE = "text/csv"
        const val JSON_MIME_TYPE = "application/json"
        private const val EXPORT_PAGE_SIZE = 500
    }
}

@Serializable
data class ImmersionAggregateExport(
    val schemaVersion: Int = ImmersionExportService.EXPORT_SCHEMA_VERSION,
    val metricDefinitions: Map<String, String> = IMMERSION_EXPORT_METRIC_DEFINITIONS,
    val createdAtEpochMillis: Long,
    val filter: StatsFilter,
    val overview: AnalyticsOverview,
    val trends: AnalyticsTrends,
    val titles: List<AnalyticsTitleRow>,
    val goals: List<AnalyticsGoalProgress>,
    val anki: AnalyticsAnkiSummary,
)

data class ImmersionExportDocument(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

internal fun List<List<String>>.toCsv(): String =
    joinToString(separator = "\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { value ->
            "\"${value.spreadsheetSafe().replace("\"", "\"\"")}\""
        }
    }

internal fun String.spreadsheetSafe(): String =
    if (firstOrNull() in CSV_FORMULA_PREFIXES) "'$this" else this

private val CSV_FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')

private val IMMERSION_EXPORT_METRIC_DEFINITIONS = mapOf(
    "gross_characters" to "Countable Unicode code points shown during qualifying exposure, including rereads.",
    "unique_source_characters" to "Countable characters from source units first seen in the selected scope.",
    "net_characters" to "Signed forward source progress after backward navigation.",
    "active_duration_ms" to "Foreground consumption time excluding paused, idle, buffered, and background time.",
    "reading_speed" to "Gross characters divided by active hours unless a different character metric is explicit.",
)
