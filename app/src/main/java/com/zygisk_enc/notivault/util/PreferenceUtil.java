package com.zygisk_enc.notivault.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.preference.PreferenceManager;

public class PreferenceUtil {

    private static final String KEY_AUTO_DELETE_DAYS = "auto_delete_days";
    private static final String KEY_AUTO_DELETE_MODE = "auto_delete_mode";
    private static final String KEY_AUTO_DELETE_PACKAGES = "auto_delete_packages";
    private static final String KEY_AUTO_DELETE_APP_RULES = "auto_delete_app_rules";
    private static final String KEY_CAPTURE_ENABLED = "capture_enabled";
    private static final String KEY_LAST_AUTO_DELETE = "last_auto_delete_time";

    public static int getGlobalAutoDeleteDays(Context context) {
        return getAutoDeleteDays(context);
    }

    public static void setGlobalAutoDeleteDays(Context context, int days) {
        setAutoDeleteDays(context, days);
    }

    public static int getAutoDeleteDays(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String val = prefs.getString(KEY_AUTO_DELETE_DAYS, "0");
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setAutoDeleteDays(Context context, int days) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(KEY_AUTO_DELETE_DAYS, String.valueOf(days)).apply();
    }

    public static java.util.Map<String, Integer> getAppAutoDeleteRules(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        java.util.Map<String, Integer> rules = new java.util.HashMap<>();
        String json = prefs.getString(KEY_AUTO_DELETE_APP_RULES, null);
        if (json != null && !json.trim().isEmpty()) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(json);
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String pkg = keys.next();
                    rules.put(pkg, obj.getInt(pkg));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return rules;
    }

    public static void setAppAutoDeleteRules(Context context, java.util.Map<String, Integer> rules) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        org.json.JSONObject obj = new org.json.JSONObject();
        if (rules != null) {
            for (java.util.Map.Entry<String, Integer> entry : rules.entrySet()) {
                try {
                    obj.put(entry.getKey(), entry.getValue());
                } catch (Exception ignored) {}
            }
        }
        prefs.edit().putString(KEY_AUTO_DELETE_APP_RULES, obj.toString()).apply();
    }

    public static void setAppAutoDeleteRule(Context context, String packageName, Integer days) {
        java.util.Map<String, Integer> rules = getAppAutoDeleteRules(context);
        if (days == null) {
            rules.remove(packageName);
        } else {
            rules.put(packageName, days);
        }
        setAppAutoDeleteRules(context, rules);
    }

    public static Integer getAppAutoDeleteRule(Context context, String packageName) {
        java.util.Map<String, Integer> rules = getAppAutoDeleteRules(context);
        return rules.get(packageName);
    }

    public static String getAutoDeleteMode(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(KEY_AUTO_DELETE_MODE, "all");
    }

    public static void setAutoDeleteMode(Context context, String mode) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(KEY_AUTO_DELETE_MODE, mode).apply();
    }

    public static java.util.Set<String> getAutoDeletePackages(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getStringSet(KEY_AUTO_DELETE_PACKAGES, new java.util.HashSet<>());
    }

    public static void setAutoDeletePackages(Context context, java.util.Set<String> packages) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putStringSet(KEY_AUTO_DELETE_PACKAGES, packages).apply();
    }

    private static final String KEY_SHOW_READ_UNREAD = "show_read_unread_status";

    public static boolean isShowReadUnreadEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(KEY_SHOW_READ_UNREAD, false);
    }

    public static void setShowReadUnreadEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(KEY_SHOW_READ_UNREAD, enabled).apply();
    }

    public static boolean isCaptureEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(KEY_CAPTURE_ENABLED, true);
    }

    public static void setCaptureEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply();
    }

    public static void setTileServiceEnabled(Context context, boolean enabled) {
        ComponentName componentName = new ComponentName(context, "com.zygisk_enc.notivault.service.NotiVaultTileService");
        context.getPackageManager().setComponentEnabledSetting(
            componentName,
            enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
    }

    public static long getLastAutoDeleteTime(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getLong(KEY_LAST_AUTO_DELETE, 0L);
    }

    public static void setLastAutoDeleteTime(Context context, long timestamp) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putLong(KEY_LAST_AUTO_DELETE, timestamp).apply();
    }

    // ── Cloud Backup Preferences ────────────────────────────────────────────

    public static String getCloudBackupUri(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString("cloud_backup_uri", null);
    }

    public static void setCloudBackupUri(Context context, String uriString) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString("cloud_backup_uri", uriString).apply();
    }

    public static String getCloudBackupPassword(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString("cloud_backup_password", null);
    }

    public static void setCloudBackupPassword(Context context, String password) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString("cloud_backup_password", password).apply();
    }

    /** Returns schedule interval in hours, or 0 if disabled. */
    public static int getCloudBackupIntervalHours(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt("cloud_backup_interval_hours", 0);
    }

    public static void setCloudBackupIntervalHours(Context context, int hours) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putInt("cloud_backup_interval_hours", hours).apply();
    }

    public static long getCloudBackupLastRun(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getLong("cloud_backup_last_run", 0L);
    }

    public static boolean getCloudBackupIncludeMedia(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("cloud_backup_include_media", false);
    }

    public static void setCloudBackupIncludeMedia(Context context, boolean include) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean("cloud_backup_include_media", include).apply();
    }

    // ── Widget Feed Filter Preferences ──────────────────────────────────────

    public static String getWidgetFeedPackage(Context context, int appWidgetId) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString("widget_feed_filter_pkg_" + appWidgetId, null);
    }

    public static void setWidgetFeedPackage(Context context, int appWidgetId, String packageName) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString("widget_feed_filter_pkg_" + appWidgetId, packageName).apply();
    }
}
