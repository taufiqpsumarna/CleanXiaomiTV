#!/usr/bin/env bash
# Rebuild and reinstall this box's apps from this repo (Xiaomi TV Box S 3rd gen, MiTV-AFMU0):
#   launcher  the CleanXiaomiTV launcher as Home (configured for Bandung and this dock), Google Ambient
#             screensaver; the launcher script also reapplies AdGuard Private DNS (PRIVATE_DNS=off skips it)
#   apps      the Weather, Prayer Times and Channels apps from modules/
#   all       both (default)
# Needs ADB on (Developer options) and the "Allow USB debugging?" prompt accepted once.
#
# Usage, from anywhere:  TV_HOST=<box ip> devices/xiaomi-tv-box-s-3rd-gen/build-and-install.sh [launcher|apps|all]
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${TV_HOST:?set TV_HOST to the box IP, e.g. TV_HOST=<box ip> $0}"
export TV_HOST
export JAVA_HOME="${JAVA_HOME:-$HOME/android-build/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-build/sdk}"
WHAT="${1:-all}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "Set up tools/venv first: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
T() { "$PY" -I tools/tv.py "$@"; }

install_apk() {   # install_apk <local apk> : push in verified chunks, then pm install over the shell
  "$PY" -I tools/b64push.py "$1" /data/local/tmp/module.apk
  T "pm install -r /data/local/tmp/module.apk; rm -f /data/local/tmp/module.apk"
}

if [ "$WHAT" = all ] || [ "$WHAT" = launcher ]; then
  # Home already belongs to this launcher, so the stock one is left alone (it stays disabled).
  # INPUT_KEY_SERVICE=1 maps the remote's Input/YouTube/Apps buttons, at the cost of key repeat on some
  # Google TV builds; leave it off unless a remote button stops working.
  (cd launcher && STOCK_LAUNCHER="${STOCK_LAUNCHER:-none}" ./build-and-install.sh)
  T "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop; settings put secure screensaver_enabled 1"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = apps ]; then
  (cd modules && ./gradlew --no-daemon -q assembleRelease)
  for m in weather prayer channels; do
    install_apk "modules/$m/build/outputs/apk/release/$m-release.apk"
  done
fi

T "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1"
echo "Done. Press Home on the remote; a new app asks once whether to show its card."
