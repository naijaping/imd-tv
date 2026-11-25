#!/usr/bin/env bash
# Records IMD TV's memory and frame smoothness over a session, for before/after comparisons (issue: memory growth and
# lag after long use). Needs adb connected to the TV (adb connect <ip>:5555).
#
#   tools/memwatch.sh [minutes] [seconds-between] [out.csv]       e.g. tools/memwatch.sh 30 60 before.csv
#   HEAP=1 tools/memwatch.sh 30 60 before.csv                     also pulls a heap dump at the start and the end
#
# Each row: time, total PSS, Java heap, native heap, graphics (KB, from `dumpsys meminfo`), and frames drawn / janky
# frames since the last row (from `dumpsys gfxinfo`). Use the app normally meanwhile (surf channels, open categories,
# Movies, Series, add-ons). Compare the CSVs of two builds; heap dumps open in Android Studio's profiler.
set -euo pipefail
PKG=org.imdtv.iptvtv
MIN=${1:-30}; EVERY=${2:-60}; OUT=${3:-memwatch-$(date +%Y%m%d-%H%M).csv}

adb get-state >/dev/null 2>&1 || { echo "adb isn't connected to the TV (adb connect <ip>:5555)"; exit 1; }
adb shell pidof "$PKG" >/dev/null 2>&1 || { echo "IMD TV isn't running on the TV: start it first"; exit 1; }

# "App Summary" lines of dumpsys meminfo: "Java Heap:  12345", "Native Heap:  6789", "Graphics:  1234", "TOTAL PSS:  98765".
field() { awk -v k="$1" 'index($0, k) { for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+$/) { print $i; exit } }'; }

heap() {
  local f=/data/local/tmp/imdtv-$1.hprof
  adb shell am dumpheap "$PKG" "$f" && sleep 5 && adb pull "$f" "${OUT%.csv}-$1.hprof" >/dev/null && adb shell rm -f "$f"
  echo "heap dump: ${OUT%.csv}-$1.hprof"
}

echo "time,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb,frames,janky_frames" > "$OUT"
[ "${HEAP:-0}" = 1 ] && heap start
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
end=$(( $(date +%s) + MIN * 60 ))
while [ "$(date +%s)" -lt "$end" ]; do
  sleep "$EVERY"
  mem=$(adb shell dumpsys meminfo "$PKG")
  gfx=$(adb shell dumpsys gfxinfo "$PKG" reset)
  row="$(date +%H:%M:%S),$(field 'TOTAL PSS:' <<<"$mem"),$(field 'Java Heap:' <<<"$mem"),$(field 'Native Heap:' <<<"$mem"),$(field 'Graphics:' <<<"$mem"),$(field 'Total frames rendered:' <<<"$gfx"),$(field 'Janky frames:' <<<"$gfx")"
  echo "$row" | tee -a "$OUT"
done
[ "${HEAP:-0}" = 1 ] && heap end
echo "saved $OUT"
