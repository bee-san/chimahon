# Immersion statistics boundary map

This inventory is the Phase 0 ownership map for the pre-event-store implementation at revision `efd462b56eb291d6bfbba458eeee78035c441728`. Paths are relative to the repository root.

## Current aggregate stores

| Store | Physical location | Current write boundaries | Current read/merge boundaries | Phase owner |
|---|---|---|---|---|
| Novel `List<Statistics>` | Per-book `novels/<book>/statistics.json` through `BookStorage` | `ReaderViewModel.persistToDisk`; `NovelMigration`; `BookImporter`; backup restore; TTU sync import/merge | Novel reader initialization/reload; `StatsScreenModel`; `StatsTitlesScreen`; novel library/widget; backup creator; TTU sync/export | `NovelCaptureAdapter` for new events; `LegacyStatisticsImporter` for old aggregates; legacy writer stays during dual-write |
| Manga `List<MangaStats>` | App files `manga_stats.json` through `MangaStatsStorage` | `ReaderViewModel.trackMangaStats`; backup restore merge | `StatsScreenModel`; `StatsTitlesScreen`; `MangaStatsSheet`; reading-stats widget; backup creator | `MangaCaptureAdapter`; `LegacyStatisticsImporter`; legacy writer stays during dual-write |
| Anki `List<AnkiStats>` | App files `anki_stats.json` through `AnkiStatsStorage` | Successful create and overwrite branches in `AnkiCardCreator`; backup restore merge | `StatsScreenModel`; reading-stats widget; backup creator | `AnkiOperationRecorder`; `LegacyStatisticsImporter`; existing combined totals remain legacy-only |

`BookStorage.load` currently treats missing, malformed, or incompatible JSON as absent by returning `null`. `MangaStatsStorage` and `AnkiStatsStorage` convert that to an empty list. Merge behavior takes per-field maxima because no device or event identity exists. Phase 2 must preserve those compatibility semantics only in the legacy layer.

## Capture and lifecycle boundaries

| User behavior | Existing boundary | Current semantics/risk | Assigned adapter |
|---|---|---|---|
| Novel session start/stop/background | Chimahon `ReaderViewModel`, `NovelReaderActivity`, `ReaderStatisticsTracker` | Timer and signed explored-position delta; lifecycle is implicit | `NovelCaptureAdapter` using the shared `ImmersionRecorder` |
| Novel range visibility/navigation | Chimahon WebView bridge, chapter/progress callbacks, `ReaderViewModel.loadChapter`/bookmark paths | Position is available, but stable displayed range identity is not yet emitted | Novel locator adapter near the reader bridge; parser revision owner in Chimahon |
| Novel image OCR lookup | `NovelImageViewer` callback into host lookup UI | Explicit tap and image/source context exist | Novel lookup intent adapter |
| Manga page transition/time | Komikku `ReaderViewModel.trackMangaStats` | Caps a page at 120 seconds, requires >500 ms, deduplicates only page index, uses UTF-16 length, zeroes missing OCR | `MangaCaptureAdapter` near reader lifecycle/page callbacks |
| Manga OCR visibility | `ReaderPageImageView`, pager/webtoon holders, OCR cache/block models | OCR cache/prefetch and visible results must be separated | Manga OCR source adapter; only viewable blocks emit exposure |
| Video playback lifecycle | `PlayerViewModel`, activity/player callbacks | Has play/pause/buffer/seek/episode context but writes no stats | `VideoCaptureAdapter` |
| Subtitle cue visibility | `PlayerViewModel` cue/history state and `PlayerControls` | Parsed/history cues are not equivalent to active visible cues | Subtitle source adapter at the active-cue UI boundary |
| Video OCR visibility | `PlayerVideoOcrOverlay` and player OCR state | Stable frames may repeat the same text | Video OCR source adapter with frame/region/hash hysteresis |
| Global/app incognito | `BasePreferences.incognitoMode`; reader/player cached state and lifecycle observers | Existing history guards are distributed | `ImmersionCapturePolicy` before recorder queue insertion; adapters finalize on barrier change |

## Explicit lookup boundaries

`DictionaryRepository.lookup` is intentionally not an instrumentation point because recursive lookup, warmup, prefetch, tokenization, and internal dictionary work also call it.

The Phase 7 `LookupTelemetry` owner attaches to explicit UI intent and records once per request:

- Novel text selection and novel image OCR host callbacks.
- Manga OCR tap in `ReaderPageImageView`/`OcrLookupPopup`.
- Active subtitle tap in `PlayerControls`/`PlayerSubtitleLookupPopup`.
- Video OCR tap in `PlayerVideoOcrOverlay`.
- Standalone `DictionaryTab` submission or recursive user-selected lookup, with no source locator when none exists.
- Screen-lookup overlay user selection, explicitly marked outside a reading session unless a supported context exists.

Result selection may later enrich the request with normalized headword/reading. Dictionary engine calls remain uninstrumented.

## Anki boundaries

`AnkiCardCreator.addToAnki` owns the external operation boundary. It currently calls `AnkiStatsStorage.addCard` in both the overwrite and new-note success branches, which makes legacy totals ambiguous.

The new `AnkiOperationRecorder` will:

- allocate an operation UUID before invoking Anki;
- distinguish create, update, duplicate, open, and external failure;
- link the current session/source locator passed by novel, manga, subtitle, or video OCR UI;
- reuse the operation UUID if Anki succeeds but local persistence retries;
- suppress all local linkage in incognito while leaving the user's explicit external Anki action alone.

`DictionaryTab` and `OcrLookupPopup` remain UI initiators; neither writes immersion rows directly.

## Current read surfaces

| Surface | Existing owner | Phase migration |
|---|---|---|
| Main Stats overview | `StatsScreenModel`, `StatsScreen`, `StatsScreenContent` | Compatibility query in Phase 2; rollup query/UI replacement in Phase 11 |
| Stats titles | `StatsTitlesScreen`, `StatsTitlesContent` | Unified title repository/query in Phases 10 and 13 |
| Manga in-reader sheet | `MangaStatsSheet` | Capability-aware session/title summary after manga capture |
| Novel statistics sheet | Chimahon `StatisticsSheet` | Shared session/metric semantics after novel capture |
| Home reading-stats widget | `ReadingStatsWidget`/receiver | Same daily rollup and basis as Overview in Phase 11 |
| Novel library progress/stat snippets | `NovelLibraryScreenModel`, `NovelProgressWidget` | Keep progress separate; migrate totals only when query parity exists |
| Backup/restore | backup creators/restorers and `BackupRestorer` | Legacy files retained; event payload added in Phase 19 |
| TTU sync | `TtuSyncManager` | Legacy max merge remains isolated; event merge waits for Phase 19 |

## Ownership rules

- Capture adapters submit typed commands; they never manipulate SQLDelight rows.
- The recorder owns lifecycle, sequence assignment, batching, privacy, and failure isolation.
- The data repository owns transactionality and idempotency.
- Indexing owns token/character derivation and cannot block base exposure persistence.
- Rollups are derived caches and query surfaces never infer unavailable detail from legacy totals.
- New Komikku UI strings use `KMR` from `i18n-kmk/.../base/` only.
