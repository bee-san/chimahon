# Immersion statistics continuation checkpoint

> Superseded by `immersion-stats-completion-audit.md` and
> `immersion-stats-legacy-retirement-runbook.md`. This file is retained as the
> historical handoff before the atomic-start and analytics follow-up commits.

This branch is based on `feat/stats` at
`0ced34e29691cb189663cbf6e170e4cf920f4284`. The fork's `main` and
`feat/stats` refs already contain the complete implementation through that
commit, including the persistence, capture, indexing, analytics, goals,
backup/restore, privacy, diagnostics, and rollout-hardening batches.

The last verified local gate passed SQLDelight generation, the combined
domain/data/app statistics tests, `spotlessApply`, `spotlessCheck`, and
`assembleDebug`. The combined test run contained 783 tests: 778 passed, five
environment-dependent tests were skipped, and none failed.

The next correctness change is atomic session start. At the checkpoint,
`DefaultImmersionRecorder` writes the title, session, and mandatory
`SESSION_STARTED` event through three repository calls. A crash between calls
can therefore leave a partial session start. Replace that flow with one
`ImmersionRecorderRepository` operation and one SQLDelight transaction, while
preserving idempotent retries and typed identity conflicts. Add repository
tests proving all three rows commit together or all roll back, then update the
recorder fake/tests to use the atomic operation.

Do not publish the current APK artifacts publicly. The draft
`stats-v2.5.0-debug` release is private and all five assets had zero downloads
when it was contained. The APKs include the repository's current FFmpegKit/mpv
native artifacts, while `docs/native-source-manifest.json` and
`docs/native-distribution-compliance.md` keep every binary distribution path
blocked until exact corresponding source is recovered or the binaries are
replaced with reproducible, fully documented builds.

Remaining plan work is tracked in `plans/stats.md`. The strongest local gaps
after atomic start are the all-time hourly raw-event scan, complete Overview
filter/widget parity tests, richer title/vocabulary/character workbenches, and
local benchmark/accessibility coverage. Device Anki, playback, TalkBack,
battery, database-growth, migration-upgrade, and end-to-end acceptance
evidence must remain explicitly unvalidated until run on representative
Android hardware.
