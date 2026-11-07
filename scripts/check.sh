#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]] && [[ -d /workspace/toolchain/jdk-21.0.9+10 ]]; then
  export JAVA_HOME=/workspace/toolchain/jdk-21.0.9+10
fi
if [[ -d /workspace/android-sdk ]]; then
  export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/workspace/.android}"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.gradle}"
fi
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
