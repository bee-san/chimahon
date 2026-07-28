# Animated-scene device validation gate

Animated-scene code and JVM tests are not sufficient evidence that the packaged
native libraries, Android encoders, AnkiDroid provider, and playback clients
work together. Every distributable build therefore remains blocked while
`animated-scene-device-validation.json` has `releaseGate` set to `blocked`.
Debug builds remain available.

The Android test uses the checked-in synthetic CC0 fixture and cannot skip when
the packaged FFmpeg build lacks a fixture encoder. It covers the real packaged
decode, frame extraction, API-dependent Android WebP encoding, copy mux,
structural validation, playback open, display rotation, and a provider that
rejects write access. Its result must still be recorded separately on API
26-to-29 and API 30 or newer devices.

Authenticated remote media is now downloaded by an app-controlled,
origin-scoped materializer. It rejects cross-origin and downgrade redirects,
freezes complete static HLS graphs locally, and removes authorization, cookie,
`Origin`, and `Referer` metadata before FFmpeg receives the input. JVM tests
cover those confinement rules, but the packaged path has not yet been exercised
against a two-origin device fixture. Keep `remoteHlsAndAuthenticated` false
until that device test verifies both direct-media and segment redirects; do not
enable the matrix row merely because the JVM tests or unauthenticated HTTP/HLS
cases succeed.

The `Animated Scene Device Validation` GitHub workflow runs the non-skippable
instrumented pipeline test on API 29 and API 35 emulators. Each job uploads the
tested commit, device properties, fixture digest, Gradle result, JUnit report,
and logcat output. A reviewer may use passing artifacts to support
`api26To29Webp`, `api30PlusWebpLossy`, `localAndReadOnlySaf`, and
`rotationAndSdr`, but must record the reviewed workflow run before changing
those rows. The workflow does not prove authenticated remote media, DASH,
failure injection, AnkiDroid behavior, external-client playback, or lower-end
hardware performance.

Before changing the gate to `verified`, a reviewer must execute and record all
matrix entries in the JSON file:

1. Run the instrumented scene pipeline test on API 26-to-29 and API 30 or newer.
2. Exercise local, genuinely read-only content URI, remote/HLS with
   authentication, and video-only DASH inputs.
3. Verify cancellation and injected failures during extraction, frame encode,
   mux, hashing, media insertion, low disk, corrupt frames, oversize output,
   timeout, and parallel taps.
4. Verify real AnkiDroid FileProvider insertion, animated-to-still fallback,
   double-storage failure, and note-provider failure after media insertion.
5. Check looping playback and synchronization in supported Anki clients.
6. Benchmark low- and high-motion authorized clips on a lower-end supported
   device, recording peak memory, wall time, temporary disk use, and output
   size.

Only set a matrix value to `true` when its evidence has been reviewed. Set
`releaseGate` to `verified` only when every value is true.
