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

    private static final String CHANNEL_ID = "backup_channel";
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

        if (uriStr == null || password == null) {
            stopSelf();
            return START_NOT_STICKY;
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
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
        }

        BackupUtil.exportBackup(this, uri, password, includeMedia, new BackupUtil.BackupProgressListener() {
            @Override
            public void onProgress(int progress) {
                notificationBuilder.setContentText((isCloudBackup ? getString(R.string.notification_cloud_backup_running) : "Exporting") + "... " + progress + "%")
                        .setProgress(100, progress, false);
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
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
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
        }

        BackupUtil.importBackup(this, uri, password, new BackupUtil.BackupProgressListener() {
            @Override
            public void onProgress(int progress) {
                notificationBuilder.setContentText(getString(R.string.importing_backup_title) + "... " + progress + "%")
                        .setProgress(100, progress, false);
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
            }

            @Override
            public void onSuccess() {
                Notification successNotification = new NotificationCompat.Builder(BackupService.this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.importing_backup_title))
                        .setContentText(getString(R.string.backup_import_success))
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
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Progress notifications for database backups and restores");
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
