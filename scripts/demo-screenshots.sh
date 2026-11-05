#!/usr/bin/env bash
# Captures the README screenshots from the debug build's demo mode (synthetic test patterns,
# placeholder camera names; never a real account). Usage:
#
#   scripts/demo-screenshots.sh <tv-serial> [<phone-serial>]
#
# Needs the debug APK installed on each device (`./gradlew assembleDebug`, then
# `adb -s <serial> install -r -t app/build/outputs/apk/debug/app-debug.apk`), `sips` (macOS) or
# `pngquant`/`cwebp` to keep each file under 400 KB, and nothing else running on the devices.
set -euo pipefail

TV=${1:?usage: $0 <tv-serial> [<phone-serial>]}
PHONE=${2:-}
PKG=com.itsluminous.hikviewer.debug
DEMO=$PKG/com.itsluminous.hikviewer.demo.DemoActivity
OUT=${OUT_DIR:-docs/screenshots}
MAX_BYTES=400000
mkdir -p "$OUT"

# Starts the demo entry point; extra arguments go to `am start` (--es screen grid, --ei cameras 12, ...).
demo() {
    local serial=$1
    shift
    adb -s "$serial" shell am force-stop "$PKG"
    adb -s "$serial" shell am start -W -n "$DEMO" "$@" > /dev/null
}

key() { adb -s "$1" shell input keyevent "$2"; }

# screencap -p on the device, then shrink in place until the file is under MAX_BYTES.
shoot() {
    local serial=$1 name=$2 file="$OUT/$2.png"
    adb -s "$serial" exec-out screencap -p > "$file"
    local longest
    longest=$(sips -g pixelWidth -g pixelHeight "$file" | awk '/pixel/ {if ($2 > m) m = $2} END {print m}')
    # Test-pattern tiles compress badly as PNG; step the longest side down until the file fits.
    for candidate in 1600 1280 960 720; do
        [ "$(stat -f %z "$file")" -le "$MAX_BYTES" ] && break
        [ "$candidate" -lt "$longest" ] || continue
        sips -Z "$candidate" "$file" > /dev/null
    done
    printf '%-18s %s  %s bytes\n' "$name" "$(sips -g pixelWidth -g pixelHeight "$file" | awk '/pixel/ {printf "%s ", $2}')" "$(stat -f %z "$file")"
}

# --- Television (1080p) ------------------------------------------------------------------------
demo "$TV" --es screen login
sleep 3
shoot "$TV" login

demo "$TV" --es screen grid --es layout GRID_4X3 --ei cameras 12
sleep 14                      # every tile shows a picture by then, even on a software decoder
key "$TV" KEYCODE_DPAD_RIGHT  # any key reveals the top bar for 5 s
key "$TV" KEYCODE_DPAD_LEFT
sleep 1
shoot "$TV" grid-4x3

demo "$TV" --es screen grid --es layout HERO_1_7 --ei cameras 8
sleep 14
shoot "$TV" grid-1-plus-7

demo "$TV" --es screen fullscreen --ei index 0
sleep 5
key "$TV" KEYCODE_DPAD_RIGHT  # shows the "switch camera" hint again
sleep 2
shoot "$TV" fullscreen

demo "$TV" --es screen playback --ei index 0
sleep 8
key "$TV" KEYCODE_DPAD_RIGHT  # controls show on any key
sleep 1
shoot "$TV" playback

demo "$TV" --es screen settings --ei cameras 12
sleep 3
shoot "$TV" settings
adb -s "$TV" shell am force-stop "$PKG"

# --- Phone (portrait) --------------------------------------------------------------------------
if [ -n "$PHONE" ]; then
    adb -s "$PHONE" shell settings put system accelerometer_rotation 0
    adb -s "$PHONE" shell settings put system user_rotation 0
    demo "$PHONE" --es screen grid --ei cameras 8
    sleep 12
    shoot "$PHONE" phone-grid

    demo "$PHONE" --es screen camera --ei index 0
    sleep 8
    shoot "$PHONE" phone-camera
    adb -s "$PHONE" shell am force-stop "$PKG"
fi
