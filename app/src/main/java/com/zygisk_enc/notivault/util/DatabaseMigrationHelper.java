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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles fast, one-time transparent migration from legacy unencrypted SQLite database
 * into the new SQLCipher encrypted database.
 *
 * Transfers raw records directly into SQLCipher in a single atomic transaction (< 150ms)
 * without blocking on KeyStore IPC. Decryption/conversion is handled smoothly in the
 * background by LegacyRecordConverter or on-demand by ViewModels.
 */
public class DatabaseMigrationHelper {

    private static final String TAG = "DatabaseMigrationHelper";
    public static final String PREF_MIGRATION_DONE = "migrated_to_sqlcipher_db_v1";
    public static final String PREFS_NAME = "notivault_migration_prefs";
    public static final String LEGACY_DB_NAME = "notivault_database";

    private static final Object MIGRATION_LOCK = new Object();
    private static final AtomicBoolean isMigrating = new AtomicBoolean(false);

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

    public static void ensureMigrated(Context context) {
        if (!needsMigration(context)) return;
        synchronized (MIGRATION_LOCK) {
            if (!needsMigration(context)) return;

            isMigrating.set(true);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            File legacyDbFile = context.getDatabasePath(LEGACY_DB_NAME);
            File encDbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME);

            // If an earlier incomplete attempt left an unverified encrypted DB, clean it up first
            if (encDbFile.exists()) {
                Log.w(TAG, "Found incomplete encrypted database from previous run. Cleaning up to restart migration cleanly.");
                context.deleteDatabase(AppDatabase.DATABASE_NAME);
            }

            Log.i(TAG, "Starting ultra-fast legacy database transfer to SQLCipher...");
            long startTime = System.currentTimeMillis();

            SQLiteDatabase legacyDb = null;
            AppDatabase encDb = null;

            try {
                legacyDb = SQLiteDatabase.openDatabase(
                        legacyDbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

                // 1. Read all raw notification records
                List<NotificationEntity> notifs = new ArrayList<>();
                boolean hasNotifTable = false;
                try (Cursor c = legacyDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='notifications'", null)) {
                    hasNotifTable = (c != null && c.moveToFirst());
                }

                if (hasNotifTable) {
                    try (Cursor cursor = legacyDb.rawQuery("SELECT * FROM notifications ORDER BY id ASC", null)) {
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
                                String pkg = colPkg != -1 ? cursor.getString(colPkg) : "";
                                String app = colApp != -1 ? cursor.getString(colApp) : "";
                                String title = colTitle != -1 ? cursor.getString(colTitle) : "";
                                String text = colText != -1 ? cursor.getString(colText) : "";
                                String bigText = colBigText != -1 ? cursor.getString(colBigText) : null;
                                long time = colTime != -1 ? cursor.getLong(colTime) : 0;

                                NotificationEntity notif = new NotificationEntity(
                                        pkg, app, title, text, bigText, time);
                                if (colRead != -1) notif.isRead = cursor.getInt(colRead) == 1;
                                if (colFav != -1) notif.isFavorite = cursor.getInt(colFav) == 1;
                                if (colDup != -1) notif.duplicateCount = cursor.getInt(colDup);
                                if (colImg != -1) notif.imagePath = cursor.getString(colImg);
                                if (colBundle != -1) notif.bundleId = cursor.getString(colBundle);
                                if (colUser != -1) notif.userId = cursor.getInt(colUser);
                                if (colMeta != -1) notif.metadata = cursor.getString(colMeta);

                                notifs.add(notif);
                            }
                        }
                    }
                }

                // 2. Read all raw toast records
                List<ToastEntity> toasts = new ArrayList<>();
                boolean hasToastsTable = false;
                try (Cursor c = legacyDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='toasts'", null)) {
                    hasToastsTable = (c != null && c.moveToFirst());
                }

                if (hasToastsTable) {
                    try (Cursor toastCursor = legacyDb.rawQuery("SELECT * FROM toasts ORDER BY id ASC", null)) {
                        if (toastCursor != null) {
                            int colPkg = toastCursor.getColumnIndex("packageName");
                            int colApp = toastCursor.getColumnIndex("appName");
                            int colText = toastCursor.getColumnIndex("text");
                            int colTime = toastCursor.getColumnIndex("timestamp");
                            int colDup = toastCursor.getColumnIndex("duplicateCount");
                            int colUser = toastCursor.getColumnIndex("userId");

                            while (toastCursor.moveToNext()) {
                                String pkg = colPkg != -1 ? toastCursor.getString(colPkg) : "";
                                String app = colApp != -1 ? toastCursor.getString(colApp) : "";
                                String text = colText != -1 ? toastCursor.getString(colText) : "";
                                long time = colTime != -1 ? toastCursor.getLong(colTime) : 0;

                                ToastEntity toast = new ToastEntity(pkg, app, text, time);
                                if (colDup != -1) toast.duplicateCount = toastCursor.getInt(colDup);
                                if (colUser != -1) toast.userId = toastCursor.getInt(colUser);

                                toasts.add(toast);
                            }
                        }
                    }
                }

                // 3. Read all raw app rules
                List<AppRuleEntity> rules = new ArrayList<>();
                boolean hasRulesTable = false;
                try (Cursor c = legacyDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='app_rules'", null)) {
                    hasRulesTable = (c != null && c.moveToFirst());
                }

                if (hasRulesTable) {
                    try (Cursor ruleCursor = legacyDb.rawQuery("SELECT * FROM app_rules", null)) {
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

                                rules.add(new AppRuleEntity(pkg, app, blockAll, blockKw, allowKw, isRuleEnabled));
                            }
                        }
                    }
                }

                Log.i(TAG, "Legacy records extracted: " + notifs.size() + " notifications, "
                        + toasts.size() + " toasts, " + rules.size() + " rules in "
                        + (System.currentTimeMillis() - startTime) + "ms.");

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

                // 5. Atomic Batch Insert inside single transaction (< 100ms)
                final AppDatabase finalEncDb = encDb;
                finalEncDb.runInTransaction(() -> {
                    final int CHUNK = 500;
                    for (int i = 0; i < notifs.size(); i += CHUNK) {
                        int end = Math.min(i + CHUNK, notifs.size());
                        finalEncDb.notificationDao().insertAll(notifs.subList(i, end));
                    }
                    for (int i = 0; i < toasts.size(); i += CHUNK) {
                        int end = Math.min(i + CHUNK, toasts.size());
                        finalEncDb.toastDao().insertAll(toasts.subList(i, end));
                    }
                    if (!rules.isEmpty()) {
                        finalEncDb.appRuleDao().insertAll(rules);
                    }
                });

                // 6. Verify Integrity
                int newNotifCount = finalEncDb.notificationDao().getTotalCountSync();
                int newToastCount = finalEncDb.toastDao().getTotalCountSync();

                if (newNotifCount >= notifs.size() && newToastCount >= toasts.size()) {
                    AppDatabase.setInstance(finalEncDb);
                    prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply();

                    legacyDb.close();
                    legacyDb = null;
                    context.deleteDatabase(LEGACY_DB_NAME);

                    long duration = System.currentTimeMillis() - startTime;
                    Log.i(TAG, "Legacy database transfer complete in " + duration + "ms! ("
                            + newNotifCount + " notifications, " + newToastCount + " toasts)");
                } else {
                    throw new IllegalStateException("Migration verification mismatch: expected "
                            + notifs.size() + " notifs / " + toasts.size() + " toasts, but got "
                            + newNotifCount + " notifs / " + newToastCount + " toasts.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Database transfer failed: " + e.getMessage(), e);
                if (encDb != null) {
                    try { encDb.close(); } catch (Exception ignored) {}
                }
                context.deleteDatabase(AppDatabase.DATABASE_NAME);
            } finally {
                isMigrating.set(false);
                if (legacyDb != null && legacyDb.isOpen()) {
                    try { legacyDb.close(); } catch (Exception ignored) {}
                }
            }
        }
    }
}
