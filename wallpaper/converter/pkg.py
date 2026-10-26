# PKG container layout ported from RePKG (notscuffed/repkg), MIT licence.
from __future__ import annotations

import struct
from pathlib import Path


class FormatError(Exception):
    pass


class Package:
    def __init__(self, path: Path, entries: dict[str, bytes]):
        self.path = path
        self.entries = entries

    def __contains__(self, name: str) -> bool:
        return self._key(name) is not None

    def read(self, name: str) -> bytes:
        key = self._key(name)
        if key is None:
            raise FormatError(f"{self.path}: no entry {name!r}")
        return self.entries[key]

    def names(self) -> list[str]:
        return sorted(self.entries)

    def _key(self, name: str) -> str | None:
        name = name.replace("\\", "/").lstrip("/")
        if name in self.entries:
            return name
        lowered = name.lower()
        for key in self.entries:
            if key.lower() == lowered:
                return key
        return None


def _u32(data: bytes, pos: int, path: Path) -> tuple[int, int]:
    if pos + 4 > len(data):
        raise FormatError(f"{path}: truncated at {pos}")
    return struct.unpack_from("<i", data, pos)[0], pos + 4


def _string(data: bytes, pos: int, path: Path) -> tuple[str, int]:
    size, pos = _u32(data, pos, path)
    if size < 0 or pos + size > len(data):
        raise FormatError(f"{path}: bad string length {size} at {pos}")
    return data[pos : pos + size].decode("utf-8", "replace"), pos + size


def load(path: Path) -> Package:
    data = path.read_bytes()
    magic, pos = _string(data, 0, path)
    if not magic.startswith("PKGV"):
        raise FormatError(f"{path}: not a PKG (magic {magic!r})")
    count, pos = _u32(data, pos, path)
    if count < 0 or count > 1 << 20:
        raise FormatError(f"{path}: implausible entry count {count}")
    table = []
    for _ in range(count):
        name, pos = _string(data, pos, path)
        offset, pos = _u32(data, pos, path)
        length, pos = _u32(data, pos, path)
        table.append((name, offset, length))
    data_start = pos
    entries: dict[str, bytes] = {}
    for name, offset, length in table:
        start = data_start + offset
        end = start + length
        if offset < 0 or length < 0 or end > len(data):
            raise FormatError(f"{path}: entry {name!r} out of range")
        entries[name.replace("\\", "/").lstrip("/")] = data[start:end]
    return Package(path, entries)


def write(path: Path, entries: dict[str, bytes], magic: str = "PKGV0001") -> None:
    header = bytearray()
    header += struct.pack("<i", len(magic)) + magic.encode()
    header += struct.pack("<i", len(entries))
    body = bytearray()
    for name, payload in entries.items():
        raw = name.encode("utf-8")
        header += struct.pack("<i", len(raw)) + raw
        header += struct.pack("<ii", len(body), len(payload))
        body += payload
    path.write_bytes(bytes(header) + bytes(body))
