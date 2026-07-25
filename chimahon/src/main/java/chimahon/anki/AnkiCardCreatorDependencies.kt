package chimahon.anki

import android.content.Context

internal data class AddedAnkiNote(
    val noteId: Long,
    val warnings: List<AnkiWriteWarning> = emptyList(),
)

/**
 * The provider boundary used by [AnkiCardCreator].
 *
 * Keeping this interface separate from Android's ContentResolver makes the
 * duplicate/preparation/commit ordering executable in local unit tests while
 * production continues to use [AnkiDroidBridge].
 */
internal interface AnkiCardGateway {
    fun hasPermission(): Boolean

    suspend fun getDeckId(deckName: String): Long

    suspend fun findNotes(
        expression: String,
        modelName: String? = null,
        deckId: Long? = null,
    ): List<Long>

    suspend fun prepareAddTarget(
        deckName: String,
        modelName: String,
        allowDefaultDeckCreation: Boolean,
        allowLapisModelCreation: Boolean,
    ): PreparedAnkiAddTarget

    suspend fun resolveAddTargetForCommit(
        prepared: PreparedAnkiAddTarget,
    ): ResolvedAnkiAddTarget

    suspend fun prepareNoteUpdate(noteId: Long): PreparedAnkiNoteUpdate

    suspend fun addPreparedNote(
        target: ResolvedAnkiAddTarget,
        fields: Map<String, String>,
        tags: List<String>,
    ): AddedAnkiNote

    suspend fun updatePreparedNote(
        target: PreparedAnkiNoteUpdate,
        fields: Map<String, String>,
    ): Boolean

    fun triggerSync()
}

internal class AnkiDroidCardGateway(
    context: Context,
) : AnkiCardGateway {
    private val bridge = AnkiDroidBridge(context)

    override fun hasPermission(): Boolean = bridge.hasPermission()

    override suspend fun getDeckId(deckName: String): Long = bridge.getDeckId(deckName)

    override suspend fun findNotes(
        expression: String,
        modelName: String?,
        deckId: Long?,
    ): List<Long> = bridge.findNotes(expression, modelName, deckId)

    override suspend fun prepareAddTarget(
        deckName: String,
        modelName: String,
        allowDefaultDeckCreation: Boolean,
        allowLapisModelCreation: Boolean,
    ): PreparedAnkiAddTarget = bridge.prepareAddTarget(
        deckName = deckName,
        modelName = modelName,
        allowDefaultDeckCreation = allowDefaultDeckCreation,
        allowLapisModelCreation = allowLapisModelCreation,
    )

    override suspend fun resolveAddTargetForCommit(
        prepared: PreparedAnkiAddTarget,
    ): ResolvedAnkiAddTarget = bridge.resolveAddTargetForCommit(prepared)

    override suspend fun prepareNoteUpdate(noteId: Long): PreparedAnkiNoteUpdate {
        return bridge.prepareNoteUpdate(noteId)
    }

    override suspend fun addPreparedNote(
        target: ResolvedAnkiAddTarget,
        fields: Map<String, String>,
        tags: List<String>,
    ): AddedAnkiNote = bridge.addPreparedNote(target, fields, tags)

    override suspend fun updatePreparedNote(
        target: PreparedAnkiNoteUpdate,
        fields: Map<String, String>,
    ): Boolean = bridge.updatePreparedNote(target, fields)

    override fun triggerSync() {
        bridge.triggerSync()
    }

    suspend fun storeMedia(source: AnkiMediaSource): String = bridge.storeMedia(source)
}

internal fun interface AnkiCardMediaStore {
    suspend fun store(source: AnkiMediaSource): String
}

internal fun interface AnkiCardStatisticsRecorder {
    fun record(
        context: Context,
        type: String?,
        profileId: String,
        titleId: String?,
    )
}

internal data class AnkiCardCreatorDependencies(
    val bridge: AnkiCardGateway,
    val mediaStore: AnkiCardMediaStore,
    val statisticsRecorder: AnkiCardStatisticsRecorder,
)
