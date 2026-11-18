package app.spicetify.extension.spotify.localserver;

import static org.junit.Assert.*;

import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public class SecretBoxTest {
    private static final String PURPOSE = "webdav-password";

    @Test public void sealedSecretOpensToTheOriginal() throws Exception {
        SecretBox box = SecretBox.inMemory();
        assertEquals("pässword ✓ with spaces", box.open(box.seal("pässword ✓ with spaces", PURPOSE), PURPOSE));
    }

    @Test public void sealedFormNeverContainsTheSecretAndDiffersEveryTime() throws Exception {
        SecretBox box = SecretBox.inMemory();
        String first = box.seal("hunter2-hunter2", PURPOSE), second = box.seal("hunter2-hunter2", PURPOSE);
        assertFalse(first.contains("hunter2"));
        assertNotEquals(first, second);
    }

    @Test public void aSecretCannotBeMovedToAnotherField() throws Exception {
        SecretBox box = SecretBox.inMemory();
        assertNull(box.open(box.seal("token", "jellyfin-token"), PURPOSE));
    }

    @Test public void aChangedValueIsRefused() throws Exception {
        SecretBox box = SecretBox.inMemory();
        String sealed = box.seal("secret", PURPOSE);
        char last = sealed.charAt(sealed.length() - 1);
        String changed = sealed.substring(0, sealed.length() - 1) + (last == '0' ? '1' : '0');
        assertNull(box.open(changed, PURPOSE));
        assertNull(box.open(sealed.substring(0, sealed.length() - 2), PURPOSE));
    }

    @Test public void aValueSealedWithAnotherKeyIsRefused() throws Exception {
        assertNull(SecretBox.inMemory().open(SecretBox.inMemory().seal("secret", PURPOSE), PURPOSE));
    }

    @Test public void garbageIsRefusedWithoutThrowing() {
        SecretBox box = SecretBox.inMemory();
        for (String garbage : new String[]{null, "", "zz", "abc", "0123456789abcdef", "not hex at all!!"})
            assertNull(garbage, box.open(garbage, PURPOSE));
    }

    @Test public void openingNeverAsksForTheKeyToBeCreated() throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        AtomicBoolean createRequested = new AtomicBoolean();
        SecretBox saving = new SecretBox(create -> key);
        String sealed = saving.seal("secret", PURPOSE);
        SecretBox reading = new SecretBox(create -> {
            if (create) createRequested.set(true);
            return key;
        });
        assertEquals("secret", reading.open(sealed, PURPOSE));
        assertFalse(createRequested.get());
    }

    @Test public void aMissingKeyMeansNullWhenOpeningAndAnErrorWhenSealing() throws Exception {
        SecretBox box = SecretBox.inMemory();
        String sealed = box.seal("secret", PURPOSE);
        SecretBox noKey = new SecretBox(create -> { throw new GeneralSecurityException("gone"); });
        assertNull(noKey.open(sealed, PURPOSE));
        try {
            noKey.seal("secret", PURPOSE);
            fail("Sealing without a key must fail instead of storing the secret in the clear");
        } catch (GeneralSecurityException expected) {
            // fine
        }
    }
}
