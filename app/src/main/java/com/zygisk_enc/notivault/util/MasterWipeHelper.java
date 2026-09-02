package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.service.BackupService;
import com.zygisk_enc.notivault.service.ClearAllService;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import com.zygisk_enc.notivault.widget.WidgetHelper;
import java.io.File;

public class MasterWipeHelper {

    /**
     * Performs a 100% complete, zero-residual destruction of the database file,
     * WAL/SHM sidecars, media attachments, and in-memory caches, followed by
     * re-initializing a pristine empty encrypted database.
     */
    public static void executeMasterWipe(Context context, Runnable onComplete) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();

        AppExecutor.execute(() -> {
            // 1. Terminate any active background services
            try {
                appCtx.stopService(new Intent(appCtx, ClearAllService.class));
                appCtx.stopService(new Intent(appCtx, BackupService.class));
            } catch (Exception ignored) {}

            // 2. Safely close database connection pool and invalidate singleton
            AppDatabase.destroyInstance();

            // 3. Completely delete SQLite database files from disk
            try {
                appCtx.deleteDatabase(AppDatabase.DATABASE_NAME);
                appCtx.deleteDatabase("notivault_database"); // legacy unencrypted DB if present

                // Explicitly purge any remaining -wal, -shm, or -journal files
                File dbFile = appCtx.getDatabasePath(AppDatabase.DATABASE_NAME);
                if (dbFile != null) {
                    deleteFileIfExists(new File(dbFile.getPath() + "-wal"));
                    deleteFileIfExists(new File(dbFile.getPath() + "-shm"));
                    deleteFileIfExists(new File(dbFile.getPath() + "-journal"));
                    deleteFileIfExists(dbFile);
                }

                File legacyFile = appCtx.getDatabasePath("notivault_database");
                if (legacyFile != null) {
                    deleteFileIfExists(new File(legacyFile.getPath() + "-wal"));
                    deleteFileIfExists(new File(legacyFile.getPath() + "-shm"));
                    deleteFileIfExists(new File(legacyFile.getPath() + "-journal"));
                    deleteFileIfExists(legacyFile);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 4. Purge all media attachments from app storage
            try {
                File filesDir = appCtx.getFilesDir();
                if (filesDir != null && filesDir.exists()) {
                    File[] files = filesDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().startsWith("notif_") || f.getName().endsWith(".enc")) {
                                f.delete();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 5. Reset migration preferences
            try {
                appCtx.getSharedPreferences("notivault_migration_prefs", Context.MODE_PRIVATE)
                        .edit().clear().putBoolean("migrated_to_sqlcipher_db_v1", true).apply();
            } catch (Exception ignored) {}

            // 6. Evict all in-memory LRU plaintext caches
            NotificationViewModel.clearDecryptedCache();

            // 7. Re-create a fresh, clean, empty encrypted database
            try {
                AppDatabase freshDb = AppDatabase.getInstance(appCtx);
                freshDb.getOpenHelper().getWritableDatabase(); // Triggers schema creation
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 8. Update all home screen widgets
            try {
                WidgetHelper.updateAllWidgets(appCtx);
            } catch (Exception ignored) {}

            if (onComplete != null) {
                new Handler(Looper.getMainLooper()).post(onComplete);
            }
        });
    }

    private static void deleteFileIfExists(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }
}
