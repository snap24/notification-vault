package com.zygisk_enc.notivault.util;

import android.app.Activity;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class AppLockManager {

    private static boolean isUnlocked = false;
    private static boolean isExpectingActivityResult = false;
    private static final Set<Activity> activeActivities = Collections.newSetFromMap(new WeakHashMap<>());

    public static boolean isUnlocked() {
        return isUnlocked;
    }

    public static void setUnlocked(boolean unlocked) {
        isUnlocked = unlocked;
    }

    public static boolean isExpectingActivityResult() {
        return isExpectingActivityResult;
    }

    public static void setExpectingActivityResult(boolean expecting) {
        isExpectingActivityResult = expecting;
    }

    public static void onActivityStarted(Activity activity) {
        if (activity != null) {
            activeActivities.add(activity);
        }
    }

    public static void onActivityStopped(Activity activity) {
        if (activity != null) {
            activeActivities.remove(activity);
            if (activeActivities.isEmpty() && !activity.isChangingConfigurations() && !isExpectingActivityResult) {
                isUnlocked = false;
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel.clearDecryptedCache();
            }
        }
    }

    public static void reset() {
        isUnlocked = false;
        isExpectingActivityResult = false;
        activeActivities.clear();
        com.zygisk_enc.notivault.viewmodel.NotificationViewModel.clearDecryptedCache();
    }
}
