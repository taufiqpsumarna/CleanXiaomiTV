# Xiaomi TV Box S 3rd gen: what was changed and how to undo it

Box: Xiaomi TV Box S 3rd gen (MiTV-AFMU0, twilight), Google TV 14, firmware UKG3.250826.001.V816.0.11.0.UZFAABX.
Set up on 2026-10-22 with [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (`/tmp/cleanyourtv`) and
[CleanXiaomiTV](https://github.com/majnioui/CleanXiaomiTV) (`/tmp/CleanXiaomiTV`, configured copy).

> **Where the scripts run:** `restore.sh` and `build-and-install.sh` use CleanYourTV's tools (its
> `tools/restore.sh` reads the recovery record `tools/disable.sh` saved). Copy this folder into a CleanYourTV
> clone as `work/` and run them from that clone's root; `build-and-install.sh` expects this repo at
> `/tmp/CleanXiaomiTV` (edit `CXT=` if it lives elsewhere).

Everything was **disabled, not uninstalled**. Backups of the original state are in `backup/`, and the
record `tools/restore.sh` uses is in `~/.cleanyourtv/recovery-<box ip>-*.json`: keep both.

> `/tmp` can be cleared when WSL restarts. Copy `/tmp/cleanyourtv`, `/tmp/CleanXiaomiTV` and
> `~/.cleanyourtv/` somewhere permanent (e.g. your home folder) if you want to rebuild or undo later.

## What is on the box now

- **Home screen:** the CleanXiaomiTV launcher (`com.majnioui.cleanxiaomitv`). Photo of the day behind it
  (changes daily), Bandung weather by the clock, next prayer with a countdown (Kemenag method), dock:
  YouTube, Netflix, Xiaomi TV+, Vidio, YouTube Music; press Down for all apps.
- **Cards:** Capacity Stats, Channels, Prayer Times, and two Weather apps (CleanYourTV's and CleanXiaomiTV's).
- **Screensaver:** Google Ambient (Google's rotating art and photos).
- **DNS:** AdGuard Private DNS (`dns.adguard-dns.com`), blocks ads and trackers in most apps, not YouTube ads.
- **Kept on purpose:** YouTube, YouTube Music, Netflix, Xiaomi TV+, voice assistant, Chromecast, Miracast/Mi
  share, firmware updates, Play Store and Play Services, and all apps you installed yourself.
- **Disabled:** 33 packages, listed with reasons in `work/debloat.txt` (telemetry, PatchWall/Mi Home tab and
  promos, Prime Video and other preloaded apps, the setup wizard, the stock Google TV launcher and the
  CleanYourTV launcher).

## Undo everything

From `/tmp/cleanyourtv`, with ADB switched on again (see "Turning ADB back on"):

```bash
TV_HOST=<box ip> work/restore.sh                # re-enable all, stock Home, DNS and screensaver back
TV_HOST=<box ip> work/restore.sh --remove-apps  # same, and uninstall the sideloaded launcher and add-ons
```

Then restart the box.

## Re-enable a single app

Without a computer: Settings > Apps > See all apps > Show system apps > pick the app > **Enable**.

With ADB, one package per command:

```bash
TV_HOST=<box ip> tools/venv/bin/python tools/tv.py 'pm enable com.amazon.amazonvideo.livingroom'
```

Notes:
- **Setup wizard** (`com.google.android.tungsten.setupwraith`): only needed to re-pair the remote. Enable it,
  pair, then disable it again, or it can take over Home.
- **Stock launcher** (`com.google.android.apps.tv.launcherx`): enabling it makes it Home again (it outranks
  sideloaded launchers on Google TV).

## Change the dock order or the apps in it

Edit the `dock_apps` list in `/tmp/CleanXiaomiTV/launcher/app/src/main/res/values/config.xml` (one package per
`<item>`, left to right; `columns` is the number of slots), then rebuild:

```bash
cd /tmp/cleanyourtv && TV_HOST=<box ip> work/build-and-install.sh launcher
```

Package names of installed apps: `TV_HOST=<box ip> tools/venv/bin/python tools/tv.py 'pm list packages -3'`.
You can also hold OK on a tile on the TV to move it.

## Change the wallpaper

- **Home background:** the photo of the day changes by itself every day. To use your own picture instead, set
  `home_photo` to `false` in that `config.xml` and push a theme with a `wallpaper.jpg` (1920x1080, dark, under
  about 300 KB) with CleanXiaomiTV's own theme script, which targets its launcher:
  `cd /tmp/CleanXiaomiTV && TV_HOST=<box ip> tools/theme.sh themes/aurora` (copy that folder and replace
  its `wallpaper.jpg`).
- **Screensaver:** Settings > System > Ambient mode on the TV, or switch back to the launcher's clock
  screensaver with
  `tools/tv.py 'settings put secure screensaver_components com.majnioui.cleanxiaomitv/com.gathin.tvhome.ClockDream'`.

## Change the city or prayer method

`weather_latitude`, `weather_longitude`, `weather_place` and `prayer_method` (20 = Kemenag) in the launcher's
`config.xml`; `latitude`, `longitude`, `place` in `/tmp/CleanXiaomiTV/modules/{weather,prayer}/src/main/res/values/config.xml`.
Then `work/build-and-install.sh all`. The box has no GPS, so the location is fixed. The Weather apps also have a
**Change city** button.

## AdGuard DNS

Turn it off: `tools/tv.py 'settings put global private_dns_mode opportunistic'`, or Settings > Network &
Internet > Private DNS > Automatic.

## Turning ADB back on

Settings > System > Developer options > **USB debugging** and **Wireless debugging** on, keep the box on Wi-Fi,
accept "Allow USB debugging?" on the screen. Turn both off again when done: port 5555 is unencrypted.
