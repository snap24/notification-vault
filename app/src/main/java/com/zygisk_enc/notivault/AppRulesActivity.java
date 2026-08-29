package com.zygisk_enc.notivault;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import com.zygisk_enc.notivault.util.AppExecutor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppRulesActivity extends BaseActivity {

    private RecyclerView rvApps;
    private TextInputEditText etSearch;
    private MaterialSwitch switchToggleAll;
    private TextView tvToggleAllTitle;
    private TextView tvToggleAllDesc;
    private ChipGroup chipGroup;
    private Chip chipUser;
    private Chip chipSystem;

    private List<AppInfoItem> userAppItems = new ArrayList<>();
    private List<AppInfoItem> systemAppItems = new ArrayList<>();
    private List<AppInfoItem> currentList = new ArrayList<>();
    private RulesAdapter adapter;

    private final ActivityResultLauncher<Intent> editRuleLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadAppsData();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_rules);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_rules);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvApps = findViewById(R.id.rv_apps_list);
        rvApps.setLayoutManager(new LinearLayoutManager(this));

        etSearch = findViewById(R.id.et_search_apps);
        switchToggleAll = findViewById(R.id.switch_toggle_all);
        tvToggleAllTitle = findViewById(R.id.tv_toggle_all_title);
        tvToggleAllDesc = findViewById(R.id.tv_toggle_all_desc);
        chipGroup = findViewById(R.id.chip_group_filters);
        chipUser = findViewById(R.id.chip_user);
        chipSystem = findViewById(R.id.chip_system);

        adapter = new RulesAdapter(this, currentList, item -> {
            Intent intent = new Intent(this, AppRuleEditActivity.class);
            intent.putExtra(AppRuleEditActivity.EXTRA_PACKAGE_NAME, item.packageName);
            intent.putExtra(AppRuleEditActivity.EXTRA_APP_NAME, item.appName);
            editRuleLauncher.launch(intent);
        }, this::updateMasterToggleState);

        rvApps.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s != null ? s.toString() : "");
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_system)) {
                currentList = systemAppItems;
                tvToggleAllTitle.setText(R.string.capture_all_system_apps);
                tvToggleAllDesc.setText(R.string.capture_all_system_desc);
            } else {
                currentList = userAppItems;
                tvToggleAllTitle.setText(R.string.capture_all_user_apps);
                tvToggleAllDesc.setText(R.string.capture_all_user_desc);
            }
            etSearch.setText("");
            adapter.setList(currentList);
            updateMasterToggleState();
        });

        switchToggleAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            handleToggleAll(isChecked);
        });

        loadAppsData();
    }

    private void loadAppsData() {
        PackageManager pm = getPackageManager();
        AppExecutor.execute(() -> {
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            List<AppInfoItem> userItems = new ArrayList<>();
            List<AppInfoItem> systemItems = new ArrayList<>();

            for (ApplicationInfo info : packages) {
                if (info.packageName.equals(getPackageName())) continue;

                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

                AppInfoItem item = new AppInfoItem();
                item.packageName = info.packageName;
                item.appName = pm.getApplicationLabel(info).toString();
                item.isCaptureEnabled = true;
                item.isSystemApp = isSystem;

                if (isSystem) {
                    systemItems.add(item);
                } else {
                    userItems.add(item);
                }
            }

            AppDatabase db = AppDatabase.getInstance(this);
            List<AppRuleEntity> rules = db.appRuleDao().getAllRulesSync();

            Map<String, AppRuleEntity> ruleMap = new HashMap<>();
            if (rules != null) {
                for (AppRuleEntity r : rules) {
                    ruleMap.put(r.packageName, r);
                }
            }

            for (AppInfoItem item : userItems) {
                AppRuleEntity rule = ruleMap.get(item.packageName);
                item.rule = rule;
                item.isCaptureEnabled = (rule == null) || !rule.blockAll;
            }

            for (AppInfoItem item : systemItems) {
                AppRuleEntity rule = ruleMap.get(item.packageName);
                item.rule = rule;
                item.isCaptureEnabled = (rule == null) || !rule.blockAll;
            }

            userItems.sort((a, b) -> a.appName.compareToIgnoreCase(b.appName));
            systemItems.sort((a, b) -> a.appName.compareToIgnoreCase(b.appName));

            runOnUiThread(() -> {
                userAppItems = userItems;
                systemAppItems = systemItems;
                currentList = chipSystem.isChecked() ? systemAppItems : userAppItems;
                adapter.setList(currentList);
                adapter.filter(etSearch.getText() != null ? etSearch.getText().toString() : "");
                updateMasterToggleState();
            });
        });
    }

    private void handleToggleAll(boolean isChecked) {
        List<AppInfoItem> listToToggle = new ArrayList<>(currentList);
        AppExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            if (isChecked) {
                for (AppInfoItem item : listToToggle) {
                    item.isCaptureEnabled = true;
                    if (item.rule != null) {
                        if (item.rule.blockKeywords.isEmpty() && item.rule.allowKeywords.isEmpty()) {
                            db.appRuleDao().deleteByPackage(item.packageName);
                            item.rule = null;
                        } else {
                            item.rule.blockAll = false;
                            db.appRuleDao().insert(item.rule);
                        }
                    }
                }
            } else {
                for (AppInfoItem item : listToToggle) {
                    item.isCaptureEnabled = false;
                    if (item.rule == null) {
                        item.rule = new AppRuleEntity(item.packageName, item.appName, true, "", "", true);
                    } else {
                        item.rule.blockAll = true;
                    }
                    db.appRuleDao().insert(item.rule);
                }
            }

            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                updateMasterToggleState();
            });
        });
    }

    private void updateMasterToggleState() {
        boolean allEnabled = true;
        for (AppInfoItem item : currentList) {
            if (!item.isCaptureEnabled) {
                allEnabled = false;
                break;
            }
        }
        switchToggleAll.setChecked(allEnabled);
    }

    static class AppInfoItem {
        String packageName;
        String appName;
        boolean isCaptureEnabled;
        boolean isSystemApp;
        AppRuleEntity rule;
    }

    static class RulesAdapter extends RecyclerView.Adapter<RulesAdapter.ViewHolder> {
        private static final Map<String, Drawable> iconCache = new ConcurrentHashMap<>();
        private final ExecutorService iconExecutor = Executors.newFixedThreadPool(3);

        private final List<AppInfoItem> fullList;
        private final List<AppInfoItem> filteredList;
        private final AppCompatActivity activity;
        private final OnRuleEditListener listener;
        private final Runnable onStateChanged;

        interface OnRuleEditListener {
            void onEdit(AppInfoItem item);
        }

        RulesAdapter(AppCompatActivity activity, List<AppInfoItem> list, OnRuleEditListener listener, Runnable onStateChanged) {
            this.activity = activity;
            this.fullList = new ArrayList<>(list);
            this.filteredList = new ArrayList<>(list);
            this.listener = listener;
            this.onStateChanged = onStateChanged;
        }

        void setList(List<AppInfoItem> list) {
            fullList.clear();
            fullList.addAll(list);
            filter("");
        }

        void filter(String query) {
            filteredList.clear();
            if (query == null || query.trim().isEmpty()) {
                filteredList.addAll(fullList);
            } else {
                String lower = query.toLowerCase().trim();
                for (AppInfoItem item : fullList) {
                    if (item.appName.toLowerCase().contains(lower) || item.packageName.toLowerCase().contains(lower)) {
                        filteredList.add(item);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_rule_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfoItem item = filteredList.get(position);
            holder.tvName.setText(item.appName);

            holder.ivIcon.setTag(item.packageName);
            Drawable cachedIcon = iconCache.get(item.packageName);
            if (cachedIcon != null) {
                holder.ivIcon.setImageDrawable(cachedIcon);
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_code);
                iconExecutor.execute(() -> {
                    try {
                        Drawable icon = activity.getPackageManager().getApplicationIcon(item.packageName);
                        iconCache.put(item.packageName, icon);
                        activity.runOnUiThread(() -> {
                            if (item.packageName.equals(holder.ivIcon.getTag())) {
                                holder.ivIcon.setImageDrawable(icon);
                            }
                        });
                    } catch (Exception ignored) {}
                });
            }

            int statusColorAttr;
            int cardColorAttr;
            String statusText;

            if (!item.isCaptureEnabled) {
                statusText = activity.getString(R.string.status_recording_disabled);
                statusColorAttr = com.google.android.material.R.attr.colorError;
                cardColorAttr = com.google.android.material.R.attr.colorErrorContainer;
            } else if (item.rule != null && item.rule.isRuleEnabled &&
                    ((item.rule.blockKeywords != null && !item.rule.blockKeywords.isEmpty()) ||
                            (item.rule.allowKeywords != null && !item.rule.allowKeywords.isEmpty()))) {
                statusText = activity.getString(R.string.status_filters_active);
                statusColorAttr = com.google.android.material.R.attr.colorPrimary;
                cardColorAttr = com.google.android.material.R.attr.colorSecondaryContainer;
            } else {
                statusText = activity.getString(R.string.status_recording_active);
                statusColorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
                cardColorAttr = com.google.android.material.R.attr.colorSurfaceContainerLow;
            }

            int textColor = com.google.android.material.color.MaterialColors.getColor(activity, statusColorAttr, android.graphics.Color.GRAY);
            int cardColor = com.google.android.material.color.MaterialColors.getColor(activity, cardColorAttr, android.graphics.Color.WHITE);

            holder.tvStatus.setText(statusText);
            holder.tvStatus.setTextColor(textColor);
            holder.card.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(cardColor));

            holder.switchCapture.setOnCheckedChangeListener(null);
            holder.switchCapture.setChecked(item.isCaptureEnabled);

            holder.switchCapture.setOnCheckedChangeListener((btn, isChecked) -> {
                if (!btn.isPressed()) return;
                item.isCaptureEnabled = isChecked;
                AppExecutor.execute(() -> {
                    AppDatabase db = AppDatabase.getInstance(activity);
                    if (isChecked) {
                        if (item.rule != null) {
                            if (item.rule.blockKeywords.isEmpty() && item.rule.allowKeywords.isEmpty()) {
                                db.appRuleDao().deleteByPackage(item.packageName);
                                item.rule = null;
                            } else {
                                item.rule.blockAll = false;
                                db.appRuleDao().insert(item.rule);
                            }
                        }
                    } else {
                        if (item.rule == null) {
                            item.rule = new AppRuleEntity(item.packageName, item.appName, true, "", "", true);
                        } else {
                            item.rule.blockAll = true;
                        }
                        db.appRuleDao().insert(item.rule);
                    }
                    activity.runOnUiThread(() -> {
                        notifyItemChanged(holder.getAdapterPosition());
                        if (onStateChanged != null) {
                            onStateChanged.run();
                        }
                    });
                });
            });

            if (holder.tvTapGuide != null) {
                holder.tvTapGuide.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            }

            View.OnClickListener editClick = v -> listener.onEdit(item);
            holder.itemView.setOnClickListener(editClick);
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvName;
            TextView tvTapGuide;
            MaterialSwitch switchCapture;
            com.google.android.material.card.MaterialCardView card;
            TextView tvStatus;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_app_icon);
                tvName = itemView.findViewById(R.id.tv_app_name);
                tvTapGuide = itemView.findViewById(R.id.tv_tap_guide);
                switchCapture = itemView.findViewById(R.id.switch_capture);
                card = itemView.findViewById(R.id.card_app_rule);
                tvStatus = itemView.findViewById(R.id.tv_rule_status);
            }
        }
    }
}
