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
