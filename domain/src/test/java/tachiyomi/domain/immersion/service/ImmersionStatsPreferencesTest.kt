package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverageFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTitleStateFilter
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.NovelNetProgressPolicy
import tachiyomi.domain.immersion.model.RawTextRetention

class ImmersionStatsPreferencesTest {
    private val preferences = ImmersionStatsPreferences(InMemoryPreferenceStore())

    @Test
    fun `shadow rollout defaults capture safely while keeping user surfaces opt in`() {
        preferences.captureEnabled().get() shouldBe true
        preferences.indexingEnabled().get() shouldBe true
        preferences.uiEnabled().get() shouldBe false
        preferences.ankiSyncEnabled().get() shouldBe false
        preferences.goalsEnabled().get() shouldBe false
        preferences.goalRemindersEnabled().get() shouldBe false
        preferences.legacyWritesEnabled().get() shouldBe true
        preferences.includeLegacyAggregates().get() shouldBe true
    }

    @Test
    fun `initial lifecycle and retention decisions are encoded`() {
        preferences.readerIdleTimeoutSeconds().get() shouldBe 120
        preferences.videoBufferingGraceSeconds().get() shouldBe 5
        preferences.rawTextRetention().get() shouldBe RawTextRetention.NEVER
        preferences.rawTextDisclosureRequired() shouldBe true
        preferences.effectiveRawTextRetention() shouldBe RawTextRetention.NEVER
        preferences.novelNetProgressPolicy().get() shouldBe NovelNetProgressPolicy.SIGNED_POSITION_DELTA
    }

    @Test
    fun `shadow rollout migration applies rollback-safe flags exactly once`() {
        preferences.captureEnabled().set(false)
        preferences.legacyWritesEnabled().set(false)

        preferences.applyReleaseRolloutDefaults()

        preferences.captureEnabled().get() shouldBe true
        preferences.legacyWritesEnabled().get() shouldBe true
        preferences.uiEnabled().get() shouldBe false
        preferences.uiEnabled().set(true)
        preferences.applyReleaseRolloutDefaults()
        preferences.uiEnabled().get() shouldBe true
    }

    @Test
    fun `rollout v3 moves the prior preview defaults back to shadow posture`() {
        val previousPreview = preferencesAtRollout(version = 2)
        previousPreview.uiEnabled().set(true)
        previousPreview.ankiSyncEnabled().set(true)
        previousPreview.goalsEnabled().set(true)
        previousPreview.goalRemindersEnabled().set(true)
        previousPreview.legacyWritesEnabled().set(false)

        previousPreview.applyReleaseRolloutDefaults()

        previousPreview.uiEnabled().get() shouldBe false
        previousPreview.ankiSyncEnabled().get() shouldBe false
        previousPreview.goalsEnabled().get() shouldBe false
        previousPreview.goalRemindersEnabled().get() shouldBe false
        previousPreview.legacyWritesEnabled().get() shouldBe true
    }

    @Test
    fun `rollout v2 preserves an explicit retention choice and acknowledges disclosure`() {
        val preferences = preferencesAtRollout(
            version = 1,
            retention = RawTextRetention.ONE_YEAR,
        )

        preferences.applyReleaseRolloutDefaults()

        preferences.rawTextRetention().get() shouldBe RawTextRetention.ONE_YEAR
        preferences.rawTextDisclosureRequired() shouldBe false
        preferences.effectiveRawTextRetention() shouldBe RawTextRetention.ONE_YEAR
    }

    @Test
    fun `rollout v2 leaves unset retention private and requiring disclosure`() {
        val preferences = preferencesAtRollout(version = 1)

        preferences.applyReleaseRolloutDefaults()

        preferences.rawTextRetention().isSet() shouldBe false
        preferences.rawTextRetention().get() shouldBe RawTextRetention.NEVER
        preferences.rawTextDisclosureRequired() shouldBe true
        preferences.effectiveRawTextRetention() shouldBe RawTextRetention.NEVER
    }

    @Test
    fun `acknowledging disclosure applies the selected retention`() {
        preferences.acknowledgeRawTextDisclosure(RawTextRetention.THIRTY_DAYS)

        preferences.rawTextRetention().get() shouldBe RawTextRetention.THIRTY_DAYS
        preferences.rawTextDisclosureRequired() shouldBe false
        preferences.effectiveRawTextRetention() shouldBe RawTextRetention.THIRTY_DAYS
    }

    @Test
    fun `dashboard navigation context survives preference recreation`() {
        val store = PersistentStatsPreferenceStore()
        val first = ImmersionStatsPreferences(store)
        first.dashboardRangePreset().set("CUSTOM")
        first.dashboardPeriodOffset().set(-2)
        first.dashboardCustomStart().set("2026-07-01")
        first.dashboardCustomEnd().set("2026-07-15")
        first.dashboardMediaKind().set("MANGA")
        first.dashboardProfileId().set("japanese")
        first.dashboardTitleId().set("00000000-0000-0000-0000-000000000001")
        first.dashboardCharacterMetric().set(CharacterMetric.UNIQUE_SOURCE)
        first.dashboardIncludeRereads().set(false)
        first.dashboardMaturityTiers().set(setOf("LEARNING", "MATURE"))
        first.dashboardProvenanceStates().set(setOf("AVAILABLE", "PARTIAL"))
        first.dashboardSelectedTab().set("CHARACTERS")
        first.dashboardTrendScale().set(AnalyticsBucketScale.WEEK)
        first.dashboardTrendMetric().set("ACTIVE_TIME")
        first.dashboardTitleSort().set(AnalyticsTitleSort.READING_SPEED)
        first.dashboardTitleState().set(AnalyticsTitleStateFilter.IN_PROGRESS)
        first.dashboardTitleCoverage().set(AnalyticsTitleCoverageFilter.PARTIAL)

        val restored = ImmersionStatsPreferences(store)
        restored.dashboardRangePreset().get() shouldBe "CUSTOM"
        restored.dashboardPeriodOffset().get() shouldBe -2
        restored.dashboardCustomStart().get() shouldBe "2026-07-01"
        restored.dashboardCustomEnd().get() shouldBe "2026-07-15"
        restored.dashboardMediaKind().get() shouldBe "MANGA"
        restored.dashboardProfileId().get() shouldBe "japanese"
        restored.dashboardTitleId().get() shouldBe "00000000-0000-0000-0000-000000000001"
        restored.dashboardCharacterMetric().get() shouldBe CharacterMetric.UNIQUE_SOURCE
        restored.dashboardIncludeRereads().get() shouldBe false
        restored.dashboardMaturityTiers().get() shouldBe setOf("LEARNING", "MATURE")
        restored.dashboardProvenanceStates().get() shouldBe setOf("AVAILABLE", "PARTIAL")
        restored.dashboardSelectedTab().get() shouldBe "CHARACTERS"
        restored.dashboardTrendScale().get() shouldBe AnalyticsBucketScale.WEEK
        restored.dashboardTrendMetric().get() shouldBe "ACTIVE_TIME"
        restored.dashboardTitleSort().get() shouldBe AnalyticsTitleSort.READING_SPEED
        restored.dashboardTitleState().get() shouldBe AnalyticsTitleStateFilter.IN_PROGRESS
        restored.dashboardTitleCoverage().get() shouldBe AnalyticsTitleCoverageFilter.PARTIAL
    }

    private fun preferencesAtRollout(
        version: Int,
        retention: RawTextRetention? = null,
    ): ImmersionStatsPreferences {
        val initialPreferences = buildList<InMemoryPreferenceStore.InMemoryPreference<*>> {
            add(
                InMemoryPreferenceStore.InMemoryPreference(
                    ImmersionStatsPreferences.ROLLOUT_VERSION,
                    version,
                    0,
                ),
            )
            retention?.let {
                add(
                    InMemoryPreferenceStore.InMemoryPreference(
                        ImmersionStatsPreferences.RAW_TEXT_RETENTION,
                        it,
                        RawTextRetention.NEVER,
                    ),
                )
            }
        }
        return ImmersionStatsPreferences(
            InMemoryPreferenceStore(initialPreferences.asSequence()),
        )
    }

    private class PersistentStatsPreferenceStore : PreferenceStore {
        private val preferences = mutableMapOf<String, InMemoryPreferenceStore.InMemoryPreference<*>>()

        override fun getString(key: String, defaultValue: String): Preference<String> =
            preference(key, defaultValue)

        override fun getLong(key: String, defaultValue: Long): Preference<Long> =
            preference(key, defaultValue)

        override fun getInt(key: String, defaultValue: Int): Preference<Int> =
            preference(key, defaultValue)

        override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
            preference(key, defaultValue)

        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
            preference(key, defaultValue)

        override fun getStringSet(
            key: String,
            defaultValue: Set<String>,
        ): Preference<Set<String>> = preference(key, defaultValue)

        override fun <T> getObjectFromString(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): Preference<T> = preference(key, defaultValue)

        override fun <T> getObjectFromInt(
            key: String,
            defaultValue: T,
            serializer: (T) -> Int,
            deserializer: (Int) -> T,
        ): Preference<T> = preference(key, defaultValue)

        override fun getAll(): Map<String, *> = preferences.mapValues { it.value.get() }

        @Suppress("UNCHECKED_CAST")
        private fun <T> preference(key: String, defaultValue: T): Preference<T> =
            preferences.getOrPut(key) {
                InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            } as Preference<T>
    }
}
