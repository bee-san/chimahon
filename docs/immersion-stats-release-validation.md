# Immersion statistics release validation

This is the machine-readable publication gate for the `v2.5.0` statistics
candidate. The candidate remains an opt-in preview with event capture and
indexing in shadow mode, legacy writers enabled, and rollback import
compatibility retained.

The gate does not authorize a legacy read-only transition or legacy-code
retirement. Those remain later release-boundary changes under
`immersion-stats-legacy-retirement-runbook.md`.

Every matrix entry starts false. A reviewer may set an entry true only when an
evidence object records:

- the exact app commit and build variant;
- device or emulator model and API level;
- test date and commands or workflow run;
- measured results where the requirement is quantitative;
- artifact paths and SHA-256 values for reports or screenshots;
- reviewer identity and an explicit pass decision.

`releaseGate` may become `verified` only when every matrix entry is true and
all referenced evidence is available. Host unit tests, green compilation, or
an unchecked manual assertion do not substitute for representative Android
evidence.
