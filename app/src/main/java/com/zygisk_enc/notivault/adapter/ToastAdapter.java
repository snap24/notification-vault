package com.zygisk_enc.notivault.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.ToastEntity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ToastAdapter extends ListAdapter<ToastEntity, ToastAdapter.ViewHolder> {

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault());

    public ToastAdapter() {
        super(new DiffUtil.ItemCallback<ToastEntity>() {
            @Override
            public boolean areItemsTheSame(@NonNull ToastEntity oldItem, @NonNull ToastEntity newItem) {
                return oldItem.id == newItem.id;
            }

            @Override
            public boolean areContentsTheSame(@NonNull ToastEntity oldItem, @NonNull ToastEntity newItem) {
                return oldItem.text.equals(newItem.text) 
                        && oldItem.timestamp == newItem.timestamp
                        && oldItem.packageName.equals(newItem.packageName);
            }
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_toast, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAppIcon;
        private final TextView tvAppName;
        private final TextView tvPackageName;
        private final TextView tvTimestamp;
        private final TextView tvToastText;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
            tvAppName = itemView.findViewById(R.id.tv_app_name);
            tvPackageName = itemView.findViewById(R.id.tv_package_name);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            tvToastText = itemView.findViewById(R.id.tv_toast_text);
        }

        public void bind(ToastEntity toast) {
            tvAppName.setText(toast.appName);
            tvPackageName.setText(toast.packageName);
            tvToastText.setText(toast.decryptedText != null ? toast.decryptedText : "");

            // Format timestamp matching system 12h/24h format (if today, show only time, if yesterday, show Yesterday + time, else show date + time)
            java.util.Calendar toastCal = java.util.Calendar.getInstance();
            toastCal.setTimeInMillis(toast.timestamp);

            java.util.Calendar today = java.util.Calendar.getInstance();
            java.util.Calendar yesterday = java.util.Calendar.getInstance();
            yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);

            Context context = itemView.getContext();
            String formattedTime = com.zygisk_enc.notivault.util.DateUtils.getTimeString(context, toast.timestamp);

            if (toastCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                    toastCal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) {
                tvTimestamp.setText(formattedTime);
            } else if (toastCal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                    toastCal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR)) {
                tvTimestamp.setText(context.getString(R.string.time_yesterday_format, formattedTime));
            } else {
                SimpleDateFormat datePrefix = new SimpleDateFormat("MMM dd, ", Locale.getDefault());
                tvTimestamp.setText(datePrefix.format(new java.util.Date(toast.timestamp)) + formattedTime);
            }

            // Load and cache App Icon asynchronously using memory-safe AppIconLoader
            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(context).loadInto(
                    ivAppIcon, toast.packageName, R.drawable.ic_code);
        }
    }
}
