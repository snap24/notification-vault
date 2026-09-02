package com.zygisk_enc.notivault.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.util.BackupUtil;

public class BackupService extends Service {

    public static final String ACTION_EXPORT = "com.zygisk_enc.notivault.action.EXPORT_BACKUP";
    public static final String ACTION_IMPORT = "com.zygisk_enc.notivault.action.IMPORT_BACKUP";

    private static final String CHANNEL_ID = "backup_tasks_channel";
    private static final int NOTIFICATION_ID = 2002;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        String uriStr = intent.getStringExtra("uri");
        String password = intent.getStringExtra("password");

        if (uriStr == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (password == null) {
            password = "";
        }

        Uri uri = Uri.parse(uriStr);

        if (ACTION_IMPORT.equals(action)) {
            handleImport(uri, password);
        } else {
            boolean includeMedia = intent.getBooleanExtra("includeMedia", false);
            boolean isCloudBackup = intent.getBooleanExtra("isCloudBackup", false);
            handleExport(uri, password, includeMedia, isCloudBackup);
        }

        return START_NOT_STICKY;
    }

    private void handleExport(Uri uri, String password, boolean includeMedia, boolean isCloudBackup) {
        String title = isCloudBackup ? getString(R.string.notification_cloud_backup_running) : "Exporting Backup";
        String initialText = "Preparing data...";

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(initialText)
                .setSmallIcon(R.drawable.ic_notification)
                .setProgress(100, 0, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
        }

        final long[] lastExportNotificationUpdate = {0};
        BackupUtil.exportBackup(this, uri, password, includeMedia, new BackupUtil.BackupProgressListener() {
            @Override
            public void onProgress(int progress) {
                long now = System.currentTimeMillis();
                if (now - lastExportNotificationUpdate[0] >= 300 || progress == 0 || progress == 100) {
                    lastExportNotificationUpdate[0] = now;
                    notificationBuilder.setContentText((isCloudBackup ? getString(R.string.notification_cloud_backup_running) : "Exporting") + "... " + progress + "%")
                            .setProgress(100, progress, false);
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                }
            }

            @Override
            public void onSuccess() {
                if (isCloudBackup) {
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(BackupService.this)
                            .edit().putLong("cloud_backup_last_run", System.currentTimeMillis()).apply();
                }

                String successTitle = isCloudBackup ? getString(R.string.notification_cloud_backup_success) : "Backup Export Successful";
                String successText = isCloudBackup ? getString(R.string.notification_cloud_backup_success_desc) : "Your encrypted backup was saved successfully.";

                Notification successNotification = new NotificationCompat.Builder(BackupService.this, CHANNEL_ID)
                        .setContentTitle(successTitle)
                        .setContentText(successText)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .build();

                notificationManager.notify(NOTIFICATION_ID, successNotification);
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }

            @Override
            public void onFailure(Exception e) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                String failureTitle = isCloudBackup ? getString(R.string.notification_cloud_backup_failed) : "Backup Export Failed";

                Notification failureNotification = new NotificationCompat.Builder(BackupService.this, CHANNEL_ID)
                        .setContentTitle(failureTitle)
                        .setContentText("Error: " + e.getMessage())
                        .setSmallIcon(R.drawable.ic_notification)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .build();

                notificationManager.notify(NOTIFICATION_ID, failureNotification);
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }
        });
    }

    private void handleImport(Uri uri, String password) {
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.importing_backup_title))
                .setContentText(getString(R.string.importing_backup_message))
                .setSmallIcon(R.drawable.ic_notification)
                .setProgress(100, 0, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
        }

        com.zygisk_enc.notivault.viewmodel.NotificationViewModel.setGlobalOperationProgress(
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel.OperationProgress.TYPE_IMPORTING, 0);

        final long[] lastImportNotificationUpdate = {0};
        BackupUtil.importBackup(this, uri, password, new BackupUtil.BackupProgressListener() {
            @Override
            public void onProgress(int progress) {
                long now = System.currentTimeMillis();
                if (now - lastImportNotificationUpdate[0] >= 300 || progress == 0 || progress == 100) {
                    lastImportNotificationUpdate[0] = now;
                    notificationBuilder.setContentText(getString(R.string.importing_backup_title) + "... " + progress + "%")
                            .setProgress(100, progress, false);
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                }
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel.setGlobalOperationProgress(
                        com.zygisk_enc.notivault.viewmodel.NotificationViewModel.OperationProgress.TYPE_IMPORTING, progress);
            }

            @Override
            public void onSuccess() {
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel.setGlobalOperationProgress(
                        com.zygisk_enc.notivault.viewmodel.NotificationViewModel.OperationProgress.TYPE_IMPORTING, 100);

                com.zygisk_enc.notivault.util.BundleManager.triggerPostImportBundling(BackupService.this, () -> {
                    // Mark pending post-import verification flag in SharedPreferences
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(BackupService.this)
                            .edit().putBoolean("pending_post_import_verify", true).apply();

                    // Trigger verification event for foreground UI
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel.triggerPostImportVerification();

                    // Clear operation progress
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel.setGlobalOperationProgress(
                            com.zygisk_enc.notivault.viewmodel.NotificationViewModel.OperationProgress.TYPE_NONE, -1);

                    Intent launchIntent = new Intent(BackupService.this, com.zygisk_enc.notivault.MainActivity.class);
                    launchIntent.setAction(Intent.ACTION_MAIN);
                    launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    launchIntent.putExtra("trigger_verify_integrity", true);

                    android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                            BackupService.this,
                            2003,
                            launchIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)
                    );

                    Notification successNotification = new NotificationCompat.Builder(BackupService.this, CHANNEL_ID)
                            .setContentTitle(getString(R.string.importing_backup_title))
                            .setContentText(getString(R.string.backup_import_success))
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)
                            .setProgress(0, 0, false)
                            .setOngoing(false)
                            .build();

                    notificationManager.notify(NOTIFICATION_ID, successNotification);
                    stopForeground(STOP_FOREGROUND_DETACH);
                    stopSelf();
                });
            }

            @Override
            public void onFailure(Exception e) {
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel.setGlobalOperationProgress(
                        com.zygisk_enc.notivault.viewmodel.NotificationViewModel.OperationProgress.TYPE_NONE, -1);

                Notification failureNotification = new NotificationCompat.Builder(BackupService.this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.importing_backup_title))
                        .setContentText(getString(R.string.backup_import_failed, e.getMessage()))
                        .setSmallIcon(R.drawable.ic_notification)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .build();

                notificationManager.notify(NOTIFICATION_ID, failureNotification);
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Backup & Restore Tasks",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Progress notifications for database backups and restores");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
