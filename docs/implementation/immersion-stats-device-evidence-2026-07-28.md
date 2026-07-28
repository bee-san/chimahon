# Immersion statistics device evidence — 2026-07-28

Run date: 2026-07-28

App commit under test: `2151ec33c2b897a1157cb16f422aba915f529127`

Build variant: `debug` (`app.chimahon.dev`), ABI `x86_64`

Branch: `test/release-device-evidence`

This document records device-evidence runs performed against the `v2.5.0`
statistics candidate. It is a factual log, not an approval. **No entry in
`docs/immersion-stats-release-validation.json` is set to `true` by this
document**, and `releaseGate` remains `blocked`. See
[Why no matrix row flips](#why-no-matrix-row-flips).

The runs below are recorded in that manifest under `unacceptedRuns`, not
`evidence`. The `evidence` array is reserved for accepted, gate-covering
evidence and is enforced as such by
`:app:verifyImmersionStatsReleaseValidationMetadata`, which requires
`result: "pass"`, a non-empty `covers` list of matrix keys, and artifacts at
immutable HTTPS URLs with SHA-256 values. These runs satisfy none of those
three conditions, so putting them there would be a false claim — and the
validator correctly rejects the attempt.

## Environment

| Property | Value |
|---|---|
| Device | Android emulator AVD `chimahon_api26` |
| System image | `android-26/google_apis/x86_64` |
| API level / release | 26 / 8.0.0 |
| Reported model / product | `Android SDK built for x86_64` / `sdk_gphone_x86_64` |
| Emulator version | 36.6.11.0 (build 15507667) |
| Host acceleration | **None.** `/dev/kvm` is absent and the host CPU exposes no `vmx`/`svm`; the AVD runs with `-accel off` (pure software emulation) |
| JDK | Temurin 17.0.19 |

The host is a virtual machine without nested virtualization. This is the single
most important qualifier on everything below.

## Commands

The Gradle-managed path (`:app:connectedDebugAndroidTest`) failed on this host
with UTP `ErrorCode 2002` "Failed to install APK" before any test executed
(`Starting 0 tests` / `Finished 0 tests`). The same APK installs successfully
via `adb`, so the failure is an install timeout under software emulation rather
than a packaging defect. Tests were therefore driven directly:

```bash
# build
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -Pandroid.injected.build.abi=x86_64

# install
adb -s emulator-5556 install -r -t app/build/intermediates/apk/debug/app-x86_64-debug.apk
adb -s emulator-5556 install -r -t app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk

# run one evidence class at a time
adb -s emulator-5556 shell am instrument -w -r \
  -e class <test class> \
  app.chimahon.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Only `emulator-5556` (`chimahon_api26`) was targeted. An unrelated emulator
belonging to another task was attached as `emulator-5554` throughout and was
never installed to, instrumented, or stopped.

## Runs

### 1. Repository smoke — `ImmersionStatsDeviceValidationTest`

Result: **OK (1 test)**, 1.698 s.

Persists a title, session, source unit, and exposure through the production
`SqlDelightImmersionRepository`, then asserts the Overview query returns the
expected gross, unique-source, net, session, and source-unit counts on-device.

Artifact: `device-smoke-api26.json`
SHA-256: `5aa71237dc931a45b13eed074fa546ac55203614cebbf96a173fb58cb341dd37`

Measured: `repositoryWriteNanos` 552 176 818; `repositoryOverviewQueryNanos`
34 254 136; `databaseBytes` 487 888.

### 2. Scale, growth, and timeline — `ImmersionStatsDeviceScaleTest`

Result: **OK (1 test)**, 144.484 s.

Writes 2 500 source units of 40 distinct CJK code points each (100 000 gross
characters) through the production repository, then queries Overview and a
120-bucket session timeline. Asserts the timeline is bounded, non-empty, and
that its gross characters reconcile exactly with the Overview total.

Artifact: `device-scale-api26.json`
SHA-256: `18004b3528d177ea8481899bc9fe0474cdba5726f12caab788f5bcff508a0f39`

| Measurement | Value |
|---|---:|
| Source units | 2 500 |
| Gross characters | 100 000 |
| Estimated raw text (UTF-8) | 300 000 B |
| Database before | 458 512 B |
| Database after | 8 482 816 B |
| **Database growth** | **8 024 304 B** |
| **Growth per source unit** | **3 209 B** |
| Timeline buckets returned | 120 of 120 requested |
| Timeline knownness present | yes |

The byte measurements are deterministic and do not depend on CPU speed, so the
growth figures are meaningful. The nanosecond figures in the artifact are not.

### 3. Live AnkiDroid provider — `ImmersionStatsAnkiDroidProviderTest`

Result: **OK (1 test)**, 1.902 s.

Runs the production `AnkiDroidInventoryProvider` against the officially
published AnkiDroid APK — not a mock (`"mocked": false`).

AnkiDroid APK: `2.24.0` x86_64,
SHA-256 `b8aaef8c8ed13e96b7bbafbc46e690490684192147ab445db8a193c4ef6989b0`

Artifact: `device-ankidroid-api26.json`
SHA-256: `da52ede57c86ddfe118b56efbfc68c0716c39ae13faca1fb97a2a738e755f7a9`

Confirmed against the live provider:

- `probe(enabled = true)` → `AVAILABLE`, reported version `2.24.0`.
- `probe(enabled = false)` → `UNAVAILABLE` / `DISABLED`, i.e. a disabled
  integration does not degrade to "all unknown".
- Capability limits are reported honestly: `noteModificationTime = true`,
  `cardModificationTime = false`, `reviewHistory = false`.

### 4. Functional acceptance scenarios — `ImmersionStatsAcceptanceDeviceTest`

Result: **OK**, both scenarios.

Unlike the performance rows, plan scenarios 33.6 and 33.7 assert *convergence*
— counter preservation, exactly-once deletion, merge idempotency, tombstone
behaviour. They contain no latency budget, so a software-emulated device is a
legitimate host for them.

Artifacts:

| Scenario | File | SHA-256 |
|---|---|---|
| 33.6 privacy and deletion | `device-acceptance-privacy-deletion-api26.json` | `7938eddc2eca2da859e2467cbdeb02da588b47937ca5bd8b05b44e48e07f080f` |
| 33.7 backup and multi-device merge | `device-acceptance-backup-merge-api26.json` | `c97638cc0c05aa33ef978bda5a30f7c441836fad1737a1ae8e9c366912ca039f` |

**33.6 (partial — see limits below).** Raw-text deletion cleared 2 rows while
gross characters stayed at 20 and sessions at 2, proving counters survive
provenance removal. Deleting one of two sessions moved sessions 2 → 1 and gross
20 → 8, i.e. exactly once; a repeated delete returned no preview and left
totals unchanged.

**33.7.** Device B (15 characters) was seeded, exported, and the app's data
cleared with `pm clear`; device A (7 characters) then merged the remote archive.
Totals summed to 22 exactly once. Repeated merges reported `ALREADY_COMPLETE`
and left totals at 22. After deleting the merged session (back to 7), merging a
*separately exported* older copy of the same data reported **5 rows skipped by
tombstone** and left totals at 7 — the deleted data did not return.

Three implementation facts were established by making these tests fail first,
and are worth recording because each initially looked like a product bug:

1. `resetAllStats` tombstones everything it deletes. Simulating a second device
   by resetting locally therefore cannot work — the merge correctly refuses to
   resurrect tombstoned rows. The harness uses `pm clear` between two
   instrumentation phases instead.
2. Re-merging a byte-identical archive short-circuits on its checkpoint ledger
   and replays the prior report rather than reporting zero inserts. Asserting
   `insertedRows == 0` tests the wrong thing.
3. For the same reason, step 3 must merge a *separately exported* copy.
   Re-merging the identical archive proves nothing about tombstones, because it
   never reaches the tombstone filter.

#### Limits on the 33.6 claim

The scenario as written also requires reading **in incognito** and confirming
zero rows of any kind, driven through the reader UI. These runs exercise the
repository layer only, so the incognito write-barrier step is **not** covered
here. `privacyAndDeletionAcceptance` therefore remains `false`.

Scenario 33.7's step 1 ("device A and B read different content") is likewise
modelled at the repository layer rather than by two physical devices
exchanging real backup files through the settings UI.

## Why no matrix row flips

`docs/immersion-stats-release-validation.md` requires each evidence object to
carry a representative device, measured results, artifact hashes, **and a
reviewer identity with an explicit pass decision**. The runs above satisfy the
commit/variant/device/command/artifact requirements but not the remaining ones:

1. **No representative performance target.** Without KVM the device is
   software-emulated, so every duration measured here is unrepresentative. This
   blocks `captureAndQueryPerformance`, `knownnessTimelinePerformance`,
   `recorderWakeAndWriteBehavior`, and audit items P16-04, P20-01, P20-02, and
   P20-08 regardless of how many times the tests pass.
2. **No reviewer.** No human has reviewed or signed off on these runs.
3. **GUI-dependent rows were not attempted to completion.** The emulator's
   System UI enters ANR ("System UI isn't responding") under software
   emulation; AnkiDroid could not be driven past `IntroductionActivity`. That
   makes `talkBack`, `largeTextAndDisplay`, `reducedMotion`,
   `visualConfigurationMatrix`, and the seven end-to-end acceptance scenarios
   unexecutable on this host. TalkBack source is present at
   `/tmp/google-talkback` but was not built or installed.
4. **`databaseAndRawTextGrowth` is measured but incomplete.** Run 2 gives a
   credible growth rate on real SQLite. The audit also requires a stable
   storage forecast (P20-09, P20-10) across week/year/multi-year profiles,
   which one 100k-character fixture does not establish.
5. **`ankiAcceptance` is a scenario, not a probe.** Run 3 proves capability
   classification against the real provider. It does not exercise knownness and
   maturity against a real collection containing new, learning, young, and
   mature cards, which is what the acceptance row requires.
6. **The acceptance runs cover their scenarios only partially.** Run 4 closes
   the convergence half of 33.6 and 33.7 but omits the incognito write-barrier
   step and the real two-device backup-file exchange, both of which the plan
   text requires. They are also unreviewed.

Rows still needing work that this session did not touch:
`supportedVersionUpgrades`, `releaseMinSdkAndMigration` (needs a release build
on a representative min-SDK device), and audit items P20-A04 through P21-10.

## What a qualifying host requires

To close the remaining rows, the device evidence must be re-run on either:

- a physical Android device (preferred, and the only way to close TalkBack,
  reduced-motion, and wake/write rows credibly); or
- an emulator on a host exposing `/dev/kvm` with hardware virtualization.

The instrumentation harness added on this branch is reusable as-is on such a
host; only the environment needs to change.
