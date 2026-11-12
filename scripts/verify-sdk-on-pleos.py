#!/usr/bin/env python3
"""Run the explicit navigation trial and capture its native screen during the bounded window.

No door control is enabled: the separate guarded door trial is already recorded.
The instrumentation cancels only its own trial route and verifies an empty destination afterward.
"""
from pathlib import Path
import os
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[1]
evidence = root / "evidence"
adb = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"
device = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
base = [str(adb), "-s", device]
fingerprint = subprocess.check_output(base + ["shell", "getprop", "ro.build.fingerprint"], text=True).strip()
expected = "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"
if fingerprint != expected:
    raise SystemExit("Exact tested Pleos AVD required; no instrumentation started")
command = base + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
    "com.hanhyo.pleosshuttle.sdk.SdkRuntimeDeviceTest", "-e", "allowNavigationTrial", "true",
    "com.hanhyo.pleosshuttle.test/androidx.test.runner.AndroidJUnitRunner"]
lines = []
capture = None
with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1) as process:
    for line in process.stdout:
        lines.append(line)
        print(line, end="", flush=True)
        if "stage=NATIVE_NAV_SCREEN_READY" in line:
            time.sleep(1)
            image = subprocess.run(base + ["exec-out", "screencap", "-p"], capture_output=True, timeout=5)
            if image.returncode == 0 and image.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                capture = evidence / "native-navigation-guiding.png"
                capture.write_bytes(image.stdout)
                print("Native screen saved; separate visual inspection required.", flush=True)
    exit_code = process.wait()
output = "".join(lines)
(evidence / "sdk-runtime-final.log").write_text(output)
if exit_code or "OK (1 test)" not in output or "Process crashed" in output or capture is None:
    raise SystemExit("SDK harness or native capture incomplete; inspect the evidence")
