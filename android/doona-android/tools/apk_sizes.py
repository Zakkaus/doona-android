#!/usr/bin/env python3
"""Print an APK's entries grouped by kind as a Markdown table: one row per native library and per group of the rest.

Usage: apk_sizes.py APK [BASE_APK]. With BASE_APK, a last column gives the stored-size change against it. The CI
release job appends the table to $GITHUB_STEP_SUMMARY, so growth shows on every run without failing it.
"""
import os
import sys
import zipfile


def group(name):
    if name.startswith("lib/"):
        return name
    if name.startswith("classes") and name.endswith(".dex"):
        return "dex (classes*.dex)"
    if name == "resources.arsc":
        return name
    if name.startswith("res/"):
        return "res/ fonts" if name.endswith((".ttf", ".otf")) else "res/ other"
    if name.startswith("assets/geo/"):
        return name
    if name.startswith("assets/licenses/"):
        return "assets/licenses/"
    if name.startswith("assets/"):
        return "assets/ other"
    if name.startswith("META-INF/"):
        return "META-INF/"
    return "other"


def sizes(path):
    out = {}
    with zipfile.ZipFile(path) as apk:
        for info in apk.infolist():
            count, stored, raw = out.get(group(info.filename), (0, 0, 0))
            out[group(info.filename)] = (count + 1, stored + info.compress_size, raw + info.file_size)
    return out


def main(argv):
    if len(argv) not in (2, 3):
        sys.exit(__doc__)
    head = sizes(argv[1])
    base = sizes(argv[2]) if len(argv) == 3 else None
    header = "| Group | Entries | Stored bytes | Uncompressed bytes |"
    rule = "|---|--:|--:|--:|"
    if base is not None:
        header += " Stored change |"
        rule += "--:|"
    print(header)
    print(rule)
    for name in sorted(set(head) | set(base or {}), key=lambda n: -head.get(n, (0, 0, 0))[1]):
        count, stored, raw = head.get(name, (0, 0, 0))
        row = f"| {name} | {count} | {stored:,} | {raw:,} |"
        if base is not None:
            row += f" {stored - base.get(name, (0, 0, 0))[1]:+,} |"
        print(row)
    print(f"\nAPK file: {os.path.getsize(argv[1]):,} bytes")


if __name__ == "__main__":
    main(sys.argv)
