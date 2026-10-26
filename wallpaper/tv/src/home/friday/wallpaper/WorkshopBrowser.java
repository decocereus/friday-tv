package home.friday.wallpaper;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Browsing Steam Workshop on the TV. The Mac's service does the fetching; this shows what comes
 * back, sends OK to /install and polls /jobs while it is open. With no Mac it says so once and
 * stops: wallpapers already on the TV keep working either way.
 */
final class WorkshopBrowser {

    private static final String TAG_PREFIX = "ws:";
    private static final int TILE_W = 196, TILE_H = 110, COLS = 4, GAP = 22, SIDE = 48;
    private static final long POLL_MS = 2000;
    private static final String UNREACHABLE =
            "Your Mac isn't reachable. Wallpapers already on the TV still work.";
    /** serve.py's SIGNED_OUT: Steam dropped the sign-in, so the library tab goes back to the QR. */
    private static final String SIGNED_OUT = "steam-signed-out";
    private static final String SIGN_IN_CAPTION = "Scan with the Steam app to show your library";

    private static final String[] SORT_KEYS = {"library", "trend", "toprated", "mostrecent"};
    private static final String[] SORT_NAMES = {"My Steam library", "Trending", "Top rated", "Newest"};
    private static final String[] KIND_KEYS = {"all", "scene", "video"};
    private static final String[] KIND_NAMES = {"All", "Scene", "Video"};

    private final WallpaperHost host;
    private final WallpaperLibrary library;
    private final WallpaperPicker picker;
    private final Executor net, img;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ServerLink link;

    private final FrameLayout overlay;
    private final ScrollView scroll;
    private final LinearLayout grid, sortRow, kindRow;
    private final EditText search;
    private final TextView status;
    private final LinearLayout signIn;
    private final QrView qr;
    private final TextView signInNote;

    private final List<Item> items = new ArrayList<>();
    private final Map<String, View> tiles = new LinkedHashMap<>();
    private final Map<String, String[]> jobs = new LinkedHashMap<>();
    private String sort = "trend", kind = "all", query = "", account = "";
    private int page = 1, request;
    private boolean open, loading, exhausted, awaitingSteam;

    private static final class Item {
        final String id, title, type;
        final String preview;
        final long size;
        final int subscriptions;

        Item(JSONObject o) {
            id = o.optString("id");
            title = o.optString("title", id);
            type = o.optString("type");
            preview = o.optString("preview");
            size = o.optLong("sizeBytes");
            subscriptions = o.optInt("subscriptions");
        }
    }

    WorkshopBrowser(WallpaperHost host, WallpaperLibrary library, Executor net, Executor img,
                    WallpaperPicker picker) {
        this.host = host;
        this.library = library;
        this.picker = picker;
        this.net = net;
        this.img = img;
        this.link = new ServerLink(host.activity());

        overlay = new FrameLayout(host.activity());
        overlay.setBackgroundColor(0xFC0E1014);
        overlay.setVisibility(View.GONE);
        LinearLayout col = Tiles.column(host.activity());
        col.setPadding(0, dp(44), 0, 0);

        TextView title = host.text("Steam Workshop", 30, WallpaperHost.SEMIBOLD);
        title.setPadding(dp(SIDE), 0, dp(SIDE), dp(10));
        col.addView(title);

        sortRow = Tiles.row(host.activity());
        sortRow.setPadding(dp(SIDE), 0, dp(SIDE), dp(8));
        for (int i = 0; i < SORT_KEYS.length; i++) {
            final String key = SORT_KEYS[i];
            sortRow.addView(chip(SORT_NAMES[i], "sort:" + key, () -> setSort(key)));
        }
        col.addView(sortRow);

        kindRow = Tiles.row(host.activity());
        kindRow.setPadding(dp(SIDE), 0, dp(SIDE), dp(8));
        for (int i = 0; i < KIND_KEYS.length; i++) {
            final String key = KIND_KEYS[i];
            kindRow.addView(chip(KIND_NAMES[i], "kind:" + key, () -> setKind(key)));
        }
        search = new EditText(host.activity());
        search.setSingleLine(true);
        search.setHint("Search");
        search.setHintTextColor(0x80FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(16), dp(7), dp(16), dp(8));
        GradientDrawable field = new GradientDrawable();
        field.setColor(0x1FFFFFFF);
        field.setCornerRadius(dp(18));
        search.setBackground(field);
        search.setOnEditorActionListener((v, action, e) -> {
            hideKeyboard();
            setQuery(search.getText().toString().trim());
            return true;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(260), -2);
        slp.leftMargin = dp(16);
        kindRow.addView(search, slp);
        col.addView(kindRow);

        status = host.text("", 13, WallpaperHost.REGULAR);
        status.setAlpha(0.7f);
        status.setPadding(dp(SIDE), dp(4), dp(SIDE), 0);
        col.addView(status);

        scroll = new ScrollView(host.activity()) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
            @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setFocusable(false);
        grid = Tiles.column(host.activity());
        grid.setPadding(dp(SIDE), dp(16), dp(SIDE), dp(60));
        scroll.addView(grid);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        signIn = Tiles.column(host.activity());
        signIn.setGravity(Gravity.CENTER_HORIZONTAL);
        signIn.setPadding(dp(SIDE), dp(10), dp(SIDE), 0);
        signIn.setVisibility(View.GONE);
        qr = new QrView(host.activity());
        signIn.addView(qr, new LinearLayout.LayoutParams(dp(220), dp(220)));
        signInNote = host.text(SIGN_IN_CAPTION, 14, WallpaperHost.MEDIUM);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(14);
        signIn.addView(signInNote, nlp);
        col.addView(signIn, new LinearLayout.LayoutParams(-1, -2));

        overlay.addView(col, Tiles.match());
    }

    View view() {
        return overlay;
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200).start();
        link.refresh();
        reload();
        ui.postDelayed(poll, POLL_MS);
    }

    void close() {
        open = false;
        hideKeyboard();
        ui.removeCallbacks(poll);
        overlay.setVisibility(View.GONE);
        grid.removeAllViews();
        tiles.clear();
        items.clear();
        picker.backFromBrowser();
    }

    /** The library changed: re-badge the tiles that are now on the TV. */
    void refresh() {
        if (open) relabelAll();
    }

    boolean onKey(int keyCode) {
        if ("library".equals(sort) && !account.isEmpty()) {
            offerSignOut();
            return true;
        }
        View focused = host.activity().getCurrentFocus();
        Object tag = focused != null ? focused.getTag() : null;
        if (!(tag instanceof String) || !((String) tag).startsWith(TAG_PREFIX)) return false;
        String id = ((String) tag).substring(TAG_PREFIX.length());
        if (library.byId(id) == null) return false;
        picker.offerRemovalById(id);
        return true;
    }

    /** Best effort: tell the Mac to drop its copy too. Silent when the Mac is not there. */
    void askMacToRemove(String id) {
        net.execute(() -> {
            try {
                link.post("/remove", id);
            } catch (ServerLink.Down ignored) { }
        });
    }

    // ---------------------------------------------------------------- loading

    private void setSort(String key) {
        if (key.equals(sort)) {
            retry();
            return;
        }
        sort = key;
        reload();
    }

    private void setKind(String key) {
        if (key.equals(kind)) {
            retry();
            return;
        }
        kind = key;
        reload();
    }

    /** OK on the chip already chosen. Nothing to change, so it means "try the Mac again". */
    private void retry() {
        if (!items.isEmpty() || awaitingSteam) return;
        link.refresh();
        reload();
    }

    private void setQuery(String text) {
        if (text.equals(query)) return;
        query = text;
        if (!query.isEmpty() && "library".equals(sort)) sort = "trend";
        reload();
    }

    private void reload() {
        page = 1;
        exhausted = false;
        items.clear();
        tiles.clear();
        grid.removeAllViews();
        markChips();
        if ("library".equals(sort)) {
            checkSteam(true);
            return;
        }
        showSignIn(false);
        fetch(true);
    }

    // ---------------------------------------------------------------- steam sign-in

    /** The library tab: either the user's items, or a QR for the Steam app to scan. */
    private void checkSteam(boolean mayBegin) {
        final int ticket = ++request;
        // Only the first check says so: the two-second poll behind the QR would flicker the line.
        if (!awaitingSteam) status.setText("Asking the Mac\u2026");
        net.execute(() -> {
            JSONObject state;
            try {
                state = link.get("/steam");
                if (mayBegin && isSignedOut(state.optString("state"))) state = link.post("/steam/signin");
            } catch (ServerLink.Down down) {
                ui.post(() -> {
                    if (ticket != request || !open) return;
                    showSignIn(false);
                    status.setText(down.answered ? "Steam said: " + down.getMessage() : UNREACHABLE);
                });
                return;
            }
            final JSONObject answer = state;
            ui.post(() -> {
                if (ticket != request || !open) return;
                applySteam(answer);
            });
        });
    }

    private static boolean isSignedOut(String state) {
        return "signed-out".equals(state) || "expired".equals(state);
    }

    private void applySteam(JSONObject state) {
        String what = state.optString("state");
        if ("signed-in".equals(what)) {
            account = state.optString("account");
            awaitingSteam = false;
            showSignIn(false);
            fetch(true);
            return;
        }
        if ("error".equals(what)) {
            awaitingSteam = false;
            showSignIn(false);
            status.setText("Steam said: " + state.optString("error"));
            return;
        }
        awaitingSteam = true;
        String challenge = state.optString("challenge");
        qr.show(challenge);
        showSignIn(true);
        signInNote.setText(state.optBoolean("scanned")
                ? "Approve the sign-in in the Steam app" : SIGN_IN_CAPTION);
        status.setText(qr.has() ? "Signing in to Steam" : "Open on a phone: " + challenge);
    }

    private void showSignIn(boolean on) {
        signIn.setVisibility(on ? View.VISIBLE : View.GONE);
        scroll.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) return;
        grid.removeAllViews();
        tiles.clear();
        items.clear();
        View chip = sortRow.getChildAt(0);
        if (chip != null && !overlayHasFocus()) chip.requestFocus();
    }

    /** Menu on the library tab: the one thing to do there is to stop using the Steam account. */
    private void offerSignOut() {
        new android.app.AlertDialog.Builder(host.activity(),
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sign out of Steam?")
                .setMessage("Friday forgets the Steam sign-in on the Mac. Wallpapers already on "
                        + "the TV and the Trending tabs are unaffected.")
                .setPositiveButton("Sign out", (d, which) -> net.execute(() -> {
                    try {
                        link.post("/steam/signout");
                    } catch (ServerLink.Down ignored) { }
                    ui.post(() -> {
                        account = "";
                        reload();
                    });
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fetch(boolean first) {
        if (loading || exhausted) return;
        loading = true;
        final int ticket = ++request;
        final int wanted = page;
        status.setText(first ? "Asking the Mac…" : "Loading more…");
        net.execute(() -> {
            String error = null;
            List<Item> found = new ArrayList<>();
            try {
                if (!link.configured() && !link.refresh()) throw new ServerLink.Down("no server.json");
                JSONObject body = "library".equals(sort)
                        ? link.get("/library")
                        : link.get("/workshop?sort=" + sort + "&type=" + kind
                                + "&page=" + wanted + "&q=" + android.net.Uri.encode(query));
                String reported = body.optString("error");
                if (!reported.isEmpty()) error = reported;
                JSONArray list = body.optJSONArray("items");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o != null && !o.optString("id").isEmpty()) found.add(new Item(o));
                }
            } catch (ServerLink.Down down) {
                error = down.answered ? "The Mac couldn't load this: " + down.getMessage() : UNREACHABLE;
            }
            final String problem = error;
            ui.post(() -> {
                loading = false;
                if (ticket != request || !open) return;
                if (SIGNED_OUT.equals(problem)) {
                    account = "";
                    checkSteam(true);
                    return;
                }
                if (problem != null && found.isEmpty()) {
                    exhausted = true;
                    status.setText(problem);
                    if (grid.getChildCount() == 0) focusFirstChip();
                    return;
                }
                if (found.isEmpty()) exhausted = true;
                else {
                    page = wanted + 1;
                    add(found);
                }
                status.setText(summary());
            });
        });
    }

    private String summary() {
        if ("library".equals(sort) && !account.isEmpty() && items.isEmpty()) {
            return "Nothing subscribed for Wallpaper Engine on " + account + ".    Menu  Sign out";
        }
        if (items.isEmpty()) return "Nothing here. Try another sort or search.";
        if ("library".equals(sort)) {
            return "OK  Install     OK again when ready  Use it     Menu  Sign out of Steam";
        }
        return "OK  Install     OK again when ready  Use it     Menu  Remove from TV";
    }

    private void add(List<Item> found) {
        for (Item item : found) {
            if (tiles.containsKey(item.id)) continue;
            int at = tiles.size();
            LinearLayout r;
            if (at % COLS == 0 || grid.getChildCount() == 0) {
                r = Tiles.row(host.activity());
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.bottomMargin = dp(18);
                grid.addView(r, rlp);
            } else {
                r = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(GAP);
            View cell = cell(item);
            r.addView(cell, lp);
            items.add(item);
        }
        if (host.activity().getCurrentFocus() == null || !overlayHasFocus()) {
            View first = Tiles.firstTile(grid);
            if (first != null) first.requestFocus();
        }
    }

    private boolean overlayHasFocus() {
        View f = host.activity().getCurrentFocus();
        for (View v = f; v != null; ) {
            if (v == overlay) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    // ---------------------------------------------------------------- tiles

    private View cell(Item item) {
        FrameLayout tile = new FrameLayout(host.activity());
        host.style(tile, TILE_W, TILE_H);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setTag(TAG_PREFIX + item.id);
        tile.setBackgroundColor(0xFF1B1E24);

        ImageView iv = new ImageView(host.activity());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(iv, Tiles.match());
        img.execute(() -> {
            Bitmap b = Previews.load(host.activity(), item.preview);
            if (b != null) ui.post(() -> iv.setImageBitmap(b));
        });
        tile.addView(Tiles.badge(host, item.type.toUpperCase(Locale.ROOT)),
                Tiles.corner(host.activity(), Gravity.BOTTOM | Gravity.START));

        TextView state = Tiles.badge(host, "");
        state.setVisibility(View.GONE);
        tile.addView(state, Tiles.corner(host.activity(), Gravity.TOP | Gravity.END));

        LinearLayout col = Tiles.column(host.activity());
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(tile, new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)));

        TextView caption = host.text(item.title, 12, WallpaperHost.MEDIUM);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setMaxWidth(dp(TILE_W));
        caption.setAlpha(0.7f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(8);
        col.addView(caption, clp);

        TextView meta = host.text(size(item.size) + "   " + subs(item.subscriptions), 10,
                WallpaperHost.REGULAR);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setMaxWidth(dp(TILE_W));
        meta.setAlpha(0.45f);
        col.addView(meta);

        col.setTag("cell:" + item.id);
        tiles.put(item.id, col);
        relabel(item.id);

        tile.setOnFocusChangeListener((v, has) -> {
            host.focus(tile, has, 1.08f);
            caption.animate().alpha(has ? 1f : 0.7f).setDuration(180).start();
            if (!has) return;
            host.tick();
            scroll.smoothScrollTo(0, Math.max(0, ((View) col.getParent()).getTop() - dp(20)));
            if (isLastRow(col)) fetch(false);
        });
        tile.setOnClickListener(v -> activate(item));
        tile.setOnLongClickListener(v -> {
            if (library.byId(item.id) == null) return false;
            picker.offerRemovalById(item.id);
            return true;
        });
        return col;
    }

    private boolean isLastRow(View cell) {
        View r = (View) cell.getParent();
        return grid.indexOfChild(r) >= grid.getChildCount() - 1;
    }

    /** OK: install it, or use it when it is already here. */
    private void activate(Item item) {
        if (library.byId(item.id) != null) {
            picker.chooseFromBrowser(item.id);
            return;
        }
        String[] job = jobs.get(item.id);
        if (job != null && "failed".equals(job[0])) jobs.remove(item.id);
        setJob(item.id, "queued", "");
        net.execute(() -> {
            String problem = null;
            try {
                JSONObject body = link.post("/install", item.id);
                String state = body.optString("state", "queued");
                ui.post(() -> setJob(item.id, state, body.optString("reason")));
            } catch (ServerLink.Down down) {
                problem = down.answered ? "The Mac couldn't start this: " + down.getMessage() : UNREACHABLE;
            }
            if (problem != null) {
                final String text = problem;
                ui.post(() -> {
                    jobs.remove(item.id);
                    relabel(item.id);
                    status.setText(text);
                });
            }
        });
    }

    private void setJob(String id, String state, String reason) {
        jobs.put(id, new String[]{state, reason == null ? "" : reason});
        relabel(id);
    }

    private static String label(String state) {
        switch (state) {
            case "queued": return "QUEUED";
            case "downloading": return "DOWNLOADING";
            case "converting": return "CONVERTING";
            case "pushing": return "SENDING";
            case "failed": return "FAILED";
            default: return "";
        }
    }

    private void relabelAll() {
        for (String id : new ArrayList<>(tiles.keySet())) relabel(id);
    }

    /** One tile's state corner and its caption line: on the TV, in flight, or failed. */
    private void relabel(String id) {
        View col = tiles.get(id);
        if (col == null) return;
        FrameLayout tile = (FrameLayout) ((LinearLayout) col).getChildAt(0);
        TextView state = (TextView) tile.getChildAt(tile.getChildCount() - 1);
        TextView meta = (TextView) ((LinearLayout) col).getChildAt(2);
        WallpaperLibrary.Entry here = library.byId(id);
        String[] job = jobs.get(id);
        if (here != null) {
            state.setText(id.equals(host.selected()) ? "IN USE" : "ON TV");
            state.setVisibility(View.VISIBLE);
            meta.setText(here.partial ? "Partial: " + here.skipped : "On this TV");
            meta.setAlpha(here.partial ? 0.7f : 0.45f);
            return;
        }
        if (job == null) {
            state.setVisibility(View.GONE);
            return;
        }
        String badge = label(job[0]);
        state.setText(badge);
        state.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
        if ("failed".equals(job[0])) {
            meta.setText("Failed: " + (job[1].isEmpty() ? "unknown" : job[1]));
            meta.setAlpha(0.8f);
        }
    }

    // ---------------------------------------------------------------- polling

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!open) return;
            ui.postDelayed(this, POLL_MS);
            if (awaitingSteam) checkSteam(false);
            if (jobs.isEmpty()) return;
            net.execute(() -> {
                final JSONObject body;
                try {
                    body = link.get("/jobs");
                } catch (ServerLink.Down down) {
                    return;
                }
                ui.post(() -> apply(body.optJSONObject("jobs")));
            });
        }
    };

    private void apply(JSONObject reported) {
        if (reported == null || !open) return;
        boolean arrived = false;
        for (String id : new ArrayList<>(jobs.keySet())) {
            JSONObject o = reported.optJSONObject(id);
            if (o == null) continue;
            String state = o.optString("state");
            if ("ready".equals(state)) {
                jobs.remove(id);
                arrived = true;
                continue;
            }
            setJob(id, state, o.optString("reason"));
        }
        if (arrived) host.rescanLibrary();
    }

    // ---------------------------------------------------------------- chrome

    private View chip(String label, String tag, Runnable action) {
        TextView t = host.text(label, 13, WallpaperHost.MEDIUM);
        t.setShadowLayer(0, 0, 0, 0);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(7), dp(16), dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(tag);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x1FFFFFFF);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(10);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, has) -> {
            t.setScaleX(has ? 1.06f : 1f);
            t.setScaleY(has ? 1.06f : 1f);
            paint(t);
            if (has) host.tick();
        });
        return t;
    }

    private void markChips() {
        for (LinearLayout r : new LinearLayout[]{sortRow, kindRow}) {
            for (int i = 0; i < r.getChildCount(); i++) {
                View v = r.getChildAt(i);
                if (v instanceof TextView && v.getTag() instanceof String) paint((TextView) v);
            }
        }
    }

    private void paint(TextView chip) {
        String tag = (String) chip.getTag();
        boolean on = tag.equals("sort:" + sort) || tag.equals("kind:" + kind);
        GradientDrawable bg = (GradientDrawable) chip.getBackground();
        if (chip.isFocused()) {
            bg.setColor(0xFFFFFFFF);
            chip.setTextColor(0xFF15171A);
        } else {
            bg.setColor(on ? 0x4DFFFFFF : 0x1FFFFFFF);
            chip.setTextColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
        }
        bg.setStroke(dp(2), on && !chip.isFocused() ? host.accent() : 0x00000000);
    }

    private void focusFirstChip() {
        View first = sortRow.getChildAt(1);
        if (first != null) first.requestFocus();
    }

    private void hideKeyboard() {
        InputMethodManager imm = host.activity().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "";
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024)
                : String.format(Locale.ROOT, "%.0f MB", mb);
    }

    private static String subs(int n) {
        if (n <= 0) return "";
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM subs", n / 1_000_000.0);
        if (n >= 1000) return String.format(Locale.ROOT, "%.0fk subs", n / 1000.0);
        return n + " subs";
    }

    private int dp(float v) {
        return Tiles.dp(host.activity(), v);
    }
}
