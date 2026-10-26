package home.friday.wallpaper;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The small pieces both wallpaper overlays build out of: rows, columns, pills and badges. */
final class Tiles {

    private Tiles() { }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        return col;
    }

    /** A dark rounded caption, for the kind and state corners of a preview tile. */
    static TextView badge(WallpaperHost host, String text) {
        Context c = host.activity();
        TextView t = host.text(text, 9, WallpaperHost.SEMIBOLD);
        t.setShadowLayer(0, 0, 0, 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xB3000000);
        pill.setCornerRadius(dp(c, 7));
        t.setBackground(pill);
        t.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 3));
        t.setLetterSpacing(0.08f);
        return t;
    }

    static FrameLayout.LayoutParams corner(Context c, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        lp.setMargins(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        return lp;
    }

    static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    static android.view.View firstTile(android.view.ViewGroup parent) {
        if (parent.getChildCount() == 0) return null;
        android.view.View c = parent.getChildAt(0);
        if (c.isFocusable()) return c;
        return c instanceof android.view.ViewGroup ? firstTile((android.view.ViewGroup) c) : null;
    }
}
