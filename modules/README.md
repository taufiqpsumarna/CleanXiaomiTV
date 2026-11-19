# Modules

Three small Android TV apps that show up on the CleanXiaomiTV home screen as **module cards**
(see [`docs/modules.md`](../docs/modules.md)). Weather and Prayer Times are about 170 KB and Channels about 750 KB (it includes a video player).
None of them runs in the background, and each uses the network only to fetch its own data.

| | |
|---|---|
| ![Weather](docs/weather.png) | ![Prayer Times](docs/prayer.png) |
| **Weather**: today and a 7-day forecast from [Open-Meteo](https://open-meteo.com). **Change city** searches any city with the remote; **Refresh** reloads. | **Prayer Times**: today's times from [Aladhan](https://aladhan.com) (Morocco method), with the next prayer and a countdown. |
| ![The channel list over a channel playing](docs/channels.png) | ![A channel playing](docs/channels-playing.png) |
| **Channels**: 26 family-friendly live Indonesian channels (Nasional, Berita, Jawa Barat, Hiburan, Edukasi & Budaya, Religi) from free streams the broadcasters publish. A Live TV page: the focused channel plays in a preview at the top, with one row of channel cards per category below; OK goes full screen, where Up / Down or CH+ / CH- switch channels and OK or Back return to the cards. | The channel list is in `channels/src/main/res/raw/channels.m3u`; once a day the app takes fresh links and logos for the same channels from [iptv-org](https://github.com/iptv-org/iptv)'s public lists, matched by `tvg-id`. Six channels (TVRI Nasional, Trans TV, Trans 7, iNews, RTV, Magna) come from the broadcasters' own public streams as listed in [dhanytv](https://github.com/dhasap/dhanytv); their `tvg-id`s carry a suffix iptv-org doesn't use, so the daily refresh leaves those links alone. A refreshed link never replaces a bundled one outright: if it fails to play, the app falls back to the bundled link, and a channel that fails on both gets an Offline badge. The preview on the cards page is muted; sound comes on in full screen. |

## Install

From the repo root, with the TV's IP:

```bash
cd modules && ./gradlew assembleRelease && cd ..
for m in weather prayer channels; do
  TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/b64push.py modules/$m/build/outputs/apk/release/$m-release.apk /data/local/tmp/$m.apk
  TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/tv.py "pm install -r /data/local/tmp/$m.apk; rm -f /data/local/tmp/$m.apk"
done
```

Press Home and the launcher asks once per app whether to show its card. Hold OK on Settings → Module cards to
change your answer.

The default city is Casablanca: `latitude`, `longitude` and `place` in each app's `src/main/res/values/config.xml`.
Prayer Times uses that city; Weather starts there and remembers whatever you pick with Change city.

Icons: [Material](https://fonts.google.com/icons) (Apache 2.0), [Feather](https://feathericons.com) (MIT) and
[Lucide](https://lucide.dev) (ISC). Font: Outfit (OFL, `FONT-LICENSE-OFL.txt`).
