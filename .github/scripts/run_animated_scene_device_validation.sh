#!/usr/bin/env bash

set -euo pipefail

: "${API_LEVEL:?API_LEVEL is required}"

evidence_dir="build/device-validation/api-$API_LEVEL"
{
    echo "device_serial=$(adb get-serialno)"
    echo "device_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "device_model=$(adb shell getprop ro.product.model | tr -d '\r')"
    echo "device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
    echo "device_fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
} >> "$evidence_dir/environment.txt"

set +e
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.player.scene.SceneCapturePipelineInstrumentedTest
test_status=$?
set -e

echo "gradle_exit_code=$test_status" >> "$evidence_dir/environment.txt"
adb logcat -d -v threadtime > "$evidence_dir/logcat.txt" || true
exit "$test_status"
