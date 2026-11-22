"""Regenerates src/rules/cn_ipv4.bin, the IPv4 table of the rule `GEOIP,CN`, from APNIC.

Usage: python -I tools/gen_cn_ipv4.py --date YYYYMMDD --work DIR
DIR is a new, empty directory outside the repository (on the desktop, one under /scratch/ssd).
The script downloads APNIC's archived delegation file of that day there,
delegated-apnic-YYYYMMDD.gz, and checks it against APNIC's MD5 file of the same day; nothing is
written into the repository but the table. An archived day, unlike delegated-apnic-latest, gives
the same table on every run, so a reviewer can run the script again and compare the bytes.

The table holds every `apnic|CN|ipv4|<start>|<count>|<date>|allocated` or `assigned` record as
the range [start, start + count - 1], merged where ranges overlap or touch, in ascending order:
each range is two big-endian u32, its first and its last address. src/rules/geoip.rs searches it
in place. Hong Kong, Macao and Taiwan have their own codes and are not in it.

Run it again with a newer --date at each release, and update the date and the counts that
geoip.rs and the rules tests record.
"""

import argparse
import gzip
import hashlib
import ipaddress
import re
import struct
import sys
import urllib.request
from pathlib import Path

ARCHIVE = "https://ftp.apnic.net/stats/apnic/{year}/delegated-apnic-{date}{suffix}"
CRATE = Path(__file__).resolve().parent.parent  # crates/doona-core
TABLE = CRATE / "src" / "rules" / "cn_ipv4.bin"
DATE = re.compile(r"^20\d{6}$")
MD5 = re.compile(r"^MD5 \(delegated-apnic-(\d{8})\) = ([0-9a-f]{32})$")


def fetch(date, work):
    """Downloads the day's file and its MD5 file into work; returns its text if the MD5 matches."""
    year = date[:4]
    files = {}
    for suffix in (".gz", ".md5.gz"):
        url = ARCHIVE.format(year=year, date=date, suffix=suffix)
        path = work / f"delegated-apnic-{date}{suffix}"
        with urllib.request.urlopen(url, timeout=60) as response:
            path.write_bytes(response.read())
        files[suffix] = gzip.decompress(path.read_bytes())
    match = MD5.match(files[".md5.gz"].decode("ascii").strip())
    if not match or match.group(1) != date:
        sys.exit(f"unexpected MD5 file: {files['.md5.gz'][:200]!r}")
    if hashlib.md5(files[".gz"]).hexdigest() != match.group(2):
        sys.exit("delegated-apnic: MD5 mismatch")
    return files[".gz"].decode("ascii")


def cn_ranges(text, date):
    """The merged [first, last] ranges of the CN IPv4 records of a delegation file of `date`."""
    lines = [line for line in text.splitlines() if line and not line.startswith("#")]
    version = lines[0].split("|")
    if version[0] != "2" or version[1] != "apnic" or version[2] != date:
        sys.exit(f"unexpected header: {lines[0]}")
    ranges = []
    for line in lines[1:]:
        fields = line.split("|")
        if len(fields) < 7 or fields[1] != "CN" or fields[2] != "ipv4":
            continue
        if fields[6] not in ("allocated", "assigned"):
            sys.exit(f"unexpected status: {line}")
        first = int(ipaddress.IPv4Address(fields[3]))
        count = int(fields[4])
        if count < 1 or first + count > 1 << 32:
            sys.exit(f"bad range: {line}")
        ranges.append((first, first + count - 1))
    if not ranges:
        sys.exit("no CN IPv4 records")
    ranges.sort()
    merged = [list(ranges[0])]
    for first, last in ranges[1:]:
        if first <= merged[-1][1] + 1:
            merged[-1][1] = max(merged[-1][1], last)
        else:
            merged.append([first, last])
    return len(ranges), merged


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--date", required=True, help="the archived day, YYYYMMDD")
    parser.add_argument(
        "--work", required=True, type=Path, help="a new, empty directory outside the repository"
    )
    args = parser.parse_args()
    if not DATE.match(args.date):
        sys.exit("--date: YYYYMMDD")
    work = args.work.resolve()
    if work.is_relative_to(CRATE.parent.parent):
        sys.exit("--work: outside the repository")
    work.mkdir(parents=True, exist_ok=True)
    if any(work.iterdir()):
        sys.exit("--work: not empty")
    records, merged = cn_ranges(fetch(args.date, work), args.date)
    TABLE.write_bytes(b"".join(struct.pack(">II", first, last) for first, last in merged))
    addresses = sum(last - first + 1 for first, last in merged)
    table = TABLE.relative_to(CRATE)
    print(f"{table}: {records} records, {len(merged)} ranges, {addresses} addresses")


if __name__ == "__main__":
    main()
