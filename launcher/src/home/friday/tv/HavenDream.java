package home.friday.tv;

import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import home.friday.wallpaper.Backdrop;
import home.friday.wallpaper.WallpaperLibrary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: the wallpaper chosen in Friday Home, or Haven's nature photos with a large clock in
 * the spirit of Apple TV's when the choice is Photos, gone, or fails to play.
 */
public class HavenDream extends DreamService {

    private static final long WALL_INTERVAL_MS = 30_000;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Store store;
    private WallpaperLibrary wallpapers;
    private Backdrop backdrop;
    private FrameLayout backdropBox;
    private View scrim, credit, clock;
    private TextView creditTitle, creditLine;
    private boolean attached, dreaming, photos;
    /** A wallpaper pushed from the Mac takes effect without waiting for the dream to restart. */
    private final SetWallpaper.Listener pushed = id -> {
        if (!attached) return;
        store.wallpaper = id;
        wallpapers.scan(bg, this::chooseBackdrop);
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        setInteractive(false);
        setFullscreen(true);
        Ui.init(this);
        store = new Store(this);
        wallpapers = new WallpaperLibrary(this);
        int side = Ui.dp(this, 56);

        FrameLayout root = new FrameLayout(this);
        backdropBox = new FrameLayout(this);
        root.addView(backdropBox, new FrameLayout.LayoutParams(-1, -1));

        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 200), Gravity.BOTTOM));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        creditTitle = Ui.text(this, "", 22, Ui.medium);
        creditLine = Ui.text(this, "", 12, Ui.regular);
        creditLine.setAlpha(0.7f);
        box.addView(creditTitle);
        box.addView(creditLine);
        credit = box;
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = side;
        clp.bottomMargin = Ui.dp(this, 44);
        root.addView(box, clp);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        Ui.style(time, 56, Ui.light);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        tlp.rightMargin = side;
        tlp.bottomMargin = Ui.dp(this, 36);
        clock = time;
        root.addView(time, tlp);

        showOverlay(false);
        setContentView(root);
        SetWallpaper.listen(pushed);
        wallpapers.scan(bg, this::chooseBackdrop);
    }

    /** The wallpaper Friday Home shows, or its photos when there is none, it is gone, or it broke. */
    private void chooseBackdrop() {
        if (!attached) return;
        String id = store.wallpaper;
        WallpaperLibrary.Entry e = id.isEmpty() ? null : wallpapers.byId(id);
        if (e == null) {
            usePhotos();
            return;
        }
        photos = false;
        use(WallpaperLibrary.backdrop(this, e, this::usePhotos));
    }

    private void usePhotos() {
        if (!attached || photos) return;
        photos = true;
        use(new Slideshow(this, WALL_INTERVAL_MS, (t, l, color) -> {
            creditTitle.setText(t);
            creditLine.setText(l);
            credit.setAlpha(0f);
            credit.animate().alpha(1f).setDuration(1800).start();
        }));
    }

    private void use(Backdrop next) {
        Backdrop old = backdrop;
        backdrop = next;
        if (old != null) {
            backdropBox.removeView(old.view());
            old.release();
        }
        next.setBlur(0f);
        backdropBox.addView(next.view(), new FrameLayout.LayoutParams(-1, -1));
        showOverlay(photos);
        if (dreaming) next.start();
    }

    /** Clock and credit belong to the photos; a wallpaper is shown on its own. */
    private void showOverlay(boolean show) {
        int v = show ? View.VISIBLE : View.GONE;
        scrim.setVisibility(v);
        credit.setVisibility(v);
        clock.setVisibility(v);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (backdrop != null) backdrop.start();
    }

    @Override public void onDreamingStopped() {
        dreaming = false;
        if (backdrop != null) backdrop.pause();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        attached = false;
        SetWallpaper.forget(pushed);
        bg.shutdownNow();
        if (backdrop != null) {
            backdrop.release();
            backdrop = null;
        }
        super.onDetachedFromWindow();
    }
}
