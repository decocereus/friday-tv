package home.friday.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Continue watching from the local TV provider: every installed app's Watch Next feed, plus
 * Nuvio's own preview channel. Other apps' preview channels are recommendations and are skipped.
 * No account API is used.
 */
final class WatchNext {
    static final class Item {
        final String title, subtitle, image, pkg, key;
        final Intent intent;
        final int progress;
        final long engagement, weight;

        Item(String title, String subtitle, String image, String pkg, String key, Intent intent,
             int progress, long engagement, long weight) {
            this.title = title; this.subtitle = subtitle; this.image = image; this.pkg = pkg;
            this.key = key;
            this.intent = intent; this.progress = progress;
            this.engagement = engagement; this.weight = weight;
        }
    }

    private WatchNext() { }

    static List<Item> load(Context context, int max) {
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Boolean> launchable = new HashMap<>();
        long now = System.currentTimeMillis();
        // Watch Next first: Nuvio mirrors titles into its preview channel without an engagement time.
        read(context, TvContract.WatchNextPrograms.CONTENT_URI, true, now, items, seen, launchable);
        read(context, TvContract.PreviewPrograms.CONTENT_URI, false, now, items, seen, launchable);
        items.sort(Comparator.comparingLong((Item i) -> i.engagement).reversed()
                .thenComparing(Comparator.comparingLong((Item i) -> i.weight).reversed()));
        return new ArrayList<>(items.subList(0, Math.min(max, items.size())));
    }

    private static void read(Context c, Uri uri, boolean watchNext, long now, List<Item> out, Set<String> seen,
                             Map<String, Boolean> launchable) {
        // TV Provider rejects selection/sort arguments; filter the publisher before reading media fields.
        try (Cursor cursor = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String pkg = text(cursor, "package_name");
                boolean nuvio = ArtworkPolicy.isNuvio(pkg);
                if (watchNext ? !(ArtworkPolicy.resumable((int) number(cursor, "watch_next_type", -1),
                        number(cursor, "last_playback_position_millis", -1), number(cursor, "duration_millis", 0),
                        number(cursor, "last_engagement_time_utc_millis", 0), now)
                        && (nuvio || launchable.computeIfAbsent(pkg, p -> isLaunchableApp(c, p)))) : !nuvio) continue;
                String title = text(cursor, "title"), encoded = text(cursor, "intent_uri");
                if (title.isEmpty() || encoded.isEmpty()) continue;
                if (cursor.getColumnIndex("browsable") >= 0 && number(cursor, "browsable", 1) == 0) continue;
                Intent intent = parseIntent(encoded, pkg);
                if (intent == null) continue;
                String id = text(cursor, "content_id");
                if (id.isEmpty()) id = text(cursor, "internal_provider_id");
                String contentId = intent.getStringExtra("contentId");
                String videoId = intent.getStringExtra("videoId");
                String type = intent.getStringExtra("contentType");
                String key = contentId != null && !contentId.isEmpty()
                        ? pkg + "|" + type + "|" + contentId + "|" + videoId
                        : pkg + "|" + (id.isEmpty() ? encoded : id);
                if (!seen.add(key)) continue;
                String image = text(cursor, "poster_art_uri");
                if (image.isEmpty()) image = text(cursor, "thumbnail_uri");
                out.add(new Item(title, text(cursor, "episode_title"), image, pkg, key, intent,
                        ArtworkPolicy.progress(number(cursor, "last_playback_position_millis", -1),
                                number(cursor, "duration_millis", 0)),
                        number(cursor, "last_engagement_time_utc_millis", 0), number(cursor, "weight", 0)));
            }
        } catch (RuntimeException ignored) {
            // Missing provider/permission yields no row, rather than a different data source.
        }
    }

    /** Background-only publishers (Google TV and TCL recommenders) have no launcher entry. */
    private static boolean isLaunchableApp(Context c, String pkg) {
        if (pkg.isEmpty() || pkg.equals(c.getPackageName())) return false;
        PackageManager pm = c.getPackageManager();
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
    }

    private static Intent parseIntent(String encoded, String publisher) {
        try {
            Intent intent = Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME);
            ComponentName component = intent.getComponent();
            if (intent.getSelector() != null) return null;
            if (component != null && !publisher.equals(component.getPackageName())) return null;
            if (intent.getPackage() != null && !publisher.equals(intent.getPackage())) return null;
            return intent.setPackage(publisher)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } catch (Exception ignored) { return null; }
    }

    private static String text(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long number(Cursor c, String name, long fallback) {
        int i = c.getColumnIndex(name);
        return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
    }
}
