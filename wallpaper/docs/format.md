# Wallpapers

Friday Home can show a Wallpaper Engine wallpaper behind its rows instead of the photo slideshow.
The Mac does all Wallpaper Engine work. The TV plays a video file or executes a render plan, and
knows nothing about Wallpaper Engine's formats.

```
Workshop item or folder ──> scripts/wallpaper (Mac) ──> bundle ──adb push──> TV library ──> Friday Home
   project.json               download, unpack,          wallpaper.json        picker,
   scene.pkg / video          decode, translate,         plan.json,            Backdrop
                              validate                   textures, shaders
```

Wallpaper Engine's own assets (`.local/wallpaper-engine/assets`) and Workshop content stay on this
Mac and the TV. They are never committed.

## Bundle layout

One folder per wallpaper, identical on the Mac (`.local/wallpapers/<id>/`) and on the TV
(`/sdcard/Android/data/home.friday.tv/files/wallpapers/<id>/`, the launcher's
`getExternalFilesDir("wallpapers")`). `<id>` is the Workshop id, or `local-<sha1 prefix>` for a
folder with no Workshop id. A folder without a readable `wallpaper.json` is ignored.

### `wallpaper.json`

```json
{
  "version": 1,
  "id": "1140295655",
  "title": "Rainy city 1920p",
  "kind": "video",
  "preview": "preview.jpg",
  "source": "workshop:1140295655",
  "video": { "file": "video.mp4", "width": 1920, "height": 1080 },
  "coverage": { "rendered": 12, "skipped": 3, "reasons": ["particles", "text"] }
}
```

`kind` is `"video"` or `"scene"`. Exactly one of `video` or `scene` is present.

```json
  "kind": "scene",
  "scene": { "plan": "plan.json", "fps": 30 }
```

`preview` is a JPEG or PNG, at most 640 px wide, for the picker. Video files are H.264, HEVC, VP9
or AV1 at most 3840x2160 (Friday decodes these in hardware), with no audio track.

`coverage` is `report.json` boiled down to what a tile can show: how many objects were rendered,
how many were left out, and the distinct reasons (the part before the colon, at most six). The TV
badges a bundle `PARTIAL` when `skipped` is above zero and names the first few reasons under the
title. It is optional: a bundle without it is treated as complete, and `wallpaper.sh push` tops up an
older bundle from its `report.json` on the way out.

## Render plan (`plan.json`)

The plan is a fully resolved, ordered list of draws. The TV renderer executes it every frame and
makes no decisions of its own beyond the built-in uniforms below.

```json
{
  "version": 1,
  "canvas": [1920, 1080],
  "clear": [0.0, 0.0, 0.0, 1.0],
  "parallax": { "enabled": false, "amount": 0.1, "delay": 1.7, "mouseInfluence": 0.0 },
  "textures": {
    "t0": { "file": "textures/t0.png", "width": 2048, "height": 1024,
            "imageWidth": 1920, "imageHeight": 1080, "wrap": "clamp", "filter": "linear" }
  },
  "programs": {
    "p0": { "vertex": "shaders/p0.vert", "fragment": "shaders/p0.frag" }
  },
  "targets": {
    "r0": { "width": 1920, "height": 1080 }
  },
  "draws": [
    {
      "object": "dreamfall-chapters-game",
      "program": "p0",
      "target": "r0",
      "blend": "disabled",
      "geometry": { "kind": "fullscreen" },
      "textures": ["t0", null, "target:r1", "fullframe"],
      "uniforms": { "g_Strength": [0.09], "g_Color4": [1, 1, 1, 1] }
    },
    {
      "object": "dreamfall-chapters-game",
      "program": "p1",
      "target": "main",
      "blend": "translucent",
      "geometry": { "kind": "quad", "origin": [960, 540], "size": [1920, 1080],
                    "scale": [1, 1], "angle": 0.0, "parallaxDepth": [1, 1] },
      "textures": ["target:r0"],
      "uniforms": {}
    }
  ]
}
```

### Coordinates and orientation

- Canvas units are Wallpaper Engine scene pixels, origin bottom-left, y up, as in `scene.json`.
- **Every texture and target is stored top row first.** Texture coordinate (0, 0) is the top-left
  pixel for image textures and render targets alike. The renderer flips y when drawing into a
  target so that ping-ponged targets keep this orientation.
- `main` is a reserved target the size of the canvas (the renderer may render it at a lower
  resolution, at most 1920x1080, and scale every target by the same factor). The renderer draws the
  whole plan into `main`, then shows `main` on screen, aspect-fill, flipped as needed.
- `fullframe` is a reserved texture: a copy of `main` taken immediately before the draw that names
  it. It stands in for Wallpaper Engine's `_rt_FullFrameBuffer`.

### Geometry

- `fullscreen` covers the whole target. `a_Position` is in clip space (-1..1), and
  `g_ModelViewProjectionMatrix` is the identity.
- `quad` is a rectangle in canvas units, centred on `origin`, `size` before `scale`, rotated by
  `angle` radians about its centre, shifted by parallax times `parallaxDepth`.
  `g_ModelViewProjectionMatrix` is the canvas orthographic projection times this model transform.
  `a_Position` is the corner in model units (±size/2, z = 0).
- Both kinds draw two triangles with `a_TexCoord` (0, 0) at the top-left corner and (1, 1) at the
  bottom-right.
- `texCoordScale` (optional, default `[1, 1]`) multiplies `a_TexCoord`, for both kinds. Wallpaper
  Engine pads a texture to a power of two and keeps the image in the top-left corner, so a draw that
  samples such a texture over the whole quad needs `[imageWidth / width, imageHeight / height]`.

### Textures, blending, uniforms

- `textures[i]` binds sampler `g_Texture<i>`. An entry is a texture id, `"target:<id>"`, `"fullframe"`
  or `null` (unbound, a 1x1 transparent black texture).
- `blend` is one of `disabled` (replace), `normal` (src-alpha, one-minus-src-alpha), `translucent`
  (same as normal), `additive` (src-alpha, one), `multiply` (dst-colour, zero).
- `uniforms` values are float arrays of length 1, 2, 3, 4, 9 or 16, set with the matching
  `glUniform*`. Integers are sent as floats unless the shader declares `int`.

The renderer sets these built-in uniforms when a program declares them:

| Uniform | Value |
|---|---|
| `g_Time` | seconds since the wallpaper started, float |
| `g_ModelViewProjectionMatrix` | per geometry kind, above |
| `g_ModelMatrix` | model transform (identity for fullscreen) |
| `g_ViewProjectionMatrix` | canvas orthographic projection |
| `g_Texture<i>Resolution` | vec4(texture width, texture height, image width, image height); for targets all four are the target size |
| `g_Texture<i>Texel` | the reciprocal of the uploaded texture size, (1 / width, 1 / height), repeated to fill a vec4 |
| `g_Texture<i>Rotation` | vec4(1, 0, 0, 1) — the identity frame, since animated textures are flattened to frame 0 |
| `g_Texture<i>Translation` | vec2(0, 0), for the same reason |
| `g_Screen` | vec3(main width, main height, width / height) |
| `g_PointerPosition` | vec2 in 0..1, a slow drift around (0.5, 0.5), since a TV has no mouse |
| `g_PointerPositionLast` | the previous frame's `g_PointerPosition` |
| `g_ParallaxPosition` | vec2, the same drift centred on 0 |
| `g_TexelSize`, `g_TexelSizeHalf` | vec2(1/main width, 1/main height) and half of it |
| `g_Daytime` | fraction of the local day, 0..1 |
| `g_AudioSpectrum16Left` / `Right`, `g_AudioSpectrum32*`, `g_AudioSpectrum64*` | zeros |

### Shaders

`shaders/*.vert` and `*.frag` are complete GLSL ES 3.00 sources (`#version 300 es`, precision
qualifiers included), with includes inlined and combos fixed by `#define`. Attributes are
`a_Position` (vec3) and `a_TexCoord` (vec2). The fragment output is `out vec4 fragColor`. Every
shader in a bundle has passed `glslangValidator` for its stage on the Mac. A draw whose shader
failed is left out of the plan, and the bundle's `report.json` says so.

### `report.json` (Mac only, not read by the TV)

What the converter did with each object and effect: `rendered`, `skipped` with a reason
(`particles`, `text`, `script`, `sound`, `3d-model`, `shader-error: …`, `missing-asset: …`), so
coverage across the corpus can be counted. The TV never parses this file; it reads the `coverage`
summary copied into `wallpaper.json`.

## Changes from converter

Everything in this section is additive: it pins down behaviour the contract left open, so the Mac
reference renderer (`scripts/wallpaper/preview.py`) and the TV renderer agree frame for frame.

### Geometry

- `geometry.texCoordScale`, described under Geometry above. The converter emits it only on an
  object's first pass, the one that reads the padded source texture; everywhere else it is absent
  and the renderer uses `[1, 1]`.

### Orientation

The canvas orthographic projection is `ortho(0, width, height, 0)`: canvas y = height (the top) maps
to clip y = -1, which is row 0 of the target, so every target is stored top row first. It is used
both as `g_ViewProjectionMatrix` and, times the model transform, as `g_ModelViewProjectionMatrix`.
A `fullscreen` draw keeps `g_ModelViewProjectionMatrix` as the identity and pairs clip y = -1 with
`a_TexCoord.y = 0`. The present pass that puts `main` on screen is the only flip in the pipeline.

### Clearing and `fullframe`

A target is cleared the first time a frame draws into it: `main` to `clear`, every other target to
transparent black. A target that a frame never draws into keeps the previous frame's content, and so
does a `fullframe` taken before the first draw into `main`.

### Pointer drift and parallax

```
g_PointerPosition = vec2(0.5 + 0.03 * sin(g_Time * 0.13), 0.5 + 0.03 * sin(g_Time * 0.11))
g_ParallaxPosition = g_PointerPosition - 0.5
```

`g_PointerPositionLast` is the same expression one frame earlier. When `parallax.enabled`, the
renderer keeps a smoothed drift, `drift += (g_ParallaxPosition - drift) * clamp(delay * dt, 0, 1)`,
and shifts a `quad` by `drift * amount * canvas * parallaxDepth`, per axis.
`parallax.mouseInfluence` is still written to the plan but the renderer ignores it, because a TV has
no mouse to be influenced by. The reference renderer replays the smoothing from t = 0 at a fixed
30 fps so that a single frame is reproducible.

### Resolution

When the canvas is larger than 1920x1080 the renderer halves it until it fits, and divides every
target and every texture by the same power of two. `g_Texture<i>Resolution` and `g_Texture<i>Texel`
describe the uploaded size, not the size in `plan.json`, so a shader that scales texture coordinates
by `g_Texture<i>Resolution.zw / g_Texture<i>Resolution.xy` is unaffected.

### `report.json`

Alongside the per-object list the converter writes `counts`, `shaderFailures` (shader name and the
first `glslangValidator` error), `missingAssets`, `unsupportedUniforms` (uniforms a shader declares
that nothing sets, so they stay zero) and `unwrittenTargets` (scene-wide buffers such as
`_rt_shadowAtlas` that no plan draw produces; those sampler slots are bound to nothing). Skip
reasons also include `invisible`, `no image`, `zero size`, `passthrough without effects` and
`material has no passes`.
