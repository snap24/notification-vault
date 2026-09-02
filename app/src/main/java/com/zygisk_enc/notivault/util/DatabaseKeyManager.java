package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Manages the 256-bit passphrase for SQLCipher whole-database encryption.
 * The raw passphrase is generated once on-device, encrypted using an AES-256 key
 * inside the hardware AndroidKeyStore (TEE / StrongBox), and stored in private app preferences.
 */
public class DatabaseKeyManager {

    private static final String KEY_ALIAS = "NotiVaultDatabaseMasterKey";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int PASSPHRASE_LENGTH_BYTES = 32; // 256 bits

    private static final String PREFS_NAME = "notivault_secure_keystore_prefs";
    private static final String PREF_KEY_ENCRYPTED_PASSPHRASE = "encrypted_db_passphrase";
    private static final String PREF_KEY_IV = "encrypted_db_iv";

    private static volatile SecretKey cachedMasterKey = null;

    private static synchronized SecretKey getMasterKey() throws Exception {
        if (cachedMasterKey != null) {
            return cachedMasterKey;
        }
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256);
            keyGenerator.init(builder.build());
            cachedMasterKey = keyGenerator.generateKey();
        } else {
            cachedMasterKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        return cachedMasterKey;
    }

    /**
     * Retrieves or creates the 256-bit SQLCipher passphrase.
     * Decrypted in memory and returned as a byte array.
     */
    public static synchronized byte[] getDatabasePassphrase(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encPassphraseB64 = prefs.getString(PREF_KEY_ENCRYPTED_PASSPHRASE, null);
        String ivB64 = prefs.getString(PREF_KEY_IV, null);

        if (encPassphraseB64 != null && ivB64 != null) {
            try {
                byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
                byte[] encryptedBytes = Base64.decode(encPassphraseB64, Base64.NO_WRAP);

                SecretKey masterKey = getMasterKey();
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.DECRYPT_MODE, masterKey, spec);

                return cipher.doFinal(encryptedBytes);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Generate a new 256-bit passphrase
        byte[] rawPassphrase = new byte[PASSPHRASE_LENGTH_BYTES];
        new SecureRandom().nextBytes(rawPassphrase);

        try {
            SecretKey masterKey = getMasterKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey);
            byte[] iv = cipher.getIV();
            byte[] encryptedBytes = cipher.doFinal(rawPassphrase);

            prefs.edit()
                    .putString(PREF_KEY_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                    .putString(PREF_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();

            return rawPassphrase;
        } catch (Exception e) {
            e.printStackTrace();
            return rawPassphrase;
        }
    }

    public static synchronized boolean saveNewPassphrase(Context context, byte[] newPassphrase) {
        if (context == null || newPassphrase == null) return false;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SecretKey masterKey = getMasterKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey);
            byte[] iv = cipher.getIV();
            byte[] encryptedBytes = cipher.doFinal(newPassphrase);

            prefs.edit()
                    .putString(PREF_KEY_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                    .putString(PREF_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String getMasterKeyAlias() {
        return KEY_ALIAS;
    }

    public static String getKeyStoreProvider() {
        return KEYSTORE_PROVIDER;
    }
}
