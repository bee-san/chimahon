package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@Serializable
enum class MediaKind {
    NOVEL,
    MANGA,
    VIDEO,
}

@Serializable
enum class CharacterMetric {
    GROSS,
    UNIQUE_SOURCE,
    NET_PROGRESS,
}

@Serializable
enum class SessionStatus {
    ACTIVE,
    COMPLETED,
    ABANDONED,
    DELETED,
}

@Serializable
enum class EventType {
    SESSION_STARTED,
    SESSION_FINALIZED,
    EXPOSURE,
    PROGRESS,
    PAUSED,
    RESUMED,
    IDLE,
    BACKGROUNDED,
    SEEK,
    SUBTITLE_MODE_CHANGED,
    SUBTITLE_TRACK_CHANGED,
    LOOKUP,
    ANKI_OPERATION,
    HEARTBEAT,
    UNIT_COMPLETED,
    TITLE_COMPLETED,
}

@Serializable
enum class SourceKind {
    NOVEL_RANGE,
    MANGA_PAGE,
    MANGA_OCR_BLOCK,
    SUBTITLE_CUE,
    VIDEO_OCR_REGION,
}

@Serializable
enum class AnkiOperationType {
    CREATE,
    UPDATE,
    DUPLICATE,
    OPEN,
    DELETE_OBSERVED,
}

@Serializable
enum class LookupStatus {
    SUCCESS,
    EMPTY,
    CANCELLED,
    FAILED,
}

@Serializable
enum class AnkiOperationStatus {
    SUCCESS,
    DUPLICATE,
    OPENED,
    FAILED,
    PERMISSION_DENIED,
    NOT_CONFIGURED,
}

@Serializable
enum class MaturityTier {
    UNKNOWN,
    NEW,
    LEARNING,
    YOUNG,
    MATURE,
    UNAVAILABLE,
    STALE,
}

@Serializable
enum class AnkiSnapshotStatus {
    COMPLETE,
    FAILED,
    CANCELLED,
    UNAVAILABLE,
}

@Serializable
enum class AnkiInventoryFailure {
    DISABLED,
    NOT_INSTALLED,
    PERMISSION_DENIED,
    UNSUPPORTED_PROVIDER,
    MISCONFIGURED_FIELDS,
    PARTIAL_RESULT,
    PROVIDER_ERROR,
}

@Serializable
enum class AnkiMatchConfidence {
    READING_AWARE,
    HEADWORD_ONLY,
    AMBIGUOUS,
}

@Serializable
enum class AnkiMaturityAggregation {
    MAX_INTERVAL,
    MIN_INTERVAL,
}

@Serializable
enum class CapabilityState {
    AVAILABLE,
    PARTIAL,
    UNAVAILABLE,
    STALE,
}

@Serializable
enum class ProvenanceState {
    AVAILABLE,
    PARTIAL,
    REMOVED,
    UNAVAILABLE,
    LEGACY_AGGREGATE,
}

@Serializable
enum class MetricQuality {
    EVENT_BACKED,
    LEGACY_AMBIGUOUS,
}

@Serializable
enum class RawTextRetention {
    NEVER,
    THIRTY_DAYS,
    ONE_YEAR,
    UNTIL_DELETED,
}

@Serializable
enum class NovelNetProgressPolicy {
    SIGNED_POSITION_DELTA,
    CLAMP_PER_SECTION,
}

@JvmInline
@Serializable
value class SessionId(val value: String) {
    init {
        requireCanonicalUuid(value, "session ID")
    }
}

@JvmInline
@Serializable
value class EventId(val value: String) {
    init {
        requireCanonicalUuid(value, "event ID")
    }
}

@JvmInline
@Serializable
value class TitleId(val value: String) {
    init {
        requireCanonicalUuid(value, "title ID")
    }
}

@JvmInline
@Serializable
value class SourceUnitId(val value: String) {
    init {
        requireCanonicalUuid(value, "source unit ID")
    }
}

@JvmInline
@Serializable
value class AnkiOperationId(val value: String) {
    init {
        requireCanonicalUuid(value, "Anki operation ID")
    }
}

@JvmInline
@Serializable
value class LanguageTag(val value: String) {
    init {
        val canonical = Locale.forLanguageTag(value).toLanguageTag()
        require(value.isNotBlank() && '_' !in value && canonical == value) {
            "Language tag must be a canonical BCP 47 tag"
        }
    }

    companion object {
        fun from(value: String): LanguageTag {
            val canonical = Locale.forLanguageTag(value.replace('_', '-')).toLanguageTag()
            require(canonical != "und" || value.equals("und", ignoreCase = true)) {
                "Language tag must be a valid BCP 47 tag"
            }
            return LanguageTag(canonical)
        }
    }
}

@JvmInline
@Serializable
value class UnicodeCodePoint(val value: Int) {
    init {
        require(Character.isValidCodePoint(value) && value !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
            "Code point must be a valid Unicode scalar value"
        }
    }

    fun asString(): String = String(Character.toChars(value))
}

@JvmInline
@Serializable
value class ImmersionLocalDate(val epochDay: Long) : Comparable<ImmersionLocalDate> {
    init {
        runCatching { LocalDate.ofEpochDay(epochDay) }.getOrElse {
            throw IllegalArgumentException("Local date epoch day is out of range", it)
        }
    }

    fun toLocalDate(): LocalDate = LocalDate.ofEpochDay(epochDay)

    override fun compareTo(other: ImmersionLocalDate): Int = epochDay.compareTo(other.epochDay)

    override fun toString(): String = toLocalDate().toString()

    companion object {
        fun from(date: LocalDate): ImmersionLocalDate = ImmersionLocalDate(date.toEpochDay())

        fun parse(value: String): ImmersionLocalDate = from(LocalDate.parse(value))
    }
}

@JvmInline
@Serializable
value class MillisecondDuration(val value: Long) {
    init {
        require(value >= 0) { "Duration cannot be negative" }
    }

    operator fun plus(other: MillisecondDuration): MillisecondDuration =
        MillisecondDuration(Math.addExact(value, other.value))
}

@JvmInline
@Serializable
value class NonNegativeCounter(val value: Long) {
    init {
        require(value >= 0) { "Counter cannot be negative" }
    }

    operator fun plus(other: NonNegativeCounter): NonNegativeCounter =
        NonNegativeCounter(Math.addExact(value, other.value))

    companion object {
        val ZERO = NonNegativeCounter(0)
    }
}

@JvmInline
@Serializable
value class NetCharacterProgress(val value: Long) {
    operator fun plus(other: NetCharacterProgress): NetCharacterProgress =
        NetCharacterProgress(Math.addExact(value, other.value))

    companion object {
        val ZERO = NetCharacterProgress(0)
    }
}

@Serializable
data class LocalDateRange(
    override val start: ImmersionLocalDate,
    override val endInclusive: ImmersionLocalDate,
) : ClosedRange<ImmersionLocalDate> {
    init {
        require(start <= endInclusive) { "Date range start must not be after its end" }
    }
}

private fun requireCanonicalUuid(value: String, label: String) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) {
        "$label must be a canonical UUID"
    }
}
