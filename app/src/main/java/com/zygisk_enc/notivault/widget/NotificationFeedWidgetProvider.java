package com.zygisk_enc.notivault.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.widget.Toast;
import com.zygisk_enc.notivault.MainActivity;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.ProfileUtil;

public class NotificationFeedWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH_FEED = "com.zygisk_enc.notivault.widget.ACTION_REFRESH_FEED";
    public static final String ACTION_SWITCH_PROFILE_FEED = "com.zygisk_enc.notivault.widget.ACTION_SWITCH_PROFILE_FEED";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateWidget(context, appWidgetManager, appWidgetId);
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_notification_feed);

        // Profile Switch & Work Badge
        boolean hasWorkProfile = ProfileUtil.hasWorkProfile(context);
        int profileMode = hasWorkProfile ? PreferenceUtil.getActiveProfileMode(context) : -1;

        if (hasWorkProfile) {
            views.setViewVisibility(R.id.btn_feed_switch_profile, android.view.View.VISIBLE);
            views.setImageViewResource(R.id.btn_feed_switch_profile, R.drawable.ic_profile_switch);
            views.setContentDescription(R.id.btn_feed_switch_profile, context.getString(profileMode == 1 ? R.string.desc_switch_to_personal : R.string.desc_switch_to_work));

            Intent switchIntent = new Intent(context, NotificationFeedWidgetProvider.class);
            switchIntent.setAction(ACTION_SWITCH_PROFILE_FEED);
            PendingIntent switchPending = PendingIntent.getBroadcast(
                    context, 203 + id, switchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.btn_feed_switch_profile, switchPending);

            views.setViewVisibility(R.id.tv_feed_work_badge, profileMode == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        } else {
            views.setViewVisibility(R.id.btn_feed_switch_profile, android.view.View.GONE);
            views.setViewVisibility(R.id.tv_feed_work_badge, android.view.View.GONE);
        }

        // Bind RemoteViewsService adapter
        Intent serviceIntent = new Intent(context, NotificationFeedService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.lv_widget_notifications, serviceIntent);
        views.setEmptyView(R.id.lv_widget_notifications, R.id.tv_widget_feed_empty);

        // Check dynamic title based on selected app filter
        String filterPkg = PreferenceUtil.getWidgetFeedPackage(context, id, profileMode);
        if (filterPkg != null && !"ALL".equals(filterPkg)) {
            String appTitle = filterPkg;
            try {
                PackageManager pm = context.getPackageManager();
                appTitle = pm.getApplicationLabel(pm.getApplicationInfo(filterPkg, 0)).toString();
            } catch (Exception ignored) {}
            views.setTextViewText(R.id.tv_feed_title, appTitle);
        } else {
            views.setTextViewText(R.id.tv_feed_title, context.getString(R.string.widget_recent_notifications_title));
        }

        // Item click pending intent template
        Intent itemClickIntent = new Intent(context, MainActivity.class);
        itemClickIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent itemPending = PendingIntent.getActivity(
                context, 200, itemClickIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.lv_widget_notifications, itemPending);

        // Refresh button
        Intent refreshIntent = new Intent(context, NotificationFeedWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH_FEED);
        PendingIntent refreshPending = PendingIntent.getBroadcast(
                context, 201, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_feed_refresh, refreshPending);

        // App Filter Selection Button (Bell icon)
        Intent filterIntent = new Intent(context, WidgetFilterActivity.class);
        filterIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        filterIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent filterPending = PendingIntent.getActivity(
                context, 202 + id, filterIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.btn_feed_open_app, filterPending);

        appWidgetManager.updateAppWidget(id, views);
        appWidgetManager.notifyAppWidgetViewDataChanged(id, R.id.lv_widget_notifications);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH_FEED.equals(intent.getAction())) {
            WidgetHelper.updateAllWidgets(context);
            resetScrollToTop(context);
            Toast.makeText(context, R.string.toast_feed_refreshed, Toast.LENGTH_SHORT).show();
        } else if (ACTION_SWITCH_PROFILE_FEED.equals(intent.getAction())) {
            if (ProfileUtil.hasWorkProfile(context)) {
                int current = PreferenceUtil.getActiveProfileMode(context);
                int next = (current == 0) ? 1 : 0;
                PreferenceUtil.setActiveProfileMode(context, next);
                Toast.makeText(context, next == 1 ? R.string.badge_work : R.string.badge_personal, Toast.LENGTH_SHORT).show();
                WidgetHelper.updateAllWidgets(context);
                resetScrollToTop(context);
            }
        }
    }

    private static void resetScrollToTop(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            android.content.ComponentName feedWidget = new android.content.ComponentName(context, NotificationFeedWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(feedWidget);
            if (ids != null && ids.length > 0) {
                for (int id : ids) {
                    RemoteViews resetViews = new RemoteViews(context.getPackageName(), R.layout.widget_notification_feed);
                    Intent serviceIntent = new Intent(context, NotificationFeedService.class);
                    serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME) + "?reset=" + System.currentTimeMillis()));
                    resetViews.setRemoteAdapter(R.id.lv_widget_notifications, serviceIntent);
                    manager.partiallyUpdateAppWidget(id, resetViews);
                    manager.notifyAppWidgetViewDataChanged(id, R.id.lv_widget_notifications);
                }
            }
        } catch (Exception ignored) {}
    }
}
