# Immersion recorder

The Phase 3 recorder is the only supported write boundary for live immersion
capture. Source adapters submit media-neutral commands and never manipulate SQL
rows, event sequences, session counters, heartbeats, or retry identities.

## Lifecycle

The explicit state machine is:

`NOT_STARTED -> STARTING -> ACTIVE -> FINALIZING -> FINALIZED`

`ACTIVE` may move through `PAUSED`, `IDLE`, or `BACKGROUND`. Any live state may
move to `FAILED` after an unrecoverable persistence error. Terminal states must
reset before another start. Title changes finalize and flush the prior identity
before allocating a new session.

The recorder allocates the session UUID, start-event UUID, and stable device ID
before its first database call. It then persists the title, session row, and
sequence-1 start event within a short start timeout. A partial start is safe:
the active row retains its heartbeat boundary and startup recovery later marks
it abandoned.

Global incognito is checked both when a session starts and under the same lock
used before queue insertion. Enabling incognito raises that queue barrier first,
then flushes and finalizes already-accepted commands. An incognito start creates
no title, session, event, or queued command.

## Time and event order

Wall time is stored for display and local-date attribution. In-process active
duration uses a monotonic clock, so a wall-clock rollback cannot create negative
or reordered duration. Sequence numbers, not timestamps, define event order.

Only `ACTIVE` accrues time. Pause, application background, idle beyond the
configured timeout, and adapter-declared buffering pauses are excluded. The
recorder splits an active interval at each local midnight using the zone that
was active at the beginning of that interval. A timezone change applies to the
next interval.

Periodic heartbeats are ordinary idempotent lifecycle events and advance the
session's last durable active boundary. Process-death recovery ends an old
active session at that boundary and derives elapsed duration from it. Heartbeat
compaction runs later as bounded retention work, never in the finalization
transaction. It combines at least three raw heartbeats in the same five-minute,
local-date, and timezone-offset window, preserves their total active duration,
writes tombstones for every replaced event, and marks affected rollups dirty.
Compacted events carry a metadata version that excludes them from later
compaction passes, so retries and delayed archive merges remain idempotent.

## Queue and failure isolation

The worker channel has a finite physical capacity. Live commands reserve space
against the smaller configured logical capacity, receive UUIDs and monotonically
increasing sequences under a short lock, and are enqueued without database work.
Lifecycle boundaries have a small reserved allowance so pause and finalization
can still drain a saturated live queue.

The worker writes ordered batches in one SQLDelight transaction. A database-busy
response retries a bounded number of times with the exact same event IDs and
sequences. Queue saturation drops the whole command atomically, records a typed
diagnostic, and requests one asynchronous urgent flush. Database unavailability
ends capture, attempts an abandoned finalization, and leaves the legacy tracker
available for the later dual-write phase.

Every successful batch invokes the rollup scheduling hook and increments typed
rollup lag. Finalization compares persisted session counters with the recorder's
accepted-event totals; a mismatch records a repair timestamp and invokes the
repair scheduling hook.

Diagnostics contain only bounded counts, durations, typed error codes, and
timestamps. They never contain a title, source text, dictionary query, locator,
or raw command payload.

## Shadow verification

`ImmersionShadowMonitor` compares hidden session or day totals with the current
trackers. Novel net progress is exact. Duplicate session and event IDs have no
tolerance. Reading-time drift is exact by default and can be widened only with
a typed, documented policy difference for background, idle, or buffering
exclusion.

The unit performance fixture warms the recorder and measures 1,000 enqueue
calls. Its initial representative-VM budget is p95 below 2 ms; database work and
flush waits are excluded because they never run on the enqueue path.
