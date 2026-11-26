# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///

"""Fetch or verify a MetaCubeX geo bundle without third-party Python packages."""

import argparse
import hashlib
import json
import os
import re
from datetime import UTC, datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.request import Request, urlopen

PINS = Path(__file__).with_name("geo-pins.json")
MANIFEST = "geo-manifest.json"
FILES = ("geoip.dat", "geosite.dat")
MAX_BYTES = 32 * 1024 * 1024
UPSTREAM = "MetaCubeX/meta-rules-dat"
API = f"https://api.github.com/repos/{UPSTREAM}"


def get_json(endpoint):
    request = Request(
        f"{API}/{endpoint}", headers={"Accept": "application/vnd.github+json"}
    )
    if token := os.environ.get("GITHUB_TOKEN"):
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    with urlopen(request, timeout=60) as response:
        return json.load(response)


def latest_pins():
    release = get_json("releases/latest")
    # Upstream deletes the previous release assets and force-pushes the same bytes to release.
    # Use the snapshot's commit URL, but check its bytes against the release API's digests.
    commit = get_json("branches/release")["commit"]["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("invalid MetaCubeX release commit")
    published = datetime.fromisoformat(release["published_at"]).astimezone(UTC)
    assets = {asset["name"]: asset for asset in release["assets"]}
    files = {}
    for name in FILES:
        if (asset := assets.get(name)) is None:
            raise ValueError(f"{name}: missing from the MetaCubeX release")
        digest = asset["digest"]
        if not isinstance(digest, str) or not digest.startswith("sha256:"):
            raise ValueError(f"{name}: release has no published SHA-256")
        files[name] = {
            "source": f"https://raw.githubusercontent.com/{UPSTREAM}/{commit}/{name}",
            "version": commit,
            "size_bytes": asset["size"],
            "sha256": digest.removeprefix("sha256:"),
        }
    return {"version": 1, "release_date": published.date().isoformat(), "files": files}


def validate_manifest(manifest):
    if manifest["version"] != 1:
        raise ValueError("unsupported geo manifest version")
    for name in FILES:
        pin = manifest["files"][name]
        size = pin["size_bytes"]
        if type(size) is not int or not 0 < size <= MAX_BYTES:
            raise ValueError(f"{name}: size_bytes must be in 1..{MAX_BYTES}")
        if not re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]):
            raise ValueError(f"{name}: invalid SHA-256 pin")


def verify(name, pin, source, target=None):
    expected = pin["size_bytes"]
    remaining = expected
    digest = hashlib.sha256()
    while chunk := source.read(min(1024 * 1024, remaining + 1)):
        remaining -= len(chunk)
        if remaining < 0:
            raise ValueError(f"{name}: size mismatch (more than {expected} bytes)")
        digest.update(chunk)
        if target is not None:
            target.write(chunk)
    if remaining:
        raise ValueError(
            f"{name}: size mismatch (expected {expected}, got {expected - remaining})"
        )
    if digest.hexdigest() != pin["sha256"]:
        raise ValueError(f"{name}: SHA-256 mismatch")


def fetch(directory, manifest):
    directory.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=directory) as tmp:
        stage = Path(tmp)
        for name in FILES:
            pin = manifest["files"][name]
            with (
                urlopen(pin["source"], timeout=60) as source,
                (stage / name).open("wb") as target,
            ):
                verify(name, pin, source, target)
        (stage / MANIFEST).write_text(
            json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
        )
        for name in (*FILES, MANIFEST):
            (stage / name).replace(directory / name)


def check_current():
    pinned = json.loads(PINS.read_text(encoding="utf-8"))
    latest = latest_pins()
    # Compare the bytes: upstream may publish the same DAT again under a new commit.
    if behind := [
        name
        for name in FILES
        if pinned["files"][name]["sha256"] != latest["files"][name]["sha256"]
    ]:
        raise ValueError(
            f"{', '.join(behind)}: the {pinned['release_date']} pins are behind the "
            f"{latest['release_date']} MetaCubeX release; dispatch the geo-bundle "
            "workflow first, then tag a commit that has the new pins"
        )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="verify an existing bundle")
    mode.add_argument("--latest", action="store_true", help="stage the latest release")
    mode.add_argument(
        "--current",
        action="store_true",
        help="fail if the pins are behind the latest release; takes no directory",
    )
    parser.add_argument("directory", type=Path, nargs="?", help="geo bundle directory")
    args = parser.parse_args(argv)
    if args.current != (args.directory is None):
        parser.error("--current takes no directory; every other mode needs one")
    try:
        if args.current:
            check_current()
            return
        if args.latest:
            manifest = latest_pins()
        else:
            path = args.directory / MANIFEST if args.check else PINS
            manifest = json.loads(path.read_text(encoding="utf-8"))
        validate_manifest(manifest)
        if args.check:
            for name in FILES:
                with (args.directory / name).open("rb") as source:
                    verify(name, manifest["files"][name], source)
        else:
            fetch(args.directory, manifest)
    except (KeyError, OSError, TypeError, ValueError) as error:
        parser.exit(1, f"{parser.prog}: {error}\n")


if __name__ == "__main__":
    main()
