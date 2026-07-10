package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.net.Uri;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

public class BackupUtil {

    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH = 256;

    public interface BackupCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    public interface BackupProgressListener extends BackupCallback {
        void onProgress(int progress);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    private static byte[] encryptBytes(byte[] rawBytes, String password) throws Exception {
        byte[] salt = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        
        SecretKey key = deriveKey(password, salt);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
        
        byte[] ciphertext = cipher.doFinal(rawBytes);
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(salt);
        bos.write(iv);
        bos.write(ciphertext);
        return bos.toByteArray();
    }

    private static byte[] decryptBytes(byte[] encryptedBytes, String password) throws Exception {
        if (encryptedBytes.length < 28) {
            throw new Exception("Invalid encrypted file length");
        }
        
        byte[] salt = new byte[16];
        System.arraycopy(encryptedBytes, 0, salt, 0, 16);
        
        byte[] iv = new byte[12];
        System.arraycopy(encryptedBytes, 16, iv, 0, 12);
        
        int ciphertextLen = encryptedBytes.length - 28;
        byte[] ciphertext = new byte[ciphertextLen];
        System.arraycopy(encryptedBytes, 28, ciphertext, 0, ciphertextLen);
        
        SecretKey key = deriveKey(password, salt);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
        
        return cipher.doFinal(ciphertext);
    }

    private static boolean isZip(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        return bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    public static void exportBackup(Context context, Uri fileUri, String password, boolean includeMedia, BackupCallback callback) {
        AppExecutor.execute(() -> {
            try {
                List<NotificationEntity> notifications = AppDatabase.getInstance(context)
                        .notificationDao().getAllNotificationsSync();

                JSONArray jsonArray = new JSONArray();
                Map<String, byte[]> mediaFiles = new HashMap<>();

                int total = notifications.size();
                for (int i = 0; i < total; i++) {
                    NotificationEntity notif = notifications.get(i);
                    JSONObject obj = new JSONObject();
                    obj.put("packageName", notif.packageName);
                    obj.put("appName", notif.appName);
                    
                    // Decrypt using current device's local Keystore to export as plaintext (secured inside encrypted zip)
                    String decryptedTitle = EncryptionHelper.decrypt(notif.title);
                    String decryptedText = EncryptionHelper.decrypt(notif.text);
                    String decryptedBigText = notif.bigText != null ? EncryptionHelper.decrypt(notif.bigText) : null;

                    obj.put("title", decryptedTitle);
                    obj.put("text", decryptedText);
                    obj.put("bigText", decryptedBigText != null ? decryptedBigText : JSONObject.NULL);
                    
                    obj.put("timestamp", notif.timestamp);
                    obj.put("isRead", notif.isRead ? 1 : 0);
                    obj.put("isFavorite", notif.isFavorite ? 1 : 0);

                    // If including media, decrypt file using local Keystore to put raw bytes in Zip
                    if (includeMedia && notif.imagePath != null && !notif.imagePath.isEmpty()) {
                        File imgFile = new File(notif.imagePath);
                        if (imgFile.exists()) {
                            byte[] decryptedImageBytes = EncryptionHelper.decryptFile(imgFile);
                            if (decryptedImageBytes != null) {
                                String fileName = imgFile.getName();
                                mediaFiles.put(fileName, decryptedImageBytes);
                                obj.put("imagePath", fileName); // Store filename as key
                            } else {
                                obj.put("imagePath", JSONObject.NULL);
                            }
                        } else {
                            obj.put("imagePath", JSONObject.NULL);
                        }
                    } else {
                        obj.put("imagePath", JSONObject.NULL);
                    }
                    
                    jsonArray.put(obj);

                    if (callback instanceof BackupProgressListener) {
                        int progress = ((i + 1) * 100) / total;
                        ((BackupProgressListener) callback).onProgress(progress);
                    }
                }

                JSONObject rootJson = new JSONObject();
                rootJson.put("version", 2);
                rootJson.put("notifications", jsonArray);

                JSONArray toastsArray = new JSONArray();
                try {
                    List<com.zygisk_enc.notivault.database.ToastEntity> toasts = AppDatabase.getInstance(context)
                            .toastDao().getAllToastsSync();
                    for (com.zygisk_enc.notivault.database.ToastEntity toast : toasts) {
                        JSONObject obj = new JSONObject();
                        obj.put("packageName", toast.packageName);
                        obj.put("appName", toast.appName);
                        obj.put("text", EncryptionHelper.decrypt(toast.text));
                        obj.put("timestamp", toast.timestamp);
                        toastsArray.put(obj);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                rootJson.put("toasts", toastsArray);

                String jsonString = rootJson.toString(2);
                byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
                byte[] finalBytesToEncrypt;

                if (includeMedia && !mediaFiles.isEmpty()) {
                    // Create a Zip Archive in memory
                    ByteArrayOutputStream zipBos = new ByteArrayOutputStream();
                    try (ZipOutputStream zos = new ZipOutputStream(zipBos)) {
                        ZipEntry jsonEntry = new ZipEntry("backup_data.json");
                        zos.putNextEntry(jsonEntry);
                        zos.write(jsonBytes);
                        zos.closeEntry();

                        for (Map.Entry<String, byte[]> entry : mediaFiles.entrySet()) {
                            ZipEntry mediaEntry = new ZipEntry("media/" + entry.getKey());
                            zos.putNextEntry(mediaEntry);
                            zos.write(entry.getValue());
                            zos.closeEntry();
                        }
                    }
                    finalBytesToEncrypt = zipBos.toByteArray();
                } else {
                    finalBytesToEncrypt = jsonBytes;
                }

                // Encrypt bytes using the user password
                byte[] encryptedData = encryptBytes(finalBytesToEncrypt, password);

                try (OutputStream os = context.getContentResolver().openOutputStream(fileUri)) {
                    if (os != null) {
                        os.write(encryptedData);
                        callback.onSuccess();
                    } else {
                        callback.onFailure(new Exception("Output stream is null"));
                    }
                }
            } catch (Exception e) {
                callback.onFailure(e);
            }
        });
    }

    public static void importBackup(Context context, Uri fileUri, String password, BackupCallback callback) {
        AppExecutor.execute(() -> {
            try {
                byte[] fileBytes;
                try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                    if (is == null) {
                        callback.onFailure(new Exception("Input stream is null"));
                        return;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[2048];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                    fileBytes = bos.toByteArray();
                }

                // Decrypt using user password
                byte[] decrypted;
                try {
                    decrypted = decryptBytes(fileBytes, password);
                } catch (Exception e) {
                    callback.onFailure(new Exception("Incorrect password or corrupted backup file."));
                    return;
                }

                String jsonString;
                Map<String, String> mediaPathMap = new HashMap<>();

                if (isZip(decrypted)) {
                    // Unzip archive
                    ByteArrayInputStream bis = new ByteArrayInputStream(decrypted);
                    ZipInputStream zis = new ZipInputStream(bis);
                    ZipEntry entry;
                    
                    byte[] buffer = new byte[2048];
                    ByteArrayOutputStream jsonBos = null;

                    while ((entry = zis.getNextEntry()) != null) {
                        ByteArrayOutputStream entryBos = new ByteArrayOutputStream();
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            entryBos.write(buffer, 0, len);
                        }
                        byte[] entryBytes = entryBos.toByteArray();

                        if (entry.getName().equals("backup_data.json")) {
                            jsonBos = entryBos;
                        } else if (entry.getName().startsWith("media/")) {
                            String fileName = new File(entry.getName()).getName();
                            File localFile = new File(context.getFilesDir(), fileName);
                            
                            // Re-encrypt file using the device KeyStore master key
                            boolean success = EncryptionHelper.encryptFile(entryBytes, localFile);
                            if (success) {
                                mediaPathMap.put(fileName, localFile.getAbsolutePath());
                            }
                        }
                        zis.closeEntry();
                    }
                    zis.close();

                    if (jsonBos == null) {
                        callback.onFailure(new Exception("Backup JSON data not found in Zip."));
                        return;
                    }
                    jsonString = new String(jsonBos.toByteArray(), StandardCharsets.UTF_8);
                } else {
                    jsonString = new String(decrypted, StandardCharsets.UTF_8);
                }

                JSONArray notificationsArray = null;
                JSONArray toastsArray = null;

                if (jsonString.trim().startsWith("[")) {
                    // Old format: root is array of notifications
                    notificationsArray = new JSONArray(jsonString);
                } else {
                    // New format: root is object
                    JSONObject rootJson = new JSONObject(jsonString);
                    notificationsArray = rootJson.optJSONArray("notifications");
                    toastsArray = rootJson.optJSONArray("toasts");
                }

                List<NotificationEntity> notifications = new ArrayList<>();
                if (notificationsArray != null) {
                    for (int i = 0; i < notificationsArray.length(); i++) {
                        JSONObject obj = notificationsArray.getJSONObject(i);
                        String titlePlain = obj.optString("title", "");
                        String textPlain = obj.optString("text", "");
                        String bigTextPlain = obj.isNull("bigText") ? null : obj.optString("bigText");

                        String encTitle = EncryptionHelper.encrypt(titlePlain);
                        String encText = EncryptionHelper.encrypt(textPlain);
                        String encBigText = bigTextPlain != null ? EncryptionHelper.encrypt(bigTextPlain) : null;

                        NotificationEntity notif = new NotificationEntity(
                                obj.optString("packageName", "unknown"),
                                obj.optString("appName", "Unknown App"),
                                encTitle,
                                encText,
                                encBigText,
                                obj.optLong("timestamp", System.currentTimeMillis())
                        );
                        notif.isRead = obj.optInt("isRead", 0) == 1;
                        notif.isFavorite = obj.optInt("isFavorite", 0) == 1;

                        String exportedFileName = obj.isNull("imagePath") ? null : obj.optString("imagePath");
                        if (exportedFileName != null && mediaPathMap.containsKey(exportedFileName)) {
                            notif.imagePath = mediaPathMap.get(exportedFileName);
                        } else {
                            notif.imagePath = null;
                        }

                        notifications.add(notif);
                    }
                }

                List<com.zygisk_enc.notivault.database.ToastEntity> toasts = new ArrayList<>();
                if (toastsArray != null) {
                    for (int i = 0; i < toastsArray.length(); i++) {
                        JSONObject obj = toastsArray.getJSONObject(i);
                        String textPlain = obj.optString("text", "");
                        String encText = EncryptionHelper.encrypt(textPlain);
                        
                        com.zygisk_enc.notivault.database.ToastEntity toast = new com.zygisk_enc.notivault.database.ToastEntity(
                                obj.optString("packageName", "unknown"),
                                obj.optString("appName", "Unknown App"),
                                encText,
                                obj.optLong("timestamp", System.currentTimeMillis())
                        );
                        toasts.add(toast);
                    }
                }

                // Insert into database
                for (NotificationEntity notif : notifications) {
                    AppDatabase.getInstance(context).notificationDao().insert(notif);
                }
                for (com.zygisk_enc.notivault.database.ToastEntity toast : toasts) {
                    AppDatabase.getInstance(context).toastDao().insert(toast);
                }
                callback.onSuccess();
            } catch (Exception e) {
                callback.onFailure(e);
            }
        });
    }
}
