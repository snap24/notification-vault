package com.zygisk_enc.notivault.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.util.AppExecutor;
import com.zygisk_enc.notivault.util.AppLockManager;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import java.util.ArrayList;
import java.util.List;

public class WidgetFilterActivity extends AppCompatActivity {

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private RecyclerView rvApps;
    private AppFilterAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.zygisk_enc.notivault.util.ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_widget_filter);

        appWidgetId = getIntent().getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
        );

        MaterialToolbar toolbar = findViewById(R.id.toolbar_filter);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvApps = findViewById(R.id.rv_filter_apps);
        rvApps.setLayoutManager(new LinearLayoutManager(this));

        loadApps();
    }

    private void loadApps() {
        String currentPkg = PreferenceUtil.getWidgetFeedPackage(this, appWidgetId);

        AppExecutor.execute(() -> {
            List<AppSummary> summaries = AppDatabase.getInstance(this)
                    .notificationDao()
                    .getAppSummariesSync();

            PackageManager pm = getPackageManager();
            List<FilterItem> items = new ArrayList<>();

            // 1. Global "All Notifications" option
            items.add(new FilterItem(
                    "ALL",
                    getString(R.string.widget_all_notifications),
                    getString(R.string.widget_all_notifications_desc),
                    getDrawable(R.drawable.ic_notification),
                    currentPkg == null || "ALL".equals(currentPkg)
            ));

            // 2. Individual Apps
            for (AppSummary summary : summaries) {
                if (summary.packageName == null || summary.packageName.isEmpty()) continue;
                String appName = summary.appName;
                Drawable icon = null;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(summary.packageName, 0);
                    if (appName == null || appName.isEmpty()) {
                        appName = pm.getApplicationLabel(info).toString();
                    }
                    icon = pm.getApplicationIcon(info);
                } catch (Exception ignored) {
                    if (appName == null || appName.isEmpty()) {
                        appName = summary.packageName;
                    }
                    icon = getDrawable(R.mipmap.ic_launcher);
                }

                String countStr = summary.count == 1
                        ? getString(R.string.app_notification_count_singular, summary.count)
                        : getString(R.string.app_notification_count_plural, summary.count);
                boolean isSelected = summary.packageName.equals(currentPkg);

                items.add(new FilterItem(
                        summary.packageName,
                        appName,
                        countStr,
                        icon,
                        isSelected
                ));
            }

            runOnUiThread(() -> {
                adapter = new AppFilterAdapter(items, item -> {
                    PreferenceUtil.setWidgetFeedPackage(this, appWidgetId, item.packageName);
                    
                    AppWidgetManager manager = AppWidgetManager.getInstance(this);
                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lv_widget_notifications);
                    }
                    WidgetHelper.updateAllWidgets(this);

                    if ("ALL".equals(item.packageName)) {
                        Toast.makeText(this, R.string.toast_widget_showing_all, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, getString(R.string.toast_widget_filtered_to, item.appName), Toast.LENGTH_SHORT).show();
                    }
                    finish();
                });
                rvApps.setAdapter(adapter);
            });
        });
    }

    private static class FilterItem {
        final String packageName;
        final String appName;
        final String subtitle;
        final Drawable icon;
        final boolean isSelected;

        FilterItem(String packageName, String appName, String subtitle, Drawable icon, boolean isSelected) {
            this.packageName = packageName;
            this.appName = appName;
            this.subtitle = subtitle;
            this.icon = icon;
            this.isSelected = isSelected;
        }
    }

    private static class AppFilterAdapter extends RecyclerView.Adapter<AppFilterAdapter.ViewHolder> {
        private final List<FilterItem> items;
        private final OnItemClickListener listener;

        interface OnItemClickListener {
            void onItemClick(FilterItem item);
        }

        AppFilterAdapter(List<FilterItem> items, OnItemClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_widget_filter_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FilterItem item = items.get(position);
            holder.tvAppName.setText(item.appName);
            holder.tvAppCount.setText(item.subtitle);
            if (item.icon != null) {
                holder.ivAppIcon.setImageDrawable(item.icon);
            }
            holder.ivCheck.setVisibility(item.isSelected ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivAppIcon;
            final TextView tvAppName;
            final TextView tvAppCount;
            final ImageView ivCheck;

            ViewHolder(View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                tvAppCount = itemView.findViewById(R.id.tv_app_count);
                ivCheck = itemView.findViewById(R.id.iv_selected_check);
            }
        }
    }
}
