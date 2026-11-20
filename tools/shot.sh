#!/usr/bin/env bash
# Usage: tools/shot.sh name   -> screenshots/name.png  (strips vendor log lines before PNG header)
export MSYS_NO_PATHCONV=1
TV="${TV:-192.168.224.196:5555}"
out="$(dirname "$0")/../screenshots/$1.png"
adb -s "$TV" exec-out screencap -p > "$out.raw"
python -c "import sys;d=open(sys.argv[1],'rb').read();i=d.find(b'\x89PNG');open(sys.argv[2],'wb').write(d[i:] if i>=0 else b'')" "$out.raw" "$out"
rm -f "$out.raw"; echo "$out $(wc -c <"$out") bytes"
