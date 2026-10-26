package home.friday.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How the Mac chooses a wallpaper:
 * {@code am broadcast -n home.friday.tv/.SetWallpaper --es friday.wallpaper <id|photos>}.
 *
 * <p>A broadcast and not an {@code am start} extra, because a start intent is not always
 * delivered: with the screensaver up, Android brings Home's task forward behind the dream without
 * resuming it, and the extra is dropped on the floor. A broadcast reaches the app in every state
 * and never wakes the TV. Whatever is live, Home or the dream, follows along at once; when
 * nothing is, the saved choice is there on the next start.
 */
public class SetWallpaper extends BroadcastReceiver {

    interface Listener {
        /** On the main thread: {@code id} is a bundle id, or "" for the photo slideshow. */
        void onWallpaper(String id);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    static void listen(Listener l) {
        LISTENERS.add(l);
    }

    static void forget(Listener l) {
        LISTENERS.remove(l);
    }

    @Override public void onReceive(Context c, Intent i) {
        String want = i != null ? i.getStringExtra("friday.wallpaper") : null;
        if (want == null) return;
        String id = want.trim();
        if ("photos".equals(id)) id = "";
        Store store = new Store(c);
        store.wallpaper = id;
        store.save();
        final String picked = id;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Listener l : LISTENERS) l.onWallpaper(picked);
        });
    }
}
