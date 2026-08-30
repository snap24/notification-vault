package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.ToastEntity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

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

    private static class RawNotificationItem {
        String packageName = "unknown";
        String appName = "Unknown App";
        String title = "";
        String text = "";
        String bigText = null;
        long timestamp = System.currentTimeMillis();
        boolean isRead = false;
        boolean isFavorite = false;
        String imagePath = null;
    }

    private static class RawToastItem {
        String packageName = "unknown";
        String appName = "Unknown App";
        String text = "";
        long timestamp = System.currentTimeMillis();
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
            int cores = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            ExecutorService threadPool = Executors.newFixedThreadPool(cores, r -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
                r.run();
            }, "NotiVault-ExportWorker"));

            try {
                List<NotificationEntity> notifications = AppDatabase.getInstance(context)
                        .notificationDao().getAllNotificationsSync();
                List<ToastEntity> toasts = AppDatabase.getInstance(context)
                        .toastDao().getAllToastsSync();

                final int notifTotal = notifications.size();
                final int toastTotal = toasts.size();
                final int totalItems = notifTotal + toastTotal;
                final AtomicInteger processedCounter = new AtomicInteger(0);

                final JSONObject[] exportNotifs = new JSONObject[notifTotal];
                final Map<String, byte[]> mediaFiles = new ConcurrentHashMap<>();
                List<Future<?>> tasks = new ArrayList<>();

                int notifChunkSize = Math.max(50, (notifTotal + cores - 1) / cores);
                for (int i = 0; i < notifTotal; i += notifChunkSize) {
                    final int start = i;
                    final int end = Math.min(i + notifChunkSize, notifTotal);
                    tasks.add(threadPool.submit(() -> {
                        for (int j = start; j < end; j++) {
                            NotificationEntity notif = notifications.get(j);
                            JSONObject obj = new JSONObject();
                            try {
                                obj.put("packageName", notif.packageName);
                                obj.put("appName", notif.appName);

                                String decryptedTitle = EncryptionHelper.decrypt(notif.title);
                                String decryptedText = EncryptionHelper.decrypt(notif.text);
                                String decryptedBigText = notif.bigText != null ? EncryptionHelper.decrypt(notif.bigText) : null;

                                obj.put("title", decryptedTitle);
                                obj.put("text", decryptedText);
                                obj.put("bigText", decryptedBigText != null ? decryptedBigText : JSONObject.NULL);

                                obj.put("timestamp", notif.timestamp);
                                obj.put("isRead", notif.isRead ? 1 : 0);
                                obj.put("isFavorite", notif.isFavorite ? 1 : 0);

                                if (includeMedia && notif.imagePath != null && !notif.imagePath.isEmpty()) {
                                    String[] paths = notif.imagePath.split("\\|");
                                    StringBuilder savedFileNames = new StringBuilder();
                                    for (String p : paths) {
                                        if (p != null && !p.trim().isEmpty()) {
                                            File imgFile = new File(p.trim());
                                            if (imgFile.exists()) {
                                                byte[] decryptedImageBytes = EncryptionHelper.decryptFile(imgFile);
                                                if (decryptedImageBytes != null) {
                                                    String fileName = imgFile.getName();
                                                    mediaFiles.put(fileName, decryptedImageBytes);
                                                    if (savedFileNames.length() > 0) savedFileNames.append("|");
                                                    savedFileNames.append(fileName);
                                                }
                                            }
                                        }
                                    }
                                    if (savedFileNames.length() > 0) {
                                        obj.put("imagePath", savedFileNames.toString());
                                    } else {
                                        obj.put("imagePath", JSONObject.NULL);
                                    }
                                } else {
                                    obj.put("imagePath", JSONObject.NULL);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            exportNotifs[j] = obj;
                            int count = processedCounter.incrementAndGet();
                            if (callback instanceof BackupProgressListener && totalItems > 0) {
                                int progress = (count * 90) / totalItems;
                                ((BackupProgressListener) callback).onProgress(progress);
                            }
                        }
                    }));
                }

                final JSONObject[] exportToasts = new JSONObject[toastTotal];
                int toastChunkSize = Math.max(50, (toastTotal + cores - 1) / cores);
                for (int i = 0; i < toastTotal; i += toastChunkSize) {
                    final int start = i;
                    final int end = Math.min(i + toastChunkSize, toastTotal);
                    tasks.add(threadPool.submit(() -> {
                        for (int j = start; j < end; j++) {
                            ToastEntity toast = toasts.get(j);
                            JSONObject obj = new JSONObject();
                            try {
                                obj.put("packageName", toast.packageName);
                                obj.put("appName", toast.appName);
                                obj.put("text", EncryptionHelper.decrypt(toast.text));
                                obj.put("timestamp", toast.timestamp);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            exportToasts[j] = obj;
                            int count = processedCounter.incrementAndGet();
                            if (callback instanceof BackupProgressListener && totalItems > 0) {
                                int progress = (count * 90) / totalItems;
                                ((BackupProgressListener) callback).onProgress(progress);
                            }
                        }
                    }));
                }

                for (Future<?> task : tasks) {
                    task.get();
                }

                JSONArray jsonArray = new JSONArray();
                for (JSONObject obj : exportNotifs) {
                    if (obj != null) jsonArray.put(obj);
                }

                JSONArray toastsArray = new JSONArray();
                for (JSONObject obj : exportToasts) {
                    if (obj != null) toastsArray.put(obj);
                }

                JSONObject rootJson = new JSONObject();
                rootJson.put("version", 2);
                rootJson.put("notifications", jsonArray);
                rootJson.put("toasts", toastsArray);

                String jsonString = rootJson.toString(2);
                byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
                byte[] finalBytesToEncrypt;

                if (includeMedia && !mediaFiles.isEmpty()) {
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

                byte[] encryptedData = encryptBytes(finalBytesToEncrypt, password);

                try (OutputStream os = context.getContentResolver().openOutputStream(fileUri)) {
                    if (os != null) {
                        os.write(encryptedData);
                        if (callback instanceof BackupProgressListener) {
                            ((BackupProgressListener) callback).onProgress(100);
                        }
                        callback.onSuccess();
                    } else {
                        callback.onFailure(new Exception("Output stream is null"));
                    }
                }
            } catch (Exception e) {
                callback.onFailure(e);
            } finally {
                threadPool.shutdown();
            }
        });
    }

    public static void importBackup(Context context, Uri fileUri, String password, BackupCallback callback) {
        AppExecutor.execute(() -> {
            int cores = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            ExecutorService threadPool = Executors.newFixedThreadPool(cores, r -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
                r.run();
            }, "NotiVault-ImportWorker"));
            try {
                byte[] fileBytes;
                try (InputStream is = context.getContentResolver().openInputStream(fileUri)) {
                    if (is == null) {
                        callback.onFailure(new Exception("Input stream is null"));
                        return;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
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

                byte[] jsonBytes = null;
                Map<String, String> mediaPathMap = new ConcurrentHashMap<>();
                List<Future<?>> mediaTasks = new ArrayList<>();

                if (isZip(decrypted)) {
                    ByteArrayInputStream bis = new ByteArrayInputStream(decrypted);
                    ZipInputStream zis = new ZipInputStream(bis);
                    ZipEntry entry;
                    byte[] buffer = new byte[4096];

                    while ((entry = zis.getNextEntry()) != null) {
                        ByteArrayOutputStream entryBos = new ByteArrayOutputStream();
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            entryBos.write(buffer, 0, len);
                        }
                        byte[] entryBytes = entryBos.toByteArray();

                        if (entry.getName().equals("backup_data.json")) {
                            jsonBytes = entryBytes;
                        } else if (entry.getName().startsWith("media/")) {
                            String fileName = new File(entry.getName()).getName();
                            File localFile = new File(context.getFilesDir(), fileName);
                            mediaTasks.add(threadPool.submit(() -> {
                                boolean success = EncryptionHelper.encryptFile(entryBytes, localFile);
                                if (success) {
                                    mediaPathMap.put(fileName, localFile.getAbsolutePath());
                                }
                            }));
                        }
                        zis.closeEntry();
                    }
                    zis.close();

                    if (jsonBytes == null) {
                        callback.onFailure(new Exception("Backup JSON data not found in Zip."));
                        return;
                    }
                } else {
                    jsonBytes = decrypted;
                }

                // Wait for all concurrent media encryptions to complete
                for (Future<?> task : mediaTasks) {
                    task.get();
                }

                // Streaming Parse JSON via JsonReader
                List<RawNotificationItem> rawNotifs = new ArrayList<>();
                List<RawToastItem> rawToasts = new ArrayList<>();
                try (ByteArrayInputStream jsonStream = new ByteArrayInputStream(jsonBytes)) {
                    parseJsonStreaming(jsonStream, rawNotifs, rawToasts);
                }

                int totalItems = rawNotifs.size() + rawToasts.size();
                AtomicInteger processedCounter = new AtomicInteger(0);

                // Multi-Core Parallel Chunk Encryption
                NotificationEntity[] encryptedNotifs = new NotificationEntity[rawNotifs.size()];
                int notifChunkSize = Math.max(50, (rawNotifs.size() + cores - 1) / cores);
                List<Future<?>> encryptTasks = new ArrayList<>();

                for (int i = 0; i < rawNotifs.size(); i += notifChunkSize) {
                    final int start = i;
                    final int end = Math.min(i + notifChunkSize, rawNotifs.size());
                    encryptTasks.add(threadPool.submit(() -> {
                        for (int j = start; j < end; j++) {
                            RawNotificationItem raw = rawNotifs.get(j);
                            String encTitle = EncryptionHelper.encrypt(raw.title);
                            String encText = EncryptionHelper.encrypt(raw.text);
                            String encBigText = raw.bigText != null ? EncryptionHelper.encrypt(raw.bigText) : null;

                            NotificationEntity notif = new NotificationEntity(
                                    raw.packageName, raw.appName, encTitle, encText, encBigText, raw.timestamp);
                            notif.isRead = raw.isRead;
                            notif.isFavorite = raw.isFavorite;

                            if (raw.imagePath != null) {
                                String[] parts = raw.imagePath.split("\\|");
                                StringBuilder restoredPaths = new StringBuilder();
                                for (String part : parts) {
                                    String localPath = mediaPathMap.get(part.trim());
                                    if (localPath != null) {
                                        if (restoredPaths.length() > 0) restoredPaths.append("|");
                                        restoredPaths.append(localPath);
                                    }
                                }
                                notif.imagePath = restoredPaths.length() > 0 ? restoredPaths.toString() : null;
                            }

                            encryptedNotifs[j] = notif;
                            int count = processedCounter.incrementAndGet();
                            if (callback instanceof BackupProgressListener && totalItems > 0) {
                                int progress = (count * 65) / totalItems;
                                ((BackupProgressListener) callback).onProgress(progress);
                            }
                        }
                    }));
                }

                ToastEntity[] encryptedToasts = new ToastEntity[rawToasts.size()];
                int toastChunkSize = Math.max(50, (rawToasts.size() + cores - 1) / cores);
                for (int i = 0; i < rawToasts.size(); i += toastChunkSize) {
                    final int start = i;
                    final int end = Math.min(i + toastChunkSize, rawToasts.size());
                    encryptTasks.add(threadPool.submit(() -> {
                        for (int j = start; j < end; j++) {
                            RawToastItem raw = rawToasts.get(j);
                            String encText = EncryptionHelper.encrypt(raw.text);
                            ToastEntity toast = new ToastEntity(
                                    raw.packageName, raw.appName, encText, raw.timestamp);
                            encryptedToasts[j] = toast;
                            int count = processedCounter.incrementAndGet();
                            if (callback instanceof BackupProgressListener && totalItems > 0) {
                                int progress = (count * 65) / totalItems;
                                ((BackupProgressListener) callback).onProgress(progress);
                            }
                        }
                    }));
                }

                for (Future<?> task : encryptTasks) {
                    task.get();
                }

                // Batch insert into database in chunks with progress reporting (65% -> 100%)
                AppDatabase db = AppDatabase.getInstance(context);
                List<NotificationEntity> notifList = Arrays.asList(encryptedNotifs);
                List<ToastEntity> toastList = Arrays.asList(encryptedToasts);

                int totalToInsert = notifList.size() + toastList.size();
                int insertedSoFar = 0;
                final int DB_CHUNK = 500;

                for (int i = 0; i < notifList.size(); i += DB_CHUNK) {
                    int end = Math.min(i + DB_CHUNK, notifList.size());
                    db.notificationDao().insertAll(notifList.subList(i, end));
                    insertedSoFar += (end - i);
                    if (callback instanceof BackupProgressListener && totalToInsert > 0) {
                        int progress = 65 + ((insertedSoFar * 35) / totalToInsert);
                        ((BackupProgressListener) callback).onProgress(Math.min(99, progress));
                    }
                }

                for (int i = 0; i < toastList.size(); i += DB_CHUNK) {
                    int end = Math.min(i + DB_CHUNK, toastList.size());
                    db.toastDao().insertAll(toastList.subList(i, end));
                    insertedSoFar += (end - i);
                    if (callback instanceof BackupProgressListener && totalToInsert > 0) {
                        int progress = 65 + ((insertedSoFar * 35) / totalToInsert);
                        ((BackupProgressListener) callback).onProgress(Math.min(99, progress));
                    }
                }

                if (callback instanceof BackupProgressListener) {
                    ((BackupProgressListener) callback).onProgress(100);
                }

                // Immediately index imported notifications for sub-5ms instant encrypted search
                BlindIndexManager.ensureDatabaseIndexed(context);

                callback.onSuccess();
            } catch (Exception e) {
                callback.onFailure(e);
            } finally {
                threadPool.shutdown();
            }
        });
    }

    private static void parseJsonStreaming(InputStream is, List<RawNotificationItem> notifs, List<RawToastItem> toasts) throws Exception {
        try (JsonReader reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            JsonToken rootToken = reader.peek();
            if (rootToken == JsonToken.BEGIN_ARRAY) {
                // Legacy format: root is an array of notifications
                reader.beginArray();
                while (reader.hasNext()) {
                    notifs.add(readSingleNotification(reader));
                }
                reader.endArray();
            } else if (rootToken == JsonToken.BEGIN_OBJECT) {
                // Modern format: root is an object with notifications & toasts
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if ("notifications".equals(name) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            notifs.add(readSingleNotification(reader));
                        }
                        reader.endArray();
                    } else if ("toasts".equals(name) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            toasts.add(readSingleToast(reader));
                        }
                        reader.endArray();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
        }
    }

    private static RawNotificationItem readSingleNotification(JsonReader reader) throws Exception {
        RawNotificationItem item = new RawNotificationItem();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }
            switch (name) {
                case "packageName": item.packageName = reader.nextString(); break;
                case "appName": item.appName = reader.nextString(); break;
                case "title": item.title = reader.nextString(); break;
                case "text": item.text = reader.nextString(); break;
                case "bigText": item.bigText = reader.nextString(); break;
                case "timestamp": item.timestamp = reader.nextLong(); break;
                case "isRead": item.isRead = reader.nextInt() == 1; break;
                case "isFavorite": item.isFavorite = reader.nextInt() == 1; break;
                case "imagePath": item.imagePath = reader.nextString(); break;
                default: reader.skipValue(); break;
            }
        }
        reader.endObject();
        return item;
    }

    private static RawToastItem readSingleToast(JsonReader reader) throws Exception {
        RawToastItem item = new RawToastItem();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }
            switch (name) {
                case "packageName": item.packageName = reader.nextString(); break;
                case "appName": item.appName = reader.nextString(); break;
                case "text": item.text = reader.nextString(); break;
                case "timestamp": item.timestamp = reader.nextLong(); break;
                default: reader.skipValue(); break;
            }
        }
        reader.endObject();
        return item;
    }
}
