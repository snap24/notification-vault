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
import java.util.ArrayList;
import java.util.List;

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
        private List<NotificationEntity> notifications = new ArrayList<>();

        NotificationFeedViewsFactory(Context context, int appWidgetId) {
            this.context = context;
            this.appWidgetId = appWidgetId;
        }

        @Override
        public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            try {
                boolean hasWorkProfile = com.zygisk_enc.notivault.util.ProfileUtil.hasWorkProfile(context);
                int profileMode = hasWorkProfile ? PreferenceUtil.getActiveProfileMode(context) : -1;
                String filterPkg = PreferenceUtil.getWidgetFeedPackage(context, appWidgetId, profileMode);
                if (filterPkg != null && !"ALL".equals(filterPkg)) {
                    notifications = AppDatabase.getInstance(context)
                            .notificationDao()
                            .getRecentNotificationsByPackageSync(filterPkg, 200, profileMode);
                } else {
                    notifications = AppDatabase.getInstance(context)
                            .notificationDao()
                            .getRecentNotificationsSync(200, profileMode);
                }
            } catch (Exception e) {
                notifications = new ArrayList<>();
            }
        }

        @Override
        public void onDestroy() {
            notifications.clear();
        }

        @Override
        public int getCount() {
            return notifications.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            try {
                if (position < 0 || position >= notifications.size()) {
                    return null;
                }

                NotificationEntity entity = notifications.get(position);
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.item_widget_notification);

                // App Name & Time
                views.setTextViewText(R.id.tv_item_app_name, entity.appName != null ? entity.appName : entity.packageName);
                views.setTextViewText(R.id.tv_item_time, DateUtils.getTimeString(context, entity.timestamp));

                // Decrypt Title & Text
                String title = EncryptionHelper.decrypt(entity.title);
                String text = EncryptionHelper.decrypt(entity.text);
                if (title == null || title.isEmpty()) {
                    title = context.getString(R.string.no_title);
                }

                views.setTextViewText(R.id.tv_item_title, title);
                views.setTextViewText(R.id.tv_item_text, text != null ? text : "");

                // App Icon (fixed lightweight thumbnail)
                Bitmap iconBitmap = getAppIconBitmap(context, entity.packageName);
                if (iconBitmap != null) {
                    views.setImageViewBitmap(R.id.iv_item_app_icon, iconBitmap);
                } else {
                    views.setImageViewResource(R.id.iv_item_app_icon, R.mipmap.ic_launcher);
                }

                // Fill-in Intent for item click
                Intent fillInIntent = new Intent();
                fillInIntent.putExtra("notification_id", entity.id);
                fillInIntent.putExtra("package_name", entity.packageName);
                views.setOnClickFillInIntent(R.id.item_widget_root, fillInIntent);

                return views;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        private Bitmap getAppIconBitmap(Context ctx, String packageName) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Drawable drawable = pm.getApplicationIcon(packageName);
                int size = 64; // fixed lightweight 64x64 thumbnail to prevent IPC overflow
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
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
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
