package com.zygisk_enc.notivault.fragment;

import android.content.Context;
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
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.databinding.FragmentStatsBinding;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import java.util.Calendar;
import java.util.List;

public class StatsFragment extends Fragment {

    private FragmentStatsBinding binding;
    private NotificationViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentStatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(NotificationViewModel.class);

        setupCountStats();
        setupTopApps();
        setupTimeDistribution();
    }

    private Integer currentTodayCount = null;
    private Integer currentTotalSinceYesterday = null;

    private void updateYesterdayCount() {
        if (currentTodayCount != null && currentTotalSinceYesterday != null) {
            int yesterday = Math.max(0, currentTotalSinceYesterday - currentTodayCount);
            binding.tvCountYesterday.setText(String.valueOf(yesterday));
        }
    }

    private void setupCountStats() {
        long todayStart = getStartOfDay(0);
        long yesterdayStart = getStartOfDay(-1);

        // Today's notifications
        viewModel.getCountSince(todayStart).observe(getViewLifecycleOwner(), countToday -> {
            currentTodayCount = countToday != null ? countToday : 0;
            binding.tvCountToday.setText(String.valueOf(currentTodayCount));
            updateYesterdayCount();
        });

        // Yesterday's notifications (count since yesterday minus count since today)
        viewModel.getCountSince(yesterdayStart).observe(getViewLifecycleOwner(), totalSinceYesterday -> {
            currentTotalSinceYesterday = totalSinceYesterday != null ? totalSinceYesterday : 0;
            updateYesterdayCount();
        });
    }

    private void setupTopApps() {
        viewModel.getTopAppsSince(0, 3).observe(getViewLifecycleOwner(), topApps -> {
            binding.layoutTopAppsContainer.removeAllViews();
            if (topApps == null || topApps.isEmpty()) {
                binding.tvNoAppsStats.setVisibility(View.VISIBLE);
                return;
            }
            binding.tvNoAppsStats.setVisibility(View.GONE);

            // Compute total count of top 3 to base progress bar percentage
            int maxCount = 0;
            for (AppSummary app : topApps) {
                if (app.count > maxCount) maxCount = app.count;
            }

            LayoutInflater inflater = LayoutInflater.from(requireContext());
            for (AppSummary app : topApps) {
                View row = inflater.inflate(R.layout.item_app_stat_row, binding.layoutTopAppsContainer, false);
                ImageView ivIcon = row.findViewById(R.id.iv_app_icon);
                TextView tvName = row.findViewById(R.id.tv_app_name);
                TextView tvCount = row.findViewById(R.id.tv_app_count);
                LinearProgressIndicator progress = row.findViewById(R.id.progress_percentage);

                // Icon
                try {
                    PackageManager pm = requireContext().getPackageManager();
                    Drawable icon = pm.getApplicationIcon(app.packageName);
                    ivIcon.setImageDrawable(icon);
                } catch (PackageManager.NameNotFoundException e) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                }

                tvName.setText(app.appName != null ? app.appName : app.packageName);
                tvCount.setText(String.valueOf(app.count));

                // Progress percentage
                int percent = maxCount > 0 ? (int) (((float) app.count / maxCount) * 100) : 0;
                progress.setProgress(percent);

                binding.layoutTopAppsContainer.addView(row);
            }
        });
    }

    private void setupTimeDistribution() {
        // Calculate daily hour stats from last 7 days for wellness analysis
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -7);
        long sevenDaysAgo = cal.getTimeInMillis();

        viewModel.getNotificationsSince(sevenDaysAgo).observe(getViewLifecycleOwner(), notifications -> {
            int morning = 0;
            int afternoon = 0;
            int evening = 0;
            int night = 0;

            if (notifications != null) {
                Calendar checkCal = Calendar.getInstance();
                for (NotificationEntity notif : notifications) {
                    checkCal.setTimeInMillis(notif.timestamp);
                    int hour = checkCal.get(Calendar.HOUR_OF_DAY);

                    if (hour >= 6 && hour < 12) {
                        morning++;
                    } else if (hour >= 12 && hour < 18) {
                        afternoon++;
                    } else if (hour >= 18 && hour < 24) {
                        evening++;
                    } else {
                        night++;
                    }
                }
            }

            // Find maximum count to compute scale percentages
            int maxVal = Math.max(morning, Math.max(afternoon, Math.max(evening, night)));

            binding.tvCountMorning.setText(String.valueOf(morning));
            binding.tvCountAfternoon.setText(String.valueOf(afternoon));
            binding.tvCountEvening.setText(String.valueOf(evening));
            binding.tvCountNight.setText(String.valueOf(night));

            binding.progressMorning.setProgress(maxVal > 0 ? (int) (((float) morning / maxVal) * 100) : 0);
            binding.progressAfternoon.setProgress(maxVal > 0 ? (int) (((float) afternoon / maxVal) * 100) : 0);
            binding.progressEvening.setProgress(maxVal > 0 ? (int) (((float) evening / maxVal) * 100) : 0);
            binding.progressNight.setProgress(maxVal > 0 ? (int) (((float) night / maxVal) * 100) : 0);
        });
    }

    private long getStartOfDay(int offsetDays) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offsetDays);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
