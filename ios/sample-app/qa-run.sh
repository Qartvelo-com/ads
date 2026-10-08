#!/bin/bash
# Usage: ./qa-run.sh <device-udid> <output-dir> [-forceNoFill] [-bannerOnly] [-bannerWidth <points>]
set -euo pipefail
if [ "$#" -lt 2 ]; then
    echo "Usage: $0 <device-udid> qa-out-<name> [launch flags]" >&2
    exit 2
fi
sample_dir="$(cd "$(dirname "$0")" && pwd)"
DEVICE="$1"; OUT="$2"; shift 2
case "$OUT" in
    qa-out-*) ;;
    *) echo "Use a new qa-out-* directory name inside the sample app." >&2; exit 2 ;;
esac
if [[ "$OUT" == */* || -e "$sample_dir/$OUT" ]]; then
    echo "Output must be a new qa-out-* directory, without path separators." >&2
    exit 2
fi
OUT="$sample_dir/$OUT"
LOGPID=""; APPPID=""
cleanup() {
    [ -z "$APPPID" ] || kill "$APPPID" >/dev/null 2>&1 || true
    [ -z "$LOGPID" ] || kill "$LOGPID" >/dev/null 2>&1 || true
}
trap cleanup EXIT
BUNDLE_ID="ge.qartvelo.AdsTest"
mkdir -p "$OUT"
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl spawn "$DEVICE" log stream --level debug --style compact \
  --predicate 'subsystem == "com.qartvelo.ads"' > "$OUT/sdk.log" 2>&1 &
LOGPID=$!
sleep 1
xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" -autoRun "$@" > "$OUT/app.log" 2>&1 &
APPPID=$!
shots=0
completed=false
for _ in $(seq 1 180); do
  sleep 1
  n=$(awk '/SCREENSHOT_NOW/ { count++ } END { print count + 0 }' "$OUT/app.log" 2>/dev/null)
  while [ "$shots" -lt "$n" ]; do
    shots=$((shots + 1))
    name=$(grep "SCREENSHOT_NOW" "$OUT/app.log" | sed -n "${shots}p" | awk '{print $NF}' | tr -d '\r')
    xcrun simctl io "$DEVICE" screenshot "$OUT/shot-$shots-$name.png" >/dev/null 2>&1
  done
  if grep -q "QA: DONE" "$OUT/app.log"; then completed=true; break; fi
done
xcrun simctl io "$DEVICE" screenshot "$OUT/final.png" >/dev/null 2>&1
grep "QA:" "$OUT/app.log" | tr -d '\r'
if ! $completed; then
    echo "QA flow did not finish within 180 seconds." >&2
    exit 1
fi
