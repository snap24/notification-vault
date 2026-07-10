package com.zygisk_enc.notivault.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class EncryptionHelper {

    private static final String KEY_ALIAS = "NotiVaultMasterKey";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;

    private static synchronized SecretKey getSecretKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
                keyGenerator.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                keyGenerator.generateKey();
            }
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void handleKeyInvalidated() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            keyGenerator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            keyGenerator.generateKey();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            return encryptInternal(plainText);
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            handleKeyInvalidated();
            try {
                return encryptInternal(plainText);
            } catch (Exception ex) {
                ex.printStackTrace();
                return plainText;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return plainText;
        }
    }

    private static String encryptInternal(String plainText) throws Exception {
        SecretKey secretKey = getSecretKey();
        if (secretKey == null) throw new Exception("SecretKey is null");

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        String ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP);
        String cipherTextBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP);

        return ivBase64 + ":" + cipherTextBase64;
    }

    public static String decrypt(String encryptedText) {
        if (encryptedText == null) return null;
        if (!encryptedText.contains(":")) return encryptedText; // Plain text fallback
        try {
            return decryptInternal(encryptedText);
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            handleKeyInvalidated();
            try {
                return decryptInternal(encryptedText);
            } catch (Exception ex) {
                ex.printStackTrace();
                return encryptedText;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return encryptedText;
        }
    }

    private static String decryptInternal(String encryptedText) throws Exception {
        String[] parts = encryptedText.split(":");
        if (parts.length != 2) return encryptedText;

        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);

        SecretKey secretKey = getSecretKey();
        if (secretKey == null) throw new Exception("SecretKey is null");

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        byte[] decrypted = cipher.doFinal(cipherText);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    public static boolean encryptFile(byte[] plainBytes, File targetFile) {
        if (plainBytes == null || targetFile == null) return false;
        try {
            return encryptFileInternal(plainBytes, targetFile);
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            handleKeyInvalidated();
            try {
                return encryptFileInternal(plainBytes, targetFile);
            } catch (Exception ex) {
                ex.printStackTrace();
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean encryptFileInternal(byte[] plainBytes, File targetFile) throws Exception {
        SecretKey secretKey = getSecretKey();
        if (secretKey == null) throw new Exception("SecretKey is null");

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plainBytes);

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(iv.length);
            fos.write(iv);
            fos.write(encrypted);
            return true;
        }
    }

    public static byte[] decryptFile(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) return null;
        try {
            return decryptFileInternal(sourceFile);
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            handleKeyInvalidated();
            try {
                return decryptFileInternal(sourceFile);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static byte[] decryptFileInternal(File sourceFile) throws Exception {
        SecretKey secretKey = getSecretKey();
        if (secretKey == null) throw new Exception("SecretKey is null");

        byte[] fileBytes;
        try (FileInputStream fis = new FileInputStream(sourceFile)) {
            int length = (int) sourceFile.length();
            fileBytes = new byte[length];
            int read = fis.read(fileBytes);
            if (read != length) return null;
        }

        if (fileBytes.length < 2) return null;

        int ivLength = fileBytes[0] & 0xFF;
        if (fileBytes.length < 1 + ivLength) return null;

        byte[] iv = new byte[ivLength];
        System.arraycopy(fileBytes, 1, iv, 0, ivLength);

        int encryptedLength = fileBytes.length - 1 - ivLength;
        byte[] encrypted = new byte[encryptedLength];
        System.arraycopy(fileBytes, 1 + ivLength, encrypted, 0, encryptedLength);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        return cipher.doFinal(encrypted);
    }
}
