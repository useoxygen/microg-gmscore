# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
import io
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from generate import archive_notices, generate, MAX_TEXT


class NoticeTests(unittest.TestCase):
    def test_actual_artifact_notices_survive_nested_aar_and_root_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, "w") as jar:
                jar.writestr("META-INF/NOTICE", "Copyright Tatu Saloranta. All rights reserved.")
            with zipfile.ZipFile(root / "example.aar", "w") as aar:
                aar.writestr("classes.jar", nested.getvalue())
            with zipfile.ZipFile(root / "example.jar", "w") as jar:
                jar.writestr("META-INF/LICENSE.txt", "Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)")
            registry = {"bundled": [], "dependencies": {
                "example:one:1": {"licenses": ["Apache-2.0"], "texts": []},
                "example:two:1": {"licenses": ["MIT"], "texts": []}}}
            artifacts = [{"coordinate": "example:one:1", "path": str(root / "example.aar")},
                         {"coordinate": "example:two:1", "path": str(root / "example.jar")}]
            rows = generate(artifacts, registry, root, "play-services-core")
            self.assertIn("Tatu Saloranta", rows[0]["text"])
            self.assertIn("QOS.ch Sarl", rows[1]["text"])
            self.assertEqual(rows, generate(list(reversed(artifacts)), registry, root, "play-services-core"))
            self.assertEqual(64, len(rows[0]["artifacts"][0]["sha256"]))

    def test_unreviewed_dependency_fails_instead_of_defaulting_to_apache(self):
        with self.assertRaisesRegex(ValueError, "new runtime dependency: unknown:library:1"):
            generate([{"coordinate": "unknown:library:1", "path": "unused.jar"}],
                     {"bundled": [], "dependencies": {}}, Path("."), "play-services-core")

    def test_oversized_notice_fails(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as jar:
            jar.writestr("META-INF/NOTICE", "x" * (MAX_TEXT + 1))
        with self.assertRaisesRegex(ValueError, "Oversized license entry"):
            archive_notices(buf)

    def test_plural_and_copyright_notice_names_are_preserved(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as jar:
            jar.writestr("META-INF/LICENSES.txt", "Multiple licenses")
            jar.writestr("NOTICES.md", "Original notices")
            jar.writestr("COPYRIGHT", "Original authors")
            jar.writestr("com/example/License.class", "Not a notice")
        self.assertEqual([("COPYRIGHT", "Original authors"), ("META-INF/LICENSES.txt", "Multiple licenses"),
                          ("NOTICES.md", "Original notices")], archive_notices(buf))

    def test_bundled_resources_are_scoped_to_the_module_that_ships_them(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "font.txt").write_text("Font copyright and license")
            registry = {"bundled": [{"title": "Core font", "license": "OFL-1.1",
                         "modules": ["play-services-core"], "texts": ["font.txt"]}], "dependencies": {}}
            self.assertEqual(1, len(generate([], registry, root, "play-services-core")))
            self.assertEqual([], generate([], registry, root, "vending-app"))

    def test_native_bytes_require_review_even_when_coordinate_is_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "native.aar"
            member = "jni/arm64-v8a/libexample.so"
            original = b"reviewed native bytes"
            with zipfile.ZipFile(archive, "w") as aar:
                aar.writestr(member, original)
            review = {"licenses": ["BSD-3-Clause"], "texts": []}
            registry = {"bundled": [], "dependencies": {"example:native:1": review}}
            artifacts = [{"coordinate": "example:native:1", "path": str(archive)}]
            with self.assertRaisesRegex(ValueError, "new or changed native binaries"):
                generate(artifacts, registry, root, "play-services-core")
            review["nativeArtifacts"] = {member: hashlib.sha256(original).hexdigest()}
            rows = generate(artifacts, registry, root, "play-services-core")
            self.assertEqual(review["nativeArtifacts"], rows[0]["artifacts"][0]["native"])
            for members in [{member: b"changed bytes"}, {}, {member: original, "jni/x86/libextra.so": b"new ABI"}]:
                with zipfile.ZipFile(archive, "w") as aar:
                    for name, data in members.items():
                        aar.writestr(name, data)
                with self.assertRaisesRegex(ValueError, "new or changed native binaries"):
                    generate(artifacts, registry, root, "play-services-core")


if __name__ == "__main__":
    unittest.main()
