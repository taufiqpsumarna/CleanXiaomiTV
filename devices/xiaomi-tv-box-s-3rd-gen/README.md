# Xiaomi TV Box S 3rd gen: what was changed and how to undo it

Box: Xiaomi TV Box S 3rd gen (MiTV-AFMU0, twilight), Google TV 14, firmware UKG3.250826.001.V816.0.11.0.UZFAABX.
Set up on 2026-10-22 with [CleanYourTV](https://github.com/Gathin23/cleanyourtv) and
[CleanXiaomiTV](https://github.com/majnioui/CleanXiaomiTV); everything needed to rebuild or undo it now lives
in this repo.

> **Where the scripts run:** from this repo, any working directory. They use `tools/tv.py` (set up once:
> `python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`) and build with the
> JDK and SDK in `~/android-build/` (override with `JAVA_HOME` / `ANDROID_HOME`).

Everything was **disabled, not uninstalled**. A snapshot of the box (package lists, settings, getprop)
taken on 2026-11-19 is in `backup-20261119-1827/` at the repo root (git ignores it: it holds the serial).

## What is on the box now

- **Home screen:** the CleanXiaomiTV launcher (`com.majnioui.cleanxiaomitv`). Photo of the day behind it
  (changes daily), Bandung weather by the clock, next prayer with a countdown (Kemenag method), dock:
  YouTube, Netflix, Xiaomi TV+, Vidio, YouTube Music; press Down for all apps.
- **Cards:** Channels (26 family-friendly Indonesian channels, since 2026-11-19), Prayer Times and Weather
  (CleanXiaomiTV's). Capacity Stats and CleanYourTV's duplicate Weather app were uninstalled on 2026-11-19.
- **Screensaver:** Google Ambient (Google's rotating art and photos).
- **DNS:** AdGuard Private DNS (`dns.adguard-dns.com`), blocks ads and trackers in most apps, not YouTube ads.
- **Kept on purpose:** YouTube, YouTube Music, Netflix, Xiaomi TV+, voice assistant, Chromecast, Miracast/Mi
  share, firmware updates, Play Store and Play Services, and all apps you installed yourself.
- **Disabled:** 33 packages, listed with reasons in `debloat.txt` here (telemetry, PatchWall/Mi Home tab and
  promos, Prime Video and other preloaded apps, the setup wizard, the stock Google TV launcher and the
  CleanYourTV launcher).

## Undo everything

With ADB switched on again (see "Turning ADB back on"):

```bash
TV_HOST=<box ip> devices/xiaomi-tv-box-s-3rd-gen/restore.sh                # re-enable all, stock Home, DNS and screensaver back
TV_HOST=<box ip> devices/xiaomi-tv-box-s-3rd-gen/restore.sh --remove-apps  # same, and uninstall the sideloaded launcher and apps
```

Then restart the box.

## Re-enable a single app

Without a computer: Settings > Apps > See all apps > Show system apps > pick the app > **Enable**.

With ADB, one package per command:

```bash
TV_HOST=<box ip> tools/venv/bin/python tools/tv.py 'pm enable com.amazon.amazonvideo.livingroom'
```

Notes:
- **Setup wizard** (`com.google.android.tungsten.setupwraith`): only needed to re-pair the remote. Order
  matters, one package per command:
  1. `pm enable com.google.android.apps.tv.launcherx` (stock launcher first)
  2. `pm enable com.google.android.tungsten.setupwraith`, then pair the remote
  3. `pm disable-user --user 0 com.google.android.tungsten.setupwraith` (setup wizard first)
  4. `pm disable-user --user 0 com.google.android.apps.tv.launcherx`, then set Home to the launcher again

  Never reboot with launcherx off and setupwraith on: its RecoveryActivity becomes Home, and on a MiTV-MOEU0
  that state ended in a factory reset (upstream #3578).
- **Stock launcher** (`com.google.android.apps.tv.launcherx`): enabling it makes it Home again (it outranks
  sideloaded launchers on Google TV).

## Notes from this box

From CleanYourTV's `examples/xiaomi-mitv-afmu0` (two units on this firmware) and this box:

- **Why the setup wizard is disabled.** With launcherx off, setupwraith's RecoveryActivity (HOME priority 1)
  beats a sideloaded launcher (priority 0) and takes Home. It has to be disabled *before* launcherx.
- **Wi-Fi can drop when setupwraith is disabled.** A network saved during first setup belongs to setupwraith,
  and Android deletes it with the app (logged as `WifiConfigManager: Remove all networks for app ...
  setupwraith`), which also cuts ADB. Check first: `tools/tv.py "dumpsys wifi" | grep -o 'cname=[^ ]*'`. Every
  entry should be `android.uid.system`; if one is setupwraith, forget and re-join that network in Settings first.
  On 2026-11-19 all four saved networks here were `android.uid.system`.
- **Leave alone:** `com.xiaomi.android.tvsetup.partnercustomizer`, `mitv.service`, `com.droidlogic*`.
- **Play Store updates re-enable disabled apps.** PatchWall (`com.mitv.tvhome.atv`) was found enabled on
  2026-11-19 after Play Store updated it (4.3.7.1 in `/system` to 4.21.148.1 in `/data/app`). It is kept on
  purpose now and commented out in `debloat.txt`; other updated packages can come back the same way.
- **Remote buttons.** With launcherx off, Netflix (BUTTON_6) still works, but YouTube (BUTTON_3), Prime Video
  (BUTTON_7), Xiaomi TV+ (BUTTON_5) and Apps (ALL_APPS) do nothing until the launcher maps them back.
- **Volume buttons send IR to the TV**, not to the box: `getevent -lt` shows nothing while they are held, so a
  held button not repeating is the TV's doing.
- **Wireless debugging keeps port 5555 open across reboots**, unencrypted. Switch USB and Wireless debugging
  off when done.
- **Disabled packages survive a reboot.** The firmware re-enabled nothing after a restart on either unit.

## Change the dock order or the apps in it

Edit the `dock_apps` list in `launcher/app/src/main/res/values/config.xml` (one package per
`<item>`, left to right; `columns` is the number of slots), then rebuild:

```bash
TV_HOST=<box ip> devices/xiaomi-tv-box-s-3rd-gen/build-and-install.sh launcher
```

Package names of installed apps: `TV_HOST=<box ip> tools/venv/bin/python tools/tv.py 'pm list packages -3'`.
You can also hold OK on a tile on the TV to move it.

## Change the wallpaper

- **Home background:** the photo of the day changes by itself every day. To use your own picture instead, set
  `home_photo` to `false` in that `config.xml` and push a theme with a `wallpaper.jpg` (1920x1080, dark, under
  about 300 KB) with CleanXiaomiTV's own theme script, which targets its launcher:
  `TV_HOST=<box ip> tools/theme.sh themes/aurora` (copy that folder and replace
  its `wallpaper.jpg`).
- **Screensaver:** Settings > System > Ambient mode on the TV, or switch back to the launcher's clock
  screensaver with
  `tools/tv.py 'settings put secure screensaver_components com.majnioui.cleanxiaomitv/com.gathin.tvhome.ClockDream'`.

## Change the city or prayer method

`weather_latitude`, `weather_longitude`, `weather_place` and `prayer_method` (20 = Kemenag) in the launcher's
`config.xml`; `latitude`, `longitude`, `place` in `modules/{weather,prayer}/src/main/res/values/config.xml`.
Then `devices/xiaomi-tv-box-s-3rd-gen/build-and-install.sh all`. The box has no GPS, so the location is fixed. The Weather app also has a
**Change city** button.

## AdGuard DNS

Turn it off: `tools/tv.py 'settings put global private_dns_mode opportunistic'`, or Settings > Network &
Internet > Private DNS > Automatic.

## Turning ADB back on

Settings > System > Developer options > **USB debugging** and **Wireless debugging** on, keep the box on Wi-Fi,
accept "Allow USB debugging?" on the screen. Turn both off again when done: port 5555 is unencrypted.
