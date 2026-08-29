package com.zygisk_enc.notivault.adapter;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.util.DateUtils;
import java.util.ArrayList;
import java.util.List;

public class NotificationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_NOTIFICATION = 1;

    public interface OnItemClickListener {
        void onItemClick(NotificationEntity entity);
        void onItemLongClick(NotificationEntity entity);
        void onDeleteClick(NotificationEntity entity);
        void onFavoriteClick(NotificationEntity entity);
    }

    // Wrapper class to hold either a header string or a notification
    public static class ListItem {
        public static final int TYPE_HEADER = 0;
        public static final int TYPE_NOTIFICATION = 1;
        public final int type;
        public final String header;
        public final NotificationEntity entity;

        public ListItem(String header) {
            this.type = TYPE_HEADER;
            this.header = header;
            this.entity = null;
        }

        public ListItem(NotificationEntity entity) {
            this.type = TYPE_NOTIFICATION;
            this.header = null;
            this.entity = entity;
        }
    }

    private final androidx.recyclerview.widget.AsyncListDiffer<ListItem> differ;
    private OnItemClickListener listener;
    private boolean showReadUnreadStatus = true;

    public void setShowReadUnreadStatus(boolean enabled) {
        if (this.showReadUnreadStatus != enabled) {
            this.showReadUnreadStatus = enabled;
            notifyDataSetChanged();
        }
    }

    private static final DiffUtil.ItemCallback<ListItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<ListItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull ListItem oldItem, @NonNull ListItem newItem) {
            if (oldItem.type != newItem.type) return false;
            if (oldItem.type == ListItem.TYPE_HEADER) {
                return oldItem.header.equals(newItem.header);
            }
            return oldItem.entity.id == newItem.entity.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull ListItem oldItem, @NonNull ListItem newItem) {
            if (oldItem.type == ListItem.TYPE_HEADER) {
                return oldItem.header.equals(newItem.header);
            }
            NotificationEntity o = oldItem.entity;
            NotificationEntity n = newItem.entity;
            return o.id == n.id && o.isRead == n.isRead && o.isFavorite == n.isFavorite && o.duplicateCount == n.duplicateCount && o.timestamp == n.timestamp;
        }
    };

    public NotificationAdapter() {
        differ = new androidx.recyclerview.widget.AsyncListDiffer<>(this, DIFF_CALLBACK);
    }

    private static final java.util.concurrent.ExecutorService imageExecutor = java.util.concurrent.Executors.newFixedThreadPool(3);
    private static final android.util.LruCache<String, Bitmap> imageCache;
    static {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        imageCache = new android.util.LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<ListItem> newItems) {
        differ.submitList(newItems);
    }

    public ListItem getItem(int position) {
        return differ.getCurrentList().get(position);
    }

    @Override
    public int getItemViewType(int position) {
        return differ.getCurrentList().get(position).type == ListItem.TYPE_HEADER ? TYPE_HEADER : TYPE_NOTIFICATION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_date_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_notification, parent, false);
            return new NotificationViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ListItem item = differ.getCurrentList().get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(item.header);
        } else if (holder instanceof NotificationViewHolder) {
            ((NotificationViewHolder) holder).bind(item.entity, listener, showReadUnreadStatus);
        }
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    // --- ViewHolders ---

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDate;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tv_date_header);
        }

        void bind(String header) {
            tvDate.setText(header);
        }
    }

    static class NotificationViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView card;
        private final ImageView ivAppIcon;
        private final TextView tvAppName;
        private final TextView tvTitle;
        private final TextView tvText;
        private final TextView tvTime;
        private final TextView tvDuplicateCount;
        private final ImageView ivNotificationImage;
        private final ImageButton btnFavorite;

        NotificationViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card_notification);
            ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
            tvAppName = itemView.findViewById(R.id.tv_app_name);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvText = itemView.findViewById(R.id.tv_text);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvDuplicateCount = itemView.findViewById(R.id.tv_duplicate_count);
            ivNotificationImage = itemView.findViewById(R.id.iv_notification_image);
            btnFavorite = itemView.findViewById(R.id.btn_favorite);
        }

        void bind(NotificationEntity entity, OnItemClickListener listener, boolean showReadUnreadStatus) {
            Context context = itemView.getContext();

            // App icon loading with memory-safe AppIconLoader
            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(context).loadInto(
                    ivAppIcon, entity.packageName, android.R.drawable.sym_def_app_icon);

            tvAppName.setText(entity.appName);

            // Decrypt fields with cache
            if (entity.decryptedTitle == null) {
                entity.decryptedTitle = EncryptionHelper.decrypt(entity.title);
            }
            if (entity.decryptedText == null) {
                entity.decryptedText = EncryptionHelper.decrypt(entity.text);
            }
            if (entity.decryptedBigText == null) {
                entity.decryptedBigText = EncryptionHelper.decrypt(entity.bigText);
            }

            tvTitle.setText(entity.decryptedTitle);

            String displayText = (entity.decryptedBigText != null && !entity.decryptedBigText.isEmpty())
                    ? entity.decryptedBigText : entity.decryptedText;
            if (displayText != null && !displayText.isEmpty()) {
                tvText.setVisibility(View.VISIBLE);
                tvText.setText(displayText);
            } else {
                tvText.setVisibility(View.GONE);
            }

            // Bind image (if any) asynchronously
            if (entity.imagePath != null && !entity.imagePath.isEmpty()) {
                ivNotificationImage.setVisibility(View.VISIBLE);
                final String firstPath = entity.imagePath.contains("|") 
                        ? entity.imagePath.split("\\|")[0].trim() 
                        : entity.imagePath.trim();
                ivNotificationImage.setTag(firstPath);

                Bitmap cachedBitmap = imageCache.get(firstPath);
                if (cachedBitmap != null) {
                    ivNotificationImage.setImageBitmap(cachedBitmap);
                } else {
                    ivNotificationImage.setImageDrawable(null);
                    
                    imageExecutor.execute(() -> {
                        try {
                            java.io.File file = new java.io.File(firstPath);
                            byte[] decryptedBytes = EncryptionHelper.decryptFile(file);
                            if (decryptedBytes != null) {
                                BitmapFactory.Options options = new BitmapFactory.Options();
                                options.inJustDecodeBounds = true;
                                BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.length, options);

                                int inSampleSize = 1;
                                int reqSize = 512;
                                if (options.outHeight > reqSize || options.outWidth > reqSize) {
                                    int halfH = options.outHeight / 2;
                                    int halfW = options.outWidth / 2;
                                    while ((halfH / inSampleSize) >= reqSize && (halfW / inSampleSize) >= reqSize) {
                                        inSampleSize *= 2;
                                    }
                                }
                                options.inSampleSize = inSampleSize;
                                options.inJustDecodeBounds = false;
                                options.inPreferredConfig = Bitmap.Config.RGB_565;

                                Bitmap bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.length, options);
                                if (bitmap != null) {
                                    imageCache.put(firstPath, bitmap);
                                    
                                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                        if (firstPath.equals(ivNotificationImage.getTag())) {
                                            ivNotificationImage.setImageBitmap(bitmap);
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            } else {
                ivNotificationImage.setTag(null);
                ivNotificationImage.setVisibility(View.GONE);
            }

            tvTime.setText(DateUtils.getTimeString(context, entity.timestamp));

            if (entity.duplicateCount > 1) {
                tvDuplicateCount.setVisibility(View.VISIBLE);
                tvDuplicateCount.setText("x" + entity.duplicateCount);
            } else {
                tvDuplicateCount.setVisibility(View.GONE);
            }

            // Visual difference for read/unread
            if (showReadUnreadStatus) {
                card.setStrokeWidth(entity.isRead ? 0 : 3);
                tvTitle.setAlpha(entity.isRead ? 0.7f : 1.0f);
            } else {
                card.setStrokeWidth(0);
                tvTitle.setAlpha(1.0f);
            }

            // Bind Favorite Icon with sleek visual distinction
            if (entity.isFavorite) {
                btnFavorite.setImageResource(R.drawable.ic_star);
                btnFavorite.setColorFilter(androidx.core.content.ContextCompat.getColor(itemView.getContext(), R.color.gold_star));
                btnFavorite.setAlpha(1.0f);
            } else {
                btnFavorite.setImageResource(R.drawable.ic_star_border);
                int outlineColor = com.google.android.material.color.MaterialColors.getColor(
                        itemView, com.google.android.material.R.attr.colorOutline, android.graphics.Color.GRAY);
                btnFavorite.setColorFilter(outlineColor);
                btnFavorite.setAlpha(0.6f);
            }

            if (listener != null) {
                card.setOnClickListener(v -> listener.onItemClick(entity));
                card.setOnLongClickListener(v -> {
                    listener.onItemLongClick(entity);
                    return true;
                });
                btnFavorite.setOnClickListener(v -> {
                    btnFavorite.animate()
                            .scaleX(1.3f)
                            .scaleY(1.3f)
                            .setDuration(120)
                            .withEndAction(() -> btnFavorite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start())
                            .start();
                    listener.onFavoriteClick(entity);
                });
            }
        }
    }
}
