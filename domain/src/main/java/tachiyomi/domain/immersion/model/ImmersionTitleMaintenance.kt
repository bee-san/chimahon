// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import java.util.UUID

sealed interface ImmersionTitleMutationRequest {
    val sourceTitleId: TitleId

    data class Rename(
        override val sourceTitleId: TitleId,
        val displayTitle: String,
    ) : ImmersionTitleMutationRequest {
        init {
            require(displayTitle.isNotBlank()) { "Display title cannot be blank" }
        }
    }

    data class Merge(
        override val sourceTitleId: TitleId,
        val targetTitleId: TitleId,
    ) : ImmersionTitleMutationRequest

    data class Split(
        override val sourceTitleId: TitleId,
        val targetTitleId: TitleId,
        val displayTitle: String,
        val dateRange: LocalDateRange,
    ) : ImmersionTitleMutationRequest {
        init {
            require(displayTitle.isNotBlank()) { "Display title cannot be blank" }
            require(sourceTitleId != targetTitleId) { "Split target must be a new title" }
        }

        companion object {
            fun create(
                sourceTitleId: TitleId,
                displayTitle: String,
                dateRange: LocalDateRange,
                id: () -> UUID = UUID::randomUUID,
            ) = Split(
                sourceTitleId = sourceTitleId,
                targetTitleId = TitleId(id().toString()),
                displayTitle = displayTitle,
                dateRange = dateRange,
            )
        }
    }
}

enum class ImmersionTitleMutationType {
    RENAME,
    MERGE,
    SPLIT,
}

enum class ImmersionTitleMutationBlocker {
    SOURCE_NOT_FOUND,
    TARGET_NOT_FOUND,
    TARGET_ALREADY_EXISTS,
    SAME_TITLE,
    INCOMPATIBLE_MEDIA,
    INCOMPATIBLE_PROFILE,
    INCOMPATIBLE_LANGUAGE,
    SOURCE_IDENTITY_CONFLICT,
    SHARED_SOURCE_UNITS,
    ACTIVE_ALIAS,
    ACTIVE_SESSION,
    EMPTY_SELECTION,
}

data class ImmersionTitleMutationPreview(
    val request: ImmersionTitleMutationRequest,
    val sessions: Long,
    val events: Long,
    val sourceUnits: Long,
    val lookups: Long,
    val ankiOperations: Long,
    val goals: Long,
    val conflictingSourceUnits: Long,
    val selectionDigest: String,
    val databaseRevision: Long,
    val blockers: Set<ImmersionTitleMutationBlocker> = emptySet(),
) {
    init {
        require(sessions >= 0)
        require(events >= 0)
        require(sourceUnits >= 0)
        require(lookups >= 0)
        require(ankiOperations >= 0)
        require(goals >= 0)
        require(conflictingSourceUnits >= 0)
        require(selectionDigest.isNotBlank())
        require(databaseRevision >= 0)
    }

    val canApply: Boolean
        get() = blockers.isEmpty()
}

data class ImmersionTitleMutation(
    val id: String,
    val type: ImmersionTitleMutationType,
    val sourceTitleId: TitleId,
    val targetTitleId: TitleId?,
    val displayTitle: String?,
    val sessions: Long,
    val sourceUnits: Long,
    val goals: Long,
    val appliedAtEpochMillis: Long,
    val rolledBackAtEpochMillis: Long? = null,
) {
    init {
        require(id.isNotBlank())
        require(sessions >= 0)
        require(sourceUnits >= 0)
        require(goals >= 0)
        require(appliedAtEpochMillis >= 0)
        require(rolledBackAtEpochMillis == null || rolledBackAtEpochMillis >= appliedAtEpochMillis)
        require(type == ImmersionTitleMutationType.RENAME || targetTitleId != null)
        require(type != ImmersionTitleMutationType.RENAME || !displayTitle.isNullOrBlank())
    }

    val canRollback: Boolean
        get() = rolledBackAtEpochMillis == null
}
