package com.zygisk_enc.notivault.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.util.DateUtils;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.ProfileUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NotificationFeedService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
        );
        return new NotificationFeedViewsFactory(getApplicationContext(), appWidgetId);
    }

    static class NotificationFeedViewsFactory implements RemoteViewsFactory {

        private final Context context;
        private final int appWidgetId;
        private final List<FeedItem> feedItems = new ArrayList<>();
        private final Map<String, Bitmap> iconCache = new HashMap<>();
        private final Map<Long, String[]> decryptedCache = new HashMap<>();
        private volatile int loadVersion = 0;

        NotificationFeedViewsFactory(Context context, int appWidgetId) {
            this.context = context;
            this.appWidgetId = appWidgetId;
        }

        @Override
        public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            final int currentVersion = ++loadVersion;
            try {
                boolean hasWorkProfile = ProfileUtil.hasWorkProfile(context);
                int profileMode = hasWorkProfile ? PreferenceUtil.getActiveProfileMode(context) : -1;
                String filterPkg = PreferenceUtil.getWidgetFeedPackage(context, appWidgetId, profileMode);

                List<NotificationEntity> rawList;
                if (filterPkg != null && !"ALL".equals(filterPkg)) {
                    rawList = AppDatabase.getInstance(context)
                            .notificationDao()
                            .getRecentNotificationsByPackageSync(filterPkg, 100, profileMode);
                } else {
                    rawList = AppDatabase.getInstance(context)
                            .notificationDao()
                            .getRecentNotificationsSync(100, profileMode);
                }

                if (currentVersion != loadVersion) {
                    return; // Abort immediately if a newer profile switch request arrived
                }

                List<FeedItem> newItems = new ArrayList<>();
                if (rawList != null) {
                    for (NotificationEntity entity : rawList) {
                        if (currentVersion != loadVersion) {
                            return; // Fast cancellation abort loop
                        }

                        String title;
                        String text;
                        String[] cached = decryptedCache.get(entity.id);
                        if (cached != null) {
                            title = cached[0];
                            text = cached[1];
                        } else {
                            title = EncryptionHelper.decrypt(entity.title);
                            text = EncryptionHelper.decrypt(entity.text);
                            if (title == null || title.trim().isEmpty()) {
                                title = context.getString(R.string.no_title);
                            }
                            if (text == null) text = "";
                            decryptedCache.put(entity.id, new String[]{title, text});
                        }

                        String timeStr = DateUtils.getTimeString(context, entity.timestamp);
                        String appName = ProfileUtil.getAppLabel(context, entity.packageName, entity.userId, entity.appName);

                        newItems.add(new FeedItem(
                                entity.id,
                                entity.packageName,
                                appName,
                                timeStr,
                                title,
                                text,
                                entity.userId
                        ));
                    }
                }

                if (currentVersion == loadVersion) {
                    feedItems.clear();
                    feedItems.addAll(newItems);
                }
            } catch (Exception e) {
                if (currentVersion == loadVersion) {
                    feedItems.clear();
                }
            }
        }

        @Override
        public void onDestroy() {
            feedItems.clear();
            iconCache.clear();
            decryptedCache.clear();
        }

        @Override
        public int getCount() {
            return feedItems.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            try {
                if (position < 0 || position >= feedItems.size()) {
                    return null;
                }

                FeedItem item = feedItems.get(position);
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.item_widget_notification);

                // App Name & Time
                views.setTextViewText(R.id.tv_item_app_name, item.appName);
                views.setTextViewText(R.id.tv_item_time, item.timeStr);

                // Pre-decrypted Title & Text
                views.setTextViewText(R.id.tv_item_title, item.title);
                views.setTextViewText(R.id.tv_item_text, item.text);

                // Fast Cached App Icon
                Bitmap iconBitmap = getCachedAppIconBitmap(item.packageName, item.userId);
                if (iconBitmap != null) {
                    views.setImageViewBitmap(R.id.iv_item_app_icon, iconBitmap);
                } else {
                    views.setImageViewResource(R.id.iv_item_app_icon, R.mipmap.ic_launcher);
                }

                // Fill-in Intent for item click
                Intent fillInIntent = new Intent();
                fillInIntent.putExtra("notification_id", item.id);
                fillInIntent.putExtra("package_name", item.packageName);
                views.setOnClickFillInIntent(R.id.item_widget_root, fillInIntent);

                return views;
            } catch (Exception e) {
                return null;
            }
        }

        private Bitmap getCachedAppIconBitmap(String packageName, int userId) {
            if (packageName == null) return null;
            String key = packageName + "_" + userId;
            if (iconCache.containsKey(key)) {
                return iconCache.get(key);
            }

            try {
                Drawable drawable = ProfileUtil.getBadgedAppIcon(context, packageName, userId);
                if (drawable == null) {
                    PackageManager pm = context.getPackageManager();
                    drawable = pm.getApplicationIcon(packageName);
                }

                int size = 64;
                Bitmap bitmap;
                if (drawable instanceof BitmapDrawable) {
                    Bitmap raw = ((BitmapDrawable) drawable).getBitmap();
                    if (raw != null && raw.getWidth() == size && raw.getHeight() == size) {
                        bitmap = raw;
                    } else {
                        bitmap = Bitmap.createScaledBitmap(raw, size, size, true);
                    }
                } else {
                    bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    drawable.setBounds(0, 0, size, size);
                    drawable.draw(canvas);
                }

                iconCache.put(key, bitmap);
                return bitmap;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public RemoteViews getLoadingView() {
            return new RemoteViews(context.getPackageName(), R.layout.item_widget_notification);
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            if (position >= 0 && position < feedItems.size()) {
                return feedItems.get(position).id;
            }
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }

    private static class FeedItem {
        final long id;
        final String packageName;
        final String appName;
        final String timeStr;
        final String title;
        final String text;
        final int userId;

        FeedItem(long id, String packageName, String appName, String timeStr, String title, String text, int userId) {
            this.id = id;
            this.packageName = packageName;
            this.appName = appName;
            this.timeStr = timeStr;
            this.title = title;
            this.text = text;
            this.userId = userId;
        }
    }
}
