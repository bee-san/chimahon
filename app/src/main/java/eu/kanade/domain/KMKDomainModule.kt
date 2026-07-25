package eu.kanade.domain

import chimahon.anki.AnkiDroidInventoryProvider
import mihon.feature.stats.indexing.DictionaryBackedJapaneseTokenizer
import mihon.feature.stats.indexing.ImmersionIndexJob
import mihon.feature.stats.indexing.SqlImmersionIndexExclusionPolicy
import mihon.feature.stats.legacy.LegacyStatsImporter
import mihon.feature.stats.recorder.ImmersionRecorderLifecycleCoordinator
import tachiyomi.data.immersion.SqlDelightImmersionRepository
import tachiyomi.data.libraryUpdateError.LibraryUpdateErrorRepositoryImpl
import tachiyomi.data.libraryUpdateError.LibraryUpdateErrorWithRelationsRepositoryImpl
import tachiyomi.data.libraryUpdateErrorMessage.LibraryUpdateErrorMessageRepositoryImpl
import tachiyomi.domain.immersion.interactor.GetLegacyAggregateTotals
import tachiyomi.domain.immersion.model.AnkiOperationEvent
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.repository.FeatureFlaggedImmersionRecorderRepository
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import tachiyomi.domain.immersion.repository.ImmersionIndexRepository
import tachiyomi.domain.immersion.repository.ImmersionLegacyImportRepository
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.repository.ImmersionRecorderRepository
import tachiyomi.domain.immersion.repository.ImmersionStatsRepository
import tachiyomi.domain.immersion.repository.NoOpImmersionRecorderRepository
import tachiyomi.domain.immersion.service.AnkiInventoryProvider
import tachiyomi.domain.immersion.service.AnkiInventorySynchronizer
import tachiyomi.domain.immersion.service.AnkiKnownnessResolver
import tachiyomi.domain.immersion.service.AnkiOperationRecorder
import tachiyomi.domain.immersion.service.AnkiOperationRepairWriter
import tachiyomi.domain.immersion.service.BoundaryImmersionTokenizer
import tachiyomi.domain.immersion.service.DefaultAnkiOperationRecorder
import tachiyomi.domain.immersion.service.DefaultImmersionRecorder
import tachiyomi.domain.immersion.service.DefaultLookupTelemetry
import tachiyomi.domain.immersion.service.DefaultSourceTextNormalizer
import tachiyomi.domain.immersion.service.ImmersionDeviceIdProvider
import tachiyomi.domain.immersion.service.ImmersionEventPersistenceObserver
import tachiyomi.domain.immersion.service.ImmersionIndexExclusionPolicy
import tachiyomi.domain.immersion.service.ImmersionIndexingEngine
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionRecorderConfiguration
import tachiyomi.domain.immersion.service.ImmersionReindexController
import tachiyomi.domain.immersion.service.ImmersionShadowMonitor
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.domain.immersion.service.LookupTelemetry
import tachiyomi.domain.immersion.service.PreferenceAnkiOperationRepairStore
import tachiyomi.domain.immersion.service.SourceTextNormalizer
import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.repository.LibraryUpdateErrorRepository
import tachiyomi.domain.libraryUpdateError.repository.LibraryUpdateErrorWithRelationsRepository
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.DeleteLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.GetLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.repository.LibraryUpdateErrorMessageRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class KMKDomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { ImmersionStatsPreferences(get()) }
        addSingletonFactory { ImmersionStatsDiagnosticsStore() }
        addSingletonFactory { ImmersionShadowMonitor() }
        addSingletonFactory { SqlDelightImmersionRepository(get()) }
        addSingletonFactory { NoOpImmersionRecorderRepository() }
        addSingletonFactory<ImmersionRecorderRepository> {
            val preferences = get<ImmersionStatsPreferences>()
            FeatureFlaggedImmersionRecorderRepository(
                delegate = get<SqlDelightImmersionRepository>(),
                disabledDelegate = get<NoOpImmersionRecorderRepository>(),
                isEnabled = { preferences.captureEnabled().get() },
                diagnostics = get(),
            )
        }
        addSingletonFactory<ImmersionIndexRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionLegacyImportRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionStatsRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionMaintenanceRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionGoalRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionAnkiRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<AnkiInventoryProvider> { AnkiDroidInventoryProvider(get()) }
        addSingletonFactory { AnkiInventorySynchronizer(get(), get()) }
        addSingletonFactory { AnkiKnownnessResolver(get()) }
        addSingletonFactory<SourceTextNormalizer> { DefaultSourceTextNormalizer() }
        addSingletonFactory { DictionaryBackedJapaneseTokenizer(get(), get(), get()) }
        addSingletonFactory { BoundaryImmersionTokenizer() }
        addSingletonFactory<ImmersionIndexExclusionPolicy> {
            SqlImmersionIndexExclusionPolicy(get())
        }
        addSingletonFactory {
            ImmersionIndexingEngine(
                repository = get(),
                normalizer = get(),
                tokenizers = listOf(
                    get<DictionaryBackedJapaneseTokenizer>(),
                    get<BoundaryImmersionTokenizer>(),
                ),
                exclusionPolicy = get(),
            )
        }
        addSingletonFactory { ImmersionReindexController(get(), get()) }
        addSingletonFactory {
            PreferenceAnkiOperationRepairStore(get())
        }
        addSingletonFactory<ImmersionRecorder> {
            val preferences = get<ImmersionStatsPreferences>()
            val ankiRepairStore = get<PreferenceAnkiOperationRepairStore>()
            DefaultImmersionRecorder(
                repository = get<SqlDelightImmersionRepository>(),
                deviceIdProvider = ImmersionDeviceIdProvider { get<eu.kanade.domain.sync.SyncPreferences>().uniqueDeviceID() },
                captureEnabled = { preferences.captureEnabled().get() },
                diagnostics = get(),
                eventPersistenceObserver = ImmersionEventPersistenceObserver { events ->
                    events.filterIsInstance<AnkiOperationEvent>().forEach {
                        ankiRepairStore.remove(it.operationId)
                    }
                    if (
                        events.any { it is ExposureEvent } &&
                        preferences.indexingEnabled().get()
                    ) {
                        ImmersionIndexJob.start(get())
                    }
                },
                configuration = ImmersionRecorderConfiguration(
                    idleTimeoutMillis = preferences.readerIdleTimeoutSeconds().get() * 1_000L,
                ),
            )
        }
        addSingletonFactory<LookupTelemetry> {
            val preferences = get<ImmersionStatsPreferences>()
            DefaultLookupTelemetry(
                recorder = get(),
                rawTextRetention = { preferences.rawTextRetention().get() },
            )
        }
        addSingletonFactory<AnkiOperationRecorder> {
            DefaultAnkiOperationRecorder(
                recorder = get(),
                repairStore = get<PreferenceAnkiOperationRepairStore>(),
                repairWriter = AnkiOperationRepairWriter {
                    get<SqlDelightImmersionRepository>().storeUnlinkedAnkiOperation(it)
                },
            )
        }
        addSingletonFactory {
            ImmersionRecorderLifecycleCoordinator(
                recorder = get(),
                basePreferences = get(),
                statsPreferences = get(),
                ankiOperationRecorder = get(),
            )
        }
        addFactory { GetLegacyAggregateTotals(get()) }
        addSingletonFactory {
            LegacyStatsImporter(
                application = get(),
                repository = get(),
                getManga = get(),
                dictionaryPreferences = get(),
                sourceManager = get(),
            )
        }

        addSingletonFactory<LibraryUpdateErrorWithRelationsRepository> {
            LibraryUpdateErrorWithRelationsRepositoryImpl(get())
        }
        addFactory { GetLibraryUpdateErrorWithRelations(get()) }

        addSingletonFactory<LibraryUpdateErrorMessageRepository> { LibraryUpdateErrorMessageRepositoryImpl(get()) }
        addFactory { GetLibraryUpdateErrorMessages(get()) }
        addFactory { DeleteLibraryUpdateErrorMessages(get()) }
        addFactory { InsertLibraryUpdateErrorMessages(get()) }

        addSingletonFactory<LibraryUpdateErrorRepository> { LibraryUpdateErrorRepositoryImpl(get()) }
        addFactory { GetLibraryUpdateErrors(get()) }
        addFactory { DeleteLibraryUpdateErrors(get()) }
        addFactory { InsertLibraryUpdateErrors(get()) }
    }
}
