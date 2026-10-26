#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
"""List Play services client classes and their minimum GMS version in an APK.
A GmsClient subclass names its service action (const-string ...START/BIND) and overrides
getMinApkVersion() as `const vX, #int N; return vX` in a ()I method."""
import re, subprocess, sys, tempfile, zipfile, os, glob
dexdump = sorted(glob.glob(os.path.expanduser('~/Library/Android/sdk/build-tools/*/dexdump')))[-1]
apk = sys.argv[1]
rows = {}
with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as td:
    for name in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', name): continue
        p = os.path.join(td, name); open(p, 'wb').write(z.read(name))
        run = subprocess.run([dexdump, '-d', p], capture_output=True, text=True, errors='ignore')
        if run.returncode != 0:
            # A failed disassembly must not read as "no API above the reported version".
            sys.exit(f'dexdump failed on {name} ({run.returncode}): {run.stderr.strip()[-500:]}')
        out = run.stdout
        for cls in out.split('Class #')[1:]:
            acts = set(re.findall(r'"(com\.google\.android\.(?:gms|location|mobstore)[\w.]*\.(?:START|BIND))"', cls))
            if not acts: continue
            mins = []
            for m in re.finditer(r"type\s+: '\(\)I'.*?(?=\n\s+#\d+\s+:|\Z)", cls, re.S):
                body = m.group(0)
                c = re.findall(r'\|0000: const(?:/high16)? v\d+, #(?:int|float) [^\n]*?// #([0-9a-f]+)', body)
                if c and re.search(r'\|000\d: return v\d+', body) and len(re.findall(r'\|\d{4}: ', body)) == 2:
                    n = int(c[0], 16)
                    if 1_000_000 <= n <= 400_000_000: mins.append(n)
            if mins:
                for a in acts: rows[a] = max(rows.get(a, 0), max(mins))
for a, n in sorted(rows.items(), key=lambda x: -x[1]): print(n, a)
