# Native distribution compliance

This records the evidence for the exact native coordinates distributed by
Chimahon. The native source gate is verified for these artifacts; replacing
either coordinate requires a new audit and manifest update.

## Verified artifacts

### FFmpegKit

- Coordinate: `com.github.bee-san:ffmpeg-kit:1.18-chimahon.2`
- AAR SHA-256:
  `840ab71ef95a3fe056bf085e17d58f647397b2fdaea8b3bc2e95b1d4577f9a61`
- Annotated tag object: `c3b7bb33d9d97554ef7b433f737e26eb30df3469`
- Peeled commit: `6a937c79cea09748385215891b68bf7d78215f81`
- Source SHA-256:
  `a68fe00a26e52e322f516a581079778efb4614a9447ae8da557b8109388020cc`
- Toolchain SHA-256:
  `85acdd55b61b93a265abf95a8c64eebcb04ed659385ba3c5f33917db6dc761a6`
- Build run:
  <https://github.com/bee-san/ffmpeg-kit/actions/runs/30281363862>

### Aniyomi mpv

- Coordinate: `com.github.bee-san:aniyomi-mpv-lib:1.18.n-chimahon.4`
- AAR SHA-256:
  `33749a56f8afbc9b83252705f9e1dbdaba22f8418448e67d2f60efd6946e0cb8`
- Annotated tag object: `295cb75a01066fa3f157ea4fb75eed19a91b2b78`
- Peeled commit: `ff7447ea918eb460bf379cc8de16e076865b067b`
- Source SHA-256:
  `4083e436db9a7b9d44d54d876ede48a139ac2de59d4b5b4d01198b6b9d71fec3`
- Toolchain SHA-256:
  `535a7996e0701e37b8a46bd9707b0b872d3be1d958b4ec20a2ef234911b6136d`
- Build run:
  <https://github.com/bee-san/aniyomi-mpv-lib/actions/runs/30281268648>

Both JitPack coordinates resolve to AARs that are byte-identical to their
GitHub release AARs.

## Source and build audit

- Both release `SHA256SUMS` files validate every attached AAR, source archive,
  and toolchain archive.
- The FFmpegKit source archive contains 34,332 entries, no `.git` metadata, and
  47 license, notice, copyright, or copying files. It includes the exact
  FFmpegKit wrapper, patched FFmpeg tree, mpv build source, transitive native
  dependencies, recursive submodules, patches, and build configuration.
- The mpv source archive contains 22,984 entries, no `.git` metadata, all 12
  native dependency roots, seven recorded recursive submodule trees, and 36
  license, notice, copyright, or copying files.
- Both source locks record Android NDK `27.2.12479018`, SDK platform `34`,
  build tools `34.0.0`, FFmpeg `n7.1` at
  `b08d7969c550a804a59511c7b83f2dd8cc0499b8`, and mpv at
  `d82701962f99051a18d65c215b70d41ebadd9a22`.
- The FFmpegKit AAR contains ten native libraries for each of `arm64-v8a`,
  `armeabi-v7a`, `x86`, and `x86_64`. The mpv AAR contains four native
  libraries for each of the same four ABIs.
- Toolchain archives include the exact successful workflow URL, commit, runner
  image, JDK, Gradle, NDK identity, helper-script checksum, and complete native
  build logs.
- AboutLibraries overrides disclose both wrapper and native-payload terms. The
  app license detail screen renders every declared license, not only the first.
- Native notices are a custom AboutLibraries license entry, so they are
  generated into the app's `R.raw.aboutlibraries` database even though generic
  `META-INF/NOTICE` files are excluded from APK packaging.

## Enforcement

`verifyNativeComplianceMetadata` resolves each coordinate without transitive
dependencies, checks the AAR hash and annotated tag, and validates packaged
license metadata. `verifyNativeSourceCompliance` additionally requires the
verified gate, exact source and toolchain URLs and hashes, and structured
in-app Corresponding Source links. Production release tasks additionally run
`verifyApplicationReleaseSourceCompliance`, which requires the annotated
application release tag to peel to the checked-out commit.

Release, release-test, FOSS, preview, and benchmark assembly, bundle, package,
signing, publishing, and upload task paths depend on this enforcement. Source
archives and equivalent-source directions must remain available for as long as
their binaries are offered. Non-release variants do not require an application
release tag, so preview and benchmark CI remain usable after a tagged release.

## Application release status

Native compliance is complete. Application distribution remains independently
blocked until every entry in `animated-scene-device-validation.json` has
reviewed device evidence and its release gate is `verified`. A missing release
OAuth client secret also prevents the GitHub Actions release build. Neither
condition is waived by this native audit.

Generated scene media is user output and does not become GPL-covered merely
because FFmpeg decoded or muxed it. Users remain responsible for having a
lawful basis to reproduce source media; protected or DRM inputs are unsupported.
