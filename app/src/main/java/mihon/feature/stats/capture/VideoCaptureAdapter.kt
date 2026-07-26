// SPDX-License-Identifier: MIT

package mihon.feature.stats.capture

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ContentHash
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.SubtitleSourceLocator
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.VideoOcrSourceLocator
import tachiyomi.domain.immersion.service.CaptureCommand
import tachiyomi.domain.immersion.service.DefaultUnicodeCountPolicy
import tachiyomi.domain.immersion.service.FinalizeReason
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.RecordResult
import tachiyomi.domain.immersion.service.ResumeReason
import tachiyomi.domain.immersion.service.SessionContext
import tachiyomi.domain.immersion.service.SessionHandle
import tachiyomi.domain.immersion.service.SessionStartResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class VideoCaptureTitle(
    val animeId: Long,
    val sourceId: Long,
    val displayTitle: String,
    val profileId: String,
    val languageTag: LanguageTag?,
    val createdAtEpochMillis: Long,
) {
    init {
        require(animeId >= 0) { "Anime ID cannot be negative" }
        require(displayTitle.isNotBlank()) { "Video title cannot be blank" }
        require(createdAtEpochMillis >= 0) { "Video creation time cannot be negative" }
    }
}

data class VideoEpisodeCapture(
    val episodeId: Long,
    val mediaId: String,
    val displayName: String,
    val durationMillis: Long? = null,
) {
    init {
        require(episodeId >= 0) { "Episode ID cannot be negative" }
        require(mediaId.isNotBlank()) { "Video media ID cannot be blank" }
        require(displayName.isNotBlank()) { "Episode name cannot be blank" }
        require(durationMillis == null || durationMillis > 0) { "Video duration must be positive" }
    }
}

enum class VideoSubtitleRole {
    PRIMARY,
    SECONDARY,
}

data class VideoSubtitleCueCapture(
    val trackId: String,
    val trackLanguage: String?,
    val role: VideoSubtitleRole,
    val cueIndex: Int,
    val startMillis: Long,
    val endMillis: Long,
    val text: String,
    val rawText: String = text,
) {
    init {
        require(trackId.isNotBlank()) { "Subtitle track ID cannot be blank" }
        require(cueIndex >= 0) { "Subtitle cue index cannot be negative" }
        require(startMillis >= 0 && endMillis > startMillis) { "Subtitle cue range must be non-empty" }
        require(text.isNotBlank()) { "Subtitle cue text cannot be blank" }
    }
}

data class VideoOcrRegionCapture(
    val regionId: String,
    val text: String,
    val engineId: String,
    val engineVersion: Int,
    val languageTag: String?,
    val confidence: Double? = null,
) {
    init {
        require(regionId.isNotBlank()) { "Video OCR region ID cannot be blank" }
        require(text.isNotBlank()) { "Video OCR text cannot be blank" }
        require(engineId.isNotBlank()) { "Video OCR engine ID cannot be blank" }
        require(engineVersion > 0) { "Video OCR engine version must be positive" }
        require(confidence == null || confidence in 0.0..1.0) {
            "Video OCR confidence must be between zero and one"
        }
    }
}

data class VideoOcrFrameCapture(
    val timestampMillis: Long,
    val frameIdentity: String,
    val regions: List<VideoOcrRegionCapture>,
    val capability: CapabilityState = CapabilityState.AVAILABLE,
) {
    init {
        require(timestampMillis >= 0) { "Video OCR timestamp cannot be negative" }
        require(frameIdentity.isNotBlank()) { "Video OCR frame identity cannot be blank" }
    }
}

data class VideoCoverageSnapshot(
    val activeSubtitleCues: Int = 0,
    val learningSubtitleCues: Int = 0,
    val ocrFrames: Int = 0,
    val ocrRegions: Int = 0,
    val subtitleCapability: CapabilityState = CapabilityState.UNAVAILABLE,
    val ocrCapability: CapabilityState = CapabilityState.PARTIAL,
    val externalPlayback: Boolean = false,
)

data class VideoProgressSnapshot(
    val episodeId: Long,
    val positionMillis: Long = 0,
    val durationMillis: Long? = null,
    val estimatedRemainingMillis: Long? = null,
    val completed: Boolean = false,
)

data class VideoMediaCaptureContext(
    val animeId: Long,
    val episodeId: Long,
    val mediaId: String,
    val positionMillis: Long,
    val sourceUnitId: SourceUnitId? = null,
    val sourceStartMillis: Long? = null,
    val sourceEndMillis: Long? = null,
    val frameIdentity: String? = null,
)

data class VideoSubtitleCueLookupKey(
    val trackId: String,
    val role: VideoSubtitleRole,
    val cueIndex: Int,
    val startMillis: Long,
    val endMillis: Long,
    val normalizedTextHash: String,
)

data class VideoOcrRegionLookupKey(
    val timestampMillis: Long,
    val frameIdentity: String,
    val regionId: String,
    val engineId: String,
    val engineVersion: Int,
    val normalizedTextHash: String,
)

data class VideoInteractionCaptureContext(
    val sessionId: SessionId? = null,
    val subtitleSources: Map<VideoSubtitleCueLookupKey, SourceUnitId> = emptyMap(),
    val ocrSources: Map<VideoOcrRegionLookupKey, SourceUnitId> = emptyMap(),
) {
    fun provenanceFor(cue: VideoSubtitleCueCapture): InteractionProvenance? {
        val session = sessionId ?: return null
        return InteractionProvenance(
            sessionId = session,
            sourceUnitId = subtitleSources[cue.lookupKey()],
        )
    }

    fun provenanceFor(
        frame: VideoOcrFrameCapture,
        region: VideoOcrRegionCapture,
    ): InteractionProvenance? {
        val session = sessionId ?: return null
        return InteractionProvenance(
            sessionId = session,
            sourceUnitId = ocrSources[frame.lookupKey(region)],
        )
    }
}

/**
 * Converts live player callbacks into event-backed immersion data.
 *
 * Subtitle text is accepted only when a cue is active. Parsing or preloading subtitle files does
 * not call this adapter and therefore cannot create exposure. A repeated observer callback while
 * the same cue remains active is ignored. Re-entry is counted only after a meaningful seek or
 * after playback has moved outside the cue by the documented hysteresis.
 */
class VideoCaptureAdapter(
    captureTitle: VideoCaptureTitle,
    private val episode: VideoEpisodeCapture,
    private val recorder: ImmersionRecorder,
    private val rawTextRetention: () -> RawTextRetention,
    private val bufferingGraceMillis: Long,
    private val incognito: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val sourceKey = "video:${captureTitle.animeId}"
    private val titleId = TitleId(stableUuid(TITLE_NAMESPACE, "$sourceKey|${captureTitle.profileId}"))
    private val title = ImmersionTitle(
        id = titleId,
        mediaKind = MediaKind.VIDEO,
        sourceKey = sourceKey,
        profileId = captureTitle.profileId,
        languageTag = captureTitle.languageTag,
        displayTitle = captureTitle.displayTitle,
        libraryId = captureTitle.animeId,
        mediaId = captureTitle.animeId.toString(),
        createdAtEpochMillis = captureTitle.createdAtEpochMillis,
        updatedAtEpochMillis = maxOf(captureTitle.createdAtEpochMillis, clock()),
    )
    private val animeId = captureTitle.animeId
    private val learningLanguage = captureTitle.languageTag
    private val commands = Channel<AdapterCommand>(Channel.UNLIMITED)
    private val mutableCoverage = MutableStateFlow(VideoCoverageSnapshot())
    private val mutableProgress = MutableStateFlow(
        VideoProgressSnapshot(
            episodeId = episode.episodeId,
            durationMillis = episode.durationMillis,
            estimatedRemainingMillis = episode.durationMillis,
        ),
    )
    private val mutableMediaContext = MutableStateFlow<VideoMediaCaptureContext?>(null)
    private val mutableInteractionContext = MutableStateFlow(VideoInteractionCaptureContext())
    private var bufferingJob: Job? = null
    private var bufferingGeneration = 0L

    val coverage: StateFlow<VideoCoverageSnapshot> = mutableCoverage.asStateFlow()
    val progress: StateFlow<VideoProgressSnapshot> = mutableProgress.asStateFlow()
    val mediaContext: StateFlow<VideoMediaCaptureContext?> = mutableMediaContext.asStateFlow()
    val interactionContext: StateFlow<VideoInteractionCaptureContext> = mutableInteractionContext.asStateFlow()

    init {
        require(bufferingGraceMillis >= 0) { "Buffering grace cannot be negative" }
        workerScope.launch {
            val state = AdapterState()
            for (command in commands) {
                if (state.finalized) {
                    if (command is AdapterCommand.Finalize) {
                        command.completion.complete(Unit)
                    }
                    continue
                }
                when (command) {
                    AdapterCommand.Playable -> ensureStarted(state)
                    is AdapterCommand.Playing -> handlePlaying(state, command.playing)
                    is AdapterCommand.Buffering -> handleBuffering(state, command.buffering, workerScope)
                    is AdapterCommand.BufferingGraceElapsed -> {
                        if (command.generation == bufferingGeneration && state.buffering) {
                            setBlocker(state, CaptureBlocker.Buffering, true)
                        }
                    }
                    is AdapterCommand.Seeking -> handleSeeking(state, command.seeking)
                    is AdapterCommand.Backgrounded -> {
                        setBlocker(state, CaptureBlocker.Background, command.backgrounded)
                    }
                    is AdapterCommand.Position -> handlePosition(state, command)
                    is AdapterCommand.SubtitleTrackChanged -> handleTrackChanged(state, command)
                    is AdapterCommand.SubtitleModeChanged -> handleSubtitleMode(state, command.visible)
                    is AdapterCommand.SubtitleCueActive -> handleSubtitleCue(state, command.cue)
                    is AdapterCommand.SubtitleCueCleared -> clearActiveSubtitle(state, command.role)
                    is AdapterCommand.OcrVisible -> handleOcrVisible(state, command.frame)
                    AdapterCommand.OcrHidden -> state.activeOcrSources.clear()
                    AdapterCommand.EpisodeCompleted -> recordCompletion(state, titleCompleted = false)
                    AdapterCommand.TitleCompleted -> recordCompletion(state, titleCompleted = true)
                    is AdapterCommand.ExternalPlayback -> handleExternalPlayback(state)
                    is AdapterCommand.Finalize -> {
                        finalize(state, command.reason)
                        command.completion.complete(Unit)
                        commands.close()
                    }
                }
            }
        }
    }

    fun onPlayableMedia() {
        commands.trySend(AdapterCommand.Playable)
    }

    fun setPlaying(playing: Boolean) {
        commands.trySend(AdapterCommand.Playing(playing))
    }

    fun setBuffering(buffering: Boolean) {
        commands.trySend(AdapterCommand.Buffering(buffering))
    }

    fun setSeeking(seeking: Boolean) {
        commands.trySend(AdapterCommand.Seeking(seeking))
    }

    fun setBackgrounded(backgrounded: Boolean) {
        commands.trySend(AdapterCommand.Backgrounded(backgrounded))
    }

    fun onPlaybackPosition(
        positionMillis: Long,
        durationMillis: Long?,
    ) {
        commands.trySend(AdapterCommand.Position(positionMillis, durationMillis))
    }

    fun onSubtitleTrackChanged(
        primaryTrackId: String?,
        secondaryTrackId: String?,
        primaryLanguage: String?,
        secondaryLanguage: String?,
    ) {
        commands.trySend(
            AdapterCommand.SubtitleTrackChanged(
                primaryTrackId,
                secondaryTrackId,
                primaryLanguage,
                secondaryLanguage,
            ),
        )
    }

    fun setSubtitleModeVisible(visible: Boolean) {
        commands.trySend(AdapterCommand.SubtitleModeChanged(visible))
    }

    fun onSubtitleCueActive(cue: VideoSubtitleCueCapture) {
        commands.trySend(AdapterCommand.SubtitleCueActive(cue))
    }

    fun onSubtitleCueCleared(role: VideoSubtitleRole) {
        commands.trySend(AdapterCommand.SubtitleCueCleared(role))
    }

    fun onVideoOcrVisible(frame: VideoOcrFrameCapture) {
        commands.trySend(AdapterCommand.OcrVisible(frame))
    }

    fun onVideoOcrHidden() {
        commands.trySend(AdapterCommand.OcrHidden)
    }

    fun snapshotSubtitleLookupProvenance(cue: VideoSubtitleCueCapture): InteractionProvenance? {
        return interactionContext.value.provenanceFor(cue)
    }

    fun snapshotOcrLookupProvenance(
        frame: VideoOcrFrameCapture,
        region: VideoOcrRegionCapture,
    ): InteractionProvenance? {
        return interactionContext.value.provenanceFor(frame, region)
    }

    fun onEpisodeCompleted() {
        commands.trySend(AdapterCommand.EpisodeCompleted)
    }

    fun onTitleCompleted() {
        commands.trySend(AdapterCommand.TitleCompleted)
    }

    fun markExternalPlaybackUnsupported() {
        commands.trySend(AdapterCommand.ExternalPlayback)
    }

    fun finalize(reason: FinalizeReason = FinalizeReason.NORMAL): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        bufferingJob?.cancel()
        if (commands.trySend(AdapterCommand.Finalize(reason, completion)).isFailure) {
            completion.complete(Unit)
        }
        return completion
    }

    private suspend fun ensureStarted(state: AdapterState): SessionHandle? {
        if (state.finalized) return null
        state.handle?.let { return it }
        return when (val result = recorder.startSession(SessionContext(title = title, incognito = incognito))) {
            is SessionStartResult.Started -> result.handle.also { handle ->
                state.handle = handle
                publishInteractionContext(state)
                if (state.blockers.isNotEmpty()) {
                    recorder.pause(handle, pauseReason(state.blockers))
                }
            }
            else -> null
        }
    }

    private suspend fun handlePlaying(
        state: AdapterState,
        playing: Boolean,
    ) {
        ensureStarted(state) ?: return
        setBlocker(state, CaptureBlocker.NotPlaying, !playing)
    }

    private suspend fun handleBuffering(
        state: AdapterState,
        buffering: Boolean,
        workerScope: CoroutineScope,
    ) {
        state.buffering = buffering
        bufferingGeneration += 1
        bufferingJob?.cancel()
        if (buffering) {
            val generation = bufferingGeneration
            bufferingJob = workerScope.launch {
                delay(bufferingGraceMillis)
                commands.trySend(AdapterCommand.BufferingGraceElapsed(generation))
            }
        } else {
            setBlocker(state, CaptureBlocker.Buffering, false)
        }
    }

    private suspend fun handleSeeking(
        state: AdapterState,
        seeking: Boolean,
    ) {
        if (seeking) {
            state.seekGeneration += 1
            state.activeSubtitleSources.clear()
        }
        setBlocker(state, CaptureBlocker.Seeking, seeking)
    }

    private suspend fun handlePosition(
        state: AdapterState,
        command: AdapterCommand.Position,
    ) {
        val handle = ensureStarted(state) ?: return
        val position = command.positionMillis.coerceAtLeast(0)
        val duration = command.durationMillis?.takeIf { it > 0 } ?: episode.durationMillis
        val previous = state.lastPositionMillis
        val delta = previous?.let { position - it }
        val meaningfulSeek = delta != null && abs(delta) > MEANINGFUL_SEEK_MILLIS
        if (meaningfulSeek) {
            state.seekGeneration += 1
            state.activeSubtitleSources.clear()
            recorder.record(handle, CaptureCommand.Activity(EventType.SEEK))
        } else if (delta != null && delta > 0 && state.blockers.isEmpty()) {
            recorder.record(handle, CaptureCommand.Activity(EventType.PROGRESS))
        }
        state.lastPositionMillis = position

        val completed = duration != null && position >= (duration * COMPLETION_RATIO).toLong()
        mutableProgress.value = VideoProgressSnapshot(
            episodeId = episode.episodeId,
            positionMillis = position,
            durationMillis = duration,
            estimatedRemainingMillis = duration?.let { (it - position).coerceAtLeast(0) },
            completed = completed,
        )
        mutableMediaContext.value = VideoMediaCaptureContext(
            animeId = animeId,
            episodeId = episode.episodeId,
            mediaId = episode.mediaId,
            positionMillis = position,
        )
        if (completed) recordCompletion(state, titleCompleted = false)
    }

    private fun handleTrackChanged(
        state: AdapterState,
        command: AdapterCommand.SubtitleTrackChanged,
    ) {
        val next = SubtitleTrackState(
            command.primaryTrackId,
            command.secondaryTrackId,
            command.primaryLanguage,
            command.secondaryLanguage,
        )
        if (state.tracks == next) return
        state.tracks = next
        state.activeSubtitleSources.clear()
        state.handle?.let {
            recorder.record(it, CaptureCommand.Activity(EventType.SUBTITLE_TRACK_CHANGED))
        }
        publishCoverage(state)
    }

    private fun handleSubtitleMode(
        state: AdapterState,
        visible: Boolean,
    ) {
        if (state.subtitleVisible == visible) return
        state.subtitleVisible = visible
        if (!visible) state.activeSubtitleSources.clear()
        state.handle?.let {
            recorder.record(it, CaptureCommand.Activity(EventType.SUBTITLE_MODE_CHANGED))
        }
        publishCoverage(state)
    }

    private suspend fun handleSubtitleCue(
        state: AdapterState,
        cue: VideoSubtitleCueCapture,
    ) {
        val handle = ensureStarted(state) ?: return
        if (!state.subtitleVisible || state.blockers.isNotEmpty()) return
        val source = subtitleSource(cue)
        if (state.activeSubtitleSources[cue.role] == source.id) return

        val previousExposure = state.subtitleExposureState[source.id]
        val position = state.lastPositionMillis ?: cue.startMillis
        val replayAllowed = previousExposure == null ||
            previousExposure.seekGeneration != state.seekGeneration ||
            abs(position - previousExposure.positionMillis) >= CUE_REENTRY_HYSTERESIS_MILLIS
        if (!replayAllowed) return

        val contributes = cue.role == VideoSubtitleRole.PRIMARY &&
            cue.trackLanguage.matchesLearningLanguage(learningLanguage)
        val count = source.characterCounts.gross
        val globallySeen = source.id in state.seenSources || recorder.hasSeenSource(source.id)
        val replayOrdinal = state.replayOrdinals.getOrDefault(source.id, 0)
        val result = recorder.record(
            handle,
            CaptureCommand.Exposure(
                source = source,
                grossCharacters = if (contributes) count else NonNegativeCounter.ZERO,
                uniqueSourceCharacters = if (contributes && !globallySeen) count else NonNegativeCounter.ZERO,
                netCharacters = NetCharacterProgress.ZERO,
                replayOrdinal = replayOrdinal,
                exposurePolicy = SUBTITLE_EXPOSURE_POLICY,
            ),
        )
        if (result is RecordResult.Enqueued) {
            state.activeSubtitleSources[cue.role] = source.id
            state.seenSources += source.id
            state.subtitleSources += source.id
            if (contributes) state.learningSubtitleSources += source.id
            state.replayOrdinals[source.id] = replayOrdinal + 1
            state.subtitleExposureState[source.id] = CueExposureState(position, state.seekGeneration)
            state.subtitleLookupSources = state.subtitleLookupSources.put(cue.lookupKey(), source.id)
            mutableMediaContext.value = VideoMediaCaptureContext(
                animeId = animeId,
                episodeId = episode.episodeId,
                mediaId = episode.mediaId,
                positionMillis = position,
                sourceUnitId = source.id,
                sourceStartMillis = cue.startMillis,
                sourceEndMillis = cue.endMillis,
            )
            publishInteractionContext(state)
            publishCoverage(state)
        }
    }

    private fun clearActiveSubtitle(
        state: AdapterState,
        role: VideoSubtitleRole,
    ) {
        state.activeSubtitleSources.remove(role)
    }

    private suspend fun handleOcrVisible(
        state: AdapterState,
        frame: VideoOcrFrameCapture,
    ) {
        val handle = ensureStarted(state) ?: return
        if (CaptureBlocker.Background in state.blockers || state.externalPlayback) return
        val visibleSources = linkedSetOf<SourceUnitId>()
        var lookupSourcesChanged = false
        frame.regions.forEachIndexed { index, region ->
            val anchor = stableOcrAnchor(state, frame, region, index)
            val source = ocrSource(frame, region, anchor)
            visibleSources += source.id
            if (source.id in state.activeOcrSources) {
                val lookupKey = frame.lookupKey(region)
                if (state.ocrLookupSources[lookupKey] != source.id) {
                    state.ocrLookupSources = state.ocrLookupSources.put(lookupKey, source.id)
                    lookupSourcesChanged = true
                }
                return@forEachIndexed
            }
            val contributes = region.languageTag.matchesLearningLanguage(learningLanguage)
            val count = source.characterCounts.gross
            val globallySeen = source.id in state.seenSources || recorder.hasSeenSource(source.id)
            val replayOrdinal = state.replayOrdinals.getOrDefault(source.id, 0)
            val result = recorder.record(
                handle,
                CaptureCommand.Exposure(
                    source = source,
                    grossCharacters = if (contributes) count else NonNegativeCounter.ZERO,
                    uniqueSourceCharacters = if (contributes && !globallySeen) count else NonNegativeCounter.ZERO,
                    netCharacters = NetCharacterProgress.ZERO,
                    replayOrdinal = replayOrdinal,
                    exposurePolicy = OCR_EXPOSURE_POLICY,
                ),
            )
            if (result is RecordResult.Enqueued) {
                state.seenSources += source.id
                state.ocrSources += source.id
                state.replayOrdinals[source.id] = replayOrdinal + 1
                val lookupKey = frame.lookupKey(region)
                if (state.ocrLookupSources[lookupKey] != source.id) {
                    state.ocrLookupSources = state.ocrLookupSources.put(lookupKey, source.id)
                    lookupSourcesChanged = true
                }
                mutableMediaContext.value = VideoMediaCaptureContext(
                    animeId = animeId,
                    episodeId = episode.episodeId,
                    mediaId = episode.mediaId,
                    positionMillis = frame.timestampMillis,
                    sourceUnitId = source.id,
                    sourceStartMillis = frame.timestampMillis,
                    frameIdentity = anchor.frameIdentity,
                )
            } else {
                visibleSources -= source.id
            }
        }
        if (lookupSourcesChanged) publishInteractionContext(state)
        state.activeOcrSources.clear()
        state.activeOcrSources += visibleSources
        state.ocrFrames += frame.timestampMillis
        state.ocrCapability = frame.capability
        publishCoverage(state)
    }

    private fun stableOcrAnchor(
        state: AdapterState,
        frame: VideoOcrFrameCapture,
        region: VideoOcrRegionCapture,
        index: Int,
    ): OcrAnchor {
        val normalizedText = normalize(region.text)
        val key = "${region.regionId}|${region.engineId}|${region.engineVersion}|${sha256(normalizedText)}"
        val previous = state.ocrAnchors[key]
        if (previous != null && frame.timestampMillis - previous.lastTimestampMillis in 0..OCR_ADJACENT_WINDOW_MILLIS) {
            return previous.copy(lastTimestampMillis = frame.timestampMillis).also {
                state.ocrAnchors[key] = it
            }
        }
        return OcrAnchor(
            timestampBucketMillis = frame.timestampMillis.floorTo(OCR_TIMESTAMP_BUCKET_MILLIS),
            frameIdentity = frame.frameIdentity,
            regionId = region.regionId.ifBlank { index.toString() },
            lastTimestampMillis = frame.timestampMillis,
        ).also { state.ocrAnchors[key] = it }
    }

    private fun recordCompletion(
        state: AdapterState,
        titleCompleted: Boolean,
    ) {
        val handle = state.handle ?: return
        if (!state.episodeCompleted) {
            state.episodeCompleted = true
            recorder.record(handle, CaptureCommand.Activity(EventType.UNIT_COMPLETED))
        }
        if (titleCompleted && !state.titleCompleted) {
            state.titleCompleted = true
            recorder.record(handle, CaptureCommand.Activity(EventType.TITLE_COMPLETED))
        }
    }

    private suspend fun handleExternalPlayback(state: AdapterState) {
        state.externalPlayback = true
        ensureStarted(state)
        setBlocker(state, CaptureBlocker.ExternalPlayback, true)
        publishCoverage(state)
    }

    private suspend fun setBlocker(
        state: AdapterState,
        blocker: CaptureBlocker,
        blocked: Boolean,
    ) {
        val wasBlocked = state.blockers.isNotEmpty()
        if (blocked) {
            state.blockers += blocker
        } else {
            state.blockers -= blocker
        }
        val isBlocked = state.blockers.isNotEmpty()
        val handle = state.handle ?: return
        when {
            !wasBlocked && isBlocked -> recorder.pause(handle, pauseReason(blocker))
            wasBlocked && !isBlocked -> recorder.resume(handle, resumeReason(blocker))
            blocker == CaptureBlocker.Background && blocked ->
                recorder.pause(handle, PauseReason.BACKGROUND)
            blocker == CaptureBlocker.Background && !blocked ->
                recorder.resume(handle, ResumeReason.FOREGROUND)
        }
    }

    private suspend fun finalize(
        state: AdapterState,
        reason: FinalizeReason,
    ) {
        state.finalized = true
        state.handle?.let { recorder.finalize(it, reason) }
        state.handle = null
        state.subtitleLookupSources = persistentMapOf()
        state.ocrLookupSources = persistentMapOf()
        publishInteractionContext(state)
    }

    private fun subtitleSource(cue: VideoSubtitleCueCapture): ImmersionSourceUnit {
        val normalizedText = normalize(cue.text)
        val textHash = ContentHash(sha256(normalizedText))
        val locator = SubtitleSourceLocator(
            sourceKey = sourceKey,
            episodeMediaId = episode.mediaId,
            subtitleTrackId = "${cue.role.name.lowercase()}:${cue.trackId}",
            cueIndex = cue.cueIndex,
            cueStartMillis = cue.startMillis,
            cueEndMillis = cue.endMillis,
            normalizedTextHash = textHash,
        )
        val now = clock()
        val count = DefaultUnicodeCountPolicy.analyze(normalizedText).countableCharacters
        return ImmersionSourceUnit(
            id = SourceUnitId(stableUuid(SOURCE_NAMESPACE, "${titleId.value}|${locator.canonicalKey()}")),
            titleId = titleId,
            sourceKind = locator.sourceKind,
            canonicalLocator = locator.canonicalKey(),
            normalizedTextHash = textHash.value,
            episodeOrMediaId = episode.mediaId,
            pageOrCueIndex = cue.cueIndex.toLong(),
            trackId = locator.subtitleTrackId,
            sourceStart = cue.startMillis,
            sourceEnd = cue.endMillis,
            parserVersion = SUBTITLE_PARSER_VERSION,
            tokenizerVersion = DefaultUnicodeCountPolicy.version,
            rawText = normalize(cue.rawText).takeUnless { rawTextRetention() == RawTextRetention.NEVER },
            firstExposedAtEpochMillis = now,
            lastExposedAtEpochMillis = now,
            characterCounts = CharacterVolume(gross = count, uniqueSource = count),
        )
    }

    private fun ocrSource(
        frame: VideoOcrFrameCapture,
        region: VideoOcrRegionCapture,
        anchor: OcrAnchor,
    ): ImmersionSourceUnit {
        val normalizedText = normalize(region.text)
        val textHash = ContentHash(sha256(normalizedText))
        val locator = VideoOcrSourceLocator(
            sourceKey = sourceKey,
            episodeMediaId = episode.mediaId,
            timestampBucketMillis = anchor.timestampBucketMillis,
            frameIdentity = anchor.frameIdentity,
            ocrRegionId = anchor.regionId,
            ocrEngineId = region.engineId,
            ocrRevision = region.engineVersion,
            normalizedTextHash = textHash,
        )
        val now = clock()
        val count = DefaultUnicodeCountPolicy.analyze(normalizedText).countableCharacters
        return ImmersionSourceUnit(
            id = SourceUnitId(stableUuid(SOURCE_NAMESPACE, "${titleId.value}|${locator.canonicalKey()}")),
            titleId = titleId,
            sourceKind = locator.sourceKind,
            canonicalLocator = locator.canonicalKey(),
            normalizedTextHash = textHash.value,
            episodeOrMediaId = episode.mediaId,
            trackId = VIDEO_OCR_TRACK_ID,
            sourceStart = frame.timestampMillis,
            ocrEngineId = region.engineId,
            ocrVersion = region.engineVersion,
            ocrConfidence = region.confidence,
            ocrQuality = frame.capability,
            tokenizerVersion = DefaultUnicodeCountPolicy.version,
            rawText = normalizedText.takeUnless { rawTextRetention() == RawTextRetention.NEVER },
            firstExposedAtEpochMillis = now,
            lastExposedAtEpochMillis = now,
            characterCounts = CharacterVolume(gross = count, uniqueSource = count),
        )
    }

    private fun publishCoverage(state: AdapterState) {
        mutableCoverage.value = VideoCoverageSnapshot(
            activeSubtitleCues = state.subtitleSources.size,
            learningSubtitleCues = state.learningSubtitleSources.size,
            ocrFrames = state.ocrFrames.size,
            ocrRegions = state.ocrSources.size,
            subtitleCapability = subtitleCapability(state),
            ocrCapability = state.ocrCapability,
            externalPlayback = state.externalPlayback,
        )
    }

    private fun publishInteractionContext(state: AdapterState) {
        mutableInteractionContext.value = VideoInteractionCaptureContext(
            sessionId = state.handle?.sessionId,
            subtitleSources = state.subtitleLookupSources,
            ocrSources = state.ocrLookupSources,
        )
    }

    private data class AdapterState(
        var handle: SessionHandle? = null,
        var finalized: Boolean = false,
        val blockers: MutableSet<CaptureBlocker> = mutableSetOf(CaptureBlocker.NotPlaying),
        val activeSubtitleSources: MutableMap<VideoSubtitleRole, SourceUnitId> = mutableMapOf(),
        val activeOcrSources: MutableSet<SourceUnitId> = mutableSetOf(),
        val seenSources: MutableSet<SourceUnitId> = mutableSetOf(),
        val subtitleSources: MutableSet<SourceUnitId> = mutableSetOf(),
        val learningSubtitleSources: MutableSet<SourceUnitId> = mutableSetOf(),
        val ocrSources: MutableSet<SourceUnitId> = mutableSetOf(),
        val ocrFrames: MutableSet<Long> = mutableSetOf(),
        val replayOrdinals: MutableMap<SourceUnitId, Int> = mutableMapOf(),
        val subtitleExposureState: MutableMap<SourceUnitId, CueExposureState> = mutableMapOf(),
        val ocrAnchors: MutableMap<String, OcrAnchor> = mutableMapOf(),
        var subtitleLookupSources: PersistentMap<VideoSubtitleCueLookupKey, SourceUnitId> = persistentMapOf(),
        var ocrLookupSources: PersistentMap<VideoOcrRegionLookupKey, SourceUnitId> = persistentMapOf(),
        var tracks: SubtitleTrackState = SubtitleTrackState(),
        var subtitleVisible: Boolean = true,
        var buffering: Boolean = false,
        var externalPlayback: Boolean = false,
        var lastPositionMillis: Long? = null,
        var seekGeneration: Long = 0,
        var ocrCapability: CapabilityState = CapabilityState.PARTIAL,
        var episodeCompleted: Boolean = false,
        var titleCompleted: Boolean = false,
    )

    private sealed interface AdapterCommand {
        data object Playable : AdapterCommand
        data class Playing(val playing: Boolean) : AdapterCommand
        data class Buffering(val buffering: Boolean) : AdapterCommand
        data class BufferingGraceElapsed(val generation: Long) : AdapterCommand
        data class Seeking(val seeking: Boolean) : AdapterCommand
        data class Backgrounded(val backgrounded: Boolean) : AdapterCommand
        data class Position(val positionMillis: Long, val durationMillis: Long?) : AdapterCommand

        data class SubtitleTrackChanged(
            val primaryTrackId: String?,
            val secondaryTrackId: String?,
            val primaryLanguage: String?,
            val secondaryLanguage: String?,
        ) : AdapterCommand

        data class SubtitleModeChanged(val visible: Boolean) : AdapterCommand
        data class SubtitleCueActive(val cue: VideoSubtitleCueCapture) : AdapterCommand
        data class SubtitleCueCleared(val role: VideoSubtitleRole) : AdapterCommand
        data class OcrVisible(val frame: VideoOcrFrameCapture) : AdapterCommand
        data object OcrHidden : AdapterCommand
        data object EpisodeCompleted : AdapterCommand
        data object TitleCompleted : AdapterCommand
        data object ExternalPlayback : AdapterCommand
        data class Finalize(val reason: FinalizeReason, val completion: CompletableDeferred<Unit>) : AdapterCommand
    }

    private sealed interface CaptureBlocker {
        data object NotPlaying : CaptureBlocker
        data object Buffering : CaptureBlocker
        data object Seeking : CaptureBlocker
        data object Background : CaptureBlocker
        data object ExternalPlayback : CaptureBlocker
    }

    private data class SubtitleTrackState(
        val primaryTrackId: String? = null,
        val secondaryTrackId: String? = null,
        val primaryLanguage: String? = null,
        val secondaryLanguage: String? = null,
    )

    private data class CueExposureState(
        val positionMillis: Long,
        val seekGeneration: Long,
    )

    private data class OcrAnchor(
        val timestampBucketMillis: Long,
        val frameIdentity: String,
        val regionId: String,
        val lastTimestampMillis: Long,
    )

    private fun subtitleCapability(state: AdapterState): CapabilityState {
        if (!state.subtitleVisible || state.tracks.primaryTrackId == null) return CapabilityState.UNAVAILABLE
        val language = state.tracks.primaryLanguage
        return when {
            learningLanguage == null || language.isNullOrBlank() -> CapabilityState.PARTIAL
            language.matchesLearningLanguage(learningLanguage) -> CapabilityState.AVAILABLE
            else -> CapabilityState.PARTIAL
        }
    }

    private fun pauseReason(blockers: Set<CaptureBlocker>): PauseReason = when {
        CaptureBlocker.Background in blockers -> PauseReason.BACKGROUND
        CaptureBlocker.Buffering in blockers -> PauseReason.BUFFERING
        else -> PauseReason.USER
    }

    private fun pauseReason(blocker: CaptureBlocker): PauseReason = when (blocker) {
        CaptureBlocker.Background -> PauseReason.BACKGROUND
        CaptureBlocker.Buffering -> PauseReason.BUFFERING
        else -> PauseReason.USER
    }

    private fun resumeReason(blocker: CaptureBlocker): ResumeReason = when (blocker) {
        CaptureBlocker.Background -> ResumeReason.FOREGROUND
        CaptureBlocker.Buffering -> ResumeReason.BUFFERING_ENDED
        else -> ResumeReason.USER
    }

    private companion object {
        const val MEANINGFUL_SEEK_MILLIS = 3_000L
        const val CUE_REENTRY_HYSTERESIS_MILLIS = 1_000L
        const val OCR_ADJACENT_WINDOW_MILLIS = 2_000L
        const val OCR_TIMESTAMP_BUCKET_MILLIS = 1_000L
        const val COMPLETION_RATIO = 0.95
        const val SUBTITLE_PARSER_VERSION = 1
        const val VIDEO_OCR_TRACK_ID = "video-ocr"
        const val SUBTITLE_EXPOSURE_POLICY = "video-subtitle-active-cue-reentry-1s-v1"
        const val OCR_EXPOSURE_POLICY = "video-ocr-visible-adjacent-2s-v1"
        const val TITLE_NAMESPACE = "immersion-title-video"
        const val SOURCE_NAMESPACE = "immersion-source-video"
    }
}

private fun String?.matchesLearningLanguage(learningLanguage: LanguageTag?): Boolean {
    if (learningLanguage == null) return true
    if (this.isNullOrBlank()) return true
    val normalized = replace('_', '-')
    val canonical = Locale.forLanguageTag(normalized).toLanguageTag()
    return canonical.equals(learningLanguage.value, ignoreCase = true) ||
        canonical.substringBefore('-').equals(learningLanguage.value.substringBefore('-'), ignoreCase = true)
}

private fun Long.floorTo(size: Long): Long = this - (this % size)

private fun normalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC)
        .trim()
        .replace(Regex("\\s+"), " ")

private fun VideoSubtitleCueCapture.lookupKey(): VideoSubtitleCueLookupKey =
    VideoSubtitleCueLookupKey(
        trackId = trackId,
        role = role,
        cueIndex = cueIndex,
        startMillis = startMillis,
        endMillis = endMillis,
        normalizedTextHash = sha256(normalize(text)),
    )

private fun VideoOcrFrameCapture.lookupKey(region: VideoOcrRegionCapture): VideoOcrRegionLookupKey =
    VideoOcrRegionLookupKey(
        timestampMillis = timestampMillis,
        frameIdentity = frameIdentity,
        regionId = region.regionId,
        engineId = region.engineId,
        engineVersion = region.engineVersion,
        normalizedTextHash = sha256(normalize(region.text)),
    )

private fun stableUuid(namespace: String, value: String): String =
    UUID.nameUUIDFromBytes(
        "$namespace\u0000$value".toByteArray(StandardCharsets.UTF_8),
    ).toString()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
