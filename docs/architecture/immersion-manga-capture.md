# Manga immersion capture

Manga capture uses two independent source units:

- A page unit is `(manga ID, chapter ID, page index)`. It records progress and active reading even when OCR is disabled, unsupported, empty, or failed.
- An OCR unit adds `(engine ID, engine version, stable block ID, normalized text hash)`. Only OCR units contribute character totals.

Page indices are never compared without their chapter ID. OCR engine or text changes intentionally create a new source identity. Layout-only changes retain the stable OCR block ID and do not create a new identity.

## Visibility

Pager pages and both members of a spread are fully visible. In webtoon mode, a page qualifies when at least half of the smaller of its rendered height and the viewport height is visible. The adapter retains its normalized visible vertical range.

An OCR block is exposed only while at least half of its rendered page-space area is inside that range. Leaving and later re-entering the threshold records another gross exposure. Unique-source characters remain deduplicated globally. Rotation and repeated layout callbacks while the block remains visible do not add exposure.

OCR cache population, neighboring-page prefetch, and background chapter recognition may supply data to the adapter, but cannot create exposure while the associated page is not visible.

## Availability and coverage

Every page source records an OCR capability state:

- `AVAILABLE` means OCR text was associated with the visible page.
- `PARTIAL` means OCR was supported but was not requested or measured.
- `UNAVAILABLE` means OCR was unsupported or failed.

OCR sources persist engine/version, optional confidence, and a quality state. A missing confidence score is `PARTIAL`, not an invented score.

Coverage is `pages with non-empty measured OCR / viewed pages`. Character totals are unavailable when coverage is absent; a missing OCR measurement is never displayed as a measured zero.

## Time, navigation, and completion

The shared recorder owns active-time allocation and idle/background exclusion. Manga capture does not add a minimum duration, so rapid flips cannot fabricate reading time. Page exposure, forward progress, seek/revisit, chapter completion, and title completion are distinct events.

Reader dialogs, statistics, lookup overlays, manual pause, application backgrounding, and incognito use the same scoped session pause/suppression rules as other media adapters.

`MangaStatsStorage` remains a compatibility dual-write during rollout. Its page key is now chapter plus page index and spreads include both pages. The hidden reconciliation report marks continuous-scroll and reread sessions non-comparable because the legacy policy intentionally suppresses those gross exposures.
