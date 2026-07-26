# Legacy immersion-statistics import

## Source inventory

The compatibility importer reads, but never edits or deletes, these existing files:

| Source | Location | Legacy identity | Time unit |
|---|---|---|---|
| Novel | `files/novels/<book directory>/statistics.json` | book directory plus local date | seconds as `Double` |
| Manga | `files/manga_stats.json` | manga ID plus local date | milliseconds as `Long` |
| Anki | `files/anki_stats.json` | profile, optional title, media kind, and local date | no reading time |

Novel `statistics.json` contains `Statistics`; the two global files contain `MangaStats`
and `AnkiStats`. The old backup/restore merge code remains authoritative for the files
and continues its field-by-field maximum merge. The SQL importer does not change that
behavior.

## Import contract

- Each physical file is hashed with SHA-256.
- Large global files are checkpointed by logical title/profile group. Each checkpoint
  uses the physical content hash and a non-sensitive logical source key.
- A deterministic UUID is derived for every legacy title and synthetic daily aggregate.
  Retrying the same hash returns `ALREADY_IMPORTED`; a changed file hash updates the
  same aggregate rather than duplicating it.
- The aggregate rows and `immersion_import_ledger` entry commit in one transaction.
  A crash before the ledger insert rolls back titles and sessions too.
- Array elements are decoded independently with unknown fields ignored. Missing fields,
  invalid dates, invalid counters, and corrupt documents produce typed counts without
  storing source text.
- Duplicate legacy rows retain current screen parity by summing their time, character,
  and card values into one daily aggregate.

The importer runs as unique WorkManager work whenever event-backed capture is enabled.
The dashboard remains independently opt-in, and current legacy writers stay active as
the safe-shadow rollback path.

## Synthetic aggregate semantics

Imported rows use `legacy_import = 1`, `legacy_metric_quality =
LEGACY_AMBIGUOUS`, and an explicit `legacy_local_date`. The original novel seconds are
kept in `legacy_reading_time_seconds`; the normal duration column stores the rounded
millisecond representation used by the existing UI. Combined old Anki counts are stored
in `legacy_cards_total`, not as card-create operations.

The required session timestamp is only a local-calendar anchor. `ended_at` remains null
because old files do not contain real session boundaries. The anchor zone and offset are
stored, so date interpretation is deterministic across DST boundaries.

No event, source unit, word, character, lookup, or Anki-operation row is fabricated.
Legacy detail is therefore unavailable rather than falsely empty.

## Compatibility and reconciliation

`GetLegacyAggregateTotals` applies the common date/media/profile/title filter and honors
`StatsFilter.includeLegacyAggregates`. `LegacyStatsImporter` compares the parsed source
totals with persisted SQL rows after every run. Its report contains only source keys,
record indexes, typed issue codes, and numeric expected/actual values; it can be exported
for developer diagnosis without leaking source text.

The last ledgered rows and original JSON remain available if a later query-path rollout
is disabled. Normal database backup restoration is the schema rollback mechanism.
