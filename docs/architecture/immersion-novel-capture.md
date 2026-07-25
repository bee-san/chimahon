# Immersion statistics: novel capture

Status: implemented behind `immersion_stats_capture_enabled`.

## Source identity and visibility

Chimahon captures source exposure from the already-rendered chapter. It does not inspect EPUB
previews, parser output, or offscreen prefetch.

The JavaScript bridge walks rendered text while excluding ruby annotation nodes and non-content
script/style/template nodes. Logical offsets count Unicode code points, not UTF-16 code units.
Chapter text is divided into fixed 64-code-point ranges. A range qualifies only when at least 50%
of its rendered rectangle area intersects the WebView viewport.

The source identity contains:

- the stable Chimahon book identity;
- the chapter href;
- the fixed logical start and exclusive end offsets;
- the NFC-normalized SHA-256 text hash;
- parser revision `1`.

These inputs are independent of pagination, font, theme, writing mode, Compose recomposition, and
rotation. The bridge first finds text nodes intersecting the viewport, then measures only candidate
ranges, so a normal page turn does not lay out every range in a long chapter.

Consecutive reports with the same visible set are reflow/configuration callbacks and do not emit
exposure. A source that leaves and later re-enters emits another gross exposure with an incremented
replay ordinal. Its global unique-source count remains zero after the first durable source identity.

## Metrics and lifecycle

- Gross characters use `DefaultUnicodeCountPolicy`, which counts Unicode letters and numbers and
  excludes whitespace, punctuation, symbols, controls, and combining marks.
- Unique-source characters use the same count but are emitted only when the recorder has not
  previously stored the stable source ID.
- Net progress is a separate signed event based on the existing absolute Chimahon character
  position. Chapter-list, bookmark, internal-link, search, and sync restores reset the baseline
  and emit a seek marker rather than fabricating the distance crossed.
- Chapter and title completion markers are idempotent within a reader session.
- Reader sheets, dictionary popups, the image viewer, manual pause, app background, recorder idle,
  and incognito stop capture. Hidden-position changes update the net baseline without becoming
  progress.
- Session-handle-scoped recorder calls prevent a disposed reader from mutating or finalizing a
  replacement reader.

The existing daily JSON tracker remains wired with its prior manual tracking behavior and is
written unchanged through the rollout.
Raw source text is independently omitted when `RawTextRetention.NEVER` is selected; source hashes
and aggregate counters remain available.

## Shadow reconciliation

`NovelCaptureReconciliationReporter` exposes a bounded, in-memory developer report for both
sessions and local calendar days. It compares legacy active time and net characters with the
finalized event-backed session:

- net character totals must match exactly;
- time may differ only within the configured idle-timeout budget, labelled as the documented
  `IDLE_EXCLUDED` policy difference;
- duplicate session identities have no tolerance;
- reports contain counters and IDs, never source text.

The report also labels sessions as non-comparable if the legacy tracker was manually paused, so a
known policy mismatch is not presented as a successful reconciliation.
