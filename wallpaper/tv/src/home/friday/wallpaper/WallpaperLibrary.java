package home.friday.wallpaper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The wallpaper bundles pushed to {@code getExternalFilesDir("wallpapers")}: one folder each, named
 * by its id, holding a {@code wallpaper.json} (wallpaper/docs/format.md). A folder whose manifest is
 * missing, oversized, of another version or short of the file it names is left out of the list.
 */
public final class WallpaperLibrary {

    public enum Kind { VIDEO, SCENE }

    public static final class Entry {
        public final String id, title;
        public final Kind kind;
        public final File dir, preview;
        /** VIDEO: the file to play and its pixel size (0 until the decoder reports it). */
        final File video;
        final int width, height;
        /** SCENE: the render plan and the rate to run it at. */
        final File plan;
        final int fps;
        /** The converter left objects out (particles, text...), from the manifest's coverage. */
        public final boolean partial;
        /** Why, for the tile's caption: at most a handful of short words. */
        public final String skipped;

        private Entry(String id, String title, Kind kind, File dir, File preview,
                      File video, int width, int height, File plan, int fps,
                      boolean partial, String skipped) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.dir = dir;
            this.preview = preview;
            this.video = video;
            this.width = width;
            this.height = height;
            this.plan = plan;
            this.fps = fps;
            this.partial = partial;
            this.skipped = skipped;
        }
    }

    /** The one place a bundle turns into a running backdrop, so Home and the dream agree. */
    public static Backdrop backdrop(Context c, Entry e, Runnable onFailure) {
        return e.kind == Kind.SCENE
                ? new SceneBackdrop(c, e.dir, e.fps, onFailure)
                : new VideoBackdrop(c, e, onFailure);
    }

    private static final long MAX_MANIFEST = 64 * 1024;

    private final File root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile List<Entry> entries = Collections.emptyList();

    public WallpaperLibrary(Context c) {
        root = c.getExternalFilesDir("wallpapers");
    }

    /** The last scan's result, by title; empty until the first {@link #scan} finishes. */
    public List<Entry> entries() {
        return entries;
    }

    public Entry byId(String id) {
        for (Entry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    /** Reads the folders on {@code bg}, then runs {@code done} on the main thread. */
    public void scan(Executor bg, Runnable done) {
        bg.execute(() -> {
            List<Entry> found = read();
            ui.post(() -> {
                entries = found;
                if (done != null) done.run();
            });
        });
    }

    /** Drops the bundle from the list at once and deletes its folder on {@code bg}. */
    public void delete(Executor bg, String id, Runnable done) {
        Entry e = byId(id);
        if (e == null) {
            if (done != null) done.run();
            return;
        }
        List<Entry> left = new ArrayList<>(entries);
        left.remove(e);
        entries = Collections.unmodifiableList(left);
        bg.execute(() -> {
            deleteTree(e.dir);
            ui.post(() -> {
                if (done != null) done.run();
            });
        });
    }

    private List<Entry> read() {
        File[] dirs = root != null ? root.listFiles() : null;
        if (dirs == null) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            Entry e = parse(d);
            if (e != null) out.add(e);
        }
        out.sort((a, b) -> {
            int t = a.title.compareToIgnoreCase(b.title);
            return t != 0 ? t : a.id.compareTo(b.id);
        });
        return Collections.unmodifiableList(out);
    }

    private static Entry parse(File dir) {
        String id = dir.getName();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return null;
        File manifest = new File(dir, "wallpaper.json");
        if (!manifest.isFile() || manifest.length() > MAX_MANIFEST) return null;
        try {
            JSONObject o = new JSONObject(
                    new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            if (o.optInt("version") != 1) return null;
            File preview = child(dir, o.optString("preview"));
            if (preview == null) return null;
            String title = o.optString("title").trim();
            if (title.isEmpty()) title = id;
            String kind = o.optString("kind");
            if ("video".equals(kind)) {
                JSONObject v = o.optJSONObject("video");
                File f = v != null ? child(dir, v.optString("file")) : null;
                if (f == null) return null;
                return new Entry(id, title, Kind.VIDEO, dir, preview, f,
                        Math.max(0, v.optInt("width")), Math.max(0, v.optInt("height")), null, 0,
                        false, "");
            }
            if ("scene".equals(kind)) {
                JSONObject s = o.optJSONObject("scene");
                File f = s != null ? child(dir, s.optString("plan")) : null;
                if (f == null) return null;
                JSONObject c = o.optJSONObject("coverage");
                int missed = c != null ? c.optInt("skipped") : 0;
                return new Entry(id, title, Kind.SCENE, dir, preview, null, 0, 0, f,
                        Math.max(1, Math.min(60, s.optInt("fps", 30))),
                        missed > 0, missed > 0 ? reasons(c) : "");
            }
            return null;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    /** The coverage reasons, joined for a caption; the manifest keeps at most a handful. */
    private static String reasons(JSONObject coverage) {
        JSONArray list = coverage.optJSONArray("reasons");
        if (list == null || list.length() == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.length() && i < 3; i++) {
            String r = list.optString(i).trim();
            if (r.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(r);
        }
        return out.toString();
    }

    /** The file a manifest's relative path names, or null if it escapes the bundle or is absent. */
    private static File child(File dir, String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.indexOf('\\') >= 0) return null;
        for (String part : rel.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return null;
        }
        File f = new File(dir, rel);
        return f.isFile() ? f : null;
    }

    private static void deleteTree(File dir) {
        // The manifest goes first, so a delete that stops half way leaves nothing the scan picks up.
        new File(dir, "wallpaper.json").delete();
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) deleteTree(k);
            else k.delete();
        }
        dir.delete();
    }
}
