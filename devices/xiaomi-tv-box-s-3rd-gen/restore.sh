#!/usr/bin/env bash
# Put the Xiaomi TV Box S 3rd gen back the way it was before the cleanup on 2026-10-22: every package in
# debloat.txt enabled again, Home back to the stock Google TV launcher, AdGuard DNS and the screensaver
# choice undone. Needs ADB on: Developer options > USB debugging (and Wireless debugging).
#
# Usage, from anywhere:  TV_HOST=<box ip> devices/xiaomi-tv-box-s-3rd-gen/restore.sh [--remove-apps]
#   --remove-apps  also uninstall the sideloaded launcher and apps (user apps, not system ones)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../.."
: "${TV_HOST:?set TV_HOST to the box IP, e.g. TV_HOST=<box ip> $0}"
export TV_HOST
PY=tools/venv/bin/python
T() { "$PY" -I tools/tv.py "$@"; }
STOCK=com.google.android.apps.tv.launcherx

# The stock launcher FIRST: with it off, the setup wizard's RecoveryActivity takes Home, and a reboot in
# that state factory-reset a MiTV-MOEU0. One package per call: chained pm commands can stop silently.
T "pm enable $STOCK"
grep -vE '^\s*(#|$)' "$HERE/debloat.txt" | sed -E 's/\s+#.*$//' | while read -r p; do
  T "pm enable $p" </dev/null
done
T "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1)"

# AdGuard Private DNS off (the box had no Private DNS set before; opportunistic is Android's default).
T "settings put global private_dns_mode opportunistic"
T "settings delete global private_dns_specifier"
# Screensaver back to the firmware default (screensaver_components was unset before).
T "settings delete secure screensaver_components"

if [ "${1:-}" = --remove-apps ]; then
  for p in com.majnioui.cleanxiaomitv com.majnioui.tv.weather com.majnioui.tv.prayer com.majnioui.tv.channels \
           com.gathin.tvhome; do
    T "pm uninstall $p" </dev/null || true
  done
fi
T "pm list packages -d"
T "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1"
echo "Done. Reboot the box once (Settings > System > Restart)."
