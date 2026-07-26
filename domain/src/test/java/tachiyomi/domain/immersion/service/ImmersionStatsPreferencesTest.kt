// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.immersion.model.NovelNetProgressPolicy
import tachiyomi.domain.immersion.model.RawTextRetention

class ImmersionStatsPreferencesTest {
    private val preferences = ImmersionStatsPreferences(InMemoryPreferenceStore())

    @Test
    fun `release rollout defaults to event backed stats and read only legacy stores`() {
        preferences.captureEnabled().get() shouldBe true
        preferences.indexingEnabled().get() shouldBe true
        preferences.uiEnabled().get() shouldBe true
        preferences.ankiSyncEnabled().get() shouldBe true
        preferences.goalsEnabled().get() shouldBe true
        preferences.legacyWritesEnabled().get() shouldBe false
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
    fun `release rollout migration promotes preview flags exactly once`() {
        preferences.captureEnabled().set(false)
        preferences.legacyWritesEnabled().set(true)

        preferences.applyReleaseRolloutDefaults()

        preferences.captureEnabled().get() shouldBe true
        preferences.legacyWritesEnabled().get() shouldBe false
        preferences.uiEnabled().set(false)
        preferences.applyReleaseRolloutDefaults()
        preferences.uiEnabled().get() shouldBe false
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
}
