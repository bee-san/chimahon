// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ImmersionTypesTest {
    @Test
    fun `validated IDs accept canonical UUIDs and reject other values`() {
        val uuid = UUID.randomUUID().toString()

        SessionId(uuid).value shouldBe uuid
        EventId(uuid).value shouldBe uuid
        TitleId(uuid).value shouldBe uuid
        SourceUnitId(uuid).value shouldBe uuid
        AnkiOperationId(uuid).value shouldBe uuid

        shouldThrow<IllegalArgumentException> { SessionId("not-a-uuid") }
        shouldThrow<IllegalArgumentException> { EventId(uuid.uppercase()) }
    }

    @Test
    fun `language tags are canonicalized and validated`() {
        LanguageTag.from("ja").value shouldBe "ja"
        LanguageTag.from("en_us").value shouldBe "en-US"
        LanguageTag.from("zh-hant-tw").value shouldBe "zh-Hant-TW"

        shouldThrow<IllegalArgumentException> { LanguageTag("en_us") }
        shouldThrow<IllegalArgumentException> { LanguageTag.from("not a tag") }
    }

    @Test
    fun `code points reject surrogate and out of range values`() {
        UnicodeCodePoint(0x20000).asString() shouldBe "\uD840\uDC00"

        shouldThrow<IllegalArgumentException> { UnicodeCodePoint(0xD800) }
        shouldThrow<IllegalArgumentException> { UnicodeCodePoint(0x110000) }
    }

    @Test
    fun `non-negative values reject negative inputs and checked addition rejects overflow`() {
        shouldThrow<IllegalArgumentException> { NonNegativeCounter(-1) }
        shouldThrow<IllegalArgumentException> { MillisecondDuration(-1) }
        shouldThrow<ArithmeticException> {
            NonNegativeCounter(Long.MAX_VALUE) + NonNegativeCounter(1)
        }
        shouldThrow<ArithmeticException> {
            MillisecondDuration(Long.MAX_VALUE) + MillisecondDuration(1)
        }
        shouldThrow<ArithmeticException> {
            NetCharacterProgress(Long.MAX_VALUE) + NetCharacterProgress(1)
        }
    }

    @Test
    fun `local date range validates order and filter is serializable`() {
        val start = ImmersionLocalDate.from(LocalDate.parse("2026-07-01"))
        val end = ImmersionLocalDate.from(LocalDate.parse("2026-07-25"))
        val filter = StatsFilter(
            dateRange = LocalDateRange(start, end),
            mediaKinds = setOf(MediaKind.NOVEL, MediaKind.VIDEO),
            languageTags = setOf(LanguageTag.from("ja")),
            titleIds = setOf(TitleId("123e4567-e89b-12d3-a456-426614174000")),
            characterMetric = CharacterMetric.UNIQUE_SOURCE,
        )

        val encoded = Json.encodeToString(filter)
        Json.decodeFromString<StatsFilter>(encoded) shouldBe filter
        shouldThrow<IllegalArgumentException> { ImmersionLocalDate(Long.MAX_VALUE) }
        shouldThrow<IllegalArgumentException> { LocalDateRange(end, start) }
        shouldThrow<IllegalArgumentException> { StatsFilter(profileIds = setOf("")) }
    }
}
