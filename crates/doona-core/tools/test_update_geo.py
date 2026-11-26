import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import update_geo


class GeoBundleTest(unittest.TestCase):
    def setUp(self):
        tmp = self.enterContext(tempfile.TemporaryDirectory())
        self.bundle = Path(tmp) / "bundle"
        self.pins = Path(tmp) / "pins.json"
        self.manifest = self.bundle / "geo-manifest.json"
        self.data = {"geoip.dat": b"fixture ip", "geosite.dat": b"fixture site"}
        self.expected = {
            "version": 1,
            "release_date": "2026-11-23",
            "files": {
                name: {
                    "source": f"https://example.org/releases/42/{name}",
                    "version": "42",
                    "size_bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
                for name, data in self.data.items()
            },
        }
        self.pins.write_text(json.dumps(self.expected), encoding="utf-8")

    def fetch(self):
        assets = {
            pin["source"]: self.data[name]
            for name, pin in self.expected["files"].items()
        }
        with (
            patch.object(update_geo, "PINS", self.pins),
            patch.object(
                update_geo,
                "urlopen",
                side_effect=lambda url, **_: io.BytesIO(assets[url]),
            ),
        ):
            update_geo.main([str(self.bundle)])

    def check_failure(self, detail, *options):
        error = io.StringIO()
        with contextlib.redirect_stderr(error), self.assertRaises(SystemExit) as result:
            update_geo.main([*options, str(self.bundle)])
        self.assertEqual(result.exception.code, 1)
        self.assertIn(detail, error.getvalue())

    def check_latest_failure(self, detail, assets):
        metadata = {
            "releases/latest": {
                "published_at": "2026-11-23T02:58:26Z",
                "assets": assets,
            },
            "branches/release": {"commit": {"sha": "a" * 40}},
        }
        with patch.object(update_geo, "get_json", side_effect=metadata.__getitem__):
            self.check_failure(detail, "--latest")
        self.assertFalse(self.bundle.exists())

    def test_latest_rejects_null_digest(self):
        self.check_latest_failure(
            "geoip.dat: release has no published SHA-256",
            [{"name": "geoip.dat", "digest": None, "size": 10}],
        )

    def test_latest_rejects_missing_asset(self):
        self.check_latest_failure(
            "geosite.dat: missing from the MetaCubeX release",
            [{"name": "geoip.dat", "digest": "sha256:" + "a" * 64, "size": 10}],
        )

    def run_current(self):
        assets = [
            {"name": name, "digest": f"sha256:{pin['sha256']}", "size": 1}
            for name, pin in self.expected["files"].items()
        ]
        metadata = {
            "releases/latest": {
                "published_at": "2026-11-25T02:58:26Z",
                "assets": assets,
            },
            "branches/release": {"commit": {"sha": "b" * 40}},
        }
        with (
            patch.object(update_geo, "PINS", self.pins),
            patch.object(update_geo, "get_json", side_effect=metadata.__getitem__),
            patch.object(update_geo, "urlopen", side_effect=AssertionError),
        ):
            update_geo.main(["--current"])

    def test_current_accepts_same_bytes_from_a_newer_release(self):
        self.run_current()

    def test_current_rejects_pins_behind_the_latest_release(self):
        self.expected["files"]["geosite.dat"]["sha256"] = "c" * 64
        error = io.StringIO()
        with contextlib.redirect_stderr(error), self.assertRaises(SystemExit) as result:
            self.run_current()
        self.assertEqual(result.exception.code, 1)
        self.assertIn(
            "geosite.dat: the 2026-11-23 pins are behind the 2026-11-25 MetaCubeX "
            "release; dispatch the geo-bundle workflow first",
            error.getvalue(),
        )

    def test_manifest_and_check(self):
        self.fetch()
        self.assertEqual(
            json.loads(self.manifest.read_text(encoding="utf-8")), self.expected
        )
        update_geo.main(["--check", str(self.bundle)])

    def test_check_rejects_corrupted_byte_at_same_size(self):
        self.fetch()
        (self.bundle / "geosite.dat").write_bytes(b"X" + self.data["geosite.dat"][1:])
        self.check_failure("geosite.dat: SHA-256 mismatch", "--check")

    def test_check_rejects_wrong_size_even_when_digest_matches(self):
        self.fetch()
        self.expected["files"]["geoip.dat"]["size_bytes"] -= 1
        self.manifest.write_text(json.dumps(self.expected), encoding="utf-8")
        self.check_failure("geoip.dat: size mismatch", "--check")

    def test_check_rejects_oversize(self):
        self.fetch()
        self.expected["files"]["geoip.dat"]["size_bytes"] = 32 * 1024 * 1024 + 1
        self.manifest.write_text(json.dumps(self.expected), encoding="utf-8")
        self.check_failure("geoip.dat: size_bytes", "--check")

    def test_fetch_does_not_replace_bundle_when_second_file_fails(self):
        self.fetch()
        self.data["geoip.dat"] = b"new ip"
        ip = self.expected["files"]["geoip.dat"]
        ip["size_bytes"] = len(self.data["geoip.dat"])
        ip["sha256"] = hashlib.sha256(self.data["geoip.dat"]).hexdigest()
        self.pins.write_text(json.dumps(self.expected), encoding="utf-8")
        self.data["geosite.dat"] = b"X" + self.data["geosite.dat"][1:]
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.fetch()
        update_geo.main(["--check", str(self.bundle)])


if __name__ == "__main__":
    unittest.main()
