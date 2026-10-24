package home.friday.tv;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Artwork is fetched only from the publishing app's own image hosts; other rows show their title. */
final class ArtworkPolicy {
    private static final Set<String> NUVIO_HOSTS = new HashSet<>(Arrays.asList(
            "image.tmdb.org", "images.metahub.space", "img.metahub.space",
            "images.fanart.tv", "assets.fanart.tv"));
    /** Publisher package to the domains its own CDN serves; subdomains match on a label boundary. */
    private static final Map<String, List<String>> APP_DOMAINS = new HashMap<>();
    static {
        APP_DOMAINS.put("com.netflix.ninja", Arrays.asList("nflxso.net", "nflxext.com"));
        APP_DOMAINS.put("com.amazon.amazonvideo.livingroom", Arrays.asList("ssl-images-amazon.com", "media-amazon.com"));
        APP_DOMAINS.put("in.startv.hotstar", Collections.singletonList("hotstarext.com"));
        APP_DOMAINS.put("com.apple.atve.androidtv.appletv", Collections.singletonList("mzstatic.com"));
        APP_DOMAINS.put("com.crunchyroll.crunchyroid", Collections.singletonList("crunchyroll.com"));
        APP_DOMAINS.put("com.sonyliv", Collections.singletonList("sonyliv.com"));
        APP_DOMAINS.put("com.google.android.youtube.tv", Arrays.asList("ytimg.com", "ggpht.com"));
        APP_DOMAINS.put("com.google.android.youtube.tvmusic", Arrays.asList("ytimg.com", "ggpht.com", "googleusercontent.com"));
    }

    private ArtworkPolicy() { }

    static boolean allows(String value, String publisher) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (isNuvio(publisher)) return NUVIO_HOSTS.contains(host);
            for (String domain : APP_DOMAINS.getOrDefault(publisher, Collections.emptyList()))
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** content:// and android.resource:// artwork must come from the publisher's own authority. */
    static boolean ownsAuthority(String authority, String publisher) {
        return authority != null && !publisher.isEmpty()
                && (authority.equals(publisher) || authority.startsWith(publisher + "."));
    }

    static boolean isNuvio(String pkg) {
        return "com.nuvio.tv".equals(pkg) || "com.nuvio.app".equals(pkg);
    }

    private static final long RECENT_MILLIS = 60L * 24 * 60 * 60 * 1000;

    /**
     * Something you are part-way through: a NEXT episode (type 1), or a CONTINUE item (type 0)
     * started but under 95% watched. Rows untouched for 60 days are stale; an unknown time is kept.
     */
    static boolean resumable(int type, long position, long duration, long engaged, long now) {
        if (engaged > 0 && now - engaged > RECENT_MILLIS) return false;
        if (type == 1) return true;
        return type == 0 && position > 0 && (duration <= 0 || position < duration * 0.95);
    }

    static int progress(long position, long duration) {
        if (duration <= 0 || position < 0) return -1;
        return (int) Math.min(1000, Math.round((double) position * 1000 / duration));
    }
}
