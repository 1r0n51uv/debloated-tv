#!/usr/bin/env bash
# Usage: tools/disable.sh pkg [pkg...]  — one pm call per package, logged for restore.sh
export MSYS_NO_PATHCONV=1
TV="${TV:-192.168.224.196:5555}"
LOG="$(dirname "$0")/../backup/disabled_by_debloat.txt"
for p in "$@"; do
  r=$(adb -s "$TV" shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')
  echo "$p -> $r"
  case "$r" in *"new state: disabled"*) grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG";; esac
done
