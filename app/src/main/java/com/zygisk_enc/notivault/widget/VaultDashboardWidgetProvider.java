package com.zygisk_enc.notivault.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.util.SizeF;
import android.widget.RemoteViews;
import android.widget.Toast;
import com.zygisk_enc.notivault.MainActivity;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.ToastHistoryActivity;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.service.NotiVaultTileService;
import com.zygisk_enc.notivault.util.AppExecutor;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.ProfileUtil;
import com.zygisk_enc.notivault.util.ShortcutHelper;
import java.util.HashMap;
import java.util.Map;

public class VaultDashboardWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TOGGLE_CAPTURE_DASHBOARD = "com.zygisk_enc.notivault.widget.ACTION_TOGGLE_CAPTURE_DASHBOARD";
    public static final String ACTION_SWITCH_PROFILE_DASHBOARD = "com.zygisk_enc.notivault.widget.ACTION_SWITCH_PROFILE_DASHBOARD";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateDashboardAsync(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateDashboardAsync(context, appWidgetManager, new int[]{appWidgetId});
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TOGGLE_CAPTURE_DASHBOARD.equals(intent.getAction())) {
            boolean current = PreferenceUtil.isCaptureEnabled(context);
            boolean next = !current;
            PreferenceUtil.setCaptureEnabled(context, next);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(
                        context,
                        new ComponentName(context, NotiVaultTileService.class)
                );
            }

            Toast.makeText(context, next ? R.string.capture_resumed_toast : R.string.capture_paused_toast, Toast.LENGTH_SHORT).show();
            ShortcutHelper.updateDynamicShortcuts(context);
            WidgetHelper.updateAllWidgets(context);
        } else if (ACTION_SWITCH_PROFILE_DASHBOARD.equals(intent.getAction())) {
            if (ProfileUtil.hasWorkProfile(context)) {
                int current = PreferenceUtil.getActiveProfileMode(context);
                int next = (current == 0) ? 1 : 0;
                PreferenceUtil.setActiveProfileMode(context, next);
                Toast.makeText(context, next == 1 ? R.string.badge_work : R.string.badge_personal, Toast.LENGTH_SHORT).show();
                WidgetHelper.updateAllWidgets(context);
            }
        }
    }

    private static void updateDashboardAsync(Context context, AppWidgetManager manager, int[] ids) {
        AppExecutor.execute(() -> {
            boolean captureEnabled = PreferenceUtil.isCaptureEnabled(context);
            boolean hasWorkProfile = ProfileUtil.hasWorkProfile(context);
            int profileMode = hasWorkProfile ? PreferenceUtil.getActiveProfileMode(context) : -1;
            long startToday = WidgetHelper.getStartOfTodayMillis();

            int notifCount = 0;
            int toastCount = 0;
            try {
                notifCount = AppDatabase.getInstance(context).notificationDao().getCountSinceSync(startToday, profileMode);
                toastCount = AppDatabase.getInstance(context).toastDao().getCountSinceSync(startToday, -1);
            } catch (Exception e) {
                e.printStackTrace();
            }

            for (int id : ids) {
                RemoteViews viewsToApply;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Map<SizeF, RemoteViews> viewMapping = new HashMap<>();
                    viewMapping.put(new SizeF(100f, 40f), build1RowViews(context, captureEnabled, notifCount, toastCount, hasWorkProfile, profileMode));
                    viewMapping.put(new SizeF(100f, 100f), build2RowViews(context, captureEnabled, notifCount, toastCount, hasWorkProfile, profileMode));
                    viewsToApply = new RemoteViews(viewMapping);
                } else {
                    Bundle options = manager.getAppWidgetOptions(id);
                    int minHeight = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40) : 40;
                    if (minHeight < 90) {
                        viewsToApply = build1RowViews(context, captureEnabled, notifCount, toastCount, hasWorkProfile, profileMode);
                    } else {
                        viewsToApply = build2RowViews(context, captureEnabled, notifCount, toastCount, hasWorkProfile, profileMode);
                    }
                }

                manager.updateAppWidget(id, viewsToApply);
            }
        });
    }

    private static RemoteViews build1RowViews(Context context, boolean captureEnabled, int notifCount, int toastCount, boolean hasWorkProfile, int profileMode) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_dashboard_1row);

        views.setTextViewText(R.id.tv_dashboard_notif_count, String.valueOf(notifCount));
        views.setTextViewText(R.id.tv_dashboard_toast_count, String.valueOf(toastCount));

        // 1. History or Profile Switch Action
        if (hasWorkProfile) {
            views.setImageViewResource(R.id.btn_action_history, R.drawable.ic_profile_switch);
            views.setContentDescription(R.id.btn_action_history, context.getString(profileMode == 1 ? R.string.desc_switch_to_personal : R.string.desc_switch_to_work));
            Intent switchIntent = new Intent(context, VaultDashboardWidgetProvider.class);
            switchIntent.setAction(ACTION_SWITCH_PROFILE_DASHBOARD);
            PendingIntent switchPending = PendingIntent.getBroadcast(
                    context, 301, switchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.btn_action_history, switchPending);
        } else {
            views.setImageViewResource(R.id.btn_action_history, R.drawable.ic_history);
            views.setContentDescription(R.id.btn_action_history, context.getString(R.string.nav_history));
            Intent historyIntent = new Intent(context, MainActivity.class);
            historyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent historyPending = PendingIntent.getActivity(
                    context, 301, historyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.btn_action_history, historyPending);
        }

        // Tapping the notification tile opens MainActivity
        Intent notifIntent = new Intent(context, MainActivity.class);
        notifIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent notifPending = PendingIntent.getActivity(
                context, 310, notifIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.tile_dashboard_notifications, notifPending);

        // 2. Favorites Action
        Intent favIntent = new Intent(context, MainActivity.class);
        favIntent.setAction(Intent.ACTION_VIEW);
        favIntent.putExtra("shortcut_action", "open_favorites");
        favIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent favPending = PendingIntent.getActivity(
                context, 302, favIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_action_favorites, favPending);

        // 3. Toasts Action
        Intent toastIntent = new Intent(context, ToastHistoryActivity.class);
        toastIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent toastPending = PendingIntent.getActivity(
                context, 303, toastIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_action_toasts, toastPending);
        views.setOnClickPendingIntent(R.id.tile_dashboard_toasts, toastPending);

        // 4. Toggle Action (Auth protected)
        views.setImageViewResource(R.id.btn_action_toggle, captureEnabled ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        Intent toggleIntent = new Intent(context, com.zygisk_enc.notivault.util.AuthActionActivity.class);
        toggleIntent.putExtra(com.zygisk_enc.notivault.util.AuthActionActivity.EXTRA_ACTION,
                com.zygisk_enc.notivault.util.AuthActionActivity.ACTION_TOGGLE_CAPTURE);
        toggleIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent togglePending = PendingIntent.getActivity(
                context, 304, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_action_toggle, togglePending);

        return views;
    }

    private static RemoteViews build2RowViews(Context context, boolean captureEnabled, int notifCount, int toastCount, boolean hasWorkProfile, int profileMode) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_vault_dashboard);

        // Title
        views.setTextViewText(R.id.tv_dashboard_title, context.getString(R.string.widget_vault_title));

        // Work profile hint badge in top right
        views.setViewVisibility(R.id.tv_dashboard_work_badge, (hasWorkProfile && profileMode == 1) ? android.view.View.VISIBLE : android.view.View.GONE);

        // Status Pill
        views.setTextViewText(R.id.tv_dashboard_status_text, context.getString(captureEnabled ? R.string.widget_status_active : R.string.widget_status_paused));
        views.setInt(R.id.iv_dashboard_status_dot, "setColorFilter", captureEnabled ? 0xFF4CAF50 : 0xFFFF9800);

        // Stats
        views.setTextViewText(R.id.tv_dashboard_notif_count, String.valueOf(notifCount));
        views.setTextViewText(R.id.tv_dashboard_toast_count, String.valueOf(toastCount));

        // 1. History or Profile Switch Action
        if (hasWorkProfile) {
            views.setImageViewResource(R.id.btn_action_history, R.drawable.ic_profile_switch);
            views.setContentDescription(R.id.btn_action_history, context.getString(profileMode == 1 ? R.string.desc_switch_to_personal : R.string.desc_switch_to_work));
            Intent switchIntent = new Intent(context, VaultDashboardWidgetProvider.class);
            switchIntent.setAction(ACTION_SWITCH_PROFILE_DASHBOARD);
            PendingIntent switchPending = PendingIntent.getBroadcast(
                    context, 305, switchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.btn_action_history, switchPending);
        } else {
            views.setImageViewResource(R.id.btn_action_history, R.drawable.ic_history);
            views.setContentDescription(R.id.btn_action_history, context.getString(R.string.nav_history));
            Intent historyIntent = new Intent(context, MainActivity.class);
            historyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent historyPending = PendingIntent.getActivity(
                    context, 305, historyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.btn_action_history, historyPending);
        }

        // Tapping the notification tile opens MainActivity
        Intent notifIntent = new Intent(context, MainActivity.class);
        notifIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent notifPending = PendingIntent.getActivity(
                context, 311, notifIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.tile_dashboard_notifications, notifPending);

        // 2. Favorites Action
        Intent favIntent = new Intent(context, MainActivity.class);
        favIntent.setAction(Intent.ACTION_VIEW);
        favIntent.putExtra("shortcut_action", "open_favorites");
        favIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent favPending = PendingIntent.getActivity(
                context, 306, favIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_action_favorites, favPending);

        // 3. Toasts Action
        Intent toastIntent = new Intent(context, ToastHistoryActivity.class);
        toastIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent toastPending = PendingIntent.getActivity(
                context, 307, toastIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_action_toasts, toastPending);
        views.setOnClickPendingIntent(R.id.tile_dashboard_toasts, toastPending);

        // 4. Toggle Action (Auth protected)
        views.setImageViewResource(R.id.btn_action_toggle, captureEnabled ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        Intent toggleIntent2 = new Intent(context, com.zygisk_enc.notivault.util.AuthActionActivity.class);
        toggleIntent2.putExtra(com.zygisk_enc.notivault.util.AuthActionActivity.EXTRA_ACTION,
                com.zygisk_enc.notivault.util.AuthActionActivity.ACTION_TOGGLE_CAPTURE);
        toggleIntent2.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent togglePending2 = PendingIntent.getActivity(
                context, 308, toggleIntent2, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_action_toggle, togglePending2);
        views.setOnClickPendingIntent(R.id.layout_dashboard_status_pill, togglePending2);

        return views;
    }
}
