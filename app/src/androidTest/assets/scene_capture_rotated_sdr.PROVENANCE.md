# Scene capture Android-test fixture

`scene_capture_rotated_sdr.mp4` is a synthetic test pattern generated for this
repository. It contains no third-party audiovisual material.

- Generator: FFmpeg 8.1.2
- Video: MPEG-4 Part 2, YUV 4:2:0, 96 by 64 coded pixels, 8 fps, 1.25 seconds
- Display metadata: 90-degree rotation
- SHA-256:
  `04b3a24d2a43a95ffe53973390ead5c1923bb559266e6fc07dbc089e808ef5d5`

Generation commands:

```text
ffmpeg -f lavfi -i testsrc2=size=96x64:rate=8:duration=1.25 \
  -c:v mpeg4 -q:v 5 -pix_fmt yuv420p scene_capture_base.mp4
ffmpeg -noautorotate -display_rotation:v:0 90 \
  -i scene_capture_base.mp4 -c copy scene_capture_rotated_sdr.mp4
```

The generated fixture is dedicated to the public domain under
[CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/legalcode).
