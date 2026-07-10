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

        String uriStr = intent.getStringExtra("uri");
        String password = intent.getStringExtra("password");
        boolean includeMedia = intent.getBooleanExtra("includeMedia", false);

        if (uriStr == null || password == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Uri uri = Uri.parse(uriStr);

        // Start Foreground Service immediately to satisfy Android 8.0+ requirements
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Exporting Backup")
                .setContentText("Preparing data...")
                .setSmallIcon(R.drawable.ic_notification)
                .setProgress(100, 0, false)
                .setOngoing(true);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
        }

        // Run the export on a background thread (BackupUtil handles it in a thread already,
        // but we orchestrate service stopping here)
        BackupUtil.exportBackup(this, uri, password, includeMedia, new BackupUtil.BackupProgressListener() {
            @Override
            public void onProgress(int progress) {
                notificationBuilder.setContentText("Exporting... " + progress + "%")
                        .setProgress(100, progress, false);
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
            }

            @Override
            public void onSuccess() {
                Notification successNotification = new NotificationCompat.Builder(BackupService.this, CHANNEL_ID)
                        .setContentTitle("Backup Export Successful")
                        .setContentText("Your encrypted backup was saved successfully.")
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

                Notification failureNotification = new NotificationCompat.Builder(BackupService.this, CHANNEL_ID)
                        .setContentTitle("Backup Export Failed")
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

        return START_NOT_STICKY;
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
