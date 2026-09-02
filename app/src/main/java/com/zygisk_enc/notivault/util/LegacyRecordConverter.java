package com.zygisk_enc.notivault.util;

import android.content.Context;
import androidx.preference.PreferenceManager;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.ToastEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class LegacyRecordConverter {

    public static final String PREF_MIGRATION_COMPLETED = "legacy_records_migrated_v2";

    public static final int ACTION_PLACEHOLDER = 0;
    public static final int ACTION_DELETE = 1;

    public interface MigrationProgressListener {
        void onProgress(int current, int total, int percentage);
        void onComplete();
        void onError(Exception e);
    }

    public static class ScanResult {
        public int totalLegacy;
        public int recoverableCount;
        public int unrecoverableCount;
    }

    public static boolean isMigrationCompleted(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_MIGRATION_COMPLETED, false);
    }

    public static void setMigrationCompleted(Context context, boolean completed) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_MIGRATION_COMPLETED, completed)
                .apply();
    }

    public interface ScanProgressListener {
        void onProgress(int current, int total, int percentage);
    }

    public static ScanResult scanLegacyRecords(Context context) {
        return scanLegacyRecords(context, null);
    }

    public static ScanResult scanLegacyRecords(Context context, ScanProgressListener listener) {
        ScanResult result = new ScanResult();
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            List<NotificationEntity> notifs = db.notificationDao().getLegacyEncryptedNotificationsSync();
            List<ToastEntity> toasts = db.toastDao().getLegacyEncryptedToastsSync();

            int notifsSize = notifs != null ? notifs.size() : 0;
            int toastsSize = toasts != null ? toasts.size() : 0;
            int total = notifsSize + toastsSize;
            int processed = 0;

            if (total == 0 && listener != null) {
                listener.onProgress(0, 0, 100);
            }

            if (notifs != null) {
                for (NotificationEntity n : notifs) {
                    boolean hasEnc = EncryptionHelper.isEncrypted(n.title) || 
                                     EncryptionHelper.isEncrypted(n.text) || 
                                     EncryptionHelper.isEncrypted(n.bigText);
                    if (hasEnc) {
                        result.totalLegacy++;
                        // Test decryption on the encrypted fields
                        boolean recoverable = true;
                        if (EncryptionHelper.isEncrypted(n.title)) {
                            String dec = EncryptionHelper.decrypt(n.title);
                            if (dec == null || dec.equals(n.title)) recoverable = false;
                        }
                        if (recoverable && EncryptionHelper.isEncrypted(n.text)) {
                            String dec = EncryptionHelper.decrypt(n.text);
                            if (dec == null || dec.equals(n.text)) recoverable = false;
                        }
                        if (recoverable) {
                            result.recoverableCount++;
                        } else {
                            result.unrecoverableCount++;
                        }
                    }
                    processed++;
                    if (listener != null && total > 0 && (processed % 25 == 0 || processed == total)) {
                        int pct = (processed * 100) / total;
                        listener.onProgress(processed, total, pct);
                    }
                }
            }

            if (toasts != null) {
                for (ToastEntity t : toasts) {
                    if (EncryptionHelper.isEncrypted(t.text)) {
                        result.totalLegacy++;
                        String dec = EncryptionHelper.decrypt(t.text);
                        if (dec != null && !dec.equals(t.text)) {
                            result.recoverableCount++;
                        } else {
                            result.unrecoverableCount++;
                        }
                    }
                    processed++;
                    if (listener != null && total > 0 && (processed % 25 == 0 || processed == total)) {
                        int pct = (processed * 100) / total;
                        listener.onProgress(processed, total, pct);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public static int countLegacyRecords(Context context) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            List<NotificationEntity> notifs = db.notificationDao().getLegacyEncryptedNotificationsSync();
            List<ToastEntity> toasts = db.toastDao().getLegacyEncryptedToastsSync();
            int count = 0;
            if (notifs != null) {
                for (NotificationEntity n : notifs) {
                    if (EncryptionHelper.isEncrypted(n.title) || 
                        EncryptionHelper.isEncrypted(n.text) || 
                        EncryptionHelper.isEncrypted(n.bigText)) {
                        count++;
                    }
                }
            }
            if (toasts != null) {
                for (ToastEntity t : toasts) {
                    if (EncryptionHelper.isEncrypted(t.text)) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean isConverting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final Object LISTENER_LOCK = new Object();
    private static MigrationProgressListener activeListener = null;
    private static volatile int lastCurrent = 0;
    private static volatile int lastTotal = 0;
    private static volatile int lastPercentage = 0;

    public static boolean isConverting() {
        return isConverting.get();
    }

    public static int getLastCurrent() {
        return lastCurrent;
    }

    public static int getLastTotal() {
        return lastTotal;
    }

    public static int getLastPercentage() {
        return lastPercentage;
    }

    public static void setListener(MigrationProgressListener listener) {
        synchronized (LISTENER_LOCK) {
            activeListener = listener;
            if (activeListener != null && isConverting.get() && lastTotal > 0) {
                activeListener.onProgress(lastCurrent, lastTotal, lastPercentage);
            }
        }
    }

    public static void convertAll(Context context, int unrecoverableAction, MigrationProgressListener listener) {
        synchronized (LISTENER_LOCK) {
            activeListener = listener;
        }

        if (isConverting.getAndSet(true)) {
            // Already running! Update listener with current progress
            synchronized (LISTENER_LOCK) {
                if (activeListener != null && lastTotal > 0) {
                    activeListener.onProgress(lastCurrent, lastTotal, lastPercentage);
                }
            }
            return;
        }

        AppExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(context);
                List<NotificationEntity> allNotifs = db.notificationDao().getLegacyEncryptedNotificationsSync();
                List<ToastEntity> allToasts = db.toastDao().getLegacyEncryptedToastsSync();

                List<NotificationEntity> legacyNotifs = new ArrayList<>();
                if (allNotifs != null) {
                    for (NotificationEntity n : allNotifs) {
                        if (EncryptionHelper.isEncrypted(n.title) || 
                            EncryptionHelper.isEncrypted(n.text) || 
                            EncryptionHelper.isEncrypted(n.bigText)) {
                            legacyNotifs.add(n);
                        }
                    }
                }

                List<ToastEntity> legacyToasts = new ArrayList<>();
                if (allToasts != null) {
                    for (ToastEntity t : allToasts) {
                        if (EncryptionHelper.isEncrypted(t.text)) {
                            legacyToasts.add(t);
                        }
                    }
                }

                final int total = legacyNotifs.size() + legacyToasts.size();
                lastTotal = total;
                lastCurrent = 0;
                lastPercentage = 0;

                if (total == 0) {
                    isConverting.set(false);
                    setMigrationCompleted(context, true);
                    synchronized (LISTENER_LOCK) {
                        if (activeListener != null) {
                            activeListener.onComplete();
                            activeListener = null;
                        }
                    }
                    return;
                }

                int cores = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
                ExecutorService pool = Executors.newFixedThreadPool(cores);
                AtomicInteger processed = new AtomicInteger(0);

                List<NotificationEntity> notifsToUpdate = java.util.Collections.synchronizedList(new ArrayList<>());
                List<NotificationEntity> notifsToDelete = java.util.Collections.synchronizedList(new ArrayList<>());

                // 1. Process Notifications in parallel
                if (!legacyNotifs.isEmpty()) {
                    int chunkSize = Math.max(25, (legacyNotifs.size() + cores - 1) / cores);
                    List<Future<?>> tasks = new ArrayList<>();

                    for (int i = 0; i < legacyNotifs.size(); i += chunkSize) {
                        final int start = i;
                        final int end = Math.min(i + chunkSize, legacyNotifs.size());
                        tasks.add(pool.submit(() -> {
                            for (int j = start; j < end; j++) {
                                NotificationEntity entity = legacyNotifs.get(j);
                                boolean titleRecovered = true;
                                boolean textRecovered = true;

                                if (EncryptionHelper.isEncrypted(entity.title)) {
                                    String dec = EncryptionHelper.decrypt(entity.title);
                                    if (dec != null && !dec.equals(entity.title)) {
                                        entity.title = dec;
                                    } else {
                                        titleRecovered = false;
                                    }
                                }

                                if (EncryptionHelper.isEncrypted(entity.text)) {
                                    String dec = EncryptionHelper.decrypt(entity.text);
                                    if (dec != null && !dec.equals(entity.text)) {
                                        entity.text = dec;
                                    } else {
                                        textRecovered = false;
                                    }
                                }

                                if (EncryptionHelper.isEncrypted(entity.bigText)) {
                                    String dec = EncryptionHelper.decrypt(entity.bigText);
                                    if (dec != null && !dec.equals(entity.bigText)) {
                                        entity.bigText = dec;
                                    } else {
                                        entity.bigText = null;
                                    }
                                }

                                boolean isRecoverable = titleRecovered && textRecovered;

                                if (isRecoverable) {
                                    entity.decryptedTitle = entity.title;
                                    entity.decryptedText = entity.text;
                                    entity.decryptedBigText = entity.bigText;
                                    notifsToUpdate.add(entity);
                                } else {
                                    if (unrecoverableAction == ACTION_DELETE) {
                                        notifsToDelete.add(entity);
                                    } else {
                                        // ACTION_PLACEHOLDER
                                        entity.title = "[Encrypted Content]";
                                        entity.text = "[Undecryptable Message]";
                                        entity.bigText = null;
                                        entity.decryptedTitle = entity.title;
                                        entity.decryptedText = entity.text;
                                        entity.decryptedBigText = null;
                                        notifsToUpdate.add(entity);
                                    }
                                }

                                int current = processed.incrementAndGet();
                                int pct = Math.min(99, (current * 100) / total);
                                lastCurrent = current;
                                lastPercentage = pct;
                                synchronized (LISTENER_LOCK) {
                                    if (activeListener != null) {
                                        activeListener.onProgress(current, total, pct);
                                    }
                                }
                            }
                        }));
                    }

                    for (Future<?> task : tasks) {
                        task.get();
                    }

                    // Batch update / delete
                    if (!notifsToUpdate.isEmpty()) {
                        int batchSize = 100;
                        for (int i = 0; i < notifsToUpdate.size(); i += batchSize) {
                            int end = Math.min(i + batchSize, notifsToUpdate.size());
                            db.notificationDao().updateAll(notifsToUpdate.subList(i, end));
                        }
                    }
                    if (!notifsToDelete.isEmpty()) {
                        int batchSize = 100;
                        for (int i = 0; i < notifsToDelete.size(); i += batchSize) {
                            int end = Math.min(i + batchSize, notifsToDelete.size());
                            db.notificationDao().deleteAllEntities(notifsToDelete.subList(i, end));
                        }
                    }
                }

                List<ToastEntity> toastsToUpdate = java.util.Collections.synchronizedList(new ArrayList<>());
                List<ToastEntity> toastsToDelete = java.util.Collections.synchronizedList(new ArrayList<>());

                // 2. Process Toasts in parallel
                if (!legacyToasts.isEmpty()) {
                    int chunkSize = Math.max(25, (legacyToasts.size() + cores - 1) / cores);
                    List<Future<?>> tasks = new ArrayList<>();

                    for (int i = 0; i < legacyToasts.size(); i += chunkSize) {
                        final int start = i;
                        final int end = Math.min(i + chunkSize, legacyToasts.size());
                        tasks.add(pool.submit(() -> {
                            for (int j = start; j < end; j++) {
                                ToastEntity entity = legacyToasts.get(j);
                                boolean recovered = true;
                                if (EncryptionHelper.isEncrypted(entity.text)) {
                                    String dec = EncryptionHelper.decrypt(entity.text);
                                    if (dec != null && !dec.equals(entity.text)) {
                                        entity.text = dec;
                                    } else {
                                        recovered = false;
                                    }
                                }

                                if (recovered) {
                                    entity.decryptedText = entity.text;
                                    toastsToUpdate.add(entity);
                                } else {
                                    if (unrecoverableAction == ACTION_DELETE) {
                                        toastsToDelete.add(entity);
                                    } else {
                                        entity.text = "[Encrypted Toast]";
                                        entity.decryptedText = entity.text;
                                        toastsToUpdate.add(entity);
                                    }
                                }

                                int current = processed.incrementAndGet();
                                int pct = Math.min(99, (current * 100) / total);
                                lastCurrent = current;
                                lastPercentage = pct;
                                synchronized (LISTENER_LOCK) {
                                    if (activeListener != null) {
                                        activeListener.onProgress(current, total, pct);
                                    }
                                }
                            }
                        }));
                    }

                    for (Future<?> task : tasks) {
                        task.get();
                    }

                    if (!toastsToUpdate.isEmpty()) {
                        int batchSize = 100;
                        for (int i = 0; i < toastsToUpdate.size(); i += batchSize) {
                            int end = Math.min(i + batchSize, toastsToUpdate.size());
                            db.toastDao().updateAll(toastsToUpdate.subList(i, end));
                        }
                    }
                    if (!toastsToDelete.isEmpty()) {
                        int batchSize = 100;
                        for (int i = 0; i < toastsToDelete.size(); i += batchSize) {
                            int end = Math.min(i + batchSize, toastsToDelete.size());
                            db.toastDao().deleteAllEntities(toastsToDelete.subList(i, end));
                        }
                    }
                }

                pool.shutdown();

                // Successfully processed all records - mark completed flag
                isConverting.set(false);
                setMigrationCompleted(context, true);
                lastCurrent = total;
                lastPercentage = 100;

                synchronized (LISTENER_LOCK) {
                    if (activeListener != null) {
                        activeListener.onProgress(total, total, 100);
                        activeListener.onComplete();
                        activeListener = null;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                isConverting.set(false);
                synchronized (LISTENER_LOCK) {
                    if (activeListener != null) {
                        activeListener.onError(e);
                        activeListener = null;
                    }
                }
            }
        });
    }
}
