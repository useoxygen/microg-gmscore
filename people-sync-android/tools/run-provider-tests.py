#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run provider-only fixtures on one exact device without Android UI automation."""
import argparse
import json
import secrets
import shlex
import subprocess

PACKAGE = "org.microg.gms.people.sync.android.test"
PROOF = "files/physical-device-authorization.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact ADB transport and physical serial")
    parser.add_argument("--physical", action="store_true", help="Explicitly permit a development phone")
    parser.add_argument("--expect-refusal", action="store_true", help="Verify a mismatched serial makes zero fixture writes")
    args = parser.parse_args()

    def shell(*parts):
        return subprocess.run(["adb", "-s", args.serial, "shell", shlex.join(parts)],
                              check=True, capture_output=True, text=True).stdout.strip()

    def prop(name):
        return shell("getprop", name)

    is_emulator = prop("ro.kernel.qemu") == "1" or prop("ro.hardware") in ("ranchu", "goldfish")
    nonce = secrets.token_hex(16)
    if not is_emulator:
        if not args.physical or prop("ro.serialno") != args.serial:
            raise SystemExit("Physical tests require --physical and an exact ro.serialno match")
        if prop("ro.build.type") not in ("userdebug", "eng") or int(prop("ro.build.version.sdk")) < 29:
            raise SystemExit("Physical fixtures require a development build on Android 10 or newer")
    if prop("sys.boot_completed") != "1" or not shell("pm", "path", PACKAGE):
        raise SystemExit("The exact device must be booted and the fixture APK installed")

    # Grant only this fixture's contacts permissions; never acquire an accessibility connection.
    for permission in ("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"):
        shell("pm", "grant", PACKAGE, permission)

    try:
        if not is_emulator:
            proof = json.dumps({"serial": args.serial, "fingerprint": prop("ro.build.fingerprint"),
                                "nonce": nonce, "createdAt": int(shell("date", "+%s")) * 1000})
            # run-as refuses non-debuggable packages. Quotes remain literal shell data.
            shell("run-as", PACKAGE, "mkdir", "-p", "files")
            shell("run-as", PACKAGE, "sh", "-c", "printf %s " + shlex.quote(proof) + " > " + PROOF)
        command = ["am", "instrument", "-w", "-e", "class", "org.microg.gms.people.ContactProviderTest"]
        if not is_emulator:
            command += ["-e", "physicalDeviceSerial", "WRONG_SERIAL" if args.expect_refusal else args.serial,
                        "-e", "physicalDeviceNonce", nonce]
        if args.expect_refusal:
            if is_emulator:
                raise SystemExit("The serial-refusal test requires a physical development phone")
            command[command.index("org.microg.gms.people.ContactProviderTest")] += "#downloadIsIdempotentAndDoesNotUploadDeviceRows"
        command += [PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"]
        output = shell(*command)
        print(output)
        if args.expect_refusal:
            if "Fixture tests require an emulator or an explicitly selected development phone" not in output:
                raise SystemExit("The mismatched-serial guard did not reject the fixture run")
            print("Verified: mismatched serial refused before fixture setup")
        elif "OK (9 tests)" not in output:
            raise SystemExit("Provider fixture suite did not pass")
    finally:
        if not is_emulator:
            shell("run-as", PACKAGE, "rm", "-f", PROOF)


if __name__ == "__main__":
    main()
