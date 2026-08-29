package com.zygisk_enc.notivault.util;

import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.zygisk_enc.notivault.R;

public class ThemeHelper {

    public static void applyTheme(AppCompatActivity activity) {
        String themePref = PreferenceManager.getDefaultSharedPreferences(activity).getString("theme_color", "grey");
        boolean isPitchBlack = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("pitch_black", false);
        boolean isNightMode = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        if (isPitchBlack && isNightMode) {
            activity.setTheme(R.style.Theme_NotiVault_Black);
        } else {
            if ("blue".equals(themePref)) {
                activity.setTheme(R.style.Theme_NotiVault_Blue);
            } else if ("green".equals(themePref)) {
                activity.setTheme(R.style.Theme_NotiVault_Green);
            } else if ("orange".equals(themePref)) {
                activity.setTheme(R.style.Theme_NotiVault_Orange);
            } else if ("purple".equals(themePref)) {
                activity.setTheme(R.style.Theme_NotiVault_Purple);
            } else {
                activity.setTheme(R.style.Theme_NotiVault_Grey);
            }
        }
    }
}
