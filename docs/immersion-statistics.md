# Immersion statistics

Chimahon records local, event-backed immersion statistics for novels, manga, and
video. Open **More → Statistics** to view the dashboard and use the settings
button there for exports, retention, integrity checks, and repair tools.

## What the metrics mean

- **Active time** counts foreground, unpaused reading or playback time. Idle,
  background, paused, and buffering time is excluded.
- **Gross characters** counts every captured source exposure, including
  rereads and replays.
- **Unique-source characters** counts a source unit once. A source unit is a
  novel range, manga page or OCR block, subtitle cue, or video OCR region.
- **Net characters** represents forward progress where the reader can supply a
  stable position. It is not available for every source.
- **Speed** is the selected character count divided by active time. Rankings
  suppress tiny denominators.
- **New words and characters** use the first indexed encounter across all
  retained event-backed history. They differ from the number unique to one
  title.
- **Anki coverage and maturity** are point-in-time observations from the most
  recent Anki inventory snapshot. Reading-to-card timing is an association, not
  proof that reading caused a learning outcome. Review-history and retention
  reports remain unavailable when the provider cannot supply those facts.
- **Legacy totals** come from the previous aggregate stores. They preserve
  historical time and character totals but cannot invent source lines,
  vocabulary, character, lookup, or Anki provenance.

All dates use the local date and offset recorded with the event. Empty calendar
buckets are shown as zero rather than silently removed.

## Privacy and retention

Stats stay on the device unless you explicitly back up, export, or sync them.
Incognito is a hard capture barrier. A title can be excluded before it enters
the recorder queue.

Aggregate counters and retained source text are separate:

- **Never retain** removes source text while preserving counts and stable
  source hashes.
- **30 days** and **one year** remove text older than the selected window.
- **Until deleted** retains text until you remove it.

Deleting retained text also removes its search document and excerpt. It does
not change aggregate totals. Deleting a session previews affected sessions,
time, gross characters, source units, words, characters, and goals. Deletion
fails closed if the database changes after that preview, writes sync
tombstones, removes source context that is not shared with another session,
and rebuilds affected totals. A full reset keeps tombstones so an old device or
backup cannot resurrect deleted history.

Goal forecasts use a recent 30-day pace, normalize fractional weekday targets,
include zero-progress active days, and cap extreme high-volume days. Goal cards
show the sample size, remaining active days, required pace, and confidence.
Insufficient history or unknown title length stays unavailable.

## Backup, restore, and export

The normal Chimahon backup can include a versioned immersion archive. Retained
source text is a separate, explicit privacy choice. Restore rejects newer
unsupported schemas, checks free space, merges stable IDs in bounded
transactions, quarantines same-ID/different-payload conflicts, applies
tombstones, and rebuilds derived totals.

Available exports:

- aggregate JSON or CSV;
- session/event JSON without source text;
- vocabulary or character CSV;
- session/event JSON with source text after an explicit warning.

CSV values are protected against spreadsheet formula injection. Exports omit
Anki/provider credentials and unrelated private identifiers.

## Historical actions and unavailable data

Search results and word/character occurrences can reopen an installed novel,
manga page, episode timestamp, subtitle cue, or video OCR timestamp. Chimahon
validates the target before navigation. If a source has moved or been deleted,
the stats remain but the action reports that the source is unavailable.

When retained text exists, **Look up again** opens the existing lookup and
mining confirmation flow. Statistics never retain copyrighted audio, images,
or video solely to make future mining possible.

## Data quality and maintenance

Every dashboard section reports relevant legacy, indexing, raw-text, OCR, and
Anki coverage. “Unavailable” means Chimahon lacks the evidence required to
calculate the metric; it is not zero.

The maintenance screen shows database and private-text size, integrity status,
quarantined conflicts, and cleanup state. Rollups and the word/character index
can be rebuilt from source events. These operations are resumable background
work and do not replace the source-of-truth rows.

## License

Original code and documentation authored for the immersion-statistics feature
are available under the repository's scoped
[MIT license](../STATS-LICENSE.md). Inherited and unrelated Chimahon code keeps
its existing license.
