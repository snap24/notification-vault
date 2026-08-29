package com.zygisk_enc.notivault;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.zygisk_enc.notivault.util.AppExecutor;
import com.zygisk_enc.notivault.util.AutoDeleteDialogHelper;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AutoDeleteRulesActivity extends BaseActivity {

    private MaterialToolbar toolbar;
    private TextView tvGlobalDesc;
    private ChipGroup chipGroupGlobal;
    private TextInputEditText etSearch;
    private ChipGroup chipGroupFilters;
    private RecyclerView rvApps;

    private List<AutoDeleteDialogHelper.AppItem> userAppItems = new ArrayList<>();
    private List<AutoDeleteDialogHelper.AppItem> systemAppItems = new ArrayList<>();
    private final Map<String, Integer> appRules = new HashMap<>();
    private int globalDays = 0;
    private UnifiedAutoDeleteAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_delete_rules);

        toolbar = findViewById(R.id.toolbar_auto_delete);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvGlobalDesc = findViewById(R.id.tv_global_status_desc);
        chipGroupGlobal = findViewById(R.id.chip_group_global_days);
        etSearch = findViewById(R.id.et_search_apps);
        chipGroupFilters = findViewById(R.id.chip_group_filters);
        rvApps = findViewById(R.id.rv_apps_list);

        rvApps.setLayoutManager(new LinearLayoutManager(this));

        globalDays = PreferenceUtil.getGlobalAutoDeleteDays(this);
        appRules.putAll(PreferenceUtil.getAppAutoDeleteRules(this));

        updateGlobalChips(globalDays);

        adapter = new UnifiedAutoDeleteAdapter(this, userAppItems, systemAppItems, appRules, () -> globalDays);
        rvApps.setAdapter(adapter);

        chipGroupGlobal.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            int newDays = globalDays;

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
                AutoDeleteDialogHelper.showCustomDaysDialog(this, globalDays, days -> {
                    globalDays = days;
                    PreferenceUtil.setGlobalAutoDeleteDays(this, days);
                    updateGlobalChips(days);
                    adapter.notifyDataSetChanged();
                });
                return;
            }

            globalDays = newDays;
            PreferenceUtil.setGlobalAutoDeleteDays(this, newDays);
            updateGlobalDesc(newDays);
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

        loadAppsData();
    }

    private void updateGlobalChips(int days) {
        chipGroupGlobal.clearCheck();
        if (days == 0) chipGroupGlobal.check(R.id.chip_global_never);
        else if (days == 1) chipGroupGlobal.check(R.id.chip_global_1d);
        else if (days == 2) chipGroupGlobal.check(R.id.chip_global_2d);
        else if (days == 3) chipGroupGlobal.check(R.id.chip_global_3d);
        else if (days == 7) chipGroupGlobal.check(R.id.chip_global_7d);
        else if (days == 14) chipGroupGlobal.check(R.id.chip_global_14d);
        else if (days == 30) chipGroupGlobal.check(R.id.chip_global_30d);
        else if (days == 60) chipGroupGlobal.check(R.id.chip_global_60d);
        else if (days == 90) chipGroupGlobal.check(R.id.chip_global_90d);
        else {
            Chip customChip = findViewById(R.id.chip_global_custom);
            if (customChip != null) customChip.setText(days + "d");
            chipGroupGlobal.check(R.id.chip_global_custom);
        }
        updateGlobalDesc(days);
    }

    private void updateGlobalDesc(int days) {
        if (tvGlobalDesc == null) return;
        if (days == 0) {
            tvGlobalDesc.setText("Global auto-delete is disabled (Never delete)");
        } else if (days == 1) {
            tvGlobalDesc.setText("Default: Delete notifications older than 1 day");
        } else {
            tvGlobalDesc.setText("Default: Delete notifications older than " + days + " days");
        }
    }

    private void loadAppsData() {
        PackageManager pm = getPackageManager();
        AppExecutor.execute(() -> {
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(0);
            List<AutoDeleteDialogHelper.AppItem> userList = new ArrayList<>();
            List<AutoDeleteDialogHelper.AppItem> systemList = new ArrayList<>();

            for (ApplicationInfo info : installedApps) {
                if (info.packageName.equals(getPackageName())) {
                    continue;
                }
                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

                AutoDeleteDialogHelper.AppItem item = new AutoDeleteDialogHelper.AppItem();
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

            runOnUiThread(() -> {
                userAppItems.clear();
                userAppItems.addAll(userList);
                systemAppItems.clear();
                systemAppItems.addAll(systemList);
                adapter.refreshDisplayList();
                adapter.notifyDataSetChanged();
            });
        });
    }

    static class UnifiedAutoDeleteAdapter extends RecyclerView.Adapter<UnifiedAutoDeleteAdapter.ViewHolder> {

        public static final int MODE_USER = 0;
        public static final int MODE_CUSTOMIZED = 1;
        public static final int MODE_SYSTEM = 2;
        public static final int MODE_ALL = 3;

        private final Context context;
        private final PackageManager pm;
        private final List<AutoDeleteDialogHelper.AppItem> userApps;
        private final List<AutoDeleteDialogHelper.AppItem> systemApps;
        private final Map<String, Integer> appRules;
        private final AutoDeleteDialogHelper.GlobalDaysProvider globalDaysProvider;
        private final List<AutoDeleteDialogHelper.AppItem> displayList = new ArrayList<>();
        private final Map<String, Drawable> iconCache = new ConcurrentHashMap<>();
        private int filterMode = MODE_USER;
        private String searchQuery = "";

        UnifiedAutoDeleteAdapter(Context context, List<AutoDeleteDialogHelper.AppItem> userApps,
                                 List<AutoDeleteDialogHelper.AppItem> systemApps,
                                 Map<String, Integer> appRules, AutoDeleteDialogHelper.GlobalDaysProvider globalDaysProvider) {
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

        public void refreshDisplayList() {
            displayList.clear();
            List<AutoDeleteDialogHelper.AppItem> source = new ArrayList<>();
            if (filterMode == MODE_USER) {
                source.addAll(userApps);
            } else if (filterMode == MODE_CUSTOMIZED) {
                for (AutoDeleteDialogHelper.AppItem item : userApps) {
                    if (appRules.containsKey(item.packageName)) source.add(item);
                }
                for (AutoDeleteDialogHelper.AppItem item : systemApps) {
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
                for (AutoDeleteDialogHelper.AppItem item : source) {
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
            AutoDeleteDialogHelper.AppItem item = displayList.get(position);
            holder.tvAppName.setText(item.appName);
            holder.tvPackageName.setText(item.packageName + (item.isSystem ? " • System" : ""));

            Integer rule = appRules.get(item.packageName);
            int globalDays = globalDaysProvider.getGlobalDays();
            String globalLabel = globalDays == 0 ? "Never" : globalDays + "d";

            if (rule == null) {
                holder.chipRule.setText("Global (" + globalLabel + ")");
                holder.chipRule.setChipIconResource(R.drawable.ic_clock);
            } else if (rule == -1) {
                holder.chipRule.setText("Never Delete");
                holder.chipRule.setChipIconResource(R.drawable.ic_lock);
            } else {
                holder.chipRule.setText(rule == 1 ? "After 1 day" : "After " + rule + " days");
                holder.chipRule.setChipIconResource(R.drawable.ic_delete_sweep);
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

        private void showRuleSelectorDialog(AutoDeleteDialogHelper.AppItem item) {
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

            BaseActivity.showDialog(context, new MaterialAlertDialogBuilder(context)
                    .setTitle(item.appName)
                    .setSingleChoiceItems(options, selectedIdx, (dialog, which) -> {
                        dialog.dismiss();
                        switch (which) {
                            case 0:
                                appRules.remove(item.packageName);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, null);
                                notifyDataSetChanged();
                                break;
                            case 1:
                                appRules.put(item.packageName, 1);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, 1);
                                notifyDataSetChanged();
                                break;
                            case 2:
                                appRules.put(item.packageName, 2);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, 2);
                                notifyDataSetChanged();
                                break;
                            case 3:
                                appRules.put(item.packageName, 3);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, 3);
                                notifyDataSetChanged();
                                break;
                            case 4:
                                appRules.put(item.packageName, 7);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, 7);
                                notifyDataSetChanged();
                                break;
                            case 5:
                                appRules.put(item.packageName, 14);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, 14);
                                notifyDataSetChanged();
                                break;
                            case 6:
                                appRules.put(item.packageName, 30);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, 30);
                                notifyDataSetChanged();
                                break;
                            case 7:
                                appRules.put(item.packageName, -1);
                                PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, -1);
                                notifyDataSetChanged();
                                break;
                            case 8:
                                int currentCustom = (currentRule != null && currentRule > 0) ? currentRule : 7;
                                AutoDeleteDialogHelper.showCustomDaysDialog(context, currentCustom, days -> {
                                    int saveVal = days <= 0 ? -1 : days;
                                    appRules.put(item.packageName, saveVal);
                                    PreferenceUtil.setAppAutoDeleteRule(context, item.packageName, saveVal);
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
