package com.zygisk_enc.notivault.fragment;

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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.databinding.FragmentStatsBinding;
import com.zygisk_enc.notivault.util.AppExecutor;
import com.zygisk_enc.notivault.view.AnalyticsDistributionChartView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StatsFragment extends Fragment {

    private enum Period {
        TODAY, YESTERDAY, DAYS_7, DAYS_30, ALL
    }

    private FragmentStatsBinding binding;
    private Period currentPeriod = Period.TODAY;
    private boolean isShowAllApps = false;
    private AnalyticsData lastCalculatedData = null;

    private static class AnalyticsData {
        int totalCount;
        int favoritesCount;
        int toastsCount;
        String velocityRate;
        String peakHourString;
        int morning;
        int afternoon;
        int evening;
        int night;
        List<AnalyticsDistributionChartView.BarItem> chartBars;
        List<AppSummary> topApps;
        String insight;
        String chartSubtitle;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentStatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    private long lastAnalyticsLoadTimestamp = 0L;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupPeriodChips();
        setupChartInspector();
        setupMoreAppsButton();

        loadAnalytics();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (System.currentTimeMillis() - lastAnalyticsLoadTimestamp > 3000L) {
            loadAnalytics();
        }
    }

    private static final String PREF_STATS_PERIOD = "stats_selected_period";
    private static final String PREF_STATS_SHOW_ALL = "stats_show_all_apps";

    private void setupPeriodChips() {
        if (getContext() != null) {
            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
            String savedPeriodName = prefs.getString(PREF_STATS_PERIOD, Period.TODAY.name());
            try {
                currentPeriod = Period.valueOf(savedPeriodName);
            } catch (Exception e) {
                currentPeriod = Period.TODAY;
            }
        }

        int targetChipId;
        switch (currentPeriod) {
            case YESTERDAY:
                targetChipId = R.id.chip_period_yesterday;
                binding.tvHeroPeriodLabel.setText(R.string.stats_logged_yesterday);
                break;
            case DAYS_7:
                targetChipId = R.id.chip_period_7days;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_last_7_days);
                break;
            case DAYS_30:
                targetChipId = R.id.chip_period_30days;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_last_30_days);
                break;
            case ALL:
                targetChipId = R.id.chip_period_all;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_all_time);
                break;
            case TODAY:
            default:
                targetChipId = R.id.chip_period_today;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_today);
                break;
        }

        binding.chipGroupPeriods.check(targetChipId);

        binding.chipGroupPeriods.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);

            if (checkedId == R.id.chip_period_today) {
                currentPeriod = Period.TODAY;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_today);
            } else if (checkedId == R.id.chip_period_yesterday) {
                currentPeriod = Period.YESTERDAY;
                binding.tvHeroPeriodLabel.setText(R.string.stats_logged_yesterday);
            } else if (checkedId == R.id.chip_period_7days) {
                currentPeriod = Period.DAYS_7;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_last_7_days);
            } else if (checkedId == R.id.chip_period_30days) {
                currentPeriod = Period.DAYS_30;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_last_30_days);
            } else if (checkedId == R.id.chip_period_all) {
                currentPeriod = Period.ALL;
                binding.tvHeroPeriodLabel.setText(R.string.stats_captured_all_time);
            }

            if (getContext() != null) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString(PREF_STATS_PERIOD, currentPeriod.name())
                        .apply();
            }

            loadAnalytics();
        });
    }

    private void setupChartInspector() {
        binding.chartDistribution.setOnBarSelectedListener((index, item) -> {
            if (item != null && binding != null) {
                binding.tvChartInspector.setText(item.label + " • " + item.count + " notifications");
            }
        });
    }

    private void setupMoreAppsButton() {
        if (getContext() != null) {
            isShowAllApps = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean(PREF_STATS_SHOW_ALL, false);
        }
        binding.btnToggleMoreApps.setText(isShowAllApps ? "Show Top 5 Only" : "Show All Apps");

        binding.btnToggleMoreApps.setOnClickListener(v -> {
            isShowAllApps = !isShowAllApps;
            if (getContext() != null) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putBoolean(PREF_STATS_SHOW_ALL, isShowAllApps)
                        .apply();
            }
            binding.btnToggleMoreApps.setText(isShowAllApps ? "Show Top 5 Only" : "Show All Apps");
            if (lastCalculatedData != null) {
                renderTopApps(lastCalculatedData.topApps, lastCalculatedData.totalCount);
            }
        });
    }

    private void loadAnalytics() {
        Context context = getContext();
        if (context == null) return;
        lastAnalyticsLoadTimestamp = System.currentTimeMillis();

        AppExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            long startTime;
            long endTime = now;

            Calendar cal = Calendar.getInstance();
            switch (currentPeriod) {
                case TODAY:
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    startTime = cal.getTimeInMillis();
                    break;
                case YESTERDAY:
                    cal.add(Calendar.DAY_OF_YEAR, -1);
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    startTime = cal.getTimeInMillis();

                    cal.set(Calendar.HOUR_OF_DAY, 23);
                    cal.set(Calendar.MINUTE, 59);
                    cal.set(Calendar.SECOND, 59);
                    endTime = cal.getTimeInMillis();
                    break;
                case DAYS_7:
                    cal.add(Calendar.DAY_OF_YEAR, -7);
                    startTime = cal.getTimeInMillis();
                    break;
                case DAYS_30:
                    cal.add(Calendar.DAY_OF_YEAR, -30);
                    startTime = cal.getTimeInMillis();
                    break;
                case ALL:
                default:
                    startTime = 0L;
                    break;
            }

            AppDatabase db = AppDatabase.getInstance(context);
            int total = db.notificationDao().getCountBetweenSync(startTime, endTime);
            int favorites = db.notificationDao().getFavoritesCountBetweenSync(startTime, endTime);
            int toasts = db.toastDao().getToastCountBetweenSync(startTime, endTime);
            List<AppSummary> topApps = db.notificationDao().getTopAppsBetweenSync(startTime, endTime, 100);
            List<Long> timestamps = db.notificationDao().getTimestampsBetweenSync(startTime, endTime);

            AnalyticsData data = computeAnalytics(context, currentPeriod, startTime, endTime, total, favorites, toasts, topApps, timestamps);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (binding == null) return;
                    lastCalculatedData = data;
                    renderUI(data);
                });
            }
        });
    }

    private AnalyticsData computeAnalytics(Context context, Period period, long startTime, long endTime,
                                           int total, int favorites, int toasts,
                                           List<AppSummary> topApps, List<Long> timestamps) {
        AnalyticsData data = new AnalyticsData();
        data.totalCount = total;
        data.favoritesCount = favorites;
        data.toastsCount = toasts;
        data.topApps = topApps != null ? topApps : new ArrayList<>();

        // 1. Calculate Velocity Rate
        long durationMs = Math.max(1000L, endTime - startTime);
        float durationHours = durationMs / (1000f * 60f * 60f);
        float durationDays = durationHours / 24f;

        if (period == Period.TODAY || period == Period.YESTERDAY) {
            float ratePerHour = durationHours > 0 ? (total / Math.max(1f, durationHours)) : 0f;
            data.velocityRate = String.format(Locale.getDefault(), "%.1f / hr", ratePerHour);
        } else {
            float ratePerDay = durationDays > 0 ? (total / Math.max(1f, durationDays)) : 0f;
            data.velocityRate = String.format(Locale.getDefault(), "%.1f / day", ratePerDay);
        }

        // 2. Daypart Breakdown & Hourly distribution
        int[] hourBuckets = new int[24];
        int morning = 0;
        int afternoon = 0;
        int evening = 0;
        int night = 0;

        Calendar c = Calendar.getInstance();
        for (Long ts : timestamps) {
            if (ts == null) continue;
            c.setTimeInMillis(ts);
            int hour = c.get(Calendar.HOUR_OF_DAY);
            if (hour >= 0 && hour < 24) {
                hourBuckets[hour]++;
            }

            if (hour >= 6 && hour < 12) morning++;
            else if (hour >= 12 && hour < 18) afternoon++;
            else if (hour >= 18 && hour < 24) evening++;
            else night++;
        }

        data.morning = morning;
        data.afternoon = afternoon;
        data.evening = evening;
        data.night = night;

        // 3. Peak Hour calculation
        int maxHourCount = 0;
        int peakHour = -1;
        for (int h = 0; h < 24; h++) {
            if (hourBuckets[h] > maxHourCount) {
                maxHourCount = hourBuckets[h];
                peakHour = h;
            }
        }

        if (peakHour >= 0 && maxHourCount > 0) {
            int displayHour = peakHour % 12 == 0 ? 12 : peakHour % 12;
            String ampm = peakHour < 12 ? "AM" : "PM";
            data.peakHourString = displayHour + ":00 " + ampm;
        } else {
            data.peakHourString = "None";
        }

        // 4. Chart Bar Items
        List<AnalyticsDistributionChartView.BarItem> barItems = new ArrayList<>();
        if (period == Period.TODAY || period == Period.YESTERDAY) {
            data.chartSubtitle = "24-Hour Distribution (Tap bar to inspect)";
            for (int h = 0; h < 24; h++) {
                int displayH = h % 12 == 0 ? 12 : h % 12;
                String ampm = h < 12 ? "a" : "p";
                String label = displayH + ampm;
                boolean isPeak = (h == peakHour) && (maxHourCount > 0);
                barItems.add(new AnalyticsDistributionChartView.BarItem(label, hourBuckets[h], isPeak));
            }
        } else if (period == Period.DAYS_7) {
            data.chartSubtitle = "Daily Volume (Last 7 Days)";
            int[] dayCounts = new int[7];
            String[] dayLabels = new String[7];
            Calendar dayCal = Calendar.getInstance();
            SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.getDefault());

            for (int d = 6; d >= 0; d--) {
                Calendar check = Calendar.getInstance();
                check.add(Calendar.DAY_OF_YEAR, -d);
                dayLabels[6 - d] = dayFormat.format(check.getTime());
            }

            for (Long ts : timestamps) {
                if (ts == null) continue;
                long diffDays = (endTime - ts) / (24L * 60L * 60L * 1000L);
                if (diffDays >= 0 && diffDays < 7) {
                    dayCounts[6 - (int) diffDays]++;
                }
            }

            int peakDayIdx = -1;
            int maxDayCount = 0;
            for (int i = 0; i < 7; i++) {
                if (dayCounts[i] > maxDayCount) {
                    maxDayCount = dayCounts[i];
                    peakDayIdx = i;
                }
            }

            for (int i = 0; i < 7; i++) {
                barItems.add(new AnalyticsDistributionChartView.BarItem(dayLabels[i], dayCounts[i], i == peakDayIdx));
            }
        } else {
            data.chartSubtitle = "Hourly Aggregate Profile";
            for (int h = 0; h < 24; h++) {
                int displayH = h % 12 == 0 ? 12 : h % 12;
                String ampm = h < 12 ? "a" : "p";
                String label = displayH + ampm;
                boolean isPeak = (h == peakHour) && (maxHourCount > 0);
                barItems.add(new AnalyticsDistributionChartView.BarItem(label, hourBuckets[h], isPeak));
            }
        }
        data.chartBars = barItems;

        // 5. Smart Insight Generator
        if (total == 0) {
            data.insight = "No notifications captured during this time frame.";
        } else if (!data.topApps.isEmpty() && data.topApps.get(0).count > (total * 0.40)) {
            AppSummary loudest = data.topApps.get(0);
            String name = loudest.appName != null ? loudest.appName : loudest.packageName;
            int percent = (int) (((float) loudest.count / total) * 100);
            data.insight = name + " is your loudest app, generating " + percent + "% of all distraction alerts.";
        } else if (night > 0 && night > (total * 0.35)) {
            data.insight = "High nighttime disturbance (" + night + " alerts during sleep hours). Consider Quiet Hours or Notification Rules.";
        } else if (night == 0) {
            data.insight = "Zero disturbance during sleep hours (00:00–06:00). Excellent digital hygiene!";
        } else if (data.peakHourString != null && !data.peakHourString.equals("None")) {
            data.insight = "Your peak notification spike happens around " + data.peakHourString + ".";
        } else {
            data.insight = "Balanced notification flow throughout your active day.";
        }

        return data;
    }

    private void renderUI(AnalyticsData data) {
        if (data == null || binding == null) return;

        // Hero Metric Card
        binding.tvTotalCount.setText(String.valueOf(data.totalCount));
        binding.tvMetricRate.setText(data.velocityRate);
        binding.tvMetricPeak.setText(data.peakHourString);
        binding.tvMetricToasts.setText(String.valueOf(data.toastsCount));
        binding.tvMetricFavorites.setText(String.valueOf(data.favoritesCount));

        // Chart
        binding.tvChartSubtitle.setText(data.chartSubtitle);
        binding.tvChartInspector.setText(R.string.stats_tap_bar_to_inspect);
        binding.chartDistribution.setData(data.chartBars);

        // Time of Day Daypart Breakdown
        int maxDaypart = Math.max(data.morning, Math.max(data.afternoon, Math.max(data.evening, data.night)));
        binding.tvCountMorning.setText(String.valueOf(data.morning));
        binding.tvCountAfternoon.setText(String.valueOf(data.afternoon));
        binding.tvCountEvening.setText(String.valueOf(data.evening));
        binding.tvCountNight.setText(String.valueOf(data.night));

        binding.progressMorning.setProgress(maxDaypart > 0 ? (int) (((float) data.morning / maxDaypart) * 100) : 0);
        binding.progressAfternoon.setProgress(maxDaypart > 0 ? (int) (((float) data.afternoon / maxDaypart) * 100) : 0);
        binding.progressEvening.setProgress(maxDaypart > 0 ? (int) (((float) data.evening / maxDaypart) * 100) : 0);
        binding.progressNight.setProgress(maxDaypart > 0 ? (int) (((float) data.night / maxDaypart) * 100) : 0);

        // Top Loudest Apps Leaderboard
        renderTopApps(data.topApps, data.totalCount);

        // Smart Insights
        binding.tvInsightText.setText(data.insight);
    }

    private void renderTopApps(List<AppSummary> apps, int totalCount) {
        if (apps == null || apps.isEmpty()) {
            binding.layoutTopAppsContainer.removeAllViews();
            binding.tvNoAppsStats.setVisibility(View.VISIBLE);
            binding.btnToggleMoreApps.setVisibility(View.GONE);
            return;
        }

        binding.tvNoAppsStats.setVisibility(View.GONE);

        int displayLimit = isShowAllApps ? apps.size() : Math.min(5, apps.size());
        if (apps.size() > 5) {
            binding.btnToggleMoreApps.setVisibility(View.VISIBLE);
            binding.btnToggleMoreApps.setText(isShowAllApps ? "Show Top 5 Only" : "Show All (" + apps.size() + " Apps)");
        } else {
            binding.btnToggleMoreApps.setVisibility(View.GONE);
        }

        int maxAppCount = apps.get(0).count;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        PackageManager pm = requireContext().getPackageManager();
        int existingChildCount = binding.layoutTopAppsContainer.getChildCount();

        for (int i = 0; i < displayLimit; i++) {
            AppSummary app = apps.get(i);
            View row;
            if (i < existingChildCount) {
                row = binding.layoutTopAppsContainer.getChildAt(i);
                row.setVisibility(View.VISIBLE);
            } else {
                row = inflater.inflate(R.layout.item_app_stat_row, binding.layoutTopAppsContainer, false);
                binding.layoutTopAppsContainer.addView(row);
            }

            TextView tvRank = row.findViewById(R.id.tv_app_rank);
            ImageView ivIcon = row.findViewById(R.id.iv_app_icon);
            TextView tvName = row.findViewById(R.id.tv_app_name);
            TextView tvCount = row.findViewById(R.id.tv_app_count);
            TextView tvPercentage = row.findViewById(R.id.tv_app_percentage);
            LinearProgressIndicator progress = row.findViewById(R.id.progress_percentage);

            int rank = i + 1;
            tvRank.setText("#" + rank);

            tvName.setText(app.appName != null ? app.appName : app.packageName);
            tvCount.setText(String.valueOf(app.count));

            int percentOfTotal = totalCount > 0 ? (int) Math.round(((double) app.count / totalCount) * 100.0) : 0;
            tvPercentage.setText(percentOfTotal + "% of total");

            int progressPercent = maxAppCount > 0 ? (int) (((float) app.count / maxAppCount) * 100) : 0;
            progress.setProgress(progressPercent);

            // Icon loading with memory-safe AppIconLoader
            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(requireContext()).loadInto(
                    ivIcon, app.packageName, android.R.drawable.sym_def_app_icon);
        }

        // Hide any surplus views already in the container
        for (int i = displayLimit; i < binding.layoutTopAppsContainer.getChildCount(); i++) {
            binding.layoutTopAppsContainer.getChildAt(i).setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
