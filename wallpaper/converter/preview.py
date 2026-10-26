"""Reference renderer for plan.json on the Mac, to compare the TV renderer against."""

from __future__ import annotations

import datetime as dt
import json
import math
import re
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image

MAX_MAIN = (1920, 1080)
FPS = 30
BLEND_FUNCS = {
    "disabled": None,
    "normal": (moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA),
    "translucent": (moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA),
    "additive": (moderngl.SRC_ALPHA, moderngl.ONE),
    "multiply": (moderngl.DST_COLOR, moderngl.ZERO),
}
AUDIO = re.compile(r"g_AudioSpectrum(16|32|64)(Left|Right)")
_PRECISION = re.compile(r"^\s*precision\s+\w+\s+\w+\s*;\s*$", re.MULTILINE)


def reduction(canvas) -> int:
    """The TV halves until the canvas fits in 1920x1080, so the reference does the same."""
    factor = 1
    while canvas[0] / factor > MAX_MAIN[0] or canvas[1] / factor > MAX_MAIN[1]:
        factor *= 2
    return factor


def to_desktop(source: str) -> str:
    source = source.replace("#version 300 es", "#version 410 core", 1)
    return _PRECISION.sub("", source)


def pointer(time: float) -> tuple[float, float]:
    return 0.5 + 0.03 * math.sin(time * 0.13), 0.5 + 0.03 * math.sin(time * 0.11)


def daytime(now: dt.datetime | None = None) -> float:
    now = now or dt.datetime.now()
    return (now.hour * 3600 + now.minute * 60 + now.second) / 86400.0


def canvas_ortho(width: float, height: float) -> np.ndarray:
    """Canvas units (origin bottom-left, y up) to clip space with y flipped, so row 0 is the top."""
    return np.array(
        [
            [2.0 / width, 0.0, 0.0, -1.0],
            [0.0, -2.0 / height, 0.0, 1.0],
            [0.0, 0.0, 1.0, 0.0],
            [0.0, 0.0, 0.0, 1.0],
        ],
        dtype="f8",
    )


def model_matrix(origin, angle: float, scale) -> np.ndarray:
    cos, sin = math.cos(angle), math.sin(angle)
    return np.array(
        [
            [cos * scale[0], -sin * scale[1], 0.0, origin[0]],
            [sin * scale[0], cos * scale[1], 0.0, origin[1]],
            [0.0, 0.0, 1.0, 0.0],
            [0.0, 0.0, 0.0, 1.0],
        ],
        dtype="f8",
    )


class Renderer:
    def __init__(self, bundle: Path):
        self.bundle = bundle
        self.plan = json.loads((bundle / "plan.json").read_text())
        self.canvas = tuple(self.plan["canvas"])
        self.reduction = reduction(self.canvas)
        self.main_size = self._reduced(self.canvas)
        self.ctx = moderngl.create_standalone_context(require=410)
        self.ctx.disable(moderngl.DEPTH_TEST)
        self.ctx.disable(moderngl.CULL_FACE)

        self.main = self.ctx.texture(self.main_size, 4, dtype="f1")
        self.main_fbo = self.ctx.framebuffer(color_attachments=[self.main])
        self.fullframe = self.ctx.texture(self.main_size, 4, dtype="f1")
        self.fullframe_fbo = self.ctx.framebuffer(color_attachments=[self.fullframe])

        self.targets: dict[str, tuple[moderngl.Texture, moderngl.Framebuffer, tuple[int, int]]] = {}
        for rid, box in self.plan["targets"].items():
            size = self._reduced((box["width"], box["height"]))
            texture = self.ctx.texture(size, 4, dtype="f1")
            texture.repeat_x = texture.repeat_y = False
            self.targets[rid] = (texture, self.ctx.framebuffer(color_attachments=[texture]), size)

        self.textures: dict[str, tuple[moderngl.Texture, tuple[int, int, int, int]]] = {}
        for tid, info in self.plan["textures"].items():
            image = Image.open(bundle / info["file"]).convert("RGBA")
            if self.reduction > 1:
                image = image.resize(self._reduced(image.size), Image.LANCZOS)
            texture = self.ctx.texture(image.size, 4, image.tobytes(), dtype="f1")
            clamp = info.get("wrap", "clamp") == "clamp"
            texture.repeat_x = texture.repeat_y = not clamp
            if info.get("filter") == "nearest":
                texture.filter = (moderngl.NEAREST, moderngl.NEAREST)
            else:
                texture.filter = (moderngl.LINEAR, moderngl.LINEAR)
            uploaded = image.size
            ratio = uploaded[0] / info["width"], uploaded[1] / info["height"]
            self.textures[tid] = (
                texture,
                (
                    uploaded[0],
                    uploaded[1],
                    max(1, round(info["imageWidth"] * ratio[0])),
                    max(1, round(info["imageHeight"] * ratio[1])),
                ),
            )

        self.blank = self.ctx.texture((1, 1), 4, bytes(4), dtype="f1")
        self.programs = {}
        for pid, files in self.plan["programs"].items():
            self.programs[pid] = self.ctx.program(
                vertex_shader=to_desktop((bundle / files["vertex"]).read_text()),
                fragment_shader=to_desktop((bundle / files["fragment"]).read_text()),
            )
        self.buffer = self.ctx.buffer(reserve=6 * 5 * 4, dynamic=True)
        self.arrays: dict[str, moderngl.VertexArray] = {}
        self._cleared: set[str] = set()
        for fbo in [self.main_fbo, self.fullframe_fbo, *(f for _, f, _ in self.targets.values())]:
            fbo.clear(0.0, 0.0, 0.0, 0.0)

    def _reduced(self, size) -> tuple[int, int]:
        return max(1, round(size[0] / self.reduction)), max(1, round(size[1] / self.reduction))

    def release(self) -> None:
        self.ctx.release()

    def render(self, time: float) -> Image.Image:
        self._cleared = set()
        parallax = self._parallax(time)
        for draw in self.plan["draws"]:
            self._draw(draw, time, parallax)
        self._clear("main", self.main_fbo)

        data = self.main_fbo.read(components=4, dtype="f1")
        return Image.frombytes("RGBA", self.main_size, data)

    def _clear(self, key: str, fbo) -> None:
        if key in self._cleared:
            return
        self._cleared.add(key)
        if key == "main":
            fbo.clear(*(self.plan.get("clear") or [0, 0, 0, 1]))
        else:
            fbo.clear(0.0, 0.0, 0.0, 0.0)

    def _parallax(self, time: float) -> tuple[float, float]:
        """The TV smooths the drift frame by frame, so the reference replays it at a fixed 30 fps."""
        settings = self.plan.get("parallax") or {}
        if not settings.get("enabled"):
            return 0.0, 0.0
        amount = float(settings.get("amount", 0.0))
        delay = float(settings.get("delay", 0.0))
        smoothed = [0.0, 0.0]
        step = 1.0 / FPS
        blend = min(max(delay * step, 0.0), 1.0)
        elapsed = 0.0
        while elapsed < time - 1e-9:
            elapsed = min(elapsed + step, time)
            px, py = pointer(elapsed)
            smoothed[0] += (px - 0.5 - smoothed[0]) * blend
            smoothed[1] += (py - 0.5 - smoothed[1]) * blend
        return smoothed[0] * amount, smoothed[1] * amount

    def _draw(self, draw: dict, time: float, parallax: tuple[float, float]) -> None:
        program = self.programs[draw["program"]]
        if "fullframe" in draw["textures"]:
            self.ctx.copy_framebuffer(self.fullframe_fbo, self.main_fbo)

        target = draw["target"]
        if target == "main":
            fbo, size = self.main_fbo, self.main_size
        else:
            _, fbo, size = self.targets[target]
        self._clear(target, fbo)
        fbo.use()
        self.ctx.viewport = (0, 0, size[0], size[1])

        blend = BLEND_FUNCS.get(draw.get("blend", "disabled"))
        if blend is None:
            self.ctx.disable(moderngl.BLEND)
        else:
            self.ctx.enable(moderngl.BLEND)
            self.ctx.blend_func = blend

        geometry = draw["geometry"]
        vertices, mvp, model = self._geometry(geometry, parallax)
        self._bind_textures(program, draw["textures"])
        self._set_builtins(program, time, mvp, model)
        for name, value in (draw.get("uniforms") or {}).items():
            self._set(program, name, value)

        self.buffer.orphan(vertices.nbytes)
        self.buffer.write(vertices)
        array = self.arrays.get(draw["program"])
        if array is None:
            attributes = [name for name in ("a_Position", "a_TexCoord") if name in program]
            layout = "3f 2f" if len(attributes) == 2 else ("3f 2x4" if attributes == ["a_Position"] else "3x4 2f")
            array = self.ctx.vertex_array(program, [(self.buffer, layout, *attributes)])
            self.arrays[draw["program"]] = array
        array.render(moderngl.TRIANGLES, vertices=6)

    def _geometry(self, geometry: dict, parallax):
        scale_uv = geometry.get("texCoordScale") or [1.0, 1.0]
        uv = np.array([[0, 0], [1, 0], [0, 1], [0, 1], [1, 0], [1, 1]], dtype="f8")
        uv = uv * np.array(scale_uv, dtype="f8")
        if geometry["kind"] == "fullscreen":
            corners = np.array(
                [[-1, -1, 0], [1, -1, 0], [-1, 1, 0], [-1, 1, 0], [1, -1, 0], [1, 1, 0]], dtype="f8"
            )
            mvp = np.identity(4)
            model = np.identity(4)
        else:
            size = geometry["size"]
            half = (size[0] / 2.0, size[1] / 2.0)
            corners = np.array(
                [
                    [-half[0], half[1], 0],
                    [half[0], half[1], 0],
                    [-half[0], -half[1], 0],
                    [-half[0], -half[1], 0],
                    [half[0], half[1], 0],
                    [half[0], -half[1], 0],
                ],
                dtype="f8",
            )
            depth = geometry.get("parallaxDepth") or [0.0, 0.0]
            shift = (
                parallax[0] * depth[0] * self.canvas[0],
                parallax[1] * depth[1] * self.canvas[1],
            )
            origin = (geometry["origin"][0] + shift[0], geometry["origin"][1] + shift[1])
            model = model_matrix(origin, float(geometry.get("angle", 0.0)), geometry.get("scale") or [1, 1])
            mvp = canvas_ortho(self.canvas[0], self.canvas[1]) @ model
        vertices = np.hstack([corners, uv]).astype("f4")
        return np.ascontiguousarray(vertices), mvp, model

    def _bind_textures(self, program, references) -> None:
        for index in range(8):
            name = f"g_Texture{index}"
            if name not in program:
                continue
            reference = references[index] if index < len(references) else None
            texture, resolution = self._texture(reference)
            texture.use(index)
            program[name].value = index
            self._set(program, f"{name}Resolution", list(resolution))
            self._set(program, f"{name}Rotation", [1.0, 0.0, 0.0, 1.0])
            self._set(program, f"{name}Translation", [0.0, 0.0])
            self._set(program, f"{name}Texel", [1.0 / resolution[0], 1.0 / resolution[1]])

    def _texture(self, reference):
        if reference is None:
            return self.blank, (1, 1, 1, 1)
        if reference == "fullframe":
            return self.fullframe, (*self.main_size, *self.main_size)
        if reference.startswith("target:"):
            texture, _, size = self.targets[_rid(reference)]
            return texture, (*size, *size)
        return self.textures[reference]

    def _set_builtins(self, program, time: float, mvp, model) -> None:
        px, py = pointer(time)
        view = canvas_ortho(self.canvas[0], self.canvas[1])
        self._set(program, "g_Time", [time])
        self._set(program, "g_Daytime", [daytime()])
        self._set_matrix(program, "g_ModelViewProjectionMatrix", mvp)
        self._set_matrix(program, "g_EffectModelViewProjectionMatrix", mvp)
        self._set_matrix(program, "g_ModelViewProjectionMatrixInverse", np.linalg.inv(mvp))
        self._set_matrix(program, "g_ModelMatrix", model)
        self._set_matrix(program, "g_EffectModelMatrix", model)
        self._set_matrix(program, "g_ViewProjectionMatrix", view)
        self._set(program, "g_Screen", [self.main_size[0], self.main_size[1], self.main_size[0] / self.main_size[1]])
        self._set(program, "g_PointerPosition", [px, py])
        lx, ly = pointer(max(0.0, time - 1.0 / FPS))
        self._set(program, "g_PointerPositionLast", [lx, ly])
        self._set(program, "g_ParallaxPosition", [px - 0.5, py - 0.5])
        self._set(program, "g_TexelSize", [1.0 / self.main_size[0], 1.0 / self.main_size[1]])
        self._set(program, "g_TexelSizeHalf", [0.5 / self.main_size[0], 0.5 / self.main_size[1]])
        for name in program:
            match = AUDIO.fullmatch(name)
            if match:
                self._set(program, name, [0.0] * int(match.group(1)))

    def _set_matrix(self, program, name: str, matrix) -> None:
        if name not in program:
            return
        program[name].write(np.ascontiguousarray(np.asarray(matrix, dtype="f4").T).tobytes())

    def _set(self, program, name: str, values) -> None:
        if name not in program:
            return
        member = program[name]
        dtype = "i4" if str(member.fmt).endswith("i") else "f4"
        data = np.asarray(values, dtype=dtype)
        want = member.array_length * member.dimension
        if data.size < want:
            data = np.resize(data, want)
        member.write(np.ascontiguousarray(data[:want]).tobytes())


def _rid(reference: str) -> str:
    return reference.partition(":")[2] or reference


def render(bundle: Path, time: float, out: Path) -> Path:
    renderer = Renderer(bundle)
    try:
        image = renderer.render(time)
    finally:
        renderer.release()
    out.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGB").save(out)
    return out
