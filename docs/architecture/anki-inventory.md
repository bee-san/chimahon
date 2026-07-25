# AnkiDroid inventory and maturity contract

Research snapshot: 2026-07-25
AnkiDroid source revision: `8409ef81cc2b1f8992f06c5711df6cc0ea86fe87`

## Supported provider surface

The implementation was checked against AnkiDroid's official
`FlashCardsContract` and `CardContentProvider`, not an inferred database schema.
The current provider exposes:

- Notes: note ID, note-type ID, fields, tags, and note modification time.
- Cards: card ID, note ID, deck ID, card type, queue, interval, raw due,
  repetitions, lapses, and SM-2 ease.
- Configuration: deck IDs/names and note-type IDs/names/field order.

Relevant upstream sources:

- <https://github.com/ankidroid/Anki-Android/blob/8409ef81cc2b1f8992f06c5711df6cc0ea86fe87/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt>
- <https://github.com/ankidroid/Anki-Android/blob/8409ef81cc2b1f8992f06c5711df6cc0ea86fe87/AnkiDroid/src/main/java/com/ichi2/anki/provider/CardContentProvider.kt>
- <https://github.com/ankidroid/Anki-Android/blob/8409ef81cc2b1f8992f06c5711df6cc0ea86fe87/AnkiDroid/src/main/AndroidManifest.xml>

The API library at this revision compiles with SDK 36 and supports API 16. The
Chimahon integration still targets its own minimum SDK 26. Compatibility is
capability-probed at runtime instead of assuming a particular AnkiDroid version:
missing columns produce `UNSUPPORTED_PROVIDER`, not an empty known-word set.

## Permission and unavailable states

Chimahon declares AnkiDroid's dangerous
`com.ichi2.anki.permission.READ_WRITE_DATABASE` permission. The existing Anki
settings switch checks installation, requests the system permission, and only
enables the profile after a grant. The inventory probe distinguishes:

- integration disabled;
- AnkiDroid not installed;
- permission denied or revoked;
- provider contract unsupported;
- field/deck/note-type configuration invalid;
- partial result;
- transient provider failure.

Disabled and unavailable states resolve to `UNAVAILABLE`. They never resolve to
an all-unknown inventory.

## Snapshot strategy

Note modification time is available, but card modification time is not. Maturity
can change without a note edit, so an incremental note-only refresh would be
incorrect. Each refresh therefore:

1. Resolves the configured deck, note type, and field order.
2. Reads matching notes and the required card scheduler columns.
3. Rejects null, partial, unsupported, or over-250,000-row results.
4. Normalizes expression and reading with the immersion index normalizer.
5. Builds all items before one SQL transaction activates the snapshot.
6. Marks the previous valid snapshot stale only after a failed attempt, while
   retaining its items.

UI queries only the local snapshot. There are no per-word provider calls.
Refresh runs at most daily under a battery-not-low constraint; manual refresh,
cancel, retry, threshold recompute, and cache clear controls are available.

## Maturity and matching

- `NEW`: card type or queue is new.
- `LEARNING`: learning or relearning type/queue.
- `YOUNG`: review card below the configured interval.
- `MATURE`: review card at or above the configured interval (default 21 days).
- `UNKNOWN`: unsupported card state or no matching item in an available cache.

The default multi-card rule uses the highest qualifying card interval. A strict
lowest-card rule is configurable. Changing the interval threshold recomputes the
local snapshot without querying AnkiDroid.

The primary key is language, normalized expression, and normalized reading.
Cards without a configured reading fall back to headword-only matching and are
labeled `HEADWORD_ONLY` or `AMBIGUOUS`.

## Explicitly unavailable data

The public provider does not expose revlog/review-history rows. Review retention,
review duration, answer ease history, and causal learning reports are therefore
capability-gated and deferred. Raw `due` is retained only as scheduler state; it
is not presented as a normalized date.

## Query-cost measurement checklist

Every completed snapshot stores note-query time, card-query time, item count,
and note count. Real provider cost must be measured on an Android device because
JVM mocks cannot reproduce AnkiDroid collection/backend work.

For each collection size (1,000, 10,000, and 100,000 cards):

1. Use one configured deck and note type with expression and reading fields.
2. Start from idle, keep the device off charge, and record AnkiDroid/Chimahon
   versions and device model.
3. Run three manual refreshes, recording note time, card time, total time,
   peak memory, cancellation behavior, and battery change.
4. Revoke permission during a refresh and confirm the old snapshot remains.
5. Rename/delete the deck and note type and confirm typed configuration errors.
6. Restore configuration and confirm retry atomically replaces the stale cache.

No physical Android device is attached to the development environment, so these
three live measurements remain a release-verification item rather than a mocked
claim.
