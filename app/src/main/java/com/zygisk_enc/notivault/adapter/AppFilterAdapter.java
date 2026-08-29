package com.zygisk_enc.notivault.adapter;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.util.AppExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AppFilterAdapter extends RecyclerView.Adapter<AppFilterAdapter.AppViewHolder> {

    public static final int SORT_VOLUME_DESC = 0;
    public static final int SORT_NAME_ASC = 1;
    public static final int SORT_VOLUME_ASC = 2;

    public interface OnAppClickListener {
        void onAppClick(AppSummary summary);
    }

    public interface OnAppLongClickListener {
        void onAppLongClick(AppSummary summary);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private List<AppSummary> fullList = new ArrayList<>();
    private final List<AppSummary> filteredList = new ArrayList<>();
    private final Set<String> selectedPackages = new HashSet<>();
    private boolean isSelectionMode = false;
    private int currentSortMode = SORT_VOLUME_DESC;
    private String currentQuery = "";

    private OnAppClickListener clickListener;
    private OnAppLongClickListener longClickListener;
    private OnSelectionChangedListener selectionListener;

    public void setOnAppClickListener(OnAppClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnAppLongClickListener(OnAppLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionListener = listener;
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public void setSelectionMode(boolean selectionMode) {
        if (this.isSelectionMode != selectionMode) {
            this.isSelectionMode = selectionMode;
            if (!selectionMode) {
                selectedPackages.clear();
            }
            notifyDataSetChanged();
        }
    }

    public Set<String> getSelectedPackages() {
        return new HashSet<>(selectedPackages);
    }

    public void selectAll() {
        selectedPackages.clear();
        for (AppSummary s : filteredList) {
            selectedPackages.add(s.packageName);
        }
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedPackages.size(), filteredList.size());
        }
    }

    public void deselectAll() {
        selectedPackages.clear();
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(0, filteredList.size());
        }
    }

    public int getSelectedCount() {
        return selectedPackages.size();
    }

    public void setSortMode(int sortMode) {
        this.currentSortMode = sortMode;
        applySort();
        filter(currentQuery);
    }

    public void submitList(List<AppSummary> newItems) {
        fullList.clear();
        if (newItems != null) {
            fullList.addAll(newItems);
        }
        applySort();
        filter(currentQuery);
    }

    public void filter(String query) {
        this.currentQuery = query != null ? query.trim().toLowerCase() : "";
        filteredList.clear();
        if (currentQuery.isEmpty()) {
            filteredList.addAll(fullList);
        } else {
            for (AppSummary summary : fullList) {
                String name = summary.appName != null ? summary.appName.toLowerCase() : "";
                String pkg = summary.packageName != null ? summary.packageName.toLowerCase() : "";
                if (name.contains(currentQuery) || pkg.contains(currentQuery)) {
                    filteredList.add(summary);
                }
            }
        }
        notifyDataSetChanged();
    }

    private void applySort() {
        if (currentSortMode == SORT_VOLUME_DESC) {
            Collections.sort(fullList, (a, b) -> Integer.compare(b.count, a.count));
        } else if (currentSortMode == SORT_NAME_ASC) {
            Collections.sort(fullList, (a, b) -> {
                String nameA = a.appName != null ? a.appName : a.packageName;
                String nameB = b.appName != null ? b.appName : b.packageName;
                return nameA.compareToIgnoreCase(nameB);
            });
        } else if (currentSortMode == SORT_VOLUME_ASC) {
            Collections.sort(fullList, (a, b) -> Integer.compare(a.count, b.count));
        }
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppSummary summary = filteredList.get(position);
        holder.bind(summary, isSelectionMode, selectedPackages.contains(summary.packageName),
                clickListener, longClickListener, () -> {
            if (selectedPackages.contains(summary.packageName)) {
                selectedPackages.remove(summary.packageName);
            } else {
                selectedPackages.add(summary.packageName);
            }
            notifyItemChanged(position);
            if (selectionListener != null) {
                selectionListener.onSelectionChanged(selectedPackages.size(), filteredList.size());
            }
        });
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCheckBox cbSelect;
        private final ImageView ivIcon;
        private final TextView tvName;
        private final TextView tvPackage;
        private final TextView tvCount;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            cbSelect = itemView.findViewById(R.id.cb_select_app);
            ivIcon = itemView.findViewById(R.id.iv_app_icon);
            tvName = itemView.findViewById(R.id.tv_app_name);
            tvPackage = itemView.findViewById(R.id.tv_app_package);
            tvCount = itemView.findViewById(R.id.tv_notification_count);
        }

        void bind(AppSummary summary, boolean selectionMode, boolean isSelected,
                  OnAppClickListener clickListener,
                  OnAppLongClickListener longClickListener,
                  Runnable onToggle) {
            Context context = itemView.getContext();

            tvName.setText(summary.appName != null ? summary.appName : summary.packageName);
            tvPackage.setText(summary.packageName);
            tvCount.setText(String.valueOf(summary.count));

            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(context).loadInto(
                    ivIcon, summary.packageName, android.R.drawable.sym_def_app_icon);

            if (selectionMode) {
                cbSelect.setVisibility(View.VISIBLE);
                cbSelect.setChecked(isSelected);
                itemView.setOnClickListener(v -> onToggle.run());
                itemView.setOnLongClickListener(null);
            } else {
                cbSelect.setVisibility(View.GONE);
                itemView.setOnClickListener(v -> {
                    if (clickListener != null) clickListener.onAppClick(summary);
                });
                itemView.setOnLongClickListener(v -> {
                    if (longClickListener != null) {
                        longClickListener.onAppLongClick(summary);
                        return true;
                    }
                    return false;
                });
            }
        }
    }
}
