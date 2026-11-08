#!/usr/bin/env bash
# Starts the P1 AVD inside the wolp-emulator container (host network: host adb sees it, guest 10.0.2.2 = host).
#   ./run.sh [avd-name] [extra emulator args...]
set -euo pipefail
AVD="${1:-p1-api34}"; shift || true
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
docker build -q -t wolp-emulator "$(dirname "$0")" >/dev/null
exec docker run --rm --name "wolp-emu-$AVD" --network host --device /dev/kvm \
  --user "$(id -u):$(id -g)" --group-add "$(getent group kvm | cut -d: -f3)" \
  -e HOME="$HOME" -e ANDROID_SDK_ROOT="$SDK" -e ANDROID_AVD_HOME="$HOME/.android/avd" \
  -e ANDROID_EMU_ENABLE_CRASH_REPORTING=0 \
  -v "$SDK:$SDK" -v "$HOME/.android:$HOME/.android" \
  wolp-emulator "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
  -writable-system -gpu swiftshader_indirect "$@"
