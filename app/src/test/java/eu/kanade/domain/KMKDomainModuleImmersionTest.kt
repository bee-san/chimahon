package eu.kanade.domain

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the dependency-injection wiring for immersion statistics.
 *
 * The statistics screen and the six background jobs resolve their collaborators
 * through Injekt at runtime, so a type that is constructed but never registered
 * fails only when the user opens the screen -- not at compile time, and not in
 * any test that builds its subject directly. This test reads the module source
 * and asserts the registrations exist, which is cheap and catches the whole
 * subsystem going unregistered.
 */
class KMKDomainModuleImmersionTest {

    private val moduleSource: String by lazy {
        File("src/main/java/eu/kanade/domain/KMKDomainModule.kt").readText()
    }

    /**
     * Every type the statistics UI and jobs ask Injekt for, that this module owns.
     * `BasePreferences`, `SecurityPreferences`, `GetAnime` and `GetEpisode` are
     * deliberately absent: they belong to `PreferenceModule` and `DomainModule`.
     */
    private val requiredRegistrations = listOf(
        "ImmersionStatsPreferences",
        "ImmersionStatsDiagnosticsStore",
        "ImmersionShadowMonitor",
        "PreferenceAnkiOperationRepairStore",
        "SqlDelightImmersionRepository",
        "NoOpImmersionRecorderRepository",
        "ImmersionRecorderRepository",
        "ImmersionIndexRepository",
        "ImmersionLegacyImportRepository",
        "ImmersionStatsRepository",
        "ImmersionAnalyticsRepository",
        "ImmersionMaintenanceRepository",
        "ImmersionGoalRepository",
        "ImmersionAnkiRepository",
        "ImmersionAnalyticsService",
        "ImmersionExportService",
        "AnkiInventoryProvider",
        "AnkiInventorySynchronizer",
        "AnkiKnownnessResolver",
        "SourceTextNormalizer",
        "ImmersionIndexExclusionPolicy",
        "ImmersionIndexingEngine",
        "ImmersionReindexController",
        "ImmersionRecorder",
        "AnkiOperationRecorder",
        "ImmersionRecorderLifecycleCoordinator",
        "GetLegacyAggregateTotals",
        "LegacyStatsImporter",
    )

    @Test
    fun `every immersion collaborator is registered`() {
        val missing = requiredRegistrations.filterNot { type ->
            moduleSource.contains("addSingletonFactory<$type>") ||
                moduleSource.contains("addFactory<$type>") ||
                moduleSource.contains("addSingletonFactory { $type(") ||
                moduleSource.contains("addFactory { $type(") ||
                Regex("""add(?:Singleton)?Factory \{\s*$type\(""").containsMatchIn(moduleSource)
        }

        missing.shouldBeEmpty()
    }

    /**
     * Capture is gated in the module by wrapping the real repository, so no call
     * site can write to the database while the feature is off. If the recorder
     * repository is ever bound straight to the SQLDelight implementation, a
     * disabled feature starts persisting again.
     */
    @Test
    fun `the recorder repository stays behind the capture feature flag`() {
        val binding = moduleSource
            .substringAfter("addSingletonFactory<ImmersionRecorderRepository>")
            .substringBefore("addSingletonFactory<ImmersionIndexRepository>")

        check(binding.contains("FeatureFlaggedImmersionRecorderRepository")) {
            "ImmersionRecorderRepository must be bound through FeatureFlaggedImmersionRecorderRepository"
        }
        check(binding.contains("captureEnabled()")) {
            "The recorder repository binding must consult captureEnabled()"
        }
    }

    /**
     * Anki card repairs are replayed later, so the guard has to hold at replay
     * time too: incognito reading must not be attributed retroactively.
     */
    @Test
    fun `anki repairs respect capture and incognito`() {
        val binding = moduleSource
            .substringAfter("addSingletonFactory<AnkiOperationRecorder>")
            .substringBefore("addSingletonFactory {\n            ImmersionRecorderLifecycleCoordinator")

        check(binding.contains("captureEnabled()")) {
            "Anki repair must be gated on captureEnabled()"
        }
        check(binding.contains("incognitoMode()")) {
            "Anki repair must be gated on incognitoMode()"
        }
    }
}
