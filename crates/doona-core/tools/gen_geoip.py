"""Regenerates the GeoIP data of rule mode: src/rules/cn_ipv4.bin from APNIC, for `GEOIP,CN`, and
src/rules/private.list from MetaCubeX, for `GEOIP,private`.

Usage: python -I tools/gen_geoip.py --date YYYYMMDD --private-rev SHA --work DIR
DIR is a new, empty directory outside the repository (on the desktop, one under /scratch/ssd).
The script downloads APNIC's archived delegation file of that day there,
delegated-apnic-YYYYMMDD.gz, and checks it against APNIC's MD5 file of the same day; and
MetaCubeX/meta-rules-dat's geo/geoip/private.list at the commit SHA (full 40 hex digits). Nothing
is written into the repository but the two files. An archived day and a commit, unlike
delegated-apnic-latest or a branch, give the same files on every run, so a reviewer can run the
script again and compare the bytes.

The CN table holds every `apnic|CN|ipv4|<start>|<count>|<date>|allocated` or `assigned` record
as the range [start, start + count - 1], merged where ranges overlap or touch, in ascending order:
each range is two big-endian u32, its first and its last address. src/rules/geoip.rs searches it
in place. Hong Kong, Macao and Taiwan have their own codes and are not in it.

private.list holds MetaCubeX's lines unchanged, IPv4 first, each a network with no host bits.
src/rules/geoip.rs keeps them as two constant tables, and a rules test holds the tables equal to
the file, so a change here fails the tests until geoip.rs follows.

Run it again with a newer --date and --private-rev at each release, and update the date, the
commit and the counts that geoip.rs and the rules tests record.
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
PRIVATE = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/{rev}/geo/geoip/private.list"
CRATE = Path(__file__).resolve().parent.parent  # crates/doona-core
TABLE = CRATE / "src" / "rules" / "cn_ipv4.bin"
PRIVATE_LIST = CRATE / "src" / "rules" / "private.list"
DATE = re.compile(r"^20\d{6}$")
REV = re.compile(r"^[0-9a-f]{40}$")
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


def private_lines(rev, work):
    """Downloads MetaCubeX's private list at commit rev into work; returns its lines, checked."""
    path = work / "private.list"
    with urllib.request.urlopen(PRIVATE.format(rev=rev), timeout=60) as response:
        path.write_bytes(response.read())
    lines = path.read_text("ascii").split()
    if not lines:
        sys.exit("private.list: empty")
    networks = []
    for line in lines:
        try:
            networks.append(ipaddress.ip_network(line, strict=True))
        except ValueError as error:
            sys.exit(f"private.list: {error}")
    versions = [n.version for n in networks]
    if versions != sorted(versions):
        sys.exit("private.list: IPv4 lines must come first")
    return lines


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
    parser.add_argument("--date", required=True, help="APNIC's archived day, YYYYMMDD")
    parser.add_argument(
        "--private-rev", required=True, help="a MetaCubeX/meta-rules-dat commit, 40 hex digits"
    )
    parser.add_argument(
        "--work", required=True, type=Path, help="a new, empty directory outside the repository"
    )
    args = parser.parse_args()
    if not DATE.match(args.date):
        sys.exit("--date: YYYYMMDD")
    if not REV.match(args.private_rev):
        sys.exit("--private-rev: a full commit id")
    work = args.work.resolve()
    if work.is_relative_to(CRATE.parent.parent):
        sys.exit("--work: outside the repository")
    work.mkdir(parents=True, exist_ok=True)
    if any(work.iterdir()):
        sys.exit("--work: not empty")
    records, merged = cn_ranges(fetch(args.date, work), args.date)
    private = private_lines(args.private_rev, work)
    TABLE.write_bytes(b"".join(struct.pack(">II", first, last) for first, last in merged))
    PRIVATE_LIST.write_text("".join(f"{line}\n" for line in private), "ascii")
    addresses = sum(last - first + 1 for first, last in merged)
    table = TABLE.relative_to(CRATE)
    print(f"{table}: {records} records, {len(merged)} ranges, {addresses} addresses")
    v4 = sum(1 for line in private if ":" not in line)
    print(f"{PRIVATE_LIST.relative_to(CRATE)}: {v4} IPv4 and {len(private) - v4} IPv6 networks")


if __name__ == "__main__":
    main()
