package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.CompoundButton;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import java.util.List;

public class RuleDialogHelper {

    public interface RuleSaveCallback {
        void onSaved(AppRuleEntity newRule);
    }

    public static void showRuleDialog(Context context, LifecycleOwner lifecycleOwner, String packageName, String appName, NotificationViewModel viewModel, RuleSaveCallback callback) {
        AppExecutor.execute(() -> {
            com.zygisk_enc.notivault.database.AppDatabase db = com.zygisk_enc.notivault.database.AppDatabase.getInstance(context);
            AppRuleEntity existingRule = db.appRuleDao().getRuleSync(packageName);
            
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                View view = LayoutInflater.from(context).inflate(R.layout.dialog_app_rule, null);
                MaterialSwitch switchBlockAll = view.findViewById(R.id.switch_block_all);
                TextInputLayout tilBlockKeywords = view.findViewById(R.id.til_block_keywords);
                TextInputEditText tietBlockKeywords = view.findViewById(R.id.tiet_block_keywords);
                TextInputLayout tilAllowKeywords = view.findViewById(R.id.til_allow_keywords);
                TextInputEditText tietAllowKeywords = view.findViewById(R.id.tiet_allow_keywords);

                if (existingRule != null) {
                    switchBlockAll.setChecked(existingRule.isRuleEnabled);
                    tietBlockKeywords.setText(existingRule.blockKeywords);
                    tietAllowKeywords.setText(existingRule.allowKeywords);
                    
                    tilBlockKeywords.setEnabled(existingRule.isRuleEnabled);
                    tietBlockKeywords.setEnabled(existingRule.isRuleEnabled);
                    tilAllowKeywords.setEnabled(existingRule.isRuleEnabled);
                    tietAllowKeywords.setEnabled(existingRule.isRuleEnabled);
                } else {
                    switchBlockAll.setChecked(true);
                    tilBlockKeywords.setEnabled(true);
                    tietBlockKeywords.setEnabled(true);
                    tilAllowKeywords.setEnabled(true);
                    tietAllowKeywords.setEnabled(true);
                }

                switchBlockAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    tilBlockKeywords.setEnabled(isChecked);
                    tietBlockKeywords.setEnabled(isChecked);
                    tilAllowKeywords.setEnabled(isChecked);
                    tietAllowKeywords.setEnabled(isChecked);
                });

                AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                        .setTitle(context.getString(R.string.dialog_rule_title, appName))
                        .setView(view)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.dialog_rule_save, null)
                        .setNeutralButton(R.string.dialog_rule_delete, null)
                        .create();

                dialog.setOnShowListener(d -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        boolean isRuleEnabled = switchBlockAll.isChecked();
                        String blockKeywords = tietBlockKeywords.getText() != null ? tietBlockKeywords.getText().toString().trim() : "";
                        String allowKeywords = tietAllowKeywords.getText() != null ? tietAllowKeywords.getText().toString().trim() : "";

                        AppRuleEntity newRule = new AppRuleEntity(packageName, appName, false, blockKeywords, allowKeywords, isRuleEnabled);
                        viewModel.insertRule(newRule);
                        Toast.makeText(context, R.string.rule_saved, Toast.LENGTH_SHORT).show();
                        if (callback != null) callback.onSaved(newRule);
                        dialog.dismiss();
                    });

                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                        viewModel.deleteRuleByPackage(packageName);
                        Toast.makeText(context, R.string.rule_deleted, Toast.LENGTH_SHORT).show();
                        if (callback != null) callback.onSaved(null);
                        dialog.dismiss();
                    });
                });

                dialog.show();
            });
        });
    }

    public static void showRulesListDialog(Context context, LifecycleOwner lifecycleOwner, NotificationViewModel viewModel) {
        PackageManager pm = context.getPackageManager();
        AppExecutor.execute(() -> {
            List<android.content.pm.ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfoItem> appItems = new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo info : packages) {
                if (info.packageName.equals(context.getPackageName())) {
                    continue;
                }
                
                boolean isSystem = (info.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isUpdatedSystem = (info.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                boolean hasLaunch = pm.getLaunchIntentForPackage(info.packageName) != null;
                if (!hasLaunch && (isSystem || isUpdatedSystem)) {
                    continue; // Skip background system services
                }
                
                AppInfoItem item = new AppInfoItem();
                item.packageName = info.packageName;
                item.appName = info.loadLabel(pm).toString();
                item.icon = info.loadIcon(pm);
                item.isCaptureEnabled = true;
                appItems.add(item);
            }
            
            com.zygisk_enc.notivault.database.AppDatabase db = com.zygisk_enc.notivault.database.AppDatabase.getInstance(context);
            List<AppRuleEntity> rules = db.appRuleDao().getAllRulesSync();
            
            java.util.Map<String, AppRuleEntity> ruleMap = new java.util.HashMap<>();
            if (rules != null) {
                for (AppRuleEntity r : rules) {
                    ruleMap.put(r.packageName, r);
                }
            }
            
            for (AppInfoItem item : appItems) {
                AppRuleEntity rule = ruleMap.get(item.packageName);
                if (rule != null) {
                    item.rule = rule;
                    item.isCaptureEnabled = !rule.blockAll;
                } else {
                    item.rule = null;
                    item.isCaptureEnabled = true;
                }
            }
            
            appItems.sort((a, b) -> a.appName.compareToIgnoreCase(b.appName));
            
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                showRulesManagerDialog(context, lifecycleOwner, appItems, viewModel);
            });
        });
    }

    private static void showRulesManagerDialog(Context context, LifecycleOwner lifecycleOwner, List<AppInfoItem> appItems, NotificationViewModel viewModel) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_rules_manager, null);
        RecyclerView rv = view.findViewById(R.id.rv_apps_list);
        TextInputEditText etSearch = view.findViewById(R.id.et_search_apps);
        MaterialSwitch switchToggleAll = view.findViewById(R.id.switch_toggle_all);
        
        CompoundButton.OnCheckedChangeListener toggleAllListener = (btn, isChecked) -> {
            AppExecutor.execute(() -> {
                com.zygisk_enc.notivault.database.AppDatabase db = com.zygisk_enc.notivault.database.AppDatabase.getInstance(context);
                if (isChecked) {
                    // Enable capture for all apps -> delete block rules
                    for (AppInfoItem item : appItems) {
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
                    // Disable capture for all apps -> block all
                    for (AppInfoItem item : appItems) {
                        item.isCaptureEnabled = false;
                        if (item.rule == null) {
                            item.rule = new AppRuleEntity(item.packageName, item.appName, true, "", "", true);
                        } else {
                            item.rule.blockAll = true;
                        }
                        db.appRuleDao().insert(item.rule);
                    }
                }
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    RecyclerView.Adapter<?> adapter = rv.getAdapter();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            });
        };

        RulesAdapter adapter = new RulesAdapter(context, lifecycleOwner, appItems, viewModel, () -> {
            updateToggleAllState(switchToggleAll, appItems, toggleAllListener);
        });
        
        rv.setLayoutManager(new LinearLayoutManager(context));
        rv.setAdapter(adapter);
        
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s != null ? s.toString() : "");
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        // Set initial toggle all state
        updateToggleAllState(switchToggleAll, appItems, toggleAllListener);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_rules_list_title)
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private static void updateToggleAllState(MaterialSwitch switchToggleAll, List<AppInfoItem> appItems, CompoundButton.OnCheckedChangeListener listener) {
        switchToggleAll.setOnCheckedChangeListener(null);
        boolean allEnabled = true;
        for (AppInfoItem item : appItems) {
            if (!item.isCaptureEnabled) {
                allEnabled = false;
                break;
            }
        }
        switchToggleAll.setChecked(allEnabled);
        switchToggleAll.setOnCheckedChangeListener(listener);
    }

    static class AppInfoItem {
        String packageName;
        String appName;
        Drawable icon;
        boolean isCaptureEnabled;
        AppRuleEntity rule;
    }

    static class RulesAdapter extends RecyclerView.Adapter<RulesAdapter.ViewHolder> {
        private final List<AppInfoItem> fullList;
        private List<AppInfoItem> filteredList;
        private final NotificationViewModel viewModel;
        private final LifecycleOwner lifecycleOwner;
        private final Context context;
        private final Runnable onStateChanged;

        RulesAdapter(Context context, LifecycleOwner lifecycleOwner, List<AppInfoItem> list, NotificationViewModel viewModel, Runnable onStateChanged) {
            this.context = context;
            this.lifecycleOwner = lifecycleOwner;
            this.fullList = list;
            this.filteredList = new java.util.ArrayList<>(list);
            this.viewModel = viewModel;
            this.onStateChanged = onStateChanged;
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
            holder.ivIcon.setImageDrawable(item.icon);
            holder.tvName.setText(item.appName);
            
            holder.switchCapture.setOnCheckedChangeListener(null);
            holder.switchCapture.setChecked(item.isCaptureEnabled);
            
            holder.switchCapture.setOnCheckedChangeListener((btn, isChecked) -> {
                item.isCaptureEnabled = isChecked;
                AppExecutor.execute(() -> {
                    com.zygisk_enc.notivault.database.AppDatabase db = com.zygisk_enc.notivault.database.AppDatabase.getInstance(context);
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
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (onStateChanged != null) {
                            onStateChanged.run();
                        }
                    });
                });
            });

            holder.btnSettings.setOnClickListener(v -> {
                showRuleDialog(context, lifecycleOwner, item.packageName, item.appName, viewModel, (newRule) -> {
                    item.rule = newRule;
                    item.isCaptureEnabled = (newRule == null) || !newRule.blockAll;
                    notifyItemChanged(holder.getAdapterPosition());
                    if (onStateChanged != null) {
                        onStateChanged.run();
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvName;
            ImageButton btnSettings;
            MaterialSwitch switchCapture;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_app_icon);
                tvName = itemView.findViewById(R.id.tv_app_name);
                btnSettings = itemView.findViewById(R.id.btn_rule_settings);
                switchCapture = itemView.findViewById(R.id.switch_capture);
            }
        }
    }
}
