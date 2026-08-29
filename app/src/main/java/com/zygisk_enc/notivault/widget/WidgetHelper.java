package com.zygisk_enc.notivault.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import com.zygisk_enc.notivault.R;
import java.util.Calendar;

public class WidgetHelper {

    public static long getStartOfTodayMillis() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static void updateAllWidgets(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);

            // 1. Update Quick Capture Widgets
            ComponentName captureWidget = new ComponentName(context, CaptureWidgetProvider.class);
            int[] captureIds = manager.getAppWidgetIds(captureWidget);
            if (captureIds != null && captureIds.length > 0) {
                Intent intent = new Intent(context, CaptureWidgetProvider.class);
                intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, captureIds);
                context.sendBroadcast(intent);
            }

            // 2. Update Notification Feed Widgets
            ComponentName feedWidget = new ComponentName(context, NotificationFeedWidgetProvider.class);
            int[] feedIds = manager.getAppWidgetIds(feedWidget);
            if (feedIds != null && feedIds.length > 0) {
                manager.notifyAppWidgetViewDataChanged(feedIds, R.id.lv_widget_notifications);
                Intent intent = new Intent(context, NotificationFeedWidgetProvider.class);
                intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, feedIds);
                context.sendBroadcast(intent);
            }

            // 3. Update Vault Dashboard Widgets
            ComponentName dashboardWidget = new ComponentName(context, VaultDashboardWidgetProvider.class);
            int[] dashboardIds = manager.getAppWidgetIds(dashboardWidget);
            if (dashboardIds != null && dashboardIds.length > 0) {
                Intent intent = new Intent(context, VaultDashboardWidgetProvider.class);
                intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, dashboardIds);
                context.sendBroadcast(intent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
