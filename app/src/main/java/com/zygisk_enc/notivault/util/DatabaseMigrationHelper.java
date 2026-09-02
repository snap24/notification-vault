package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import androidx.room.Room;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.ToastEntity;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles one-time transparent migration from legacy unencrypted SQLite database
 * with per-record encrypted fields into the new SQLCipher encrypted database.
 *
 * Utilizes multi-threaded parallel decryption across all CPU cores, atomic Room transaction
 * batch insertions, and strict pre/post verification to ensure zero data loss.
 */
public class DatabaseMigrationHelper {

    private static final String TAG = "DatabaseMigrationHelper";
    public static final String PREF_MIGRATION_DONE = "migrated_to_sqlcipher_db_v1";
    public static final String PREFS_NAME = "notivault_migration_prefs";
    public static final String LEGACY_DB_NAME = "notivault_database";

    private static final Object MIGRATION_LOCK = new Object();
    private static final AtomicBoolean isMigrating = new AtomicBoolean(false);

    public interface MigrationProgressListener {
        void onProgress(int current, int total, int percentage);
        void onComplete();
        void onError(Exception e);
    }

    public static boolean isMigrating() {
        return isMigrating.get();
    }

    public static boolean needsMigration(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_MIGRATION_DONE, false)) {
            return false;
        }

        File legacyDbFile = context.getDatabasePath(LEGACY_DB_NAME);
        if (!legacyDbFile.exists() || legacyDbFile.length() == 0) {
            // Fresh install or no legacy DB exists
            prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply();
            return false;
        }

        return true;
    }

    public static void migrateAsync(Context context, MigrationProgressListener listener) {
        AppExecutor.execute(() -> {
            try {
                doMigration(context.getApplicationContext(), listener);
            } catch (Exception e) {
                Log.e(TAG, "Async migration failed: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        });
    }

    public static void ensureMigrated(Context context) {
        if (!needsMigration(context)) return;
        try {
            doMigration(context.getApplicationContext(), null);
        } catch (Exception e) {
            Log.e(TAG, "ensureMigrated failed: " + e.getMessage(), e);
        }
    }

    private static class RawLegacyNotif {
        String pkg;
        String app;
        String title;
        String text;
        String bigText;
        long time;
        boolean isRead;
        boolean isFav;
        int dup = 1;
        String img;
        String bundle;
        int userId = 0;
        String meta;
    }

    private static class RawLegacyToast {
        String pkg;
        String app;
        String text;
        long time;
        int dup = 1;
        int userId = 0;
    }

    private static void doMigration(Context context, MigrationProgressListener listener) throws Exception {
        synchronized (MIGRATION_LOCK) {
            if (!needsMigration(context)) {
                if (listener != null) listener.onComplete();
                return;
            }

            isMigrating.set(true);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            File legacyDbFile = context.getDatabasePath(LEGACY_DB_NAME);
            File encDbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME);

            // If an earlier incomplete attempt left an unverified encrypted DB, clean it up first
            if (encDbFile.exists()) {
                Log.w(TAG, "Found incomplete encrypted database from previous run. Cleaning up to restart migration cleanly.");
                context.deleteDatabase(AppDatabase.DATABASE_NAME);
            }

            Log.i(TAG, "Starting multi-core legacy database migration to SQLCipher...");
            SQLiteDatabase legacyDb = null;
            AppDatabase encDb = null;

            try {
                legacyDb = SQLiteDatabase.openDatabase(
                        legacyDbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

                // 1. Read all raw notification records
                List<RawLegacyNotif> rawNotifs = new ArrayList<>();
                boolean hasNotifTable = false;
                try (Cursor c = legacyDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='notifications'", null)) {
                    hasNotifTable = (c != null && c.moveToFirst());
                }

                if (hasNotifTable) {
                    Cursor cursor = null;
                    try {
                        cursor = legacyDb.rawQuery("SELECT * FROM notifications ORDER BY id ASC", null);
                        if (cursor != null) {
                            int colPkg = cursor.getColumnIndex("packageName");
                            int colApp = cursor.getColumnIndex("appName");
                            int colTitle = cursor.getColumnIndex("title");
                            int colText = cursor.getColumnIndex("text");
                            int colBigText = cursor.getColumnIndex("bigText");
                            int colTime = cursor.getColumnIndex("timestamp");
                            int colRead = cursor.getColumnIndex("isRead");
                            int colFav = cursor.getColumnIndex("isFavorite");
                            int colDup = cursor.getColumnIndex("duplicateCount");
                            int colImg = cursor.getColumnIndex("imagePath");
                            int colBundle = cursor.getColumnIndex("bundleId");
                            int colUser = cursor.getColumnIndex("userId");
                            int colMeta = cursor.getColumnIndex("metadata");

                            while (cursor.moveToNext()) {
                                RawLegacyNotif item = new RawLegacyNotif();
                                item.pkg = colPkg != -1 ? cursor.getString(colPkg) : "";
                                item.app = colApp != -1 ? cursor.getString(colApp) : "";
                                item.title = colTitle != -1 ? cursor.getString(colTitle) : "";
                                item.text = colText != -1 ? cursor.getString(colText) : "";
                                item.bigText = colBigText != -1 ? cursor.getString(colBigText) : null;
                                item.time = colTime != -1 ? cursor.getLong(colTime) : 0;
                                item.isRead = colRead != -1 && cursor.getInt(colRead) == 1;
                                item.isFav = colFav != -1 && cursor.getInt(colFav) == 1;
                                item.dup = colDup != -1 ? cursor.getInt(colDup) : 1;
                                item.img = colImg != -1 ? cursor.getString(colImg) : null;
                                item.bundle = colBundle != -1 ? cursor.getString(colBundle) : null;
                                item.userId = colUser != -1 ? cursor.getInt(colUser) : 0;
                                item.meta = colMeta != -1 ? cursor.getString(colMeta) : null;
                                rawNotifs.add(item);
                            }
                        }
                    } finally {
                        if (cursor != null) cursor.close();
                    }
                }

                // 2. Read all raw toast records
                List<RawLegacyToast> rawToasts = new ArrayList<>();
                boolean hasToastsTable = false;
                try (Cursor c = legacyDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='toasts'", null)) {
                    hasToastsTable = (c != null && c.moveToFirst());
                }

                if (hasToastsTable) {
                    Cursor toastCursor = null;
                    try {
                        toastCursor = legacyDb.rawQuery("SELECT * FROM toasts ORDER BY id ASC", null);
                        if (toastCursor != null) {
                            int colPkg = toastCursor.getColumnIndex("packageName");
                            int colApp = toastCursor.getColumnIndex("appName");
                            int colText = toastCursor.getColumnIndex("text");
                            int colTime = toastCursor.getColumnIndex("timestamp");
                            int colDup = toastCursor.getColumnIndex("duplicateCount");
                            int colUser = toastCursor.getColumnIndex("userId");

                            while (toastCursor.moveToNext()) {
                                RawLegacyToast item = new RawLegacyToast();
                                item.pkg = colPkg != -1 ? toastCursor.getString(colPkg) : "";
                                item.app = colApp != -1 ? toastCursor.getString(colApp) : "";
                                item.text = colText != -1 ? toastCursor.getString(colText) : "";
                                item.time = colTime != -1 ? toastCursor.getLong(colTime) : 0;
                                item.dup = colDup != -1 ? toastCursor.getInt(colDup) : 1;
                                item.userId = colUser != -1 ? toastCursor.getInt(colUser) : 0;
                                rawToasts.add(item);
                            }
                        }
                    } finally {
                        if (toastCursor != null) toastCursor.close();
                    }
                }

                // 3. Read all raw app rules
                List<AppRuleEntity> rawRules = new ArrayList<>();
                boolean hasRulesTable = false;
                try (Cursor c = legacyDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='app_rules'", null)) {
                    hasRulesTable = (c != null && c.moveToFirst());
                }

                if (hasRulesTable) {
                    Cursor ruleCursor = null;
                    try {
                        ruleCursor = legacyDb.rawQuery("SELECT * FROM app_rules", null);
                        if (ruleCursor != null) {
                            int colPkg = ruleCursor.getColumnIndex("packageName");
                            int colApp = ruleCursor.getColumnIndex("appName");
                            int colBlockAll = ruleCursor.getColumnIndex("blockAll");
                            int colBlockKw = ruleCursor.getColumnIndex("blockKeywords");
                            int colAllowKw = ruleCursor.getColumnIndex("allowKeywords");
                            int colEnabled = ruleCursor.getColumnIndex("isRuleEnabled");

                            while (ruleCursor.moveToNext()) {
                                String pkg = colPkg != -1 ? ruleCursor.getString(colPkg) : "";
                                String app = colApp != -1 ? ruleCursor.getString(colApp) : "";
                                boolean blockAll = colBlockAll != -1 && ruleCursor.getInt(colBlockAll) == 1;
                                String blockKw = colBlockKw != -1 ? ruleCursor.getString(colBlockKw) : "";
                                String allowKw = colAllowKw != -1 ? ruleCursor.getString(colAllowKw) : "";
                                boolean isRuleEnabled = colEnabled == -1 || ruleCursor.getInt(colEnabled) == 1;

                                rawRules.add(new AppRuleEntity(pkg, app, blockAll, blockKw, allowKw, isRuleEnabled));
                            }
                        }
                    } finally {
                        if (ruleCursor != null) ruleCursor.close();
                    }
                }

                final int totalItems = rawNotifs.size() + rawToasts.size();
                Log.i(TAG, "Legacy database records found: " + rawNotifs.size() + " notifications, "
                        + rawToasts.size() + " toasts, " + rawRules.size() + " rules.");

                // 4. Initialize target SQLCipher database
                byte[] passphrase = DatabaseKeyManager.getDatabasePassphrase(context);
                net.zetetic.database.sqlcipher.SupportOpenHelperFactory factory =
                        new net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase);

                encDb = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        AppDatabase.DATABASE_NAME
                )
                .openHelperFactory(factory)
                .addMigrations(
                        AppDatabase.MIGRATION_1_8,
                        AppDatabase.MIGRATION_7_8,
                        AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10,
                        AppDatabase.MIGRATION_10_11,
                        AppDatabase.MIGRATION_11_12,
                        AppDatabase.MIGRATION_12_13
                )
                .build();

                // 5. Multi-Threaded Parallel Decryption Engine
                int cores = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
                ExecutorService threadPool = Executors.newFixedThreadPool(cores);

                NotificationEntity[] convertedNotifs = new NotificationEntity[rawNotifs.size()];
                ToastEntity[] convertedToasts = new ToastEntity[rawToasts.size()];

                AtomicInteger processedCounter = new AtomicInteger(0);
                AtomicInteger lastPct = new AtomicInteger(-1);

                List<Future<?>> tasks = new ArrayList<>();

                // Parallel convert notifications
                int notifChunkSize = Math.max(50, (rawNotifs.size() + cores - 1) / cores);
                for (int i = 0; i < rawNotifs.size(); i += notifChunkSize) {
                    final int start = i;
                    final int end = Math.min(i + notifChunkSize, rawNotifs.size());
                    tasks.add(threadPool.submit(() -> {
                        for (int j = start; j < end; j++) {
                            RawLegacyNotif raw = rawNotifs.get(j);
                            String decTitle = raw.title != null ? (EncryptionHelper.isEncrypted(raw.title) ? EncryptionHelper.decrypt(raw.title) : raw.title) : "";
                            String decText = raw.text != null ? (EncryptionHelper.isEncrypted(raw.text) ? EncryptionHelper.decrypt(raw.text) : raw.text) : "";
                            String decBigText = raw.bigText != null ? (EncryptionHelper.isEncrypted(raw.bigText) ? EncryptionHelper.decrypt(raw.bigText) : raw.bigText) : null;

                            NotificationEntity notif = new NotificationEntity(
                                    raw.pkg, raw.app, decTitle, decText, decBigText, raw.time);
                            notif.isRead = raw.isRead;
                            notif.isFavorite = raw.isFav;
                            notif.duplicateCount = raw.dup;
                            notif.imagePath = raw.img;
                            notif.bundleId = raw.bundle;
                            notif.userId = raw.userId;
                            notif.metadata = raw.meta;

                            convertedNotifs[j] = notif;

                            int count = processedCounter.incrementAndGet();
                            if (listener != null && totalItems > 0) {
                                int pct = (count * 75) / totalItems;
                                int old = lastPct.get();
                                if (pct != old && lastPct.compareAndSet(old, pct)) {
                                    listener.onProgress(count, totalItems, pct);
                                }
                            }
                        }
                    }));
                }

                // Parallel convert toasts
                int toastChunkSize = Math.max(50, (rawToasts.size() + cores - 1) / cores);
                for (int i = 0; i < rawToasts.size(); i += toastChunkSize) {
                    final int start = i;
                    final int end = Math.min(i + toastChunkSize, rawToasts.size());
                    tasks.add(threadPool.submit(() -> {
                        for (int j = start; j < end; j++) {
                            RawLegacyToast raw = rawToasts.get(j);
                            String decText = raw.text != null ? (EncryptionHelper.isEncrypted(raw.text) ? EncryptionHelper.decrypt(raw.text) : raw.text) : "";

                            ToastEntity toast = new ToastEntity(raw.pkg, raw.app, decText, raw.time);
                            toast.duplicateCount = raw.dup;
                            toast.userId = raw.userId;

                            convertedToasts[j] = toast;

                            int count = processedCounter.incrementAndGet();
                            if (listener != null && totalItems > 0) {
                                int pct = (count * 75) / totalItems;
                                int old = lastPct.get();
                                if (pct != old && lastPct.compareAndSet(old, pct)) {
                                    listener.onProgress(count, totalItems, pct);
                                }
                            }
                        }
                    }));
                }

                for (Future<?> task : tasks) {
                    task.get();
                }
                threadPool.shutdown();

                // 6. Fast Atomic Transaction Batch Insertion (75% -> 100%)
                if (listener != null) {
                    listener.onProgress(totalItems, totalItems, 80);
                }

                final AppDatabase finalEncDb = encDb;
                finalEncDb.runInTransaction(() -> {
                    final int CHUNK = 500;
                    for (int i = 0; i < convertedNotifs.length; i += CHUNK) {
                        int end = Math.min(i + CHUNK, convertedNotifs.length);
                        finalEncDb.notificationDao().insertAll(Arrays.asList(convertedNotifs).subList(i, end));
                    }
                    for (int i = 0; i < convertedToasts.length; i += CHUNK) {
                        int end = Math.min(i + CHUNK, convertedToasts.length);
                        finalEncDb.toastDao().insertAll(Arrays.asList(convertedToasts).subList(i, end));
                    }
                    if (!rawRules.isEmpty()) {
                        finalEncDb.appRuleDao().insertAll(rawRules);
                    }
                });

                // 7. Verify Integrity of Migrated Records
                int newNotifCount = finalEncDb.notificationDao().getTotalCountSync();
                int newToastCount = finalEncDb.toastDao().getTotalCountSync();

                if (newNotifCount >= rawNotifs.size() && newToastCount >= rawToasts.size()) {
                    // Success! Attach persistent open instance to AppDatabase
                    AppDatabase.setInstance(finalEncDb);

                    // Mark migration complete in preferences
                    prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply();

                    if (listener != null) {
                        listener.onProgress(totalItems, totalItems, 100);
                    }

                    // Safely close and delete legacy database file
                    legacyDb.close();
                    legacyDb = null;
                    context.deleteDatabase(LEGACY_DB_NAME);

                    Log.i(TAG, "Legacy database successfully migrated to SQLCipher! ("
                            + newNotifCount + " notifications, " + newToastCount + " toasts)");

                    if (listener != null) {
                        listener.onComplete();
                    }
                } else {
                    throw new IllegalStateException("Migration verification mismatch: expected "
                            + rawNotifs.size() + " notifs / " + rawToasts.size() + " toasts, but got "
                            + newNotifCount + " notifs / " + newToastCount + " toasts.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Database migration failed: " + e.getMessage(), e);
                // Clean up incomplete target database so it can cleanly retry
                if (encDb != null) {
                    try { encDb.close(); } catch (Exception ignored) {}
                }
                context.deleteDatabase(AppDatabase.DATABASE_NAME);
                if (listener != null) {
                    listener.onError(e);
                }
                throw e;
            } finally {
                isMigrating.set(false);
                if (legacyDb != null && legacyDb.isOpen()) {
                    try { legacyDb.close(); } catch (Exception ignored) {}
                }
            }
        }
    }
}
