# Immersion lookup and Anki instrumentation

Lookup telemetry is attached to explicit UI intent, not to dictionary engine calls. The shared
`LookupTelemetry` boundary allocates one token per popup/tab request. Recursive selections receive
their own tokens, while repository warm-up, deinflection, media loading, duplicate checks, and
prefetches remain uninstrumented.

The token snapshots the active immersion session and most recently retained source unit. Completion
records exactly one of `SUCCESS`, `EMPTY`, `CANCELLED`, or `FAILED`. Lookup totals count only
`SUCCESS`; other statuses remain available for diagnostics and provenance but are excluded from the
primary counter and lookup-rate numerator. Queries are NFC-normalized and always hashed. Raw query
text is stored only when the configured raw-text retention policy permits it.

Anki operations allocate a stable UUID before any AnkiDroid permission, lookup, update, or insert
call. Results distinguish:

- a new note (`CREATE` + `SUCCESS`);
- an overwrite (`UPDATE` + `SUCCESS`);
- duplicate rejection (`DUPLICATE`);
- opening an existing note (`OPEN`);
- permission, configuration, and external failures.

Only successful create and update operations increment their respective counters. Opening,
previewing, duplicate rejection, and failures do not. The legacy daily JSON count remains in place
during migration, but it does not define the new counters.

Successful external operations enter a durable local repair queue before event enqueue. The queue
is acknowledged only after the recorder commits the corresponding event. A rejected, saturated, or
failed local write therefore retains the same operation UUID. Startup repair persists such an
operation as explicitly unlinked when its original session can no longer be reopened; it never
fabricates a session event or source relationship. Incognito and absent-session contexts suppress
interaction persistence; invoking Anki remains an explicit user action and is otherwise unchanged.

Lookup and Anki events travel through the same serialized recorder queue as exposures. This keeps
session sequence numbers atomic and permits lookup/mining while the media adapter is paused for its
popup without adding active reading or playback time.
