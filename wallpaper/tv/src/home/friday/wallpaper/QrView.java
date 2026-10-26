package home.friday.wallpaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Draws a {@link Qr} on a white card with a quiet zone, which is what a phone camera wants. */
final class QrView extends View {

    private static final int QUIET = 4;

    private final Paint paint = new Paint();
    private Qr code;

    QrView(Context c) {
        super(c);
        paint.setAntiAlias(false);
    }

    void show(String text) {
        code = text == null || text.isEmpty() ? null : Qr.encode(text);
        invalidate();
    }

    boolean has() {
        return code != null;
    }

    @Override protected void onDraw(Canvas canvas) {
        Qr qr = code;
        if (qr == null) return;
        int side = Math.min(getWidth(), getHeight());
        int modules = qr.size() + QUIET * 2;
        // A whole number of pixels per module, so the camera sees clean edges.
        int scale = Math.max(1, side / modules);
        int drawn = scale * modules;
        float left = (getWidth() - drawn) / 2f, top = (getHeight() - drawn) / 2f;
        paint.setColor(Color.WHITE);
        canvas.drawRect(left, top, left + drawn, top + drawn, paint);
        paint.setColor(Color.BLACK);
        for (int y = 0; y < qr.size(); y++) {
            for (int x = 0; x < qr.size(); x++) {
                if (!qr.dark(x, y)) continue;
                float px = left + (x + QUIET) * scale, py = top + (y + QUIET) * scale;
                canvas.drawRect(px, py, px + scale, py + scale, paint);
            }
        }
    }
}
