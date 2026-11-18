package app.spicetify.extension.spotify.localserver;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ServerConfigTest {
    private static final String USER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String LIBRARY_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private JellyfinConnection jellyfin(String token) {
        JellyfinClient.Account account = new JellyfinClient.Account(
                new ServerConnection("https://music.example/proxy/", "", ""),
                ServerConfig.deviceId(), USER_ID, "Listener", token);
        return new JellyfinConnection(account, LIBRARY_ID, "Music");
    }

    private SharedPreferences preferences() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("spicetify_local_server", Context.MODE_PRIVATE);
    }

    private void restart() throws Exception {
        Field saved = ServerConfig.class.getDeclaredField("preferences");
        saved.setAccessible(true);
        saved.set(null, null);
        ServerConfig.initialize(RuntimeEnvironment.getApplication());
    }

    private File configFile() {
        return new File(RuntimeEnvironment.getApplication().getNoBackupFilesDir(), ConfigFile.NAME);
    }

    @Before public void setup() throws Exception {
        Field stamp = ServerConfig.class.getDeclaredField("serverStamp");
        stamp.setAccessible(true);
        stamp.set(null, null);
        Field saved = ServerConfig.class.getDeclaredField("preferences");
        saved.setAccessible(true);
        saved.set(null, null);
        Application app = RuntimeEnvironment.getApplication();
        ServerConfig.useSecretsForTesting(SecretBox.inMemory());
        ServerConfig.initialize(app);
        ServerConfig.configure(false, "", "", "");
    }
    @Test public void reconfigurationInvalidatesOldSnapshotAndClearsIndex() {
        ServerConfig.configure(true, "https://dav.example/old/", "old-user", "old-pass");
        ServerConfig.Snapshot old = ServerConfig.snapshot();
        ServerConfig.configure(true, "https://dav.example/new/", "new-user", "new-pass");
        assertFalse(ServerConfig.isCurrent(old));
        assertFalse(ServerConfig.publish(old, () -> fail("Stale scan published")));
        assertTrue(ServerIndex.tracks().isEmpty());
    }
    @Test public void disableCancelsExistingSession() {
        ServerConfig.configure(true, "https://dav.example/music/", "user", "pass");
        ServerConfig.Snapshot old = ServerConfig.snapshot();
        ServerConfig.enabled(false);
        assertFalse(ServerConfig.isCurrent(old));
        assertEquals("Disabled", ServerIndex.status());
        ServerIndex.scanAsync();
        assertEquals("Disabled", ServerIndex.status());
    }
    @Test public void blankPasswordRetentionIsScopedToTheSameRootAndAccount() {
        ServerConfig.configure(true, "https://dav.example/music/", "user", "pass");
        ServerConfig.configure(true, "https://dav.example/music", "user", null);
        assertTrue(ServerConfig.snapshot().hasPassword());
        ServerConfig.configure(true, "https://other.example/music/", "user", null);
        assertFalse(ServerConfig.snapshot().hasPassword());
    }
    @Test public void repeatedInitializationPreservesActiveSession() {
        ServerConfig.configure(true, "https://dav.example/music/", "user", "pass");
        ServerConfig.Snapshot before = ServerConfig.snapshot();
        ServerConfig.initialize(RuntimeEnvironment.getApplication());
        assertSame(before, ServerConfig.snapshot());
    }
    @Test public void explicitEmptyPasswordClearsTheSecret() {
        ServerConfig.configure(true, "https://dav.example/music/", "user", "pass");
        ServerConfig.configure(true, "https://dav.example/music/", "user", "");
        assertFalse(ServerConfig.snapshot().hasPassword());
    }

    @Test public void jellyfinSelectionSurvivesReloadWithoutSavingAWebDavSecret() throws Exception {
        String deviceId = ServerConfig.deviceId();
        ServerConfig.configure(true, "https://dav.example/music/", "user", "webdav-secret");
        ServerConfig.Snapshot previous = ServerConfig.snapshot();
        assertTrue(ServerConfig.configureJellyfinIfCurrent(previous, true, jellyfin("jellyfin-token")));
        assertEquals(ServerConfig.Provider.JELLYFIN, ServerConfig.snapshot().provider());
        assertEquals("Music", ServerConfig.snapshot().jellyfinConnection().libraryName);
        assertFalse(preferences().getAll().values().contains("webdav-secret"));
        assertEquals(deviceId, preferences().getString("device_id", ""));

        Field saved = ServerConfig.class.getDeclaredField("preferences");
        saved.setAccessible(true);
        saved.set(null, null);
        ServerConfig.initialize(RuntimeEnvironment.getApplication());
        assertEquals(deviceId, ServerConfig.deviceId());
        assertEquals(ServerConfig.Provider.JELLYFIN, ServerConfig.snapshot().provider());
        assertEquals("jellyfin-token", ServerConfig.snapshot().jellyfinConnection().token());
        assertTrue(ServerConfig.snapshot().enabled);
    }

    @Test public void staleJellyfinSelectionCannotReplaceChangedSettings() {
        ServerConfig.Snapshot beforeLogin = ServerConfig.snapshot();
        ServerConfig.configure(true, "https://dav.example/music/", "user", "webdav-secret");
        assertFalse(ServerConfig.configureJellyfinIfCurrent(beforeLogin, true, jellyfin("late-token")));
        assertEquals(ServerConfig.Provider.WEBDAV, ServerConfig.snapshot().provider());
        assertFalse(preferences().getAll().values().contains("late-token"));
    }

    @Test public void disableRetainsJellyfinButForgetRemovesItsSessionAndIndex() {
        assertTrue(ServerConfig.configureJellyfinIfCurrent(ServerConfig.snapshot(), true, jellyfin("jellyfin-token")));
        ServerConfig.Snapshot active = ServerConfig.snapshot();
        ServerConfig.enabled(false);
        assertFalse(ServerConfig.isCurrent(active));
        assertEquals(ServerConfig.Provider.JELLYFIN, ServerConfig.snapshot().provider());
        assertEquals("jellyfin-token", ServerConfig.snapshot().jellyfinConnection().token());
        assertTrue(ServerIndex.tracks().isEmpty());
        ServerConfig.forget();
        assertEquals(ServerConfig.Provider.NONE, ServerConfig.snapshot().provider());
        assertFalse(preferences().getAll().values().contains("jellyfin-token"));
        assertEquals("Disabled", ServerIndex.status());
    }

    @Test public void switchingBackToWebDavRemovesInactiveJellyfinCredentials() {
        assertTrue(ServerConfig.configureJellyfinIfCurrent(ServerConfig.snapshot(), true, jellyfin("jellyfin-token")));
        ServerConfig.configure(true, "https://dav.example/music/", "user", "webdav-secret");
        assertEquals(ServerConfig.Provider.WEBDAV, ServerConfig.snapshot().provider());
        assertNull(ServerConfig.snapshot().jellyfinConnection());
        assertTrue(ServerConfig.snapshot().hasPassword());
        assertFalse(preferences().getAll().values().contains("jellyfin-token"));
    }

    @Test public void secretsAreSealedAtRestAndInTheServerFile() throws Exception {
        ServerConfig.configure(true, "https://dav.example/music/", "user", "webdav-secret");
        assertNull(preferences().getString("password", null));
        assertFalse(preferences().getString("password_sealed", "").isEmpty());
        assertFalse(preferences().getAll().values().contains("webdav-secret"));
        assertFalse(new String(Files.readAllBytes(configFile().toPath()), StandardCharsets.ISO_8859_1).contains("webdav-secret"));

        assertTrue(ServerConfig.configureJellyfinIfCurrent(ServerConfig.snapshot(), true, jellyfin("jellyfin-token")));
        assertNull(preferences().getString("jellyfin_token", null));
        assertFalse(preferences().getAll().values().contains("jellyfin-token"));
        assertFalse(new String(Files.readAllBytes(configFile().toPath()), StandardCharsets.ISO_8859_1).contains("jellyfin-token"));
    }

    @Test public void plainSecretsFromOlderVersionsAreSealedOnceAndErased() throws Exception {
        ServerConfig.configure(true, "https://dav.example/music/", "user", "");
        preferences().edit().putString("password", "legacy-secret").apply();
        restart();
        assertEquals("legacy-secret", ServerConfig.snapshot().connection().password());
        assertNull(preferences().getString("password", null));
        assertFalse(preferences().getAll().values().contains("legacy-secret"));
        restart();
        assertEquals("legacy-secret", ServerConfig.snapshot().connection().password());
    }

    @Test public void plainJellyfinTokenFromOlderVersionsIsSealedAndErased() throws Exception {
        assertTrue(ServerConfig.configureJellyfinIfCurrent(ServerConfig.snapshot(), true, jellyfin("placeholder")));
        preferences().edit().putString("jellyfin_token", "legacy-token").remove("jellyfin_token_sealed").apply();
        restart();
        assertEquals("legacy-token", ServerConfig.snapshot().jellyfinConnection().token());
        assertNull(preferences().getString("jellyfin_token", null));
        assertFalse(preferences().getAll().values().contains("legacy-token"));
    }

    @Test public void aPasswordThatCannotBeOpenedIsDroppedAndAskedForAgain() throws Exception {
        ServerConfig.configure(true, "https://dav.example/music/", "user", "pass");
        ServerConfig.useSecretsForTesting(SecretBox.inMemory()); // another key, as after a restore onto another phone
        restart();
        assertEquals(ServerConfig.Provider.WEBDAV, ServerConfig.snapshot().provider());
        assertFalse(ServerConfig.snapshot().hasPassword());
    }

    @Test public void aJellyfinSessionThatCannotBeOpenedIsSignedOut() throws Exception {
        assertTrue(ServerConfig.configureJellyfinIfCurrent(ServerConfig.snapshot(), true, jellyfin("jellyfin-token")));
        ServerConfig.useSecretsForTesting(SecretBox.inMemory());
        restart();
        assertEquals(ServerConfig.Provider.NONE, ServerConfig.snapshot().provider());
        assertFalse(ServerConfig.snapshot().enabled);
    }

    @Test public void theTrackServerProcessLoadsWhatTheMainProcessSaved() {
        Application app = RuntimeEnvironment.getApplication();
        ServerConfig.configure(true, "https://dav.example/music/", "user", "pass");
        ServerConfig.Snapshot main = ServerConfig.snapshot();
        ServerConfig.loadForServer(app);
        ServerConfig.Snapshot server = ServerConfig.snapshot();
        assertNotSame(main, server);
        assertTrue(server.enabled);
        assertEquals("https://dav.example/music/", server.rootUrl());
        assertEquals("user", server.username());
        assertEquals("pass", server.connection().password());
        ServerConfig.loadForServer(app);
        assertSame("An unchanged file must not replace a running session", server, ServerConfig.snapshot());
        ServerConfig.enabled(false);
        ServerConfig.loadForServer(app);
        assertFalse(ServerConfig.snapshot().enabled);
        assertEquals("pass", ServerConfig.snapshot().connection().password());
    }

    @Test public void theTrackServerProcessCarriesTheJellyfinSession() {
        Application app = RuntimeEnvironment.getApplication();
        assertTrue(ServerConfig.configureJellyfinIfCurrent(ServerConfig.snapshot(), true, jellyfin("jellyfin-token")));
        ServerConfig.loadForServer(app);
        assertEquals(ServerConfig.Provider.JELLYFIN, ServerConfig.snapshot().provider());
        assertEquals("jellyfin-token", ServerConfig.snapshot().jellyfinConnection().token());
        assertEquals(ServerConfig.deviceId(), ServerConfig.snapshot().jellyfinConnection().deviceId());
    }

    @Test public void theTrackServerProcessIsDisabledWithoutOrWithADamagedFile() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ServerConfig.configure(true, "https://dav.example/music/", "user", "pass");
        assertTrue(configFile().delete());
        ServerConfig.loadForServer(app);
        assertFalse(ServerConfig.snapshot().enabled);
        Files.write(configFile().toPath(), "url=\\uZZZZ\n".getBytes(StandardCharsets.ISO_8859_1));
        ServerConfig.loadForServer(app);
        assertFalse(ServerConfig.snapshot().enabled);
    }
}
