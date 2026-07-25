# Native distribution compliance

This is a release gate for every APK or app bundle that contains the current
FFmpegKit or mpv native artifacts. It is not a declaration that the gate has
already been satisfied.

## Verified binary facts

- `com.github.jmir1:ffmpeg-kit:1.17` has SHA-256
  `4570a5cb8fa2c87808e81ebf4b7f3747cb5aa52b1662dc8a1c03831c37b26b89`.
  Its wrapper POM declares LGPL-3.0, while the tagged wrapper source headers
  grant LGPL-3.0-or-later terms. Its bundled FFmpeg configuration enables GPL
  and version-3 code. The native payload identifies itself as GPL version 3 or
  later.
- `com.github.aniyomiorg:aniyomi-mpv-lib:1.17.n` has SHA-256
  `a08c2d3345fb1f46f7ffe2f68999f244666de4a1ed1f90a0cef6c1c761a6d793`.
  Its POM declares MIT only. The exact AAR contains GPL-enabled mpv and
  `libpostproc.so`, which identifies itself as GPL version 3 or later.
- AboutLibraries overrides disclose both the wrapper and native-payload terms.
  The app license detail screen renders every declared license, not only the
  first one.
- The manifest verifier resolves each declared coordinate without transitive
  dependencies, requires exactly one AAR, and hashes that resolved file. A
  coordinate cannot borrow another artifact's binary or source evidence.
- The verifier resolves each annotated upstream tag with `git ls-remote`,
  checks its tag-object ID, and requires `sourceCommit` to equal the tag's
  peeled commit. AboutLibraries and notice links point to those real commit
  trees rather than to annotated-tag objects.
- Native notices are a custom AboutLibraries license entry, so they are
  generated into the app's `R.raw.aboutlibraries` database even though generic
  `META-INF/NOTICE` files are excluded from APK packaging.

## Why distribution is currently blocked

The tagged native build scripts clone some dependencies from an unpinned
default branch, including dav1d, libass, and libplacebo. The tag and build
scripts are therefore not enough to reconstruct the exact sources used for the
published AARs. Neither inspected AAR contains a source bundle or native
license/notice bundle.

`verifyNativeSourceCompliance` intentionally blocks release, release-test,
FOSS, preview, and benchmark assembly, bundle, package, signing, publishing,
and upload task paths while `docs/native-source-manifest.json` remains
`blocked`. Debug builds remain available for development and verification.

## Unblocking a binary release

1. Prefer replacing both artifacts with reproducible builds whose direct and
   transitive native sources, patches, submodules, and immutable revisions are
   recorded.
2. Archive the complete corresponding source, build scripts, patches, and
   configuration needed to reproduce the exact distributed native binaries.
3. Record the source archive URL and SHA-256, exact source commit and revision
   URL, and a toolchain evidence archive URL and SHA-256 for each coordinate in
   `native-source-manifest.json`.
4. Point each artifact's structured AboutLibraries `Corresponding Source`
   funding link at that artifact's exact source archive. Record the exact
   application revision URL under `Application Corresponding Source` in the
   native-notices entry. These structured links are rendered and clickable in
   the app; prose containing the URL does not satisfy the gate.
5. Preserve all applicable license texts, copyright notices, and attribution
   files in the source archive and in the app's generated license database.
6. Put equivalent-source directions next to every offered binary and keep the
   source available for as long as that binary remains offered.
7. Run `verifyNativeComplianceMetadata`, inspect the generated APK/AAB license
   database and source links, and verify each archive checksum independently.
8. Change `releaseGate` to `verified` only after a reviewer has checked the
   archived source against the exact artifact hashes.

Generated scene media is user output and does not become GPL-covered merely
because FFmpeg decoded or muxed it. Users remain responsible for having a
lawful basis to reproduce source media; protected or DRM inputs are unsupported.
