#!/usr/bin/env python3
"""Spawn Waze with the test-only HostValidator hook, then open AARE's car preview on Waze.

Waze's CarAppService uses androidx.car.app.validation.HostValidator and rejects AARE's
signing certificate. The hooked validator accepts AARE so the public template UI can be
exercised without modifying the Waze APK.

Example:
    python3 run_waze.py
"""
import os
import subprocess
import sys
import time
from pathlib import Path

import frida

HERE = Path(__file__).resolve().parent
JS = HERE / "waze_host_agent_compiled.js"
SERIAL = os.environ.get("ANDROID_SERIAL", "")
PREVIEW = "net.prototux.aare/.ui.CarPreviewActivity"
PACKAGE = "com.waze"


def adb(*args, timeout=30):
    cmd = ["adb"]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += list(args)
    return subprocess.run(cmd, text=True, capture_output=True, timeout=timeout)


def shell_root(command, timeout=30):
    return adb("shell", "su", "-c", command, timeout=timeout)


def on_message(message, data):
    if message.get("type") == "send":
        print("[frida]", message.get("payload"), flush=True)
    elif message.get("type") == "error":
        print("[frida error]", message.get("stack") or message, flush=True)


def main():
    if not JS.exists():
        sys.exit(f"missing {JS}; build it with frida-compile, see README.md")

    whoami = shell_root("id")
    if "uid=0" not in whoami.stdout:
        sys.exit(f"root is required for the preview activity: {whoami.stdout}{whoami.stderr}")

    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "force-stop", "net.prototux.aare")
    adb("logcat", "-c")
    time.sleep(1)

    device = frida.get_usb_device(timeout=10)
    print("[driver] spawning Waze", flush=True)
    pid = device.spawn([PACKAGE])
    print("[driver] pid", pid, flush=True)
    session = device.attach(pid)
    script = session.create_script(JS.read_text())
    script.on("message", on_message)
    script.load()
    print("[driver] host hook loaded; resuming", flush=True)
    device.resume(pid)
    time.sleep(2)

    print("[driver] starting AARE preview on Waze", flush=True)
    result = shell_root(f"am start -W -n {PREVIEW} --es launchPackage {PACKAGE}")
    print(result.stdout, flush=True)
    print(result.stderr, flush=True)

    print("[driver] keeping the Waze hook alive; press Ctrl-C to stop", flush=True)
    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        print("[driver] stopped", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("[driver] FATAL:", repr(e), flush=True)
        sys.exit(1)
