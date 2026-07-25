# Chimahon statistics code license

Copyright (c) 2026 Autumn Skerritt

All original code, tests, database migrations, documentation, and other
materials authored for Chimahon's immersion-statistics feature are licensed
under the [MIT License](LICENSES/MIT.txt).

This grant covers:

- the statistics implementation under
  `domain/src/**/tachiyomi/domain/immersion/`,
  `data/src/**/tachiyomi/data/immersion/`,
  `app/src/**/mihon/feature/stats/`, and
  `chimahon/src/**/com/canopus/chimareader/stats/`;
- new statistics-specific screens, navigation, workers, services, tests,
  database migrations, and documentation introduced by the `feat/stats`
  development series; and
- original statistics-specific additions made by that development series
  inside pre-existing Chimahon files.

This does not relicense any inherited or unrelated code. The repository's
existing licenses continue to apply to that material, including the root
GPL-3.0 license. Where a file contains both inherited code and new statistics
code, this MIT grant applies only to the original statistics-specific
contribution, not to the pre-existing contents of the file.
