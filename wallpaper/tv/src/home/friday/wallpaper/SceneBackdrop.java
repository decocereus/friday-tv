package home.friday.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs a wallpaper bundle's plan.json on the GPU. Every draw goes into the reserved {@code main}
 * target, which is then shown aspect-fill. Textures and targets keep plan.json's orientation, top
 * row first, so the y flip lives in the canvas projection and happens once more when presenting.
 *
 * <p>A SurfaceView behind the window, like {@link VideoBackdrop}: this TV composites Home's
 * window with the GPU and upscales it to 2160p, so a TextureView would drag the whole window
 * through that for every scene frame. Its own layer goes to the display controller instead, at
 * whatever size the scene renders, and the TV scales it. Home cross-fades backdrops, so fading
 * into a scene dips through the bundle's preview rather than blending.
 */
final class SceneBackdrop implements Backdrop {

    private static final String TAG = "FridayScene";
    private static final int MAX_W = 1920, MAX_H = 1080;
    private static final float MAX_SCRIM = 0.55f;
    private static final long LOG_NANOS = 5_000_000_000L;
    private static final int STRIDE = 20;
    private static final float[] ZEROS = new float[64];
    private static final float[] NO_ROTATION = {1f, 0f, 0f, 1f}, NO_TRANSLATION = {0f, 0f};
    private static final float[] STEPS = SceneMath.STEPS;
    private static final String[] SAMPLER = new String[ScenePlan.MAX_TEXTURE_UNITS];
    private static final String[] RESOLUTION = new String[ScenePlan.MAX_TEXTURE_UNITS];
    private static final String[] TEXEL = new String[ScenePlan.MAX_TEXTURE_UNITS];
    private static final String[] ROTATION = new String[ScenePlan.MAX_TEXTURE_UNITS];
    private static final String[] TRANSLATION = new String[ScenePlan.MAX_TEXTURE_UNITS];

    static {
        for (int i = 0; i < ScenePlan.MAX_TEXTURE_UNITS; i++) {
            SAMPLER[i] = "g_Texture" + i;
            RESOLUTION[i] = "g_Texture" + i + "Resolution";
            TEXEL[i] = "g_Texture" + i + "Texel";
            ROTATION[i] = "g_Texture" + i + "Rotation";
            TRANSLATION[i] = "g_Texture" + i + "Translation";
        }
    }

    private static final String PRESENT_VS = "#version 300 es\n"
            + "in vec3 a_Position; in vec2 a_TexCoord; uniform vec2 u_Scale; out vec2 v_TexCoord;\n"
            // main holds its top row at v = 0 and the screen's top is clip y = +1: flip once here.
            + "void main() { v_TexCoord = vec2(a_TexCoord.x, 1.0 - a_TexCoord.y);\n"
            + "  gl_Position = vec4(a_Position.xy * u_Scale, 0.0, 1.0); }\n";
    private static final String PRESENT_FS = "#version 300 es\n"
            + "precision mediump float; in vec2 v_TexCoord; uniform sampler2D u_Tex; out vec4 fragColor;\n"
            + "void main() { fragColor = vec4(texture(u_Tex, v_TexCoord).rgb, 1.0); }\n";

    private final File dir;
    private final int fps;
    private final long period;
    private final Runnable onFailure;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final FrameLayout box;
    private final SurfaceView scene;
    private final ImageView still;
    private final View scrim;
    private final HandlerThread thread;
    private final Handler glh;
    private final Renderer renderer = new Renderer();
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();
    private volatile ScenePlan plan;
    private volatile boolean running, released;
    private volatile int generation;
    private boolean notified, uncovered;
    private int fixedW, fixedH;
    private volatile int viewW, viewH;
    private float blur;

    SceneBackdrop(Context c, java.io.File bundleDir, int fps, Runnable onFailure) {
        this.dir = bundleDir;
        this.fps = fps <= 0 ? 30 : Math.min(60, fps);
        this.period = 1_000_000_000L / this.fps;
        this.onFailure = onFailure;

        box = new FrameLayout(c);
        box.setBackgroundColor(Color.BLACK);
        scene = new SurfaceView(c);
        box.addView(scene, new FrameLayout.LayoutParams(-1, -1));
        scene.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            viewW = r - l;
            viewH = b - t;
        });
        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(still, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(c);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        box.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        thread = new HandlerThread("FridayScene", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        glh = new Handler(thread.getLooper());

        scene.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }

            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                Surface s = h.getSurface();
                glh.post(() -> renderer.attach(s, w, hh));
            }

            @Override public void surfaceDestroyed(SurfaceHolder h) {
                // Nothing is on the surface after this, so the preview covers it again.
                uncovered = false;
                still.animate().cancel();
                still.setAlpha(1f);
                // The surface is gone once this returns, so wait for the GL thread to drop it.
                CountDownLatch done = new CountDownLatch(1);
                if (!glh.post(() -> {
                    renderer.detach();
                    done.countDown();
                })) return;
                try {
                    done.await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        loader.execute(this::load);
    }

    @Override public View view() {
        return box;
    }

    @Override public void start() {
        if (released || running) return;
        running = true;
        glh.post(renderer::resume);
        reload();
    }

    @Override public void pause() {
        if (!running) return;
        running = false;
        glh.post(renderer::halt);
    }

    @Override public void release() {
        if (released) return;
        released = true;
        running = false;
        loader.shutdownNow();
        still.setImageDrawable(null);
        glh.post(() -> {
            renderer.halt();
            renderer.free();
            renderer.shutdown();
        });
        thread.quitSafely();
    }

    /** 0 = the scene as it renders, 1 = a dark scrim over it, where photos would be blurred. */
    @Override public void setBlur(float f) {
        blur = f;
        scrim.setAlpha(Math.max(0f, Math.min(1f, f)) * MAX_SCRIM);
    }

    @Override public float getBlur() {
        return blur;
    }

    /** Picks up any texture that is not on the GPU yet; harmless once the loader is gone. */
    private void reload() {
        int gen = generation;
        try {
            if (!loader.isShutdown()) loader.execute(() -> decode(gen));
        } catch (RuntimeException ignored) {
        }
    }

    /** The first frame is on screen: fade the bundle's preview out. */
    private void uncover() {
        if (uncovered || released) return;
        uncovered = true;
        still.animate().alpha(0f).setDuration(260).start();
    }

    /** Render at {@code w} x {@code h} and let the TV's compositor scale the layer to the panel. */
    private void fix(int w, int h) {
        if (released || (w == fixedW && h == fixedH)) return;
        fixedW = w;
        fixedH = h;
        scene.getHolder().setFixedSize(w, h);
    }

    private void fail(String why) {
        Log.e(TAG, why);
        ui.post(() -> {
            if (notified || released) return;
            notified = true;
            onFailure.run();
        });
    }

    // ---------------------------------------------------------------- loading

    private void load() {
        cover();
        try {
            ScenePlan p = ScenePlan.read(dir, "plan.json");
            plan = p;
            Log.i(TAG, "plan: " + p.draws.size() + " draws, " + p.programs.size() + " programs, "
                    + p.textures.size() + " textures, " + (p.targets.size() - 1) + " targets, canvas "
                    + p.canvasWidth + "x" + p.canvasHeight);
            glh.post(renderer::wake);
            decode(generation);
        } catch (Exception e) {
            fail("plan: " + e.getMessage());
        }
    }

    /** The bundle's picker preview hides the black surface until the first frame lands. */
    private void cover() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.startsWith("preview.")) continue;
            if (!n.endsWith(".jpg") && !n.endsWith(".jpeg") && !n.endsWith(".png")) continue;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) ui.post(() -> {
                if (!released && !uncovered) still.setImageBitmap(b);
            });
            return;
        }
    }

    /**
     * Decodes textures here and hands each to the GL thread. Textures shrink by the same factor
     * as main, rounded to the power of two BitmapFactory can subsample to.
     */
    private void decode(int gen) {
        ScenePlan p = plan;
        if (p == null) return;
        int sample = SceneMath.sample(SceneMath.downscale(p.canvasWidth, p.canvasHeight, MAX_W, MAX_H));
        for (ScenePlan.Texture t : p.textures.values()) {
            if (released || !running || gen != generation) return;
            if (!claimed.add(gen + ":" + t.id)) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPremultiplied = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inSampleSize = sample;
            Bitmap b;
            try {
                b = BitmapFactory.decodeFile(t.file.getAbsolutePath(), o);
            } catch (OutOfMemoryError e) {
                b = null;
            }
            if (b == null) {
                Log.w(TAG, "texture " + t.id + ": cannot decode " + t.file.getName());
                continue;
            }
            int w = b.getWidth(), h = b.getHeight();
            ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            // Row 0 of a Bitmap is the top row and copyPixelsToBuffer keeps that order, so the
            // upload lands top row first, which is the orientation plan.json promises.
            b.copyPixelsToBuffer(px);
            px.rewind();
            b.recycle();
            int iw = Math.max(1, Math.round(t.imageWidth * (float) w / t.width));
            int ih = Math.max(1, Math.round(t.imageHeight * (float) h / t.height));
            glh.post(() -> renderer.upload(gen, t, px, w, h, iw, ih));
        }
    }

    // ---------------------------------------------------------------- GL side

    private static final class Prog {
        final int id;
        final boolean ok;
        final int pos, uv, samplers;
        final Set<String> integers;
        final Map<String, Integer> locs = new HashMap<>();
        /** Frame this program last had the per-frame uniforms, so each frame sets them once. */
        int stamp = -1;

        Prog(int id, boolean ok, int samplers, Set<String> integers) {
            this.id = id;
            this.ok = ok;
            this.samplers = samplers;
            this.integers = integers;
            this.pos = ok ? GLES30.glGetAttribLocation(id, "a_Position") : -1;
            this.uv = ok ? GLES30.glGetAttribLocation(id, "a_TexCoord") : -1;
        }

        int loc(String name) {
            Integer l = locs.get(name);
            if (l == null) {
                l = GLES30.glGetUniformLocation(id, name);
                locs.put(name, l);
            }
            return l;
        }
    }

    private static final class Tex {
        final int name, w, h, iw, ih;

        Tex(int name, int w, int h, int iw, int ih) {
            this.name = name; this.w = w; this.h = h; this.iw = iw; this.ih = ih;
        }
    }

    private static final class Rt {
        final int fbo, tex, w, h;
        boolean cleared;

        Rt(int fbo, int tex, int w, int h) {
            this.fbo = fbo; this.tex = tex; this.w = w; this.h = h;
        }
    }

    /** Everything below runs on the GL thread, which owns the EGL context and paces itself. */
    private final class Renderer {

        private final Map<String, Prog> progs = new HashMap<>();
        private final Map<String, Tex> texes = new HashMap<>();
        private final Map<String, Rt> rts = new HashMap<>();
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private final float[] vp = new float[16], ident = new float[16], model = new float[16], mvp = new float[16];
        private final float[] res = new float[4], screen = new float[3], pointer = new float[2];
        private final float[] parallax = new float[2], drift = new float[2], texel = new float[2];
        private final float[] half = new float[2], fit = new float[2], last = new float[2];
        private final float[] oneTexel = new float[2];
        private final int[] one = new int[1], scratch = new int[16];
        private final float[] frames = new float[600], cpu = new float[600];
        private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        private EGLSurface sfc = EGL14.EGL_NO_SURFACE;
        private EGLConfig cfg;
        private ScenePlan built;
        private Prog present;
        private int[] vbos;
        private int fsVbo, blank, frameTex;
        private int surfW, surfH, mainW, mainH;
        private final float[] probe = new float[256];
        private float canvasFit = 1f, time, parX, parY, daytime;
        private long lastFrame, windowStart, requested, probeStart;
        private int samples, drawn, skipped, probed, step, boundFbo = -1, stamp;
        private boolean current, rendering, broken, lost, shown, mainDirty, needFrame, warm;

        private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
            @Override public void doFrame(long nanos) {
                if (!rendering) return;
                Choreographer.getInstance().postFrameCallback(this);
                // Take the vsync nearest the fps budget: 30 fps is every other vsync at 60 Hz.
                if (requested == 0 || nanos - requested >= period - 8_000_000L) {
                    requested = nanos;
                    frame();
                }
            }
        };

        // ------------------------------------------------------------ lifecycle

        void attach(Surface win, int w, int h) {
            if (broken || !win.isValid()) return;
            detach();
            surfW = w;
            surfH = h;
            if (ctx == EGL14.EGL_NO_CONTEXT && !context()) return;
            sfc = EGL14.eglCreateWindowSurface(dpy, cfg, win, new int[]{EGL14.EGL_NONE}, 0);
            if (sfc == null || sfc == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(dpy, sfc, sfc, ctx)) {
                sfc = EGL14.EGL_NO_SURFACE;
                broken = true;
                fail("cannot make the scene surface current: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return;
            }
            current = true;
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glDisable(GLES30.GL_DITHER);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
            lastFrame = windowStart = 0;
            samples = 0;
            if (running) resume();
            else frame();
        }

        private boolean context() {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, version, 0, version, 1)) {
                broken = true;
                fail("no EGL display");
                return false;
            }
            int[] want = {
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[8];
            int[] found = new int[1];
            if (!EGL14.eglChooseConfig(dpy, want, 0, configs, 0, configs.length, found, 0) || found[0] == 0) {
                broken = true;
                fail("no OpenGL ES 3.0 EGL config");
                return false;
            }
            // An opaque surface is what lets the display controller put this layer on a plane
            // instead of asking the GPU to blend Home's whole window over it.
            cfg = configs[0];
            int[] alpha = new int[1];
            for (int i = 0; i < found[0]; i++) {
                if (EGL14.eglGetConfigAttrib(dpy, configs[i], EGL14.EGL_ALPHA_SIZE, alpha, 0)
                        && alpha[0] == 0) {
                    cfg = configs[i];
                    break;
                }
            }
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                ctx = EGL14.EGL_NO_CONTEXT;
                broken = true;
                fail("no OpenGL ES 3.0 context: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return false;
            }
            generation++;
            return true;
        }

        void detach() {
            halt();
            if (sfc != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(dpy, sfc);
                sfc = EGL14.EGL_NO_SURFACE;
            }
            current = false;
            shown = false;
        }

        void resume() {
            if (rendering || !current || broken) return;
            rendering = true;
            requested = 0;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(tick);
        }

        void halt() {
            rendering = false;
            Choreographer.getInstance().removeFrameCallback(tick);
        }

        /** The plan arrived: draw one frame even while paused, so the backdrop is not black. */
        void wake() {
            if (current && !rendering && !broken) frame();
        }

        void shutdown() {
            detach();
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(dpy);
            }
            ctx = EGL14.EGL_NO_CONTEXT;
            dpy = EGL14.EGL_NO_DISPLAY;
            pending.clear();
        }

        // ------------------------------------------------------------ the frame

        private void frame() {
            if (!current || broken) return;
            long now = System.nanoTime();
            ScenePlan p = plan;
            if (p == null || (built != p && !build(p))) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glViewport(0, 0, Math.max(1, surfW), Math.max(1, surfH));
                GLES30.glClearColor(0f, 0f, 0f, 1f);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                swap();
                return;
            }
            while (!pending.isEmpty()) pending.poll().run();
            boolean measure = lastFrame != 0;
            float dt = measure ? Math.min(0.25f, (now - lastFrame) / 1e9f) : 0f;
            float interval = measure ? (now - lastFrame) / 1e6f : 0f;
            lastFrame = now;
            time += dt;
            advance(p, dt);

            for (Rt r : rts.values()) r.cleared = false;
            // main is cleared before any draw, so a pass that samples fullframe first in the
            // frame sees the cleared frame rather than the one before it.
            Rt main = rts.get(ScenePlan.MAIN);
            if (main == null) return;
            target(main);
            main.cleared = true;
            mainDirty = true;
            GLES30.glClearColor(p.clear[0], p.clear[1], p.clear[2], p.clear[3]);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            stamp++;
            drawn = 0;
            skipped = 0;
            for (int i = 0; i < p.draws.size(); i++) draw(p, p.draws.get(i), vbos[i]);
            present();
            swap();
            if (!shown) {
                shown = true;
                ui.post(SceneBackdrop.this::uncover);
            }

            if (measure && samples < frames.length) {
                frames[samples] = interval;
                cpu[samples] = (System.nanoTime() - now) / 1e6f;
                samples++;
            }
            if (measure) probe(interval, now);
            if (windowStart == 0) windowStart = now;
            else if (now - windowStart >= LOG_NANOS) {
                report(now - windowStart);
                windowStart = now;
                samples = 0;
            }
        }

        /**
         * Two seconds of p95 frame time past the fps budget means this scene cannot hold the
         * frame rate at this size: render main and every target one step smaller. Never go back
         * up, so the backdrop does not oscillate between resolutions while Home is open.
         *
         * <p>One step per window, and the window after a step is thrown away like the first one:
         * remaking every target and re-uploading its texture stalls those frames, and judging the
         * new size by them took scenes from 1.0 to 0.5 in two windows without ever trying 0.75.
         */
        private void probe(float interval, long now) {
            if (probed < probe.length) probe[probed++] = interval;
            if (probeStart == 0) {
                probeStart = now;
                return;
            }
            if (now - probeStart < 2_000_000_000L) return;
            float budget = period / 1e6f;
            if (!warm) {
                // A warm-up window: shader compiles, texture uploads, or the targets just resized.
                warm = true;
            } else if (probed >= 8 && SceneMath.p95(probe, probed) > budget * 1.2f
                    && step < STEPS.length - 1) {
                step++;
                Log.i(TAG, String.format(Locale.ROOT,
                        "p95 over %.1f ms for two seconds: main and targets to %.2f of the fitted size",
                        budget, STEPS[step]));
                ScenePlan p = built;
                if (p != null && !sizes(p)) broken = true;
                lastFrame = windowStart = 0;
                samples = 0;
                warm = false;
            }
            probed = 0;
            probeStart = now;
        }

        private void swap() {
            if (EGL14.eglSwapBuffers(dpy, sfc)) return;
            int e = EGL14.eglGetError();
            if (!lost) {
                lost = true;
                Log.e(TAG, "eglSwapBuffers failed: 0x" + Integer.toHexString(e));
            }
            if (e == EGL14.EGL_CONTEXT_LOST || e == EGL14.EGL_BAD_SURFACE) {
                broken = true;
                halt();
                fail("lost the GL surface: 0x" + Integer.toHexString(e));
            }
        }

        private void advance(ScenePlan p, float dt) {
            long ms = System.currentTimeMillis();
            daytime = SceneMath.daytime(ms, TimeZone.getDefault().getOffset(ms));
            last[0] = pointer[0];
            last[1] = pointer[1];
            SceneMath.drift(time, SceneMath.DRIFT_AMPLITUDE, drift);
            pointer[0] = 0.5f + drift[0];
            pointer[1] = 0.5f + drift[1];
            parallax[0] = drift[0];
            parallax[1] = drift[1];
            float tx = 0f, ty = 0f;
            if (p.parallax.enabled) {
                tx = drift[0] * p.parallax.amount * p.canvasWidth;
                ty = drift[1] * p.parallax.amount * p.canvasHeight;
            }
            parX = SceneMath.lag(parX, tx, dt, p.parallax.delay);
            parY = SceneMath.lag(parY, ty, dt, p.parallax.delay);
        }

        private void draw(ScenePlan p, ScenePlan.Draw d, int vbo) {
            Prog prog = progs.get(d.program.id);
            Rt rt = rts.get(d.target.id);
            if (prog == null || !prog.ok || rt == null) {
                skipped++;
                return;
            }
            // A draw that writes main cannot sample it, so that one needs the snapshot; a draw
            // into any other target samples main itself, which saves a full-frame copy.
            if (d.fullframe && d.target.main && frameTex != 0 && mainDirty) copyFrame();
            target(rt);
            if (!rt.cleared) {
                rt.cleared = true;
                GLES30.glClearColor(0f, 0f, 0f, 0f);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            }
            blend(d.blend);
            GLES30.glUseProgram(prog.id);

            if (d.geometry == ScenePlan.Geometry.QUAD) {
                SceneMath.model(d.originX + parX * d.depthX, d.originY + parY * d.depthY,
                        d.scaleX, d.scaleY, d.angle, model);
                SceneMath.multiply(vp, model, mvp);
            } else {
                System.arraycopy(ident, 0, model, 0, 16);
                System.arraycopy(ident, 0, mvp, 0, 16);
            }
            matrix(prog, "g_ModelViewProjectionMatrix", mvp);
            matrix(prog, "g_ModelMatrix", model);
            if (prog.stamp != stamp) {
                prog.stamp = stamp;
                matrix(prog, "g_ViewProjectionMatrix", vp);
                float1(prog, "g_Time", time);
                float1(prog, "g_Daytime", daytime);
                vec(prog, "g_PointerPosition", pointer, 2);
                vec(prog, "g_PointerPositionLast", last, 2);
                vec(prog, "g_ParallaxPosition", parallax, 2);
            }

            int units = Math.max(d.textures.size(), prog.samplers);
            for (int u = 0; u < units && u < ScenePlan.MAX_TEXTURE_UNITS; u++) {
                int name = blank;
                res[0] = res[1] = res[2] = res[3] = 1f;
                if (u < d.textures.size()) {
                    ScenePlan.Bind b = d.textures.get(u);
                    if (b.kind == ScenePlan.Bind.Kind.TEXTURE) {
                        Tex t = texes.get(b.texture.id);
                        if (t != null) {
                            name = t.name;
                            res[0] = t.w; res[1] = t.h; res[2] = t.iw; res[3] = t.ih;
                        }
                    } else if (b.kind == ScenePlan.Bind.Kind.TARGET) {
                        Rt r = rts.get(b.target.id);
                        if (r != null) {
                            name = r.tex;
                            res[0] = res[2] = r.w; res[1] = res[3] = r.h;
                        }
                    } else if (b.kind == ScenePlan.Bind.Kind.FULLFRAME) {
                        Rt m = rts.get(ScenePlan.MAIN);
                        int src = d.target.main ? frameTex : m != null ? m.tex : 0;
                        if (src != 0) {
                            name = src;
                            res[0] = res[2] = mainW; res[1] = res[3] = mainH;
                        }
                    }
                }
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name);
                vec(prog, RESOLUTION[u], res, 4);
                oneTexel[0] = 1f / res[0];
                oneTexel[1] = 1f / res[1];
                vec(prog, TEXEL[u], oneTexel, 2);
            }

            for (int i = 0; i < d.uniforms.size(); i++) {
                ScenePlan.Uniform u = d.uniforms.get(i);
                int l = prog.loc(u.name);
                if (l < 0) continue;
                if (prog.integers.contains(u.name) && u.value.length <= 4) ints(l, u.value);
                else floats(l, u.value);
            }

            attribs(prog, vbo);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            drawn++;
            if (d.target.main) mainDirty = true;
        }

        private void target(Rt rt) {
            if (rt.fbo != boundFbo) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rt.fbo);
                boundFbo = rt.fbo;
            }
            GLES30.glViewport(0, 0, rt.w, rt.h);
        }

        private void present() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            boundFbo = 0;
            GLES30.glViewport(0, 0, Math.max(1, surfW), Math.max(1, surfH));
            GLES30.glDisable(GLES30.GL_BLEND);
            Rt main = rts.get(ScenePlan.MAIN);
            if (main == null) return;
            GLES30.glUseProgram(present.id);
            SceneMath.present(mainW, mainH, Math.max(1, surfW), Math.max(1, surfH), fit);
            vec(present, "u_Scale", fit, 2);
            GLES30.glUniform1i(present.loc("u_Tex"), 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, main.tex);
            attribs(present, fsVbo);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        /** {@code fullframe} is main as it stands right before the draw that samples it. */
        private void copyFrame() {
            Rt main = rts.get(ScenePlan.MAIN);
            if (main == null) return;
            mainDirty = false;
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, main.fbo);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex);
            GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, main.w, main.h);
        }

        private void report(long windowNanos) {
            int n = samples;
            if (n <= 0) return;
            float sum = 0f, cpuSum = 0f;
            for (int i = 0; i < n; i++) {
                sum += frames[i];
                cpuSum += cpu[i];
            }
            float[] copy = new float[n];
            System.arraycopy(frames, 0, copy, 0, n);
            Log.i(TAG, String.format(Locale.ROOT,
                    "frame avg %.1f ms p95 %.1f ms, cpu avg %.1f ms, %.1f fps (asked %d), "
                            + "%d draws, %d skipped, main %dx%d, surface %dx%d",
                    sum / n, SceneMath.p95(copy, n), cpuSum / n, n * 1e9f / windowNanos, fps,
                    drawn, skipped, mainW, mainH, surfW, surfH));
        }

        // ------------------------------------------------------------ building

        private boolean build(ScenePlan p) {
            canvasFit = SceneMath.downscale(p.canvasWidth, p.canvasHeight, MAX_W, MAX_H);
            SceneMath.identity(ident);
            SceneMath.ortho(p.canvasWidth, p.canvasHeight, vp);
            present = program("present", PRESENT_VS, PRESENT_FS, java.util.Collections.emptySet(), 0);
            if (!present.ok) {
                broken = true;
                fail("the present program does not compile on this device");
                return false;
            }
            blank = blank();
            fsVbo = buffer(SceneMath.fullscreen(1f, 1f));
            needFrame = false;
            vbos = new int[p.draws.size()];
            for (int i = 0; i < p.draws.size(); i++) {
                ScenePlan.Draw d = p.draws.get(i);
                boolean plainUv = d.texScaleX == 1f && d.texScaleY == 1f;
                vbos[i] = d.geometry == ScenePlan.Geometry.QUAD
                        ? buffer(SceneMath.quad(d.sizeX, d.sizeY, d.texScaleX, d.texScaleY))
                        : plainUv ? fsVbo : buffer(SceneMath.fullscreen(d.texScaleX, d.texScaleY));
                // Only a pass that writes main needs a snapshot of it to sample.
                needFrame |= d.fullframe && d.target.main;
                if (!progs.containsKey(d.program.id)) {
                    progs.put(d.program.id, program(d.program.id, d.program.vertex, d.program.fragment,
                            d.program.integers, d.program.samplers));
                }
            }
            if (!sizes(p)) return false;
            built = p;
            warm = false;
            probed = 0;
            probeStart = 0;
            reload();
            Log.i(TAG, "built main " + mainW + "x" + mainH + ", canvas fit " + canvasFit
                    + ", render scale " + STEPS[step]);
            return true;
        }

        /** (Re)makes main, the targets and the frame snapshot at the current render scale. */
        private boolean sizes(ScenePlan p) {
            float s = SceneMath.renderScale(p.canvasWidth, p.canvasHeight, MAX_W, MAX_H, step);
            mainW = SceneMath.px(p.canvasWidth, s);
            mainH = SceneMath.px(p.canvasHeight, s);
            for (Rt r : rts.values()) {
                GLES30.glDeleteFramebuffers(1, new int[]{r.fbo}, 0);
                GLES30.glDeleteTextures(1, new int[]{r.tex}, 0);
            }
            rts.clear();
            boundFbo = -1;
            for (ScenePlan.Target t : p.targets.values()) {
                Rt rt = target(t, s);
                if (rt != null) rts.put(t.id, rt);
            }
            if (rts.get(ScenePlan.MAIN) == null) {
                broken = true;
                fail("main " + mainW + "x" + mainH + " is not a complete framebuffer");
                return false;
            }
            if (frameTex != 0) GLES30.glDeleteTextures(1, new int[]{frameTex}, 0);
            frameTex = needFrame ? texture(mainW, mainH) : 0;
            screen[0] = mainW;
            screen[1] = mainH;
            screen[2] = (float) mainW / mainH;
            texel[0] = 1f / mainW;
            texel[1] = 1f / mainH;
            half[0] = texel[0] / 2f;
            half[1] = texel[1] / 2f;
            for (Prog prog : progs.values()) constants(prog);
            // The compositor scales the layer to the screen, so draw no more pixels than needed.
            int w = Math.round(viewW * STEPS[step]), h = Math.round(viewH * STEPS[step]);
            if (step > 0 && w > 0 && h > 0) ui.post(() -> fix(w, h));
            return true;
        }

        /** Uniforms that never change for a program: samplers, silent audio, screen metrics. */
        private void constants(Prog prog) {
            if (!prog.ok) return;
            GLES30.glUseProgram(prog.id);
            for (int i = 0; i < ScenePlan.MAX_TEXTURE_UNITS; i++) {
                int l = prog.loc(SAMPLER[i]);
                if (l >= 0) GLES30.glUniform1i(l, i);
                // The converter treats these as built-ins; identity keeps texture coordinates as they are.
                vec(prog, ROTATION[i], NO_ROTATION, 4);
                vec(prog, TRANSLATION[i], NO_TRANSLATION, 2);
            }
            vec(prog, "g_Screen", screen, 3);
            vec(prog, "g_TexelSize", texel, 2);
            vec(prog, "g_TexelSizeHalf", half, 2);
            for (int n : new int[]{16, 32, 64}) {
                silence(prog, "g_AudioSpectrum" + n + "Left", n);
                silence(prog, "g_AudioSpectrum" + n + "Right", n);
            }
        }

        private void silence(Prog prog, String name, int n) {
            int l = prog.loc(name);
            if (l < 0) l = prog.loc(name + "[0]");
            if (l >= 0) GLES30.glUniform1fv(l, n, ZEROS, 0);
        }

        private Prog program(String id, String vs, String fs, Set<String> integers, int samplers) {
            int v = shader(id, GLES30.GL_VERTEX_SHADER, vs), f = shader(id, GLES30.GL_FRAGMENT_SHADER, fs);
            int p = GLES30.glCreateProgram();
            boolean ok = v != 0 && f != 0 && p != 0;
            if (ok) {
                GLES30.glAttachShader(p, v);
                GLES30.glAttachShader(p, f);
                GLES30.glLinkProgram(p);
                GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, one, 0);
                ok = one[0] != 0;
                if (!ok) Log.e(TAG, "program " + id + " does not link: " + GLES30.glGetProgramInfoLog(p));
            }
            if (v != 0) GLES30.glDeleteShader(v);
            if (f != 0) GLES30.glDeleteShader(f);
            return new Prog(p, ok, samplers, integers);
        }

        private int shader(String id, int type, String src) {
            int s = GLES30.glCreateShader(type);
            if (s == 0) return 0;
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, one, 0);
            if (one[0] != 0) return s;
            Log.e(TAG, "program " + id + ": " + (type == GLES30.GL_VERTEX_SHADER ? "vertex" : "fragment")
                    + " shader does not compile: " + GLES30.glGetShaderInfoLog(s));
            GLES30.glDeleteShader(s);
            return 0;
        }

        private int buffer(float[] verts) {
            ByteBuffer b = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder());
            b.asFloatBuffer().put(verts).position(0);
            GLES30.glGenBuffers(1, one, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, one[0]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.length * 4, b, GLES30.GL_STATIC_DRAW);
            return one[0];
        }

        private int texture(int w, int h) {
            GLES30.glGenTextures(1, one, 0);
            int name = one[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            return name;
        }

        private int blank() {
            int name = texture(1, 1);
            ByteBuffer px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            px.put(new byte[]{0, 0, 0, 0}).rewind();
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px);
            return name;
        }

        private Rt target(ScenePlan.Target t, float s) {
            int w = SceneMath.px(t.width, s), h = SceneMath.px(t.height, s);
            int tex = texture(w, h);
            GLES30.glGenFramebuffers(1, one, 0);
            int fbo = one[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, tex, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) return new Rt(fbo, tex, w, h);
            Log.e(TAG, "target " + t.id + " " + w + "x" + h + " is incomplete: 0x" + Integer.toHexString(status));
            GLES30.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            GLES30.glDeleteTextures(1, new int[]{tex}, 0);
            return null;
        }

        void upload(int gen, ScenePlan.Texture t, ByteBuffer px, int w, int h, int iw, int ih) {
            if (gen != generation || broken) return;
            if (!current || built == null) {
                pending.add(() -> upload(gen, t, px, w, h, iw, ih));
                return;
            }
            int name = texture(w, h);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px);
            int wrap = t.wrap == ScenePlan.Wrap.REPEAT ? GLES30.GL_REPEAT
                    : t.wrap == ScenePlan.Wrap.MIRROR ? GLES30.GL_MIRRORED_REPEAT : GLES30.GL_CLAMP_TO_EDGE;
            int filter = t.filter == ScenePlan.Filter.NEAREST ? GLES30.GL_NEAREST : GLES30.GL_LINEAR;
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
            Tex old = texes.put(t.id, new Tex(name, w, h, iw, ih));
            if (old != null) GLES30.glDeleteTextures(1, new int[]{old.name}, 0);
        }

        void free() {
            if (current) {
                for (Prog p : progs.values()) if (p.id != 0) GLES30.glDeleteProgram(p.id);
                if (present != null && present.id != 0) GLES30.glDeleteProgram(present.id);
                for (Tex t : texes.values()) GLES30.glDeleteTextures(1, new int[]{t.name}, 0);
                for (Rt r : rts.values()) {
                    GLES30.glDeleteFramebuffers(1, new int[]{r.fbo}, 0);
                    GLES30.glDeleteTextures(1, new int[]{r.tex}, 0);
                }
                if (blank != 0) GLES30.glDeleteTextures(1, new int[]{blank}, 0);
                if (frameTex != 0) GLES30.glDeleteTextures(1, new int[]{frameTex}, 0);
                if (vbos != null) for (int v : vbos) if (v != 0 && v != fsVbo) GLES30.glDeleteBuffers(1, new int[]{v}, 0);
                if (fsVbo != 0) GLES30.glDeleteBuffers(1, new int[]{fsVbo}, 0);
            }
            progs.clear();
            texes.clear();
            rts.clear();
            pending.clear();
            present = null;
            built = null;
            vbos = null;
            fsVbo = blank = frameTex = 0;
        }

        // ------------------------------------------------------------ GL state

        private void attribs(Prog prog, int vbo) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            if (prog.pos >= 0) {
                GLES30.glEnableVertexAttribArray(prog.pos);
                GLES30.glVertexAttribPointer(prog.pos, 3, GLES30.GL_FLOAT, false, STRIDE, 0);
            }
            if (prog.uv >= 0) {
                GLES30.glEnableVertexAttribArray(prog.uv);
                GLES30.glVertexAttribPointer(prog.uv, 2, GLES30.GL_FLOAT, false, STRIDE, 12);
            }
        }

        private void blend(ScenePlan.Blend b) {
            if (b == ScenePlan.Blend.DISABLED) {
                GLES30.glDisable(GLES30.GL_BLEND);
                return;
            }
            GLES30.glEnable(GLES30.GL_BLEND);
            if (b == ScenePlan.Blend.ADDITIVE) GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            else if (b == ScenePlan.Blend.MULTIPLY) GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO);
            else GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        }

        private void matrix(Prog prog, String name, float[] m) {
            int l = prog.loc(name);
            if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, m, 0);
        }

        private void float1(Prog prog, String name, float v) {
            int l = prog.loc(name);
            if (l >= 0) GLES30.glUniform1f(l, v);
        }

        private void vec(Prog prog, String name, float[] v, int n) {
            int l = prog.loc(name);
            if (l < 0) return;
            if (n == 2) GLES30.glUniform2fv(l, 1, v, 0);
            else if (n == 3) GLES30.glUniform3fv(l, 1, v, 0);
            else GLES30.glUniform4fv(l, 1, v, 0);
        }

        private void floats(int l, float[] v) {
            switch (v.length) {
                case 1: GLES30.glUniform1fv(l, 1, v, 0); break;
                case 2: GLES30.glUniform2fv(l, 1, v, 0); break;
                case 3: GLES30.glUniform3fv(l, 1, v, 0); break;
                case 4: GLES30.glUniform4fv(l, 1, v, 0); break;
                case 9: GLES30.glUniformMatrix3fv(l, 1, false, v, 0); break;
                default: GLES30.glUniformMatrix4fv(l, 1, false, v, 0); break;
            }
        }

        private void ints(int l, float[] v) {
            for (int i = 0; i < v.length && i < scratch.length; i++) scratch[i] = Math.round(v[i]);
            switch (v.length) {
                case 1: GLES30.glUniform1iv(l, 1, scratch, 0); break;
                case 2: GLES30.glUniform2iv(l, 1, scratch, 0); break;
                case 3: GLES30.glUniform3iv(l, 1, scratch, 0); break;
                default: GLES30.glUniform4iv(l, 1, scratch, 0); break;
            }
        }
    }
}
