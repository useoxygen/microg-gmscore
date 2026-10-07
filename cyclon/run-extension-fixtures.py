#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
"""Run isolated Android extension fixtures and retain hash/result receipts."""
import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--calendar-apk", required=True, type=Path)
    parser.add_argument("--location-apk", required=True, type=Path)
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicitly selected Android emulator is accepted")

    def adb(*parts, binary=False, timeout=45):
        return subprocess.check_output(["adb", "-s", args.serial, *parts],
                                       text=not binary, stderr=subprocess.STDOUT, timeout=timeout)

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("The selected serial is not an emulator")
    fixtures = [
        ("calendar", "org.microg.gms.calendar.sync.android.test", args.calendar_apk,
         ["READ_CALENDAR", "WRITE_CALENDAR"]),
        ("location", "org.microg.gms.location.core.test", args.location_apk,
         ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION"]),
    ]
    # Preflight every artifact before making any device change. Never replace an existing app.
    for _, package, apk, _ in fixtures:
        badging = subprocess.check_output([str(args.aapt), "dump", "badging", str(apk)], text=True)
        if not re.search(r"package: name='" + re.escape(package) + r"'", badging):
            parser.error("An APK does not match the reserved fixture package")
        if ("package:" + package) in adb("shell", "pm", "list", "packages", package).splitlines():
            parser.error("A fixture package is already installed; review its ownership before removing it")
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = {"format": 1, "serial": args.serial, "emulator": True,
               "started_at": datetime.now(timezone.utc).isoformat(), "fixtures": [], "cleanup": []}
    installed = []
    try:
        for name, package, apk, permissions in fixtures:
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            adb("install", "-t", str(apk), timeout=90)
            installed.append(package)
            path = adb("shell", "pm", "path", package).strip().removeprefix("package:")
            readback = hashlib.sha256(adb("exec-out", "cat", path, binary=True)).hexdigest()
            if readback != digest:
                raise RuntimeError("Installed fixture bytes differ from the supplied APK")
            for permission in permissions:
                adb("shell", "pm", "grant", package, "android.permission." + permission)
            if name == "location":
                adb("shell", "appops", "set", package, "android:mock_location", "allow")
            output = adb("shell", "am", "instrument", "-w",
                         package + "/androidx.test.runner.AndroidJUnitRunner", timeout=180)
            (args.output / (name + ".log")).write_text(output)
            matched = re.search(r"OK \((\d+) tests?\)", output)
            passed = bool(matched) and "FAILURES!!!" not in output
            receipt["fixtures"].append({"name": name, "package": package, "apk_sha256": digest,
                "installed_sha256": readback, "passed": passed,
                "tests": int(matched.group(1)) if matched else None})
            if not passed:
                raise RuntimeError(name + " fixture failed; inspect its retained log")
    finally:
        # Uninstall only the new fixture APKs. Their synthetic accounts and permission grants
        # belong to these packages; no product APK, Google account, or other emulator is changed.
        for package in reversed(installed):
            try:
                output = adb("uninstall", package)
                receipt["cleanup"].append({"package": package, "removed": output.strip() == "Success"})
            except Exception:
                receipt["cleanup"].append({"package": package, "removed": False})
        receipt["finished_at"] = datetime.now(timezone.utc).isoformat()
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if not all(row["removed"] for row in receipt["cleanup"]):
        raise RuntimeError("Fixture cleanup incomplete; inspect receipt.json")
    print("Android extension fixtures passed; receipts: " + str(args.output))


if __name__ == "__main__":
    main()
