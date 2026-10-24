#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
"""Verify migration's compile-only platform classes are not defined in the APK."""
import argparse
import struct
import zipfile


def definitions(dex):
    if dex[:4] != b'dex\n':
        raise ValueError('Expected standard DEX')
    uint = lambda off: struct.unpack_from('<I', dex, off)[0]
    string_count, strings = uint(56), uint(60)
    type_count, types = uint(64), uint(68)
    class_count, classes = uint(96), uint(100)
    for index in range(class_count):
        type_id = uint(classes + index * 32)
        if type_id >= type_count:
            raise ValueError('Invalid DEX type index')
        string_id = uint(types + type_id * 4)
        if string_id >= string_count:
            raise ValueError('Invalid DEX string index')
        pos = uint(strings + string_id * 4)
        # Skip the unsigned LEB128 UTF16-length prefix. Class descriptors are ASCII.
        for _ in range(5):
            byte = dex[pos]
            pos += 1
            if not byte & 128:
                break
        else:
            raise ValueError('Invalid DEX string length')
        end = dex.index(0, pos)
        yield dex[pos:end].decode('ascii')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        classes = set()
        for entry in apk.namelist():
            if entry.startswith('classes') and entry.endswith('.dex'):
                classes.update(definitions(apk.read(entry)))
    forbidden = {
        'Landroid/app/backup/BackupTransport;',
        'Landroid/app/backup/RestoreSet;',
        'Landroid/app/backup/RestoreDescription;',
    }
    if classes & forbidden:
        raise SystemExit('ERROR: compile-only framework classes leaked into APK')
    required = {
        'Lcom/google/android/gms/backup/BackupTransportService;',
        'Lorg/microg/gms/migration/backup/MigrationBackupTransport;',
        'Lorg/microg/gms/migration/android/AndroidTargetDeviceBinder;',
        'Lcom/google/security/cryptauth/lib/securegcm/Ukey2Handshake;',
        'Lorg/microg/gms/migration/protobuf/ByteString;',
    }
    if not required <= classes:
        raise SystemExit('ERROR: migration implementation is missing from APK')
    print('PASS: migration implementation present; compile-only Android backup stubs absent')


if __name__ == '__main__':
    main()
