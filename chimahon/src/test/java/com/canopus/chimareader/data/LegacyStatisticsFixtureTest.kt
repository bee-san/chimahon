// SPDX-License-Identifier: MIT

package com.canopus.chimareader.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class LegacyStatisticsFixtureTest {
    @Test
    fun `novel fixture preserves current interpreted totals`() {
        val statistics = decode<List<Statistics>>("novel-normal.json")

        statistics.size shouldBe 2
        statistics.sumOf { it.charactersRead } shouldBe 2_000
        statistics.sumOf { it.readingTime } shouldBe 900.5
        statistics.last().completedBook shouldBe 1
    }

    @Test
    fun `manga fixture preserves millisecond time and title identity`() {
        val statistics = decode<List<MangaStats>>("manga-normal.json")

        statistics.sumOf { it.charactersRead } shouldBe 850
        statistics.sumOf { it.readingTime } shouldBe 210_000L
        statistics.map { it.mangaId }.distinct() shouldBe listOf(42L)
    }

    @Test
    fun `Anki fixture preserves the existing combined card semantics`() {
        val statistics = decode<List<AnkiStats>>("anki-normal.json")

        statistics.sumOf { it.mangaCards + it.novelCards } shouldBe 10
        statistics.first().profileId shouldBe "default"
        statistics.first().titleId shouldBe "fixture-title"
    }

    @Test
    fun `older minimal shapes retain model defaults`() {
        decode<List<Statistics>>("novel-older-minimal.json").single() shouldBe Statistics(
            title = "Older Fixture Novel",
            dateKey = "2024-01-02",
        )
        decode<List<MangaStats>>("manga-older-minimal.json").single() shouldBe MangaStats(
            dateKey = "2024-01-02",
        )
        decode<List<AnkiStats>>("anki-older-minimal.json").single() shouldBe AnkiStats(
            dateKey = "2024-01-02",
        )
    }

    @Test
    fun `missing required and corrupt fixtures remain parse failures`() {
        shouldThrow<SerializationException> {
            decode<List<Statistics>>("novel-missing-required.json")
        }
        shouldThrow<SerializationException> {
            decode<List<Statistics>>("novel-corrupt.json")
        }
        shouldThrow<SerializationException> {
            decode<List<MangaStats>>("manga-missing-required.json")
        }
        shouldThrow<SerializationException> {
            decode<List<MangaStats>>("manga-corrupt.json")
        }
        shouldThrow<SerializationException> {
            decode<List<AnkiStats>>("anki-missing-required.json")
        }
        shouldThrow<SerializationException> {
            decode<List<AnkiStats>>("anki-corrupt.json")
        }
    }

    private inline fun <reified T> decode(name: String): T {
        val path = "immersion/legacy/$name"
        val contents = checkNotNull(javaClass.classLoader?.getResource(path)) {
            "Missing fixture $path"
        }.readText()
        return Json.decodeFromString(contents)
    }
}
