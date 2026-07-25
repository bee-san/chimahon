# ADR 0001: Event-backed immersion statistics contract

- Status: Accepted for staged implementation
- Date: 2026-07-25
- Owners: Chimahon statistics foundation
- Plan: `plans/stats.md`, Sections 5, 6, and 9

## Context

Chimahon currently stores novel, manga, and Anki totals in independent JSON aggregates. Those files can answer how much was read on a day, but they cannot safely identify a session, distinguish a reread from forward progress, link a lookup to its source, or merge independent activity from two devices.

The old `charactersRead` fields also have different meanings. Novel tracking measures a signed change in the estimated document position. Manga tracking counts OCR text with Kotlin UTF-16 length and stores zero when OCR is unavailable. Neither representation can be renamed to a richer metric without changing its meaning.

## Decision

### Source of truth and derived state

Post-migration activity will be recorded as caller-identified, append-only events grouped into sessions. SQLDelight will persist the events, source units, and later word/character occurrences. Daily, title, session, and lifetime rollups are rebuildable caches, never the only source of truth.

Session IDs, event IDs, title IDs, source-unit IDs, and Anki-operation IDs are canonical UUIDs created before a retryable boundary. Each event also has a monotonic per-session sequence. The same ID with the same payload is idempotent; the same ID with a different payload is corruption and must not be silently accepted.

The initial contract versions are:

| Contract | Version |
|---|---:|
| Immersion schema | 1 |
| Capture commands | 1 |
| Unicode normalization/count policy | 1 |
| Tokenizer/index | 1 |
| Rollups | 1 |

They are encoded in `ImmersionStatsVersions`.

### Character and word metrics

All character counts iterate Unicode code points. The version 1 default count policy retains Unicode letters and numbers. It excludes whitespace, punctuation, control and formatting characters, symbols, combining marks, and variation selectors. Source identity retains a normalized-text hash and policy version so later policies can rebuild derived values.

The product exposes separate values rather than a generic "characters" total:

| Metric | Formula | Domain representation |
|---|---|---|
| Gross characters | Sum of countable characters in qualifying exposure events, including intentional rereads/replays | `CharacterVolume.gross` |
| Unique-source characters | Countable characters from each stable source unit once in the requested uniqueness scope | `CharacterVolume.uniqueSource` |
| Net progress | Signed forward position delta minus backward position delta | `CharacterVolume.netProgress` |
| Distinct characters | Number of unique Unicode scalar values encountered in the scope | `ReadingMetrics.distinctCharacters` |
| New characters | Distinct characters whose global first-seen event is in the scope | `ReadingMetrics.newCharacters` |
| Words encountered | Sum of lexical occurrences exposed | `ReadingMetrics.wordsEncountered` |
| Unique words | Distinct `(language, normalized headword, normalized reading)` identities in scope | `ReadingMetrics.uniqueWords` |
| New words | Word identities whose global first-seen event is in scope | `ReadingMetrics.newWords` |
| Active time | Foreground consumable time excluding pause, idle, excessive buffering, and background | `ReadingMetrics.activeTime` |
| Reading speed | Selected character value divided by active hours | `ReadingMetrics.readingSpeedPerHour` |
| Lookup rate | Successful explicit user lookups / gross characters × 10,000 | `ReadingMetrics.lookupRatePerTenThousandGrossCharacters` |
| Mining rate | Newly created Anki cards / gross characters × 10,000 | `ReadingMetrics.miningRatePerTenThousandGrossCharacters` |
| Novelty rate | Globally new words / unique words in scope | `ReadingMetrics.noveltyRate` |
| Vocabulary density | Unique words / gross characters × 10,000 | `ReadingMetrics.vocabularyDensityPerTenThousandGrossCharacters` |
| Character coverage | Encountered target-script characters represented in the selected Anki inventory / encountered target-script characters | `ReadingMetrics.characterCoverage` |

A rate with a zero or non-positive denominator is unavailable (`null`), not zero. Anki updates are represented separately and never contribute to the created-card mining rate.

### Stable source identities

Source locators use length-prefixed canonical identity parts so embedded separators cannot collide. They never include raw text:

- Novel: source/document/section, logical range, normalized text hash, parser revision.
- Manga: manga/chapter/page plus optional OCR engine/revision/block and normalized text hash.
- Subtitle: title/episode/track/cue index/start/end and normalized text hash.
- Video OCR: title/episode/timestamp bucket/frame/region/engine/revision and normalized text hash.

The canonical locator is hashed into a source-unit identity in the persistence phase. A repeated UI callback for the same exposure command reuses its event ID. A genuine source exit and re-entry may create a new gross exposure while retaining the same source-unit identity.

### Session lifecycle and time

The state machine is encoded by `ImmersionSessionStateMachine`:

```text
NOT_STARTED -> ACTIVE
ACTIVE -> PAUSED | IDLE | BACKGROUND | FINALIZED
PAUSED -> ACTIVE | BACKGROUND | FINALIZED
IDLE -> ACTIVE | BACKGROUND | FINALIZED
BACKGROUND -> PAUSED -> ACTIVE
any live state -> FINALIZED
```

Foreground restoration is deliberately paused until the media adapter confirms consumption has resumed. Finalization is idempotent. Other illegal transitions fail explicitly.

The initial reader idle timeout is 120 seconds. Video buffering has a 5-second grace before becoming inactive. Both are preferences. Novel net progress preserves signed document-position deltas; gross exposure remains separate. Durations use monotonic time while a process is alive and are split at the stored local calendar boundary.

### Privacy and retention

Incognito is a hard barrier evaluated before queue insertion. It suppresses sessions, events, raw text, hashes, words, characters, lookups, Anki linkage, recents, and diagnostic payloads. Per-title exclusion and a disabled capture flag use the same pre-queue policy.

Raw source retention is independent from aggregate capture. The initial product choice is `UNTIL_DELETED`, but capture remains off and raw text cannot be written until a later onboarding flow gives clear disclosure and an immediate `NEVER` choice. Raw text is not logged. Diagnostics contain only bounded counts, typed error codes, and timestamps.

### Legacy data

Legacy JSON is imported later as visibly synthetic aggregate data with `LEGACY_AMBIGUOUS` quality. It may contribute its known time, character, completion, and combined card totals. It must not fabricate source units, events, real session times, vocabulary, characters, lookups, or Anki operations. Source files remain intact through the dual-write and rollback period.

### Filtering and capability state

`StatsFilter` is a serializable, Compose-independent value object shared by all future query families. It carries date/comparison ranges, media, profile, language, title, legacy inclusion, character basis, replay inclusion, maturity, and provenance filters.

Missing OCR, tokenization, Anki, or source-text capability is represented by `CapabilityState` and `ProvenanceState`. It is not represented as a measured zero. A failed Anki refresh preserves the last valid snapshot and reports stale capability.

## Alternatives rejected

- Extending the JSON daily aggregates: cannot provide stable event identity, provenance, correct multi-device addition, or deterministic rebuilds.
- Storing only rollups: makes repair, deletion, reindexing, and metric-version changes unverifiable.
- One character counter: hides incompatible gross, unique, and position semantics and creates misleading speed comparisons.
- Counting Kotlin `String.length`: counts supplementary Unicode characters as two UTF-16 code units.
- Instrumenting `DictionaryRepository`: counts prefetch, tokenization, and internal queries rather than explicit user intent.
- Defaulting missing OCR or Anki to zero/unknown: converts a capability failure into false learning data.

## Consequences

- Persistence and capture can be added behind flags without changing the current Stats UI.
- More storage and versioning are required, and raw-text storage needs explicit user controls.
- Every adapter must provide stable identity and lifecycle information rather than writing database rows.
- Rollups, deletion, sync, and backup have deterministic sources from which they can rebuild.
- The old stores remain authoritative until shadow reconciliation and staged retirement pass.
