package eu.kanade.domain

import tachiyomi.data.immersion.SqlDelightImmersionRepository
import tachiyomi.data.libraryUpdateError.LibraryUpdateErrorRepositoryImpl
import tachiyomi.data.libraryUpdateError.LibraryUpdateErrorWithRelationsRepositoryImpl
import tachiyomi.data.libraryUpdateErrorMessage.LibraryUpdateErrorMessageRepositoryImpl
import tachiyomi.domain.immersion.repository.FeatureFlaggedImmersionRecorderRepository
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import tachiyomi.domain.immersion.repository.ImmersionIndexRepository
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.repository.ImmersionRecorderRepository
import tachiyomi.domain.immersion.repository.ImmersionStatsRepository
import tachiyomi.domain.immersion.repository.NoOpImmersionRecorderRepository
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
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
        addSingletonFactory<ImmersionStatsRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionMaintenanceRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionGoalRepository> { get<SqlDelightImmersionRepository>() }
        addSingletonFactory<ImmersionAnkiRepository> { get<SqlDelightImmersionRepository>() }

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
