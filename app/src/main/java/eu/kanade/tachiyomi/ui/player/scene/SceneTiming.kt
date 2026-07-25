package eu.kanade.tachiyomi.ui.player.scene

import kotlin.math.max
import kotlin.math.min

internal const val SCENE_ANCHOR_TOLERANCE_SECONDS = 0.125
internal const val SCENE_MIN_DURATION_SECONDS = 0.25
internal const val SCENE_MAX_ANIMATION_DURATION_SECONDS = 10.0
internal const val SCENE_MAX_AUDIO_DURATION_SECONDS = 30.0

internal enum class SceneClockDomain {
    SUBTITLE,
    MEDIA,
}

internal enum class SceneRangeProvenance {
    MPV_SUBTITLE_PROPERTIES,
    PARSED_SUBTITLE_CUE,
    PLAYBACK_POSITION,
    OCR_CAPTURE,
}

private val SceneRangeProvenance.expectedClockDomain: SceneClockDomain
    get() = when (this) {
        SceneRangeProvenance.MPV_SUBTITLE_PROPERTIES,
        SceneRangeProvenance.PARSED_SUBTITLE_CUE,
        -> SceneClockDomain.SUBTITLE
        SceneRangeProvenance.PLAYBACK_POSITION,
        SceneRangeProvenance.OCR_CAPTURE,
        -> SceneClockDomain.MEDIA
    }

internal data class SceneRangeCandidate(
    val startSeconds: Double,
    val endSeconds: Double,
    val clockDomain: SceneClockDomain,
    val provenance: SceneRangeProvenance,
) {
    init {
        require(provenance.expectedClockDomain == clockDomain) {
            "$provenance must use ${provenance.expectedClockDomain} clock"
        }
    }
}

internal data class SceneRangeEndpointPair(
    val startSeconds: Double?,
    val endSeconds: Double?,
    val clockDomain: SceneClockDomain,
    val provenance: SceneRangeProvenance,
) {
    init {
        require(provenance.expectedClockDomain == clockDomain) {
            "$provenance must use ${provenance.expectedClockDomain} clock"
        }
    }

    fun completeCandidateOrNull(): SceneRangeCandidate? {
        return SceneRangeCandidate(
            startSeconds = startSeconds ?: return null,
            endSeconds = endSeconds ?: return null,
            clockDomain = clockDomain,
            provenance = provenance,
        )
    }
}

internal data class SceneTimeRange(
    val startSeconds: Double,
    val endSeconds: Double,
) {
    val durationSeconds: Double
        get() = endSeconds - startSeconds

    fun contains(
        positionSeconds: Double,
        toleranceSeconds: Double = SCENE_ANCHOR_TOLERANCE_SECONDS,
    ): Boolean {
        return positionSeconds >= startSeconds - toleranceSeconds &&
            positionSeconds <= endSeconds + toleranceSeconds
    }
}

internal data class SceneTimingSnapshot(
    val anchorSeconds: Double,
    val subtitleSpeed: Double,
    val subtitleDelaySeconds: Double,
    val mediaDurationSeconds: Double?,
)

internal data class SceneResolvedTiming(
    val animationRange: SceneTimeRange,
    val audioRange: SceneTimeRange,
)

internal object SceneTimingResolver {
    fun resolve(
        snapshot: SceneTimingSnapshot,
        mpvSubtitleRange: SceneRangeEndpointPair?,
        parsedSubtitleRanges: List<SceneRangeCandidate>,
        playbackFallback: SceneRangeCandidate,
        animationCapSeconds: Double = SCENE_MAX_ANIMATION_DURATION_SECONDS,
        audioCapSeconds: Double = SCENE_MAX_AUDIO_DURATION_SECONDS,
    ): SceneResolvedTiming? {
        if (!snapshot.isValid()) return null
        if (!animationCapSeconds.isFinite() || animationCapSeconds < SCENE_MIN_DURATION_SECONDS) return null
        if (!audioCapSeconds.isFinite() || audioCapSeconds < SCENE_MIN_DURATION_SECONDS) return null

        val candidates = buildList {
            mpvSubtitleRange?.completeCandidateOrNull()?.let(::add)
            addAll(parsedSubtitleRanges)
            add(playbackFallback)
        }
        val resolvedSource = candidates.firstNotNullOfOrNull { candidate ->
            candidate.toMediaRange(snapshot)?.takeIf {
                it.contains(snapshot.anchorSeconds) &&
                    it.isInsideMedia(snapshot.mediaDurationSeconds)
            }
        } ?: return null

        val animationRange = deriveCappedRange(
            sourceRange = resolvedSource,
            anchorSeconds = snapshot.anchorSeconds,
            capSeconds = animationCapSeconds,
            mediaDurationSeconds = snapshot.mediaDurationSeconds,
        ) ?: return null
        val audioRange = deriveCappedRange(
            sourceRange = resolvedSource,
            anchorSeconds = snapshot.anchorSeconds,
            capSeconds = audioCapSeconds,
            mediaDurationSeconds = snapshot.mediaDurationSeconds,
        ) ?: return null

        return SceneResolvedTiming(
            animationRange = animationRange,
            audioRange = audioRange,
        )
    }

    fun deriveCappedRange(
        sourceRange: SceneTimeRange,
        anchorSeconds: Double,
        capSeconds: Double,
        mediaDurationSeconds: Double?,
    ): SceneTimeRange? {
        if (!sourceRange.isValid() || !anchorSeconds.isFinite()) return null
        if (!capSeconds.isFinite() || capSeconds < SCENE_MIN_DURATION_SECONDS) return null
        if (!sourceRange.contains(anchorSeconds)) return null
        if (mediaDurationSeconds != null) {
            if (!mediaDurationSeconds.isFinite() || mediaDurationSeconds < SCENE_MIN_DURATION_SECONDS) return null
            if (anchorSeconds > mediaDurationSeconds + SCENE_ANCHOR_TOLERANCE_SECONDS) return null
        }

        val sourceDuration = sourceRange.durationSeconds
        val targetDuration = sourceDuration.coerceIn(SCENE_MIN_DURATION_SECONDS, capSeconds)
        val candidateStart = if (sourceDuration > capSeconds) {
            anchorSeconds - capSeconds / 2.0
        } else {
            sourceRange.startSeconds - (targetDuration - sourceDuration) / 2.0
        }

        val start = if (mediaDurationSeconds != null) {
            candidateStart.coerceIn(0.0, max(0.0, mediaDurationSeconds - targetDuration))
        } else {
            max(0.0, candidateStart)
        }
        val result = SceneTimeRange(start, start + targetDuration)
        return result.takeIf { it.contains(anchorSeconds) }
    }

    fun ocrRange(
        anchorSeconds: Double,
        paddingSeconds: Double,
        mediaDurationSeconds: Double?,
    ): SceneRangeCandidate? {
        if (!anchorSeconds.isFinite() || anchorSeconds < 0.0) return null
        if (!paddingSeconds.isFinite() || paddingSeconds <= 0.0) return null
        val start = max(0.0, anchorSeconds - paddingSeconds)
        val end = mediaDurationSeconds
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { min(it, anchorSeconds + paddingSeconds) }
            ?: (anchorSeconds + paddingSeconds)
        if (end <= start) return null
        return SceneRangeCandidate(
            startSeconds = start,
            endSeconds = end,
            clockDomain = SceneClockDomain.MEDIA,
            provenance = SceneRangeProvenance.OCR_CAPTURE,
        )
    }

    private fun SceneTimingSnapshot.isValid(): Boolean {
        return anchorSeconds.isFinite() &&
            anchorSeconds >= 0.0 &&
            subtitleSpeed.isFinite() &&
            subtitleSpeed > 0.0 &&
            subtitleDelaySeconds.isFinite() &&
            (
                mediaDurationSeconds == null ||
                    (mediaDurationSeconds.isFinite() && mediaDurationSeconds >= 0.0)
                )
    }

    private fun SceneRangeCandidate.toMediaRange(snapshot: SceneTimingSnapshot): SceneTimeRange? {
        if (!startSeconds.isFinite() || !endSeconds.isFinite() || endSeconds <= startSeconds) return null
        val range = when (clockDomain) {
            SceneClockDomain.SUBTITLE -> SceneTimeRange(
                startSeconds = startSeconds * snapshot.subtitleSpeed + snapshot.subtitleDelaySeconds,
                endSeconds = endSeconds * snapshot.subtitleSpeed + snapshot.subtitleDelaySeconds,
            )
            SceneClockDomain.MEDIA -> SceneTimeRange(startSeconds, endSeconds)
        }
        return range.takeIf { it.isValid() }
    }

    private fun SceneTimeRange.isValid(): Boolean {
        return startSeconds.isFinite() &&
            endSeconds.isFinite() &&
            startSeconds >= 0.0 &&
            endSeconds > startSeconds
    }

    private fun SceneTimeRange.isInsideMedia(mediaDurationSeconds: Double?): Boolean {
        return mediaDurationSeconds == null ||
            endSeconds <= mediaDurationSeconds + RANGE_BOUND_EPSILON_SECONDS
    }

    private const val RANGE_BOUND_EPSILON_SECONDS = 0.000_001
}
