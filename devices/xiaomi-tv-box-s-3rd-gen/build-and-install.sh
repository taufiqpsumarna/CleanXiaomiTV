#!/usr/bin/env bash
# Rebuild and reinstall everything on the Xiaomi TV Box S 3rd gen (MiTV-AFMU0), as set up on 2026-10-22:
#   - the CleanXiaomiTV launcher as Home (from /tmp/CleanXiaomiTV, configured for Bandung and this dock)
#   - its Weather, Prayer Times and Channels apps
#   - the Capacity Stats and Weather add-ons from CleanYourTV
#   - the Google Ambient screensaver
# Run it after editing the dock or the city (see work/README.md). It also reapplies AdGuard Private DNS;
# pass PRIVATE_DNS=off to leave DNS alone. Needs ADB on (Developer options) and the prompt accepted.
#
# Usage, from the cleanyourtv repo root:  TV_HOST=<box ip> work/build-and-install.sh [launcher|apps|all]
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the box IP, e.g. TV_HOST=<box ip> $0}"
export TV_HOST
export JAVA_HOME="${JAVA_HOME:-$HOME/android-build/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-build/sdk}"
CXT=/tmp/CleanXiaomiTV
WHAT="${1:-all}"
PY=tools/venv/bin/python
T() { "$PY" -I tools/tv.py "$@"; }

install_apk() {   # install_apk <local apk> : push in verified chunks, then pm install over the shell
  "$PY" -I tools/b64push.py "$1" /data/local/tmp/addon.apk
  T "pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk"
}

if [ "$WHAT" = all ] || [ "$WHAT" = launcher ]; then
  # Home already belongs to this launcher after the first install, so nothing gets disabled here.
  # INPUT_KEY_SERVICE=1 maps the remote's Input/YouTube/Apps buttons, at the cost of key repeat on some
  # Google TV builds; leave it off unless a remote button stops working.
  (cd "$CXT/launcher" && STOCK_LAUNCHER="${STOCK_LAUNCHER:-none}" ./build-and-install.sh)
  T "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop; settings put secure screensaver_enabled 1"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = apps ]; then
  (cd "$CXT/modules" && ./gradlew --no-daemon -q assembleRelease)
  for m in weather prayer channels; do
    install_apk "$CXT/modules/$m/build/outputs/apk/release/$m-release.apk"
  done
  for a in capacity-stats weather; do
    (cd "addons/$a" && ./gradlew --no-daemon -q assembleRelease)
    install_apk "addons/$a/app/build/outputs/apk/release/app-release.apk"
  done
fi

T "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1"
echo "Done. Press Home on the remote; new add-ons ask once whether to show their card."
