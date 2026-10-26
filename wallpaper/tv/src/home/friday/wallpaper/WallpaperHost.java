package home.friday.wallpaper;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Everything the wallpaper module needs from the app it lives in. Friday Home implements it; a
 * standalone wallpaper app would implement the same ten methods and get the same picker. Nothing
 * here is about wallpapers: it is the host's selection, its focus hand-off and its look.
 */
public interface WallpaperHost {

    int LIGHT = 0, REGULAR = 1, MEDIUM = 2, SEMIBOLD = 3;

    /** Views are built against it and the overlays attach to its window. */
    Activity activity();

    /** The bundle id showing now, "" for the host's own backdrop (Friday Home's photos). */
    String selected();

    /** The user chose one; "" asks for the host's own backdrop. */
    void select(String id);

    /** A bundle arrived or went away: rescan the library and refresh whatever is open. */
    void rescanLibrary();

    void toast(String text);

    /** An overlay took the screen: stop focus reaching the host's own rows. */
    void overlayOpened();

    /** The overlay closed: put focus back where the host wants it. */
    void overlayClosed();

    /** The focus click. */
    void tick();

    /** The host's focus animation for one of its tiles: lift, tilt and sheen. */
    void focus(View tile, boolean has, float scale);

    /** Makes {@code tile} look like one of the host's: corners, elevation and highlight layers. */
    void style(FrameLayout tile, int wDp, int hDp);

    /** A label in the host's type, {@code weight} one of {@link #LIGHT}..{@link #SEMIBOLD}. */
    TextView text(String s, int sp, int weight);

    /** Focus-ring colour, for the bits of chrome that are not tiles. */
    int accent();
}
