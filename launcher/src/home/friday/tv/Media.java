package home.friday.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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

/** Artwork only: no recommendations, analytics, location, accounts, cookies or POST requests. */
final class Media {
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private Media() { }

    static Bitmap poster(Context c, String address, String publisher) {
        if (address == null || address.isEmpty()) return null;
        Bitmap cached = MEMORY.get(address);
        if (cached != null) return cached;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 2;
            Bitmap image;
            if (address.startsWith("content://") || address.startsWith("android.resource://")) {
                Uri uri = Uri.parse(address);
                String authority = uri.getAuthority();
                if (!ArtworkPolicy.ownsAuthority(authority, publisher)) return null;
                try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                    image = BitmapFactory.decodeStream(in, null, options);
                }
            } else {
                if (!ArtworkPolicy.allows(address, publisher)) return null;
                File dir = new File(c.getCacheDir(), "nuvio-artwork");
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                File file = new File(dir, digest(address) + ".img");
                if (!file.isFile()) {
                    byte[] data = download(address, publisher);
                    if (data == null) return null;
                    Files.write(file.toPath(), data);
                    prune(dir);
                }
                image = BitmapFactory.decodeFile(file.getPath(), options);
            }
            if (image != null) MEMORY.put(address, image);
            return image;
        } catch (Exception | OutOfMemoryError ignored) { return null; }
    }

    private static byte[] download(String address, String publisher) {
        for (int redirects = 0; redirects <= 2; redirects++) {
            if (!ArtworkPolicy.allows(address, publisher)) return null;
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
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (out.size() + count > 4 * 1024 * 1024) return null;
                        out.write(buffer, 0, count);
                    }
                    return out.toByteArray();
                }
            } catch (Exception ignored) { return null; }
            finally { if (connection != null) connection.disconnect(); }
        }
        return null;
    }

    private static String digest(String value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
            result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".img"));
        if (files == null || files.length <= 60) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - 60; i++) files[i].delete();
    }
}
