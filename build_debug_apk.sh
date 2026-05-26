#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$ROOT_DIR/.build-env/jdk-17"
export ANDROID_HOME="$ROOT_DIR/.build-env/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$ROOT_DIR/.gradle-home"

"$ROOT_DIR/.build-env/gradle-7.6.4/bin/gradle" --no-daemon :app:assembleDebug
