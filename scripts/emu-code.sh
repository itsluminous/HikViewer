#!/usr/bin/env bash
# Types the video verification code from local.env into the focused dialog field and presses OK.
# Usage: scripts/emu-code.sh <emulator-serial> [path/to/local.env]
set -euo pipefail
SERIAL="${1:?emulator serial}"
ENV_FILE="${2:-$(dirname "$0")/../local.env}"
case "$SERIAL" in emulator-*) ;; *) echo "refusing to target non-emulator $SERIAL" >&2; exit 2;; esac
ADB="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb -s $SERIAL"
code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["encryption"])' "$ENV_FILE")
$ADB shell input text "$code"
$ADB shell input keyevent KEYCODE_TAB
$ADB shell input keyevent KEYCODE_TAB
$ADB shell input keyevent KEYCODE_DPAD_CENTER
echo "code submitted on $SERIAL"
