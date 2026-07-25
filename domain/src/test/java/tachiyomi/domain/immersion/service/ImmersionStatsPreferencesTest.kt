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
        preferences.rawTextRetention().get() shouldBe RawTextRetention.UNTIL_DELETED
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
}
