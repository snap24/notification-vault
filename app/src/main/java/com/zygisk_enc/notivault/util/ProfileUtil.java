package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility helper for Android Multi-User & Work Profile (Managed Profile) detection,
 * badged icon rendering, and cross-profile application resolution.
 */
public final class ProfileUtil {

    private static final Map<Integer, Boolean> WORK_PROFILE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, UserHandle> USER_HANDLE_CACHE = new ConcurrentHashMap<>();
    private static volatile long lastProfileCacheUpdate = 0;
    private static final long CACHE_TTL_MS = 60_000L; // 1 minute

    private ProfileUtil() {}

    /**
     * Checks if a given userId belongs to an Android Managed Profile (Work Profile).
     */
    public static boolean isWorkProfile(@NonNull Context context, int userId) {
        if (userId == 0) return false;
        refreshCacheIfNeeded(context);
        Boolean isWork = WORK_PROFILE_CACHE.get(userId);
        if (isWork != null) {
            return isWork;
        }

        UserHandle handle = getUserHandle(context, userId);
        if (handle != null) {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null) {
                boolean result = isManagedProfile(um, handle);
                WORK_PROFILE_CACHE.put(userId, result);
                return result;
            }
        }
        return false;
    }

    /**
     * Returns true if the device has at least one active Work Profile.
     */
    public static boolean hasWorkProfile(@NonNull Context context) {
        refreshCacheIfNeeded(context);
        for (Boolean isWork : WORK_PROFILE_CACHE.values()) {
            if (Boolean.TRUE.equals(isWork)) return true;
        }
        return false;
    }

    /**
     * Returns all user profiles on the device (Personal + Work Profile + Clone/Secondary).
     */
    @NonNull
    public static List<UserHandle> getAllProfiles(@NonNull Context context) {
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um != null) {
                List<UserHandle> profiles = um.getUserProfiles();
                if (profiles != null) return profiles;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Collections.singletonList(Process.myUserHandle());
    }

    /**
     * Finds the UserHandle corresponding to a given integer userId.
     */
    @Nullable
    public static UserHandle getUserHandle(@NonNull Context context, int userId) {
        if (userId == 0) {
            return Process.myUserHandle();
        }
        refreshCacheIfNeeded(context);
        UserHandle cached = USER_HANDLE_CACHE.get(userId);
        if (cached != null) {
            return cached;
        }

        List<UserHandle> profiles = getAllProfiles(context);
        for (UserHandle profile : profiles) {
            if (getUserId(profile) == userId) {
                USER_HANDLE_CACHE.put(userId, profile);
                return profile;
            }
        }
        return null;
    }

    /**
     * Extracts integer user ID from UserHandle across Android versions.
     */
    public static int getUserId(@NonNull UserHandle userHandle) {
        return userHandle.hashCode();
    }

    /**
     * Resolves the app label for a specific package and profile.
     */
    @NonNull
    public static String getAppLabel(@NonNull Context context, @NonNull String packageName, int userId, @Nullable String fallback) {
        try {
            UserHandle handle = getUserHandle(context, userId);
            if (handle != null && userId != 0) {
                LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                if (launcherApps != null) {
                    List<LauncherActivityInfo> activities = launcherApps.getActivityList(packageName, handle);
                    if (activities != null && !activities.isEmpty()) {
                        CharSequence label = activities.get(0).getLabel();
                        if (label != null && label.length() > 0) {
                            return label.toString();
                        }
                    }
                }
            }

            PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception ignored) {}

        return (fallback != null && !fallback.isEmpty()) ? fallback : packageName;
    }

    /**
     * Loads a badged icon for the given package and user profile.
     */
    @Nullable
    public static Drawable getBadgedAppIcon(@NonNull Context context, @NonNull String packageName, int userId) {
        try {
            UserHandle handle = getUserHandle(context, userId);
            PackageManager pm = context.getPackageManager();

            if (handle != null && userId != 0) {
                LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                if (launcherApps != null) {
                    List<LauncherActivityInfo> activities = launcherApps.getActivityList(packageName, handle);
                    if (activities != null && !activities.isEmpty()) {
                        int density = context.getResources().getDisplayMetrics().densityDpi;
                        Drawable badged = activities.get(0).getBadgedIcon(density);
                        if (badged != null) return badged;
                    }
                }

                // Fallback: get raw icon and badge it
                Drawable rawIcon = pm.getApplicationIcon(packageName);
                return pm.getUserBadgedIcon(rawIcon, handle);
            }

            return pm.getApplicationIcon(packageName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void refreshCacheIfNeeded(@NonNull Context context) {
        long now = System.currentTimeMillis();
        if (now - lastProfileCacheUpdate > CACHE_TTL_MS || WORK_PROFILE_CACHE.isEmpty()) {
            synchronized (ProfileUtil.class) {
                if (now - lastProfileCacheUpdate > CACHE_TTL_MS || WORK_PROFILE_CACHE.isEmpty()) {
                    try {
                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                        if (um != null) {
                            List<UserHandle> profiles = um.getUserProfiles();
                            if (profiles != null) {
                                for (UserHandle p : profiles) {
                                    int uid = getUserId(p);
                                    USER_HANDLE_CACHE.put(uid, p);
                                    WORK_PROFILE_CACHE.put(uid, isManagedProfile(um, p));
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    lastProfileCacheUpdate = now;
                }
            }
        }
    }

    private static boolean isManagedProfile(@NonNull UserManager um, @NonNull UserHandle userHandle) {
        if (userHandle.equals(Process.myUserHandle())) {
            try {
                return um.isManagedProfile();
            } catch (Throwable ignored) {}
        }
        try {
            java.lang.reflect.Method method = UserManager.class.getMethod("isManagedProfile", int.class);
            Object result = method.invoke(um, userHandle.hashCode());
            if (result instanceof Boolean) return (Boolean) result;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method method = UserManager.class.getMethod("isProfile", UserHandle.class);
            Object result = method.invoke(um, userHandle);
            if (result instanceof Boolean) return (Boolean) result;
        } catch (Throwable ignored) {}
        return userHandle.hashCode() != 0 && !userHandle.equals(Process.myUserHandle());
    }
}
