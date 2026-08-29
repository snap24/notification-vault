package com.zygisk_enc.notivault.util;

public class AppLockManager {

    private static boolean isUnlocked = false;
    private static int runningActivities = 0;

    public static boolean isUnlocked() {
        return isUnlocked;
    }

    public static void setUnlocked(boolean unlocked) {
        isUnlocked = unlocked;
    }

    public static void onActivityStarted() {
        runningActivities++;
    }

    public static void onActivityStopped() {
        runningActivities = Math.max(0, runningActivities - 1);
        if (runningActivities == 0) {
            isUnlocked = false;
        }
    }
}
