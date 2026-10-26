"""Promotes integer literals to float literals where GLSL ES 3.00 needs it.

Wallpaper Engine's shaders are written against HLSL and desktop GLSL, which both convert
`int` to `float` implicitly. GLSL ES 3.00 does not, so `float mask = 1;` and `offset * 2`
have to be rewritten. Everything that still looks like an integer context is left alone and
glslangValidator is the backstop.
"""

from __future__ import annotations

import re

INT_TYPES = {
    "int", "uint", "bool",
    "ivec2", "ivec3", "ivec4",
    "uvec2", "uvec3", "uvec4",
    "bvec2", "bvec3", "bvec4",
}
QUALIFIERS = {"const", "in", "out", "inout", "uniform", "flat", "highp", "mediump", "lowp", "attribute", "varying"}
INT_NAMES = {"gl_VertexID", "gl_InstanceID", "gl_FragDepth"}
BOUNDARY = {";", "{", "}", ")", ":"}
BINARY = {
    "+", "-", "*", "/", "%", "<", ">", "<=", ">=", "==", "!=", "=",
    "+=", "-=", "*=", "/=", "&", "|", "^", "<<", ">>", "?",
}

_TOKEN = re.compile(
    r"""
    (?P<space>[ \t\r\n]+)
  | (?P<line_comment>//[^\n]*)
  | (?P<block_comment>/\*.*?\*/)
  | (?P<directive>\#[^\n]*)
  | (?P<float>
        (?:\d+\.\d*|\.\d+)(?:[eE][+-]?\d+)?[fF]?
      | \d+[eE][+-]?\d+[fF]?
      | \d+[fF]
    )
  | (?P<hex>0[xX][0-9a-fA-F]+[uU]?)
  | (?P<uint>\d+[uU])
  | (?P<int>\d+)
  | (?P<name>[A-Za-z_]\w*)
  | (?P<op><<=|>>=|<=|>=|==|!=|&&|\|\||\+\+|--|\+=|-=|\*=|/=|%=|<<|>>|.)
    """,
    re.VERBOSE | re.DOTALL,
)

SKIP = {"space", "line_comment", "block_comment", "directive"}


def _tokenize(source: str) -> list[tuple[str, str, int, int]]:
    out = []
    for match in _TOKEN.finditer(source):
        kind = match.lastgroup
        out.append((kind, match.group(0), match.start(), match.end()))
    return out


def promote_int_literals(source: str) -> str:
    tokens = _tokenize(source)
    code = [index for index, token in enumerate(tokens) if token[0] not in SKIP]
    position = {index: slot for slot, index in enumerate(code)}
    int_names = _int_names(tokens, code)
    int_params = _int_params(tokens, code)

    edits: list[tuple[int, int]] = []
    for slot, index in enumerate(code):
        if tokens[index][0] != "int":
            continue
        if not _is_integer_context(tokens, code, slot, int_names, int_params):
            edits.append((tokens[index][2], tokens[index][3]))

    if not edits:
        return source
    out = []
    last = 0
    for start, end in edits:
        out.append(source[last:start])
        out.append(source[start:end] + ".0")
        last = end
    out.append(source[last:])
    return "".join(out)


def _int_names(tokens, code) -> set[str]:
    names = set(INT_NAMES)
    for slot, index in enumerate(code):
        if tokens[index][1] in INT_TYPES and slot + 1 < len(code):
            nxt = tokens[code[slot + 1]]
            if nxt[0] == "name":
                names.add(nxt[1])
    return names


def _int_params(tokens, code) -> dict[str, set[int]]:
    """Maps a function name to the argument slots it declares as an integer type."""
    out: dict[str, set[int]] = {}
    for slot, index in enumerate(code):
        if tokens[index][0] != "name" or slot + 1 >= len(code):
            continue
        if tokens[code[slot + 1]][1] != "(":
            continue
        if slot > 0 and tokens[code[slot - 1]][1] == ".":
            continue
        close = _match_paren(tokens, code, slot + 1)
        if close is None or close + 1 >= len(code) or tokens[code[close + 1]][1] != "{":
            continue
        name = tokens[index][1]
        args = _split_args(tokens, code, slot + 1, close)
        ints = set()
        for argument, words in enumerate(args):
            if any(word in INT_TYPES for word in words):
                ints.add(argument)
        if ints:
            out.setdefault(name, set()).update(ints)
    return out


def _match_paren(tokens, code, open_slot: int) -> int | None:
    depth = 0
    for slot in range(open_slot, len(code)):
        text = tokens[code[slot]][1]
        if text == "(":
            depth += 1
        elif text == ")":
            depth -= 1
            if depth == 0:
                return slot
    return None


def _split_args(tokens, code, open_slot: int, close_slot: int) -> list[list[str]]:
    args: list[list[str]] = [[]]
    depth = 0
    for slot in range(open_slot + 1, close_slot):
        text = tokens[code[slot]][1]
        if text in "([":
            depth += 1
        elif text in ")]":
            depth -= 1
        elif text == "," and depth == 0:
            args.append([])
            continue
        args[-1].append(text)
    return args if args != [[]] else []


def _is_integer_context(tokens, code, slot: int, int_names: set[str], int_params: dict[str, set[int]]) -> bool:
    before = tokens[code[slot - 1]][1] if slot > 0 else ""
    after = tokens[code[slot + 1]][1] if slot + 1 < len(code) else ""

    if before == "[" or after == "]":
        return True
    if before == "case" or after == ":":
        return True
    if before == "%" or after == "%":
        return True
    if before == "." or after == ".":
        return True
    if _declared_int(tokens, code, slot):
        return True
    if _neighbour_is_int(tokens, code, slot, -1, int_names) or _neighbour_is_int(tokens, code, slot, 1, int_names):
        return True
    return _in_int_argument(tokens, code, slot, int_params)


def _declared_int(tokens, code, slot: int) -> bool:
    cursor = slot - 1
    while cursor >= 0:
        text = tokens[code[cursor]][1]
        if text in BOUNDARY or text == ",":
            break
        if text == "(":
            break
        cursor -= 1
    cursor += 1
    while cursor < slot and tokens[code[cursor]][1] in QUALIFIERS:
        cursor += 1
    return cursor < slot and tokens[code[cursor]][1] in INT_TYPES


def _neighbour_is_int(tokens, code, slot: int, step: int, int_names: set[str]) -> bool:
    cursor = slot + step
    if cursor < 0 or cursor >= len(code):
        return False
    if tokens[code[cursor]][1] not in BINARY:
        return False
    cursor += step
    while 0 <= cursor < len(code) and tokens[code[cursor]][1] in ("(", ")", "+", "-"):
        cursor += step
    if not (0 <= cursor < len(code)):
        return False
    token = tokens[code[cursor]]
    if token[0] == "uint" or token[0] == "hex":
        return True
    return token[0] == "name" and token[1] in int_names


def _in_int_argument(tokens, code, slot: int, int_params: dict[str, set[int]]) -> bool:
    depth = 0
    argument = 0
    cursor = slot - 1
    while cursor >= 0:
        text = tokens[code[cursor]][1]
        if text in (")", "]"):
            depth += 1
        elif text == "[":
            if depth == 0:
                return True
            depth -= 1
        elif text == "(":
            if depth == 0:
                break
            depth -= 1
        elif text == "," and depth == 0:
            argument += 1
        elif text in (";", "{", "}") and depth == 0:
            return False
        cursor -= 1
    if cursor < 0:
        return False
    callee = cursor - 1
    if callee < 0 or tokens[code[callee]][0] != "name":
        return False
    name = tokens[code[callee]][1]
    if name in INT_TYPES:
        return True
    return argument in int_params.get(name, set())


# GLSL ES 3.00 keeps these for future use; Wallpaper Engine's shaders use some as plain names.
RESERVED = (
    "sample filter input output common partition active buffer shared packed resource patch subroutine "
    "image long short half fixed unsigned inline noinline static extern external interface superp this "
    "class union enum typedef template namespace using cast sizeof goto volatile public asm"
).split()

_RESERVED = re.compile(r"\b(" + "|".join(RESERVED) + r")\b")


def rename_reserved(source: str) -> str:
    return _RESERVED.sub(lambda m: "we_" + m.group(1), source)


# HLSL lets a `const` local be initialised from a runtime value; GLSL ES requires a constant
# expression, so the qualifier is dropped from declarations that have an initialiser.
_CONST_LOCAL = re.compile(
    r"\bconst\s+(?=(?:float|int|uint|bool|vec[234]|ivec[234]|uvec[234]|bvec[234]|mat[234](?:x[234])?)\s+\w+\s*=)"
)


def drop_const_initialisers(source: str) -> str:
    return _CONST_LOCAL.sub("", source)


SCALAR_TYPES = {
    "float": "float", "int": "int", "uint": "uint",
    "vec2": "vec2", "vec3": "vec3", "vec4": "vec4",
    "ivec2": "ivec2", "ivec3": "ivec3", "ivec4": "ivec4",
    "uvec2": "uvec2", "uvec3": "uvec3", "uvec4": "uvec4",
    "float2": "vec2", "float3": "vec3", "float4": "vec4",
    "int2": "ivec2", "int3": "ivec3", "int4": "ivec4",
}
STATEMENT_START = {";", "{", "}", "", "(", ":", "else", "do"}


def wrap_initialisers(source: str) -> str:
    """HLSL converts on assignment (`float x = anInt;`); a constructor call does the same in GLSL."""
    tokens = _tokenize(source)
    code = [index for index, token in enumerate(tokens) if token[0] not in SKIP]
    edits: list[tuple[int, int, str]] = []
    slot = 0
    while slot + 2 < len(code):
        text = tokens[code[slot]][1]
        previous = tokens[code[slot - 1]][1] if slot > 0 else ""
        if text not in SCALAR_TYPES or previous not in STATEMENT_START:
            slot += 1
            continue
        if tokens[code[slot + 1]][0] != "name" or tokens[code[slot + 2]][1] != "=":
            slot += 1
            continue
        end = _statement_end(tokens, code, slot + 3)
        if end is None:
            slot += 1
            continue
        start_offset = tokens[code[slot + 3]][2]
        end_offset = tokens[code[end]][2]
        edits.append((start_offset, end_offset, SCALAR_TYPES[text]))
        slot = end + 1

    if not edits:
        return source
    out = []
    last = 0
    for start, end, cast in edits:
        out.append(source[last:start])
        out.append(f"{cast}(" + source[start:end].rstrip() + ")")
        last = end
    out.append(source[last:])
    return "".join(out)


def _statement_end(tokens, code, slot: int) -> int | None:
    depth = 0
    while slot < len(code):
        text = tokens[code[slot]][1]
        if text in "([":
            depth += 1
        elif text in ")]":
            depth -= 1
            if depth < 0:
                return None
        elif depth == 0:
            if text == ";":
                return slot
            if text in (",", "{", "}"):
                return None
        slot += 1
    return None
