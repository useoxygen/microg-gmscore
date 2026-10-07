#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
"""Build offline notices from the actual variant artifacts and reviewed metadata.

Native members are matched to reviewed bytes and producer-version notice bundles.
See README.md for coverage and remaining release review requirements.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

NOTICE = re.compile(r"(?:^|/)(?:licen[cs]es?|notices?|copying|copyright)(?:[./_-]|$)", re.I)
MAX_TEXT = 2 * 1024 * 1024
MAX_NESTED = 32 * 1024 * 1024
MAX_NATIVE = 128 * 1024 * 1024


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


def archive_natives(archive):
    result = {}
    with zipfile.ZipFile(archive) as z:
        for item in sorted(z.infolist(), key=lambda item: item.filename):
            if item.is_dir():
                continue
            if item.filename.endswith(".so"):
                if item.file_size > MAX_NATIVE:
                    raise ValueError("Oversized native entry: " + item.filename)
                digest = hashlib.sha256()
                with z.open(item) as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                if item.filename in result:
                    raise ValueError("Duplicate native entry: " + item.filename)
                result[item.filename] = digest.hexdigest()
            elif item.filename == "classes.jar" or (item.filename.startswith("libs/") and item.filename.endswith(".jar")):
                if item.file_size > MAX_NESTED:
                    raise ValueError("Oversized nested archive: " + item.filename)
                result.update((item.filename + "/" + name, digest)
                              for name, digest in archive_natives(io.BytesIO(z.read(item))).items())
    return result


def generate(artifacts, registry, texts, module):
    rows = []
    for entry in registry["bundled"]:
        if "modules" in entry and module not in entry["modules"]:
            continue
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
        if review.get("noticeSources"):
            parts.append("Notice and source provenance:\n" + "\n".join(review["noticeSources"]))
        provenance = []
        for path in sorted(paths, key=lambda path: Path(path).name):
            artifact = Path(path)
            native = archive_natives(artifact)
            if native != review.get("nativeArtifacts", {}):
                raise ValueError("Review new or changed native binaries for: " + coordinate)
            provenance.append({"file": artifact.name, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                               "native": native})
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
    parser.add_argument("--module", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = generate(json.loads(args.artifacts.read_text()), json.loads(args.registry.read_text()), args.texts, args.module)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
    print("Bundled %d offline license entries." % len(rows))


if __name__ == "__main__":
    main()
