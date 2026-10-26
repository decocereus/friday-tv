import struct

import lz4.block
import numpy as np
import pytest

from converter import tex


def build(fmt: int, payload: bytes, width: int, height: int, *, flags: int = 0,
          texture=(None, None), image=(None, None), container=b"TEXB0002", compress=False) -> bytes:
    texture_w, texture_h = (texture[0] or width, texture[1] or height)
    image_w, image_h = (image[0] or width, image[1] or height)
    out = bytearray()
    out += b"TEXV0005\0" + b"TEXI0001\0"
    out += struct.pack("<iiiiiiI", fmt, flags, texture_w, texture_h, image_w, image_h, 0)
    out += container + b"\0"
    out += struct.pack("<i", 1)
    out += struct.pack("<i", 1)
    out += struct.pack("<ii", width, height)
    body = lz4.block.compress(payload, store_size=False) if compress else payload
    out += struct.pack("<ii", 1 if compress else 0, len(payload))
    out += struct.pack("<i", len(body)) + body
    return bytes(out)


def test_rgba8888_keeps_the_top_row_first():
    rows = np.zeros((2, 3, 4), dtype=np.uint8)
    rows[0] = [10, 20, 30, 40]
    rows[1] = [50, 60, 70, 80]
    decoded = tex.load(build(tex.RGBA8888, rows.tobytes(), 3, 2), "synthetic.tex")

    assert decoded.format_name == "rgba8888"
    assert decoded.pixels.shape == (2, 3, 4)
    assert list(decoded.pixels[0, 0]) == [10, 20, 30, 40]
    assert list(decoded.pixels[1, 2]) == [50, 60, 70, 80]


def test_r8_lands_in_red_with_opaque_alpha():
    plane = np.array([[1, 2], [3, 4]], dtype=np.uint8)
    decoded = tex.load(build(tex.R8, plane.tobytes(), 2, 2), "mask.tex")

    assert np.array_equal(decoded.pixels[..., 0], plane)
    assert not decoded.pixels[..., 1].any()
    assert not decoded.pixels[..., 2].any()
    assert (decoded.pixels[..., 3] == 255).all()


def test_rg88_lands_in_red_and_green():
    plane = np.array([[[1, 2], [3, 4]]], dtype=np.uint8)
    decoded = tex.load(build(tex.RG88, plane.tobytes(), 2, 1), "flow.tex")

    assert np.array_equal(decoded.pixels[..., :2], plane)
    assert not decoded.pixels[..., 2].any()
    assert (decoded.pixels[..., 3] == 255).all()


def test_lz4_mipmaps_are_decompressed():
    rows = np.arange(4 * 4 * 4, dtype=np.uint8).reshape(4, 4, 4)
    decoded = tex.load(build(tex.RGBA8888, rows.tobytes(), 4, 4, compress=True), "packed.tex")

    assert np.array_equal(decoded.pixels, rows)


def test_dxt1_decodes_a_flat_block():
    red = struct.pack("<H", 0xF800)
    block = red + red + bytes(4)
    decoded = tex.load(build(tex.DXT1, block, 4, 4), "flat.tex")

    assert list(decoded.pixels[0, 0]) == [255, 0, 0, 255]
    assert list(decoded.pixels[3, 3]) == [255, 0, 0, 255]


def test_dxt5_alpha_uses_the_endpoint_codebook():
    alpha = bytes([255, 0]) + bytes(6)
    colour = struct.pack("<HH", 0xFFFF, 0xFFFF) + bytes(4)
    decoded = tex.load(build(tex.DXT5, alpha + colour, 4, 4), "fade.tex")

    assert (decoded.pixels[..., 3] == 255).all()


def test_padding_is_kept_so_shaders_can_scale_texcoords():
    rows = np.zeros((8, 8, 4), dtype=np.uint8)
    decoded = tex.load(build(tex.RGBA8888, rows.tobytes(), 8, 8, image=(5, 3)), "padded.tex")

    assert (decoded.mip_width, decoded.mip_height) == (8, 8)
    assert (decoded.image_width, decoded.image_height) == (5, 3)


def test_embedded_png_payload_is_decoded(tmp_path):
    from io import BytesIO

    from PIL import Image

    buffer = BytesIO()
    Image.new("RGBA", (4, 2), (9, 8, 7, 6)).save(buffer, "PNG")
    raw = build(tex.RGBA8888, buffer.getvalue(), 4, 2, container=b"TEXB0003")
    # TEXB0003 carries a FreeImage format word before the images.
    raw = raw[: 9 + 9 + 28 + 9 + 4] + struct.pack("<i", 13) + raw[9 + 9 + 28 + 9 + 4 :]
    decoded = tex.load(raw, "embedded.tex")

    assert decoded.pixels.shape == (2, 4, 4)
    assert list(decoded.pixels[0, 0]) == [9, 8, 7, 6]


def test_bad_magic_names_the_file():
    with pytest.raises(tex.FormatError, match="broken.tex"):
        tex.load(b"NOPE\0" + bytes(64), "broken.tex")


def test_video_textures_are_called_out():
    raw = build(tex.RGBA8888, b"", 1, 1, flags=tex.FLAG_IS_VIDEO)
    with pytest.raises(tex.VideoTexture):
        tex.load(raw, "clip.tex")
