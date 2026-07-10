package com.zygisk_enc.notivault.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class EncryptionHelper {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALIAS = "notivault_secure_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
}
