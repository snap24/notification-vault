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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.zygisk_enc.notivault.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AutoDeleteDialogHelper {

    public static class AppItem {
        public String packageName;
        public String appName;
        public boolean isSystem;
    }

    public static void showPerAppAutoDeleteDialog(Context context) {
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

            Set<String> savedPackages = PreferenceUtil.getAutoDeletePackages(context);
            Set<String> workingSelection = new HashSet<>(savedPackages);

            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_per_app_auto_delete, null);
                TextInputEditText etSearch = dialogView.findViewById(R.id.et_search_apps);
                ChipGroup chipGroup = dialogView.findViewById(R.id.chip_group_filters);
                TextView tvToggleAllTitle = dialogView.findViewById(R.id.tv_toggle_all_title);
                TextView tvToggleAllDesc = dialogView.findViewById(R.id.tv_toggle_all_desc);
                MaterialSwitch switchToggleAll = dialogView.findViewById(R.id.switch_toggle_all);
                RecyclerView rvApps = dialogView.findViewById(R.id.rv_apps_list);

                rvApps.setLayoutManager(new LinearLayoutManager(context));
                AutoDeleteAppAdapter adapter = new AutoDeleteAppAdapter(context, userList, systemList, workingSelection);
                rvApps.setAdapter(adapter);

                adapter.setOnSelectionListener(() -> {
                    updateToggleAllState(adapter, tvToggleAllTitle, tvToggleAllDesc, switchToggleAll);
                });

                updateToggleAllState(adapter, tvToggleAllTitle, tvToggleAllDesc, switchToggleAll);

                switchToggleAll.setOnClickListener(v -> {
                    boolean isChecked = switchToggleAll.isChecked();
                    adapter.toggleAllVisible(isChecked);
                    updateToggleAllState(adapter, tvToggleAllTitle, tvToggleAllDesc, switchToggleAll);
                });

                chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                    if (checkedIds.contains(R.id.chip_system)) {
                        adapter.setFilterMode(AutoDeleteAppAdapter.MODE_SYSTEM);
                    } else if (checkedIds.contains(R.id.chip_all)) {
                        adapter.setFilterMode(AutoDeleteAppAdapter.MODE_ALL);
                    } else {
                        adapter.setFilterMode(AutoDeleteAppAdapter.MODE_USER);
                    }
                    updateToggleAllState(adapter, tvToggleAllTitle, tvToggleAllDesc, switchToggleAll);
                });

                etSearch.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        adapter.setSearchQuery(s != null ? s.toString() : "");
                        updateToggleAllState(adapter, tvToggleAllTitle, tvToggleAllDesc, switchToggleAll);
                    }
                    @Override
                    public void afterTextChanged(Editable s) {}
                });

                AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.dialog_per_app_auto_delete_title)
                        .setView(dialogView)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.save, (d, which) -> {
                            PreferenceUtil.setAutoDeletePackages(context, workingSelection);
                            int count = workingSelection.size();
                            String toastMsg = count == 1
                                    ? context.getString(R.string.toast_saved_auto_delete_singular, count)
                                    : context.getString(R.string.toast_saved_auto_delete_plural, count);
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show();
                        })
                        .create();

                com.zygisk_enc.notivault.BaseActivity.showDialog(context, dialog);
            });
        });
    }

    public static void showCustomDaysDialog(Context context, Runnable onSaved) {
        int currentDays = PreferenceUtil.getAutoDeleteDays(context);
        final int[] workingDays = new int[]{currentDays};

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_auto_delete_days, null);
        TextInputEditText etDays = dialogView.findViewById(R.id.et_custom_days);
        android.widget.ImageButton btnMinus = dialogView.findViewById(R.id.btn_minus_days);
        android.widget.ImageButton btnPlus = dialogView.findViewById(R.id.btn_plus_days);

        etDays.setText(String.valueOf(workingDays[0]));

        btnMinus.setOnClickListener(v -> {
            int val = parseDays(etDays.getText());
            if (val > 0) {
                val--;
                workingDays[0] = val;
                etDays.setText(String.valueOf(val));
                if (etDays.getText() != null) {
                    etDays.setSelection(etDays.getText().length());
                }
            }
        });

        btnPlus.setOnClickListener(v -> {
            int val = parseDays(etDays.getText());
            if (val < 9999) {
                val++;
                workingDays[0] = val;
                etDays.setText(String.valueOf(val));
                if (etDays.getText() != null) {
                    etDays.setSelection(etDays.getText().length());
                }
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
                    PreferenceUtil.setAutoDeleteDays(context, days);
                    if (onSaved != null) {
                        onSaved.run();
                    }
                }));
    }

    private static int parseDays(CharSequence text) {
        if (text == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(text.toString().trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void updateToggleAllState(AutoDeleteAppAdapter adapter, TextView title, TextView desc, MaterialSwitch switchView) {
        int visibleCount = adapter.getVisibleCount();
        int selectedVisibleCount = adapter.getSelectedVisibleCount();

        switchView.setOnCheckedChangeListener(null);
        switchView.setChecked(visibleCount > 0 && selectedVisibleCount == visibleCount);

        Context context = title.getContext();
        String category = adapter.getFilterMode() == AutoDeleteAppAdapter.MODE_SYSTEM
                ? context.getString(R.string.category_system_apps)
                : adapter.getFilterMode() == AutoDeleteAppAdapter.MODE_ALL
                ? context.getString(R.string.category_apps)
                : context.getString(R.string.category_user_apps);

        title.setText(context.getString(R.string.auto_delete_all_category, category));
        desc.setText(context.getString(R.string.auto_delete_enabled_count, selectedVisibleCount, visibleCount));
    }

    static class AutoDeleteAppAdapter extends RecyclerView.Adapter<AutoDeleteAppAdapter.ViewHolder> {

        public static final int MODE_USER = 0;
        public static final int MODE_SYSTEM = 1;
        public static final int MODE_ALL = 2;

        public interface OnSelectionListener {
            void onSelectionChanged();
        }

        private final Context context;
        private final PackageManager pm;
        private final List<AppItem> userApps;
        private final List<AppItem> systemApps;
        private final Set<String> selectedPackages;
        private final List<AppItem> displayList = new ArrayList<>();
        private final Map<String, Drawable> iconCache = new ConcurrentHashMap<>();
        private int filterMode = MODE_USER;
        private String searchQuery = "";
        private OnSelectionListener selectionListener;

        AutoDeleteAppAdapter(Context context, List<AppItem> userApps, List<AppItem> systemApps, Set<String> selectedPackages) {
            this.context = context;
            this.pm = context.getPackageManager();
            this.userApps = userApps;
            this.systemApps = systemApps;
            this.selectedPackages = selectedPackages;
            refreshDisplayList();
        }

        void setOnSelectionListener(OnSelectionListener listener) {
            this.selectionListener = listener;
        }

        void setFilterMode(int mode) {
            this.filterMode = mode;
            refreshDisplayList();
            notifyDataSetChanged();
        }

        int getFilterMode() {
            return filterMode;
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

        void toggleAllVisible(boolean enable) {
            for (AppItem item : displayList) {
                if (enable) {
                    selectedPackages.add(item.packageName);
                } else {
                    selectedPackages.remove(item.packageName);
                }
            }
            notifyDataSetChanged();
            if (selectionListener != null) {
                selectionListener.onSelectionChanged();
            }
        }

        int getVisibleCount() {
            return displayList.size();
        }

        int getSelectedVisibleCount() {
            int count = 0;
            for (AppItem item : displayList) {
                if (selectedPackages.contains(item.packageName)) {
                    count++;
                }
            }
            return count;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_auto_delete_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem item = displayList.get(position);
            holder.tvAppName.setText(item.appName);
            holder.tvPackageName.setText(item.packageName + (item.isSystem ? " • System" : ""));

            boolean isChecked = selectedPackages.contains(item.packageName);
            holder.switchAutoDelete.setOnCheckedChangeListener(null);
            holder.switchAutoDelete.setChecked(isChecked);

            holder.itemView.setOnClickListener(v -> {
                boolean next = !holder.switchAutoDelete.isChecked();
                holder.switchAutoDelete.setChecked(next);
                if (next) {
                    selectedPackages.add(item.packageName);
                } else {
                    selectedPackages.remove(item.packageName);
                }
                if (selectionListener != null) {
                    selectionListener.onSelectionChanged();
                }
            });

            holder.switchAutoDelete.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    selectedPackages.add(item.packageName);
                } else {
                    selectedPackages.remove(item.packageName);
                }
                if (selectionListener != null) {
                    selectionListener.onSelectionChanged();
                }
            });

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

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivAppIcon;
            final TextView tvAppName;
            final TextView tvPackageName;
            final MaterialSwitch switchAutoDelete;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
                tvAppName = itemView.findViewById(R.id.tv_app_name);
                tvPackageName = itemView.findViewById(R.id.tv_package_name);
                switchAutoDelete = itemView.findViewById(R.id.switch_auto_delete);
            }
        }
    }
}
