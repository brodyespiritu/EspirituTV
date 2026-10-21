# EspirituTV

A fast Home screen for TCL Android TV / Google TV, built from the EspirituTV canvas design:
full-screen show art, a frosted Apple-style dock, and a banner for the show you watch every week.

## Install

1. On the TV: Settings → System → About → press **Build** 7 times, then Developer options → **USB debugging** on
   (on Google TV it's called *Network debugging*). Note the TV's IP address under Network.
2. On your Mac:

```bash
./install.sh 192.168.1.50 --make-home
```

Accept the debugging prompt on the TV. `--make-home` turns off the stock Google TV home screen so Home opens
EspirituTV; `./install.sh <ip> --restore` puts it back. Run without `--make-home` to update later.

To see the screen with sample history (a show left half-way, and one watched the last three Tuesdays):
`./install.sh <ip> --demo`. Clear it from Settings → Forget watch history.

## What's on the screen

- **Background** – a popular show from the app focused in the dock (nothing tagged Horror), rotating every 14 s.
  With a TMDB key set (`./install.sh <ip> --tmdb-key KEY`, free from themoviedb.org/settings/api), each still
  gives way after 7 s to the show's trailer, muted, with a 600 ms crossfade in and out, then the next still.
  Trailers come from YouTube's embedded player and are off with no key; toggle them in Settings.
  To try the effect without an account, `--tmdb-key yt:VIDEOID` plays that one YouTube video for every show.
- **Banner** (top right) – *Continue watching* for a show you left part-way (last 4 days): where you stopped
  and the minutes left, OK resumes the app. Otherwise the show you watch on the same weekday (3 of the last 6
  weeks), with *Tonight* on that day. Nothing when there's neither. `./install.sh <ip> --history` prints what
  the TV has recorded. Home reads the TV's media sessions whenever it's in front (TCL kills background apps, so
  a live listener alone misses things). Hulu's TV app reports position but no title: the banner then says
  *On Hulu · 30 min in* until you hold OK on it and type the show's name (or
  `./install.sh <ip> --watching com.hulu.livingroomplus "The Mentalist"`).
- **Dock** – your apps (hold OK for options: move, remove, hide, uninstall), then All apps, Inputs, Settings.
  The focused app grows to 1.5×, gets a white ring and a glow, and the rest dim.
- **Add to Up Next** – saves the show; saved shows lead that app's rotation.

Watch history comes from the TV's media sessions (what Netflix, YouTube TV… report as playing), via the
notification-listener permission `install.sh` grants. It stays on the TV. Art and show details come from
[TVmaze](https://www.tvmaze.com) (CC BY-SA) and are cached for weeks, so the screen still fills in offline.

## Why it's fast

No frameworks, no web views, one ~1 MB APK. The show art and its shading are composed into a single screen-sized
bitmap off the main thread, so each frame draws one picture plus the small glass surfaces; the frosted glass samples
a 160 × 90 pre-blurred, pre-tinted copy of it (a texture lookup, no filters). App tiles are rendered once and cached
on disk. Images are decoded no larger than the screen (16-bit on low-RAM TVs), network data is cached for weeks,
nothing touches the network or disk on the main thread, and the art stops rotating after 10 minutes without a key
press. Measured on a 1080p emulator: 6 ms median frame, 0 janky frames, ~46 MB of memory.

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35.0.0):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease && cp app/build/outputs/apk/release/app-release.apk EspirituTV.apk
```

## Updates

EspirituTV updates itself. Once a day (and whenever you choose Settings → **Check for updates**) it reads a small
feed published with each GitHub release. When a newer version is out, Home shows an **Update available** chip
beside the avatar and Settings an **Update to 1.1** row; choosing either downloads the APK, checks its SHA-256
and asks the TV's usual install question. The first time, Android wants you to allow EspirituTV to install apps:
the launcher opens that switch for you; turn it on and press Back. After one over-the-air update the launcher is
the app's installer of record, and Android 12+ stops asking.

Releases live on GitHub Releases (`REPO` in `Updater.kt`; the repository has to be public so TVs can fetch the
feed without signing in). To ship one:

```bash
./release.sh 1.1 "What changed"
```

That raises `versionCode` by one and sets `versionName` in `app/build.gradle.kts`, builds the release APK, writes
`dist/latest.json` with the APK's SHA-256 and uploads both to a GitHub release tagged `v1.1`. A TV still running a
build from before the updater needs one last `./install.sh <ip>`; everything after that arrives on its own.

### Trying the update flow on the emulator

A debug build reads the feed from your Mac (`http://10.0.2.2:8000/latest.json`) instead of GitHub:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
./release.sh 1.1 "Test" --local
sed -i '' 's#"apkUrl": ".*"#"apkUrl": "http://10.0.2.2:8000/EspirituTV.apk"#' dist/latest.json
python3 -m http.server 8000 --directory dist
```

Press Home on the emulator: the chip appears within a few seconds, and **Download and install** walks through the
TV's confirmation. `release.sh --local` bumped the version; put it back in `app/build.gradle.kts` if that wasn't a
real release.
