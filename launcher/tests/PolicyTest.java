package home.friday.tv;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

public final class PolicyTest {
    private static int checks;
    private static void check(boolean result, String name) {
        checks++;
        if (!result) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        String nuvio = "com.nuvio.tv", netflix = "com.netflix.ninja";
        check(ArtworkPolicy.allows("https://image.tmdb.org/t/p/w780/cover.jpg", nuvio), "known cover host");
        check(ArtworkPolicy.allows("https://IMAGES.METAHUB.SPACE/poster.jpg", nuvio), "case-insensitive host");
        for (String url : new String[]{"http://image.tmdb.org/a", "https://image.tmdb.org.evil.test/a",
                "https://user:secret@image.tmdb.org/a", "https://image.tmdb.org:444/a",
                "https://127.0.0.1/a", "https://api.open-meteo.com/a", "https://apis.justwatch.com/graphql",
                "file:///etc/passwd", "https://example.com/a", "https://image.tmdb.org@evil.test/a"}) {
            check(!ArtworkPolicy.allows(url, nuvio), "blocked destination " + url);
        }
        check(ArtworkPolicy.allows("https://occ-0-2464-3646.1.nflxso.net/art.jpg", netflix), "app's own CDN subdomain");
        check(ArtworkPolicy.allows("https://img1.hotstarext.com/a.jpg", "in.startv.hotstar"), "hotstar CDN");
        check(ArtworkPolicy.allows("https://is1-ssl.mzstatic.com/a.jpg", "com.apple.atve.androidtv.appletv"), "apple CDN");
        check(!ArtworkPolicy.allows("https://evilnflxso.net/a.jpg", netflix), "reject suffix without label boundary");
        check(!ArtworkPolicy.allows("https://nflxso.net.evil.test/a.jpg", netflix), "reject CDN name as prefix");
        check(!ArtworkPolicy.allows("http://occ-0.nflxso.net/a.jpg", netflix), "reject plain http for apps");
        check(!ArtworkPolicy.allows("https://image.tmdb.org/a.jpg", netflix), "hosts are per publisher");
        check(!ArtworkPolicy.allows("https://occ-0.nflxso.net/a.jpg", nuvio), "nuvio cannot use another app's CDN");
        check(!ArtworkPolicy.allows("https://tmdbmeta.b-cdn.net/a.jpg", "com.tcl.browser"), "unknown publisher fetches nothing");
        check(!ArtworkPolicy.allows("https://image.tmdb.org/a.jpg", ""), "missing publisher fetches nothing");
        check(ArtworkPolicy.ownsAuthority("com.nuvio.tv.provider", nuvio), "publisher's own provider");
        check(ArtworkPolicy.ownsAuthority("com.netflix.ninja", netflix), "publisher's own resources");
        check(!ArtworkPolicy.ownsAuthority("com.nuvio.tvx", nuvio), "reject authority prefix spoof");
        check(!ArtworkPolicy.ownsAuthority("com.android.contacts", netflix), "reject other authority");
        check(!ArtworkPolicy.ownsAuthority(null, netflix), "reject missing authority");
        check(ArtworkPolicy.isNuvio("com.nuvio.tv"), "official APK publisher");
        check(ArtworkPolicy.isNuvio("com.nuvio.app"), "store publisher");
        check(!ArtworkPolicy.isNuvio("com.nuvio.tv.fake"), "reject publisher prefix spoof");
        long now = 10_000L * 24 * 3600_000, hour = 3600_000;
        check(ArtworkPolicy.resumable(0, 1547000, 3568000, now - 335 * hour, now), "part-watched, two weeks ago");
        check(ArtworkPolicy.resumable(1, -1, 0, now - hour, now), "next episode without progress");
        check(ArtworkPolicy.resumable(0, 10, 0, 0, now), "unknown duration and engagement kept");
        check(!ArtworkPolicy.resumable(0, 0, 1417000, now - hour, now), "never started");
        check(!ArtworkPolicy.resumable(0, 1412000, 1415750, now - hour, now), "finished episode");
        check(!ArtworkPolicy.resumable(0, 1036000, 3956000, now - 4366 * hour, now), "stale for six months");
        check(!ArtworkPolicy.resumable(1, 0, 0, now - 61 * 24 * hour, now), "stale next episode");
        for (int type : new int[]{-1, 2, 3, 4}) check(!ArtworkPolicy.resumable(type, 500, 1000, now - hour, now), "skip watch next type " + type);
        check(ArtworkPolicy.progress(300, 1000) == 300, "progress percentage");
        check(ArtworkPolicy.progress(0, 0) == -1, "unknown duration");
        check(ArtworkPolicy.progress(2000, 1000) == 1000, "cap finished progress");
        check(ArtworkPolicy.progress(Long.MAX_VALUE / 2, Long.MAX_VALUE) == 500, "avoid multiplication overflow");
        File dir = Files.createTempDirectory("friday-backup-test").toFile();
        try {
            LayoutBackups store = new LayoutBackups(dir, 3);
            check(store.latest() == null, "empty backup");
            store.save("{\"dock\":\"one\"}");
            store.save("{\"dock\":\"one\"}");
            check(dir.listFiles().length == 1, "identical state is not duplicated");
            store.save("{\"dock\":\"two\"}");
            check(store.previous(store.latest()).contains("one"), "previous state available");
            Files.write(new File(dir,"unrelated.txt").toPath(), "keep".getBytes(StandardCharsets.UTF_8));
            store.save("{\"dock\":\"three\"}");
            store.save("{\"dock\":\"four\"}");
            check(store.latest().contains("four"), "latest state available");
            check(new File(dir,"unrelated.txt").isFile(), "retention preserves unrelated files");
            check(dir.listFiles((d,n)->n.endsWith(".json")).length == 3, "retention bound");
            try { store.save("x".repeat(524289)); throw new AssertionError("oversized layout accepted"); }
            catch (java.io.IOException expected) { }
            check(store.latest().contains("four"), "failed write preserves previous snapshot");
        } finally {
            try (var files = Files.walk(dir.toPath())) {
                files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});
            }
        }
        System.out.println(checks + " artwork, Watch Next, progress and backup checks passed");
    }
}
