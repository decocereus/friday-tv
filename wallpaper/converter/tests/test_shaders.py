import shutil

import pytest

from converter import glsl, shaders
from converter.locator import Locator

VERT = """
#include "common_vertex.h"

uniform mat4 g_ModelViewProjectionMatrix;

attribute vec3 a_Position;
attribute vec2 a_TexCoord;

varying vec2 v_TexCoord;

void main() {
\tgl_Position = mul(vec4(a_Position, 1.0), g_ModelViewProjectionMatrix);
\tv_TexCoord = a_TexCoord;
}
"""

FRAG = """
// [COMBO] {"material":"ui_tint","combo":"TINT","type":"options","default":0}

uniform sampler2D g_Texture0;
uniform sampler2D g_Texture1; // {"combo":"MASK","default":"util/white"}
uniform float g_Strength; // {"material":"strength","default":0.25,"range":[0,1]}
uniform vec3 g_TintColor; // {"material":"tint","default":"1 0 0"}

varying vec2 v_TexCoord;

void main() {
\tvec4 albedo = texSample2D(g_Texture0, v_TexCoord);
\tfloat mask = 1;
\tconst float scale = 2 * g_Strength;
#if MASK
\tmask = texSample2D(g_Texture1, v_TexCoord).r;
#endif
#if TINT
\talbedo.rgb = lerp(albedo.rgb, g_TintColor, saturate(mask * scale));
#endif
\tgl_FragColor = albedo;
}
"""

INCLUDE = """
mat3 Unused(vec3 a) { return mat3(a, a, a); }
"""


@pytest.fixture
def sample(tmp_path):
    (tmp_path / "shaders").mkdir()
    (tmp_path / "shaders" / "sample.vert").write_text(VERT)
    (tmp_path / "shaders" / "sample.frag").write_text(FRAG)
    (tmp_path / "shaders" / "common_vertex.h").write_text(INCLUDE)
    return Locator(tmp_path)


def units(sample):
    return [
        shaders.load_unit(sample, "sample", shaders.VERTEX, None),
        shaders.load_unit(sample, "sample", shaders.FRAGMENT, None),
    ]


def test_annotations_become_combos_and_uniforms(sample):
    pair = units(sample)
    combos = shaders.resolve_combos(pair, {}, {}, {0, 1}, {0: 4, 1: 9}, {"ui_tint": 1})
    assert combos["TINT"] == 1
    assert combos["MASK"] == 1
    assert combos["TEX0FORMAT"] == 4
    assert combos["TEX1FORMAT"] == 9

    values = shaders.resolve_params(pair, {"strength": 0.5})
    assert values["g_Strength"] == [0.5]
    assert values["g_TintColor"] == [1.0, 0.0, 0.0]


def test_a_material_combo_beats_the_annotation_default(sample):
    combos = shaders.resolve_combos(units(sample), {"TINT": 3}, {}, set(), {}, {})
    assert combos["TINT"] == 3
    assert "MASK" not in combos


def test_an_override_combo_wins(sample):
    combos = shaders.resolve_combos(units(sample), {"TINT": 3}, {"tint": 7}, set(), {}, {})
    assert combos["TINT"] == 7


def test_emitted_source_is_es300_with_includes_and_defines(sample):
    pair = units(sample)
    combos = shaders.resolve_combos(pair, {}, {}, {0, 1}, {}, {})
    fragment = shaders.emit(pair[1], combos)

    assert fragment.startswith("#version 300 es\n")
    assert "out vec4 fragColor;" in fragment
    assert "gl_FragColor" not in fragment
    assert "#define MASK 1" in fragment
    assert "float mask = float(1.0);" in fragment

    vertex = shaders.emit(pair[0], combos)
    assert "mat3 Unused(vec3 a)" in vertex
    assert "#include" not in vertex
    assert "#define attribute in" in vertex


@pytest.mark.skipif(shutil.which("glslangValidator") is None, reason="glslangValidator is not installed")
def test_both_stages_validate(sample):
    pair = units(sample)
    combos = shaders.resolve_combos(pair, {}, {}, {0, 1}, {}, {})
    for unit in pair:
        assert shaders.validate(shaders.emit(unit, combos), unit.stage) is None


def test_undefined_macros_in_conditionals_become_zero():
    text = shaders.emit(
        shaders.Unit("frag", "x.frag", "#if HLSL_SM30\nint a;\n#endif\n#ifdef HLSL\nint b;\n#endif\n", ""),
        {},
    )
    assert "#if 0" in text
    assert "#ifdef HLSL" in text


def test_reserved_words_are_renamed():
    assert glsl.rename_reserved("vec4 sample = f();") == "vec4 we_sample = f();"
    assert glsl.rename_reserved("vec4 sampler2D_x;") == "vec4 sampler2D_x;"


def test_int_literals_are_promoted_only_outside_integer_context():
    out = glsl.promote_int_literals("float a = 1; int i = 2; float b = arr[3] * 4; for (int k = 0; k < 5; ++k) {}")
    assert "float a = 1.0;" in out
    assert "int i = 2;" in out
    assert "arr[3]" in out and "* 4.0" in out
    assert "int k = 0;" in out and "k < 5;" in out
