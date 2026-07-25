# Immersion text indexing

Capture never waits for indexing. Retained source units are claimed later in bounded batches by a
unique WorkManager job. Claims, retry attempts, tokenizer identity/version, normalization version,
confidence, terminal capability, and next-attempt time are persisted per source unit.

## Normalization and characters

Normalization creates a separate NFC view; it does not mutate retained display text. It normalizes
line endings, collapses whitespace, and can optionally cap repeated-character runs. Version 1
iterates Unicode scalar values rather than UTF-16 code units. Letters and numbers are countable.
Whitespace, punctuation, symbols, controls, combining marks, and variation selectors are excluded.
Per-script counts, distinct characters, occurrence counts, and first ordinals are produced in the
same pass. Supplementary-plane characters remain single identities.

## Vocabulary capability

- Japanese uses a small adapter over the existing HoshiDicts-backed dictionary repository. It
  performs longest available matching from each position, retains expression/reading/POS,
  deinflection and optional local frequency, and never crosses the user-lookup telemetry boundary.
- Languages with safe Unicode word boundaries use a `unicode-boundary-low-confidence` fallback.
  These tokens are explicitly confidence `0.35` and are not presented as dictionary-normalized.
- Korean, Chinese, and unknown language remain character-capable but lexically
  `UNSUPPORTED_LANGUAGE` until a stable analyzer adapter is provided.
- Missing retained raw text is terminal `RAW_TEXT_UNAVAILABLE`; capture totals remain intact.

Japanese word identity is `(language, NFC headword, normalized reading)`. Katakana readings are
normalized to hiragana, absent readings use an empty component, and homographs with different
readings remain distinct. Frequency/JLPT/grade are optional enrichments, never identity fields.

## Reindex and repair

Index results replace all word/character occurrences for a source in one transaction. First/last
seen values are recomputed from source provenance, and old orphans are removed only after the new
result is valid. This makes tokenizer split/merge upgrades and replayed work idempotent.

Failures retain a typed cause and exponential next-attempt time. Explicit reindex can select
language, title, and exposure date range, reports progress, and honors cancellation. Terminal
capabilities are requeued by the same command when support changes.

Exclusions are matched after normalization and before persisted denominators. Global, language,
and title scopes are supported for both stable word identities and Unicode code points.
