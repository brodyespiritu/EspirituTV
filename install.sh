#!/bin/sh
# Installs EspirituTV on a TCL Android TV / Google TV over Wi-Fi.
#   ./install.sh 192.168.1.50              install / update
#   ./install.sh 192.168.1.50 --make-home  also make EspirituTV the Home screen
#   ./install.sh 192.168.1.50 --demo       add sample watch history (see the banner and Continue watching)
#   ./install.sh 192.168.1.50 --history    print what the TV has recorded being watched
#   ./install.sh 192.168.1.50 --restore    put the stock TCL/Google launcher back
set -e
IP="$1"; MODE="$2"; ARG="$3"
[ -z "$IP" ] && { echo "Usage: $0 <TV-IP-address> [--make-home|--demo|--history|--watching PKG \"Show\"|--news-test|--restore]"; exit 1; }
PKG=com.espiritu.tv
# Stock home screens: Google TV launcher, its setup helper (re-enables it), classic Android TV launcher.
STOCK="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.tvlauncher"
DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
[ -x "$ADB" ] || { echo "adb not found. Install it with: brew install android-platform-tools"; exit 1; }
TV() { "$ADB" -s "$IP:5555" "$@"; }

echo "Connecting to $IP… (accept the 'Allow USB debugging' prompt on the TV if it appears)"
"$ADB" connect "$IP:5555"
TV wait-for-device

if [ "$MODE" = "--restore" ]; then
  for P in $STOCK; do TV shell pm enable "$P" >/dev/null 2>&1 || true; done
  TV shell cmd notification disallow_listener "$PKG/.MediaWatcher" >/dev/null 2>&1 || true
  echo "Stock launcher restored. Press Home on the remote."
  exit 0
fi

if [ "$MODE" = "--demo" ]; then
  TV shell am broadcast -a com.espiritu.tv.DEMO -n "$PKG/.DevReceiver" >/dev/null
  echo "Sample history added. Press Home on the remote."
  exit 0
fi

if [ "$MODE" = "--news-test" ]; then
  TV shell am broadcast -a com.espiritu.tv.NEWS_TEST -n "$PKG/.DevReceiver" >/dev/null
  echo "The newest CNAW2 article should slide in at the top right for five seconds."
  exit 0
fi

if [ "$MODE" = "--history" ]; then
  TV logcat -c 2>/dev/null || true
  TV shell am broadcast -a com.espiritu.tv.SCAN -n "$PKG/.DevReceiver" >/dev/null
  TV shell am broadcast -a com.espiritu.tv.DUMP -n "$PKG/.DevReceiver" >/dev/null
  sleep 1
  TV logcat -d -s EspirituTV:I | sed 's/^.*EspirituTV: //'
  exit 0
fi

# Some TV apps (Hulu's) tell the system they're playing but not what. Name it (and the episode, once; the TV counts on from there):
#   ./install.sh <ip> --watching com.hulu.livingroomplus "The Mentalist" S3E7
if [ "$MODE" = "--watching" ]; then
  [ -n "$ARG" ] || { echo "Usage: $0 $IP --watching <app-package> \"<show name>\""; exit 1; }
  # The TV's shell splits the command again, so the name travels inside its own quotes.
  if [ -n "$5" ]; then EP="--es episode $5"; else EP=""; fi
  TV shell am broadcast -a com.espiritu.tv.WATCHING -n "$PKG/.DevReceiver" --es pkg "$ARG" --es name "\"$4\"" $EP >/dev/null
  echo "Noted: $ARG is playing ${4:-(cleared)}${5:+, $5}."
  exit 0
fi

TV install -r "$DIR/EspirituTV.apk"
echo "Installed."

# The CNAW2 news banner draws over whatever is on screen, a playing show included.
TV shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 && echo "News banners may draw over other apps."
TV shell am start-foreground-service -n "$PKG/.NewsService" >/dev/null 2>&1 || true

# Lets EspirituTV see what's playing (for Continue watching and the weekly banner). Nothing leaves the TV.
if TV shell cmd notification allow_listener "$PKG/.MediaWatcher" >/dev/null 2>&1; then
  echo "Watch-history access on."
else
  CUR="$(TV shell settings get secure enabled_notification_listeners | tr -d '\r')"
  case "$CUR" in *"$PKG/.MediaWatcher"*) ;; null|"") TV shell settings put secure enabled_notification_listeners "$PKG/.MediaWatcher" ;; *) TV shell settings put secure enabled_notification_listeners "$CUR:$PKG/.MediaWatcher" ;; esac
  echo "Watch-history access on."
fi

if [ "$MODE" = "--make-home" ]; then
  TV shell cmd package set-home-activity "$PKG/.MainActivity" || true
  INSTALLED="$(TV shell pm list packages | tr -d '\r')"
  for P in $STOCK; do
    if echo "$INSTALLED" | grep -q "package:$P\$"; then
      TV shell pm disable-user --user 0 "$P" && echo "Turned off $P"
    fi
  done
  TV shell input keyevent KEYCODE_HOME
  echo "Done. EspirituTV is now your Home screen."
fi
