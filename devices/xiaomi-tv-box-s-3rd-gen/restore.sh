#!/usr/bin/env bash
# Put the Xiaomi TV Box S 3rd gen back the way it was before the cleanup on 2026-10-22.
# Re-enables every package in work/debloat.txt (the setup wizard, launcherx and the CleanYourTV launcher too),
# gives Home back to the stock Google TV launcher, and undoes the AdGuard DNS and screensaver changes.
# Needs ADB on: Developer options > USB debugging (and Wireless debugging), then accept the prompt on the TV.
#
# Usage, from the repo root:  TV_HOST=<box ip> work/restore.sh [--remove-apps]
#   --remove-apps  also uninstall the sideloaded launcher and add-ons (they are user apps, not system ones)
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the box IP, e.g. TV_HOST=<box ip> $0}"
export TV_HOST
T() { tools/venv/bin/python -I tools/tv.py "$@"; }

# Packages and Home, from the record tools/disable.sh saved in ~/.cleanyourtv/ before the first change.
tools/restore.sh work/debloat.txt com.google.android.apps.tv.launcherx

# AdGuard Private DNS off (the box had no Private DNS set before; opportunistic is Android's default).
T "settings put global private_dns_mode opportunistic"
T "settings delete global private_dns_specifier"
# Screensaver back to the firmware default (screensaver_components was unset before).
T "settings delete secure screensaver_components"

if [ "${1:-}" = --remove-apps ]; then
  for p in com.majnioui.cleanxiaomitv com.majnioui.tv.weather com.majnioui.tv.prayer com.majnioui.tv.channels \
           com.lionlev.tv.capacity io.github.sumyabazar1.weather com.gathin.tvhome; do
    T "pm uninstall $p" || true
  done
fi
T "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1"
echo "Done. Reboot the box once (Settings > System > Restart)."
