#!/bin/sh
# doona-core keeps no data path of its own: honk reads and writes the TUN descriptor and runs the only netstack. Fails
# when crates/doona-core
#   - has a tun/ or tunnel/ module;
#   - does packet I/O on a descriptor: libc read/write/readv/writev/recv*, tokio's AsyncFd, or from_raw_fd (the JNI
#     side duplicates the borrowed TUN descriptor with BorrowedFd::borrow_raw(..).try_clone_to_owned() and hands it on);
#   - depends directly on a userspace netstack or TUN crate (honk's own dependency on honk-netstack is not doona's).
# Override the crate root with $1 (the negative control points it at a planted tree).
set -eu
root=$(cd "$(dirname "$0")/../../.." && pwd)
crate=${1:-$root/crates/doona-core}
fail=0

report() {
    printf 'check-no-netstack: %s\n%s\n' "$1" "$2" >&2
    fail=1
}

modules=$(find "$crate/src" \( -path "$crate/src/tun" -o -path "$crate/src/tunnel" -o -path "$crate/src/tun.rs" -o -path "$crate/src/tunnel.rs" \) -print)
[ -z "$modules" ] || report 'a tun or tunnel module' "$modules"

io=$(grep -rnE 'libc::(read|write|readv|writev|recv|recvfrom|recvmsg|recvmmsg)\b|\bAsyncFd\b|from_raw_fd' --include='*.rs' "$crate" || true)
[ -z "$io" ] || report 'packet I/O on a descriptor' "$io"

crates='smoltcp|lwip|ipstack|netstack-smoltcp|honk-netstack|tun2proxy|tun-rs|tun|tokio-tun|etherparse'
deps=$(grep -nE "^[[:space:]]*($crates)[[:space:]]*(=|\.)|^\[.*dependencies\.($crates)\]|package[[:space:]]*=[[:space:]]*\"($crates)\"" "$crate/Cargo.toml" || true)
[ -z "$deps" ] || report 'a netstack or TUN dependency' "$deps"

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-no-netstack: doona-core has no TUN I/O and no netstack; honk owns the data path"
