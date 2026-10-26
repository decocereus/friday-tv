# TEX container layout and DXT block decoding ported from RePKG (notscuffed/repkg), MIT licence.
from __future__ import annotations

import io
import struct
from dataclasses import dataclass, field
from pathlib import Path

import lz4.block
import numpy as np
from PIL import Image

from .pkg import FormatError

RGBA8888 = 0
DXT5 = 4
DXT3 = 6
DXT1 = 7
RG88 = 8
R8 = 9

FLAG_NO_INTERPOLATION = 1
FLAG_CLAMP_UVS = 2
FLAG_IS_GIF = 4
FLAG_IS_VIDEO = 32

_FORMAT_NAMES = {RGBA8888: "rgba8888", DXT5: "dxt5", DXT3: "dxt3", DXT1: "dxt1", RG88: "rg88", R8: "r8"}


class VideoTexture(FormatError):
    pass


@dataclass
class Tex:
    name: str
    format: int
    flags: int
    texture_width: int
    texture_height: int
    image_width: int
    image_height: int
    frame_count: int = 0
    pixels: np.ndarray | None = None
    embedded: bytes | None = None
    mip_width: int = 0
    mip_height: int = 0
    notes: list[str] = field(default_factory=list)

    @property
    def clamp(self) -> bool:
        return bool(self.flags & FLAG_CLAMP_UVS)

    @property
    def nearest(self) -> bool:
        return bool(self.flags & FLAG_NO_INTERPOLATION)

    @property
    def animated(self) -> bool:
        return bool(self.flags & FLAG_IS_GIF)

    @property
    def format_name(self) -> str:
        return _FORMAT_NAMES.get(self.format, str(self.format))


class _Reader:
    def __init__(self, data: bytes, name: str):
        self.data = data
        self.pos = 0
        self.name = name

    def take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise FormatError(f"{self.name}: truncated TEX at {self.pos}")
        out = self.data[self.pos : self.pos + n]
        self.pos += n
        return out

    def i32(self) -> int:
        return struct.unpack("<i", self.take(4))[0]

    def nstring(self, limit: int = 64) -> str:
        end = self.data.find(b"\0", self.pos)
        if end < 0 or end - self.pos > limit:
            raise FormatError(f"{self.name}: unterminated string at {self.pos}")
        out = self.data[self.pos : end].decode("utf-8", "replace")
        self.pos = end + 1
        return out


def load(data: bytes, name: str) -> Tex:
    r = _Reader(data, name)
    magic1 = r.nstring(16)
    if magic1 != "TEXV0005":
        raise FormatError(f"{name}: not a TEX (magic {magic1!r})")
    magic2 = r.nstring(16)
    if magic2 != "TEXI0001":
        raise FormatError(f"{name}: unexpected TEX header magic {magic2!r}")

    fmt = r.i32()
    tex = Tex(
        name=name,
        format=fmt,
        flags=r.i32(),
        texture_width=r.i32(),
        texture_height=r.i32(),
        image_width=r.i32(),
        image_height=r.i32(),
    )
    r.i32()

    container = r.nstring(16)
    image_count = r.i32()
    if image_count < 0 or image_count > 4096:
        raise FormatError(f"{name}: implausible image count {image_count}")
    image_format = -1
    is_video = False
    if container == "TEXB0003":
        image_format = r.i32()
    elif container == "TEXB0004":
        image_format = r.i32()
        is_video = r.i32() == 1
    elif container not in ("TEXB0001", "TEXB0002"):
        raise FormatError(f"{name}: unknown TEX image container {container!r}")

    version = int(container[4:])
    if version == 4 and not is_video:
        version = 3

    if tex.flags & FLAG_IS_VIDEO or is_video:
        raise VideoTexture(f"{name}: holds a video, which a render plan cannot play")

    mips = _read_image(r, version, image_format, name)
    tex.frame_count = image_count
    if image_count > 1:
        tex.notes.append(f"animated texture with {image_count} frames, used frame 0")

    width, height, payload = mips[0]
    tex.mip_width, tex.mip_height = width, height
    if image_format != -1:
        tex.embedded = payload
        tex.pixels = _decode_embedded(payload, name)
        tex.mip_height, tex.mip_width = tex.pixels.shape[0], tex.pixels.shape[1]
    else:
        tex.pixels = _decode_raw(fmt, payload, width, height, name)
    return tex


def _read_image(r: _Reader, version: int, image_format: int, name: str) -> list[tuple[int, int, bytes]]:
    mipmap_count = r.i32()
    if mipmap_count < 1 or mipmap_count > 32:
        raise FormatError(f"{name}: implausible mipmap count {mipmap_count}")
    mips = []
    for _ in range(mipmap_count):
        width = r.i32()
        height = r.i32()
        compressed = False
        decompressed_size = 0
        if version >= 2:
            compressed = r.i32() == 1
            decompressed_size = r.i32()
        size = r.i32()
        if size < 0:
            raise FormatError(f"{name}: bad mipmap byte count {size}")
        payload = r.take(size)
        if compressed:
            payload = lz4.block.decompress(payload, uncompressed_size=decompressed_size)
        mips.append((width, height, payload))
    return mips


def _decode_embedded(payload: bytes, name: str) -> np.ndarray:
    try:
        image = Image.open(io.BytesIO(payload))
        image.load()
    except Exception as exc:
        raise FormatError(f"{name}: cannot decode embedded image payload: {exc}") from exc
    return np.asarray(image.convert("RGBA"), dtype=np.uint8)


def _decode_raw(fmt: int, payload: bytes, width: int, height: int, name: str) -> np.ndarray:
    # Channel layout follows what an OpenGL R8/RG8 texture gives a sampler, because
    # assets/shaders/common_fragment.h ConvertTextureFormat reads R8 as .r and RG88 as .rg.
    if fmt == RGBA8888:
        need = width * height * 4
        if len(payload) < need:
            raise FormatError(f"{name}: RGBA payload is {len(payload)} bytes, need {need}")
        return np.frombuffer(payload[:need], dtype=np.uint8).reshape(height, width, 4).copy()
    if fmt == R8:
        need = width * height
        if len(payload) < need:
            raise FormatError(f"{name}: R8 payload is {len(payload)} bytes, need {need}")
        plane = np.frombuffer(payload[:need], dtype=np.uint8).reshape(height, width)
        out = np.zeros((height, width, 4), dtype=np.uint8)
        out[..., 0] = plane
        out[..., 3] = 255
        return out
    if fmt == RG88:
        need = width * height * 2
        if len(payload) < need:
            raise FormatError(f"{name}: RG88 payload is {len(payload)} bytes, need {need}")
        plane = np.frombuffer(payload[:need], dtype=np.uint8).reshape(height, width, 2)
        out = np.zeros((height, width, 4), dtype=np.uint8)
        out[..., :2] = plane
        out[..., 3] = 255
        return out
    if fmt in (DXT1, DXT3, DXT5):
        return _decode_dxt(fmt, payload, width, height, name)
    raise FormatError(f"{name}: unsupported TEX format {fmt}")


def _unpack565(words: np.ndarray) -> np.ndarray:
    red = (words >> 11) & 0x1F
    green = (words >> 5) & 0x3F
    blue = words & 0x1F
    out = np.empty(words.shape + (4,), dtype=np.uint8)
    out[..., 0] = (red << 3) | (red >> 2)
    out[..., 1] = (green << 2) | (green >> 4)
    out[..., 2] = (blue << 3) | (blue >> 2)
    out[..., 3] = 255
    return out


def _decode_dxt(fmt: int, payload: bytes, width: int, height: int, name: str) -> np.ndarray:
    block_bytes = 8 if fmt == DXT1 else 16
    blocks_x = (width + 3) // 4
    blocks_y = (height + 3) // 4
    need = blocks_x * blocks_y * block_bytes
    if len(payload) < need:
        raise FormatError(f"{name}: DXT payload is {len(payload)} bytes, need {need}")
    raw = np.frombuffer(payload[:need], dtype=np.uint8).reshape(blocks_y, blocks_x, block_bytes)
    colour = raw[..., block_bytes - 8 :]

    words = colour[..., 0].astype(np.uint16) | (colour[..., 1].astype(np.uint16) << 8)
    words1 = colour[..., 2].astype(np.uint16) | (colour[..., 3].astype(np.uint16) << 8)
    c0 = _unpack565(words).astype(np.int32)
    c1 = _unpack565(words1).astype(np.int32)

    palette = np.empty(c0.shape[:2] + (4, 4), dtype=np.uint8)
    palette[..., 0, :] = c0
    palette[..., 1, :] = c1
    three_colour = (fmt == DXT1) & (words <= words1)
    mid = ((c0 + c1) // 2).astype(np.uint8)
    two_thirds = ((2 * c0 + c1) // 3).astype(np.uint8)
    one_third = ((c0 + 2 * c1) // 3).astype(np.uint8)
    pick = three_colour[..., None]
    palette[..., 2, :] = np.where(pick, mid, two_thirds)
    palette[..., 3, :] = np.where(pick, 0, one_third)
    palette[..., 2, 3] = 255
    palette[..., 3, 3] = np.where(three_colour, 0, 255)

    packed = colour[..., 4:8]
    shifts = np.array([0, 2, 4, 6], dtype=np.uint8)
    indices = (packed[..., None] >> shifts) & 0x3
    indices = indices.reshape(blocks_y, blocks_x, 4, 4)

    flat = palette.reshape(-1, 4, 4)
    idx = indices.reshape(-1, 16)
    pixels = np.take_along_axis(flat, idx[:, :, None].astype(np.intp), axis=1)
    pixels = pixels.reshape(blocks_y, blocks_x, 4, 4, 4)

    if fmt == DXT5:
        pixels[..., 3] = _dxt5_alpha(raw)
    elif fmt == DXT3:
        pixels[..., 3] = _dxt3_alpha(raw)

    image = pixels.transpose(0, 2, 1, 3, 4).reshape(blocks_y * 4, blocks_x * 4, 4)
    return np.ascontiguousarray(image[:height, :width])


def _dxt3_alpha(raw: np.ndarray) -> np.ndarray:
    quant = raw[..., :8]
    lo = quant & 0x0F
    hi = quant >> 4
    pairs = np.stack([(lo << 4) | lo, (hi << 4) | hi], axis=-1)
    return pairs.reshape(raw.shape[0], raw.shape[1], 4, 4)


def _dxt5_alpha(raw: np.ndarray) -> np.ndarray:
    a0 = raw[..., 0].astype(np.int32)
    a1 = raw[..., 1].astype(np.int32)
    codes = np.empty(a0.shape + (8,), dtype=np.uint8)
    codes[..., 0] = a0
    codes[..., 1] = a1
    five = a0 <= a1
    for i in range(1, 5):
        codes[..., 1 + i] = np.where(five, ((5 - i) * a0 + i * a1) // 5, 0)
    for i in range(1, 7):
        seven = ((7 - i) * a0 + i * a1) // 7
        codes[..., i + 1] = np.where(five, codes[..., i + 1], seven)
    codes[..., 6] = np.where(five, 0, codes[..., 6])
    codes[..., 7] = np.where(five, 255, codes[..., 7])

    bits = raw[..., 2:8].astype(np.uint64)
    value = np.zeros(a0.shape, dtype=np.uint64)
    for j in range(6):
        value |= bits[..., j] << np.uint64(8 * j)
    shifts = (np.arange(16, dtype=np.uint64) * np.uint64(3))
    idx = ((value[..., None] >> shifts) & np.uint64(7)).astype(np.intp)

    flat_codes = codes.reshape(-1, 8)
    alpha = np.take_along_axis(flat_codes, idx.reshape(-1, 16), axis=1)
    return alpha.reshape(raw.shape[0], raw.shape[1], 4, 4)


def to_png(tex: Tex, path: Path) -> None:
    pixels = tex.pixels
    if pixels is None:
        raise FormatError(f"{tex.name}: no pixel data")
    Image.fromarray(pixels, "RGBA").save(path, optimize=True)
