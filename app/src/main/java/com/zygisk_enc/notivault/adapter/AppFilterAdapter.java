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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppFilterAdapter extends RecyclerView.Adapter<AppFilterAdapter.AppViewHolder> {

    public interface OnAppClickListener {
        void onAppClick(AppSummary summary);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private List<AppSummary> fullList = new ArrayList<>();
    private List<AppSummary> filteredList = new ArrayList<>();
    private final Set<String> selectedPackages = new HashSet<>();
    private boolean isSelectionMode = false;
    private OnAppClickListener listener;
    private OnSelectionChangedListener selectionListener;
    private String currentQuery = "";

    public void setOnAppClickListener(OnAppClickListener listener) {
        this.listener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionListener = listener;
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public void setSelectionMode(boolean enabled) {
        if (this.isSelectionMode != enabled) {
            this.isSelectionMode = enabled;
            if (!enabled) {
                selectedPackages.clear();
            }
            notifyDataSetChanged();
            if (selectionListener != null) {
                selectionListener.onSelectionChanged(selectedPackages.size(), filteredList.size());
            }
        }
    }

    public void selectAll() {
        for (AppSummary summary : filteredList) {
            selectedPackages.add(summary.packageName);
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

    public Set<String> getSelectedPackages() {
        return new HashSet<>(selectedPackages);
    }

    public void submitList(List<AppSummary> newItems) {
        fullList = newItems != null ? newItems : new ArrayList<>();
        filter(currentQuery);
    }

    public void filter(String query) {
        currentQuery = query != null ? query.trim().toLowerCase() : "";
        filteredList.clear();
        if (currentQuery.isEmpty()) {
            filteredList.addAll(fullList);
        } else {
            for (AppSummary summary : fullList) {
                String name = summary.appName != null ? summary.appName.toLowerCase() : "";
                String pkg = summary.packageName.toLowerCase();
                if (name.contains(currentQuery) || pkg.contains(currentQuery)) {
                    filteredList.add(summary);
                }
            }
        }
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedPackages.size(), filteredList.size());
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
        holder.bind(summary, isSelectionMode, selectedPackages.contains(summary.packageName), listener, () -> {
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

        void bind(AppSummary summary, boolean selectionMode, boolean isSelected, OnAppClickListener listener, Runnable onToggle) {
            Context context = itemView.getContext();
            PackageManager pm = context.getPackageManager();
            try {
                Drawable icon = pm.getApplicationIcon(summary.packageName);
                ivIcon.setImageDrawable(icon);
            } catch (PackageManager.NameNotFoundException e) {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            tvName.setText(summary.appName != null ? summary.appName : summary.packageName);
            tvPackage.setText(summary.packageName);
            tvCount.setText(String.valueOf(summary.count));

            if (selectionMode) {
                cbSelect.setVisibility(View.VISIBLE);
                cbSelect.setChecked(isSelected);
                itemView.setOnClickListener(v -> onToggle.run());
            } else {
                cbSelect.setVisibility(View.GONE);
                itemView.setOnClickListener(v -> { if (listener != null) listener.onAppClick(summary); });
            }
        }
    }
}
