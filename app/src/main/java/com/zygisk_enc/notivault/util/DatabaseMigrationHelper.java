package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.io.File;

/**
 * Handles one-time transparent migration from legacy unencrypted SQLite database
 * with per-record encrypted fields into the new SQLCipher encrypted database.
 */
public class DatabaseMigrationHelper {

    private static final String TAG = "DatabaseMigrationHelper";
    private static final String PREF_MIGRATION_DONE = "migrated_to_sqlcipher_db_v1";
    private static final String PREFS_NAME = "notivault_migration_prefs";

    public static synchronized void ensureMigrated(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_MIGRATION_DONE, false)) {
            return;
        }

        File legacyDbFile = context.getDatabasePath("notivault_database");
        File encryptedDbFile = context.getDatabasePath("notivault_database_encrypted.db");

        if (!legacyDbFile.exists()) {
            // Fresh install or no legacy DB exists
            prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply();
            return;
        }

        if (encryptedDbFile.exists()) {
            // Target encrypted DB already initialized
            prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply();
            return;
        }

        Log.i(TAG, "Starting legacy database migration to SQLCipher...");
        SQLiteDatabase legacyDb = null;
        try {
            legacyDb = SQLiteDatabase.openDatabase(
                    legacyDbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            // Trigger SQLCipher initialization and Room creation
            byte[] passphrase = DatabaseKeyManager.getDatabasePassphrase(context);
            net.zetetic.database.sqlcipher.SupportOpenHelperFactory factory =
                    new net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase);

            com.zygisk_enc.notivault.database.AppDatabase encDb =
                    androidx.room.Room.databaseBuilder(
                            context.getApplicationContext(),
                            com.zygisk_enc.notivault.database.AppDatabase.class,
                            "notivault_database_encrypted.db"
                    )
                    .openHelperFactory(factory)
                    .addMigrations(
                            com.zygisk_enc.notivault.database.AppDatabase.MIGRATION_1_8,
                            com.zygisk_enc.notivault.database.AppDatabase.MIGRATION_7_8,
                            com.zygisk_enc.notivault.database.AppDatabase.MIGRATION_8_9,
                            com.zygisk_enc.notivault.database.AppDatabase.MIGRATION_9_10,
                            com.zygisk_enc.notivault.database.AppDatabase.MIGRATION_10_11,
                            com.zygisk_enc.notivault.database.AppDatabase.MIGRATION_11_12,
                            com.zygisk_enc.notivault.database.AppDatabase.MIGRATION_12_13
                    )
                    .build();

            // 1. Migrate Notifications
            Cursor cursor = legacyDb.rawQuery("SELECT * FROM notifications ORDER BY id ASC", null);
            if (cursor != null) {
                java.util.List<com.zygisk_enc.notivault.database.NotificationEntity> batch = new java.util.ArrayList<>();
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
                    String rawTitle = colTitle != -1 ? cursor.getString(colTitle) : "";
                    String rawText = colText != -1 ? cursor.getString(colText) : "";
                    String rawBigText = colBigText != -1 ? cursor.getString(colBigText) : null;
                    long time = colTime != -1 ? cursor.getLong(colTime) : 0;

                    // Decrypt legacy per-field ciphertexts so they are stored as clean plaintext in SQLCipher
                    String decTitle = rawTitle != null ? EncryptionHelper.decrypt(rawTitle) : "";
                    String decText = rawText != null ? EncryptionHelper.decrypt(rawText) : "";
                    String decBigText = rawBigText != null ? EncryptionHelper.decrypt(rawBigText) : null;

                    com.zygisk_enc.notivault.database.NotificationEntity entity =
                            new com.zygisk_enc.notivault.database.NotificationEntity(
                                    pkg, app, decTitle, decText, decBigText, time);

                    if (colRead != -1) entity.isRead = cursor.getInt(colRead) == 1;
                    if (colFav != -1) entity.isFavorite = cursor.getInt(colFav) == 1;
                    if (colDup != -1) entity.duplicateCount = cursor.getInt(colDup);
                    if (colImg != -1) entity.imagePath = cursor.getString(colImg);
                    if (colBundle != -1) entity.bundleId = cursor.getString(colBundle);
                    if (colUser != -1) entity.userId = cursor.getInt(colUser);
                    if (colMeta != -1) entity.metadata = cursor.getString(colMeta);

                    batch.add(entity);
                    if (batch.size() >= 250) {
                        encDb.notificationDao().insertAll(batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    encDb.notificationDao().insertAll(batch);
                    batch.clear();
                }
                cursor.close();
            }

            // 2. Migrate Toasts
            Cursor toastCursor = null;
            try {
                toastCursor = legacyDb.rawQuery("SELECT * FROM toasts ORDER BY id ASC", null);
                if (toastCursor != null) {
                    java.util.List<com.zygisk_enc.notivault.database.ToastEntity> toastBatch = new java.util.ArrayList<>();
                    int colPkg = toastCursor.getColumnIndex("packageName");
                    int colApp = toastCursor.getColumnIndex("appName");
                    int colText = toastCursor.getColumnIndex("text");
                    int colTime = toastCursor.getColumnIndex("timestamp");
                    int colDup = toastCursor.getColumnIndex("duplicateCount");
                    int colUser = toastCursor.getColumnIndex("userId");

                    while (toastCursor.moveToNext()) {
                        String pkg = colPkg != -1 ? toastCursor.getString(colPkg) : "";
                        String app = colApp != -1 ? toastCursor.getString(colApp) : "";
                        String rawText = colText != -1 ? toastCursor.getString(colText) : "";
                        long time = colTime != -1 ? toastCursor.getLong(colTime) : 0;
                        String decText = rawText != null ? EncryptionHelper.decrypt(rawText) : "";

                        com.zygisk_enc.notivault.database.ToastEntity toast =
                                new com.zygisk_enc.notivault.database.ToastEntity(pkg, app, decText, time);
                        if (colDup != -1) toast.duplicateCount = toastCursor.getInt(colDup);
                        if (colUser != -1) toast.userId = toastCursor.getInt(colUser);

                        toastBatch.add(toast);
                        if (toastBatch.size() >= 250) {
                            encDb.toastDao().insertAll(toastBatch);
                            toastBatch.clear();
                        }
                    }
                    if (!toastBatch.isEmpty()) {
                        encDb.toastDao().insertAll(toastBatch);
                        toastBatch.clear();
                    }
                    toastCursor.close();
                }
            } catch (Exception ignored) {}

            // 3. Migrate App Rules
            Cursor ruleCursor = null;
            try {
                ruleCursor = legacyDb.rawQuery("SELECT * FROM app_rules", null);
                if (ruleCursor != null) {
                    java.util.List<com.zygisk_enc.notivault.database.AppRuleEntity> ruleBatch = new java.util.ArrayList<>();
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

                        com.zygisk_enc.notivault.database.AppRuleEntity rule =
                                new com.zygisk_enc.notivault.database.AppRuleEntity(
                                        pkg, app, blockAll, blockKw, allowKw, isRuleEnabled);
                        ruleBatch.add(rule);
                    }
                    if (!ruleBatch.isEmpty()) {
                        encDb.appRuleDao().insertAll(ruleBatch);
                    }
                    ruleCursor.close();
                }
            } catch (Exception ignored) {}

            encDb.close();
            Log.i(TAG, "Legacy database migration to SQLCipher completed successfully!");
            prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply();

            // Archive or clean up legacy database
            try {
                context.deleteDatabase("notivault_database");
            } catch (Exception e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to migrate legacy database: " + e.getMessage(), e);
        } finally {
            if (legacyDb != null && legacyDb.isOpen()) {
                legacyDb.close();
            }
        }
    }
}
