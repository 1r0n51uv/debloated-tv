#!/usr/bin/env bash
# Build the launcher and install it on the TV as the default home screen.
# Usage: ./install.sh [tv-ip:5555] [--no-build]
set -e
export MSYS_NO_PATHCONV=1
TV="${1:-192.168.224.196:5555}"
PKG=com.sev.atvhome
cd "$(dirname "$0")"

if [ "$2" != "--no-build" ]; then
  (cd launcher && ./gradlew assembleDebug -q)
fi
APK=launcher/app/build/outputs/apk/debug/app-debug.apk

adb connect "$TV" >/dev/null
# 'adb install' can wait forever for an on-screen confirmation on some firmware;
# pushing and running pm install over the shell avoids that.
adb -s "$TV" push "$APK" /data/local/tmp/atvhome.apk >/dev/null
adb -s "$TV" shell pm install -r /data/local/tmp/atvhome.apk
adb -s "$TV" shell rm -f /data/local/tmp/atvhome.apk

# The Wi-Fi pill can show the network name only while location is on (Android 11 rule).
# On this TV, turning location on forces a Google "location accuracy" consent dialog and
# declining it turns location back off, so location is left as it is and the pill reads "Wi-Fi".
adb -s "$TV" shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true

adb -s "$TV" shell cmd package set-home-activity $PKG/.HomeActivity
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "Installed. HOME candidates:"
adb -s "$TV" shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep /
