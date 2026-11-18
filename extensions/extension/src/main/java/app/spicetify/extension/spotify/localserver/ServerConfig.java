package app.spicetify.extension.spotify.localserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ServerConfig {
    public enum Provider { NONE, WEBDAV, JELLYFIN }

    private static final String TAG = "SpicetifyServer";
    private static final String DEVICE_ID = "device_id";
    private static final String JELLYFIN_ROOT = "jellyfin_root";
    private static final String JELLYFIN_USER_ID = "jellyfin_user_id";
    private static final String JELLYFIN_USER_NAME = "jellyfin_user_name";
    private static final String JELLYFIN_LIBRARY_ID = "jellyfin_library_id";
    private static final String JELLYFIN_LIBRARY_NAME = "jellyfin_library_name";
    /** Secrets are saved sealed under these keys; the plain-text keys below are only read to migrate old installs. */
    private static final String PASSWORD_SEALED = "password_sealed";
    private static final String JELLYFIN_TOKEN_SEALED = "jellyfin_token_sealed";
    private static final String LEGACY_PASSWORD = "password";
    private static final String LEGACY_JELLYFIN_TOKEN = "jellyfin_token";
    private static final String PASSWORD_PURPOSE = "webdav-password";
    private static final String TOKEN_PURPOSE = "jellyfin-token";
    private static SharedPreferences preferences;
    private static Context context;
    private static File configFile;
    private static SecretBox secrets = new SecretBox(new DeviceKeys());
    /** Track server process only: what was last loaded from the file the main process writes. */
    private static ConfigFile.Stamp serverStamp;
    private static volatile Snapshot current = new Snapshot(false, null, null);
    private ServerConfig() {}

    /** Replaces the key that seals saved secrets, for tests that have no Android Keystore. */
    public static synchronized void useSecretsForTesting(SecretBox box) { secrets = box; }

    public static final class Snapshot {
        public final boolean enabled;
        private final ServerConnection connection;
        private final JellyfinConnection jellyfin;
        Snapshot(boolean enabled, ServerConnection connection, JellyfinConnection jellyfin) {
            if (connection != null && jellyfin != null) throw new IllegalArgumentException("Select one server provider.");
            this.enabled = enabled; this.connection = connection; this.jellyfin = jellyfin;
        }
        public Provider provider() { return jellyfin != null ? Provider.JELLYFIN : connection != null ? Provider.WEBDAV : Provider.NONE; }
        public String rootUrl() { return connection == null ? "" : connection.root.toASCIIString(); }
        public String username() { return connection == null ? "" : connection.username; }
        public boolean hasPassword() { return connection != null && connection.hasPassword(); }
        public JellyfinConnection jellyfinConnection() { return jellyfin; }
        ServerConnection connection() { return connection; }
    }

    /** Reads saved values by name; a missing one is the empty string. */
    private interface Values { String get(String key); }

    public static synchronized void initialize(Context supplied) {
        if (preferences != null) return;
        context = supplied.getApplicationContext();
        configFile = new File(context.getNoBackupFilesDir(), ConfigFile.NAME);
        preferences = context.getSharedPreferences("spicetify_local_server", Context.MODE_PRIVATE);
        if (preferences.getString(DEVICE_ID, "").isEmpty())
            preferences.edit().putString(DEVICE_ID, UUID.randomUUID().toString()).apply();
        migratePlainSecrets();
        try {
            current = restore(key -> {
                if ("enabled".equals(key)) return String.valueOf(preferences.getBoolean("enabled", false));
                return preferences.getString(key, "");
            });
        } catch (IllegalArgumentException ignored) {
            forget();
            return;
        }
        publishToServer();
    }

    /**
     * Builds the settings from saved values, opening the sealed secrets. A secret that cannot be opened (its key
     * is gone, for example after the app's data was restored onto another phone) comes back empty: a WebDAV
     * folder then asks for its password again, and a Jellyfin session is refused so the person signs in again.
     */
    private static Snapshot restore(Values saved) {
        boolean enabled = "true".equals(saved.get("enabled"));
        if ("jellyfin".equals(saved.get("provider"))) {
            return new Snapshot(enabled, null, JellyfinConnection.restore(
                    saved.get(JELLYFIN_ROOT), saved.get(DEVICE_ID),
                    saved.get(JELLYFIN_USER_ID), saved.get(JELLYFIN_USER_NAME),
                    unseal(saved.get(JELLYFIN_TOKEN_SEALED), TOKEN_PURPOSE),
                    saved.get(JELLYFIN_LIBRARY_ID), saved.get(JELLYFIN_LIBRARY_NAME)));
        }
        String url = saved.get("url");
        ServerConnection connection = url.isEmpty() ? null : new ServerConnection(url,
                saved.get("username"), unseal(saved.get(PASSWORD_SEALED), PASSWORD_PURPOSE));
        return new Snapshot(enabled && connection != null, connection, null);
    }

    /** Settings saved by older versions kept the secrets as plain text; seal them once, then erase the plain copy. */
    private static void migratePlainSecrets() {
        String password = preferences.getString(LEGACY_PASSWORD, null);
        String token = preferences.getString(LEGACY_JELLYFIN_TOKEN, null);
        if (password == null && token == null) return;
        SharedPreferences.Editor edit = preferences.edit();
        try {
            if (password != null) edit.putString(PASSWORD_SEALED, seal(password, PASSWORD_PURPOSE));
            if (token != null) edit.putString(JELLYFIN_TOKEN_SEALED, seal(token, TOKEN_PURPOSE));
        } catch (IllegalArgumentException cannotSeal) {
            // No plain-text copy is kept when the Keystore fails: the person enters the secret again.
            Log.w(TAG, "Could not protect the saved server secrets; they were removed.");
        }
        edit.remove(LEGACY_PASSWORD).remove(LEGACY_JELLYFIN_TOKEN).apply();
    }

    private static String seal(String secret, String purpose) {
        if (secret.isEmpty()) return "";
        try {
            return secrets.seal(secret, purpose);
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new IllegalArgumentException("This phone could not store the secret securely. Try again, or restart Spotify.");
        }
    }

    private static String unseal(String sealed, String purpose) {
        if (sealed == null || sealed.isEmpty()) return "";
        String secret = secrets.open(sealed, purpose);
        if (secret == null) {
            Log.w(TAG, "A saved server secret could not be opened; it must be entered again.");
            return "";
        }
        return secret;
    }

    /**
     * Hands the saved settings to the track server process: a copy of the preferences, with the secrets still
     * sealed, in a file that is replaced whole. Written before a change returns, so the server process never
     * serves with settings older than the ones the person just saved.
     */
    private static void publishToServer() {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            if (entry.getValue() != null) values.put(entry.getKey(), String.valueOf(entry.getValue()));
        try {
            ConfigFile.write(configFile, values);
        } catch (IOException error) {
            Log.w(TAG, "Could not save the settings for the track server process", error);
        }
    }

    /**
     * Runs in the track server process: loads the settings the main process saved, when the file has been replaced
     * since the last call. Never writes, and never creates the key that seals the secrets.
     */
    static synchronized void loadForServer(Context supplied) {
        Context app = supplied.getApplicationContext();
        File file = new File(app.getNoBackupFilesDir(), ConfigFile.NAME);
        ConfigFile.Stamp stamp = ConfigFile.stamp(file);
        if (context != null && stamp.equals(serverStamp)) return;
        context = app;
        Snapshot loaded = new Snapshot(false, null, null);
        try {
            Map<String, String> saved = ConfigFile.read(file);
            if (saved != null) loaded = restore(key -> {
                String value = saved.get(key);
                return value == null ? "" : value;
            });
        } catch (IOException | IllegalArgumentException invalid) {
            Log.w(TAG, "The saved server settings could not be used: " + invalid.getMessage());
        }
        serverStamp = stamp;
        current = loaded;
    }

    public static Snapshot snapshot() { return current; }
    public static synchronized String deviceId() {
        requireInitialized();
        return preferences.getString(DEVICE_ID, "");
    }
    static Context context() { return context; }
    static boolean isCurrent(Snapshot snapshot) { return current == snapshot && snapshot.enabled; }
    static synchronized boolean publish(Snapshot snapshot, Runnable update) {
        if (!isCurrent(snapshot)) return false;
        update.run(); return true;
    }

    /** Null password retains the saved password only for the same server/account. Empty clears it. */
    public static synchronized void configure(boolean enabled, String rootUrl, String username, String password) {
        requireSupported(enabled);
        requireInitialized();
        ServerConnection connection = null;
        if (!rootUrl.trim().isEmpty()) {
            connection = new ServerConnection(rootUrl, username, password == null ? "" : password);
            if (password == null && current.connection != null && connection.root.equals(current.connection.root)
                    && connection.username.equals(current.connection.username)) {
                connection = new ServerConnection(rootUrl, username, current.connection.password());
            }
        } else if (enabled) throw new IllegalArgumentException("Enter an HTTPS WebDAV folder URL first.");
        String sealed = connection == null ? "" : seal(connection.password(), PASSWORD_PURPOSE);
        current = new Snapshot(enabled, connection, null);
        removeJellyfin(preferences.edit().putBoolean("enabled", enabled)
                .putString("provider", connection == null ? "" : "webdav")
                .putString("url", current.rootUrl()).putString("username", current.username())
                .putString(PASSWORD_SEALED, sealed).remove(LEGACY_PASSWORD)).apply();
        publishToServer();
        ServerIndex.invalidate();
    }

    /** Publishes an asynchronous library choice only while its starting settings are still current. */
    public static synchronized boolean configureJellyfinIfCurrent(Snapshot expected, boolean enabled, JellyfinConnection connection) {
        requireSupported(enabled);
        requireInitialized();
        if (expected != current) return false;
        if (connection == null || !deviceId().equals(connection.deviceId()))
            throw new IllegalArgumentException("The Jellyfin session belongs to another installation.");
        String sealedToken = seal(connection.token(), TOKEN_PURPOSE);
        current = new Snapshot(enabled, null, connection);
        preferences.edit().putBoolean("enabled", enabled).putString("provider", "jellyfin")
                .remove("url").remove("username").remove(PASSWORD_SEALED).remove(LEGACY_PASSWORD)
                .putString(JELLYFIN_ROOT, connection.root.toASCIIString())
                .putString(JELLYFIN_USER_ID, connection.userId).putString(JELLYFIN_USER_NAME, connection.userName)
                .putString(JELLYFIN_TOKEN_SEALED, sealedToken).remove(LEGACY_JELLYFIN_TOKEN)
                .putString(JELLYFIN_LIBRARY_ID, connection.libraryId)
                .putString(JELLYFIN_LIBRARY_NAME, connection.libraryName).apply();
        publishToServer();
        ServerIndex.invalidate();
        return true;
    }

    public static synchronized void enabled(boolean value) {
        requireSupported(value);
        requireInitialized();
        if (value && current.provider() == Provider.NONE) throw new IllegalArgumentException("Choose a server first.");
        if (current.enabled == value) return;
        current = new Snapshot(value, current.connection, current.jellyfin);
        preferences.edit().putBoolean("enabled", value).apply();
        publishToServer();
        ServerIndex.invalidate();
    }

    public static synchronized void forget() {
        requireInitialized();
        String savedDeviceId = deviceId();
        current = new Snapshot(false, null, null);
        preferences.edit().clear().putString(DEVICE_ID, savedDeviceId).apply();
        publishToServer();
        ServerIndex.invalidate();
    }

    private static SharedPreferences.Editor removeJellyfin(SharedPreferences.Editor edit) {
        return edit.remove(JELLYFIN_ROOT).remove(JELLYFIN_USER_ID).remove(JELLYFIN_USER_NAME)
                .remove(JELLYFIN_TOKEN_SEALED).remove(LEGACY_JELLYFIN_TOKEN).remove(JELLYFIN_LIBRARY_ID)
                .remove(JELLYFIN_LIBRARY_NAME);
    }
    private static void requireInitialized() {
        if (preferences == null) throw new IllegalStateException("Server settings are not initialized.");
    }
    private static void requireSupported(boolean enabled) {
        if (enabled && android.os.Build.VERSION.SDK_INT < 26)
            throw new IllegalArgumentException("Server files requires Android 8 or later.");
    }
}
