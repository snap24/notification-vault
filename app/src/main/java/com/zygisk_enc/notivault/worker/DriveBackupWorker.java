package com.zygisk_enc.notivault.worker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.zygisk_enc.notivault.util.BackupUtil;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorkManager worker that performs a scheduled encrypted backup into the
 * user-configured SAF folder (stored as "cloud_backup_uri").
 * The encrypted .vault file is written under the folder and given a
 * timestamped name so Drive/Nextcloud/etc. can sync it automatically.
 */
public class DriveBackupWorker extends Worker {

    private static final String TAG = "DriveBackupWorker";
    public static final String KEY_FOLDER_URI  = "cloud_backup_uri";
    public static final String KEY_PASSWORD    = "cloud_backup_password";

    public DriveBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        String folderUriStr = prefs.getString(KEY_FOLDER_URI, null);
        String password     = prefs.getString(KEY_PASSWORD, null);
        boolean includeMedia = com.zygisk_enc.notivault.util.PreferenceUtil.getCloudBackupIncludeMedia(ctx);

        if (folderUriStr == null || folderUriStr.isEmpty()) {
            Log.w(TAG, "No backup folder configured — skipping scheduled backup.");
            return Result.failure();
        }

        Uri folderUri = Uri.parse(folderUriStr);

        // Build a timestamped filename: notivault_backup_20260727_080000.vault
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        String filename = "notivault_backup_" + timestamp + ".vault";

        // Use DocumentFile to create a file inside the persisted SAF folder
        androidx.documentfile.provider.DocumentFile folder =
                androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, folderUri);

        if (folder == null || !folder.exists() || !folder.canWrite()) {
            Log.e(TAG, "Backup folder is not accessible: " + folderUriStr);
            return Result.failure();
        }

        androidx.documentfile.provider.DocumentFile newFile =
                folder.createFile("application/octet-stream", filename);

        if (newFile == null) {
            Log.e(TAG, "Could not create backup file in the folder.");
            return Result.failure();
        }

        Uri fileUri = newFile.getUri();

        // Block until BackupUtil completes (it runs on its own thread internally)
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        BackupUtil.exportBackup(ctx, fileUri, password != null ? password : "", includeMedia, new BackupUtil.BackupProgressListener() {
            @Override public void onProgress(int progress) { /* no-op for worker */ }

            @Override
            public void onSuccess() {
                success.set(true);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Scheduled backup failed", e);
                // Delete the broken file
                try { newFile.delete(); } catch (Exception ignored) {}
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure();
        }

        // Save last successful backup timestamp
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putLong("cloud_backup_last_run", System.currentTimeMillis())
                .apply();

        return success.get() ? Result.success() : Result.failure();
    }
}
