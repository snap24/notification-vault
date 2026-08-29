package com.zygisk_enc.notivault;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.zygisk_enc.notivault.util.AppLockManager;
import com.zygisk_enc.notivault.util.ShortcutHelper;

public class NotiVaultApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize dynamic shortcuts on app process start
        ShortcutHelper.updateDynamicShortcuts(this);

        // Immediately lock app when the device screen turns off / power button is pressed
        android.content.IntentFilter screenOffFilter = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    AppLockManager.setUnlocked(false);
                }
            }
        }, screenOffFilter);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                boolean isFlagSecure = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                        .getBoolean("flag_secure", true);
                if (isFlagSecure) {
                    activity.getWindow().setFlags(
                            android.view.WindowManager.LayoutParams.FLAG_SECURE,
                            android.view.WindowManager.LayoutParams.FLAG_SECURE
                    );
                }
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                if (!(activity instanceof com.zygisk_enc.notivault.util.AuthActionActivity) &&
                    !(activity instanceof com.zygisk_enc.notivault.widget.WidgetConfigAuthActivity)) {
                    AppLockManager.onActivityStarted();
                }
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {}

            @Override
            public void onActivityPaused(@NonNull Activity activity) {}

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                if (!activity.isChangingConfigurations()) {
                    if (!(activity instanceof com.zygisk_enc.notivault.util.AuthActionActivity) &&
                        !(activity instanceof com.zygisk_enc.notivault.widget.WidgetConfigAuthActivity)) {
                        AppLockManager.onActivityStopped();
                    }
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) {
            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(this).clearCache();
            com.zygisk_enc.notivault.adapter.NotificationAdapter.clearImageCache();
            com.zygisk_enc.notivault.viewmodel.NotificationViewModel.clearDecryptedCache();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        com.zygisk_enc.notivault.util.AppIconLoader.getInstance(this).clearCache();
        com.zygisk_enc.notivault.adapter.NotificationAdapter.clearImageCache();
        com.zygisk_enc.notivault.viewmodel.NotificationViewModel.clearDecryptedCache();
    }
}
