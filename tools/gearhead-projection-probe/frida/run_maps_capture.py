#!/usr/bin/env python3
"""Spawn Maps under Frida, load the UID gate hook, then start the probe Activity."""
import os
import subprocess
import sys
import time
from pathlib import Path

import frida

SERIAL = os.environ.get("ANDROID_SERIAL", "")
PROBE = "net.prototux.probe/.ProbeActivity"
JS = os.environ.get(
    "FRIDA_AGENT",
    str(Path(__file__).with_name("maps_capture_agent_compiled.js")),
)


def adb(*args, timeout=30):
    command = ["adb"]
    if SERIAL:
        command += ["-s", SERIAL]
    command += list(args)
    return subprocess.run(
        command, check=False, text=True, capture_output=True, timeout=timeout
    )


def on_message(message, data):
    if message.get("type") == "send":
        print("[frida]", message.get("payload"), flush=True)
    elif message.get("type") == "error":
        print("[frida error]", message.get("stack") or message, flush=True)


def main():
    print("[driver] force-stop Maps", flush=True)
    adb("shell", "am", "force-stop", "com.google.android.apps.maps")
    adb("shell", "am", "force-stop", "net.prototux.probe")
    time.sleep(1)

    device = frida.get_usb_device(timeout=10)
    print("[driver] spawning Maps", flush=True)
    pid = device.spawn(["com.google.android.apps.maps"])
    print("[driver] pid", pid, flush=True)
    session = device.attach(pid)
    script = session.create_script(open(JS, encoding="utf-8").read())
    script.on("message", on_message)
    script.load()
    print("[driver] hook loaded; resuming", flush=True)
    device.resume(pid)
    time.sleep(2)

    print("[driver] starting probe", flush=True)
    result = adb("shell", "am", "start", "-W", "-n", PROBE)
    print(result.stdout, flush=True)
    print(result.stderr, flush=True)

    # Let onSetup/onProjectionStart and the first frames arrive.
    time.sleep(20)
    print("[driver] done", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("[driver] FATAL:", repr(e), flush=True)
        sys.exit(1)
