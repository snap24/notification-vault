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
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.service.NotiVaultTileService;
import com.zygisk_enc.notivault.util.AppExecutor;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.ShortcutHelper;
import java.util.HashMap;
import java.util.Map;

public class CaptureWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TOGGLE_CAPTURE_WIDGET = "com.zygisk_enc.notivault.widget.ACTION_TOGGLE_CAPTURE";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateWidgetsAsync(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateWidgetsAsync(context, appWidgetManager, new int[]{appWidgetId});
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TOGGLE_CAPTURE_WIDGET.equals(intent.getAction())) {
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
        }
    }

    private static void updateWidgetsAsync(Context context, AppWidgetManager manager, int[] ids) {
        AppExecutor.execute(() -> {
            boolean captureEnabled = PreferenceUtil.isCaptureEnabled(context);
            long startToday = WidgetHelper.getStartOfTodayMillis();
            int countToday = AppDatabase.getInstance(context).notificationDao().getCountSinceSync(startToday);

            for (int id : ids) {
                RemoteViews viewsToApply;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ Responsive Layout Map
                    Map<SizeF, RemoteViews> viewMapping = new HashMap<>();
                    viewMapping.put(new SizeF(40f, 40f), build1x1Views(context, captureEnabled, countToday));
                    viewMapping.put(new SizeF(100f, 40f), build2x1Views(context, captureEnabled, countToday));
                    viewsToApply = new RemoteViews(viewMapping);
                } else {
                    // Android < 12: inspect options bundle
                    Bundle options = manager.getAppWidgetOptions(id);
                    int minWidth = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 130) : 130;
                    if (minWidth < 100) {
                        viewsToApply = build1x1Views(context, captureEnabled, countToday);
                    } else {
                        viewsToApply = build2x1Views(context, captureEnabled, countToday);
                    }
                }

                manager.updateAppWidget(id, viewsToApply);
            }
        });
    }

    private static RemoteViews build1x1Views(Context context, boolean captureEnabled, int countToday) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_capture_1x1);

        // Update Status text and dot
        views.setTextViewText(R.id.tv_widget_status, captureEnabled ? "Active" : "Paused");
        views.setImageViewResource(R.id.iv_status_dot, R.drawable.ic_circle_status);
        views.setInt(R.id.iv_status_dot, "setColorFilter", captureEnabled ? 0xFF4CAF50 : 0xFFFF9800);

        // Update Count
        views.setTextViewText(R.id.tv_widget_count, String.valueOf(countToday));

        // Update Toggle Button Icon
        views.setImageViewResource(R.id.btn_widget_toggle_capture, captureEnabled ? R.drawable.ic_pause : R.drawable.ic_play_arrow);

        // Set Click Pending Intent for Toggle Button (Auth protected)
        Intent toggleIntent = new Intent(context, com.zygisk_enc.notivault.util.AuthActionActivity.class);
        toggleIntent.putExtra(com.zygisk_enc.notivault.util.AuthActionActivity.EXTRA_ACTION,
                com.zygisk_enc.notivault.util.AuthActionActivity.ACTION_TOGGLE_CAPTURE);
        toggleIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent togglePending = PendingIntent.getActivity(
                context, 101, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_widget_toggle_capture, togglePending);

        // Set Click Pending Intent for Widget Container (opens MainActivity)
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 102, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        return views;
    }

    private static RemoteViews build2x1Views(Context context, boolean captureEnabled, int countToday) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_capture);

        // Update Status text and dot
        views.setTextViewText(R.id.tv_widget_status, captureEnabled ? "Recording Active" : "Recording Paused");
        views.setImageViewResource(R.id.iv_status_dot, R.drawable.ic_circle_status);
        views.setInt(R.id.iv_status_dot, "setColorFilter", captureEnabled ? 0xFF4CAF50 : 0xFFFF9800);

        // Update Count
        views.setTextViewText(R.id.tv_widget_count, countToday + (countToday == 1 ? " notification today" : " notifications today"));

        // Update Toggle Button Icon
        views.setImageViewResource(R.id.btn_widget_toggle_capture, captureEnabled ? R.drawable.ic_pause : R.drawable.ic_play_arrow);

        // Set Click Pending Intent for Toggle Button (Auth protected)
        Intent toggleIntent2 = new Intent(context, com.zygisk_enc.notivault.util.AuthActionActivity.class);
        toggleIntent2.putExtra(com.zygisk_enc.notivault.util.AuthActionActivity.EXTRA_ACTION,
                com.zygisk_enc.notivault.util.AuthActionActivity.ACTION_TOGGLE_CAPTURE);
        toggleIntent2.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent togglePending2 = PendingIntent.getActivity(
                context, 103, toggleIntent2, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_widget_toggle_capture, togglePending2);

        // Set Click Pending Intent for Widget Container (opens MainActivity)
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 104, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        return views;
    }
}
