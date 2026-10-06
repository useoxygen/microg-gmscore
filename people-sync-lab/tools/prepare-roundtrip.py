#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepare fresh exact-device proof, then open the optional disposable-contact test."""
import argparse
import hashlib
import json
import shlex
import subprocess
import uuid

PACKAGE = "org.microg.gms.contacts.lab"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--account", required=True, help="Exact authorized Google account")
    args = parser.parse_args()

    def shell(*parts):
        return subprocess.run(["adb", "-s", args.serial, "shell", shlex.join(parts)],
                              check=True, capture_output=True, text=True).stdout.strip()

    if args.serial != "3B241JEKB00555" or shell("getprop", "ro.serialno") != args.serial:
        raise SystemExit("This live test is restricted to the explicitly authorized physical 00555")
    if shell("getprop", "ro.build.type") not in ("userdebug", "eng"):
        raise SystemExit("Live test requires a development build")
    if shell("getprop", "sys.boot_completed") != "1" or not shell("pm", "path", PACKAGE):
        raise SystemExit("Device must be booted and the debug lab APK installed")
    for permission in ("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"):
        shell("pm", "grant", PACKAGE, permission)
    nonce = str(uuid.uuid4())
    proof = json.dumps({"serial": args.serial, "fingerprint": shell("getprop", "ro.build.fingerprint"),
                        "accountSha256": hashlib.sha256(args.account.encode()).hexdigest(),
                        "nonce": nonce, "createdAt": int(shell("date", "+%s")) * 1000})
    shell("run-as", PACKAGE, "mkdir", "-p", "files")
    shell("run-as", PACKAGE, "sh", "-c", "printf %s " + shlex.quote(proof) + " > files/roundtrip-device-proof.json")
    # Only the normal consent UI authorizes the Google scope. No token or client secret is handled here.
    print(shell("am", "start", "-n", PACKAGE + "/.ContactsLabActivity", "--es", "accountName", args.account,
                "--es", "roundTripNonce", nonce))
    print("Ready: tap Test disposable contact round trip. Proof expires in five minutes.")


if __name__ == "__main__":
    main()
