#!/usr/bin/env bash
# Installs the debug APK on an emulator and logs in with the credentials from local.env, typing them
# through adb so they never appear in a shell history or a log. Usage:
#   scripts/emu-login.sh <emulator-serial> [path/to/local.env]
# Never target a real TV with this script; the serial must be an emulator-NNNN.
set -euo pipefail
SERIAL="${1:?emulator serial, e.g. emulator-5554}"
ENV_FILE="${2:-$(dirname "$0")/../local.env}"
case "$SERIAL" in emulator-*) ;; *) echo "refusing to target non-emulator $SERIAL" >&2; exit 2;; esac
ADB="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/adb -s $SERIAL"
APK="$(dirname "$0")/../app/build/outputs/apk/debug/app-universal-debug.apk"
PKG=com.itsluminous.hikviewer.debug

mobile=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["mobile"])' "$ENV_FILE")
password=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["password"])' "$ENV_FILE")

$ADB install -r "$APK" >/dev/null
$ADB shell pm clear $PKG >/dev/null
$ADB shell am start -W -n $PKG/com.itsluminous.hikviewer.ui.login.LoginActivity >/dev/null
sleep 2
# Account field has focus on open. `adb shell input text` types into the focused view.
$ADB shell input text "$mobile"
$ADB shell input keyevent KEYCODE_TAB
$ADB shell input text "$password"
$ADB shell input keyevent KEYCODE_TAB   # region spinner
$ADB shell input keyevent KEYCODE_TAB   # login button
$ADB shell input keyevent KEYCODE_DPAD_CENTER
echo "login submitted on $SERIAL"
