# Immersion statistics Phase 0 handoff

## Scope delivered

- Canonical enums and validated values for media, metrics, lifecycle/status, events, sources, Anki operations, maturity, capability, provenance, IDs, language tags, Unicode scalar values, dates, durations, and counters.
- Serializable, Compose-independent `StatsFilter`.
- Version 1 Unicode code-point counting policy and script inventory.
- Explicit session lifecycle state machine and pre-queue privacy/capture policy.
- Stable source locator contracts for novel, manga/OCR, subtitle, and video OCR.
- Reading metric formulas with unavailable zero-denominator behavior.
- Rollout flags, lifecycle policy preferences, retention/net-progress decisions, and privacy-safe local diagnostics state.
- Legacy novel/manga/Anki JSON fixtures covering current, older minimal, missing required, and corrupt forms.
- ADR and current writer/reader boundary ownership map.

Production stats behavior is unchanged. Every immersion rollout flag defaults off, no persistence schema exists yet, and the current JSON stores remain authoritative.

## Contract versions

| Contract | Before | After |
|---|---:|---:|
| Immersion schema target | none | 1 |
| Capture | none | 1 |
| Normalization/counting | none | 1 |
| Tokenizer/index | none | 1 |
| Rollup | none | 1 |

## Migration and rollback

There is no database migration and no current-store mutation in Phase 0. Rollback is removal of the unused contracts, preferences, diagnostics registration, fixtures, and documentation. Existing JSON files are neither read differently nor rewritten.

## Verification

Completed on 2026-07-25 with JDK 21 and a user-owned Android SDK:

- `./gradlew :domain:testDebugUnitTest :chimahon:testDebugUnitTest`: passed.
  - Domain: 84 tests, 0 skipped, 0 failed.
  - Chimahon: 141 tests, 4 pre-existing optional-corpus skips, 0 failed.
  - Legacy fixture coverage: 5 tests spanning novel, manga, and Anki normal,
    older-minimal, missing-required, and corrupt inputs.
- `./gradlew spotlessApply`: passed.
- `./gradlew spotlessCheck`: passed.
- `./gradlew assembleDebug`: passed; universal and four ABI-specific debug APKs
  were produced.
- Non-base locale diff audit: passed; no string or plurals entry was added under
  `i18n-kmk/src`, `i18n/src`, or `i18n-sy/src`.

The first APK build required initializing the repository-pinned `hoshidicts`
submodule and its nested submodules. The build emits existing Kotlin 2.4/R8
metadata and deprecation warnings, but no compilation, dexing, or packaging
failure.

## Manual validation

No device, live reader/player, or AnkiDroid behavior is changed or claimed in this phase.

## Next phase

Phase 1 adds the SQLDelight schema, additive migration, repository contracts/implementation, DI, and transaction/idempotency tests. It must not activate capture or replace the current UI.
