#!/usr/bin/env bash
set -euo pipefail
api_level="${1:?Android API level required}"
[[ "$api_level" =~ ^[0-9]+$ ]]
image="system-images;android-${api_level};google_apis;x86_64"
avd="podyom-api${api_level}"
sdkmanager emulator "$image"
export ANDROID_AVD_HOME="$RUNNER_TEMP/podyom-avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | avdmanager create avd --name "$avd" --package "$image" --path "$ANDROID_AVD_HOME/${avd}.avd" --force
"$ANDROID_HOME/emulator/emulator" -list-avds | grep -qx "$avd"
"$ANDROID_HOME/emulator/emulator" -accel-check
"$ANDROID_HOME/emulator/emulator" -avd "$avd" -no-window -no-audio -no-snapshot \
  -gpu software -feature -Vulkan -no-boot-anim > "$RUNNER_TEMP/podyom-emulator-$api_level.log" 2>&1 &
emulator_pid=$!
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then tail -100 "$RUNNER_TEMP/podyom-emulator-$api_level.log"; fi
  adb -s emulator-5554 emu kill || kill "$emulator_pid" || true
}
trap cleanup EXIT
timeout 150s bash -c 'until adb get-state 2>/dev/null | grep -qx device; do kill -0 "$1" || exit 1; sleep 2; done' _ "$emulator_pid"
timeout 210s bash -c 'until [ "$(adb shell getprop sys.boot_completed | tr -d "\r")" = 1 ]; do sleep 2; done'
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
if [[ "$api_level" == 30 ]]; then
  adb shell settings put system font_scale 1.35
  adb shell wm size 720x1280
  adb shell wm density 320
fi
gradle_status=0
./gradlew :app:connectedDebugAndroidTest --console=plain || gradle_status=$?
if [[ -d app/build/outputs/androidTest-results/connected ]]; then
  mkdir -p "app/build/instrumentation-api$api_level"
  cp -r app/build/outputs/androidTest-results/connected/. "app/build/instrumentation-api$api_level/"
fi
exit "$gradle_status"
