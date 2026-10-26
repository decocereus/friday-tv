package home.friday.wallpaper;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;

/**
 * A wallpaper bundle's video, looping and muted, cropped to fill the screen. The decoder only
 * exists between {@link #start()} and {@link #pause()}, so an app opened from Home gets the TV's
 * hardware decoder back. The bundle's preview covers the surface until the first frame arrives.
 * A SurfaceView, not a TextureView: the TV's compositor scales and shows the video itself, so
 * Home's window is not redrawn and GPU-composited to 4K for every video frame.
 */
final class VideoBackdrop implements Backdrop {

    private static final float MAX_SCRIM = 0.55f;

    private final FrameLayout box;
    private final SurfaceView video;
    private final ImageView still;
    private final View scrim;
    private final File file;
    private final Runnable onFailure;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private SurfaceHolder surface;
    private int vw, vh;
    private boolean running, drawn, failed;
    private float blur;

    VideoBackdrop(Context c, WallpaperLibrary.Entry e, Runnable onFailure) {
        file = e.video;
        vw = e.width;
        vh = e.height;
        this.onFailure = onFailure;

        box = new FrameLayout(c);
        box.setBackgroundColor(Color.BLACK);
        video = new SurfaceView(c);
        box.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fit();
        });
        still = new ImageView(c);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setImageBitmap(BitmapFactory.decodeFile(e.preview.getAbsolutePath()));
        box.addView(still, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(c);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        box.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                surface = h;
                if (running) open();
            }

            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }

            @Override public void surfaceDestroyed(SurfaceHolder h) {
                stop();
                surface = null;
            }
        });
    }

    @Override public View view() {
        return box;
    }

    @Override public void start() {
        running = true;
        if (failed || player != null) return;
        // The surface holds the frame it was paused on, which may be stale: cover it again.
        drawn = false;
        still.animate().cancel();
        still.setAlpha(1f);
        open();
    }

    @Override public void pause() {
        running = false;
        stop();
    }

    @Override public void release() {
        running = false;
        stop();
        surface = null;
        still.setImageDrawable(null);
    }

    /** 0 = the video as it is, 1 = a dark scrim over it, where photos would be blurred. */
    @Override public void setBlur(float f) {
        blur = f;
        scrim.setAlpha(Math.max(0f, Math.min(1f, f)) * MAX_SCRIM);
    }

    @Override public float getBlur() {
        return blur;
    }

    private void open() {
        if (player != null || surface == null || failed) return;
        if (!file.isFile()) {
            fail();
            return;
        }
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDisplay(surface);
            p.setDataSource(file.getAbsolutePath());
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnErrorListener((mp, what, extra) -> {
                fail();
                return true;
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !drawn) {
                    drawn = true;
                    still.animate().alpha(0f).setDuration(260).start();
                }
                return false;
            });
            p.setOnVideoSizeChangedListener((mp, w, h) -> {
                if (w <= 0 || h <= 0) return;
                vw = w;
                vh = h;
                fit();
            });
            p.setOnPreparedListener(mp -> {
                fit();
                if (running) mp.start();
            });
            p.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException e) {
            fail();
        }
    }

    private void stop() {
        MediaPlayer p = player;
        player = null;
        if (p == null) return;
        p.setOnErrorListener(null);
        p.setOnPreparedListener(null);
        p.setOnInfoListener(null);
        p.setOnVideoSizeChangedListener(null);
        try {
            p.reset();
        } catch (IllegalStateException ignored) { }
        p.release();
    }

    /** Size the surface to the video's aspect, larger than the screen on one axis, centred. */
    private void fit() {
        int w = box.getWidth(), h = box.getHeight();
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return;
        float s = Math.max(w / (float) vw, h / (float) vh);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        int fw = Math.round(vw * s), fh = Math.round(vh * s);
        if (lp.width == fw && lp.height == fh) return;
        lp.width = fw;
        lp.height = fh;
        video.setLayoutParams(lp);
    }

    private void fail() {
        if (failed) return;
        failed = true;
        stop();
        if (onFailure != null) ui.post(onFailure);
    }
}
