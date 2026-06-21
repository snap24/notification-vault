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
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppSummary;
import java.util.ArrayList;
import java.util.List;

public class AppFilterAdapter extends RecyclerView.Adapter<AppFilterAdapter.AppViewHolder> {

    public interface OnAppClickListener {
        void onAppClick(AppSummary summary);
    }

    private List<AppSummary> items = new ArrayList<>();
    private OnAppClickListener listener;

    public void setOnAppClickListener(OnAppClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<AppSummary> newItems) {
        items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivIcon;
        private final TextView tvName;
        private final TextView tvCount;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_app_icon);
            tvName = itemView.findViewById(R.id.tv_app_name);
            tvCount = itemView.findViewById(R.id.tv_notification_count);
        }

        void bind(AppSummary summary, OnAppClickListener listener) {
            Context context = itemView.getContext();
            PackageManager pm = context.getPackageManager();
            try {
                Drawable icon = pm.getApplicationIcon(summary.packageName);
                ivIcon.setImageDrawable(icon);
            } catch (PackageManager.NameNotFoundException e) {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            tvName.setText(summary.appName != null ? summary.appName : summary.packageName);
            tvCount.setText(String.valueOf(summary.count));
            itemView.setOnClickListener(v -> { if (listener != null) listener.onAppClick(summary); });
        }
    }
}
