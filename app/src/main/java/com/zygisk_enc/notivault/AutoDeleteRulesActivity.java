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
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.zygisk_enc.notivault.util.AppExecutor;
import com.zygisk_enc.notivault.util.AutoDeleteDialogHelper;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoDeleteRulesActivity extends BaseActivity {

    private MaterialToolbar toolbar;
    private MaterialButton btnChangeGlobalRule;
    private TextInputEditText etSearch;
    private TextView tvAppsCountBadge;
    private ChipGroup chipGroupFilters;
    private RecyclerView rvApps;
    private View layoutLoading;
    private View layoutEmptyState;

    private final List<AutoDeleteDialogHelper.AppItem> userAppItems = new ArrayList<>();
    private final List<AutoDeleteDialogHelper.AppItem> systemAppItems = new ArrayList<>();
    private final Map<String, Integer> appRules = new HashMap<>();
    private int globalDays = 0;
    private UnifiedAutoDeleteAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_delete_rules);

        toolbar = findViewById(R.id.toolbar_auto_delete);
        toolbar.setNavigationOnClickListener(v -> finish());

        btnChangeGlobalRule = findViewById(R.id.btn_change_global_rule);
        etSearch = findViewById(R.id.et_search_apps);
        tvAppsCountBadge = findViewById(R.id.tv_apps_count_badge);
        chipGroupFilters = findViewById(R.id.chip_group_filters);
        rvApps = findViewById(R.id.rv_apps_list);
        layoutLoading = findViewById(R.id.layout_loading);
        layoutEmptyState = findViewById(R.id.layout_empty_state);

        if (btnChangeGlobalRule != null) {
            btnChangeGlobalRule.setOnClickListener(v -> showGlobalRetentionBottomSheet());
        }

        rvApps.setLayoutManager(new LinearLayoutManager(this));

        globalDays = PreferenceUtil.getGlobalAutoDeleteDays(this);
        appRules.putAll(PreferenceUtil.getAppAutoDeleteRules(this));

        updateGlobalRetentionButton(globalDays);

        adapter = new UnifiedAutoDeleteAdapter(this, userAppItems, systemAppItems, appRules, () -> globalDays);
        rvApps.setAdapter(adapter);

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

    private void showGlobalRetentionBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_app_rule_selector, null);
        dialog.setContentView(sheetView);

        ImageView ivIcon = sheetView.findViewById(R.id.bs_iv_app_icon);
        TextView tvName = sheetView.findViewById(R.id.bs_tv_app_name);
        TextView tvPkg = sheetView.findViewById(R.id.bs_tv_package_name);
        RecyclerView rvOptions = sheetView.findViewById(R.id.bs_rv_rule_options);

        tvName.setText(R.string.global_auto_delete_section);
        tvPkg.setText(R.string.global_auto_delete_section_desc);
        ivIcon.setImageResource(R.drawable.ic_delete_sweep);

        List<RuleOptionItem> options = new ArrayList<>();
        options.add(new RuleOptionItem(0, getString(R.string.never), "Never delete notifications automatically", R.drawable.ic_lock, globalDays == 0));
        options.add(new RuleOptionItem(1, "1 Day", "Delete notifications older than 24 hours", R.drawable.ic_delete_sweep, globalDays == 1));
        options.add(new RuleOptionItem(3, "3 Days", "Delete notifications older than 3 days", R.drawable.ic_delete_sweep, globalDays == 3));
        options.add(new RuleOptionItem(7, "7 Days (Recommended)", "Delete notifications older than 1 week", R.drawable.ic_delete_sweep, globalDays == 7));
        options.add(new RuleOptionItem(14, "14 Days", "Delete notifications older than 2 weeks", R.drawable.ic_delete_sweep, globalDays == 14));
        options.add(new RuleOptionItem(30, "30 Days", "Delete notifications older than 1 month", R.drawable.ic_delete_sweep, globalDays == 30));
        options.add(new RuleOptionItem(90, "90 Days", "Delete notifications older than 3 months", R.drawable.ic_delete_sweep, globalDays == 90));
        boolean isCustom = globalDays > 0 && globalDays != 1 && globalDays != 3 && globalDays != 7 && globalDays != 14 && globalDays != 30 && globalDays != 90;
        String customTitle = isCustom ? globalDays + " Days (Custom)" : getString(R.string.retention_custom_days);
        options.add(new RuleOptionItem(999, customTitle, "Specify custom retention duration in days", R.drawable.ic_rules, isCustom));

        RuleOptionAdapter optionAdapter = new RuleOptionAdapter(options, selectedOption -> {
            dialog.dismiss();
            if (selectedOption.id == 999) {
                AutoDeleteDialogHelper.showCustomDaysDialog(this, globalDays, days -> {
                    globalDays = days;
                    PreferenceUtil.setGlobalAutoDeleteDays(this, days);
                    updateGlobalRetentionButton(days);
                    adapter.notifyDataSetChanged();
                });
            } else {
                globalDays = selectedOption.id;
                PreferenceUtil.setGlobalAutoDeleteDays(this, selectedOption.id);
                updateGlobalRetentionButton(selectedOption.id);
                adapter.notifyDataSetChanged();
            }
        });

        rvOptions.setLayoutManager(new LinearLayoutManager(this));
        rvOptions.setAdapter(optionAdapter);

        dialog.show();
    }

    public void showAppRetentionBottomSheet(AutoDeleteDialogHelper.AppItem item) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_app_rule_selector, null);
        dialog.setContentView(sheetView);

        ImageView ivIcon = sheetView.findViewById(R.id.bs_iv_app_icon);
        TextView tvName = sheetView.findViewById(R.id.bs_tv_app_name);
        TextView tvPkg = sheetView.findViewById(R.id.bs_tv_package_name);
        RecyclerView rvOptions = sheetView.findViewById(R.id.bs_rv_rule_options);

        tvName.setText(item.appName);
        tvPkg.setText(item.packageName + (item.isSystem ? " • System App" : " • User App"));
        com.zygisk_enc.notivault.util.AppIconLoader.getInstance(this).loadInto(
                ivIcon, item.packageName, android.R.drawable.sym_def_app_icon);

        int currentGlobalDays = globalDays;
        String globalLabel = currentGlobalDays == 0 ? getString(R.string.chip_rule_never_delete) : currentGlobalDays + "d";

        Integer currentRule = appRules.get(item.packageName);

        List<RuleOptionItem> options = new ArrayList<>();
        options.add(new RuleOptionItem(0, getString(R.string.retention_use_global_default, globalLabel), "Follows global system retention policy", R.drawable.ic_clock, currentRule == null));
        options.add(new RuleOptionItem(1, getString(R.string.retention_after_1_day_otp), "Delete daily, best for verification codes", R.drawable.ic_delete_sweep, currentRule != null && currentRule == 1));
        options.add(new RuleOptionItem(3, getString(R.string.retention_after_x_days, 3), "Delete notifications older than 3 days", R.drawable.ic_delete_sweep, currentRule != null && currentRule == 3));
        options.add(new RuleOptionItem(7, getString(R.string.retention_after_x_days, 7), "Delete notifications older than 1 week", R.drawable.ic_delete_sweep, currentRule != null && currentRule == 7));
        options.add(new RuleOptionItem(14, getString(R.string.retention_after_x_days, 14), "Delete notifications older than 2 weeks", R.drawable.ic_delete_sweep, currentRule != null && currentRule == 14));
        options.add(new RuleOptionItem(30, getString(R.string.retention_after_x_days, 30), "Delete notifications older than 1 month", R.drawable.ic_delete_sweep, currentRule != null && currentRule == 30));
        options.add(new RuleOptionItem(-1, getString(R.string.retention_never_delete), "Protect all notifications from auto-deletion", R.drawable.ic_lock, currentRule != null && currentRule == -1));
        boolean isCustom = currentRule != null && currentRule > 0 && currentRule != 1 && currentRule != 3 && currentRule != 7 && currentRule != 14 && currentRule != 30;
        String customTitle = isCustom ? getString(R.string.retention_after_x_days, currentRule) + " (Custom)" : getString(R.string.retention_custom_days);
        options.add(new RuleOptionItem(999, customTitle, "Specify custom retention duration in days", R.drawable.ic_rules, isCustom));

        RuleOptionAdapter optionAdapter = new RuleOptionAdapter(options, selectedOption -> {
            dialog.dismiss();
            if (selectedOption.id == 0) {
                appRules.remove(item.packageName);
                PreferenceUtil.setAppAutoDeleteRule(this, item.packageName, null);
            } else if (selectedOption.id == 999) {
                int currentCustom = (currentRule != null && currentRule > 0) ? currentRule : 7;
                AutoDeleteDialogHelper.showCustomDaysDialog(this, currentCustom, days -> {
                    int saveVal = days <= 0 ? -1 : days;
                    appRules.put(item.packageName, saveVal);
                    PreferenceUtil.setAppAutoDeleteRule(this, item.packageName, saveVal);
                    adapter.notifyDataSetChanged();
                    updateEmptyStateAndCount();
                });
                return;
            } else {
                appRules.put(item.packageName, selectedOption.id);
                PreferenceUtil.setAppAutoDeleteRule(this, item.packageName, selectedOption.id);
            }
            adapter.notifyDataSetChanged();
            updateEmptyStateAndCount();
        });

        rvOptions.setLayoutManager(new LinearLayoutManager(this));
        rvOptions.setAdapter(optionAdapter);

        dialog.show();
    }

    private void updateGlobalRetentionButton(int days) {
        if (btnChangeGlobalRule != null) {
            String label;
            if (days == 0) {
                label = getString(R.string.never);
            } else if (days == 1) {
                label = "1 Day";
            } else {
                label = days + " Days";
            }
            btnChangeGlobalRule.setText(label + " ▾");
        }
    }

    public void updateEmptyStateAndCount() {
        int count = adapter != null ? adapter.getItemCount() : 0;
        if (tvAppsCountBadge != null) {
            tvAppsCountBadge.setText(count == 1 ? "1 app" : count + " apps");
        }
        if (layoutEmptyState != null && rvApps != null) {
            if (count == 0 && (layoutLoading == null || layoutLoading.getVisibility() != View.VISIBLE)) {
                layoutEmptyState.setVisibility(View.VISIBLE);
                rvApps.setVisibility(View.GONE);
            } else {
                layoutEmptyState.setVisibility(View.GONE);
                rvApps.setVisibility(View.VISIBLE);
            }
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
                if (layoutLoading != null) {
                    layoutLoading.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    }).start();
                }
                adapter.refreshDisplayList();
                adapter.notifyDataSetChanged();
                updateEmptyStateAndCount();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.zygisk_enc.notivault.util.AppIconLoader.getInstance(this).clearCache();
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
            if (context instanceof AutoDeleteRulesActivity) {
                ((AutoDeleteRulesActivity) context).updateEmptyStateAndCount();
            }
        }

        void setSearchQuery(String query) {
            this.searchQuery = query != null ? query.trim().toLowerCase() : "";
            refreshDisplayList();
            notifyDataSetChanged();
            if (context instanceof AutoDeleteRulesActivity) {
                ((AutoDeleteRulesActivity) context).updateEmptyStateAndCount();
            }
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
            String globalLabel = globalDays == 0 ? context.getString(R.string.chip_rule_never_delete) : globalDays + "d";

            android.util.TypedValue typedValue = new android.util.TypedValue();
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true);
            int primaryContainer = typedValue.data;
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, typedValue, true);
            int onPrimaryContainer = typedValue.data;
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, typedValue, true);
            int surfaceContainerHigh = typedValue.data;
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
            int onSurfaceVariant = typedValue.data;

            if (rule == null) {
                holder.tvRuleLabel.setText(context.getString(R.string.chip_rule_global_format, globalLabel));
                holder.ivRuleIcon.setImageResource(R.drawable.ic_clock);
                holder.cardRuleBadge.setCardBackgroundColor(surfaceContainerHigh);
                holder.tvRuleLabel.setTextColor(onSurfaceVariant);
                holder.ivRuleIcon.setColorFilter(onSurfaceVariant);
            } else if (rule == -1) {
                holder.tvRuleLabel.setText(R.string.chip_rule_never_delete);
                holder.ivRuleIcon.setImageResource(R.drawable.ic_lock);
                holder.cardRuleBadge.setCardBackgroundColor(primaryContainer);
                holder.tvRuleLabel.setTextColor(onPrimaryContainer);
                holder.ivRuleIcon.setColorFilter(onPrimaryContainer);
            } else {
                holder.tvRuleLabel.setText(rule == 1 ? context.getString(R.string.retention_after_1_day) : context.getString(R.string.retention_after_x_days, rule));
                holder.ivRuleIcon.setImageResource(R.drawable.ic_delete_sweep);
                holder.cardRuleBadge.setCardBackgroundColor(primaryContainer);
                holder.tvRuleLabel.setTextColor(onPrimaryContainer);
                holder.ivRuleIcon.setColorFilter(onPrimaryContainer);
            }

            holder.itemView.setOnClickListener(v -> {
                if (context instanceof AutoDeleteRulesActivity) {
                    ((AutoDeleteRulesActivity) context).showAppRetentionBottomSheet(item);
                }
            });

            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(context).loadInto(
                    holder.ivAppIcon, item.packageName, android.R.drawable.sym_def_app_icon);
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final com.google.android.material.card.MaterialCardView cardAppItem;
            final ImageView ivAppIcon;
            final TextView tvAppName;
            final TextView tvPackageName;
            final com.google.android.material.card.MaterialCardView cardRuleBadge;
            final ImageView ivRuleIcon;
            final TextView tvRuleLabel;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                cardAppItem = itemView.findViewById(R.id.card_app_item);
                ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                tvPackageName = itemView.findViewById(R.id.tv_package_name);
                cardRuleBadge = itemView.findViewById(R.id.card_rule_badge);
                ivRuleIcon = itemView.findViewById(R.id.iv_rule_icon);
                tvRuleLabel = itemView.findViewById(R.id.tv_rule_label);
            }
        }
    }

    public static class RuleOptionItem {
        public final int id;
        public final String title;
        public final String subtitle;
        public final int iconRes;
        public final boolean isSelected;

        public RuleOptionItem(int id, String title, String subtitle, int iconRes, boolean isSelected) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
            this.isSelected = isSelected;
        }
    }

    public static class RuleOptionAdapter extends RecyclerView.Adapter<RuleOptionAdapter.ViewHolder> {
        private final List<RuleOptionItem> items;
        private final OnOptionSelectedListener listener;

        public interface OnOptionSelectedListener {
            void onSelected(RuleOptionItem item);
        }

        public RuleOptionAdapter(List<RuleOptionItem> items, OnOptionSelectedListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_retention_rule_option, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RuleOptionItem item = items.get(position);
            holder.tvTitle.setText(item.title);
            holder.tvSubtitle.setText(item.subtitle);
            holder.ivIcon.setImageResource(item.iconRes);
            holder.rbSelected.setChecked(item.isSelected);

            Context context = holder.itemView.getContext();
            android.util.TypedValue typedValue = new android.util.TypedValue();
            if (item.isSelected) {
                context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true);
                holder.cardOption.setCardBackgroundColor(typedValue.data);
                context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
                holder.cardOption.setStrokeColor(typedValue.data);
                holder.cardOption.setStrokeWidth(Math.round(1.5f * context.getResources().getDisplayMetrics().density));
            } else {
                context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerLow, typedValue, true);
                holder.cardOption.setCardBackgroundColor(typedValue.data);
                context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true);
                holder.cardOption.setStrokeColor(typedValue.data);
                holder.cardOption.setStrokeWidth(Math.round(1f * context.getResources().getDisplayMetrics().density));
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onSelected(item);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final com.google.android.material.card.MaterialCardView cardOption;
            final ImageView ivIcon;
            final TextView tvTitle;
            final TextView tvSubtitle;
            final RadioButton rbSelected;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                cardOption = itemView.findViewById(R.id.card_option);
                ivIcon = itemView.findViewById(R.id.iv_option_icon);
                tvTitle = itemView.findViewById(R.id.tv_option_title);
                tvSubtitle = itemView.findViewById(R.id.tv_option_subtitle);
                rbSelected = itemView.findViewById(R.id.rb_selected);
            }
        }
    }
}
