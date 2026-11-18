package app.spicetify.extension.spotify.localserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The server settings as Spotify's main process hands them to the track server process: one small file,
 * replaced whole so a reader sees the old settings or the new ones and never half of each. It replaces
 * {@code Context.MODE_MULTI_PROCESS} preferences, which Android deprecated because they do not guard
 * against a reader catching a write. Secrets in it are already sealed by {@link SecretBox}.
 */
final class ConfigFile {
    static final String NAME = "spicetify_server_config.properties";

    private ConfigFile() {}

    /** A cheap way to tell that the file was replaced since it was last read. */
    static final class Stamp {
        static final Stamp ABSENT = new Stamp(-1, -1);
        final long modified, length;

        Stamp(long modified, long length) {
            this.modified = modified;
            this.length = length;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Stamp && modified == ((Stamp) other).modified && length == ((Stamp) other).length;
        }

        @Override public int hashCode() { return Long.hashCode(modified) * 31 + Long.hashCode(length); }
    }

    /** Take the stamp before reading: a file replaced in between is then simply read again next time. */
    static Stamp stamp(File file) {
        return file.isFile() ? new Stamp(file.lastModified(), file.length()) : Stamp.ABSENT;
    }

    /** Writes {@code values} to a temporary file, flushes it to disk, then renames it over {@code file}. */
    static void write(File file, Map<String, String> values) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        Properties properties = new Properties();
        properties.putAll(values);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            properties.store((OutputStream) out, null);
            out.getFD().sync();
        } catch (IOException | RuntimeException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(file)) {
            temporary.delete();
            throw new IOException("Could not replace the server settings file.");
        }
    }

    /** The saved values, or null when there is no file. */
    static Map<String, String> read(File file) throws IOException {
        if (!file.isFile()) return null;
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            properties.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("The server settings file is damaged.", malformed);
        }
        Map<String, String> values = new HashMap<>();
        for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
        return values;
    }
}
