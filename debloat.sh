#!/usr/bin/env bash
# Re-applies the debloat from the lists in backup/ (e.g. after restore.sh, an OTA or a
# factory reset). Undo with restore.sh. Usage: ./debloat.sh [tv-ip:5555]
export MSYS_NO_PATHCONV=1
TV="${1:-192.168.224.196:5555}"
cd "$(dirname "$0")"
adb connect "$TV" >/dev/null

# One pm call per adb invocation: chained pm commands can silently stop after the first
while read -r p; do
  [ -n "$p" ] || continue
  echo "$p -> $(adb -s "$TV" shell pm disable-user --user 0 "$p" </dev/null | tr -d '\r')"
done < backup/disabled_by_debloat.txt

# Xiaomi re-enables these at every boot; suspending keeps them from running anyway
while read -r p; do
  [ -n "$p" ] || continue
  adb -s "$TV" shell pm suspend --user 0 "$p" </dev/null
done < backup/suspended_by_debloat.txt

# Ad-blocking Private DNS (undo: private_dns_mode opportunistic)
adb -s "$TV" shell settings put global private_dns_specifier dns.adguard-dns.com
adb -s "$TV" shell settings put global private_dns_mode hostname

# Custom launcher as home, if installed
if adb -s "$TV" shell pm path com.sev.atvhome </dev/null | grep -q package:; then
  adb -s "$TV" shell cmd package set-home-activity com.sev.atvhome/.HomeActivity
fi
adb -s "$TV" shell input keyevent KEYCODE_HOME
