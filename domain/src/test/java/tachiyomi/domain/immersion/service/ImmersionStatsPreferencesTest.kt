package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.immersion.model.NovelNetProgressPolicy
import tachiyomi.domain.immersion.model.RawTextRetention

class ImmersionStatsPreferencesTest {
    private val preferences = ImmersionStatsPreferences(InMemoryPreferenceStore())

    @Test
    fun `rollout flags default off`() {
        preferences.captureEnabled().get() shouldBe false
        preferences.indexingEnabled().get() shouldBe false
        preferences.uiEnabled().get() shouldBe false
        preferences.ankiSyncEnabled().get() shouldBe false
        preferences.goalsEnabled().get() shouldBe false
        preferences.includeLegacyAggregates().get() shouldBe true
    }

    @Test
    fun `initial lifecycle and retention decisions are encoded`() {
        preferences.readerIdleTimeoutSeconds().get() shouldBe 120
        preferences.videoBufferingGraceSeconds().get() shouldBe 5
        preferences.rawTextRetention().get() shouldBe RawTextRetention.UNTIL_DELETED
        preferences.novelNetProgressPolicy().get() shouldBe NovelNetProgressPolicy.SIGNED_POSITION_DELTA
    }
}
