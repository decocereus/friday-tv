package home.friday.wallpaper;

/** Plain maths for the scene renderer: matrices, orientation, downscaling, pointer drift. */
final class SceneMath {

    /** Render scales tried in order when a scene cannot hold its frame rate; never reversed. */
    static final float[] STEPS = {1f, 0.75f, 0.5f};

    /** Lissajous amplitude of the fake pointer, in 0..1 texture-space units. */
    static final float DRIFT_AMPLITUDE = 0.08f;
    private static final double DRIFT_X_PERIOD = 37.0, DRIFT_Y_PERIOD = 53.0;

    private SceneMath() { }

    static void identity(float[] out) {
        for (int i = 0; i < 16; i++) out[i] = 0f;
        out[0] = out[5] = out[10] = out[15] = 1f;
    }

    /**
     * Canvas orthographic projection, column-major. Canvas units are y-up with the origin at the
     * bottom-left, while every target stores its top row first, so top and bottom are swapped
     * here: canvas y = h lands on clip y = -1, which is row 0 of the target.
     */
    static void ortho(float w, float h, float[] out) {
        identity(out);
        out[0] = 2f / w;
        out[5] = -2f / h;
        out[10] = -1f;
        out[12] = -1f;
        out[13] = 1f;
    }

    /** translate(tx, ty) * rotateZ(angle) * scale(sx, sy), column-major. */
    static void model(float tx, float ty, float sx, float sy, float angle, float[] out) {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        identity(out);
        out[0] = cos * sx;
        out[1] = sin * sx;
        out[4] = -sin * sy;
        out[5] = cos * sy;
        out[12] = tx;
        out[13] = ty;
    }

    /** out = a * b, column-major 4x4; {@code out} must not alias {@code a} or {@code b}. */
    static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            int o = col * 4;
            float b0 = b[o], b1 = b[o + 1], b2 = b[o + 2], b3 = b[o + 3];
            for (int row = 0; row < 4; row++) {
                out[o + row] = a[row] * b0 + a[4 + row] * b1 + a[8 + row] * b2 + a[12 + row] * b3;
            }
        }
    }

    /** Factor that fits a canvas into {@code maxW} x {@code maxH}, never magnifying. */
    static float downscale(int w, int h, int maxW, int maxH) {
        return Math.min(1f, Math.min((float) maxW / w, (float) maxH / h));
    }

    /** The canvas-fitting factor at render scale {@code level}, the floor being half of it. */
    static float renderScale(int w, int h, int maxW, int maxH, int level) {
        return downscale(w, h, maxW, maxH) * STEPS[Math.max(0, Math.min(STEPS.length - 1, level))];
    }

    /** Largest power-of-two BitmapFactory sample size that does not shrink past {@code s}. */
    static int sample(float s) {
        int n = 1;
        while (s <= 0.5f / n && n < 1 << 12) n *= 2;
        return n;
    }

    static int px(int v, float s) {
        return Math.max(1, Math.round(v * s));
    }

    /** Fake pointer offset, centred on 0: two slow sines with incommensurate periods. */
    static void drift(double t, float amp, float[] out) {
        out[0] = (float) (amp * Math.sin(2 * Math.PI * t / DRIFT_X_PERIOD));
        out[1] = (float) (amp * Math.sin(2 * Math.PI * t / DRIFT_Y_PERIOD + 1.3));
    }

    /** First-order lag towards {@code target}, frame-rate independent. */
    static float lag(float cur, float target, float dt, float delay) {
        if (delay <= 0f || dt <= 0f) return target;
        return cur + (target - cur) * (float) (1 - Math.exp(-dt / delay));
    }

    /** Clip-space scale that aspect-fills a surface with the main target, cropping the overflow. */
    static void present(int mainW, int mainH, int surfW, int surfH, float[] out) {
        float main = (float) mainW / mainH, surf = (float) surfW / surfH;
        out[0] = Math.max(1f, main / surf);
        out[1] = Math.max(1f, surf / main);
    }

    /**
     * Four triangle-strip corners as (x, y, z, u, v) in model units, texture coordinate (0, 0) on
     * the corner with the largest y, since canvas y is up but texture v = 0 is the top row.
     * {@code su, sv} scale the texture coordinates, which addresses the image inside a padded
     * texture: the image sits against v = 0, the top row.
     */
    static float[] quad(float w, float h, float su, float sv) {
        float x = w / 2f, y = h / 2f;
        return new float[]{
                -x, y, 0f, 0f, 0f,
                x, y, 0f, su, 0f,
                -x, -y, 0f, 0f, sv,
                x, -y, 0f, su, sv};
    }

    /** Fullscreen strip in clip space, v growing with y so that v = 0 is row 0 of the target. */
    static float[] fullscreen(float su, float sv) {
        return new float[]{
                -1f, -1f, 0f, 0f, 0f,
                1f, -1f, 0f, su, 0f,
                -1f, 1f, 0f, 0f, sv,
                1f, 1f, 0f, su, sv};
    }

    /** Fraction of the local day, 0..1. */
    static float daytime(long epochMs, int offsetMs) {
        long day = 86_400_000L;
        long local = ((epochMs + offsetMs) % day + day) % day;
        return local / (float) day;
    }

    /** 95th percentile of the first {@code n} entries of {@code samples}; sorts them in place. */
    static float p95(float[] samples, int n) {
        if (n <= 0) return 0f;
        java.util.Arrays.sort(samples, 0, n);
        return samples[Math.min(n - 1, Math.max(0, (int) Math.ceil(0.95 * n) - 1))];
    }
}
