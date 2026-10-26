from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import shaders, tex
from .locator import Locator, MissingAsset
from .pkg import FormatError

RT_FULLFRAME = ("_rt_fullframebuffer", "_rt_mipmappedframebuffer")
BLEND = {"normal": "disabled", "translucent": "translucent", "additive": "additive", "disabled": "disabled"}
STATIC_UNIFORMS = (
    "g_Alpha", "g_UserAlpha", "g_Brightness", "g_Color", "g_Color4", "g_CompositeColor",
    "g_LightAmbientColor", "g_LightSkylightColor", "g_TextureReductionScale", "g_NormalModelMatrix",
    "g_EffectTextureProjectionMatrix", "g_EffectTextureProjectionMatrixInverse", "g_Texture3MipMapInfo",
    "g_EyePosition", "g_AltModelMatrix", "g_AltViewProjectionMatrix", "g_AltNormalModelMatrix",
)
BUILTIN_UNIFORMS = {
    "g_Time", "g_ModelViewProjectionMatrix", "g_ModelMatrix", "g_ViewProjectionMatrix", "g_Screen",
    "g_PointerPosition", "g_PointerPositionLast", "g_ParallaxPosition", "g_TexelSize", "g_TexelSizeHalf",
    "g_Daytime", "g_Texture0Rotation", "g_Texture0Translation",
}


@dataclass
class Entry:
    object_id: int
    name: str
    state: str
    reason: str = ""
    draws: int = 0
    notes: list[str] = field(default_factory=list)


@dataclass
class MaterialPass:
    shader: str
    blending: str
    textures: dict[int, str]
    usertextures: dict[int, str]
    combos: dict[str, int]
    constants: dict[str, object]


@dataclass
class PassPlan:
    material: MaterialPass
    override: dict
    binds: dict[int, str]
    target: str | None
    effect_dir: str | None
    label: str
    fbos: dict[str, float] = field(default_factory=dict)
    effect_key: str = ""


def _vec(raw: object, count: int, fallback: tuple[float, ...]) -> list[float]:
    if isinstance(raw, str):
        parts = raw.replace(",", " ").split()
        try:
            out = [float(p) for p in parts]
        except ValueError:
            out = list(fallback)
    elif isinstance(raw, (list, tuple)):
        out = [float(p) for p in raw]
    elif isinstance(raw, (int, float)):
        out = [float(raw)] * count
    else:
        out = list(fallback)
    if len(out) < count:
        out = out + list(fallback[len(out) :])
    return out[:count]


def _texture_map(raw: object) -> dict[int, str]:
    out: dict[int, str] = {}
    if not isinstance(raw, list):
        return out
    for index, item in enumerate(raw):
        if isinstance(item, str) and item:
            out[index] = item
        elif isinstance(item, dict) and isinstance(item.get("name"), str):
            out[index] = item["name"]
    return out


def _combo_map(raw: object) -> dict[str, int]:
    if not isinstance(raw, dict):
        return {}
    out = {}
    for key, value in raw.items():
        if isinstance(value, (int, float)) and float(value).is_integer():
            out[key] = int(value)
    return out


def _material_pass(raw: dict) -> MaterialPass:
    return MaterialPass(
        shader=raw["shader"],
        blending=str(raw.get("blending", "normal")).lower(),
        textures=_texture_map(raw.get("textures")),
        usertextures=_texture_map(raw.get("usertextures")),
        combos=_combo_map(raw.get("combos")),
        constants=raw.get("constantshadervalues") if isinstance(raw.get("constantshadervalues"), dict) else {},
    )


class Builder:
    def __init__(self, locator: Locator, out: Path, properties: dict):
        self.locator = locator
        self.out = out
        self.properties = properties
        self.textures: dict[str, dict] = {}
        self.texture_by_handle: dict[str, str] = {}
        self.texture_format: dict[str, int] = {}
        self.programs: dict[str, dict] = {}
        self.program_by_key: dict[tuple, str] = {}
        self.targets: dict[str, dict] = {}
        self.draws: list[dict] = []
        self.entries: list[Entry] = []
        self.shader_failures: list[dict] = []
        self.missing: list[str] = []
        self.unsupported_uniforms: set[str] = set()
        self._program_source: dict[str, str] = {}
        self.unwritten_targets: set[str] = set()

    # -- scene ---------------------------------------------------------------

    def build(self, scene: dict) -> dict:
        general = scene.get("general") or {}
        projection = general.get("orthogonalprojection") or {}
        width = int(projection.get("width") or 0)
        height = int(projection.get("height") or 0)
        objects = scene.get("objects") or []
        if width <= 0 or height <= 0:
            width, height = self._auto_canvas(objects)
        self.canvas = (width, height)
        self.ambient = _vec(general.get("ambientcolor"), 3, (0.3, 0.3, 0.3))
        self.skylight = _vec(general.get("skylightcolor"), 3, (0.3, 0.3, 0.3))

        by_id = {obj.get("id"): obj for obj in objects if isinstance(obj.get("id"), int)}
        for obj in objects:
            try:
                self._object(obj, by_id)
            except tex.VideoTexture as exc:
                self.entries.append(
                    Entry(obj.get("id", -1), str(obj.get("name", "")), "skipped", f"video-texture: {exc}")
                )
            except (FormatError, MissingAsset, KeyError, ValueError) as exc:
                self.entries.append(
                    Entry(
                        object_id=obj.get("id", -1),
                        name=str(obj.get("name", "")),
                        state="skipped",
                        reason=f"{type(exc).__name__}: {exc}",
                    )
                )

        clear = _vec(general.get("clearcolor"), 3, (0.0, 0.0, 0.0))
        parallax = {
            "enabled": bool(self._user(general.get("cameraparallax"), False)),
            "amount": float(self._user(general.get("cameraparallaxamount"), 0.1) or 0.1),
            "delay": float(self._user(general.get("cameraparallaxdelay"), 0.1) or 0.1),
            "mouseInfluence": float(self._user(general.get("cameraparallaxmouseinfluence"), 0.0) or 0.0),
        }
        self._prune_targets()
        return {
            "version": 1,
            "canvas": [self.canvas[0], self.canvas[1]],
            "clear": clear + [1.0],
            "parallax": parallax,
            "textures": self.textures,
            "programs": self.programs,
            "targets": self.targets,
            "draws": self.draws,
        }

    def _prune_targets(self) -> None:
        used = set()
        for draw in self.draws:
            used.add(draw["target"])
            used.update(_rid(ref) for ref in draw["textures"] if isinstance(ref, str) and ref.startswith("target:"))
        self.targets = {rid: box for rid, box in self.targets.items() if rid in used}

    def report(self) -> dict:
        return {
            "objects": [
                {
                    "id": e.object_id,
                    "name": e.name,
                    "state": e.state,
                    **({"reason": e.reason} if e.reason else {}),
                    **({"draws": e.draws} if e.draws else {}),
                    **({"notes": e.notes} if e.notes else {}),
                }
                for e in self.entries
            ],
            "shaderFailures": self.shader_failures,
            "missingAssets": sorted(set(self.missing)),
            "unsupportedUniforms": sorted(self.unsupported_uniforms),
            "unwrittenTargets": sorted(self.unwritten_targets),
        }

    def _auto_canvas(self, objects: list[dict]) -> tuple[int, int]:
        extent = [0.0, 0.0]
        for obj in objects:
            if not obj.get("image"):
                continue
            origin = _vec(obj.get("origin"), 3, (0.0, 0.0, 0.0))
            size = _vec(obj.get("size"), 2, (0.0, 0.0))
            extent[0] = max(extent[0], abs(origin[0]) + size[0] / 2)
            extent[1] = max(extent[1], abs(origin[1]) + size[1] / 2)
        if extent[0] <= 0 or extent[1] <= 0:
            return 1920, 1080
        return int(round(extent[0] * 2)), int(round(extent[1] * 2))

    def _user(self, raw: object, fallback: object) -> object:
        if not isinstance(raw, dict):
            return fallback if raw is None else raw
        value = raw.get("value", fallback)
        user = raw.get("user")
        if isinstance(user, str):
            prop = self.properties.get(user)
            return prop.get("value", value) if isinstance(prop, dict) else value
        if isinstance(user, dict):
            prop = self.properties.get(user.get("name", ""))
            if not isinstance(prop, dict) or "value" not in prop:
                return value
            return _same(prop["value"], user.get("condition"))
        return value

    # -- objects -------------------------------------------------------------

    def _object(self, obj: dict, by_id: dict) -> None:
        name = str(obj.get("name", ""))
        oid = obj.get("id", -1)
        for key, reason in (("particle", "particles"), ("text", "text"), ("sound", "sound"), ("model", "3d-model")):
            if obj.get(key):
                self.entries.append(Entry(oid, name, "skipped", reason))
                return
        if not obj.get("image"):
            self.entries.append(Entry(oid, name, "skipped", "no image"))
            return
        if not self._user(obj.get("visible"), True):
            self.entries.append(Entry(oid, name, "skipped", "invisible"))
            return

        model = self.locator.read_json(obj["image"])
        material = self.locator.read_json(model["material"])
        material_passes = [_material_pass(p) for p in material.get("passes") or []]
        if not material_passes:
            self.entries.append(Entry(oid, name, "skipped", "material has no passes"))
            return

        notes: list[str] = []
        if model.get("puppet"):
            notes.append("puppet warp ignored, drawn as a flat quad")

        base_ref, base_format, base_size = self._base_texture(material_passes[0])
        origin, scale, angle = self._transform(obj, by_id)
        size = self._size(obj, model, base_size)
        if model.get("fullscreen"):
            size = (float(self.canvas[0]), float(self.canvas[1]))
            origin = (self.canvas[0] / 2.0, self.canvas[1] / 2.0)
        if size[0] <= 0 or size[1] <= 0:
            self.entries.append(Entry(oid, name, "skipped", "zero size"))
            return
        origin = self._align(obj, origin, size, scale)

        passes = self._pass_list(obj, material_passes, notes)
        if len(passes) > 1:
            passes[-1].material.blending = passes[0].material.blending
            passes[0].material.blending = "normal"

        if model.get("passthrough") and not any(p.effect_dir for p in passes):
            self.entries.append(Entry(oid, name, "skipped", "passthrough without effects", notes=notes))
            return

        entry = Entry(oid, name, "rendered", notes=notes)
        self.entries.append(entry)
        self._emit_passes(obj, model, passes, entry, base_ref, base_format, base_size, origin, size, scale, angle)

    def _base_texture(self, first: MaterialPass) -> tuple[str | None, int, tuple[float, float] | None]:
        if not first.textures:
            return None, 0, None
        name = first.textures[min(first.textures)]
        return self._source(name, None)

    def _transform(self, obj: dict, by_id: dict) -> tuple[tuple[float, float], tuple[float, float], float]:
        chain = []
        current = obj
        for _ in range(32):
            chain.append(current)
            parent = current.get("parent")
            if not isinstance(parent, int) or parent not in by_id or by_id[parent] is current:
                break
            current = by_id[parent]
        origin, scale, angle = self._local(chain[-1])
        for node in reversed(chain[:-1]):
            local_origin, local_scale, local_angle = self._local(node)
            dx = local_origin[0] * scale[0]
            dy = local_origin[1] * scale[1]
            cos, sin = math.cos(angle), math.sin(angle)
            origin = (origin[0] + dx * cos - dy * sin, origin[1] + dx * sin + dy * cos)
            scale = (local_scale[0] * scale[0], local_scale[1] * scale[1])
            angle = local_angle + angle
        return origin, scale, angle

    def _local(self, obj: dict) -> tuple[tuple[float, float], tuple[float, float], float]:
        origin = _vec(self._user(obj.get("origin"), None), 3, (0.0, 0.0, 0.0))
        scale = _vec(self._user(obj.get("scale"), None), 3, (1.0, 1.0, 1.0))
        angles = _vec(self._user(obj.get("angles"), None), 3, (0.0, 0.0, 0.0))
        return (origin[0], origin[1]), (scale[0], scale[1]), angles[2]

    def _size(self, obj: dict, model: dict, base_size: tuple[float, float] | None) -> tuple[float, float]:
        size = _vec(self._user(obj.get("size"), None), 2, (0.0, 0.0))
        if (size[0] == 0 or size[1] == 0) and base_size is None and model.get("solidlayer"):
            return float(self.canvas[0]), float(self.canvas[1])
        if (size[0] == 0 or size[1] == 0) and base_size is not None:
            return base_size
        if (size[0] == 0 or size[1] == 0) and model.get("width") and model.get("height"):
            return float(model["width"]), float(model["height"])
        return size[0], size[1]

    def _align(self, obj: dict, origin, size, scale) -> tuple[float, float]:
        alignment = str(obj.get("horizontalalign") or obj.get("alignment") or "center")
        x, y = origin
        half_w = size[0] * scale[0] / 2.0
        half_h = size[1] * scale[1] / 2.0
        if "top" in alignment:
            y -= half_h
        elif "bottom" in alignment:
            y += half_h
        if "left" in alignment:
            x += half_w
        elif "right" in alignment:
            x -= half_w
        return x, y

    def _pass_list(self, obj: dict, material_passes: list[MaterialPass], notes: list[str]) -> list[PassPlan]:
        passes = [
            PassPlan(material=mp, override={}, binds={}, target=None, effect_dir=None, label="material")
            for mp in material_passes
        ]
        for index, effect in enumerate(obj.get("effects") or []):
            if not isinstance(effect, dict) or not effect.get("file"):
                continue
            if not self._user(effect.get("visible"), True):
                notes.append(f"effect {effect['file']} hidden")
                continue
            try:
                passes += self._effect_passes(effect, notes, index)
            except (MissingAsset, FormatError, KeyError) as exc:
                notes.append(f"effect {effect['file']} skipped: {exc}")
        mode = self._user(obj.get("colorBlendMode"), 0)
        if isinstance(mode, (int, float)) and int(mode) > 0:
            blend = self.locator.read_json("materials/util/effectpassthrough.json")
            passes.append(
                PassPlan(
                    material=_material_pass((blend["passes"])[0]),
                    override={"combos": {"BLENDMODE": int(mode)}},
                    binds={},
                    target=None,
                    effect_dir=None,
                    label="colorBlendMode",
                )
            )
        return passes

    def _effect_passes(self, effect: dict, notes: list[str], index: int) -> list[PassPlan]:
        file = effect["file"]
        effect_dir = _effect_dir(file)
        data = self.locator.read_json(file, effect_dir)
        fbos: dict[str, float] = {}
        for fbo in data.get("fbos") or []:
            fbos[str(fbo["name"]).lower()] = float(fbo.get("scale", 1.0) or 1.0)
        overrides = [o for o in effect.get("passes") or [] if isinstance(o, dict)]
        out: list[PassPlan] = []
        slot = 0
        label = re.sub(r"[^a-z0-9]+", "-", file.lower()).strip("-")
        for entry in data.get("passes") or []:
            binds = {}
            for bind in entry.get("bind") or []:
                if isinstance(bind, dict) and "index" in bind and "name" in bind:
                    binds[int(bind["index"])] = str(bind["name"])
            target = entry.get("target")
            if not entry.get("material"):
                if entry.get("command") != "copy" or not entry.get("source") or not target:
                    notes.append(f"effect pass in {file} skipped: unsupported command")
                    continue
                out.append(
                    PassPlan(
                        material=MaterialPass("passthrough", "normal", {0: str(entry["source"])}, {}, {}, {}),
                        override={},
                        binds=binds,
                        target=str(target),
                        effect_dir=effect_dir,
                        label=label,
                    )
                )
                continue
            emat = self.locator.read_json(entry["material"], effect_dir)
            override = overrides[slot] if slot < len(overrides) else {}
            for raw in emat.get("passes") or []:
                out.append(
                    PassPlan(
                        material=_material_pass(raw),
                        override=override,
                        binds=binds,
                        target=str(target) if target else None,
                        effect_dir=effect_dir,
                        label=label,
                    )
                )
            if slot < len(overrides):
                slot += 1
        for plan in out:
            plan.fbos = fbos
            plan.effect_key = f"{index}:{effect_dir or file}"
        return out

    # -- passes --------------------------------------------------------------

    def _emit_passes(
        self, obj, model, passes, entry, base_ref, base_format, base_size, origin, size, scale, angle
    ) -> None:
        oid = obj.get("id", -1)
        width = max(1, int(round(size[0])))
        height = max(1, int(round(size[1])))
        ping = self._target(f"o{oid}a", width, height)
        pong = self._target(f"o{oid}b", width, height)
        fbo_ids: dict[tuple[str, str], str] = {}

        alpha = float(self._user(obj.get("alpha"), 1.0) or 0.0)
        colour = _vec(self._user(obj.get("color"), None), 3, (1.0, 1.0, 1.0))
        brightness = float(self._user(obj.get("brightness"), 1.0) or 0.0)
        statics = {
            "g_Alpha": [alpha],
            "g_UserAlpha": [alpha],
            "g_Brightness": [brightness],
            "g_Color": colour,
            "g_Color4": colour + [1.0],
            "g_CompositeColor": colour,
            "g_LightAmbientColor": self.ambient,
            "g_LightSkylightColor": self.skylight,
            "g_TextureReductionScale": [1.0],
            "g_NormalModelMatrix": [1, 0, 0, 0, 1, 0, 0, 0, 1],
            "g_EffectTextureProjectionMatrix": _identity4(),
            "g_EffectTextureProjectionMatrixInverse": _identity4(),
            "g_Texture3MipMapInfo": [0.0],
            "g_EyePosition": [self.canvas[0] / 2.0, self.canvas[1] / 2.0, 0.0],
            "g_AltModelMatrix": _identity4(),
            "g_AltViewProjectionMatrix": _identity4(),
            "g_AltNormalModelMatrix": [1, 0, 0, 0, 1, 0, 0, 0, 1],
        }

        parallax_depth = _vec(self._user(obj.get("parallaxDepth"), None), 2, (0.0, 0.0))
        quad = {
            "kind": "quad",
            "origin": [origin[0], origin[1]],
            "size": [size[0], size[1]],
            "scale": [scale[0], scale[1]],
            "angle": angle,
            "parallaxDepth": parallax_depth,
        }
        tex_scale = [1.0, 1.0]
        if base_ref is not None and base_size is not None and base_ref in self.textures:
            info = self.textures[base_ref]
            tex_scale = [info["imageWidth"] / info["width"], info["imageHeight"] / info["height"]]

        draw_to = ping
        as_input = base_ref
        current_main, current_sub = ping, pong
        effect_input: str | None = None
        in_sequence = False

        for index, plan in enumerate(passes):
            is_first = index == 0
            is_last = index == len(passes) - 1
            previous_draw_to = draw_to

            target_name = plan.target
            writes_target = False
            if target_name:
                resolved = self._resolve_fbo(target_name, fbo_ids, plan, width, height)
                if resolved is not None:
                    if not in_sequence:
                        effect_input = as_input
                        in_sequence = True
                    draw_to = resolved
                    writes_target = True
                else:
                    entry.notes.append(f"pass target {target_name} unresolved")

            if is_first and model.get("passthrough") and not model.get("fullscreen"):
                geometry = dict(quad)
            else:
                geometry = {"kind": "fullscreen"}
            if not writes_target and is_last:
                geometry = dict(quad)
                draw_to = "main"
            if is_first and tex_scale != [1.0, 1.0]:
                geometry["texCoordScale"] = tex_scale

            ok = self._draw(
                obj, plan, entry, geometry, draw_to, as_input, effect_input if in_sequence else None,
                fbo_ids, statics, base_format, width, height,
            )
            if ok:
                entry.draws += 1

            if writes_target:
                as_input = draw_to
                draw_to = previous_draw_to
            else:
                draw_to = current_sub
                as_input = current_main
                current_main, current_sub = current_sub, current_main
                in_sequence = False
                effect_input = None

    def _draw(
        self, obj, plan, entry, geometry, draw_to, as_input, effect_input, fbo_ids, statics,
        base_format, width, height,
    ) -> bool:
        material = plan.material
        override = plan.override
        constants = dict(material.constants)
        if isinstance(override.get("constantshadervalues"), dict):
            constants.update(override["constantshadervalues"])
        constants = {key: self._user(value, value) for key, value in constants.items()}

        slots: dict[int, str] = {}
        try:
            units = [
                shaders.load_unit(self.locator, material.shader, shaders.VERTEX, plan.effect_dir),
                shaders.load_unit(self.locator, material.shader, shaders.FRAGMENT, plan.effect_dir),
            ]
        except shaders.ShaderError as exc:
            self.shader_failures.append({"object": obj.get("id", -1), "shader": material.shader, "error": str(exc)})
            entry.notes.append(f"shader {material.shader} unavailable")
            return False

        for unit in units:
            for sampler in unit.samplers:
                if sampler.default:
                    slots[sampler.index] = sampler.default
        slots.update(material.textures)
        slots.update(material.usertextures)
        slots.update(_texture_map(override.get("textures")))
        slots.update(_texture_map(override.get("usertextures")))
        slots.update(plan.binds)

        refs: dict[int, str | None] = {}
        formats: dict[int, int] = {}
        for index, name in sorted(slots.items()):
            ref, fmt = self._slot(name, plan, fbo_ids, effect_input, as_input, width, height)
            refs[index] = ref
            if ref is not None:
                formats[index] = fmt
        if refs.get(0) is None:
            refs[0] = as_input
        if refs[0] is not None and 0 not in formats:
            formats[0] = base_format

        bound = {index for index, ref in refs.items() if ref is not None}
        combos = shaders.resolve_combos(
            units, material.combos, _combo_map(override.get("combos")), bound, formats, constants
        )
        program = self._program(material.shader, combos, units, plan.effect_dir)
        if program is None:
            self.shader_failures.append(
                {"object": obj.get("id", -1), "shader": material.shader, "error": "glslangValidator rejected it"}
            )
            entry.notes.append(f"shader {material.shader} failed validation")
            return False
        pid, source = program

        uniforms = {
            name: value
            for name, value in shaders.resolve_params(units, constants).items()
            if shaders.uses(source, name)
        }
        for name in STATIC_UNIFORMS:
            if shaders.uses(source, name):
                uniforms[name] = statics[name]
        for name in sorted(set(re.findall(r"\bg_[A-Za-z0-9_]+", source))):
            if name in uniforms or name in BUILTIN_UNIFORMS:
                continue
            if re.fullmatch(r"g_Texture\d+(Resolution|Texel)?", name) or name.startswith("g_AudioSpectrum"):
                continue
            self.unsupported_uniforms.add(name)

        top = max(refs) if refs else -1
        textures = [refs.get(index) for index in range(top + 1)]
        self.draws.append(
            {
                "object": str(obj.get("name") or obj.get("id")),
                "pass": plan.label,
                "program": pid,
                "target": _rid(draw_to),
                "blend": BLEND.get(material.blending, "disabled"),
                "geometry": geometry,
                "textures": textures,
                "uniforms": uniforms,
            }
        )
        return True

    def _slot(self, name, plan, fbo_ids, effect_input, as_input, width, height) -> tuple[str | None, int]:
        if name == "previous":
            return (effect_input or as_input), 0
        lowered = name.lower()
        if lowered in RT_FULLFRAME:
            return "fullframe", 0
        if lowered.startswith("_rt_") or lowered.startswith("_alias_"):
            resolved = self._resolve_fbo(name, fbo_ids, plan, width, height)
            return resolved, 0
        ref, fmt, _ = self._source(name, plan.effect_dir)
        return ref, fmt

    def _resolve_fbo(self, name, fbo_ids, plan, width, height) -> str | None:
        lowered = name.lower()
        if lowered in RT_FULLFRAME:
            return "fullframe"
        if lowered in plan.fbos:
            key = (plan.effect_key, lowered)
            if key not in fbo_ids:
                scale = plan.fbos[lowered] or 1.0
                fbo_ids[key] = self._target(
                    f"{lowered.strip('_')}_{len(self.targets)}",
                    max(1, int(width / scale)),
                    max(1, int(height / scale)),
                )
            return fbo_ids[key]
        # Scene-wide buffers (the shadow atlas, the bloom chain) have no producer in a plan.
        self.unwritten_targets.add(name)
        return None

    # -- registries ----------------------------------------------------------

    def _target(self, name: str, width: int, height: int) -> str:
        rid = re.sub(r"[^a-zA-Z0-9]+", "_", name)
        self.targets[rid] = {"width": width, "height": height}
        return f"target:{rid}"

    def _source(self, name: str, effect_dir: str | None) -> tuple[str | None, int, tuple[float, float] | None]:
        lowered = name.lower()
        if lowered in RT_FULLFRAME:
            return "fullframe", 0, (float(self.canvas[0]), float(self.canvas[1]))
        if lowered.startswith("_rt_") or lowered.startswith("_alias_"):
            return None, 0, None
        try:
            handle = self.locator.find(f"materials/{name}.tex", effect_dir)
        except MissingAsset:
            self.missing.append(f"materials/{name}.tex")
            return None, 0, None
        if handle in self.texture_by_handle:
            tid = self.texture_by_handle[handle]
            info = self.textures[tid]
            return tid, self.texture_format[tid], (float(info["imageWidth"]), float(info["imageHeight"]))

        tid = f"t{len(self.textures)}"
        raw = self.locator.read_handle(handle)
        if handle.endswith(".png"):
            image, fmt, clamp, nearest = self._png_texture(handle, raw)
            width, height = image.size
            image_width, image_height = width, height
            (self.out / "textures").mkdir(parents=True, exist_ok=True)
            image.save(self.out / "textures" / f"{tid}.png", optimize=True)
        else:
            decoded = tex.load(raw, handle)
            fmt = decoded.format
            clamp, nearest = decoded.clamp, decoded.nearest
            width, height = decoded.mip_width, decoded.mip_height
            image_width, image_height = decoded.image_width, decoded.image_height
            if decoded.notes:
                self.missing.extend(f"{name}: {note}" for note in decoded.notes)
            (self.out / "textures").mkdir(parents=True, exist_ok=True)
            tex.to_png(decoded, self.out / "textures" / f"{tid}.png")

        self.textures[tid] = {
            "file": f"textures/{tid}.png",
            "width": width,
            "height": height,
            "imageWidth": min(image_width, width),
            "imageHeight": min(image_height, height),
            "wrap": "clamp" if clamp else "repeat",
            "filter": "nearest" if nearest else "linear",
        }
        self.texture_format[tid] = fmt
        self.texture_by_handle[handle] = tid
        return tid, fmt, (float(image_width), float(image_height))

    def _png_texture(self, handle: str, raw: bytes):
        from io import BytesIO

        from PIL import Image

        image = Image.open(BytesIO(raw)).convert("RGBA")
        info = {}
        path = Path(handle.partition(":")[2])
        meta = path.with_suffix(".tex-json")
        if meta.is_file():
            try:
                info = json.loads(meta.read_text(encoding="utf-8-sig"))
            except json.JSONDecodeError:
                info = {}
        formats = {"rgba8888": 0, "dxt5": 4, "dxt3": 6, "dxt1": 7, "rg88": 8, "r8": 9}
        fmt = formats.get(str(info.get("format", "rgba8888")).lower(), 0)
        return image, fmt, bool(info.get("clampuvs", False)), bool(info.get("nointerpolation", False))

    def _program(self, shader: str, combos: dict[str, int], units, effect_dir):
        key = (shader, tuple(sorted(combos.items())))
        if key in self.program_by_key:
            pid = self.program_by_key[key]
            if pid is None:
                return None
            return pid, self._program_source[pid]
        vertex = shaders.emit(units[0], combos)
        fragment = shaders.emit(units[1], combos)
        for source, stage in ((vertex, shaders.VERTEX), (fragment, shaders.FRAGMENT)):
            error = shaders.validate(source, stage)
            if error is not None:
                self.program_by_key[key] = None
                self.shader_failures.append({"shader": f"{shader}.{stage}", "error": _first_error(error)})
                return None
        pid = f"p{len(self.programs)}"
        (self.out / "shaders").mkdir(parents=True, exist_ok=True)
        (self.out / "shaders" / f"{pid}.vert").write_text(vertex)
        (self.out / "shaders" / f"{pid}.frag").write_text(fragment)
        self.programs[pid] = {"vertex": f"shaders/{pid}.vert", "fragment": f"shaders/{pid}.frag"}
        self.program_by_key[key] = pid
        self._program_source[pid] = (
            shaders.preprocess(vertex, shaders.VERTEX) + "\n" + shaders.preprocess(fragment, shaders.FRAGMENT)
        )
        return pid, self._program_source[pid]


def _identity4() -> list[float]:
    return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def _rid(reference: str) -> str:
    return reference.partition(":")[2] or reference


def _effect_dir(file: str) -> str | None:
    parts = file.replace("\\", "/").split("/")
    if len(parts) >= 2 and parts[0] == "effects":
        return parts[-2] if parts[-1].endswith(".json") else parts[1]
    return None


def _same(value: object, condition: object) -> bool:
    if condition is None:
        return bool(value)
    try:
        return int(float(str(value))) == int(float(str(condition)))
    except ValueError:
        return str(value) == str(condition)


def _first_error(text: str) -> str:
    for line in text.splitlines():
        if line.startswith("ERROR:"):
            return line.strip()
    return text.strip().splitlines()[0] if text.strip() else "unknown"
