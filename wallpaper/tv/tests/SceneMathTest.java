package home.friday.wallpaper;

public final class SceneMathTest {
    private static int checks;
    private static void check(boolean result, String name) {
        checks++;
        if (!result) throw new AssertionError(name);
    }
    private static void close(float got, float want, String name) {
        check(Math.abs(got - want) < 1e-4f, name + " (got " + got + ", want " + want + ")");
    }
    /** out = m * (x, y, 0, 1) for a column-major 4x4. */
    private static float[] apply(float[] m, float x, float y) {
        return new float[]{m[0] * x + m[4] * y + m[12], m[1] * x + m[5] * y + m[13]};
    }
    public static void main(String[] args) {
        float[] m = new float[16], n = new float[16], o = new float[16];

        SceneMath.identity(m);
        float[] p = apply(m, 7f, -3f);
        close(p[0], 7f, "identity x");
        close(p[1], -3f, "identity y");

        // The canvas projection carries the y flip: canvas y = 0 is the bottom and must land on
        // clip y = +1, while canvas y = h (the top) lands on clip y = -1, which is target row 0.
        SceneMath.ortho(1920f, 1080f, m);
        p = apply(m, 0f, 0f);
        close(p[0], -1f, "ortho left edge");
        close(p[1], 1f, "ortho canvas bottom to clip top");
        p = apply(m, 1920f, 1080f);
        close(p[0], 1f, "ortho right edge");
        close(p[1], -1f, "ortho canvas top to clip bottom");
        p = apply(m, 960f, 540f);
        close(p[0], 0f, "ortho centre x");
        close(p[1], 0f, "ortho centre y");

        SceneMath.model(100f, 200f, 2f, 3f, 0f, n);
        p = apply(n, 1f, 1f);
        close(p[0], 102f, "model scale then translate x");
        close(p[1], 203f, "model scale then translate y");
        SceneMath.model(0f, 0f, 1f, 1f, (float) Math.PI / 2f, n);
        p = apply(n, 1f, 0f);
        close(p[0], 0f, "quarter turn x");
        close(p[1], 1f, "quarter turn is counter-clockwise in canvas units");

        SceneMath.ortho(1920f, 1080f, m);
        SceneMath.model(960f, 540f, 1f, 1f, 0f, n);
        SceneMath.multiply(m, n, o);
        p = apply(o, -960f, 540f);
        close(p[0], -1f, "centred full-canvas quad covers the left edge");
        close(p[1], -1f, "the quad's top corner lands on target row 0");
        p = apply(o, 960f, -540f);
        close(p[0], 1f, "quad right edge");
        close(p[1], 1f, "quad bottom corner");

        float[] quad = SceneMath.quad(1920f, 1080f, 1f, 1f);
        check(quad.length == 20, "four corners of five floats");
        check(quad[0] == -960f && quad[1] == 540f, "first corner is model top-left");
        check(quad[3] == 0f && quad[4] == 0f, "texture (0, 0) sits on the largest y");
        check(quad[15] == 960f && quad[16] == -540f, "last corner is model bottom-right");
        check(quad[18] == 1f && quad[19] == 1f, "texture (1, 1) sits on the smallest y");
        float[] padded = SceneMath.quad(1920f, 1080f, 1920f / 2048f, 1080f / 2048f);
        check(padded[3] == 0f && padded[4] == 0f, "a padded image still starts at (0, 0)");
        check(Math.abs(padded[18] - 1920f / 2048f) < 1e-6f && Math.abs(padded[19] - 1080f / 2048f) < 1e-6f,
                "a padded image ends inside its texture");
        float[] fs = SceneMath.fullscreen(1f, 1f);
        check(fs[1] == -1f && fs[4] == 0f, "fullscreen pairs v = 0 with clip y = -1");
        check(fs[16] == 1f && fs[19] == 1f, "fullscreen pairs v = 1 with clip y = +1");

        close(SceneMath.downscale(1920, 1080, 1920, 1080), 1f, "1080p canvas is not resized");
        close(SceneMath.downscale(1280, 720, 1920, 1080), 1f, "a small canvas is never magnified");
        close(SceneMath.downscale(3840, 2160, 1920, 1080), 0.5f, "4k canvas halves");
        close(SceneMath.downscale(1080, 1920, 1920, 1080), 1080f / 1920f, "portrait canvas fits its height");
        check(SceneMath.px(3840, 0.5f) == 1920 && SceneMath.px(2160, 0.5f) == 1080, "scaled main size");
        check(SceneMath.px(1, 0.1f) == 1, "a target never collapses to zero");
        close(SceneMath.renderScale(1920, 1080, 1920, 1080, 0), 1f, "1080p canvas at full render scale");
        close(SceneMath.renderScale(1920, 1080, 1920, 1080, 1), 0.75f, "one step down");
        close(SceneMath.renderScale(1920, 1080, 1920, 1080, 2), 0.5f, "the floor is half");
        close(SceneMath.renderScale(1920, 1080, 1920, 1080, 7), 0.5f, "the floor holds past the last step");
        close(SceneMath.renderScale(3840, 2160, 1920, 1080, 2), 0.25f, "a 4k canvas floors at a quarter");
        check(SceneMath.px(3840, SceneMath.renderScale(3840, 2160, 1920, 1080, 2)) == 960
                && SceneMath.px(2160, SceneMath.renderScale(3840, 2160, 1920, 1080, 2)) == 540,
                "a 4k canvas renders main at 960x540 on the floor");
        check(SceneMath.px(1920, SceneMath.renderScale(1920, 1080, 1920, 1080, 1)) == 1440,
                "a 1080p canvas renders main at 1440x810 after one step");
        check(SceneMath.sample(1f) == 1, "no subsampling at full size");
        check(SceneMath.sample(0.5f) == 2, "half size subsamples by two");
        check(SceneMath.sample(0.6f) == 1, "never subsample past the main factor");
        check(SceneMath.sample(0.25f) == 4, "quarter size subsamples by four");
        check(SceneMath.sample(0.2f) == 4, "subsampling stays a power of two");

        float[] fit = new float[2];
        SceneMath.present(1920, 1080, 1920, 1080, fit);
        close(fit[0], 1f, "exact fit x");
        close(fit[1], 1f, "exact fit y");
        SceneMath.present(1920, 1080, 2560, 1080, fit);
        close(fit[0], 1f, "wider screen keeps full width");
        close(fit[1], (2560f / 1080f) / (1920f / 1080f), "wider screen crops top and bottom");
        SceneMath.present(1080, 1920, 1920, 1080, fit);
        close(fit[0], 1f, "portrait canvas keeps full width");
        check(fit[1] > 3f, "portrait canvas overflows far above and below");

        float[] d = new float[2], prev = new float[2];
        float maxStep = 0f, maxAmp = 0f;
        for (double t = 0; t < 400; t += 1 / 30.0) {
            SceneMath.drift(t, SceneMath.DRIFT_AMPLITUDE, d);
            maxAmp = Math.max(maxAmp, Math.max(Math.abs(d[0]), Math.abs(d[1])));
            if (t > 0) maxStep = Math.max(maxStep, Math.abs(d[0] - prev[0]) + Math.abs(d[1] - prev[1]));
            prev[0] = d[0];
            prev[1] = d[1];
        }
        check(maxAmp <= SceneMath.DRIFT_AMPLITUDE + 1e-6f, "drift stays inside its amplitude");
        check(maxStep < 0.001f, "drift moves smoothly at 30 fps (" + maxStep + " per frame)");
        SceneMath.drift(0, SceneMath.DRIFT_AMPLITUDE, d);
        SceneMath.drift(20, SceneMath.DRIFT_AMPLITUDE, prev);
        check(Math.abs(d[0] - prev[0]) > 0.01f, "drift actually moves over twenty seconds");

        close(SceneMath.lag(0f, 1f, 1f, 0f), 1f, "no delay snaps");
        check(SceneMath.lag(0f, 1f, 1f / 30f, 1.7f) < 0.03f, "a 1.7 s delay barely moves in one frame");
        float v = 0f;
        for (int i = 0; i < 300; i++) v = SceneMath.lag(v, 1f, 1f / 30f, 1.7f);
        check(v > 0.99f, "the lag reaches its target");

        close(SceneMath.daytime(0, 0), 0f, "midnight UTC");
        close(SceneMath.daytime(43_200_000L, 0), 0.5f, "noon UTC");
        close(SceneMath.daytime(0, 5 * 3600_000), 5f / 24f, "local offset applies");
        close(SceneMath.daytime(0, -5 * 3600_000), 19f / 24f, "negative offset wraps");

        float[] times = {10f, 1f, 5f, 100f, 2f, 3f, 4f, 6f, 7f, 8f};
        close(SceneMath.p95(times, 10), 100f, "p95 of ten samples is the worst");
        close(SceneMath.p95(new float[]{3f, 1f, 2f}, 3), 3f, "p95 of three samples");
        close(SceneMath.p95(new float[]{5f}, 0), 0f, "p95 of nothing");

        System.out.println(checks + " scene matrix, orientation, downscale, drift and timing checks passed");
    }
}
