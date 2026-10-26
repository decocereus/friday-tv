package home.friday.wallpaper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A bundle's plan.json, parsed and validated once into immutable objects: ids resolved to the
 * texture, program and target they name, shader sources read, reserved names checked. The
 * renderer makes no further decisions about the plan's contents.
 */
final class ScenePlan {

    static final String MAIN = "main", FULLFRAME = "fullframe";
    static final int MAX_TEXTURE_UNITS = 8;

    enum Geometry { FULLSCREEN, QUAD }

    enum Blend { DISABLED, NORMAL, TRANSLUCENT, ADDITIVE, MULTIPLY }

    enum Wrap { CLAMP, REPEAT, MIRROR }

    enum Filter { LINEAR, NEAREST }

    static final class Texture {
        final String id;
        final File file;
        final int width, height, imageWidth, imageHeight;
        final Wrap wrap;
        final Filter filter;

        private Texture(String id, File file, int w, int h, int iw, int ih, Wrap wrap, Filter filter) {
            this.id = id; this.file = file; this.width = w; this.height = h;
            this.imageWidth = iw; this.imageHeight = ih; this.wrap = wrap; this.filter = filter;
        }
    }

    static final class Program {
        final String id;
        final String vertex, fragment;
        /** Uniforms the sources declare as int, bool or a vector of those, so they go in as ints. */
        final java.util.Set<String> integers;
        /** One past the highest {@code g_Texture<i>} sampler the sources declare. */
        final int samplers;

        private Program(String id, String vertex, String fragment) {
            this.id = id; this.vertex = vertex; this.fragment = fragment;
            String src = vertex + "\n" + fragment;
            this.integers = Collections.unmodifiableSet(integerUniforms(src));
            this.samplers = samplerCount(src);
        }
    }

    static final class Target {
        final String id;
        final int width, height;
        final boolean main;

        private Target(String id, int w, int h, boolean main) {
            this.id = id; this.width = w; this.height = h; this.main = main;
        }
    }

    /** What a draw binds to one sampler unit. */
    static final class Bind {
        enum Kind { NONE, TEXTURE, TARGET, FULLFRAME }

        static final Bind NONE = new Bind(Kind.NONE, null, null);
        static final Bind FRAME = new Bind(Kind.FULLFRAME, null, null);

        final Kind kind;
        final Texture texture;
        final Target target;

        private Bind(Kind kind, Texture texture, Target target) {
            this.kind = kind; this.texture = texture; this.target = target;
        }
    }

    static final class Uniform {
        final String name;
        final float[] value;

        private Uniform(String name, float[] value) {
            this.name = name; this.value = value;
        }
    }

    static final class Draw {
        final String object;
        final Program program;
        final Target target;
        final Blend blend;
        final Geometry geometry;
        final float originX, originY, sizeX, sizeY, scaleX, scaleY, angle, depthX, depthY;
        /** a_TexCoord multiplier, for an image padded into a larger texture. */
        final float texScaleX, texScaleY;
        final List<Bind> textures;
        final List<Uniform> uniforms;
        final boolean fullframe;

        private Draw(String object, Program program, Target target, Blend blend, Geometry geometry,
                     float[] g, List<Bind> textures, List<Uniform> uniforms) {
            this.object = object; this.program = program; this.target = target; this.blend = blend;
            this.geometry = geometry;
            this.originX = g[0]; this.originY = g[1]; this.sizeX = g[2]; this.sizeY = g[3];
            this.scaleX = g[4]; this.scaleY = g[5]; this.angle = g[6]; this.depthX = g[7]; this.depthY = g[8];
            this.texScaleX = g[9]; this.texScaleY = g[10];
            this.textures = Collections.unmodifiableList(textures);
            this.uniforms = Collections.unmodifiableList(uniforms);
            boolean frame = false;
            for (Bind b : textures) frame |= b.kind == Bind.Kind.FULLFRAME;
            this.fullframe = frame;
        }
    }

    static final class Parallax {
        final boolean enabled;
        final float amount, delay, mouseInfluence;

        private Parallax(boolean enabled, float amount, float delay, float mouseInfluence) {
            this.enabled = enabled; this.amount = amount; this.delay = delay;
            this.mouseInfluence = mouseInfluence;
        }
    }

    final int canvasWidth, canvasHeight;
    final float[] clear;
    final Parallax parallax;
    final Target main;
    final Map<String, Texture> textures;
    final Map<String, Program> programs;
    final Map<String, Target> targets;
    final List<Draw> draws;

    private ScenePlan(int cw, int ch, float[] clear, Parallax parallax, Target main,
                      Map<String, Texture> textures, Map<String, Program> programs,
                      Map<String, Target> targets, List<Draw> draws) {
        this.canvasWidth = cw; this.canvasHeight = ch; this.clear = clear; this.parallax = parallax;
        this.main = main;
        this.textures = Collections.unmodifiableMap(textures);
        this.programs = Collections.unmodifiableMap(programs);
        this.targets = Collections.unmodifiableMap(targets);
        this.draws = Collections.unmodifiableList(draws);
    }

    /** Reads {@code bundleDir/name}; every rejection names the member that is wrong. */
    static ScenePlan read(File bundleDir, String name) throws IOException {
        File file = new File(bundleDir, name);
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException(file + ": cannot be read (" + e.getMessage() + ")", e);
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException(name + ": not valid JSON (" + e.getMessage() + ")", e);
        }
        try {
            return parse(bundleDir, name, root);
        } catch (JSONException e) {
            throw new IOException(name + ": " + e.getMessage(), e);
        }
    }

    private static ScenePlan parse(File dir, String name, JSONObject root) throws IOException, JSONException {
        int version = root.optInt("version", 0);
        if (version != 1) throw new IOException(name + ": version " + version + " is not supported");

        JSONArray canvas = root.optJSONArray("canvas");
        if (canvas == null || canvas.length() != 2) throw new IOException(name + ": canvas must be [width, height]");
        int cw = canvas.getInt(0), ch = canvas.getInt(1);
        if (cw <= 0 || ch <= 0) throw new IOException(name + ": canvas " + cw + "x" + ch + " is empty");

        float[] clear = {0f, 0f, 0f, 1f};
        JSONArray c = root.optJSONArray("clear");
        if (c != null) {
            if (c.length() != 4) throw new IOException(name + ": clear must be four numbers");
            for (int i = 0; i < 4; i++) clear[i] = (float) c.getDouble(i);
        }

        JSONObject p = root.optJSONObject("parallax");
        Parallax parallax = p == null
                ? new Parallax(false, 0f, 0f, 0f)
                : new Parallax(p.optBoolean("enabled", false), (float) p.optDouble("amount", 0.1),
                        (float) p.optDouble("delay", 0.0), (float) p.optDouble("mouseInfluence", 0.0));

        Map<String, Texture> textures = new LinkedHashMap<>();
        JSONObject tj = root.optJSONObject("textures");
        for (String id : keys(tj)) {
            if (FULLFRAME.equals(id)) throw new IOException(name + ": texture id \"" + FULLFRAME + "\" is reserved");
            JSONObject t = tj.getJSONObject(id);
            String path = t.optString("file", "");
            if (path.isEmpty()) throw new IOException(name + ": texture " + id + " has no file");
            File f = resolve(dir, path, name, "texture " + id);
            int w = t.optInt("width", 0), h = t.optInt("height", 0);
            if (w <= 0 || h <= 0) throw new IOException(name + ": texture " + id + " has no size");
            textures.put(id, new Texture(id, f, w, h, t.optInt("imageWidth", w), t.optInt("imageHeight", h),
                    wrap(t.optString("wrap", "clamp"), name, id), filter(t.optString("filter", "linear"), name, id)));
        }

        Map<String, Program> programs = new LinkedHashMap<>();
        JSONObject pj = root.optJSONObject("programs");
        for (String id : keys(pj)) {
            JSONObject g = pj.getJSONObject(id);
            String vs = g.optString("vertex", ""), fs = g.optString("fragment", "");
            if (vs.isEmpty() || fs.isEmpty()) throw new IOException(name + ": program " + id + " needs vertex and fragment");
            programs.put(id, new Program(id,
                    source(resolve(dir, vs, name, "program " + id), name, "program " + id),
                    source(resolve(dir, fs, name, "program " + id), name, "program " + id)));
        }

        Map<String, Target> targets = new LinkedHashMap<>();
        Target main = new Target(MAIN, cw, ch, true);
        JSONObject rj = root.optJSONObject("targets");
        for (String id : keys(rj)) {
            if (MAIN.equals(id)) throw new IOException(name + ": target id \"" + MAIN + "\" is reserved");
            JSONObject r = rj.getJSONObject(id);
            int w = r.optInt("width", 0), h = r.optInt("height", 0);
            if (w <= 0 || h <= 0) throw new IOException(name + ": target " + id + " has no size");
            targets.put(id, new Target(id, w, h, false));
        }
        targets.put(MAIN, main);

        JSONArray dj = root.optJSONArray("draws");
        if (dj == null) throw new IOException(name + ": draws must be an array");
        List<Draw> draws = new ArrayList<>(dj.length());
        for (int i = 0; i < dj.length(); i++) {
            JSONObject d = dj.getJSONObject(i);
            String where = name + ": draw " + i + " (" + d.optString("object", "?") + ")";
            Program program = programs.get(d.optString("program", ""));
            if (program == null) throw new IOException(where + ": unknown program \"" + d.optString("program") + "\"");
            Target target = targets.get(d.optString("target", MAIN));
            if (target == null) throw new IOException(where + ": unknown target \"" + d.optString("target") + "\"");
            Blend blend = blend(d.optString("blend", "normal"), where);

            JSONObject gj = d.optJSONObject("geometry");
            if (gj == null) throw new IOException(where + ": no geometry");
            String kind = gj.optString("kind", "");
            Geometry geometry;
            float[] g = {0f, 0f, cw, ch, 1f, 1f, 0f, 1f, 1f, 1f, 1f};
            if ("fullscreen".equals(kind)) {
                geometry = Geometry.FULLSCREEN;
            } else if ("quad".equals(kind)) {
                geometry = Geometry.QUAD;
                pair(gj, "origin", where, g, 0, true);
                pair(gj, "size", where, g, 2, true);
                pair(gj, "scale", where, g, 4, false);
                pair(gj, "parallaxDepth", where, g, 7, false);
                g[6] = (float) gj.optDouble("angle", 0.0);
            } else {
                throw new IOException(where + ": geometry kind \"" + kind + "\" is neither fullscreen nor quad");
            }
            pair(gj, "texCoordScale", where, g, 9, false);

            List<Bind> binds = new ArrayList<>();
            JSONArray xj = d.optJSONArray("textures");
            if (xj != null) {
                if (xj.length() > MAX_TEXTURE_UNITS) {
                    throw new IOException(where + ": " + xj.length() + " textures, at most " + MAX_TEXTURE_UNITS);
                }
                for (int u = 0; u < xj.length(); u++) binds.add(bind(xj.isNull(u) ? null : xj.getString(u),
                        textures, targets, where + " texture " + u));
            }

            List<Uniform> uniforms = new ArrayList<>();
            JSONObject uj = d.optJSONObject("uniforms");
            for (String key : keys(uj)) uniforms.add(new Uniform(key, floats(uj.get(key), where + " uniform " + key)));

            draws.add(new Draw(d.optString("object", ""), program, target, blend, geometry, g, binds, uniforms));
        }
        return new ScenePlan(cw, ch, clear, parallax, main, textures, programs, targets, draws);
    }

    // ---------------------------------------------------------------- members

    private static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        if (o != null) for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static void pair(JSONObject o, String key, String where, float[] out, int at, boolean required)
            throws IOException, JSONException {
        JSONArray a = o.optJSONArray(key);
        if (a == null) {
            if (required) throw new IOException(where + ": geometry needs " + key);
            return;
        }
        if (a.length() != 2) throw new IOException(where + ": " + key + " must be two numbers");
        out[at] = (float) a.getDouble(0);
        out[at + 1] = (float) a.getDouble(1);
    }

    private static Bind bind(String spec, Map<String, Texture> textures, Map<String, Target> targets, String where)
            throws IOException {
        if (spec == null || spec.isEmpty()) return Bind.NONE;
        if (FULLFRAME.equals(spec)) return Bind.FRAME;
        if (spec.startsWith("target:")) {
            String id = spec.substring(7);
            Target t = targets.get(id);
            if (t == null) throw new IOException(where + ": unknown target \"" + id + "\"");
            return new Bind(Bind.Kind.TARGET, null, t);
        }
        Texture t = textures.get(spec);
        if (t == null) throw new IOException(where + ": unknown texture \"" + spec + "\"");
        return new Bind(Bind.Kind.TEXTURE, t, null);
    }

    private static float[] floats(Object value, String where) throws IOException, JSONException {
        if (value instanceof Number) return new float[]{((Number) value).floatValue()};
        if (!(value instanceof JSONArray)) throw new IOException(where + ": not a number or array of numbers");
        JSONArray a = (JSONArray) value;
        int n = a.length();
        if (n != 1 && n != 2 && n != 3 && n != 4 && n != 9 && n != 16) {
            throw new IOException(where + ": length " + n + " is not 1, 2, 3, 4, 9 or 16");
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = (float) a.getDouble(i);
        return out;
    }

    private static Blend blend(String s, String where) throws IOException {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "disabled": return Blend.DISABLED;
            case "normal": return Blend.NORMAL;
            case "translucent": return Blend.TRANSLUCENT;
            case "additive": return Blend.ADDITIVE;
            case "multiply": return Blend.MULTIPLY;
            default: throw new IOException(where + ": unknown blend \"" + s + "\"");
        }
    }

    private static Wrap wrap(String s, String name, String id) throws IOException {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "clamp": return Wrap.CLAMP;
            case "repeat": return Wrap.REPEAT;
            case "mirror": return Wrap.MIRROR;
            default: throw new IOException(name + ": texture " + id + ": unknown wrap \"" + s + "\"");
        }
    }

    private static Filter filter(String s, String name, String id) throws IOException {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "linear": return Filter.LINEAR;
            case "nearest": case "point": return Filter.NEAREST;
            default: throw new IOException(name + ": texture " + id + ": unknown filter \"" + s + "\"");
        }
    }

    /** Bundle paths are relative and stay inside the bundle: a pushed folder is not trusted. */
    private static File resolve(File dir, String path, String name, String where) throws IOException {
        File f = new File(dir, path);
        String root, child;
        try {
            root = dir.getCanonicalPath();
            child = f.getCanonicalPath();
        } catch (IOException e) {
            throw new IOException(name + ": " + where + ": \"" + path + "\" cannot be resolved", e);
        }
        if (!child.startsWith(root.endsWith(File.separator) ? root : root + File.separator)) {
            throw new IOException(name + ": " + where + ": \"" + path + "\" leaves the bundle");
        }
        if (!f.isFile()) throw new IOException(name + ": " + where + ": \"" + path + "\" is missing");
        return f;
    }

    private static String source(File f, String name, String where) throws IOException {
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (s.trim().isEmpty()) throw new IOException(name + ": " + where + ": " + f.getName() + " is empty");
        return s;
    }

    private static java.util.Set<String> integerUniforms(String src) {
        java.util.Set<String> out = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(?:int|uint|bool|[ib]vec[234])\\s+([A-Za-z_]\\w*)")
                .matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static int samplerCount(String src) {
        int n = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?sampler\\w*\\s+g_Texture(\\d+)").matcher(src);
        while (m.find()) n = Math.max(n, Integer.parseInt(m.group(1)) + 1);
        return Math.min(n, MAX_TEXTURE_UNITS);
    }
}
