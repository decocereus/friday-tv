package home.friday.wallpaper;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallpaper picker: Photos first, then every bundle on the TV, then the tile that opens the
 * Workshop browser. The host owns the selection and the focus hand-off; this owns the overlays.
 */
public final class WallpaperPicker {

    private static final String TAG_PREFIX = "wall:";
    private static final int WALL_W = 196, WALL_H = 110, WALL_COLS = 4, GAP = 22, SIDE = 48;

    private final WallpaperHost host;
    private final WallpaperLibrary library;
    private final Executor bg, img;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final FrameLayout overlay;
    private final ScrollView scroll;
    private final LinearLayout grid;
    private final TextView hint;
    /** Workshop calls take seconds; they must not sit behind a library scan on the host's queue. */
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private final WorkshopBrowser browser;
    private boolean open;

    public WallpaperPicker(WallpaperHost host, WallpaperLibrary library, Executor bg, Executor img) {
        this.host = host;
        this.library = library;
        this.bg = bg;
        this.img = img;

        overlay = new FrameLayout(host.activity());
        overlay.setBackgroundColor(0xFC0E1014);
        overlay.setVisibility(View.GONE);
        LinearLayout col = Tiles.column(host.activity());
        col.setPadding(0, dp(56), 0, 0);

        TextView title = host.text("Wallpaper", 34, WallpaperHost.SEMIBOLD);
        title.setPadding(dp(SIDE), 0, dp(SIDE), dp(6));
        col.addView(title);

        hint = host.text("", 13, WallpaperHost.REGULAR);
        hint.setAlpha(0.6f);
        hint.setPadding(dp(SIDE), 0, dp(SIDE), 0);
        col.addView(hint);

        scroll = new ScrollView(host.activity()) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
            @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setFocusable(false);
        grid = Tiles.column(host.activity());
        grid.setPadding(dp(SIDE), dp(26), dp(SIDE), dp(60));
        scroll.addView(grid);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        overlay.addView(col, Tiles.match());
        browser = new WorkshopBrowser(host, library, net, img, this);
    }

    /** Both overlays, for the host to add to its root on top of everything else, in order. */
    public View[] views() {
        return new View[]{overlay, browser.view()};
    }

    public boolean isOpen() {
        return open || browser.isOpen();
    }

    public void release() {
        net.shutdownNow();
    }

    public void open() {
        open = true;
        host.overlayOpened();
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200).start();
        fill();
    }

    /** Back: out of the browser onto the picker, or out of the picker onto Home. */
    public void close() {
        if (browser.isOpen()) {
            browser.close();
            return;
        }
        dismiss();
    }

    private void dismiss() {
        open = false;
        overlay.setVisibility(View.GONE);
        grid.removeAllViews();
        host.overlayClosed();
    }

    /**
     * The library changed under us. Only the overlay in front is rebuilt: refilling the picker
     * while the browser covers it moves focus to a tile nobody can see, and the next OK then lands
     * on the picker instead of the browser tile the user is looking at.
     */
    public void refresh() {
        if (open && !browser.isOpen()) fill();
        browser.refresh();
    }

    /** Menu or Info on a tile. Returns true when it took the key. */
    public boolean onKey(int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_INFO) return false;
        if (browser.isOpen()) return browser.onKey(keyCode);
        return offerRemoval(host.activity().getCurrentFocus());
    }

    /** The browser closed itself: the picker is underneath and wants focus back. */
    void backFromBrowser() {
        fill();
    }

    /** OK on a browser tile that is already on the TV. */
    void chooseFromBrowser(String id) {
        choose(id);
    }

    void offerRemovalById(String id) {
        View tile = grid.findViewWithTag(TAG_PREFIX + id);
        if (tile != null) {
            offerRemoval(tile);
            return;
        }
        WallpaperLibrary.Entry e = library.byId(id);
        if (e != null) confirmRemoval(e);
    }

    // ---------------------------------------------------------------- the grid

    /** Rebuilds the tiles and keeps the focused one, which a rescan may have replaced. */
    private void fill() {
        View was = host.activity().getCurrentFocus();
        Object tag = was != null ? was.getTag() : null;
        List<WallpaperLibrary.Entry> list = library.entries();
        hint.setText(list.isEmpty()
                ? "Nothing here yet. Browse Workshop to add one."
                : "OK  Use this wallpaper     Menu or hold OK  Remove from TV");
        grid.removeAllViews();
        LinearLayout r = null;
        // Photos, every bundle, then Browse Workshop at the end.
        for (int i = 0; i <= list.size() + 1; i++) {
            if (i % WALL_COLS == 0) {
                r = Tiles.row(host.activity());
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.bottomMargin = dp(20);
                grid.addView(r, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(GAP);
            r.addView(i == 0 ? photoCell()
                    : i <= list.size() ? bundleCell(list.get(i - 1))
                    : browseCell(), lp);
        }
        View keep = tag instanceof String ? grid.findViewWithTag(tag) : null;
        if (keep == null) keep = Tiles.firstTile(grid);
        if (keep != null) keep.requestFocus();
    }

    private View photoCell() {
        FrameLayout tile = tile("");
        tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3C4350, 0xFF1C2028}));
        TextView t = host.text("Photos", 20, WallpaperHost.MEDIUM);
        t.setGravity(Gravity.CENTER);
        tile.addView(t, Tiles.match());
        if (host.selected().isEmpty()) {
            tile.addView(Tiles.badge(host, "IN USE"), corner(Gravity.TOP | Gravity.END));
        }
        return cell(tile, "Photos", () -> choose(""));
    }

    private View bundleCell(WallpaperLibrary.Entry e) {
        FrameLayout tile = tile(e.id);
        tile.setBackgroundColor(0xFF1B1E24);
        ImageView iv = new ImageView(host.activity());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(iv, Tiles.match());
        img.execute(() -> {
            Bitmap b = BitmapFactory.decodeFile(e.preview.getAbsolutePath());
            if (b != null) ui.post(() -> iv.setImageBitmap(b));
        });
        tile.addView(Tiles.badge(host, e.kind == WallpaperLibrary.Kind.SCENE ? "SCENE" : "VIDEO"),
                corner(Gravity.BOTTOM | Gravity.START));
        if (e.partial) tile.addView(Tiles.badge(host, "PARTIAL"), corner(Gravity.BOTTOM | Gravity.END));
        if (e.id.equals(host.selected())) {
            tile.addView(Tiles.badge(host, "IN USE"), corner(Gravity.TOP | Gravity.END));
        }
        return cell(tile, e.title, () -> choose(e.id));
    }

    /** The way in to Steam Workshop, and the only tile here that is not a wallpaper. */
    private View browseCell() {
        FrameLayout tile = new FrameLayout(host.activity());
        host.style(tile, WALL_W, WALL_H);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag("browse");
        tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2B3A5C, 0xFF141A26}));
        TextView t = host.text("Browse\nWorkshop", 18, WallpaperHost.MEDIUM);
        t.setGravity(Gravity.CENTER);
        tile.addView(t, Tiles.match());
        return cell(tile, "Steam Workshop", browser::open);
    }

    /** The 16:9 frame every picker tile is built on. */
    private FrameLayout tile(String id) {
        FrameLayout tile = new FrameLayout(host.activity());
        host.style(tile, WALL_W, WALL_H);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setTag(TAG_PREFIX + id);
        return tile;
    }

    /** Wraps a tile with its caption and the focus behaviour shared by every cell. */
    private View cell(FrameLayout tile, String label, Runnable onOk) {
        LinearLayout col = Tiles.column(host.activity());
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(tile, new LinearLayout.LayoutParams(dp(WALL_W), dp(WALL_H)));

        TextView caption = host.text(label, 13, WallpaperHost.MEDIUM);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setMaxWidth(dp(WALL_W));
        caption.setAlpha(0.7f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(9);
        col.addView(caption, llp);

        tile.setOnFocusChangeListener((v, has) -> {
            host.focus(tile, has, 1.08f);
            caption.animate().alpha(has ? 1f : 0.7f).setDuration(180).start();
            if (!has) return;
            host.tick();
            scroll.smoothScrollTo(0, Math.max(0, ((View) col.getParent()).getTop() - dp(26)));
        });
        tile.setOnClickListener(v -> onOk.run());
        tile.setOnLongClickListener(v -> offerRemoval(tile));
        return col;
    }

    private void choose(String id) {
        if (browser.isOpen()) browser.close();
        dismiss();
        host.select(id);
    }

    /** Menu, or a long press, on a library tile: the one thing to do with a bundle is delete it. */
    private boolean offerRemoval(View v) {
        Object tag = v != null ? v.getTag() : null;
        if (!(tag instanceof String) || !((String) tag).startsWith(TAG_PREFIX)) return false;
        String id = ((String) tag).substring(TAG_PREFIX.length());
        WallpaperLibrary.Entry e = id.isEmpty() ? null : library.byId(id);
        if (e == null) return false;
        confirmRemoval(e);
        return true;
    }

    private void confirmRemoval(WallpaperLibrary.Entry e) {
        new AlertDialog.Builder(host.activity(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(e.title)
                .setMessage("Deletes the bundle from this TV, and from the Mac when it is reachable."
                        + " You stay subscribed on Steam.")
                .setPositiveButton("Remove from TV", (d, which) -> remove(e.id))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void remove(String id) {
        boolean inUse = id.equals(host.selected());
        browser.askMacToRemove(id);
        library.delete(bg, id, () -> {
            if (inUse) host.select("");
            refresh();
        });
    }

    private FrameLayout.LayoutParams corner(int gravity) {
        return Tiles.corner(host.activity(), gravity);
    }

    private int dp(float v) {
        return Tiles.dp(host.activity(), v);
    }
}
