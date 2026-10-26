from __future__ import annotations

import json
import re
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from .glsl import drop_const_initialisers, promote_int_literals, rename_reserved, wrap_initialisers
from .locator import Locator, MissingAsset

VERTEX = "vert"
FRAGMENT = "frag"

PRELUDE = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
#define GLSL 1
#define GLSL_ES 1
#define mul(x, y) ((y) * (x))
#define max(x, y) max(y, x)
#define lerp mix
#define frac fract
#define saturate(x) (clamp(x, 0.0, 1.0))
#define CAST2(x) (vec2(x))
#define CAST3(x) (vec3(x))
#define CAST4(x) (vec4(x))
#define CAST3X3(x) (mat3(x))
#define CASTF(x) (float(x))
#define CASTU(x) (uint(x))
#define CASTI(x) (int(x))
#define CASTB(x) (bool(x))
#define float2 vec2
#define float3 vec3
#define float4 vec4
#define int2 ivec2
#define int3 ivec3
#define int4 ivec4
#define uint2 uvec2
#define uint3 uvec3
#define uint4 uvec4
#define texSample2D texture
#define texSample2DLod textureLod
#define texSample2DGrad textureGrad
#define texSample3D texture
#define texture2D texture
#define texture2DLod textureLod
#define textureCube texture
#define log10(x) (log2(x) * 0.301029995663981)
#define atan2(y, x) atan(y, x)
#define fmod(x, y) ((x) - (y) * trunc((x) / (y)))
#define rsqrt(x) inversesqrt(x)
#define ddx dFdx
#define ddy dFdy
#define clip(x) { if ((x) < 0.0) discard; }
"""

VERTEX_PRELUDE = "#define attribute in\n#define varying out\n"
FRAGMENT_PRELUDE = "out vec4 fragColor;\n#define varying in\n"

LIGHTING_V1_STUB = """vec3 PerformLighting_V1(vec3 worldPos, vec3 albedo, vec3 normal, vec3 viewDir,
    vec3 specularTint, vec3 baseReflectance, float roughness, float metallic)
{
    return vec3(0.0);
}
"""

_COMBO = re.compile(r"//\s*\[COMBO\]\s*(\{.*\})\s*$")
_UNIFORM = re.compile(r"^\s*uniform\s+(\S+)\s+(\w+)\s*(?:\[[^\]]*\])?\s*;\s*//\s*(\{.*\})\s*$")
_INCLUDE = re.compile(r'^[ \t]*#include[ \t]+"([^"]+)"[ \t]*\r?$', re.MULTILINE)
_REQUIRE = re.compile(r"^[ \t]*#require[ \t]+(\w+)[ \t]*\r?$", re.MULTILINE)
_MAIN = re.compile(r"^\s*(?:\w[\w \t]*)?\bmain\s*\(", re.MULTILINE)


class ShaderError(Exception):
    pass


@dataclass
class ComboDecl:
    name: str
    default: int
    material: str | None


@dataclass
class Param:
    name: str
    type: str
    material: str | None
    default: object | None


@dataclass
class Sampler:
    index: int
    default: str | None
    combo: str | None
    require: dict | None
    require_any: bool


@dataclass
class Unit:
    stage: str
    file: str
    body: str
    includes: str
    combos: list[ComboDecl] = field(default_factory=list)
    params: list[Param] = field(default_factory=list)
    samplers: list[Sampler] = field(default_factory=list)


def load_unit(locator: Locator, shader: str, stage: str, effect_dir: str | None) -> Unit:
    path = f"shaders/{shader}.{stage}"
    try:
        source = locator.read_text(path, effect_dir)
    except MissingAsset as exc:
        raise ShaderError(f"missing shader {path}") from exc

    includes: list[str] = []
    body = _strip_includes(locator, source, effect_dir, includes, depth=0)
    body = _REQUIRE.sub(lambda m: _require(m.group(1), shader), body)
    body = body.replace("gl_FragColor", "fragColor")

    unit = Unit(stage=stage, file=path, body=body, includes="\n".join(includes))
    for line in (source + "\n" + unit.includes).splitlines():
        _scan_line(line, unit)
    return unit


def _require(module: str, shader: str) -> str:
    if module == "LightingV1":
        return LIGHTING_V1_STUB
    raise ShaderError(f"{shader}: unknown #require module {module}")


def _strip_includes(locator: Locator, source: str, effect_dir: str | None, out: list[str], depth: int) -> str:
    if depth > 8:
        raise ShaderError("include nesting too deep")

    def take(match: re.Match[str]) -> str:
        name = match.group(1)
        try:
            text = locator.read_text(f"shaders/{name}", effect_dir)
        except MissingAsset:
            return f"// missing include {name}"
        out.append(f"// begin {name}")
        out.append(_strip_includes(locator, text, effect_dir, out, depth + 1))
        out.append(f"// end {name}")
        return f"// included {name}"

    return _INCLUDE.sub(take, source)


def _scan_line(line: str, unit: Unit) -> None:
    combo = _COMBO.search(line)
    if combo:
        try:
            data = json.loads(combo.group(1))
        except json.JSONDecodeError:
            return
        name = data.get("combo")
        if isinstance(name, str):
            default = data.get("default", 0)
            unit.combos.append(
                ComboDecl(name=name, default=int(default) if isinstance(default, (int, float)) else 0,
                          material=data.get("material"))
            )
        return

    declaration = _UNIFORM.match(line)
    if not declaration:
        return
    kind, name, raw = declaration.groups()
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return
    if kind.startswith("sampler"):
        index = _sampler_index(name)
        if index is None:
            return
        default = data.get("default")
        unit.samplers.append(
            Sampler(
                index=index,
                default=default if isinstance(default, str) else None,
                combo=data.get("combo"),
                require=data.get("require") if isinstance(data.get("require"), dict) else None,
                require_any=bool(data.get("requireany", False)),
            )
        )
        return
    if kind in ("float", "int", "vec2", "vec3", "vec4"):
        unit.params.append(Param(name=name, type=kind, material=data.get("material"), default=data.get("default")))


def _sampler_index(name: str) -> int | None:
    match = re.fullmatch(r"g_Texture(\d+)", name)
    return int(match.group(1)) if match else None


def resolve_combos(
    units: list[Unit],
    material_combos: dict[str, int],
    override_combos: dict[str, int],
    bound_slots: set[int],
    texture_formats: dict[int, int],
    constants: dict[str, object],
) -> dict[str, int]:
    combos: dict[str, int] = {}
    for unit in units:
        for decl in unit.combos:
            key = decl.name.upper()
            if key in combos:
                continue
            value = decl.default
            if decl.material and decl.material in constants:
                given = constants[decl.material]
                if isinstance(given, (int, float)) and float(given).is_integer():
                    value = int(given)
            combos[key] = value
        for sampler in unit.samplers:
            if not sampler.combo:
                continue
            key = sampler.combo.upper()
            if key in combos or sampler.index not in bound_slots:
                continue
            combos[key] = 1
    for name, value in material_combos.items():
        combos[name.upper()] = int(value)
    for name, value in override_combos.items():
        combos[name.upper()] = int(value)
    for index, fmt in texture_formats.items():
        combos.setdefault(f"TEX{index}FORMAT", fmt)
    return combos


def resolve_params(units: list[Unit], constants: dict[str, object]) -> dict[str, list[float]]:
    values: dict[str, list[float]] = {}
    for unit in units:
        for param in unit.params:
            raw = param.default
            if param.material and param.material in constants:
                raw = constants[param.material]
            if raw is None:
                continue
            parsed = _as_floats(raw, param.type)
            if parsed is not None:
                values[param.name] = parsed
    return values


def _as_floats(raw: object, kind: str) -> list[float] | None:
    want = {"float": 1, "int": 1, "vec2": 2, "vec3": 3, "vec4": 4}[kind]
    if isinstance(raw, str):
        parts = raw.replace(",", " ").split()
        try:
            numbers = [float(p) for p in parts]
        except ValueError:
            return None
    elif isinstance(raw, bool):
        numbers = [float(raw)]
    elif isinstance(raw, (int, float)):
        numbers = [float(raw)]
    elif isinstance(raw, list):
        try:
            numbers = [float(p) for p in raw]
        except (TypeError, ValueError):
            return None
    else:
        return None
    if not numbers:
        return None
    if len(numbers) < want:
        numbers = numbers + [numbers[-1]] * (want - len(numbers))
    return numbers[:want]


def emit(unit: Unit, combos: dict[str, int]) -> str:
    head = [PRELUDE, VERTEX_PRELUDE if unit.stage == VERTEX else FRAGMENT_PRELUDE]
    for name in sorted(combos):
        head.append(f"#define {name} {combos[name]}\n")
    body = unit.body
    if unit.includes:
        body = _insert_includes(body, unit.includes)
    text = _ground_conditionals("".join(head) + "\n" + body + "\n")
    return wrap_initialisers(promote_int_literals(drop_const_initialisers(rename_reserved(text))))


_DEFINED = re.compile(r"\bdefined\s*(?:\(\s*(\w+)\s*\)|(\w+))")
_CONDITIONAL = re.compile(r"^([ \t]*#(?:if|elif)[ \t]+)(.*)$", re.MULTILINE)
_WORD = re.compile(r"[A-Za-z_]\w*")


def _ground_conditionals(source: str) -> str:
    """GLSL ES forbids undefined macros in #if, so substitute 0 for names nothing defines."""
    known = set(re.findall(r"^[ \t]*#define[ \t]+(\w+)", source, re.MULTILINE))

    def fix(match: re.Match[str]) -> str:
        head, expr = match.group(1), match.group(2)
        guarded: list[str] = []

        def park(hit: re.Match[str]) -> str:
            guarded.append(hit.group(0))
            return f"\x00{len(guarded) - 1}\x00"

        expr = _DEFINED.sub(park, expr)
        expr = _WORD.sub(lambda w: w.group(0) if w.group(0) in known else "0", expr)
        for index, text in enumerate(guarded):
            expr = expr.replace(f"\x00{index}\x00", text)
        return head + expr

    return _CONDITIONAL.sub(fix, source)


def _insert_includes(body: str, includes: str) -> str:
    match = _MAIN.search(body)
    if match is None:
        return includes + "\n" + body
    at = match.start()
    return body[:at] + includes + "\n" + body[at:]


def preprocess(source: str, stage: str) -> str:
    """The live code only, so a uniform in a dead #if branch is not mistaken for one in use."""
    done = _glslang(source, stage, ["-E"])
    return done.stdout if done.returncode == 0 else source


def uses(source: str, name: str) -> bool:
    return re.search(rf"\b{re.escape(name)}\b", source) is not None


def validate(source: str, stage: str) -> str | None:
    """Returns None when glslangValidator accepts the source, else its complaint."""
    done = _glslang(source, stage, [])
    if done.returncode == 0:
        return None
    return (done.stdout + done.stderr).strip()


def _glslang(source: str, stage: str, flags: list[str]) -> subprocess.CompletedProcess:
    suffix = ".vert" if stage == VERTEX else ".frag"
    with tempfile.NamedTemporaryFile("w", suffix=suffix, delete=False) as handle:
        handle.write(source)
        temp = Path(handle.name)
    try:
        return subprocess.run(
            ["glslangValidator", *flags, str(temp)], capture_output=True, text=True, check=False
        )
    finally:
        temp.unlink(missing_ok=True)
