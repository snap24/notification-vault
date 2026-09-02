package com.zygisk_enc.notivault.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.repository.NotificationRepository;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClearAllService extends Service {

    public static final String CHANNEL_ID = "vault_maintenance_channel_v2";
    public static final int NOTIFICATION_ID = 2005;

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final MutableLiveData<Integer> progressLiveData = new MutableLiveData<>(-1);

    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

    public static boolean isClearingInProgress() {
        return isRunning.get();
    }

    public static LiveData<Integer> getProgressLiveData() {
        return progressLiveData;
    }

    public static int getCurrentProgress() {
        Integer val = progressLiveData.getValue();
        return val != null ? val : -1;
    }

    public static void start(Context context) {
        if (context == null) return;
        if (!isRunning.compareAndSet(false, true)) {
            return; // Already running
        }
        progressLiveData.postValue(0);
        Intent intent = new Intent(context, ClearAllService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isRunning.set(true);

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.clear_all_title))
                .setContentText("Preparing vault clearance...")
                .setSmallIcon(R.drawable.ic_notification)
                .setProgress(100, 0, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
        }

        NotificationRepository repository = new NotificationRepository(getApplication());
        repository.deleteAll(new NotificationRepository.ProgressCallback() {
            @Override
            public void onProgress(int progress) {
                progressLiveData.postValue(progress);
                updateNotificationProgress(progress);
            }

            @Override
            public void onComplete() {
                progressLiveData.postValue(100);
                showCompletionNotification();
            }
        });

        return START_NOT_STICKY;
    }

    private void updateNotificationProgress(int progress) {
        if (notificationBuilder == null || notificationManager == null) return;
        String statusText;
        if (progress <= 10) {
            statusText = "Cleaning media attachments... " + progress + "%";
        } else if (progress <= 75) {
            statusText = "Deleting records from database... " + progress + "%";
        } else if (progress < 100) {
            statusText = "Optimizing & shrinking database... " + progress + "%";
        } else {
            statusText = "Vault cleared successfully";
        }

        notificationBuilder.setContentText(statusText)
                .setProgress(100, progress, false);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void showCompletionNotification() {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder.setContentText("Vault cleared successfully")
                    .setProgress(100, 100, false)
                    .setOngoing(false);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }

        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {}

            isRunning.set(false);
            progressLiveData.postValue(-1);

            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
            stopForeground(true);
            stopSelf();
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Vault Maintenance",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Progress notifications for database cleanup and maintenance");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        isRunning.set(false);
        progressLiveData.postValue(-1);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
