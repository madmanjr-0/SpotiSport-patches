package app.spicetify.extension.spotify.localserver;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Seals a saved secret (a WebDAV password, a Jellyfin session token) with AES-256-GCM so it is never
 * written to disk in the clear. The key comes from {@link Keys}; on a device that is the Android Keystore,
 * whose key never leaves the secure hardware or the system process and is shared by every process of the
 * app. A sealed value is bound to its {@code purpose}, so one field's value cannot be copied into another.
 *
 * <p>Opening never throws: a value that cannot be opened (the key is gone after a restore onto another
 * device, the value was altered) is reported as {@code null} and the caller asks the person to sign in
 * again.
 */
public final class SecretBox {
    /** Where the key lives. {@code create} is true only for the process that saves secrets. */
    public interface Keys {
        SecretKey key(boolean create) throws GeneralSecurityException;
    }

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Keys keys;

    public SecretBox(Keys keys) {
        this.keys = keys;
    }

    /** A box with a throwaway key held in memory, for tests that cannot reach the Android Keystore. */
    public static SecretBox inMemory() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, new SecureRandom());
            SecretKey key = generator.generateKey();
            return new SecretBox(create -> key);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Returns the sealed form of {@code plain} as hex text, or throws if the key cannot be used. */
    String seal(String plain, String purpose) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(true)); // the provider picks a fresh random IV
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException("Unexpected IV length.");
        byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = Arrays.copyOf(iv, IV_BYTES + body.length);
        System.arraycopy(body, 0, sealed, IV_BYTES, body.length);
        return hex(sealed);
    }

    /** Returns the secret, or null when {@code sealed} cannot be opened for {@code purpose}. */
    String open(String sealed, String purpose) {
        try {
            byte[] data = unhex(sealed);
            if (data == null || data.length <= IV_BYTES + TAG_BITS / 8) return null;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keys.key(false), new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException unusable) {
            return null;
        }
    }

    private static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = HEX[(data[i] >> 4) & 15];
            out[i * 2 + 1] = HEX[data[i] & 15];
        }
        return new String(out);
    }

    private static byte[] unhex(String text) {
        if (text == null || text.length() % 2 != 0) return null;
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16), low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            out[i] = (byte) (high << 4 | low);
        }
        return out;
    }
}
