package app.spicetify.extension.spotify.localserver;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The Android Keystore key that seals saved server secrets. Keystore keys belong to the app's user ID, so
 * Spotify's main process and the track server process open the same key. Only the process that saves
 * secrets (the main one) may create it, so the two can never race to create two different keys.
 */
final class DeviceKeys implements SecretBox.Keys {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "spicetify_server_secrets";

    @Override
    public synchronized SecretKey key(boolean create) throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            Key existing = store.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            if (!create) throw new GeneralSecurityException("The saved-secret key does not exist.");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        } catch (IOException error) {
            throw new GeneralSecurityException(error);
        }
    }
}
