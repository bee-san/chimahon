# Chimahon Immersion Statistics — Product and Implementation Plan

Status: proposed
Research date: 2026-07-25
Target branch: `feat/stats`
Target product: Chimahon / Komikku Android app
Primary goal: make every meaningful unit of reading and mining measurable from a day down to a title, session, source line, word, and Unicode character.

## 1. Executive summary

Chimahon already records useful daily totals for novels and manga, plus daily Anki card counts. The current model is intentionally small: JSON aggregates store characters, time, speed, completion, and cards, while the Stats screen joins those totals with library and history data. That is enough for "how much did I read?" but not for:

- Which characters and words did I encounter?
- Where did I encounter them?
- Were they new to me, known, or mature in Anki?
- Which title introduced the most new vocabulary?
- How does reading speed change by title, medium, time, and difficulty?
- What happened inside an individual session?
- Did a lookup or mined card come from a particular line?
- Am I on pace to meet a daily or date-bound goal?
- Can totals survive multi-device sync without double counting?

The target is a local-first immersion analytics system inspired by the strongest parts of:

- [SubMiner](https://github.com/ksyasuda/SubMiner): session/event history, vocabulary and kanji drill-down, title trends, source-line provenance, historical mining, known-word tracking, and operational stats maintenance.
- [SubMiner PR #172](https://github.com/ksyasuda/SubMiner/pull/172): Anki maturity tiers (`new`, `learning`, `young`, and `mature`) layered on reading-aware known-word matching.
- [GameSentenceMiner](https://github.com/bpwhelan/GameSentenceMiner): raw character throughput, reading speed, daily goals, per-title novelty, difficulty comparisons, rich temporal analysis, kanji coverage, and Anki learning-impact reports.

The implementation must not treat all "characters" as the same metric. It will persist and display at least:

1. **Gross character exposure**: valid Unicode code points shown while content is actively consumed, including intentional rereads.
2. **Unique source character exposure**: characters from source units first seen by the user, deduplicated by stable content identity.
3. **Net reading progress**: forward progress through a source after backward navigation is accounted for.
4. **Distinct character knowledge inventory**: unique Han/kanji, kana, Hangul, or other script characters encountered, with counts and provenance.
5. **Lexical vocabulary**: normalized words keyed by language, headword, and reading, with occurrences and Anki state.

The system will be event-backed and rollup-driven:

```text
Novel reader ─┐
Manga + OCR ──┼─> normalized exposure events ─> SQLDelight event store
Video + subs ─┤                                  │
Dictionary ───┤                                  ├─> word/character index
Anki actions ─┘                                  ├─> daily/title/session rollups
                                                   ├─> goals and projections
                                                   └─> Compose query models
```

The existing JSON totals remain readable during migration. They are imported as explicitly marked legacy aggregates and are not used to invent word, character, line, or session history that never existed.

## 2. Product end state

When this plan is complete, a user can:

- Open Stats and filter by date, media type, language/profile, title, and metric semantics.
- See today, streak, time, gross characters, unique-source characters, net progress, speed, lookups, cards, new words, and new characters.
- Compare daily, weekly, monthly, yearly, and all-time activity.
- Inspect every title's time, characters, speed, sessions, vocabulary, novelty, mining rate, and completion trend.
- Open a word or character and see its first encounter, most recent encounter, total count, title distribution, containing words, and source lines.
- Jump from a source occurrence back into a supported reader/player location or mine a card from retained context.
- Inspect a session timeline with pause, lookup, mining, and exposure markers.
- Color vocabulary by Anki maturity without treating temporary Anki query failures as lost knowledge.
- Define daily, date-bound, title-completion, and manual habit goals with pace forecasts and rest-day adjustments.
- Understand correlations between reading and later Anki learning while clearly labeling correlation rather than causation.
- Export or erase raw source text independently from aggregate statistics.
- Back up and restore the database without losing event identity or double counting synced sessions.
- Use incognito reading with a hard guarantee that no stats, source text, lookup, or mining event is written.

## 3. Scope, priorities, and non-goals

### 3.1 Priority ladder

**P0 — trustworthy foundation**

- Metric definitions, event identity, session lifecycle, SQLDelight schema, legacy import, rollups, privacy rules, and capture for novel/manga/video.
- Accurate character counts and active time are more important than chart breadth.

**P1 — core user value**

- Overview, trends, title detail, sessions, vocabulary, character drill-down, source provenance, global filtering, and goal tracking.

**P2 — learning intelligence**

- Anki knownness/maturity, learning-impact views, missing high-frequency character/word reports, historical mining, projections, and coverage targets.

**P3 — operational maturity**

- Multi-device merge, data maintenance, configurable retention, exports, diagnostics, reconciliation, and legacy-store retirement.

### 3.2 Non-goals

- Reproducing either reference app pixel-for-pixel.
- Copying React, Python, or SQL implementation code from the reference projects. Concepts may be reimplemented natively in Kotlin, Compose, and SQLDelight.
- Replacing Anki's scheduler or claiming that reading directly caused retention changes.
- Sending reading text or stats to a hosted analytics service.
- Blocking reading on tokenization, dictionary, Anki, OCR, or rollup failures.
- Pretending legacy aggregate data contains historical source lines or vocabulary.
- Requiring Japanese-specific tokenization for basic character/time statistics in other languages.

## 4. Research baseline

Implementation should record a new research snapshot if the plan is executed much later than these source revisions:

| Project | Revision inspected | Relevant area |
|---|---:|---|
| Chimahon | `efd462b56eb291d6bfbba458eeee78035c441728` | Existing Stats screen, JSON stores, readers, OCR, player, dictionary, Anki, SQLDelight |
| SubMiner `main` | `e223cf9b71b9c21f1cf36de458e6948fe335beec` | Stats dashboard, events, vocabulary, trends, sessions, title detail |
| SubMiner PR #172 | `fb212cd575b3f0e870ff32116d481278e2e7058a` | Maturity-aware known-word cache and rendering |
| GameSentenceMiner `main` | `de3c55914ed1bc447a900fa639c72d96610411cb` | Character stats, goals, title analysis, Anki stats |

### 4.1 Reference feature map

Legend:

- **Adopt**: core target behavior.
- **Adapt**: useful concept requiring mobile/media-aware redesign.
- **Later**: valuable after the foundation is stable.
- **Skip**: not appropriate for Chimahon.

| Area | SubMiner | GameSentenceMiner | Chimahon now | Decision |
|---|---|---|---|---|
| Today summary | Watch time, cards, sessions, episodes | Hours, characters, sessions, speed | Time, characters, speed, cards | **Adopt** one semantic summary |
| Activity streak | Current streak | Current/longest streaks | Current streak | **Adopt** configurable qualifying activity |
| Daily activity chart | 14-day watch time | Time, characters, speed, moving average | Reading duration bars | **Adopt** metric-switchable chart |
| Calendar heatmap | 90-day activity | Reading history heatmap | Limited/no unified heatmap | **Adopt** with metric selector |
| Long-range trends | 7/30/90/365/all; daily/monthly | Daily and cumulative analytics | Date-period filters | **Adopt** |
| Zero-filled dates | Explicit bucket filling | Daily series | Partial | **Adopt** to prevent misleading gaps |
| Cumulative totals | Time, words, new words, cards, episodes, sessions, lookups | Characters, time, words | Aggregate totals | **Adopt** |
| Efficiency metrics | Words/min, cards/hour, lookups/100 words | Characters/hour, cards/10k characters | Characters/minute | **Adopt**, label denominators |
| Time-of-day/day-of-week | Both | Both, including speed | None | **Adopt** after event timestamps exist |
| Title library | Per-title time, sessions, episodes, cards, lookups | Per-title chars, time, speed, status | Per-title time/chars/last read | **Adopt** |
| Title trend visibility | Top/recent/manual series selection | Top-title lists | None | **Adapt** for phone screen limits |
| Title novelty | Vocabulary growth and top words | Globally introduced words, new/10k characters | None | **Adopt** |
| Difficulty analysis | Session language/difficulty fields | Speed by difficulty, tags, genres | No stats difficulty | **Later**, metadata-dependent |
| Session history | Rich grouped session list | Sessions in title/overview | No persistent sessions | **Adopt** |
| Session timeline | Known/unknown ratio, pauses, lookups, cards | Session totals | None | **Adopt** after event capture |
| Session deletion | Session/day/title maintenance | History cleanup tools | JSON file reset only | **Adopt** with rollup rebuild |
| Raw character throughput | Token/word centered | Core metric | Daily characters | **Adopt**, correct Unicode counting |
| Net progress | Not primary | Progress/estimated remaining | Novel tracker uses position deltas | **Adopt** as separate metric |
| Word inventory | Headword/reading/POS/count/rank | Words, POS, occurrence filters | Dictionary lookups only | **Adopt** |
| Word exclusions | Persisted exclusions | Filters/cleanup | None | **Adopt** |
| Word detail | Titles, occurrences, first/last, similar words | Anki state, games, latest lines | None | **Adopt** |
| Historical mining | Word/sentence/audio from occurrence | Word/source tooling | Mining only in current context | **Adapt** where media is retained |
| Character/kanji grid | Frequency grid and detail | Every encountered kanji and source sentences | None | **Adopt**, generalize by script |
| Character coverage | Encountered kanji | Encountered vs Anki, coverage target | None | **Adopt** |
| Known words | Reading-aware known-word cache | Anki word/kanji membership | No stats knownness | **Adopt** |
| Maturity tiers | PR #172: new/learning/young/mature | Mature at 21+ days/interval | None | **Adopt** with configurable threshold |
| Anki activity | Card-mined events | Cards, reviews, review time, retention | Daily created/overwritten counts | **Adapt** to AnkiDroid capabilities |
| Reading/Anki relationship | Session annotations | Same-week and lagged views | None | **Later**, honest capability labels |
| Goals | No dedicated goal engine | Daily, date-bound, completion, manual | None | **Adopt** |
| Pace forecasts | No | 30-day projections, finish-by-date | None | **Adopt** |
| Rest days | No | Reduced weekday targets | None | **Adopt** |
| Cover/metadata | AniList cover/title repair | Release date, genre, tags, difficulty | Library/tracker metadata | **Adapt**, reuse existing metadata |
| Search history | Sentence/headword search | Word/title filters | None | **Adopt** with privacy controls |
| Multi-device sync | Session UUID merge and rollup reconciliation PRs | Local DB | JSON max-per-day merge | **Later**, design identity now |
| Local-first | Yes | Yes | Yes | **Preserve** |
| Data retention | Raw/rollup retention tiers | Cleanup tools | JSON aggregates retained | **Adapt**, raw text separable from totals |
| Background stats server | Desktop HTTP/headless service | Local web server | In-process Android app | **Skip**; use repositories/Flows |

### 4.2 Research entry points and PR signals

Primary source entry points:

- [SubMiner repository](https://github.com/ksyasuda/SubMiner) and its native Stats workspace.
- [SubMiner Vocabulary tab](https://github.com/ksyasuda/SubMiner/blob/main/stats/src/components/vocabulary/VocabularyTab.tsx) for word/kanji summaries, filters, first-seen trends, cross-title words, and detail navigation.
- [SubMiner PR #172](https://github.com/ksyasuda/SubMiner/pull/172) for the current maturity-aware known-word direction.
- [GameSentenceMiner README](https://github.com/bpwhelan/GameSentenceMiner/blob/main/README.md) for documented kanji grid, goals, and history maintenance.
- [GameSentenceMiner general stats template](https://github.com/bpwhelan/GameSentenceMiner/blob/main/GameSentenceMiner/web/templates/stats.html) for volume, speed, novelty, title, difficulty, genre/tag, and temporal reports.
- [GameSentenceMiner Anki stats template](https://github.com/bpwhelan/GameSentenceMiner/blob/main/GameSentenceMiner/web/templates/anki_stats.html) for coverage, retention, lagged learning, and missing-item workbench behavior.

SubMiner's pull-request history also shows the operational sequence behind its current feature set:

| PR(s) | Product/architecture signal for Chimahon |
|---|---|
| [#8](https://github.com/ksyasuda/SubMiner/pull/8), [#19](https://github.com/ksyasuda/SubMiner/pull/19), [#25](https://github.com/ksyasuda/SubMiner/pull/25) | Start with durable session tracking, then build the first dashboard and immersion overview |
| [#50](https://github.com/ksyasuda/SubMiner/pull/50), [#111](https://github.com/ksyasuda/SubMiner/pull/111) | Dashboard iteration must include maintenance and repair UX, not only new charts |
| [#60](https://github.com/ksyasuda/SubMiner/pull/60) | Word exclusions need persistence and consistent denominator behavior |
| [#140](https://github.com/ksyasuda/SubMiner/pull/140) | Long-range trends need series visibility, ranking, calendar correctness, and legible tooltips |
| [#142](https://github.com/ksyasuda/SubMiner/pull/142), [#149](https://github.com/ksyasuda/SubMiner/pull/149), [#172](https://github.com/ksyasuda/SubMiner/pull/172) | Known-word matching should be reading-aware, cached, failure-tolerant, then enriched with maturity |
| [#148](https://github.com/ksyasuda/SubMiner/pull/148) | Title covers/metadata should load without delaying the analytics query |
| [#152](https://github.com/ksyasuda/SubMiner/pull/152), [#160](https://github.com/ksyasuda/SubMiner/pull/160), [#164](https://github.com/ksyasuda/SubMiner/pull/164) | Cross-machine stats require unified types, stable session identity, idempotent merge, and rollup reconciliation |
| [#58](https://github.com/ksyasuda/SubMiner/pull/58), [#144](https://github.com/ksyasuda/SubMiner/pull/144) | Desktop background daemons are useful there but map to Android repositories/work scheduling, not a local HTTP server |

The plan adopts those lessons but orders Chimahon's work around Android lifecycle correctness and three distinct media capture paths before exposing advanced dashboards.

### 4.3 What Chimahon already has

The plan extends rather than discards these current components:

- `chimahon/.../data/Statistics.kt`: novel, manga, and Anki daily aggregate models.
- `BookStorage`, `MangaStatsStorage`, and `AnkiStatsStorage`: JSON persistence and merge behavior.
- `ReaderStatisticsTracker`: novel character-position deltas and active time.
- Chimahon novel `ReaderViewModel`: reader lifecycle and progress.
- Komikku `ReaderViewModel`: manga page transitions and OCR-derived character totals.
- `PlayerViewModel`: timed subtitles, OCR text, playback position, episode/anime context, pause/seek, and Anki audio capture.
- `DictionaryRepository` and `DictionaryTab`: user-facing lookup boundaries.
- `AnkiCardCreator`: successful Anki creation/overwrite boundary.
- `StatsScreenModel`, `StatsScreen`, and `presentation/more/stats/*`: current state and UI.
- SQLDelight in `data/src/main/sqldelight/tachiyomi/` and Komikku-specific DI in `KMKDomainModule.kt`.
- Home stats widget showing today's characters, time, speed, and cards.

### 4.4 Current risks to fix, not preserve

- Daily JSON aggregates have no session UUID, device ID, source locator, or event identity.
- Merge-by-maximum avoids some duplicates but cannot correctly merge independent activity from two devices on the same day.
- Novel "characters read" is effectively net position change; backward navigation can subtract from it.
- Kotlin string length can count UTF-16 code units rather than Unicode code points if used without care.
- Manga OCR tracking can record zero when OCR is absent and currently deduplicates too coarsely by page index rather than a stable `(manga, chapter, page, OCR revision, text hash)` locator.
- Player subtitle and OCR activity is not persisted into Stats.
- Dictionary calls happen at multiple layers; instrumenting every repository call would count prefetch/internal queries as user lookups.
- Anki overwrites are currently counted alongside new cards, which overstates creation.
- There is no single contract for active time, pause, background, idle, reread, replay, incognito, or deleted source data.

## 5. Metric contract

This section is normative. UI, storage, tests, exports, and documentation must use these definitions.

### 5.1 Text units

| Term | Definition |
|---|---|
| Unicode character | One Unicode code point, not one UTF-16 code unit and not one grapheme cluster |
| Countable character | A code point retained by the selected counting policy |
| Script character | A countable code point classified as Han, Hiragana, Katakana, Hangul, Latin, or other |
| Lexical word | A language-normalized token with a stable identity such as `(language, headword, reading)` |
| Source unit | A stable line, subtitle cue, OCR page/block, or novel text range with source locator and normalized hash |
| Exposure | A user-visible presentation of a source unit during active consumption |

Default count policy:

- Exclude whitespace, control, and formatting characters.
- Exclude punctuation from reading-volume metrics by default.
- Retain letters, syllabaries, ideographs, and digits.
- Store enough metadata to recompute under future policies where feasible.
- Use code-point iteration and Unicode script/category classification.

### 5.2 Reading metrics

| Metric | Formula and semantics |
|---|---|
| Gross characters | Sum of countable characters in qualifying exposure events; intentional replay/reread counts again |
| Unique-source characters | Sum of countable characters for source units first exposed globally or within the selected scope |
| Net progress characters | Positive forward source-position change minus backward change, clamped and medium-specific |
| Distinct characters | Count of unique code points encountered in the selected scope |
| New characters | Distinct characters whose global first-seen event falls in the selected scope |
| Words encountered | Sum of lexical word occurrence exposures |
| Unique words | Count of distinct lexical word identities encountered |
| New words | Words whose global first-seen event falls in the selected scope |
| Active time | Foreground consumption time excluding pause, idle, buffering beyond policy, and background |
| Reading speed | Gross countable characters divided by active hours unless UI explicitly selects net or unique-source speed |
| Lookup rate | User-initiated successful lookups per 10,000 gross characters |
| Mining rate | Newly created Anki notes per 10,000 gross characters |
| Novelty rate | Globally new words divided by unique words encountered in the title/scope |
| Vocabulary density | Unique words per 10,000 gross characters |
| Character coverage | Encountered target-script characters represented in the selected Anki inventory divided by encountered target-script characters |

Rules:

- A speed card must always show or disclose whether it uses gross, unique-source, or net characters.
- Rates with a zero denominator render unavailable, not zero.
- "Today" uses the user's current local calendar and stored event timezone/offset for historical bucketing.
- Daily duration crossing midnight is split at the local day boundary.
- Deleted raw text may remove drill-down while preserving counters; UI must label unavailable provenance.

### 5.3 Session contract

A session begins when supported content becomes actively consumable and ends when:

- The reader/player closes.
- The title changes.
- The process is explicitly finalized.
- A configurable inactivity gap causes the next activity to start a new session.

A session can be paused and resumed. Active time stops while:

- The app is backgrounded or the relevant activity loses foreground visibility.
- Video is paused or buffering past the grace threshold.
- Reader input is idle beyond the configured timeout.
- An overlay blocks consumption under the agreed policy.

Session counters are derived from idempotent events. Crashes may leave a session `ABANDONED`; recovery finalizes it using the last trustworthy heartbeat and never assumes activity through the crash.

### 5.4 Reread, replay, and deduplication

- Gross exposure counts intentional rereads/replays.
- Unique-source exposure counts a stable source unit once per requested scope.
- Duplicate Compose recomposition, repeated observer callbacks, configuration changes, and process replay do not create new exposure.
- Novel locators combine title/document ID, chapter/section, normalized range anchors, text hash, and parser revision.
- Manga locators combine manga ID, chapter ID, page index, OCR engine/revision, normalized text hash, and optional OCR block identity.
- Video locators combine anime/episode/media ID, subtitle track, cue index, cue start/end, normalized text hash, and OCR region identity where relevant.
- Seeking backward and replaying a subtitle is a legitimate new gross exposure only after the cue has left and re-entered the active window under the replay policy.

### 5.5 Lookup and card semantics

- Count only explicit user-requested lookups that produce or deliberately request a result.
- Do not count tokenizer, prefetch, hover/highlight, background dictionary, or stats-indexing calls.
- A lookup event records query, selected normalized word when known, source locator, and result status.
- A new Anki note/card increments `cards_created`.
- Updating/overwriting an existing note increments `cards_updated`, not `cards_created`.
- Opening Anki, previewing a card, or detecting a duplicate is recorded only if useful but never reported as a created card.
- Use Anki note/card IDs plus a stable operation UUID to make retries idempotent.

### 5.6 Knownness and maturity

Known-word identity is reading-aware by default:

```text
(language, normalized headword, normalized reading)
```

Fallback matching may use headword only, but the UI and database record that confidence is lower.

Default maturity tiers:

| Tier | Meaning |
|---|---|
| Unknown | No matching Anki item in the last valid snapshot |
| New | Matching card exists but has not entered learning/review |
| Learning | Card is in learning or relearning |
| Young | Review card below configured maturity threshold |
| Mature | Review interval at or above the configured threshold; default 21 days |
| Unavailable | Anki state cannot be queried or has never been synced |
| Stale | Last successful snapshot exceeds the freshness threshold |

An optional refresh failure must preserve the last successful snapshot and mark it stale; it must not downgrade all vocabulary to unknown.

## 6. Cross-cutting invariants

These are release-blocking:

1. **Incognito is a hard write barrier.** No session, event, text, word, character, lookup, card linkage, recent item, or diagnostic payload is persisted.
2. **Reading never waits for stats.** Capture is buffered and failure-isolated.
3. **Every retry is idempotent.** Events have stable UUIDs and per-session sequence numbers.
4. **Rollups are rebuildable.** Aggregate tables are caches, never the sole source of truth for post-migration activity.
5. **Legacy data is honest.** It may populate totals but not fabricated source detail.
6. **Unicode is counted correctly.** No metric depends on raw Kotlin `String.length`.
7. **Local calendar behavior is tested.** DST, timezone change, midnight split, and week-start configuration are deterministic.
8. **User-facing strings are Komikku strings.** Add them only to `i18n-kmk/src/commonMain/moko-resources/base/` and reference `KMR`; do not edit translated locale files.
9. **No raw text in logs.** Diagnostics use event IDs, hashes, sizes, and typed error codes.
10. **Deletion converges.** Deleting a session/title/raw-text scope updates indexes and rollups transactionally or schedules a visible repair.
11. **Capability gaps are explicit.** Missing tokenizer, OCR, Anki permissions, or raw provenance appears as unavailable/partial, never as zero.
12. **Accessibility is not deferred.** Charts have textual summaries, grids expose semantic labels, and color is never the only maturity indicator.

## 7. Target architecture

### 7.1 Layering

```text
app/chimahon capture adapters
  └─ domain immersion interactors and models
       └─ domain repository interfaces
            └─ data SQLDelight repository implementation
                 ├─ append-only event tables
                 ├─ source/word/character indexes
                 ├─ Anki snapshot tables
                 └─ daily/title/session rollups

Stats Compose UI
  └─ screen models
       └─ query interactors returning paged/aggregated immutable models
```

Recommended packages:

```text
domain/src/main/java/tachiyomi/domain/immersion/
  model/
  repository/
  interactor/

data/src/main/java/tachiyomi/data/immersion/
data/src/main/sqldelight/tachiyomi/immersion.sq

app/src/main/java/eu/kanade/domain/immersion/
app/src/main/java/eu/kanade/tachiyomi/ui/stats/
app/src/main/java/eu/kanade/presentation/more/stats/

chimahon/src/main/java/chimahon/stats/
  capture/
  tokenizer/
  anki/
```

Names may be adjusted to match current module dependency boundaries, but:

- SQLDelight implementation remains in `data`.
- repository contracts and pure metric models remain in `domain`.
- reader/player-specific adapters remain close to their lifecycle owners.
- Komikku-specific DI registration goes through `KMKDomainModule`.

### 7.2 Write pipeline

```text
UI/media lifecycle
  -> CaptureAdapter emits typed CaptureCommand
  -> ImmersionRecorder validates privacy + session state
  -> bounded in-memory queue
  -> transactional batch append
  -> enqueue text indexing and rollup work
  -> observer Flow invalidation
```

Requirements:

- Use a bounded queue with loss diagnostics and graceful direct-flush fallback for session finalization.
- Batch events off the main thread.
- A capture command carries a stable idempotency key chosen at the source boundary.
- Heavy tokenization runs after the exposure is durably recorded.
- Tokenizer/indexing failures do not delete or invalidate the base exposure.
- Store indexing version so text can be reindexed when normalization/tokenization changes.

### 7.3 Read pipeline

```text
StatsFilter
  -> Query interactor
  -> rollup-first SQL query
  -> paged detail query only when expanded
  -> immutable StatsUiModel
  -> Compose
```

Do not:

- Load all events, words, or lines into `StatsScreenModel`.
- Build time buckets in Composables.
- Run per-row queries for title covers, word counts, or character counts.
- Depend on raw source text for summary totals.

## 8. SQLDelight data model

Exact SQL names may change during schema review, but the identities and relationships are required.

### 8.1 Core identity tables

#### `immersion_title`

Purpose: stable stats identity across manga, novel, video, and imported content.

Suggested columns:

- `id TEXT PRIMARY KEY` — generated stable UUID.
- `media_kind TEXT NOT NULL` — `NOVEL`, `MANGA`, `VIDEO`, or future kind.
- `source_key TEXT NOT NULL` — namespaced identifier such as `manga:<id>`, `novel:<book-id>`, or `video:<anime-id>`.
- `profile_id TEXT` and `language_tag TEXT`.
- `display_title TEXT NOT NULL`.
- optional library/tracker/media IDs.
- optional difficulty, release date, status, total units, total character estimate.
- `created_at`, `updated_at`, `deleted_at`.
- unique index on `(media_kind, source_key, profile_id)`.

#### `immersion_session`

- `id TEXT PRIMARY KEY` — UUID generated before the first event.
- `device_id TEXT NOT NULL`.
- `title_id TEXT NOT NULL`.
- `media_kind`, `language_tag`, `profile_id`.
- `started_at`, `ended_at`, `start_zone_id`, `start_offset_seconds`.
- `status` — `ACTIVE`, `COMPLETED`, `ABANDONED`, `DELETED`.
- active/elapsed/pause/idle/buffer durations.
- gross/unique-source/net character counters.
- word, line/page/cue, lookup, card-create, card-update counters.
- last sequence and last heartbeat.
- capture/schema versions.
- legacy-import and sync-origin flags.

Indexes: title/time, status/time, device/time, profile/language/time.

#### `immersion_event`

- `id TEXT PRIMARY KEY`.
- `session_id TEXT NOT NULL`.
- `sequence INTEGER NOT NULL`.
- `occurred_at`, timezone offset.
- `type` — session lifecycle, exposure, progress, pause/resume, seek, lookup, card action, heartbeat.
- optional `source_unit_id`, `word_id`, Anki operation ID.
- typed delta columns for active milliseconds, gross/unique/net characters, lookups, created/updated cards.
- small structured metadata version and payload only where typed columns cannot express the event.
- unique `(session_id, sequence)`.

Keep frequently queried counters typed. Do not hide all analytics in JSON.

### 8.2 Source provenance tables

#### `immersion_source_unit`

- stable ID or fingerprint.
- title ID and medium-specific locator fields.
- source kind: novel range, manga page/block, subtitle cue, video OCR region.
- chapter/section/episode/page/cue/track/position fields.
- source start/end positions or timestamps.
- normalized text hash, parser/OCR/tokenizer version, OCR confidence.
- encrypted/plain raw text according to product policy.
- first/last exposed timestamps.
- countable character count and script breakdown.
- unique index on the canonical locator plus normalized text hash.

#### `immersion_source_exposure`

- event/session/source unit.
- replay ordinal and exposure policy.
- gross/unique-source counters.
- active duration attributed where meaningful.
- unique event key.

Separating source identity from exposure lets one line be reread many times without duplicating retained text.

### 8.3 Vocabulary and character tables

#### `immersion_word`

- ID, language tag, normalized headword, normalized reading, display forms.
- part of speech and tokenization confidence.
- optional frequency corpus/rank, JLPT/grade-style metadata.
- global first/last seen timestamps.
- exclusion flag or separate exclusion relation.
- unique identity index on language/headword/reading.

#### `immersion_word_occurrence`

- word ID, source unit ID, token ordinal.
- surface text, source offsets, deinflection/tokenizer metadata.
- first indexed version.
- unique `(source_unit_id, token_ordinal, word_id)`.

#### `immersion_character`

- code point integer primary identity.
- rendered string.
- Unicode name/category/script.
- optional Japanese readings, grade, JLPT, frequency metadata where locally available.
- first/last seen.

#### `immersion_character_occurrence`

- character code point, source unit ID, occurrence count, first ordinal.
- unique `(character_code_point, source_unit_id)`.

The occurrence table stores aggregated count per source unit unless exact every-position highlighting is required.

### 8.4 Lookup and Anki tables

#### `immersion_lookup`

- event/operation UUID.
- session/source unit.
- raw query, normalized word ID where resolved.
- selected dictionary/result identity where safe.
- success/status, timestamp.
- no full dictionary response payload.

#### `immersion_anki_operation`

- operation UUID and event ID.
- session/source unit/word link.
- note ID/card ID/deck ID if available.
- type: create, update, duplicate, open, delete-observed.
- success and timestamp.
- idempotency uniqueness on operation ID and successful external IDs.

#### `immersion_anki_snapshot`

- snapshot ID, profile/deck scope, requested/completed time.
- capability/version/status/error code.
- complete/partial/stale flags.

#### `immersion_anki_item`

- snapshot ID, note/card IDs.
- normalized word and reading or character.
- card type/queue, interval, due, lapses, ease where available.
- maturity tier and first-mature timestamp.
- indexes on word identity, character, maturity, and snapshot.

### 8.5 Rollups and goals

#### `immersion_daily_rollup`

Keyed by local date, profile, language, media kind, and optional title.

Columns include:

- active time.
- gross, unique-source, and net characters.
- source units, words, unique words, globally new words.
- distinct/new characters.
- sessions, lookups, created/updated cards.
- rollup version and last applied event sequence/time.

#### `immersion_hourly_rollup`

Rebuildable local-hour totals keyed by date, hour, profile, language, media kind,
title, provenance, and replay state. This table preserves event-occurrence and
legacy-session-start hour semantics while keeping all-time temporal filters off
the raw event table.

#### `immersion_lifetime_rollup`

Global and per-title totals that remain after optional raw event/source retention cleanup. Include provenance-availability flags so the UI knows a drill-down cannot be reconstructed.

#### `immersion_applied_event`

Tracks event IDs applied to rollups if rollup updates are not naturally made in the same transaction. Prefer same-transaction updates when practical.

#### `immersion_goal`

- ID, type, metric, target, period/date range.
- optional media/profile/language/title scope.
- weekday target multipliers/rest-day policy.
- active/completed/archived state.
- creation and update timestamps.

#### `immersion_goal_check_in`

For manual habit goals only: goal/date/status/note/timestamp.

#### `immersion_goal_achievement`

Stores immutable earned milestone identity so later target edits do not silently erase trophies.

### 8.6 Operational tables

- `immersion_import_ledger`: source store/version/hash/imported-at/result, preventing repeat legacy imports.
- `immersion_rollup_state`: schema/index/rollup version and repair cursor.
- `immersion_sync_peer`: peer/device identity and high-water marks.
- `immersion_tombstone`: deleted event/session/source IDs for convergent sync.
- `immersion_exclusion`: word/title/source exclusions with reason and scope.
- `immersion_retention_state`: cleanup cursor and last successful run.

### 8.7 Retention defaults

Initial recommended defaults:

- Aggregated totals: retained until explicit deletion.
- Sessions/events: retained indefinitely initially; offer 90-day/1-year/custom cleanup later.
- Raw source text: retained for drill-down by default only after a clear first-run disclosure; offer `Never`, `30 days`, `1 year`, and `Until deleted`.
- Word/character identities and counts: retained until explicit deletion.
- Telemetry/heartbeats: compact after session finalization.
- Anki snapshots: retain recent full snapshots plus derived current state and weekly historical summary.

Before release, product review must decide whether raw source text defaults to on or opt-in. Whatever choice is made, aggregate capture and raw-text retention must be separate settings.

## 9. Query and filter contract

One `StatsFilter` value object must drive every tab:

- Local date/time range and comparison range.
- Media kinds.
- Profiles and language tags.
- Titles.
- Include/exclude legacy aggregate data.
- Character basis: gross, unique-source, or net.
- Reread/replay inclusion where applicable.
- Knownness/maturity tiers.
- Raw provenance availability.

Filter changes should be serializable into saved state and deep links. Every detail route inherits the filter and may narrow it. A user returning from a word/character/session detail must retain the previous filter and scroll state.

All SQL queries must define:

- timezone bucketing behavior.
- null/unavailable behavior.
- legacy inclusion behavior.
- whether excluded words/titles are removed from numerator, denominator, or both.
- stable sort and deterministic tie-breaker.

## 10. Rollout structure

Each numbered phase below is designed to be used as a separate `/goal`. A goal is complete only when its explicit end state and verification gates are met. Do not combine UI phases before the event/store foundation is reconciled against current JSON totals.

Suggested release slices:

| Slice | Phases | User-visible result |
|---|---|---|
| Foundation | 0–3 | Hidden trustworthy session/event recording |
| Media parity | 4–7 | All Chimahon consumption and mining paths captured |
| Index and analytics | 8–10 | Words, characters, maturity, and fast rollups |
| Core dashboard | 11–13 | Overview, trends, titles |
| Drill-down | 14–16 | Vocabulary, characters, sessions, search/mining |
| Coaching | 17–18 | Goals and Anki learning reports |
| Production hardening | 19–21 | Backup/sync/privacy/performance/rollout and legacy retirement |

## 11. Phase 0 — Metric contract, ADR, flags, and baseline fixtures

### `/goal` objective

> Establish the canonical immersion-statistics contract before persistence changes: document and encode metric semantics, source identities, capability states, privacy behavior, and feature flags; add baseline fixtures that capture current JSON behavior so later phases can prove migration parity.

### Dependencies

None. This is the mandatory first phase.

### Scope

- Convert Sections 5, 6, and 9 into code-level domain types and architecture decisions.
- Inventory every current stats write/read boundary.
- Record representative current-store fixtures without changing production behavior.
- Add kill switches and diagnostics safe enough to ship before UI exposure.

### Likely code areas

- New `domain/.../immersion/model/` types.
- New `chimahon/.../stats/` contracts.
- Current `Statistics.kt`, storage classes, reader trackers, player, dictionary, and Anki creator for boundary documentation only.
- Preference/feature-flag service in the app layer.
- `docs/architecture/` or an adjacent plan/ADR location if the repository has an established convention.

### Implementation tasks

- [ ] Add an ADR covering event store, rollups, stable IDs, legacy import, raw text retention, and why gross/unique/net are separate.
- [ ] Define enums/value types for `MediaKind`, `CharacterMetric`, `SessionStatus`, `EventType`, `SourceKind`, `AnkiOperationType`, `MaturityTier`, `CapabilityState`, and `ProvenanceState`.
- [ ] Define validated value objects for session/event/title/source IDs, language tag, code point, local date, duration, and non-negative counters.
- [ ] Define `StatsFilter` independently of Compose.
- [ ] Define the count policy interface and default Unicode policy.
- [ ] Specify active/idle/background/pause transitions as a state machine.
- [ ] Define source locator interfaces for novel, manga, subtitle, and video OCR.
- [ ] Define version numbers for schema, capture, normalization, tokenizer, and rollups.
- [ ] Add feature flags:
  - `immersion_stats_capture_enabled`
  - `immersion_stats_indexing_enabled`
  - `immersion_stats_ui_enabled`
  - `immersion_stats_anki_sync_enabled`
  - `immersion_stats_goals_enabled`
- [ ] Add a local diagnostics state containing queue depth, last write/index/rollup error code, last repair, and dropped command count—never source text.
- [ ] Capture anonymized test fixtures for current novel, manga, and Anki JSON shapes, including missing/corrupt/older forms.
- [ ] Write a boundary map listing each current writer and reader, and assign an owner adapter for later phases.
- [ ] Decide the initial inactivity timeout and video buffering grace value; make both preferences, not constants scattered across readers.
- [ ] Decide whether novel backward navigation produces negative net progress or clamps per source section; encode the decision in tests.

### Tests and verification

- Unit-test Unicode supplementary-plane code points, combining marks, punctuation, whitespace, kana, Han, and Hangul.
- Unit-test state transitions such as foreground → active → idle → active → background → finalize.
- Unit-test invalid negative counters and overflows.
- Snapshot-test old JSON fixtures so their interpreted totals are stable.
- Run repository formatting and compile gates if Kotlin/XML changes:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
```

### End state / definition of done

- Every future statistic has one written formula and one domain representation.
- Source identities and idempotency requirements are implementable for all three media paths.
- Incognito and unavailable capability behavior are encoded, not only documented.
- The app still uses the old stores in production, but hidden feature flags and fixtures make later development safe.
- No UI copy has been added outside `KMR`/`i18n-kmk` base resources.

## 12. Phase 1 — SQLDelight schema and repository foundation

### `/goal` objective

> Implement the versioned SQLDelight immersion-statistics schema, domain repository contracts, transactional data implementation, DI wiring, migration, and repository tests without changing current stats capture or UI.

### Dependencies

Phase 0.

### Scope

- Add the tables in Section 8 with the minimum columns needed through Phase 10.
- Add typed SQLDelight adapters and domain/data mapping.
- Expose transaction-safe append/query/repair interfaces.
- Do not yet instrument readers.

### Likely code areas

- `data/src/main/sqldelight/tachiyomi/immersion.sq`
- next numbered file under `data/src/main/sqldelight/tachiyomi/migrations/`
- `domain/.../immersion/repository/`
- `domain/.../immersion/interactor/`
- `data/.../immersion/`
- `app/src/main/java/eu/kanade/domain/KMKDomainModule.kt`

### Repository interfaces

At minimum:

- `ImmersionRecorderRepository`
  - create/finalize/recover session.
  - append event batch.
  - upsert source unit.
  - append exposure with idempotency key.
- `ImmersionIndexRepository`
  - claim indexing work.
  - upsert words/characters/occurrences.
  - mark indexed version or typed failure.
- `ImmersionStatsRepository`
  - overview/trend/title/session/vocabulary/character queries.
  - observe invalidation/revision.
- `ImmersionMaintenanceRepository`
  - rebuild rollups.
  - validate invariants.
  - delete scopes.
  - apply retention.
- `ImmersionGoalRepository`.
- `ImmersionAnkiRepository`.

### Implementation tasks

- [ ] Add the schema and indexes using text/long adapters consistent with repository conventions.
- [ ] Use foreign keys/cascade behavior only where deletion semantics are intentionally defined.
- [ ] Ensure every event and session ID is caller-supplied so retries can reuse identity.
- [ ] Implement one transaction that creates/gets a source unit, appends an exposure event, advances sequence, and updates the live session counter.
- [ ] Make duplicate event insertion return an `AlreadyApplied` result rather than throw as an unexpected error.
- [ ] Implement session finalization and abandoned-session recovery.
- [ ] Add schema and rollup metadata rows.
- [ ] Add query pagination primitives with stable cursor or stable `(sort value, id)` ordering.
- [ ] Add data mappers that reject invalid database values with typed corruption errors.
- [ ] Wire repositories in `KMKDomainModule`.
- [ ] Add a no-op/failure-isolating recorder used when the feature flag is off.
- [ ] Add a database integrity report query:
  - orphaned events/source occurrences.
  - duplicate session sequences.
  - negative counters.
  - rollup version mismatch.
- [ ] Document indexes and expected query shapes; use `EXPLAIN QUERY PLAN` in tests for the primary lists if supported by the harness.

### Migration rules

- Migration must be additive.
- Existing installations must open without requiring legacy import to succeed.
- A database migration failure must leave the original database recoverable under existing backup behavior.
- Do not delete or modify JSON stores in this phase.

### Tests and verification

- Fresh database schema test.
- Upgrade test from migration 46 to the new migration.
- Duplicate event/idempotent session tests.
- Foreign-key and deletion behavior tests.
- Crash simulation: session and events commit together or not at all.
- Pagination stability with tied timestamps/counts.
- Concurrent read during batched write.
- `:data:generateSqlDelightInterface`, targeted data tests, then mandatory gates:

```bash
./gradlew :data:generateSqlDelightInterface
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
```

### End state / definition of done

- A fresh or upgraded app has a valid, empty immersion-statistics schema.
- Repository tests prove append idempotency, transactionality, pagination, recovery, and deletion relationships.
- Production capture and UI remain unchanged behind flags.
- Schema migration is reversible through normal database backup restoration; no legacy file is removed.

## 13. Phase 2 — Legacy JSON import and compatibility bridge

### `/goal` objective

> Import existing novel, manga, and Anki daily JSON aggregates exactly once into clearly marked synthetic legacy sessions/rollups; provide dual-read reconciliation and a safe fallback without fabricating source, word, character, or real-session detail.

### Dependencies

Phases 0–1.

### Import semantics

- One synthetic session per `(legacy source, local date, title/profile/media)` where needed.
- `legacy_import = true`.
- Start/end time unavailable unless the legacy record provides it.
- Characters map to the best-known legacy semantic and carry `metric_quality = LEGACY_AMBIGUOUS`.
- Reading time is retained exactly.
- Speed is recomputed from imported totals when possible; old max/min speed stays in an optional legacy metadata field if required for parity.
- Card counts import as `legacy_cards_total`, not as fabricated Anki operation events.
- No source units, words, characters, lookups, or provenance are created.

### Likely code areas

- `BookStorage`
- `MangaStatsStorage`
- `AnkiStatsStorage`
- existing `Statistics.kt`
- new import interactor/worker and `immersion_import_ledger`
- `StatsScreenModel` compatibility query layer

### Implementation tasks

- [ ] Enumerate every real JSON location, naming rule, and version.
- [ ] Hash each source file or logical record set and persist an import-ledger entry.
- [ ] Parse defensively: isolate malformed titles/days rather than abort the entire import.
- [ ] Preserve current max-merge behavior only inside the old storage compatibility layer.
- [ ] Import records transactionally per source/title with restartable checkpoints.
- [ ] Record imported/skipped/failed counts and typed reasons without source text.
- [ ] Build a reconciliation query comparing old screen totals to `legacy + new SQL` totals by date/media/title.
- [ ] Add a developer-only report/export for mismatches.
- [ ] During dual-write rollout, keep the existing JSON writer and new event writer independently observable.
- [ ] Add a preference to exclude legacy ambiguous totals from novelty/speed views; default summary views should include them with an info label.
- [ ] Make detail routes explain why imported days have no sessions, words, characters, or source lines.
- [ ] Do not mark the import complete until the database transaction and ledger write both succeed.
- [ ] Keep source JSON files untouched.

### Tests and verification

- Empty, normal, duplicate-day, partial, corrupt, old-version, future-field, and large-file fixtures.
- Timezone/date-key interpretation.
- Import replay produces no extra rows.
- Import interruption resumes without duplicates.
- Old-vs-new total equality for supported aggregate fields.
- UI state for mixed legacy and event-backed periods.
- Mandatory formatting/build gates after code changes.

### End state / definition of done

- Existing users see the same total time/characters/cards after enabling the new query path.
- Imported data is visibly and queryably distinguished from event-backed data.
- No fake word/character/session detail exists.
- Re-running or crashing the importer cannot double count.
- Old files remain available for rollback.

## 14. Phase 3 — Session recorder, event batching, lifecycle, and reconciliation

### `/goal` objective

> Build a production-safe, media-neutral immersion session recorder with an explicit lifecycle state machine, bounded asynchronous batching, crash recovery, incognito barrier, rollup hooks, diagnostics, and shadow-mode reconciliation.

### Dependencies

Phases 0–2.

### Core API sketch

```kotlin
interface ImmersionRecorder {
    suspend fun startSession(context: SessionContext): SessionHandle
    fun record(command: CaptureCommand): RecordResult
    suspend fun pause(reason: PauseReason)
    suspend fun resume(reason: ResumeReason)
    suspend fun finalize(reason: FinalizeReason)
}
```

The concrete API may differ, but source adapters must not manipulate database rows directly.

### Implementation tasks

- [ ] Implement lifecycle states and legal transitions.
- [ ] Allocate session UUID and device ID before the first database call.
- [ ] Persist a session-start event synchronously enough to establish identity while never blocking content rendering for a long database operation.
- [ ] Implement a bounded channel/queue with batch-size and flush-interval tuning.
- [ ] Assign monotonic per-session sequence numbers before retry.
- [ ] Flush on lifecycle stop, title change, and normal finalization.
- [ ] Persist periodic heartbeats only when active; compact them after finalization if safe.
- [ ] Split active durations at local midnight.
- [ ] Exclude background, idle, pause, and excessive buffering.
- [ ] Handle process death by abandoning at the last persisted active boundary.
- [ ] Enforce incognito before queue insertion, not only at repository write.
- [ ] Ensure toggling incognito finalizes any active tracked session before the barrier takes effect.
- [ ] Add diagnostics for queue saturation, write latency, last error, abandoned recovery, and rollup lag.
- [ ] Never include source text, dictionary query, or title in crash/log payloads unless the user's local debug export explicitly includes it.
- [ ] Add shadow mode: compute session/day totals without exposing UI and compare against current trackers.
- [ ] Define typed tolerances:
  - reading time discrepancy allowed only for documented active-time policy changes.
  - novel net progress should match current tracker within an exact fixture.
  - no tolerance for duplicate session/event IDs.
- [ ] Implement repair scheduling if live session counters and events diverge.

### Failure behavior

- Queue full: record a typed dropped-event diagnostic and attempt a bounded urgent flush; never freeze the reader.
- Database busy: retry with limited backoff using the same IDs.
- Database unavailable: end capture for that session, expose local warning/diagnostic, retain old tracking during dual-write phase.
- Tokenizer unavailable: base exposure persists and indexing remains pending.
- Clock moves backward: event order remains sequence-based; wall time is preserved but duration uses monotonic time within the live process.

### Tests and verification

- Full lifecycle transition table.
- Rapid open/close and configuration change.
- Two title changes in quick succession.
- Midnight/DST/timezone changes.
- Process-death recovery.
- Queue saturation and retry idempotency.
- Incognito toggled before, during, and after session.
- Concurrent capture from a dictionary overlay and reader.
- Shadow reconciliation fixtures.
- Performance microbenchmark for `record()` call overhead.

### End state / definition of done

- A test adapter can produce complete, durable, idempotent sessions without knowledge of SQL.
- Active time is correct through pause/background/idle/crash scenarios.
- Incognito produces zero rows and zero queued commands.
- Recorder overhead meets the initial target: main-thread enqueue p95 under 2 ms on representative hardware or a documented adjusted budget.
- The feature remains hidden while shadow diagnostics prove it can run safely.

## 15. Phase 4 — Novel reader capture adapter

### `/goal` objective

> Instrument Chimahon's novel reader so active sessions, gross exposure, unique-source exposure, net progress, source ranges, lifecycle transitions, and title completion are recorded accurately without altering reading behavior.

### Dependencies

Phase 3.

### Source semantics

A novel source unit should represent a stable displayed text range, not a transient Compose layout:

- Book/title stable ID.
- Chapter/section ID.
- Normalized paragraph/range anchor.
- Start/end logical positions.
- Normalized text hash.
- Parser/content revision.

If exact stable ranges are not available in the current reader, first add a locator abstraction and test it against reflow, font-size changes, reopening, and chapter reload.

### Likely code areas

- `chimahon/src/main/java/com/canopus/chimareader/ui/reader/ReaderStatisticsTracker.kt`
- Chimahon novel `ReaderViewModel.kt`
- text rendering/navigation callbacks.
- `BookStorage` title identity mapping.
- new `chimahon/.../stats/capture/NovelCaptureAdapter.kt`

### Implementation tasks

- [ ] Start/finalize sessions on real reader lifecycle and title transitions.
- [ ] Feed foreground, visibility, idle, and overlay pause events.
- [ ] Replace implicit `String.length` counting with the canonical Unicode counter.
- [ ] Preserve current net-position metric as `net progress`, not gross exposure.
- [ ] Emit exposure only when a range becomes meaningfully visible under a documented threshold.
- [ ] Deduplicate recomposition, layout, rotation, and font/theme changes.
- [ ] Count deliberate backward rereading as gross exposure while keeping unique-source and net metrics distinct.
- [ ] Create stable source units with enough retained context for word/character drill-down.
- [ ] Record chapter/section completion and title progress where reliable.
- [ ] Handle find/search jumps, bookmarks, table-of-contents jumps, and restored position.
- [ ] Do not record text shown only in previews, background parsing, or offscreen prefetch.
- [ ] Respect raw-text retention separately from aggregate exposure.
- [ ] Dual-write existing daily JSON until Phase 21.
- [ ] Add a developer reconciliation screen/report by session and day.

### Tests and verification

- Read forward across several ranges.
- Scroll backward and reread.
- Jump via chapter list, bookmark, and search.
- Rotate/change font without new exposure.
- Background and resume after idle.
- Cross midnight.
- Open incognito.
- Reopen the same range on a new day.
- Supplementary Unicode and mixed Japanese/Korean/Latin content.
- Compare legacy tracker net characters/time to new net/time under an equivalent policy.

### End state / definition of done

- Every novel session has accurate active time and separate gross, unique-source, and net characters.
- Source ranges remain stable across reflow and reopen.
- Rereads count only in metrics where intended.
- Current reader performance and behavior are unchanged.
- Shadow reconciliation has no unexplained duplicates or missing finalization.

## 16. Phase 5 — Manga OCR capture adapter

### `/goal` objective

> Instrument manga reading at chapter/page/OCR-block granularity, replacing page-index-only deduplication with stable source identity and recording accurate active time, text exposure, OCR capability/confidence, rereads, and progress.

### Dependencies

Phase 3; Phase 4 provides a useful adapter pattern but is not a hard dependency.

### Source semantics

Canonical manga source unit:

```text
(manga ID, chapter ID, page index, OCR engine/version, OCR block ID,
 normalized text hash)
```

The page remains a valid progress unit even when OCR is unavailable. Text/character metrics must then be `Unavailable`, not falsely zero.

### Likely code areas

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- reader page-change and chapter-change lifecycle.
- OCR cache/model classes.
- `presentation/reader/stats/MangaStatsSheet.kt`
- new `MangaCaptureAdapter`.

### Implementation tasks

- [ ] Map manga/chapter/page to a stable `immersion_title` and source locator.
- [ ] Reset/differentiate deduplication on chapter change.
- [ ] Emit page-view/progress even if OCR does not exist.
- [ ] Emit text exposure only from the OCR result actually associated with the viewable page.
- [ ] Persist OCR engine/version, confidence/quality flag, and normalized hash.
- [ ] Avoid counting OCR cache population, neighboring-page prefetch, or background recognition as exposure.
- [ ] Define two-page/spread behavior and simultaneous visibility threshold.
- [ ] Define webtoon continuous-scroll visibility and repeated partial block exposure.
- [ ] Count reread/revisit as gross exposure after a real page leave/re-enter; unique-source stays deduplicated.
- [ ] Attribute active time to page/session with the existing upper-bound protection reviewed against the canonical idle policy.
- [ ] Ensure quick page flips do not fabricate a minimum reading duration.
- [ ] Track chapter completion independently from OCR coverage.
- [ ] Expose OCR coverage percentage for title/session so character totals can be interpreted.
- [ ] Dual-write `MangaStatsStorage` until retirement.
- [ ] Fix current zero-character ambiguity in legacy/UI query mapping.

### Tests and verification

- Page with cached OCR, newly completed OCR, failed OCR, and no OCR support.
- Same page index in two chapters.
- Page revisit, chapter revisit, rotation, split pages, and webtoon.
- OCR result changes after engine/version update.
- Rapid flip and long idle.
- Background/foreground and reader process recreation.
- Incognito.
- Reconciliation against current time and page behavior.

### End state / definition of done

- Manga sessions are queryable even with no OCR.
- Character totals clearly express OCR coverage and never treat unavailable OCR as a measured zero.
- No cross-chapter page collision exists.
- OCR text is counted only when its page/block is meaningfully visible.
- Reread, progress, and unique-source metrics remain distinct.

## 17. Phase 6 — Video subtitle and OCR capture adapter

### `/goal` objective

> Add full video immersion capture using active subtitle cues and visible OCR text, including playback-aware active time, pause/buffer/seek/replay semantics, episode/title identity, source timestamps, and media context suitable for later historical mining.

### Dependencies

Phase 3.

### Source semantics

Subtitle source unit:

```text
(anime/title ID, episode/media ID, subtitle track ID, cue index,
 cue start ms, cue end ms, normalized text hash)
```

Video OCR source unit:

```text
(anime/title ID, episode/media ID, timestamp bucket/frame identity,
 OCR region ID, OCR engine/version, normalized text hash)
```

### Likely code areas

- `app/src/main/java/eu/kanade/tachiyomi/ui/player/PlayerViewModel.kt`
- subtitle cue/history models.
- playback state, position, seek, pause, buffering, episode transitions.
- OCR overlay pipeline.
- audio/screenshot capture context used by Anki.
- new `VideoCaptureAdapter`.

### Implementation tasks

- [ ] Start/finalize on playable media and episode/title transition.
- [ ] Count active time only while foreground, playing, and not beyond buffering/idle policy.
- [ ] Record pause/resume, meaningful seek, subtitle-mode, and track-change events.
- [ ] Emit exposure when a cue becomes active, not when the subtitle file is parsed.
- [ ] Deduplicate repeated cue observer callbacks.
- [ ] Treat seek-back cue re-entry as gross replay only after a documented hysteresis/window.
- [ ] Avoid counting scrub previews or frame thumbnails.
- [ ] Record primary and optional secondary subtitle roles; only configured learning-language text contributes to primary character/word metrics.
- [ ] Track video OCR visibility separately from subtitle cues and deduplicate stable text across adjacent frames.
- [ ] Persist timestamps and media IDs required to reopen the source if still available.
- [ ] Link currently available screenshot/audio capture context without copying large media into the stats database.
- [ ] Record subtitle/OCR coverage and language capability.
- [ ] Handle external player handoff as unsupported/partial rather than estimating activity.
- [ ] Add title/episode completion and estimated remaining data where the player can provide it.

### Tests and verification

- Normal playback through sequential cues.
- Pause on a cue, resume, buffer, background, foreground.
- Seek backward/replay and seek forward past cues.
- Change subtitle track/language.
- Overlapping primary/secondary cues.
- Duplicate cues with identical text at different timestamps.
- Video OCR text stable across frames then changed.
- Episode auto-advance.
- Incognito and process death.
- Session timestamps align with media positions.

### End state / definition of done

- Video reading contributes trustworthy session/time/character/source stats.
- Subtitle parsing alone produces no exposure.
- Pause, buffering, background, and replay semantics match the contract.
- Each retained occurrence can identify its episode and timestamp.
- Unsupported external playback is visibly partial, never silently counted.

## 18. Phase 7 — User lookup and Anki operation instrumentation

### `/goal` objective

> Instrument only explicit user dictionary lookups and successful Anki actions across novel, manga, and video contexts, linking them idempotently to session/source provenance while distinguishing card creation, update, duplicate, and failure.

### Dependencies

Phases 3–6 for full media coverage; it may begin after Phase 3 with staged adapters.

### Likely code areas

- `chimahon/src/main/java/chimahon/DictionaryRepository.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/dictionary/DictionaryTab.kt`
- OCR/dictionary popup call sites.
- reader dictionary selection call sites.
- video subtitle/OCR popup call sites.
- `chimahon/src/main/java/chimahon/anki/AnkiCardCreator.kt`
- new `LookupTelemetry` and `AnkiOperationRecorder` abstractions.

### Implementation tasks

- [ ] Inventory every explicit user lookup entry point.
- [ ] Introduce a shared UI-intent boundary that records a lookup exactly once after explicit request.
- [ ] Keep dictionary engine/repository internal calls uninstrumented by default.
- [ ] Carry `SessionContext` and `SourceLocator` into lookup UI without coupling dictionary domain models to SQL.
- [ ] Record raw query only under raw-text retention/privacy policy; always permit hash/normalized word linkage.
- [ ] Resolve selected headword/reading/POS after the user selects a result where possible.
- [ ] Record successful, empty, cancelled, and failed status separately; primary counters should use the documented successful/requested definition.
- [ ] Wrap Anki actions in a stable operation UUID allocated before external invocation.
- [ ] On success, persist returned note/card identity if the API exposes it.
- [ ] Distinguish create from overwrite/update.
- [ ] Do not increment create totals for duplicate rejection, preview, Anki open, or edit-only.
- [ ] Retry database linkage using the same operation UUID if Anki succeeded but local persistence failed.
- [ ] Provide a repair queue for externally successful/unlinked operations.
- [ ] Ensure incognito does not persist lookup or linkage even if Anki itself is explicitly used.
- [ ] Preserve current Anki daily JSON dual-write with corrected new/update semantics available only in the new store.

### Tests and verification

- One tap/request yields one lookup despite recomposition or multiple dictionary queries.
- Empty/cancelled/error behavior.
- Same visible source used by novel, manga OCR, subtitle, and video OCR.
- Anki new card, duplicate, overwrite, external failure, and local post-success failure.
- Retry idempotency with returned Anki ID.
- Incognito.
- No dictionary prefetch/tokenization calls appear as lookup events.

### End state / definition of done

- Lookup and mining rates use real user actions and correct denominators.
- Every supported lookup/card action links to session and source when available.
- New cards and updates are no longer conflated in the new model.
- Retrying any external/local boundary cannot double count.

## 19. Phase 8 — Character and vocabulary indexing pipeline

### `/goal` objective

> Implement a versioned, language-aware indexing pipeline that converts retained source units into correct Unicode character inventories and lexical word occurrences, supports Japanese first without blocking other languages, and can safely reindex after tokenizer or normalization changes.

### Dependencies

Phases 1 and at least one source capture adapter from Phases 4–6.

### Language strategy

The schema is language-neutral; capabilities vary by language:

- **All languages:** Unicode code-point counts, script classification, distinct-character inventory, source provenance.
- **Japanese:** normalized tokens with headword, reading, part of speech, deinflection/frequency where existing dictionary assets support it.
- **Korean:** use an existing Korean analyzer only if it has a stable app-level API; otherwise character stats first and lexical state explicitly unavailable.
- **Other languages:** pluggable tokenizer interface; fallback boundary tokenizer must be labeled low confidence and should not claim dictionary-normalized vocabulary.

### Interfaces

```kotlin
interface SourceTextNormalizer {
    val version: Int
    fun normalize(input: String, language: LanguageTag): NormalizedText
}

interface ImmersionTokenizer {
    val id: String
    val version: Int
    fun supports(language: LanguageTag): Boolean
    suspend fun tokenize(text: NormalizedText): TokenizationResult
}
```

### Implementation tasks

- [ ] Implement Unicode code-point iteration with category/script classification.
- [ ] Produce countable-character count, per-script counts, and distinct code points in one pass.
- [ ] Normalize line endings, Unicode normalization form, whitespace, and optional repeated-character rules without mutating retained source display text.
- [ ] Decide whether variation selectors and combining marks are excluded, attached, or exposed as advanced detail; test the decision.
- [ ] Define Japanese token identity and reading normalization, including kana-only words and absent readings.
- [ ] Reuse existing dictionary/tokenization assets through a small adapter rather than duplicating parser logic.
- [ ] Keep user lookup and background tokenization paths separate.
- [ ] Persist tokenizer ID/version/confidence and indexing state per source unit.
- [ ] Implement an idempotent worker that claims pending source units in bounded batches.
- [ ] Use one transaction to upsert words/characters/occurrences and mark a source unit indexed.
- [ ] Make a failed unit retryable with typed cause and exponential scheduling.
- [ ] Add an explicit `UNSUPPORTED_LANGUAGE` terminal state that can be requeued when capability changes.
- [ ] Add reindex command by version/language/title/date with progress and cancellation.
- [ ] Preserve first-seen identity when reindexing produces the same normalized word.
- [ ] Define conflict behavior when a tokenizer upgrade splits/merges tokens:
  - occurrences are rebuilt for affected source units.
  - global first-seen is recomputed.
  - old orphan word rows are cleaned only after validation.
- [ ] Add frequency/JLPT/grade metadata as optional local enrichments, not identity fields.
- [ ] Implement exclusion matching after normalization and before analytics denominators.

### Tests and verification

- Unicode supplementary characters and mixed scripts.
- Japanese inflections, same spelling/different reading, kana-only words, names, punctuation.
- Duplicate source text at different source locators.
- Same source unit indexed twice.
- Tokenizer crash and retry.
- Reindex version change with split/merge.
- Raw text removed before indexing.
- Unsupported language.
- Large chapter/subtitle file memory behavior.
- Benchmark indexing throughput and transaction size.

### End state / definition of done

- Every retained source unit has a deterministic indexing state.
- Character inventories are correct for all supported text regardless of lexical tokenizer availability.
- Japanese word occurrences have stable normalized identities and provenance.
- Reindexing is safe, observable, cancellable, and cannot double counts.
- Stats capture never blocks on this pipeline.

## 20. Phase 9 — Anki inventory, knownness, and maturity synchronization

### `/goal` objective

> Build a capability-aware AnkiDroid inventory sync that maps notes/cards to Chimahon words and characters, computes reading-aware knownness and maturity tiers, survives partial permission/API failures, and never downgrades a valid cache on refresh failure.

### Dependencies

Phases 7–8.

### Mandatory discovery spike

Before locking the implementation:

- Verify the currently supported AnkiDroid content-provider/API capabilities on the app's min/target SDK.
- Determine which fields are available in bulk: note ID, card ID, deck, fields, queue/type, interval, due, reviews, lapse/ease, and modification time.
- Measure query cost for representative 1k, 10k, and 100k-card collections.
- Document permissions, user prompts, unavailable states, and compatibility versions.
- If review-history/retention data is unavailable, defer those reports rather than approximating them.

### Matching contract

- User config chooses which note fields represent expression, reading, and optional character.
- Normalize with the same pipeline used by immersion words.
- Primary match: `(language, headword, reading)`.
- Secondary match: headword-only with lower-confidence label.
- A note with multiple cards is known if any configured card qualifies; maturity uses a documented aggregation such as maximum interval unless the user selects a stricter mode.

### Implementation tasks

- [ ] Add Anki integration capability probe and persistent status.
- [ ] Add settings for note types/decks/field mappings/language scope.
- [ ] Implement incremental snapshot sync when modification timestamps permit; otherwise bounded full snapshots.
- [ ] Build the new snapshot completely before atomically making it current.
- [ ] Retain the last valid snapshot if refresh fails or is partial.
- [ ] Store refresh status, age, capability, counts, and typed failure.
- [ ] Compute `UNKNOWN`, `NEW`, `LEARNING`, `YOUNG`, and `MATURE`.
- [ ] Make the mature interval threshold configurable with 21 days as the initial default.
- [ ] Record reading match confidence and ambiguity.
- [ ] Build word and character coverage queries.
- [ ] Add refresh, cancel, retry, and clear-cache controls.
- [ ] Avoid per-word/per-card UI queries; all UI reads the local snapshot.
- [ ] Schedule refresh conservatively and only under acceptable device/battery conditions.
- [ ] Add a manual live AnkiDroid verification checklist because mocks cannot prove provider behavior.
- [ ] Add optional maturity colors/tags to vocabulary/reading surfaces only after accessibility tokens are defined.
- [ ] Ensure disabled Anki integration yields `Unavailable`, not all unknown.

### Tests and verification

- Multi-card note with mixed states.
- Reading-aware homographs.
- Missing/misconfigured fields.
- Deleted/renamed deck and note type.
- Large snapshot.
- Partial provider error.
- Refresh failure preserves the old snapshot and marks it stale.
- Threshold change recomputes maturity without external requery where possible.
- Permission denied/revoked.
- No Anki installed.
- Manual live test with actual new, learning, young, and mature cards.

### End state / definition of done

- Vocabulary and character queries can join against a local, versioned Anki snapshot.
- Knownness is reading-aware and confidence-labeled.
- Maturity tiers are stable, configurable, and accessible.
- A provider failure cannot make the user's known inventory disappear.
- Unsupported review-history fields remain explicitly unavailable.

## 21. Phase 10 — Rollup engine and analytics query API

### `/goal` objective

> Implement rebuildable daily/lifetime/title rollups and a tested query API for overview, trends, titles, vocabulary, characters, sessions, goals, and Anki analytics, with correct filtering, local calendar handling, zero-filled buckets, and bounded performance.

### Dependencies

Phases 1–9 for the full query surface. Base rollups may start after Phase 3.

### Rollup strategy

- Update live session counters transactionally with events.
- Apply finalized-event deltas to daily/title rollups with event identity protection.
- Recompute complex distinct/new word/character metrics from indexed occurrences when indexing completes.
- Store rollup/index version and dirty ranges.
- Rebuild a dirty date/title range without touching unrelated history.
- Full rebuild remains available from event/source tables.

### Required query families

1. **Overview**
   - today/range/all-time totals.
   - current and longest streak.
   - comparison with previous equal-length period.
   - capability/provenance/legacy coverage.
2. **Trends**
   - daily/weekly/monthly buckets.
   - cumulative series.
   - efficiency series.
   - time-of-day and weekday.
   - title series with top/recent/manual selection.
3. **Titles**
   - totals, speed, novelty, mining/lookup rate, progress/completion, first/last active.
4. **Vocabulary**
   - totals/new/known/maturity, top repeated, common-by-frequency, cross-title, first-seen series.
5. **Characters**
   - distinct/new/count/coverage, frequency grid, code-point detail, containing words, source occurrences.
6. **Sessions**
   - paged list and grouped day/title views.
   - event timeline summary and source units.
7. **Goals**
   - achieved/target/pace/projection/streak/milestones.
8. **Anki**
   - coverage and available learning-impact inputs.

### Implementation tasks

- [ ] Define immutable domain query/result types independent of current UI.
- [ ] Implement a common local-calendar bucket utility with configurable first day of week.
- [ ] Split cross-midnight duration/events deterministically.
- [ ] Generate zero-filled buckets for requested ranges.
- [ ] Implement previous-period comparison with explicit partial-day handling.
- [ ] Define streak qualification per metric/goal and timezone.
- [ ] Implement globally-new vocabulary/characters using true first-seen timestamps, not per-title first-seen.
- [ ] Implement title novelty and cards/lookups per 10k denominator handling.
- [ ] Implement maturity/knownness joins using the current valid snapshot and status.
- [ ] Implement legacy inclusion so unsupported detail remains absent but totals remain correct.
- [ ] Return data-quality metadata alongside every result:
  - legacy share.
  - OCR/text coverage.
  - indexing completion.
  - Anki freshness.
  - raw provenance availability.
- [ ] Implement rollup dirty-range tracking after index, delete, import, timezone policy, or exclusion changes.
- [ ] Implement bounded repair worker and manual rebuild.
- [ ] Paginate sessions, words, characters, and source occurrences.
- [ ] Add stable sort options and deterministic tie breakers.
- [ ] Add query timing diagnostics with query family, filters hash, row count, and duration only.
- [ ] Add database fixtures at 1 day, 1 year, and large multi-title scale.

### Performance goals

These are initial device-class goals, to be adjusted only with evidence:

- Overview cold query p95 under 300 ms; full screen first meaningful render under 750 ms.
- 365-point trend query p95 under 300 ms.
- First page of 100 vocabulary/character/session rows under 200 ms.
- Filter change must not perform a full raw-event table scan.
- Rollup/index work must yield to reader/player interactions and avoid main thread.
- Compose should render charts from bounded series, not thousands of raw events.

### Tests and verification

- Hand-calculated fixture asserting every metric and denominator.
- DST spring/fall, timezone move, midnight, leap day, week-start.
- Zero denominator and unavailable capability.
- Legacy-only, event-only, and mixed datasets.
- Excluded title/word effect on numerator and denominator.
- Delete then rebuild.
- Failed partial indexing then completion.
- Snapshot stale/unavailable.
- Query plan/index tests and large-fixture benchmark.

### End state / definition of done

- All UI phases can consume stable domain query APIs without loading raw history.
- Rebuilding rollups reproduces the same result.
- Filters and calendar boundaries are consistent across every tab.
- Results disclose their data quality.
- Large representative datasets meet documented budgets or have an approved measured exception.

## 22. Phase 11 — Overview, global filters, and widget parity

### `/goal` objective

> Replace the current Stats overview query/UI with the new filter-aware analytics model while preserving existing totals, adding trustworthy summary metrics, comparisons, heatmap, data-quality disclosure, and home-widget parity.

### Dependencies

Phases 2 and 10. Capture adapters should be sufficiently complete to produce useful live data.

### User experience

Top-level filters:

- Range: today, week, month, year, all, custom.
- Offset or explicit prior/next period.
- Media: all, novel, manga, video.
- Language/profile.
- Title.
- Character basis: gross, unique-source, net.
- Legacy inclusion.

Summary cards:

- Active time.
- Characters using selected basis.
- Reading speed.
- Sessions.
- Lookups.
- New cards.
- New words.
- New target-script characters.
- Current streak and longest streak.

Additional content:

- Previous-period change with absolute and percentage values.
- Metric-switchable daily chart.
- Activity heatmap.
- Recent sessions.
- Data-quality/capability card.

### Likely code areas

- `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsScreenModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/stats/*`
- home stats widget state/query.
- `i18n-kmk/src/commonMain/moko-resources/base/strings.xml`

### Implementation tasks

- [ ] Split the monolithic stats state into filter, overview, navigation, and independently loadable sections.
- [ ] Persist filters with `SavedStateHandle`/screen state conventions.
- [ ] Add date picker/custom range and clear filter summary.
- [ ] Use `KMR` for every new label/content description/error/empty state.
- [ ] Make cards tappable into the relevant detail tab with inherited filters.
- [ ] Add concise metric-definition info sheet.
- [ ] Show unavailable rather than `0` for missing OCR/index/Anki capability.
- [ ] Add data-quality indicator for legacy share, OCR/index coverage, and stale Anki data.
- [ ] Add textual chart summary for accessibility.
- [ ] Use shapes/icons/text as well as color for positive/negative comparisons.
- [ ] Preserve loading content during filter refresh rather than blanking the whole screen.
- [ ] Handle partial section failures independently.
- [ ] Update the home widget to read the same today's rollup and character-basis preference.
- [ ] Keep widget work bounded and resilient when the database is locked/unavailable.
- [ ] Add navigation scaffolding for later tabs without placeholder claims.

### Tests and verification

- Screen-model tests for all filters and previous-period behavior.
- Legacy-only and mixed-state UI tests.
- Empty/new user, unavailable OCR/index/Anki, stale snapshot.
- Accessibility semantics and font scale.
- Rotation/process restoration.
- Widget totals match Overview for identical filters.
- Compose screenshot/golden tests if the repository has a supported harness.
- Mandatory `spotlessApply` → `spotlessCheck` → `assembleDebug`.

Local JVM coverage asserts every Overview filter dimension across current,
comparison, inventory, quality, and streak queries. It also asserts that the
reading-stats widget uses the default Today filter and projects the selected
character basis, time, speed, and cards from the same Overview metrics.

### End state / definition of done

- The default Stats screen answers "what did I do today and how does it compare?"
- Existing time/character/card totals remain reconciled.
- Users can tell which character metric and data quality they are seeing.
- Every summary metric drills into supporting data where available.
- The widget and screen use the same query semantics.

## 23. Phase 12 — Activity and trends

### `/goal` objective

> Add a high-density but mobile-usable Activity/Trends experience covering daily and cumulative volume, efficiency, temporal patterns, title contributions, configurable date aggregation, and transparent denominators.

### Dependencies

Phases 10–11.

### Views

1. **Activity**
   - active time.
   - gross/unique/net characters.
   - sessions.
   - lookups.
   - cards.
   - new words/characters.
2. **Cumulative**
   - time, characters, source units, words, new words, cards, sessions.
3. **Efficiency**
   - characters/hour.
   - words/minute where tokenization coverage is sufficient.
   - cards/hour and cards/10k characters.
   - lookups/10k characters.
4. **Patterns**
   - activity by weekday and hour.
   - speed by weekday and hour.
   - best volume/speed days with minimum denominator thresholds.
5. **Title contributions**
   - stacked/cumulative series for selected titles.
   - top, recent, all/none, and manual visibility.

### Implementation tasks

- [ ] Add 7/30/90/365/all/custom range shortcuts.
- [ ] Select day/week/month aggregation based on range with user override.
- [ ] Allow empty bucket visibility and explain it.
- [ ] Add metric selector rather than rendering every chart simultaneously.
- [ ] Add moving average with configurable/documented window.
- [ ] Prevent "best speed" awards for trivially small sessions/days using a minimum character/time threshold.
- [ ] Add title series cap appropriate to phone performance and legend space.
- [ ] Persist title visibility/filter choice.
- [ ] Add top/recent selection and search.
- [ ] Show precise tooltip/table values and accessible list alternative.
- [ ] Add compare-to-previous-period overlay only where legible.
- [ ] Surface indexing/OCR/legacy coverage beneath affected series.
- [ ] Ensure month grouping uses local calendar months, not 30-day windows.

### Tests and verification

- Sparse dates and zero-filled buckets.
- Range crossing timezone/DST/month/year.
- More titles than series cap.
- Title renamed/deleted.
- Very small denominator.
- Mixed media and capability coverage.
- Chart accessibility and large font.
- Query/render benchmark for 365 days and all-time monthly series.

### End state / definition of done

- Users can answer when, how much, how quickly, and in which titles they immersed.
- Every efficiency number has an inspectable denominator.
- Sparse data and missing capability cannot create false trends.
- Charts remain responsive and accessible on a phone.

## 24. Phase 13 — Title library and title detail analytics

### `/goal` objective

> Build a unified stats library and title-detail experience for novels, manga, and video, including volume, speed, novelty, mining, sessions, progress, coverage, metadata, and source drill-down.

### Dependencies

Phases 10–12.

### Title library

Rows/cards should support:

- Cover and title.
- Media kind and language/profile.
- Active time.
- selected character metric.
- speed.
- sessions.
- last active.
- progress/completion.
- new words/characters.
- Anki cards.
- OCR/index/provenance coverage.

Sort:

- recent.
- title.
- time.
- characters.
- speed with minimum-volume filter.
- novelty.
- cards/mining rate.
- completion/progress.

Filter:

- media, status, language/profile, date activity, coverage, completed/in-progress.

### Title detail

- Identity, metadata, cover, status, source/library link.
- Overall time, characters, speed, sessions, units completed, estimated remaining.
- First/last active, active days, calendar span.
- Average volume/time per active day.
- Best qualifying day for characters/time/speed.
- Lookups, new/update cards, lookup/mining rates.
- Unique words, globally introduced words, novelty rate, new words per 10k characters.
- Distinct/new characters and Anki coverage.
- Cumulative characters/time and daily charts.
- Word acquisition by configurable 10k/25k/50k/100k character buckets.
- Session/unit lists and available source lines.

### Implementation tasks

- [ ] Create a stable title identity adapter across library manga, novel books, anime/episodes, removed content, and legacy imports.
- [ ] Avoid losing stats if a library item is deleted; mark linkage unavailable while retaining the stats title.
- [ ] Add cover/metadata lookup with local fallback and no stats-query network dependency.
- [ ] Add sort/filter state and paging.
- [ ] Use qualifying thresholds for speed ranking.
- [ ] Compute estimated remaining only when total length/unit information and a stable pace exist; otherwise unavailable.
- [ ] Label confidence on estimated remaining.
- [ ] Add title rename/merge/split maintenance operations with preview and rollback strategy.
- [ ] Add title deletion with options:
  - stats only.
  - raw provenance only.
  - unlink from library but retain stats.
- [ ] Link title detail to current reader/player position and history where possible.
- [ ] Ensure novelty uses global first-seen and clearly differs from "unique words in title."
- [ ] Add media-specific unit naming (chapter, page, episode, section) through `KMR` plurals.
- [ ] Add a data-quality section showing OCR/index/raw/Anki/legacy coverage.

### Tests and verification

- Same display title from different media/sources.
- Deleted library item with retained stats.
- Title rename and merge conflict.
- Legacy-only title.
- No OCR or partial indexing.
- Unknown total length.
- High-speed outlier with tiny volume.
- Large library paging/sort performance.

### End state / definition of done

- Every media kind appears in one coherent stats library.
- A title detail explains volume, pace, vocabulary growth, mining behavior, coverage, and provenance.
- Deleted/renamed library data does not orphan or silently merge stats.
- Estimates and rankings are confidence/threshold-aware.

## 25. Phase 14 — Vocabulary analytics and word detail

### `/goal` objective

> Add vocabulary analytics with word growth, frequency, cross-title recurrence, knownness/maturity, exclusions, search/filter/export, and source-backed word detail suitable for learning decisions.

### Dependencies

Phases 8–13; Phase 9 for knownness/maturity.

### Vocabulary overview

- Unique words.
- New words in selected range.
- Known words and coverage percentage.
- Maturity-tier distribution.
- New words per 10k characters.
- Optional names/kana/grammar exclusions.
- Indexing and Anki freshness.

### Lists and charts

- New words by day/cumulative growth.
- Most repeated words.
- Most common words by external frequency rank.
- Words seen across multiple titles.
- Recently first seen and recently seen.
- Unknown high-frequency words.
- Known/mature but frequently looked-up words.
- Words mined but never/rarely reread.

### Word detail

- Headword, reading, display forms, POS, language, tokenizer confidence.
- Count, first/last seen, first source, titles, sessions, source lines.
- Frequency/JLPT or equivalent metadata when available.
- Lookup count and most recent lookup.
- Anki note/card state, match confidence, maturity, snapshot age.
- Similar/related surface or reading forms where a reliable local function exists.
- Mine/reopen actions when source media is still available.

### Implementation tasks

- [ ] Build paged list with stable search/sort/filter.
- [ ] Add filters for knownness, maturity, script, POS, name, kana-only, grammar, occurrence count, date, title, media, and frequency rank.
- [ ] Add configurable persisted exclusions by normalized word identity.
- [ ] Define exclusion impact on growth/coverage denominators and expose it in settings.
- [ ] Add bulk include/exclude with preview.
- [ ] Add CSV export of the filtered list with versioned headers and no raw source text unless explicitly selected.
- [ ] Add drill-down occurrence paging grouped by title/source.
- [ ] Indicate when raw text was deleted but occurrence counts remain.
- [ ] Link a retained source occurrence back to reader/player if locator resolution succeeds.
- [ ] Reuse current mining flow with historical text/audio/screenshot only when those assets are still accessible and user confirms.
- [ ] Never cache copyrighted media blobs in stats solely to enable future mining.
- [ ] Add deep link to current dictionary/Anki search.
- [ ] Add content descriptions combining maturity tier, occurrence count, and knownness without color reliance.
- [ ] Keep list-state filters across detail navigation.

### Tests and verification

- Same headword/different reading.
- Kana-only and name filters.
- Exclusion changes trigger correct rollup dirtiness/rebuild.
- Stale/unavailable Anki.
- Raw text deleted.
- Source item removed/moved.
- Historical mining with and without accessible media.
- CSV escaping and privacy selections.
- 100k-word paging/search benchmark.

### End state / definition of done

- Users can see what vocabulary they encountered, what was globally new, what recurs across titles, and what Anki considers known/mature.
- Every word count is backed by indexed occurrences or clearly marked unavailable.
- Exclusions and exports are deterministic and reversible.
- Historical source actions never claim access to deleted/unavailable media.

## 26. Phase 15 — Character/kanji grid, detail, and coverage

### `/goal` objective

> Add script-aware per-character analytics centered on a scalable kanji/Han grid, with frequency, first/last encounter, title and source provenance, containing words, new-character growth, Anki coverage, missing-character priorities, and accessible non-color representations.

### Dependencies

Phases 8–14; Phase 9 for Anki coverage.

### Character overview

- Distinct count by script.
- New characters in selected range.
- Total occurrence exposure.
- Encountered-vs-Anki coverage.
- Mature-character coverage where meaningful.
- New-character growth over time.
- Top titles introducing new characters.
- Grid/list switch.

### Grid modes

- Encounter frequency with log-scaled color.
- First-seen recency.
- Knownness/maturity.
- Frequency/JLPT/grade band when metadata exists.
- Missing high-frequency priority.

Every mode must also expose a text/icon/border/semantic representation.

### Character detail

- Character glyph, code point, Unicode name/script/category.
- Optional Japanese on/kun readings, meanings, grade/JLPT/frequency.
- Total source occurrence count and gross exposure count.
- First/last seen with source.
- Titles and sessions.
- Words containing it, sorted by occurrence/frequency/knownness.
- Source lines and reopen/mine actions.
- Anki matching items and maturity.
- Neighbor navigation preserving the current sort/filter.

### Implementation tasks

- [ ] Add script tabs/filters with Japanese-first defaults based on active profile language.
- [ ] Implement virtualized grid and paging; never compose all characters in a huge dataset at once.
- [ ] Use log-scaled frequency only after documenting the transformation and legend.
- [ ] Add range presets such as all, encountered, new, unknown, young, mature, missing high-frequency.
- [ ] Add coverage target setting and daily character acquisition suggestion without pretending it is an Anki schedule.
- [ ] Implement priority modes inspired by frequency/JLPT/grade/mixed weighting with formula disclosure.
- [ ] Add containing-word query and occurrence paging.
- [ ] Keep character identity as code point; render variation/compatibility forms safely.
- [ ] Handle fonts missing a glyph with code-point fallback.
- [ ] Add export of selected characters and supporting words.
- [ ] Add search by glyph, code point, reading, meaning, or containing word where metadata permits.
- [ ] Separate encounter coverage from mature coverage.
- [ ] Label character knownness as unavailable when Anki field mapping does not support characters.

### Tests and verification

- Common/rare CJK, supplementary-plane Han, kana, Hangul, Latin.
- Font missing glyph.
- Log-scale zero/one/large counts.
- Character in multiple words/titles.
- Anki word match without explicit character inventory.
- Raw source removed.
- Filter/detail/back navigation preservation.
- Grid accessibility and performance.

### End state / definition of done

- A user can tap any encountered target-script character and trace it to words, titles, sessions, and retained source lines.
- Coverage distinguishes encountered, present in Anki, and mature.
- The grid remains usable without color and at large font sizes.
- Supplementary Unicode characters are counted and rendered as one character.

## 27. Phase 16 — Session history, event timeline, source search, and historical actions

### `/goal` objective

> Add a session-history and source-provenance experience that can explain how every total was produced, visualize pauses/lookups/cards/exposure, search retained text and words, safely delete or repair scopes, and reopen or mine historical context when source assets remain available.

### Dependencies

Phases 3–15.

### Session list

- Group by day, then optionally title/media.
- Search title.
- Filter date/media/profile/language/title/status/data quality.
- Summary: start/end, active time, characters, speed, source units, lookups, created cards.
- Status indicators for completed, recovered/abandoned, legacy synthetic, partial indexing, and raw provenance removed.
- Delete one session/day/title selection with preview.

### Session detail

- Header totals and data quality.
- Timeline:
  - exposure volume.
  - known/unknown/maturity ratio where indexed and Anki-ready.
  - pause/background/idle/buffer shading.
  - lookup markers.
  - created/updated card markers.
  - meaningful seek/chapter/page transition markers.
- Source-unit list with title-specific locators/timestamps.
- Event inspector in developer mode only.

### Source search

- Search normalized retained source text.
- Search normalized headword/reading through occurrences.
- Filter title/media/date/language.
- Result shows a bounded excerpt, source locator, session/date, and capability.
- Results never reveal text from incognito or raw-text-deleted scopes.

### Implementation tasks

- [ ] Build paged session list and grouped section queries.
- [ ] Implement timeline downsampling so long sessions do not render one point per event.
- [ ] Define marker collision/aggregation behavior.
- [ ] Add knownness timeline only after index/snapshot joins meet performance budgets.
- [ ] Add source search using an indexed local strategy appropriate to SQLDelight/SQLite; evaluate FTS availability and migration cost.
- [ ] Keep raw text search isolated from summary queries.
- [ ] Implement reopen resolvers:
  - novel range/chapter.
  - manga chapter/page.
  - video episode/timestamp/subtitle track.
- [ ] Validate resolver target before navigation and show a non-destructive unavailable state.
- [ ] Add historical mine actions using the existing editor/confirmation flow.
- [ ] Implement delete preview with affected sessions/time/characters/words/characters/goals.
- [ ] On delete, write tombstones, remove private source data, mark dirty rollups, rebuild, and present progress.
- [ ] Add "delete raw text only" separately.
- [ ] Add session correction tools only for safe fields such as title link; do not permit arbitrary counter edits without an auditable adjustment event.
- [ ] Add local diagnostic export with user-controlled inclusion of titles/text.

### Tests and verification

- Very short and very long sessions.
- Thousands of events and overlapping markers.
- Abandoned/recovered and legacy synthetic sessions.
- Deleted/moved source item.
- Search Unicode normalization and escaping.
- Raw-text deletion.
- Session deletion and exact rollup rebuild.
- Backup/sync tombstone behavior once Phase 19 exists.
- Historical mining from available/unavailable media.
- Incognito absence.

### End state / definition of done

- Summary numbers can be traced to real sessions and source units.
- Timelines explain pauses, lookups, cards, and exposure without freezing the UI.
- Users can search and act on retained context while preserving privacy.
- Deletion is previewable, complete, idempotent, and reflected in all rollups.

## 28. Phase 17 — Goals, pace, projections, streaks, and milestones

### `/goal` objective

> Implement a flexible local goal engine for daily habits, date-bound totals, title completion, card creation, and manual check-ins, with weekday/rest-day targets, pace projections, streaks, milestones, and honest handling of missing/legacy data.

### Dependencies

Phases 10–13. Character/vocabulary goals may additionally depend on Phases 14–15.

### Goal types

1. **Perpetual daily**
   - active minutes.
   - gross/unique/net characters.
   - new cards.
   - sessions.
   - optional new words/characters.
2. **Date-bound total**
   - time, characters, cards, titles/units completed.
3. **Finish title by date**
   - based on total units/estimated characters and recent qualifying pace.
4. **Manual habit**
   - user check-in, independent of captured analytics.
5. **Coverage target**
   - encountered-character or vocabulary Anki coverage, clearly capability-dependent.

### Goal behavior

- Scope by media, language/profile, and optionally title.
- Weekday multipliers including zero-target rest days.
- Start/end dates and timezone.
- Current progress, required pace, rolling 7/30-day pace, projected completion.
- Current/longest streak.
- Earned milestones/trophies.
- Pause/archive without deleting history.

### Implementation tasks

- [ ] Add create/edit/archive flows with metric definition previews.
- [ ] Prevent invalid goal/metric combinations where capability is unavailable.
- [ ] Decide whether a day follows its original timezone or current timezone for streaks; document and test.
- [ ] Implement daily target calculation with weekday multipliers.
- [ ] Define current-day partial progress without declaring failure before the day ends.
- [ ] Implement forecast using a robust recent-window average and minimum data requirement.
- [ ] Show confidence/unavailable rather than a false precise completion date.
- [ ] For title completion, prefer known unit progress; use character estimates only when trustworthy.
- [ ] Store immutable achievement events.
- [ ] Allow goal edits to apply prospectively or restart history; do not silently rewrite earned milestones.
- [ ] Add reminder hooks only if there is an existing notification preference pattern and the user opts in.
- [ ] Add compact Today goal cards to Overview/widget where space permits.
- [ ] Add progress explanations: actual, target to date, needed per remaining active day, and projection assumptions.
- [ ] Add manual check-in with optional local note under privacy controls.
- [ ] Add goal export/backup.

### Tests and verification

- Rest days and fractional multipliers.
- Goal created mid-day/mid-period.
- Timezone/DST and missed days.
- Target reached exactly, exceeded, later stats deletion.
- Archived/reactivated/edited goal.
- Unknown title length and insufficient pace history.
- Legacy data inclusion.
- Capability becomes unavailable.
- Multiple overlapping goals.

### End state / definition of done

- Users can set goals aligned to how they actually consume media.
- Progress, streak, and forecast calculations are deterministic and explained.
- Rest days do not break streaks.
- Missing data or uncertain title length cannot produce confident but false projections.
- Historical achievements survive ordinary goal edits.

## 29. Phase 18 — Anki learning-impact and gap analytics

### `/goal` objective

> Add capability-gated Anki analytics that connect reading exposure, mining, maturity, and available review outcomes; provide actionable missing-word/character reports while labeling lag, coverage, and correlation limitations.

### Dependencies

Phases 7–10 and 14–17. Review/retention sections depend on the Phase 9 discovery spike confirming data access.

### Core reports

Always available when note/card snapshot fields permit:

- Cards created/updated over time.
- Cards and mining rate per title/media.
- Reading-to-card lag.
- Encountered vocabulary/characters present in Anki.
- Maturity distribution.
- High-frequency encountered words/characters missing from Anki.
- Frequently looked-up but unmined items.
- Mined items with little subsequent source exposure.

Conditionally available:

- Reviews and review time.
- Retention.
- Average review time/retention by source title.
- Cards/10k characters.
- Mature words or characters per 100k characters with configurable lag, defaulting to a clearly labeled 21-day maturity window.
- Same-week reading-to-card flow.
- Weekly source/outcome and lagged learning pipeline.

### Missing-item workbench

Filters:

- word vs character.
- title/media/language.
- seen count range.
- global frequency range.
- POS include/exclude.
- name/kana/grammar handling.
- missing-character requirement.
- first/last seen range.
- raw provenance availability.

Actions:

- inspect occurrences.
- open dictionary/Anki search.
- mine from available historical source with confirmation.
- exclude/include.
- export filtered CSV.

### Implementation tasks

- [ ] Build capability matrix and hide/disable only unavailable reports with an explanation.
- [ ] Define source attribution for cards mined outside Chimahon or with ambiguous mapping.
- [ ] Define reading-to-card and card-to-maturity lag formulas.
- [ ] Never infer title attribution solely from note text when no source operation link exists; use `Unattributed`.
- [ ] Add weekly aligned buckets with complete/partial-week labeling.
- [ ] Require minimum sample sizes for retention/speed comparisons.
- [ ] Label all reading/outcome relationships as observational correlations.
- [ ] Add maturity/coverage target settings.
- [ ] Implement missing high-frequency priority formula with inspectable components.
- [ ] Add pagination and CSV export to the missing-item workbench.
- [ ] Reuse word/character detail and historical action flows.
- [ ] Cache expensive aggregate results by snapshot and rollup version.
- [ ] Add data-freshness banner and refresh control.

### Tests and verification

- Cards created in/outside Chimahon.
- Ambiguous word/readings and multi-card notes.
- Partial week and 21-day lag boundary.
- Small sample suppressed/unavailable.
- Stale and partial snapshot.
- Deleted cards/notes.
- Attribution across multiple titles.
- Export privacy and escaping.
- Large inventory/query performance.

### End state / definition of done

- Users can identify high-value encountered material missing from Anki and inspect supporting context.
- Available Anki activity is connected to reading without false attribution or causal claims.
- Capability, freshness, lag, and sample-size limits are visible on every affected report.
- Unsupported retention/review analytics remain absent rather than guessed.

## 30. Phase 19 — Backup, restore, multi-device merge, export, retention, and privacy

### `/goal` objective

> Make immersion statistics operationally durable and user-controlled: integrate backup/restore, implement idempotent multi-device event merge and tombstones, add exports, configurable raw-data retention, scoped deletion, repair tools, and privacy guarantees.

### Dependencies

Phases 1–18 for complete coverage. Identity and tombstone columns must already exist from Phase 1.

### Backup and restore

- Include schema/version, sessions, events, rollups or rebuild metadata, sources according to privacy selection, word/character index, Anki mappings/snapshot policy, goals, exclusions, device identities, and tombstones.
- Restore into empty and non-empty databases.
- Merge by stable IDs, not per-day maximum.
- Rebuild rollups/indexes when imported versions are stale.
- Preserve unknown future fields where the backup framework supports it or reject with a clear compatibility message.

### Multi-device merge

Model after an event-identity approach:

- Session/event/source UUIDs make append merge idempotent.
- Device ID identifies origin, not uniqueness by itself.
- Tombstones prevent deleted history from returning.
- Conflicting same-ID/different-payload records are quarantined and reported.
- Rollups are never merged as truth; events/imported immutable aggregates merge, then rollups rebuild.
- Legacy synthetic sessions use deterministic source/import IDs to avoid duplication.

### Privacy and retention controls

- Incognito remains a hard barrier.
- Per-title stats exclusion before capture.
- Raw text retention independent from aggregate stats.
- Delete:
  - all stats.
  - date range.
  - title/media/profile/language.
  - sessions.
  - raw source text only.
  - Anki cache only.
  - lookup history only where separately supported.
- Export:
  - aggregate CSV/JSON.
  - session/event JSON.
  - word/character CSV.
  - raw text only with explicit opt-in.

### Implementation tasks

- [ ] Extend the existing backup framework with a versioned immersion payload or database-aware backup.
- [ ] Add restore preflight summary and free-space/version checks.
- [ ] Make restore/merge resumable and transactional by bounded chunks.
- [ ] Add post-restore integrity validation and rollup rebuild.
- [ ] Add device ID lifecycle and reset semantics.
- [ ] Implement deterministic legacy import IDs.
- [ ] Implement event/session/source/tombstone merge.
- [ ] Add conflict quarantine table/report and safe resolution.
- [ ] Implement retention worker with dry-run preview.
- [ ] Compact finalized heartbeats/telemetry without changing totals.
- [ ] Add raw-text removal that also updates search indexes/excerpts but retains counters and hashed source identity as required.
- [ ] Add per-title capture exclusion and verify it prevents queue insertion.
- [ ] Add full reset requiring explicit confirmation; use recoverable backup prompt where possible.
- [ ] Add export schema version and metric definitions.
- [ ] Ensure exports escape spreadsheet-formula injection.
- [ ] Ensure no secret Anki/provider tokens or private external identifiers are exported unnecessarily.
- [ ] Add local maintenance UI:
  - database size by category.
  - last backup/repair/index/rollup.
  - integrity status.
  - rebuild/reindex/cleanup.
- [ ] Integrate any TTU/remote sync only after local backup merge tests pass.

### Tests and verification

- Backup/restore empty, populated, mixed legacy/event, and large databases.
- Restore same backup twice.
- Two devices with independent same-day activity.
- Same event received in different order.
- Delete on one device then sync old copy.
- Same ID/different payload conflict.
- Raw text excluded from backup and removed by retention.
- Formula-injection export values.
- Interrupted restore/merge/retention and retry.
- Low disk space and future schema version.
- Post-restore totals/detail equality.

### End state / definition of done

- Backup/restore reproduces totals, sessions, vocabulary, characters, Anki linkage, goals, and exclusions within selected privacy scope.
- Repeating a merge never double counts.
- Deletions converge across devices through tombstones.
- Users can inspect storage, erase raw text independently, export their data, and repair derived state.
- Existing JSON max-per-day merge is no longer used for event-backed history.

## 31. Phase 20 — Performance, battery, accessibility, i18n, and product polish

### `/goal` objective

> Harden the complete stats system for production through measured performance/battery work, database-size controls, accessibility audits, KMR-only internationalization, responsive layouts, error recovery, and comprehensive documentation.

### Dependencies

Phases 1–19.

### Performance and battery

- [ ] Add macro/microbenchmarks for capture enqueue, batch writes, indexing, rollups, Overview, Trends, title detail, vocabulary, character grid, sessions, and search.
- [ ] Profile 1 week, 1 year, and multi-year/100k+ source-unit datasets.
- [ ] Validate indexes with query plans.
- [ ] Remove N+1 cover/metadata/Anki queries.
- [ ] Bound Compose series and use paging/virtualization.
- [ ] Coalesce invalidations and filter changes.
- [ ] Run indexing/repair/retention under appropriate dispatcher/work constraints.
- [ ] Verify recorder does not wake the device excessively or write per frame/scroll tick.
- [ ] Measure database and raw-text growth per hour/10k characters.
- [ ] Add user-visible storage forecast/cleanup only if estimates are stable.

### Accessibility

- [ ] Every chart has a textual summary/table alternative.
- [ ] Every grid cell has meaningful semantics.
- [ ] Maturity/heat/frequency never uses color alone.
- [ ] Validate TalkBack order, focus, gestures, dialogs, and filter announcements.
- [ ] Validate 200% font scale and display size.
- [ ] Respect reduced motion.
- [ ] Use readable contrast through existing UI tokens.
- [ ] Make large glyphs and ruby/readings pronounceable or provide semantic text.

### Internationalization

- [ ] Move every new string to `i18n-kmk/src/commonMain/moko-resources/base/`.
- [ ] Import `tachiyomi.i18n.kmk.KMR`.
- [ ] Add plurals for character, word, card, session, title, chapter, page, episode, day, and hour/minute where natural language requires it.
- [ ] Use locale-aware number/date/duration/percentage formatting.
- [ ] Avoid concatenated/transposed English fragments.
- [ ] Do not edit non-base locale XML.
- [ ] Add translator comments/context where the resource system supports it.

### Reliability and polish

- [ ] Audit every loading/empty/partial/error/stale/rebuilding state.
- [ ] Make section retries independent.
- [ ] Add data-quality glossary and privacy/retention onboarding.
- [ ] Add metric help and versioned export documentation.
- [ ] Add developer integrity/benchmark documentation.
- [ ] Review titles/source excerpts for privacy in Android recents/screenshots where applicable.
- [ ] Add screenshot tests for light/dark/dynamic color, phone/tablet, and large font if supported.
- [ ] Ensure navigation/filter state survives process recreation.
- [ ] Review all analytics for misleading labels, axes, truncated ranges, and denominator omissions.

### Verification gate

At minimum:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
./gradlew testReleaseUnitTest
```

Also run:

- SQLDelight generation/migration tests.
- targeted benchmarks.
- manual reader/player/Anki/backup flows.
- `git diff` check proving no new strings were added to non-base locales.

### End state / definition of done

- Capture and dashboards meet approved measured budgets on representative hardware.
- Battery/database growth is understood and controlled.
- Every screen is usable with TalkBack, large text, reduced motion, and without color.
- Every new user-facing string is a `KMR` base resource.
- Failure, stale, partial, and maintenance states are clear and recoverable.

## 32. Phase 21 — Shadow rollout, parity reconciliation, release, and legacy retirement

### `/goal` objective

> Roll out immersion statistics safely: reconcile hidden dual-write data against existing stores, stage user-visible enablement, monitor local integrity diagnostics, migrate widgets/backups, and retire legacy writers only after quantified parity and rollback criteria are met.

### Dependencies

Phases 0–20.

### Rollout stages

1. **Developer-only capture**
   - event capture on.
   - UI off.
   - old stores authoritative.
2. **Internal shadow**
   - all adapters on.
   - automated reconciliation after sessions/days.
   - indexing/rollup repair exercised.
3. **Opt-in preview**
   - new UI available behind preference.
   - old overview accessible for comparison.
4. **Default UI with dual write**
   - new query path authoritative.
   - legacy writers retained as rollback.
5. **Legacy read-only**
   - stop JSON writes after a release boundary.
   - preserve files and importer.
6. **Legacy retirement**
   - remove obsolete writers only after backup/restore and multi-device migration evidence.
   - retain import compatibility for a documented window.

### Reconciliation metrics

Compare by session/day/title/media:

- active time with explained policy delta.
- net character progress.
- gross character delta where old semantics permit comparison.
- Anki created/updated difference.
- title/date identity mapping.
- sessions missing finalization.
- event/rollup invariant errors.
- OCR/text coverage.
- queue drops and write/index/rollup failures.

### Promotion criteria

- No unexplained duplicate events/sessions in automated fixtures or internal manual runs.
- No incognito writes in unit/integration/manual testing.
- Novel/manga totals reconcile under documented semantic mappings.
- Video capture passes pause/buffer/seek/replay scenarios.
- Legacy import parity passes representative real fixtures.
- Backup/restore and repeat merge preserve exact totals and identities.
- Main-thread capture and screen-query budgets pass on representative hardware.
- Database growth and cleanup behavior are acceptable.
- All mandatory Gradle/format/migration tests pass.
- User documentation explains the metrics and privacy settings.

### Rollback criteria

Disable capture/UI flags or return to old query path if:

- duplicate rate is nonzero without a bounded known cause.
- queue drops exceed the approved threshold.
- database migration/repair corrupts or loses source-of-truth rows.
- incognito writes any record.
- active reading/player performance regresses materially.
- battery or database growth exceeds approved budget.
- backup restore cannot reproduce pre-backup results.

### Implementation tasks

- [ ] Add versioned local rollout flags and safe defaults.
- [ ] Add reconciliation report accessible to developers/testers.
- [ ] Add one-tap local stats diagnostic export with privacy review.
- [ ] Track local health counters without remote raw telemetry.
- [ ] Exercise upgrade from several supported app versions.
- [ ] Validate release build, min SDK device behavior, and database migration.
- [ ] Update home widget and backup authority before stopping legacy writes.
- [ ] Announce legacy read-only transition in release notes.
- [ ] Keep a rollback reader for old JSON through the agreed compatibility window.
- [ ] Delete obsolete code only in a separate, reviewable commit/PR after evidence is captured.

### End state / definition of done

- New stats are authoritative, measured, and recoverable.
- Every supported media/lookup/mining path produces the intended data exactly once.
- Existing users retain their historical totals.
- No legacy writer remains in the hot path.
- Rollback/import compatibility remains documented and tested.

## 33. Final acceptance scenarios

The full program is not complete until these end-to-end scenarios pass on a real or representative Android environment.

### 33.1 Novel reading

1. Open a Japanese novel and read three new ranges for five active minutes.
2. Scroll backward and reread one range.
3. Change font size and rotate.
4. Background for two minutes, return, look up a word, and create one new Anki card.
5. Close the reader.

Expected:

- One completed session unless the configured inactivity gap requires two.
- Active time excludes background.
- Gross characters include reread.
- Unique-source characters do not duplicate the reread.
- Net progress reflects final forward position.
- Reflow/rotation adds nothing.
- One explicit lookup and one created card link to the source.
- Word and character detail show the retained occurrence after indexing.

### 33.2 Manga with partial OCR

1. Read three pages in chapter A; two have OCR.
2. Move to chapter B page 1, whose page index matches chapter A page 1.
3. Revisit a page and wait.

Expected:

- Chapter/page progress includes all four source pages.
- Character totals cover only OCR-backed viewable text and show OCR coverage.
- Same page index in another chapter is distinct.
- Revisit increases gross but not unique-source exposure.
- Idle time is excluded.
- No OCR prefetch text is counted.

### 33.3 Video subtitles, OCR, and replay

1. Play an episode with primary Japanese and secondary English subtitles.
2. Pause on a cue, buffer, seek backward, replay it, then switch tracks.
3. Trigger OCR on a stable on-screen sign and mine a card with audio.

Expected:

- Only configured learning-language text contributes to primary metrics.
- Pause/background/buffer policy excludes inactive time.
- Cue observer duplicates do not count.
- Intentional replay increases gross exposure under policy.
- OCR stable frames do not create repeated source units.
- Card/source links include episode and timestamp; media is referenced rather than copied.

### 33.4 Knownness and maturity

1. Sync Anki with matching unknown/new/learning/young/mature items.
2. Inspect vocabulary and character grids.
3. Revoke provider permission and refresh.

Expected:

- Reading-aware matches show correct tiers and confidence.
- Accessibility labels expose tiers without color.
- Failed refresh retains the last valid snapshot, marks it stale, and does not turn everything unknown.

### 33.5 Goals

1. Create a daily 30-minute goal with a 50% Saturday target and Sunday rest day.
2. Create a finish-title-by-date goal.
3. Cross midnight/DST and edit the target.

Expected:

- Daily targets and streaks respect weekday multipliers.
- Rest day does not break a streak.
- Forecast explains its pace window and becomes unavailable when the title length is unknown.
- Earned milestones are not silently rewritten by edits.

### 33.6 Privacy and deletion

1. Read in incognito, perform a lookup, and mine externally.
2. Read normally, then delete raw source text while retaining totals.
3. Delete one session.

Expected:

- Incognito creates no local stats rows of any kind.
- Raw-text deletion removes search/excerpts but preserves counters and clearly marks unavailable provenance.
- Session deletion changes every dependent rollup/index/goal calculation exactly once and creates a sync tombstone.

### 33.7 Backup and multi-device merge

1. Device A and B read different content on the same local date.
2. Merge both backups twice.
3. Delete a session on A and merge again with an older B copy.

Expected:

- Both devices' activity is summed once.
- Repeating the merge changes nothing.
- The tombstone prevents deleted data from returning.
- Rebuilt rollups match event totals.

## 34. Required test matrix

| Layer | Required coverage |
|---|---|
| Pure domain | Unicode, normalization, metric formulas, state machine, streaks, goals, maturity |
| SQLDelight | fresh schema, migration, constraints, indexes, idempotency, deletion, paging |
| Repository | transaction boundaries, repair, rebuild, import, sync merge |
| Capture adapters | lifecycle, dedup, source identity, incognito, failure isolation |
| Indexing | tokenizer versions, unsupported language, retry, reindex split/merge |
| Queries | filters, local calendar, zero buckets, unavailable/legacy/data quality |
| Screen models | loading/partial/error/filter/navigation restoration |
| UI | accessibility semantics, large font, dark/light, empty/large data |
| Performance | enqueue, writes, index, rollup, query, render, database growth |
| Integration | novel, manga OCR, video, dictionary, AnkiDroid, widget |
| Operational | backup, restore, repeat merge, tombstones, retention, export |

Every implementation phase that changes Kotlin/XML must finish with:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleDebug
```

Schema phases also run:

```bash
./gradlew :data:generateSqlDelightInterface
```

Release-hardening phases add relevant unit/integration suites, including `testReleaseUnitTest`.

## 35. Suggested `/goal` execution order

Use one goal per phase in order. A concise start command can use the exact objective sentence from each phase. Recommended grouping if fewer goals are desired:

1. **Contract and persistence**: Phases 0–2.
2. **Recorder and media capture**: Phases 3–7.
3. **Index, Anki, and query engine**: Phases 8–10.
4. **Core dashboards**: Phases 11–13.
5. **Vocabulary, characters, and provenance**: Phases 14–16.
6. **Goals and learning analytics**: Phases 17–18.
7. **Operations and release**: Phases 19–21.

Do not collapse Phases 0–10 into one code change. Schema, capture, migration, indexing, and rollups need independently reviewable failure boundaries.

### Goal handoff template

Append this to any phase objective when starting `/goal`:

> Work only in the `feat/stats` worktree. Preserve unrelated changes. Read `AGENTS.md` before edits. Keep the new system behind the applicable feature flags unless this phase explicitly changes rollout state. Use `KMR` and only `i18n-kmk/.../base/` for new strings. Add focused tests, run SQLDelight generation after schema changes, then run `spotlessApply`, `spotlessCheck`, and `assembleDebug`. End with a reconciliation or evidence report against the phase's definition of done; do not claim unavailable device/Anki validation was performed.

### Phase handoff artifact

Each goal should leave:

- Code and tests.
- A short implementation note with schema/capture/query versions changed.
- Migration/rollback impact.
- Commands run and results.
- Measured performance where relevant.
- Manual validation completed and explicitly unvalidated items.
- Any metric-contract change proposed for review.
- Next phase blockers.

## 36. Open product decisions

Resolve these at the named phase; do not make silent implementation guesses.

| Decision | Deadline | Recommended initial choice |
|---|---|---|
| Raw source text default retention | Phase 0/1 product review | Explicit first-run disclosure; default retained locally for drill-down, with immediate `Never` option |
| Reader idle timeout | Phase 0 | 120 seconds, user-configurable |
| Video buffering grace | Phase 0/3 | Short grace such as 5 seconds, then inactive |
| Gross replay hysteresis | Media phases | Require source unit exit and genuine re-entry/seek |
| Novel net backward behavior | Phase 0/4 | Preserve signed position delta but report gross separately |
| Unique-source scope default | Phase 0/10 | Global first exposure for "new"; selected range/title scopes available |
| Maturity threshold | Phase 9 | 21-day interval, configurable |
| Multi-card maturity aggregation | Phase 9 | Highest qualifying interval, with documented setting |
| Names/kana excluded by default | Phase 8/14 | Include in totals; offer obvious filters |
| Raw event retention | Phase 19 | Indefinite until measured size justifies a default |
| Stats sync transport | Phase 19 | Reuse existing backup/sync transport; event merge remains transport-neutral |
| Difficulty source | Phase 13 | Optional manual/metadata field; do not infer from speed |
| External-player stats | Phase 6 | Mark unsupported unless reliable callbacks exist |

## 37. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Ambiguous character semantics | Misleading speed/growth | Separate gross, unique-source, net, distinct, and new metrics |
| UTF-16 counting | Wrong non-BMP totals | Code-point iteration and fixtures |
| UI lifecycle duplicates | Inflated activity | Stable source IDs, event UUIDs, sequence/idempotency tests |
| OCR gaps | False zero manga volume | Capability/coverage fields and unavailable state |
| Tokenizer changes | Vocabulary history drift | Versioned indexing, reindex, rebuild, metric version |
| Anki API limits | Missing maturity/review fields | Discovery spike and capability-gated UI |
| Provider failure | Apparent knowledge loss | Atomic snapshots and stale last-known-good cache |
| Raw text privacy/storage | Sensitive/large DB | Separate retention, disclosure, delete-raw-only, no logs |
| Main-thread/battery regression | Worse reading experience | Async bounded batches, benchmarks, work constraints |
| Multi-device double counting | Untrustworthy totals | Event/session UUIDs, deterministic imports, tombstones |
| Legacy migration fabrication | False drill-down | Synthetic aggregate sessions with explicit quality |
| Rollup drift | Inconsistent tabs | Rebuildable caches, applied IDs, integrity checks |
| Huge Compose datasets | Jank/OOM | Rollup queries, paging, virtualized grids, downsampling |
| Misleading causal analytics | Bad learning decisions | Observational labels, lag/sample thresholds, no causal wording |
| Upstream merge conflicts | Maintenance cost | Small KMK islands, domain boundaries, focused commits |

## 38. Definition of complete

This initiative is complete only when:

- Novel, manga, and video capture meet the shared metric/session contract.
- Character counts use Unicode code points and distinguish gross, unique-source, net, distinct, and new.
- Word and character provenance is queryable from title to session to source unit.
- Lookups and Anki operations are explicit, linked, and idempotent.
- Anki knownness/maturity is capability-aware and last-known-good.
- Overview, Trends, Titles, Vocabulary, Characters, Sessions, Goals, and available Anki reports are production-ready.
- Legacy totals import once and reconcile without fabricated detail.
- Backup/restore and repeated multi-device merges do not double count.
- Incognito writes nothing.
- Raw text can be erased independently.
- Rollups rebuild deterministically.
- Accessibility, localization, performance, battery, storage, and release gates pass.
- The old JSON writers have completed the staged retirement process, while documented import compatibility remains.
