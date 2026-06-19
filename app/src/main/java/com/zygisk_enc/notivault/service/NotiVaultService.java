package com.zygisk_enc.notivault.service;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotiVaultService extends NotificationListenerService {

    private static final String[] EXCLUDED_PACKAGES = {
            "com.zygisk_enc.notivault",
            "android"
    };

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (!PreferenceUtil.isCaptureEnabled(this)) return;

        String packageName = sbn.getPackageName();
        if (isExcluded(packageName)) return;

        // Skip ongoing / foreground service notifications
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCS = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCS = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigTextCS = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

        String title = titleCS != null ? titleCS.toString().trim() : "";
        String text = textCS != null ? textCS.toString().trim() : "";
        String bigText = bigTextCS != null ? bigTextCS.toString().trim() : null;

        // Skip entirely empty notifications
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;

        String appName = getAppName(packageName);
        long timestamp = sbn.getPostTime();

        NotificationEntity entity = new NotificationEntity(
                packageName, appName, title, text, bigText, timestamp);

        executor.execute(() -> AppDatabase.getInstance(this).notificationDao().insert(entity));
    }

    private String getAppName(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private boolean isExcluded(String packageName) {
        for (String pkg : EXCLUDED_PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }
}
