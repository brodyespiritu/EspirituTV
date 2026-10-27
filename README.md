# EspirituTV

A fast Home screen for TCL Android TV / Google TV, built from the EspirituTV canvas design:
full-screen show art and rows of Apple TV-style app cards.

## Install

1. On the TV: Settings → System → About → press **Build** 7 times, then Developer options → **USB debugging** on
   (on Google TV it's called *Network debugging*). Note the TV's IP address under Network.
2. On your Mac:

```bash
./install.sh 192.168.1.50 --make-home
```

Accept the debugging prompt on the TV. `--make-home` turns off the stock Google TV home screen so Home opens
EspirituTV; `./install.sh <ip> --restore` puts it back. Run without `--make-home` to update later.

To try it with sample watch history (a show left half-way, which puts a dot under its app's card):
`./install.sh <ip> --demo`. Clear it from Settings → Forget watch history.

## What's on the screen

- **Loading screen** – while Home starts from cold (after a reboot or an update), after Apple Music's "Made by
  Sound": a black, still surface with a studio light's faint reflection on it; a drop lands on the heavy white
  EspirituTV wordmark with a glint, and the impact and its rebound send out rings of ripples, light glinting off
  their crests and the reflection wobbling as they cross it, bending the letters as they pass while the wordmark
  rises into view out of focus and sharpens. Like water, they bend red, green and blue differently, so the letters split into
  colour fringes and the crests into thin rainbows, closing back up into clean white as the surface settles and
  Home fades in. Gentler drops follow if Home is still loading.
- **Hero slideshow** – a slideshow of its own that doesn't follow the app cards: it takes turns between your
  streaming services, mostly series that premiered in the last few months (picked from TVmaze's schedules), plus
  established hits, nothing tagged Horror, your Up Next picks first, up to 10 shows a visit. Press right past the
  last button for the next show, left past the first for the one before. Each show appears with
  its official title logo over a text-free backdrop (TMDB art, checked by eye), drifting slowly closer like the
  Apple TV screensaver, with a soft crossfade every 14 s and a fresh order on every visit to Home. On Android 11+
  the art runs on its own surface behind Home, so the motion never redraws the cards or text. Brand-new series
  read "New on …" with a *New series* tag. Turn the drift off in Settings → Background motion.
- **In-progress dot** – a small dot under the card of an app with a show left part-way (last 4 days).
  `./install.sh <ip> --history` prints what the TV has recorded. Home reads the TV's media sessions whenever it's
  in front (TCL kills background apps, so a live listener alone misses things).
- **CNAW2 news banners** – when cnaw2news.com publishes an article, it slides in as a frosted banner at the top
  right over whatever is on screen (a playing show included), stays five seconds and slides away. A small
  watcher checks the site's public feed once a minute; `install.sh` grants the "draw over other apps"
  permission it needs. Toggle it in Settings, or try it with `./install.sh <ip> --news-test`.
- **App cards** – five to a row under the show, in tvOS's 5:3 shape with Apple's continuous corners: your pinned
  apps first, then every other app, then Inputs and Settings. Each card is the app's own horizontal TV banner
  (the logo art Netflix, YouTube, Disney+… make for TV launchers); apps without one get their icon's background
  carried across the card so the logo and the card read as one. The focused card grows 1.1× with a white ring, a glow and its
  name beneath. Press down past the first row and the show fades back while the rows scroll up. Hold OK (or
  MENU) on an app for options: pin to the front, move, hide, uninstall.
- **Add to Up Next** – saves the show; saved shows lead that app's rotation.

Watch history comes from the TV's media sessions (what Netflix, YouTube TV… report as playing), via the
notification-listener permission `install.sh` grants. It stays on the TV. Art and show details come from
[TVmaze](https://www.tvmaze.com) (CC BY-SA) and are cached for weeks, so the screen still fills in offline.

## Why it's fast

No frameworks, no web views, one ~1 MB APK. The show art and its shading are composed into a single screen-sized
bitmap off the main thread, so each frame draws one picture plus the small glass surfaces; the frosted glass samples
a 160 × 90 pre-blurred, pre-tinted copy of it (a texture lookup, no filters). App cards are rendered once (already rounded) and
cached on disk. Images are decoded no larger than the screen (16-bit on low-RAM TVs), network data is cached for weeks,
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
