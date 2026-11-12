#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB=${ADB:-"$HOME/Library/Android/sdk/platform-tools/adb"}
SERIAL=${1:-emulator-5556}
AVD=$("$ADB" -s "$SERIAL" emu avd name | tr -d '\r' | head -n 1)
if [[ "$AVD" != "Pleos_Connect_v2.1.0" ]]; then
  printf 'Refusing installation: expected Pleos_Connect_v2.1.0, observed %s\n' "$AVD" >&2
  exit 1
fi
"$ADB" -s "$SERIAL" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -s "$SERIAL" shell am start -n com.hanhyo.pleosshuttle/.MainActivity
