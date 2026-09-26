#!/usr/bin/env python3
"""Drive a DjApp emulator by visible text. Never by remembered coordinates."""
import argparse, re, subprocess, sys, time, xml.etree.ElementTree as ET


def adb(serial, *args, binary=False):
    out = subprocess.run(["adb", "-s", serial, *args], capture_output=True, check=True)
    return out.stdout if binary else out.stdout.decode()


def nodes(serial):
    # The dump comes from the emulator we own, so stdlib XML parsing is fine here.
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    root = ET.fromstring(adb(serial, "shell", "cat", "/sdcard/ui.xml"))
    for n in root.iter("node"):
        yield n.get("text", ""), n.get("content-desc", ""), n.get("bounds", "")


def centre(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(serial, label):
    found = list(nodes(serial))
    for match in (lambda s: s == label, lambda s: label.lower() in s.lower()):
        for text, desc, bounds in found:
            if match(text) or match(desc):
                x, y = centre(bounds)
                adb(serial, "shell", "input", "tap", str(x), str(y))
                return
    sys.exit(f"no node labelled {label!r}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("-s", "--serial", required=True)
    # Multi-display devices (the Fold) need an id, or screencap writes a warning into the PNG.
    p.add_argument("-d", "--display", help="SurfaceFlinger display id for screencap")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("dump")
    sub.add_parser("tap").add_argument("label")
    sub.add_parser("burst").add_argument("prefix")
    a = p.parse_args()
    if a.cmd == "dump":
        for text, desc, bounds in nodes(a.serial):
            if text or desc:
                print(f"{text} | {desc} | {bounds}")
    elif a.cmd == "tap":
        tap(a.serial, a.label)
    else:
        display = ["-d", a.display] if a.display else []
        for i in (1, 2, 3):
            with open(f"{a.prefix}-{i}.png", "wb") as f:
                f.write(adb(a.serial, "exec-out", "screencap", *display, "-p", binary=True))
            time.sleep(2)


if __name__ == "__main__":
    main()
