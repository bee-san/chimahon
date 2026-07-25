package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.Bitmap
import `is`.xyz.mpv.MPVLib
import java.io.Closeable
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns one resource until the UI owner closes it and every in-flight mining lease has finished.
 *
 * The resource is disposed exactly once. A lease lets accepted mining survive popup dismissal
 * without letting the popup and background job race to recycle the same bitmap.
 */
internal class OwnedResource<T : Any>(
    resource: T,
    private val disposer: (T) -> Unit,
) : Closeable {
    private val lock = Any()
    private var resource: T? = resource
    private var ownerOpen = true
    private var leases = 0

    fun valueOrNull(): T? = synchronized(lock) { resource }

    fun acquireLease(): Lease? = synchronized(lock) {
        if (!ownerOpen || resource == null) return null
        leases += 1
        Lease(this)
    }

    override fun close() {
        disposeIfReady(
            synchronized(lock) {
                if (!ownerOpen) return
                ownerOpen = false
                takeResourceForDisposal()
            },
        )
    }

    private fun releaseLease() {
        disposeIfReady(
            synchronized(lock) {
                check(leases > 0) { "Owned resource lease released more than once" }
                leases -= 1
                takeResourceForDisposal()
            },
        )
    }

    private fun takeResourceForDisposal(): T? {
        if (ownerOpen || leases != 0) return null
        return resource.also { resource = null }
    }

    private fun disposeIfReady(value: T?) {
        value?.let(disposer)
    }

    class Lease internal constructor(
        private val owner: OwnedResource<*>,
    ) : Closeable {
        private val lock = Any()
        private var open = true

        override fun close() {
            synchronized(lock) {
                if (!open) return
                open = false
            }
            owner.releaseLease()
        }
    }
}

internal class OwnedBitmap private constructor(
    private val owner: OwnedResource<Bitmap>,
) : Closeable {
    constructor(bitmap: Bitmap) : this(
        OwnedResource(bitmap) {
            if (!it.isRecycled) it.recycle()
        },
    )

    fun bitmapOrNull(): Bitmap? = owner.valueOrNull()

    fun acquireLease(): OwnedResource.Lease? = owner.acquireLease()

    override fun close() = owner.close()

    override fun toString(): String = "OwnedBitmap(<redacted>)"
}

/**
 * A media snapshot bound to the paused player state at lookup/OCR-capture time.
 *
 * All values are copied primitives or immutable scene-core values. In particular, this never
 * retains the mutable extension [eu.kanade.tachiyomi.animesource.model.Video] instance.
 */
internal class SceneCaptureRequest(
    val videoIdentity: SceneVideoIdentity,
    val videoInput: SceneVideoInputResolution,
    val anchorMediaSeconds: Double,
    val sourceRangeCandidate: SceneRangeCandidate?,
    val timingSnapshot: SceneTimingSnapshot,
    val resolvedTiming: SceneResolvedTiming?,
    val animationLimitSeconds: Double,
    val audioLimitSeconds: Double,
    val selectedExternalAudioRequired: Boolean,
    val selectedAudioFfmpegIndex: Int?,
    private val stillFallback: OwnedBitmap,
) : Closeable {
    fun fallbackBitmapOrNull(): Bitmap? = stillFallback.bitmapOrNull()

    fun acquireMiningLease(): OwnedResource.Lease? = stillFallback.acquireLease()

    override fun close() = stillFallback.close()

    override fun toString(): String {
        return "SceneCaptureRequest(" +
            "videoIdentity=$videoIdentity, " +
            "videoInput=$videoInput, " +
            "anchorMediaSeconds=$anchorMediaSeconds, " +
            "sourceRangeCandidate=$sourceRangeCandidate, " +
            "timingSnapshot=$timingSnapshot, " +
            "resolvedTiming=$resolvedTiming, " +
            "animationLimitSeconds=$animationLimitSeconds, " +
            "audioLimitSeconds=$audioLimitSeconds, " +
            "selectedExternalAudioRequired=$selectedExternalAudioRequired, " +
            "selectedAudioFfmpegIndex=$selectedAudioFfmpegIndex, " +
            "stillFallback=<redacted>)"
    }
}

internal class CapturedOcrFrame(
    val request: SceneCaptureRequest,
) : Closeable {
    val bitmap: Bitmap?
        get() = request.fallbackBitmapOrNull()

    val mediaTimeSeconds: Double
        get() = request.anchorMediaSeconds

    val videoIdentity: SceneVideoIdentity
        get() = request.videoIdentity

    override fun close() = request.close()

    override fun toString(): String {
        return "CapturedOcrFrame(" +
            "mediaTimeSeconds=$mediaTimeSeconds, " +
            "videoIdentity=$videoIdentity, " +
            "bitmap=<redacted>)"
    }
}

internal interface SceneMpvPropertyReader {
    fun double(name: String): Double?

    fun string(name: String): String?

    fun boolean(name: String): Boolean?

    fun int(name: String): Int?
}

internal object DirectSceneMpvPropertyReader : SceneMpvPropertyReader {
    override fun double(name: String): Double? = runCatching {
        MPVLib.getPropertyDouble(name)
    }.getOrNull()

    override fun string(name: String): String? = runCatching {
        MPVLib.getPropertyString(name)
    }.getOrNull()

    override fun boolean(name: String): Boolean? = runCatching {
        MPVLib.getPropertyBoolean(name)
    }.getOrNull()

    override fun int(name: String): Int? = runCatching {
        MPVLib.getPropertyInt(name)
    }.getOrNull()
}

internal data class SceneMpvSnapshot(
    val anchorMediaSeconds: Double,
    val mediaDurationSeconds: Double?,
    val subtitleStartSeconds: Double?,
    val subtitleEndSeconds: Double?,
    val subtitleSpeed: Double,
    val subtitleDelaySeconds: Double,
    val playableValue: String?,
    val selectedAudioId: Int?,
    val selectedAudioFfmpegIndex: Int?,
    val selectedExternalAudioValue: String?,
    val selectedAudioIsExternal: Boolean,
    val seekable: Boolean?,
)

/**
 * Reads every player property needed by a request through one injectable boundary.
 *
 * mpv has no multi-property transaction, so callers pause playback, take this immutable value,
 * capture the fallback, take a second value, and reject the capture if the stable fields changed.
 */
internal class SceneMpvSnapshotReader(
    private val properties: SceneMpvPropertyReader = DirectSceneMpvPropertyReader,
) {
    fun read(): SceneMpvSnapshot? {
        val anchor = properties.double("time-pos")
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
        val duration = properties.double("duration")
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val speed = properties.double("sub-speed")
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        val delay = properties.double("sub-delay")
            ?.takeIf(Double::isFinite)
            ?: return null

        val selectedAudio = selectedAudio()
        return SceneMpvSnapshot(
            anchorMediaSeconds = anchor,
            mediaDurationSeconds = duration,
            subtitleStartSeconds = properties.double("sub-start/full"),
            subtitleEndSeconds = properties.double("sub-end/full"),
            subtitleSpeed = speed,
            subtitleDelaySeconds = delay,
            playableValue = properties.string("path")?.takeIf(String::isNotBlank),
            selectedAudioId = selectedAudio.id,
            selectedAudioFfmpegIndex = selectedAudio.ffmpegIndex,
            selectedExternalAudioValue = selectedAudio.externalValue,
            selectedAudioIsExternal = selectedAudio.isExternal,
            seekable = properties.boolean("seekable"),
        )
    }

    private fun selectedAudio(): SelectedAudioSnapshot {
        val selectedAudioId = properties.string("aid")?.toIntOrNull()
            ?: properties.int("aid")
            ?: return SelectedAudioSnapshot()
        val trackCount = properties.int("track-list/count")
            ?: return SelectedAudioSnapshot(id = selectedAudioId)
        val index = (0 until trackCount)
            .firstOrNull { index ->
                properties.string("track-list/$index/type") == "audio" &&
                    properties.int("track-list/$index/id") == selectedAudioId
            }
            ?: return SelectedAudioSnapshot(id = selectedAudioId)
        val externalValue = properties.string("track-list/$index/external-filename")
            ?.takeIf(String::isNotBlank)
        return SelectedAudioSnapshot(
            id = selectedAudioId,
            ffmpegIndex = properties.int("track-list/$index/ff-index")?.takeIf { it >= 0 },
            externalValue = externalValue,
            isExternal = properties.boolean("track-list/$index/external") == true ||
                externalValue != null,
        )
    }

    private data class SelectedAudioSnapshot(
        val id: Int? = null,
        val ffmpegIndex: Int? = null,
        val externalValue: String? = null,
        val isExternal: Boolean = false,
    )
}

internal class SceneCaptureRequestFactory(
    private val mpvSnapshotReader: SceneMpvSnapshotReader = SceneMpvSnapshotReader(),
) {
    suspend fun captureSubtitle(
        videoSnapshot: (SceneMpvSnapshot) -> SceneVideoInputSnapshot?,
        parsedSubtitleCandidates: List<SceneRangeCandidate>,
        playbackFallback: SceneRangeCandidate?,
        captureFallback: suspend () -> Bitmap?,
        animationLimitSeconds: Double = SCENE_MAX_ANIMATION_DURATION_SECONDS,
        audioLimitSeconds: Double = SCENE_MAX_AUDIO_DURATION_SECONDS,
    ): SceneCaptureRequest? {
        return capture(
            videoSnapshot = videoSnapshot,
            captureFallback = captureFallback,
            animationLimitSeconds = animationLimitSeconds,
            audioLimitSeconds = audioLimitSeconds,
        ) { mpv ->
            val timingSnapshot = mpv.toTimingSnapshot()
            val mpvCandidate = SceneRangeEndpointPair(
                startSeconds = mpv.subtitleStartSeconds,
                endSeconds = mpv.subtitleEndSeconds,
                clockDomain = SceneClockDomain.SUBTITLE,
                provenance = SceneRangeProvenance.MPV_SUBTITLE_PROPERTIES,
            ).completeCandidateOrNull()
            val parsedCandidates = parsedSubtitleCandidates.filter {
                it.clockDomain == SceneClockDomain.SUBTITLE &&
                    it.provenance == SceneRangeProvenance.PARSED_SUBTITLE_CUE
            }
            val fallback = playbackFallback
                ?.takeIf { it.clockDomain == SceneClockDomain.MEDIA }
                ?: playbackFallbackFor(mpv)
            val resolved = SceneTimingResolver.resolve(
                snapshot = timingSnapshot,
                mpvSubtitleRange = mpvCandidate?.let {
                    SceneRangeEndpointPair(
                        startSeconds = it.startSeconds,
                        endSeconds = it.endSeconds,
                        clockDomain = it.clockDomain,
                        provenance = it.provenance,
                    )
                },
                parsedSubtitleRanges = parsedCandidates,
                playbackFallback = fallback,
                animationCapSeconds = animationLimitSeconds,
                audioCapSeconds = audioLimitSeconds,
            )
            ResolvedCaptureTiming(
                snapshot = timingSnapshot,
                resolved = resolved,
                sourceCandidate = resolved?.let { resolvedTiming ->
                    (listOfNotNull(mpvCandidate) + parsedCandidates + fallback)
                        .firstOrNull { it.resolvesTo(resolvedTiming, timingSnapshot) }
                },
            )
        }
    }

    suspend fun captureOcr(
        videoSnapshot: (SceneMpvSnapshot) -> SceneVideoInputSnapshot?,
        paddingSeconds: Double,
        captureFallback: suspend () -> Bitmap?,
        animationLimitSeconds: Double = SCENE_MAX_ANIMATION_DURATION_SECONDS,
        audioLimitSeconds: Double = SCENE_MAX_AUDIO_DURATION_SECONDS,
    ): CapturedOcrFrame? {
        val request = capture(
            videoSnapshot = videoSnapshot,
            captureFallback = captureFallback,
            animationLimitSeconds = animationLimitSeconds,
            audioLimitSeconds = audioLimitSeconds,
        ) { mpv ->
            val timingSnapshot = mpv.toTimingSnapshot()
            val ocrCandidate = SceneTimingResolver.ocrRange(
                anchorSeconds = mpv.anchorMediaSeconds,
                paddingSeconds = paddingSeconds,
                mediaDurationSeconds = mpv.mediaDurationSeconds,
            )
            val resolved = ocrCandidate?.let {
                SceneTimingResolver.resolve(
                    snapshot = timingSnapshot,
                    mpvSubtitleRange = null,
                    parsedSubtitleRanges = emptyList(),
                    playbackFallback = it,
                    animationCapSeconds = animationLimitSeconds,
                    audioCapSeconds = audioLimitSeconds,
                )
            }
            ResolvedCaptureTiming(
                snapshot = timingSnapshot,
                resolved = resolved,
                sourceCandidate = resolved?.let { ocrCandidate },
            )
        } ?: return null
        return CapturedOcrFrame(request)
    }

    private suspend fun capture(
        videoSnapshot: (SceneMpvSnapshot) -> SceneVideoInputSnapshot?,
        captureFallback: suspend () -> Bitmap?,
        animationLimitSeconds: Double,
        audioLimitSeconds: Double,
        resolveTiming: (SceneMpvSnapshot) -> ResolvedCaptureTiming,
    ): SceneCaptureRequest? {
        val beforeMpv = mpvSnapshotReader.read() ?: return null
        val beforeVideo = videoSnapshot(beforeMpv) ?: return null
        val fallback = captureFallback() ?: return null
        var transferred = false

        try {
            val afterMpv = mpvSnapshotReader.read() ?: return null
            val afterVideo = videoSnapshot(afterMpv) ?: return null
            if (!sameCaptureState(beforeMpv, afterMpv) || beforeVideo != afterVideo) return null

            val resolution = SceneVideoInputResolver.resolve(beforeVideo)
            val identity = when (resolution) {
                is SceneVideoInputResolution.Supported -> resolution.input.identity
                is SceneVideoInputResolution.Unsupported -> beforeVideo.fallbackIdentity()
            }
            val timing = resolveTiming(beforeMpv)
            val request = SceneCaptureRequest(
                videoIdentity = identity,
                videoInput = resolution,
                anchorMediaSeconds = beforeMpv.anchorMediaSeconds,
                sourceRangeCandidate = timing.sourceCandidate,
                timingSnapshot = timing.snapshot,
                resolvedTiming = timing.resolved,
                animationLimitSeconds = animationLimitSeconds,
                audioLimitSeconds = audioLimitSeconds,
                selectedExternalAudioRequired = beforeMpv.selectedAudioIsExternal,
                selectedAudioFfmpegIndex = beforeMpv.selectedAudioFfmpegIndex,
                stillFallback = OwnedBitmap(fallback),
            )
            transferred = true
            return request
        } finally {
            if (!transferred && !fallback.isRecycled) {
                fallback.recycle()
            }
        }
    }

    private fun sameCaptureState(before: SceneMpvSnapshot, after: SceneMpvSnapshot): Boolean {
        return abs(before.anchorMediaSeconds - after.anchorMediaSeconds) <= SCENE_ANCHOR_TOLERANCE_SECONDS &&
            nullableDoubleEquals(before.mediaDurationSeconds, after.mediaDurationSeconds) &&
            nullableDoubleEquals(before.subtitleStartSeconds, after.subtitleStartSeconds) &&
            nullableDoubleEquals(before.subtitleEndSeconds, after.subtitleEndSeconds) &&
            nullableDoubleEquals(before.subtitleSpeed, after.subtitleSpeed) &&
            nullableDoubleEquals(before.subtitleDelaySeconds, after.subtitleDelaySeconds) &&
            before.playableValue == after.playableValue &&
            before.selectedAudioId == after.selectedAudioId &&
            before.selectedAudioFfmpegIndex == after.selectedAudioFfmpegIndex &&
            before.selectedExternalAudioValue == after.selectedExternalAudioValue &&
            before.selectedAudioIsExternal == after.selectedAudioIsExternal &&
            before.seekable == after.seekable
    }

    private fun nullableDoubleEquals(first: Double?, second: Double?): Boolean {
        if (first == null || second == null) return first == second
        if (!first.isFinite() || !second.isFinite()) return first.toBits() == second.toBits()
        return abs(first - second) <= DOUBLE_SNAPSHOT_TOLERANCE_SECONDS
    }

    private fun SceneMpvSnapshot.toTimingSnapshot(): SceneTimingSnapshot {
        return SceneTimingSnapshot(
            anchorSeconds = anchorMediaSeconds,
            subtitleSpeed = subtitleSpeed,
            subtitleDelaySeconds = subtitleDelaySeconds,
            mediaDurationSeconds = mediaDurationSeconds,
        )
    }

    private fun SceneRangeCandidate.resolvesTo(
        resolved: SceneResolvedTiming,
        snapshot: SceneTimingSnapshot,
    ): Boolean {
        if (provenance != resolved.provenance) return false
        val mediaStart = when (clockDomain) {
            SceneClockDomain.SUBTITLE -> startSeconds * snapshot.subtitleSpeed + snapshot.subtitleDelaySeconds
            SceneClockDomain.MEDIA -> startSeconds
        }
        val mediaEnd = when (clockDomain) {
            SceneClockDomain.SUBTITLE -> endSeconds * snapshot.subtitleSpeed + snapshot.subtitleDelaySeconds
            SceneClockDomain.MEDIA -> endSeconds
        }
        return abs(mediaStart - resolved.sourceRange.startSeconds) <= DOUBLE_SNAPSHOT_TOLERANCE_SECONDS &&
            abs(mediaEnd - resolved.sourceRange.endSeconds) <= DOUBLE_SNAPSHOT_TOLERANCE_SECONDS
    }

    private fun playbackFallbackFor(snapshot: SceneMpvSnapshot): SceneRangeCandidate {
        val duration = snapshot.mediaDurationSeconds
        val range = if (duration == null) {
            val start = max(0.0, snapshot.anchorMediaSeconds - DEFAULT_PLAYBACK_FALLBACK_SECONDS / 2.0)
            start to max(snapshot.anchorMediaSeconds, start + DEFAULT_PLAYBACK_FALLBACK_SECONDS)
        } else {
            val targetDuration = min(DEFAULT_PLAYBACK_FALLBACK_SECONDS, duration)
            val start = (snapshot.anchorMediaSeconds - targetDuration / 2.0)
                .coerceIn(0.0, max(0.0, duration - targetDuration))
            start to (start + targetDuration)
        }
        return SceneRangeCandidate(
            startSeconds = range.first,
            endSeconds = range.second,
            clockDomain = SceneClockDomain.MEDIA,
            provenance = SceneRangeProvenance.PLAYBACK_POSITION,
        )
    }

    private fun SceneVideoInputSnapshot.fallbackIdentity(): SceneVideoIdentity {
        val identitySource = listOf(
            episodeId?.toString().orEmpty(),
            sourceId?.toString().orEmpty(),
            quality,
            originalVideoValue,
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identitySource.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return SceneVideoIdentity(
            episodeId = episodeId,
            sourceId = sourceId,
            quality = quality,
            inputDigest = digest,
        )
    }

    private companion object {
        const val DEFAULT_PLAYBACK_FALLBACK_SECONDS = 1.0
        const val DOUBLE_SNAPSHOT_TOLERANCE_SECONDS = 0.000_001
    }

    private data class ResolvedCaptureTiming(
        val snapshot: SceneTimingSnapshot,
        val resolved: SceneResolvedTiming?,
        val sourceCandidate: SceneRangeCandidate?,
    )
}
