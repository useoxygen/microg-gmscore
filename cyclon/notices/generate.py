#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
"""Build offline notices from the actual variant artifacts and reviewed metadata.

This is a Maven artifact notice inventory, not an audit of every embedded native
dependency. See README.md for coverage and remaining release review requirements.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

NOTICE = re.compile(r"(?:^|/)(?:licen[cs]e|notice|copying)(?:[./_-]|$)", re.I)
MAX_TEXT = 2 * 1024 * 1024
MAX_NESTED = 32 * 1024 * 1024


def archive_notices(archive):
    result = []
    with zipfile.ZipFile(archive) as z:
        for item in sorted(z.infolist(), key=lambda item: item.filename):
            if item.is_dir():
                continue
            if NOTICE.search(item.filename) and not item.filename.endswith(".class"):
                if item.file_size > MAX_TEXT:
                    raise ValueError("Oversized license entry: " + item.filename)
                result.append((item.filename, z.read(item).decode("utf-8")))
            elif item.filename == "classes.jar" or (item.filename.startswith("libs/") and item.filename.endswith(".jar")):
                if item.file_size > MAX_NESTED:
                    raise ValueError("Oversized nested archive: " + item.filename)
                result.extend((item.filename + "/" + name, text) for name, text in archive_notices(io.BytesIO(z.read(item))))
    return result


def generate(artifacts, registry, texts):
    rows = []
    for entry in registry["bundled"]:
        rows.append({"title": entry["title"], "license": entry["license"],
                     "text": "\n\n".join((texts / name).read_text() for name in entry["texts"])})
    grouped = {}
    for artifact in artifacts:
        grouped.setdefault(artifact["coordinate"], set()).add(artifact["path"])
    for coordinate, paths in sorted(grouped.items()):
        review = registry["dependencies"].get(coordinate)
        if not review:
            raise ValueError("Review license metadata for new runtime dependency: " + coordinate)
        parts = [coordinate, "License metadata: " + ", ".join(review["licenses"])]
        provenance = []
        for path in sorted(paths, key=lambda path: Path(path).name):
            artifact = Path(path)
            provenance.append({"file": artifact.name, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()})
            for name, text in archive_notices(artifact):
                parts.append(artifact.name + ": " + name + "\n\n" + text)
        for name in review["texts"]:
            parts.append((texts / name).read_text())
        rows.append({"title": coordinate, "license": ", ".join(review["licenses"]),
                     "text": "\n\n".join(parts), "artifacts": provenance})
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--texts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = generate(json.loads(args.artifacts.read_text()), json.loads(args.registry.read_text()), args.texts)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
    print("Bundled %d offline license entries." % len(rows))


if __name__ == "__main__":
    main()
