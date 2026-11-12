#!/usr/bin/env python3
"""Read only this debug app's named PoC evidence files from the verified local AVD."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
adb = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"
base = [str(adb), "-s", sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"]
expected = "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"
if subprocess.check_output(base + ["shell", "getprop", "ro.build.fingerprint"], text=True).strip() != expected:
    raise SystemExit("Unexpected AVD; no evidence read")
user = subprocess.check_output(base + ["shell", "am", "get-current-user"], text=True).strip()
if not user.isdigit():
    raise SystemExit("Current Android user could not be identified")
names = ["flow-device-evidence.json", "gleo-local-intent-evidence.json",
    "process-restore-prepare.json", "process-restore-verify.json",
    "audio-player-device.json", "audio-focus-device.json", "sdk-runtime-observation.json",
    "flow-screenshot-status.txt", "flow-ended-device.png"]
for name in names:
    result = subprocess.run(base + ["exec-out", "run-as", "com.hanhyo.pleosshuttle",
        "--user", user, "cat", "files/" + name], capture_output=True, check=True)
    if name.endswith(".png") and not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SystemExit("Invalid screenshot: " + name)
    (root / "evidence" / name).write_bytes(result.stdout)
    print(name, len(result.stdout), "bytes")
