# Immersion video capture

Video immersion capture is driven only by live player callbacks. Parsing, downloading, or
preloading a subtitle file never emits an exposure.

## Session and active-time boundaries

- A session is scoped to one anime title, dictionary profile, and episode/media ID.
- Playback time is active only while the activity is foregrounded, MPV is playing, and seeking is
  inactive.
- Buffering remains active for the configured grace period. If buffering outlasts the grace,
  capture pauses at that boundary and resumes when buffering ends.
- Explicit pause, background, episode transition, incognito, and finalization use the shared
  recorder state machine. The recorder's inactivity timeout still caps silent callback gaps.
- A paused subtitle/OCR provenance exposure may be persisted with zero active time. Background
  exposure remains rejected.

## Subtitle identity and replay

Subtitle identity is:

```text
(video title ID, episode/media ID, primary-or-secondary track ID, cue index,
 cue start ms, cue end ms, NFC text hash)
```

An exposure is emitted when a cue is active and visible. Repeated observer callbacks for the
currently active cue are ignored. Re-entering the same cue adds gross exposure only after a
meaningful seek or after playback has moved at least one second outside its prior observation.
Unique-source exposure remains globally deduplicated.

Primary and secondary subtitle roles are retained separately. Only primary text whose track
language matches the configured learning language contributes character totals. A missing track
language is treated as partial capability and allowed to contribute because rejecting an unlabeled
learning track would create a false zero. Secondary and known non-learning-language cues retain
source provenance with zero primary metrics.

Track changes and subtitle visibility changes are explicit events. Identical text at different cue
timestamps is distinct source material.

## Video OCR and mining context

Video OCR identity is:

```text
(video title ID, episode/media ID, timestamp bucket, stable frame identity,
 region ID, OCR engine/version, NFC text hash)
```

OCR is emitted only after a captured frame has been recognized and its overlay is visible. Stable
region text observed in adjacent frames within two seconds reuses the original frame anchor and
does not create another source unit. Changed text creates a new source. Failed OCR records
unavailable coverage, never a measured zero.

The adapter exposes a lightweight media context containing anime, episode/media ID, timestamp,
source unit, cue bounds, or frame identity. Screenshot and audio mining can reference this context;
large image, audio, and video bytes are never copied into the statistics database.

## Progress, completion, and unsupported playback

Position and duration produce an episode progress snapshot and estimated remaining duration.
Crossing 95% records episode completion once. A known final episode can also record title
completion once.

External players do not expose reliable playback callbacks to Komikku. Their handoff is visibly
reported as unsupported and no activity, duration, subtitle, or OCR exposure is estimated.
