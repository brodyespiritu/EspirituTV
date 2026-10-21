#!/bin/sh
# Installs EspirituTV on a TCL Android TV / Google TV over Wi-Fi.
#   ./install.sh 192.168.1.50              install / update
#   ./install.sh 192.168.1.50 --make-home  also make EspirituTV the Home screen
#   ./install.sh 192.168.1.50 --demo       add sample watch history (see the banner and Continue watching)
#   ./install.sh 192.168.1.50 --restore    put the stock TCL/Google launcher back
set -e
IP="$1"; MODE="$2"
[ -z "$IP" ] && { echo "Usage: $0 <TV-IP-address> [--make-home|--demo|--restore]"; exit 1; }
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

TV install -r "$DIR/EspirituTV.apk"
echo "Installed."

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
