#!/usr/bin/env bash
# Installs an APK on the real TV through tv.py (no stock adb) and logs in with the credentials from
# local.env, typed through the TV's input service so they appear in no shell history or log. Usage:
#   scripts/tv-login.sh <apk> <package> [path/to/local.env]
# e.g. scripts/tv-login.sh app/build/outputs/apk/debug/app-debug.apk com.itsluminous.hikviewer.debug
# Requires TV_HOST (the TV's address) and the cleanyourtv checkout at $CLEANYOURTV (default ../cleanyourtv).
set -euo pipefail
APK="${1:?apk path}"
PKG="${2:?package name}"
ENV_FILE="${3:-$(dirname "$0")/../local.env}"
: "${TV_HOST:?TV_HOST must be set}"
CYT="${CLEANYOURTV:-$(dirname "$0")/../../cleanyourtv}"
# tv.py finds its venv relative to the cleanyourtv checkout, so it runs from there.
tv() { (cd "$CYT" && perl -e 'alarm 120; exec @ARGV' -- python3 tools/tv.py "$@"); }
push() { (cd "$CYT" && tools/venv/bin/python tools/b64push.py "$@"); }

# The login form takes the number WITHOUT the country code (the default region, India, adds 91),
# so strip that prefix from local.env's full number. Computed, never echoed.
mobile=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1]))["mobile"]; print(m[2:] if m.startswith("91") else m)' "$ENV_FILE")
password=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["password"])' "$ENV_FILE")
encryption=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["encryption"])' "$ENV_FILE")

push "$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")" /data/local/tmp/hikviewer.apk >/dev/null
tv "pm install -r /data/local/tmp/hikviewer.apk"
tv "pm clear $PKG" >/dev/null
tv "am start -W -n $PKG/com.itsluminous.hikviewer.ui.login.LoginActivity" >/dev/null
sleep 3
# Account field has focus on open. `input text` types into the focused view.
tv "input text $mobile" >/dev/null
tv "input keyevent KEYCODE_TAB"
tv "input text $password" >/dev/null
tv "input keyevent KEYCODE_TAB"   # region spinner
tv "input keyevent KEYCODE_TAB"   # login button
tv "input keyevent KEYCODE_DPAD_CENTER"
echo "login submitted on $TV_HOST for $PKG"
# The grid asks for the recorder's verification code once per install; its input has focus, and
# OK sits one TAB below (the dialog's buttons; Cancel finishes the app, OK is reached first from the input).
sleep 20
tv "input text $encryption" >/dev/null
tv "input keyevent KEYCODE_TAB"
tv "input keyevent KEYCODE_DPAD_CENTER"
echo "verification code submitted"
