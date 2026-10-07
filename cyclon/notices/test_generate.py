# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
import io
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
            rows = generate(artifacts, registry, root)
            self.assertIn("Tatu Saloranta", rows[0]["text"])
            self.assertIn("QOS.ch Sarl", rows[1]["text"])
            self.assertEqual(rows, generate(list(reversed(artifacts)), registry, root))
            self.assertEqual(64, len(rows[0]["artifacts"][0]["sha256"]))

    def test_unreviewed_dependency_fails_instead_of_defaulting_to_apache(self):
        with self.assertRaisesRegex(ValueError, "new runtime dependency: unknown:library:1"):
            generate([{"coordinate": "unknown:library:1", "path": "unused.jar"}],
                     {"bundled": [], "dependencies": {}}, Path("."))

    def test_oversized_notice_fails(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as jar:
            jar.writestr("META-INF/NOTICE", "x" * (MAX_TEXT + 1))
        with self.assertRaisesRegex(ValueError, "Oversized license entry"):
            archive_notices(buf)


if __name__ == "__main__":
    unittest.main()
