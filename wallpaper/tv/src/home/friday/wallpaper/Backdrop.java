package home.friday.wallpaper;

import android.view.View;

/** What Friday Home draws behind its rows: the photo slideshow, a video, or a rendered scene. */
public interface Backdrop {
    View view();

    /** Home is visible: animate or play. */
    void start();

    /** Home is hidden: stop decoding and rendering, keep resources for a quick start(). */
    void pause();

    /** Free everything; the backdrop is not used again. */
    void release();

    /** 0 = sharp, 1 = fully softened behind overlays (blur for photos, a dark scrim otherwise). */
    void setBlur(float f);

    float getBlur();
}
