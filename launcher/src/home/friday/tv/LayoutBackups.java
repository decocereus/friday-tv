package home.friday.tv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Small, versioned local snapshots. A failed write leaves the prior snapshots intact. */
final class LayoutBackups {
    private final File directory;
    private final int retained;

    LayoutBackups(File directory, int retained) {
        if (retained < 2) throw new IllegalArgumentException("At least two snapshots are required");
        this.directory = directory;
        this.retained = retained;
    }

    synchronized void save(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512 * 1024) throw new IOException("Layout is too large");
        if (json.equals(latest())) return;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create backup directory");
        File[] files = snapshots();
        long number = files.length == 0 ? 1 : Long.parseLong(files[files.length - 1].getName().substring(6, 26)) + 1;
        File target = new File(directory, String.format(Locale.ROOT, "state-%020d.json", number));
        File temporary = File.createTempFile(".snapshot-", ".tmp", directory);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(bytes);
                out.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), target.toPath());
            }
            files = snapshots();
            for (int i = 0; i < files.length - retained; i++) Files.deleteIfExists(files[i].toPath());
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    synchronized String latest() throws IOException {
        File[] files = snapshots();
        return files.length == 0 ? null : read(files[files.length - 1]);
    }

    synchronized String previous(String current) throws IOException {
        File[] files = snapshots();
        for (int i = files.length - 1; i >= 0; i--) {
            String value = read(files[i]);
            if (!value.equals(current)) return value;
        }
        return null;
    }

    private File[] snapshots() {
        File[] files = directory.listFiles((dir, name) -> name.matches("state-[0-9]{20}\\.json"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static String read(File file) throws IOException {
        if (file.length() > 512 * 1024) throw new IOException("Invalid backup size");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
