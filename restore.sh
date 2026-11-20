#!/usr/bin/env bash
# Re-enables every package the debloat disabled, restores the stock launcher as HOME,
# and resets Private DNS. Usage: ./restore.sh [tv-ip:5555]
export MSYS_NO_PATHCONV=1
TV="${1:-192.168.224.196:5555}"
cd "$(dirname "$0")"
adb connect "$TV" >/dev/null
while read -r p; do
  [ -n "$p" ] || continue
  echo "$p -> $(adb -s "$TV" shell pm enable --user 0 "$p" </dev/null | tr -d '\r')"
done < backup/disabled_by_debloat.txt
# Xiaomi re-enables Netflix and Prime Video at every boot, so those two are also suspended
while read -r p; do
  [ -n "$p" ] || continue
  adb -s "$TV" shell pm unsuspend --user 0 "$p" </dev/null
done < backup/suspended_by_debloat.txt
adb -s "$TV" shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
adb -s "$TV" shell settings put global private_dns_mode opportunistic
adb -s "$TV" shell settings delete global private_dns_specifier
echo "Done. Optional: adb -s $TV uninstall com.sev.atvhome   (removes the custom launcher)"
