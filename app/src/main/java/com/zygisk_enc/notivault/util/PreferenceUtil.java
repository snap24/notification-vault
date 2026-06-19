package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public class PreferenceUtil {

    private static final String KEY_AUTO_DELETE_DAYS = "auto_delete_days";
    private static final String KEY_CAPTURE_ENABLED = "capture_enabled";

    public static int getAutoDeleteDays(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return Integer.parseInt(prefs.getString(KEY_AUTO_DELETE_DAYS, "0"));
    }

    public static boolean isCaptureEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(KEY_CAPTURE_ENABLED, true);
    }
}
