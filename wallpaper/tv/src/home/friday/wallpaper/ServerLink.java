package home.friday.wallpaper;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Mac's wallpaper service, if it is there. Its address and token arrive in a {@code
 * server.json} the service itself writes into the app's files folder over adb, so there is
 * nothing to pair and nothing to type. Every call is plain HTTP on a background thread; the
 * service only ever answers on the LAN and only with the token.
 */
final class ServerLink {

    static final class Down extends Exception {
        /** True when the Mac answered with an error, false when it could not be reached at all. */
        final boolean answered;

        Down(String why) {
            this(why, false);
        }

        Down(String why, boolean answered) {
            super(why);
            this.answered = answered;
        }
    }

    private static final String TAG = "FridayWallpaper";
    private static final long MAX_CONFIG = 8 * 1024, MAX_BODY = 2 * 1024 * 1024;
    private static final int CONNECT_MS = 2500, READ_MS = 12000;

    private final File config;
    private String base = "", token = "";

    ServerLink(Context c) {
        File files = c.getExternalFilesDir(null);
        config = files != null ? new File(files, "server.json") : null;
    }

    /** Re-reads server.json. Cheap enough to call before a burst of requests. */
    boolean refresh() {
        base = "";
        token = "";
        if (config == null || !config.isFile() || config.length() > MAX_CONFIG) return false;
        try {
            JSONObject o = new JSONObject(
                    new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
            String url = o.optString("url").trim();
            String key = o.optString("token").trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
            if (key.isEmpty()) return false;
            base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            token = key;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean configured() {
        return !base.isEmpty();
    }

    JSONObject get(String path) throws Down {
        return call("GET", path, null);
    }

    JSONObject post(String path, String id) throws Down {
        return call("POST", path,
                new JSONObject(java.util.Collections.singletonMap("id", id)).toString());
    }

    JSONObject post(String path) throws Down {
        return call("POST", path, "{}");
    }

    private JSONObject call(String method, String path, String payload) throws Down {
        if (base.isEmpty() && !refresh()) throw new Down("no server.json");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            connection.setRequestProperty("X-Friday-Token", token);
            connection.setRequestProperty("Accept", "application/json");
            if (payload != null) {
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = in == null ? "" : read(in);
            if (status == 403) throw new Down("the Mac rejected this TV's token");
            if (status >= 400) {
                String said = text.isEmpty() ? "" : new JSONObject(text).optString("error");
                throw new Down(said.isEmpty() ? "the Mac answered " + status : said, true);
            }
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } catch (Down d) {
            throw d;
        } catch (Exception e) {
            Log.w(TAG, method + " " + path + " failed: " + e);
            throw new Down("the Mac did not answer");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > MAX_BODY) break;
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
}
