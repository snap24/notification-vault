package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zygisk_enc.notivault.MainActivity;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.ToastHistoryActivity;
import java.util.ArrayList;
import java.util.List;

public class ShortcutHelper {

    public static final String ID_TOAST_LOGS = "shortcut_toast_logs";
    public static final String ID_FAVORITES = "shortcut_favorites";
    public static final String ID_SEARCH = "shortcut_search";
    public static final String ID_TOGGLE_CAPTURE = "shortcut_toggle_capture";

    public static void showPinShortcutsDialog(Context context) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.shortcut_pin_not_supported, Toast.LENGTH_LONG).show();
            return;
        }

        boolean captureEnabled = PreferenceUtil.isCaptureEnabled(context);
        String toggleLabel = captureEnabled
                ? context.getString(R.string.shortcut_pause_capture)
                : context.getString(R.string.shortcut_resume_capture);

        String[] shortcutTitles = new String[]{
                context.getString(R.string.shortcut_toast_logs_short) + " — " + context.getString(R.string.shortcut_toast_logs_long),
                context.getString(R.string.shortcut_favorites_short) + " — " + context.getString(R.string.shortcut_favorites_long),
                context.getString(R.string.shortcut_search_short) + " — " + context.getString(R.string.shortcut_search_long),
                toggleLabel + " — " + context.getString(R.string.shortcut_toggle_capture_long)
        };

        String[] shortcutIds = new String[]{
                ID_TOAST_LOGS,
                ID_FAVORITES,
                ID_SEARCH,
                ID_TOGGLE_CAPTURE
        };

        com.zygisk_enc.notivault.BaseActivity.showDialog(context, new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_pin_shortcuts_title)
                .setItems(shortcutTitles, (dialog, which) -> {
                    if (which >= 0 && which < shortcutIds.length) {
                        pinShortcut(context, shortcutIds[which]);
                    }
                })
                .setNegativeButton(R.string.cancel, null));
    }

    public static void pinShortcut(Context context, String shortcutId) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.shortcut_pin_not_supported, Toast.LENGTH_LONG).show();
            return;
        }

        ShortcutInfoCompat shortcutInfo = createShortcutInfo(context, shortcutId);
        if (shortcutInfo != null) {
            boolean success = ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null);
            if (success) {
                Toast.makeText(context, R.string.shortcut_pin_success, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static IconCompat createStyledShortcutIcon(Context context, int iconRes, int glyphColor, int bgColor) {
        try {
            float density = context.getResources().getDisplayMetrics().density;
            int size = (int) (48 * density);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

            // Draw circular badge background
            android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(bgColor);
            float radius = size / 2f;
            canvas.drawCircle(radius, radius, radius, bgPaint);

            // Draw centered vector icon with tint
            android.graphics.drawable.Drawable drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes);
            if (drawable != null) {
                android.graphics.drawable.Drawable wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(drawable.mutate());
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, glyphColor);

                int padding = (int) (10 * density);
                wrapped.setBounds(padding, padding, size - padding, size - padding);
                wrapped.draw(canvas);
            }
            return IconCompat.createWithBitmap(bitmap);
        } catch (Exception e) {
            e.printStackTrace();
            return IconCompat.createWithResource(context, iconRes);
        }
    }

    public static ShortcutInfoCompat createShortcutInfo(Context context, String shortcutId) {
        switch (shortcutId) {
            case ID_TOAST_LOGS: {
                Intent intent = new Intent(context, ToastHistoryActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                return new ShortcutInfoCompat.Builder(context, ID_TOAST_LOGS)
                        .setShortLabel(context.getString(R.string.shortcut_toast_logs_short))
                        .setLongLabel(context.getString(R.string.shortcut_toast_logs_long))
                        .setIcon(createStyledShortcutIcon(context, R.drawable.ic_toast_bread, 0xFFE65100, 0xFFFFE0B2))
                        .setIntent(intent)
                        .setRank(1)
                        .build();
            }
            case ID_FAVORITES: {
                Intent intent = new Intent(context, MainActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.putExtra("shortcut_action", "open_favorites");
                return new ShortcutInfoCompat.Builder(context, ID_FAVORITES)
                        .setShortLabel(context.getString(R.string.shortcut_favorites_short))
                        .setLongLabel(context.getString(R.string.shortcut_favorites_long))
                        .setIcon(createStyledShortcutIcon(context, R.drawable.ic_star, 0xFFF57F17, 0xFFFFF9C4))
                        .setIntent(intent)
                        .setRank(2)
                        .build();
            }
            case ID_SEARCH: {
                Intent intent = new Intent(context, MainActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.putExtra("shortcut_action", "open_search");
                return new ShortcutInfoCompat.Builder(context, ID_SEARCH)
                        .setShortLabel(context.getString(R.string.shortcut_search_short))
                        .setLongLabel(context.getString(R.string.shortcut_search_long))
                        .setIcon(createStyledShortcutIcon(context, R.drawable.ic_search, 0xFF1565C0, 0xFFBBDEFB))
                        .setIntent(intent)
                        .setRank(3)
                        .build();
            }
            case ID_TOGGLE_CAPTURE: {
                boolean captureEnabled = PreferenceUtil.isCaptureEnabled(context);
                Intent intent = new Intent(context, AuthActionActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.putExtra(AuthActionActivity.EXTRA_ACTION, AuthActionActivity.ACTION_TOGGLE_CAPTURE);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int iconRes = captureEnabled ? R.drawable.ic_pause : R.drawable.ic_play_arrow;
                int glyphColor = captureEnabled ? 0xFFC62828 : 0xFF2E7D32;
                int bgColor = captureEnabled ? 0xFFFFCDD2 : 0xFFC8E6C9;

                String shortLabel = captureEnabled
                        ? context.getString(R.string.shortcut_pause_capture)
                        : context.getString(R.string.shortcut_resume_capture);
                String longLabel = captureEnabled
                        ? "Pause Notification Recording"
                        : "Resume Notification Recording";

                return new ShortcutInfoCompat.Builder(context, ID_TOGGLE_CAPTURE)
                        .setShortLabel(shortLabel)
                        .setLongLabel(longLabel)
                        .setIcon(createStyledShortcutIcon(context, iconRes, glyphColor, bgColor))
                        .setIntent(intent)
                        .setRank(0)
                        .build();
            }
        }
        return null;
    }

    public static void updateDynamicShortcuts(Context context) {
        try {
            List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
            shortcuts.add(createShortcutInfo(context, ID_TOGGLE_CAPTURE));
            shortcuts.add(createShortcutInfo(context, ID_TOAST_LOGS));
            shortcuts.add(createShortcutInfo(context, ID_FAVORITES));
            shortcuts.add(createShortcutInfo(context, ID_SEARCH));

            ShortcutManagerCompat.removeAllDynamicShortcuts(context);
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
