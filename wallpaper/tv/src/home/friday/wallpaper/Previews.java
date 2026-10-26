package home.friday.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Workshop preview images, under the same rule as Friday Home's app artwork
 * (home.friday.tv.ArtworkPolicy): https only, no credentials in the URL, port 443, and a host
 * from a fixed list. A preview URL arrives over the LAN in JSON, so it is never trusted; these
 * are the hosts Steam serves {@code preview_url} from. Artwork only: no cookies, no POSTs.
 */
final class Previews {

    /** Steam's UGC image hosts. The akamai one is what older items still redirect from. */
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "images.steamusercontent.com", "steamuserimages-a.akamaihd.net",
            "community.cloudflare.steamstatic.com", "community.akamai.steamstatic.com",
            "community.fastly.steamstatic.com", "shared.cloudflare.steamstatic.com",
            "shared.akamai.steamstatic.com", "shared.fastly.steamstatic.com"));

    private static final int MAX_BYTES = 4 * 1024 * 1024, KEEP = 120;

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    private Previews() { }

    static boolean allows(String address) {
        try {
            URI uri = new URI(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) return false;
            return HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Decoded preview, from memory, then the cache folder, then the network. Never on the UI thread. */
    static Bitmap load(Context c, String address) {
        if (address == null || address.isEmpty() || !allows(address)) return null;
        Bitmap cached = MEMORY.get(address);
        if (cached != null) return cached;
        try {
            File dir = new File(c.getCacheDir(), "workshop-previews");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File file = new File(dir, digest(address) + ".img");
            if (!file.isFile()) {
                byte[] data = download(address);
                if (data == null) return null;
                Files.write(file.toPath(), data);
                prune(dir);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 2;
            Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
            if (image != null) MEMORY.put(address, image);
            return image;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static byte[] download(String address) {
        for (int redirects = 0; redirects <= 2; redirects++) {
            if (!allows(address)) return null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("Accept", "image/*");
                connection.setRequestProperty("User-Agent", "FridayArtwork/1.0");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    address = new URI(address).resolve(location).toString();
                    continue;
                }
                if (status != 200) return null;
                try (InputStream in = connection.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (out.size() + count > MAX_BYTES) return null;
                        out.write(buffer, 0, count);
                    }
                    return out.toByteArray();
                }
            } catch (Exception ignored) {
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private static String digest(String value) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
            out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".img"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - KEEP; i++) files[i].delete();
    }
}
