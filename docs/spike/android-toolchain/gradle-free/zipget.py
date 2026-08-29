#!/usr/bin/env python3
"""Extract named members from a remote zip using HTTP range requests.

Google's build-tools zip is 62 MB but the two files we need from it are 17 MB,
and the platform zip is 64 MB for a 26 MB android.jar. Rather than download the
whole archive to throw most of it away, read the zip's central directory off the
end of the file and then fetch only the members we want.

    zipget.py <url> <outdir> <suffix> [suffix...]

Suffixes match the end of the member path, e.g. "lib/d8.jar".
"""

import os
import struct
import sys
import urllib.request
import zlib

UA = {"User-Agent": "box-android-provision/1"}


def fetch(url, start=None, end=None):
    """GET url, optionally a byte range (inclusive)."""
    req = urllib.request.Request(url, headers=dict(UA))
    if start is not None:
        req.add_header("Range", f"bytes={start}-{'' if end is None else end}")
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def size_of(url):
    req = urllib.request.Request(url, method="HEAD", headers=dict(UA))
    with urllib.request.urlopen(req, timeout=60) as r:
        if r.headers.get("Accept-Ranges") != "bytes":
            raise SystemExit("server does not advertise range support")
        return int(r.headers["Content-Length"])


def central_directory(url, total):
    """Locate and return the parsed central directory entries."""
    tail_len = min(65_557, total)          # max EOCD + comment
    tail = fetch(url, total - tail_len)
    idx = tail.rfind(b"PK\x05\x06")
    if idx < 0:
        raise SystemExit("no end-of-central-directory record found")
    cd_size, cd_off = struct.unpack_from("<II", tail, idx + 12)
    if cd_off == 0xFFFFFFFF:
        raise SystemExit("zip64 archive - not handled")

    cd = fetch(url, cd_off, cd_off + cd_size - 1)
    entries, pos = [], 0
    while pos + 46 <= len(cd) and cd[pos:pos + 4] == b"PK\x01\x02":
        method, = struct.unpack_from("<H", cd, pos + 10)
        csize, usize = struct.unpack_from("<II", cd, pos + 20)
        nlen, elen, clen = struct.unpack_from("<HHH", cd, pos + 28)
        lho, = struct.unpack_from("<I", cd, pos + 42)
        name = cd[pos + 46: pos + 46 + nlen].decode("utf-8", "replace")
        entries.append((name, method, csize, usize, lho))
        pos += 46 + nlen + elen + clen
    return entries


def extract(url, outdir, suffixes):
    total = size_of(url)
    entries = central_directory(url, total)
    os.makedirs(outdir, exist_ok=True)

    wanted = [e for e in entries if any(e[0].endswith(s) for s in suffixes)]
    if not wanted:
        raise SystemExit(f"none of {suffixes} found in archive")

    got = 0
    for name, method, csize, usize, lho in wanted:
        # local header is 30 bytes + name + extra; lengths there can differ
        # from the central directory's, so read it rather than assume.
        head = fetch(url, lho, lho + 29)
        nlen, elen = struct.unpack_from("<HH", head, 26)
        start = lho + 30 + nlen + elen
        blob = fetch(url, start, start + csize - 1)
        data = zlib.decompress(blob, -15) if method == 8 else blob
        if len(data) != usize:
            raise SystemExit(f"{name}: size mismatch")

        dest = os.path.join(outdir, os.path.basename(name))
        with open(dest, "wb") as f:
            f.write(data)
        got += csize
        print(f"  {os.path.basename(name):24s} {usize // 1024:>7d} KB "
              f"(fetched {csize // 1024} KB)")

    print(f"  transferred {got / 1e6:.1f} MB instead of {total / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    extract(sys.argv[1], sys.argv[2], sys.argv[3:])
