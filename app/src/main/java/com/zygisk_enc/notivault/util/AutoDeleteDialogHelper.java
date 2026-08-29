package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AutoDeleteDialogHelper {

    public static class AppItem {
        public String packageName;
        public String appName;
        public boolean isSystem;
    }

    public static void showUnifiedAutoDeleteDialog(Context context, Runnable onDismiss) {
        PackageManager pm = context.getPackageManager();
        AppExecutor.execute(() -> {
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(0);
            List<AppItem> userList = new ArrayList<>();
            List<AppItem> systemList = new ArrayList<>();

            for (ApplicationInfo info : installedApps) {
                if (info.packageName.equals(context.getPackageName())) {
                    continue;
                }
                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

                AppItem item = new AppItem();
                item.packageName = info.packageName;
                try {
                    CharSequence label = info.loadLabel(pm);
                    item.appName = label != null ? label.toString() : info.packageName;
                } catch (Exception e) {
                    item.appName = info.packageName;
                }
                item.isSystem = isSystem;

                if (isSystem) {
                    systemList.add(item);
                } else {
                    userList.add(item);
                }
            }

            Collections.sort(userList, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
            Collections.sort(systemList, (a, b) -> a.appName.compareToIgnoreCase(b.appName));

            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_unified_auto_delete, null);
                
                TextView tvGlobalDesc = dialogView.findViewById(R.id.tv_global_status_desc);
                ChipGroup chipGroupGlobal = dialogView.findViewById(R.id.chip_group_global_days);
                TextInputEditText etSearch = dialogView.findViewById(R.id.et_search_apps);
                ChipGroup chipGroupFilters = dialogView.findViewById(R.id.chip_group_filters);
                RecyclerView rvApps = dialogView.findViewById(R.id.rv_apps_list);

                final int[] workingGlobalDays = new int[]{PreferenceUtil.getGlobalAutoDeleteDays(context)};
                final Map<String, Integer> workingAppRules = new HashMap<>(PreferenceUtil.getAppAutoDeleteRules(context));

                updateGlobalChips(dialogView, workingGlobalDays[0], tvGlobalDesc);

                rvApps.setLayoutManager(new LinearLayoutManager(context));
                UnifiedAutoDeleteAdapter adapter = new UnifiedAutoDeleteAdapter(
                        context, userList, systemList, workingAppRules, () -> workingGlobalDays[0]);
                rvApps.setAdapter(adapter);

                chipGroupGlobal.setOnCheckedStateChangeListener((group, checkedIds) -> {
                    if (checkedIds.isEmpty()) return;
                    int checkedId = checkedIds.get(0);
                    int newDays = workingGlobalDays[0];

                    if (checkedId == R.id.chip_global_never) newDays = 0;
                    else if (checkedId == R.id.chip_global_1d) newDays = 1;
                    else if (checkedId == R.id.chip_global_2d) newDays = 2;
                    else if (checkedId == R.id.chip_global_3d) newDays = 3;
                    else if (checkedId == R.id.chip_global_7d) newDays = 7;
                    else if (checkedId == R.id.chip_global_14d) newDays = 14;
                    else if (checkedId == R.id.chip_global_30d) newDays = 30;
                    else if (checkedId == R.id.chip_global_60d) newDays = 60;
                    else if (checkedId == R.id.chip_global_90d) newDays = 90;
                    else if (checkedId == R.id.chip_global_custom) {
                        showCustomDaysDialog(context, workingGlobalDays[0], days -> {
                            workingGlobalDays[0] = days;
                            updateGlobalChips(dialogView, days, tvGlobalDesc);
                            adapter.notifyDataSetChanged();
                        });
                        return;
                    }

                    workingGlobalDays[0] = newDays;
                    updateGlobalDesc(context, newDays, tvGlobalDesc);
                    adapter.notifyDataSetChanged();
                });

                chipGroupFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
                    if (checkedIds.contains(R.id.chip_customized)) {
                        adapter.setFilterMode(UnifiedAutoDeleteAdapter.MODE_CUSTOMIZED);
                    } else if (checkedIds.contains(R.id.chip_system)) {
                        adapter.setFilterMode(UnifiedAutoDeleteAdapter.MODE_SYSTEM);
                    } else if (checkedIds.contains(R.id.chip_all)) {
                        adapter.setFilterMode(UnifiedAutoDeleteAdapter.MODE_ALL);
                    } else {
                        adapter.setFilterMode(UnifiedAutoDeleteAdapter.MODE_USER);
                    }
                });

                etSearch.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        adapter.setSearchQuery(s != null ? s.toString() : "");
                    }
                    @Override
                    public void afterTextChanged(Editable s) {}
                });

                AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.dialog_unified_auto_delete_title)
                        .setView(dialogView)
                        .setPositiveButton(R.string.save, (d, which) -> {
                            PreferenceUtil.setGlobalAutoDeleteDays(context, workingGlobalDays[0]);
                            PreferenceUtil.setAppAutoDeleteRules(context, workingAppRules);
                            if (onDismiss != null) onDismiss.run();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .create();

                com.zygisk_enc.notivault.BaseActivity.showDialog(context, dialog);
            });
        });
    }

    private static void updateGlobalChips(View root, int days, TextView tvDesc) {
        ChipGroup group = root.findViewById(R.id.chip_group_global_days);
        group.clearCheck();
        if (days == 0) group.check(R.id.chip_global_never);
        else if (days == 1) group.check(R.id.chip_global_1d);
        else if (days == 2) group.check(R.id.chip_global_2d);
        else if (days == 3) group.check(R.id.chip_global_3d);
        else if (days == 7) group.check(R.id.chip_global_7d);
        else if (days == 14) group.check(R.id.chip_global_14d);
        else if (days == 30) group.check(R.id.chip_global_30d);
        else if (days == 60) group.check(R.id.chip_global_60d);
        else if (days == 90) group.check(R.id.chip_global_90d);
        else {
            Chip customChip = root.findViewById(R.id.chip_global_custom);
            customChip.setText(days + "d");
            group.check(R.id.chip_global_custom);
        }
        updateGlobalDesc(root.getContext(), days, tvDesc);
    }

    private static void updateGlobalDesc(Context context, int days, TextView tvDesc) {
        if (tvDesc == null) return;
        if (days == 0) {
            tvDesc.setText("Global auto-delete is disabled (Never delete)");
        } else if (days == 1) {
            tvDesc.setText("Default: Delete notifications older than 1 day");
        } else {
            tvDesc.setText("Default: Delete notifications older than " + days + " days");
        }
    }

    public static void showCustomDaysDialog(Context context, int currentVal, CustomDaysCallback callback) {
        final int[] workingDays = new int[]{Math.max(0, currentVal)};

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_auto_delete_days, null);
        TextInputEditText etDays = dialogView.findViewById(R.id.et_custom_days);
        ImageButton btnMinus = dialogView.findViewById(R.id.btn_minus_days);
        ImageButton btnPlus = dialogView.findViewById(R.id.btn_plus_days);

        etDays.setText(String.valueOf(workingDays[0]));

        btnMinus.setOnClickListener(v -> {
            int val = parseDays(etDays.getText());
            if (val > 0) {
                val--;
                workingDays[0] = val;
                etDays.setText(String.valueOf(val));
                if (etDays.getText() != null) etDays.setSelection(etDays.getText().length());
            }
        });

        btnPlus.setOnClickListener(v -> {
            int val = parseDays(etDays.getText());
            if (val < 9999) {
                val++;
                workingDays[0] = val;
                etDays.setText(String.valueOf(val));
                if (etDays.getText() != null) etDays.setSelection(etDays.getText().length());
            }
        });

        dialogView.findViewById(R.id.chip_preset_never).setOnClickListener(v -> {
            workingDays[0] = 0;
            etDays.setText("0");
            etDays.setSelection(1);
        });
        dialogView.findViewById(R.id.chip_preset_7).setOnClickListener(v -> {
            workingDays[0] = 7;
            etDays.setText("7");
            etDays.setSelection(1);
        });
        dialogView.findViewById(R.id.chip_preset_15).setOnClickListener(v -> {
            workingDays[0] = 15;
            etDays.setText("15");
            etDays.setSelection(2);
        });
        dialogView.findViewById(R.id.chip_preset_30).setOnClickListener(v -> {
            workingDays[0] = 30;
            etDays.setText("30");
            etDays.setSelection(2);
        });
        dialogView.findViewById(R.id.chip_preset_60).setOnClickListener(v -> {
            workingDays[0] = 60;
            etDays.setText("60");
            etDays.setSelection(2);
        });
        dialogView.findViewById(R.id.chip_preset_90).setOnClickListener(v -> {
            workingDays[0] = 90;
            etDays.setText("90");
            etDays.setSelection(2);
        });
        dialogView.findViewById(R.id.chip_preset_180).setOnClickListener(v -> {
            workingDays[0] = 180;
            etDays.setText("180");
            etDays.setSelection(3);
        });

        com.zygisk_enc.notivault.BaseActivity.showDialog(context, new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_custom_days_title)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, which) -> {
                    int days = parseDays(etDays.getText());
                    if (callback != null) callback.onSelected(days);
                }));
    }

    public interface CustomDaysCallback {
        void onSelected(int days);
    }

    private static int parseDays(CharSequence text) {
        if (text == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(text.toString().trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    public static void executeAutoDelete(Context context, AppDatabase db) {
        long now = System.currentTimeMillis();
        int globalDays = PreferenceUtil.getGlobalAutoDeleteDays(context);
        Map<String, Integer> appRules = PreferenceUtil.getAppAutoDeleteRules(context);

        // 1. Process specific per-app overrides
        List<String> customizedPackages = new ArrayList<>(appRules.keySet());
        for (Map.Entry<String, Integer> entry : appRules.entrySet()) {
            String pkg = entry.getKey();
            int days = entry.getValue();
            if (days > 0) {
                long cutoff = now - (days * 24L * 60L * 60L * 1000L);
                List<String> imagePaths = db.notificationDao().getOldImagePathsForPackage(cutoff, pkg);
                if (imagePaths != null) {
                    for (String p : imagePaths) deleteFile(p);
                }
                db.notificationDao().deleteOlderThanForPackage(cutoff, pkg);
            }
            // If days == -1 (Never), no deletion is performed for this package!
        }

        // 2. Process global default for all non-customized apps
        if (globalDays > 0) {
            long globalCutoff = now - (globalDays * 24L * 60L * 60L * 1000L);
            if (!customizedPackages.isEmpty()) {
                List<String> imagePaths = db.notificationDao().getOldImagePathsExcludingPackages(globalCutoff, customizedPackages);
                if (imagePaths != null) {
                    for (String p : imagePaths) deleteFile(p);
                }
                db.notificationDao().deleteOlderThanExcludingPackages(globalCutoff, customizedPackages);
            } else {
                List<String> imagePaths = db.notificationDao().getOldImagePaths(globalCutoff);
                if (imagePaths != null) {
                    for (String p : imagePaths) deleteFile(p);
                }
                db.notificationDao().deleteOlderThan(globalCutoff);
            }
        }

        PreferenceUtil.setLastAutoDeleteTime(context, now);
    }

    private static void deleteFile(String imagePath) {
        if (imagePath != null && !imagePath.isEmpty()) {
            String[] paths = imagePath.split("\\|");
            for (String p : paths) {
                if (p != null && !p.trim().isEmpty()) {
                    File file = new File(p.trim());
                    if (file.exists()) file.delete();
                }
            }
        }
    }

    public interface GlobalDaysProvider {
        int getGlobalDays();
    }

    static class UnifiedAutoDeleteAdapter extends RecyclerView.Adapter<UnifiedAutoDeleteAdapter.ViewHolder> {

        public static final int MODE_USER = 0;
        public static final int MODE_CUSTOMIZED = 1;
        public static final int MODE_SYSTEM = 2;
        public static final int MODE_ALL = 3;

        private final Context context;
        private final PackageManager pm;
        private final List<AppItem> userApps;
        private final List<AppItem> systemApps;
        private final Map<String, Integer> appRules;
        private final GlobalDaysProvider globalDaysProvider;
        private final List<AppItem> displayList = new ArrayList<>();
        private final Map<String, Drawable> iconCache = new ConcurrentHashMap<>();
        private int filterMode = MODE_USER;
        private String searchQuery = "";

        UnifiedAutoDeleteAdapter(Context context, List<AppItem> userApps, List<AppItem> systemApps,
                                 Map<String, Integer> appRules, GlobalDaysProvider globalDaysProvider) {
            this.context = context;
            this.pm = context.getPackageManager();
            this.userApps = userApps;
            this.systemApps = systemApps;
            this.appRules = appRules;
            this.globalDaysProvider = globalDaysProvider;
            refreshDisplayList();
        }

        void setFilterMode(int mode) {
            this.filterMode = mode;
            refreshDisplayList();
            notifyDataSetChanged();
        }

        void setSearchQuery(String query) {
            this.searchQuery = query != null ? query.trim().toLowerCase() : "";
            refreshDisplayList();
            notifyDataSetChanged();
        }

        private void refreshDisplayList() {
            displayList.clear();
            List<AppItem> source = new ArrayList<>();
            if (filterMode == MODE_USER) {
                source.addAll(userApps);
            } else if (filterMode == MODE_CUSTOMIZED) {
                for (AppItem item : userApps) {
                    if (appRules.containsKey(item.packageName)) source.add(item);
                }
                for (AppItem item : systemApps) {
                    if (appRules.containsKey(item.packageName)) source.add(item);
                }
            } else if (filterMode == MODE_SYSTEM) {
                source.addAll(systemApps);
            } else {
                source.addAll(userApps);
                source.addAll(systemApps);
            }

            if (searchQuery.isEmpty()) {
                displayList.addAll(source);
            } else {
                for (AppItem item : source) {
                    if (item.appName.toLowerCase().contains(searchQuery) || item.packageName.toLowerCase().contains(searchQuery)) {
                        displayList.add(item);
                    }
                }
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_unified_auto_delete_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem item = displayList.get(position);
            holder.tvAppName.setText(item.appName);
            holder.tvPackageName.setText(item.packageName + (item.isSystem ? " • System" : ""));

            Integer rule = appRules.get(item.packageName);
            int globalDays = globalDaysProvider.getGlobalDays();
            String globalLabel = globalDays == 0 ? "Never" : globalDays + "d";

            if (rule == null) {
                holder.chipRule.setText("Global (" + globalLabel + ")");
                holder.chipRule.setChipIconResource(R.drawable.ic_clock);
                holder.chipRule.setChipBackgroundColorResource(android.R.color.transparent);
            } else if (rule == -1) {
                holder.chipRule.setText("Never Delete");
                holder.chipRule.setChipIconResource(R.drawable.ic_lock);
                holder.chipRule.setChipBackgroundColorResource(android.R.color.transparent);
            } else {
                holder.chipRule.setText(rule == 1 ? "After 1 day" : "After " + rule + " days");
                holder.chipRule.setChipIconResource(R.drawable.ic_delete_sweep);
                holder.chipRule.setChipBackgroundColorResource(android.R.color.transparent);
            }

            holder.itemView.setOnClickListener(v -> showRuleSelectorDialog(item));

            Drawable cachedIcon = iconCache.get(item.packageName);
            if (cachedIcon != null) {
                holder.ivAppIcon.setImageDrawable(cachedIcon);
            } else {
                holder.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                AppExecutor.execute(() -> {
                    try {
                        Drawable icon = pm.getApplicationIcon(item.packageName);
                        iconCache.put(item.packageName, icon);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            if (holder.getAdapterPosition() == position) {
                                holder.ivAppIcon.setImageDrawable(icon);
                            }
                        });
                    } catch (Exception ignored) {}
                });
            }
        }

        private void showRuleSelectorDialog(AppItem item) {
            int globalDays = globalDaysProvider.getGlobalDays();
            String globalLabel = globalDays == 0 ? "Never" : globalDays + " days";

            String[] options = new String[]{
                    "Use Global Default (" + globalLabel + ")",
                    "After 1 day (e.g. OTP / Banking)",
                    "After 2 days",
                    "After 3 days",
                    "After 7 days",
                    "After 14 days",
                    "After 30 days",
                    "Never Auto-Delete (Keep forever)",
                    "Custom days..."
            };

            Integer currentRule = appRules.get(item.packageName);
            int selectedIdx = 0;
            if (currentRule == null) selectedIdx = 0;
            else if (currentRule == 1) selectedIdx = 1;
            else if (currentRule == 2) selectedIdx = 2;
            else if (currentRule == 3) selectedIdx = 3;
            else if (currentRule == 7) selectedIdx = 4;
            else if (currentRule == 14) selectedIdx = 5;
            else if (currentRule == 30) selectedIdx = 6;
            else if (currentRule == -1) selectedIdx = 7;
            else selectedIdx = 8;

            com.zygisk_enc.notivault.BaseActivity.showDialog(context, new MaterialAlertDialogBuilder(context)
                    .setTitle(item.appName)
                    .setSingleChoiceItems(options, selectedIdx, (dialog, which) -> {
                        dialog.dismiss();
                        switch (which) {
                            case 0:
                                appRules.remove(item.packageName);
                                notifyDataSetChanged();
                                break;
                            case 1:
                                appRules.put(item.packageName, 1);
                                notifyDataSetChanged();
                                break;
                            case 2:
                                appRules.put(item.packageName, 2);
                                notifyDataSetChanged();
                                break;
                            case 3:
                                appRules.put(item.packageName, 3);
                                notifyDataSetChanged();
                                break;
                            case 4:
                                appRules.put(item.packageName, 7);
                                notifyDataSetChanged();
                                break;
                            case 5:
                                appRules.put(item.packageName, 14);
                                notifyDataSetChanged();
                                break;
                            case 6:
                                appRules.put(item.packageName, 30);
                                notifyDataSetChanged();
                                break;
                            case 7:
                                appRules.put(item.packageName, -1);
                                notifyDataSetChanged();
                                break;
                            case 8:
                                int currentCustom = (currentRule != null && currentRule > 0) ? currentRule : 7;
                                showCustomDaysDialog(context, currentCustom, days -> {
                                    if (days <= 0) {
                                        appRules.put(item.packageName, -1);
                                    } else {
                                        appRules.put(item.packageName, days);
                                    }
                                    notifyDataSetChanged();
                                });
                                break;
                        }
                    })
                    .setNegativeButton(R.string.cancel, null));
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivAppIcon;
            final TextView tvAppName;
            final TextView tvPackageName;
            final Chip chipRule;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                tvPackageName = itemView.findViewById(R.id.tv_package_name);
                chipRule = itemView.findViewById(R.id.chip_app_rule);
            }
        }
    }
}
