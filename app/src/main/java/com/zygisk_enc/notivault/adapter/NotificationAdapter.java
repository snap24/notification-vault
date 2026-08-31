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
    public static final int TYPE_BUNDLE = 2;

    public interface OnItemClickListener {
        void onItemClick(NotificationEntity entity);
        void onItemLongClick(NotificationEntity entity);
        void onDeleteClick(NotificationEntity entity);
        void onFavoriteClick(NotificationEntity entity);
        void onBundleClick(NotificationBundle bundle);
        void onBundleFavoriteClick(NotificationBundle bundle);
    }

    public static class NotificationBundle {
        public final String packageName;
        public final String appName;
        public final List<NotificationEntity> notifications;
        public final long latestTimestamp;
        public final long oldestTimestamp;

        public NotificationBundle(String packageName, String appName, List<NotificationEntity> notifications) {
            this.packageName = packageName;
            this.appName = appName;
            this.notifications = notifications != null ? notifications : new ArrayList<>();
            this.latestTimestamp = !this.notifications.isEmpty() ? this.notifications.get(0).timestamp : 0;
            this.oldestTimestamp = !this.notifications.isEmpty() ? this.notifications.get(this.notifications.size() - 1).timestamp : 0;
        }

        public int getCount() {
            return notifications.size();
        }

        public boolean hasFavorite() {
            for (NotificationEntity entity : notifications) {
                if (entity.isFavorite) return true;
            }
            return false;
        }

        public int getFavoriteCount() {
            int count = 0;
            for (NotificationEntity entity : notifications) {
                if (entity.isFavorite) count++;
            }
            return count;
        }
    }

    // Wrapper class to hold either a header string, a notification, or a bundle
    public static class ListItem {
        public static final int TYPE_HEADER = 0;
        public static final int TYPE_NOTIFICATION = 1;
        public static final int TYPE_BUNDLE = 2;

        public final int type;
        public final String header;
        public final NotificationEntity entity;
        public final NotificationBundle bundle;

        public ListItem(String header) {
            this.type = TYPE_HEADER;
            this.header = header;
            this.entity = null;
            this.bundle = null;
        }

        public ListItem(NotificationEntity entity) {
            this.type = TYPE_NOTIFICATION;
            this.header = null;
            this.entity = entity;
            this.bundle = null;
        }

        public ListItem(NotificationBundle bundle) {
            this.type = TYPE_BUNDLE;
            this.header = null;
            this.entity = null;
            this.bundle = bundle;
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
            } else if (oldItem.type == ListItem.TYPE_BUNDLE) {
                return oldItem.bundle.packageName.equals(newItem.bundle.packageName) &&
                       oldItem.bundle.latestTimestamp == newItem.bundle.latestTimestamp &&
                       oldItem.bundle.getCount() == newItem.bundle.getCount();
            }
            return oldItem.entity.id == newItem.entity.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull ListItem oldItem, @NonNull ListItem newItem) {
            if (oldItem.type == ListItem.TYPE_HEADER) {
                return oldItem.header.equals(newItem.header);
            } else if (oldItem.type == ListItem.TYPE_BUNDLE) {
                return oldItem.bundle.getCount() == newItem.bundle.getCount() &&
                       oldItem.bundle.latestTimestamp == newItem.bundle.latestTimestamp &&
                       oldItem.bundle.getFavoriteCount() == newItem.bundle.getFavoriteCount();
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
    private static final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
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

    public static void clearImageCache() {
        if (imageCache != null) {
            imageCache.evictAll();
        }
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<ListItem> newItems) {
        differ.submitList(newItems);
    }

    public void submitList(List<ListItem> newItems, Runnable commitCallback) {
        differ.submitList(newItems, commitCallback);
    }

    public List<ListItem> getCurrentList() {
        return differ.getCurrentList();
    }

    public ListItem getItem(int position) {
        return differ.getCurrentList().get(position);
    }

    @Override
    public int getItemViewType(int position) {
        ListItem item = differ.getCurrentList().get(position);
        if (item.type == ListItem.TYPE_HEADER) return TYPE_HEADER;
        if (item.type == ListItem.TYPE_BUNDLE) return TYPE_BUNDLE;
        return TYPE_NOTIFICATION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_date_header, parent, false);
            return new HeaderViewHolder(view);
        } else if (viewType == TYPE_BUNDLE) {
            View view = inflater.inflate(R.layout.item_notification_bundle, parent, false);
            return new BundleViewHolder(view);
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
        } else if (holder instanceof BundleViewHolder) {
            ((BundleViewHolder) holder).bind(item.bundle, listener);
        } else if (holder instanceof NotificationViewHolder) {
            ((NotificationViewHolder) holder).bind(item.entity, listener, showReadUnreadStatus);
        }
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    // --- ViewHolders ---

    static class BundleViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView card;
        private final ImageView ivAppIcon;
        private final TextView tvAppName;
        private final TextView tvBundleTag;
        private final TextView tvTime;
        private final TextView tvWorkBadge;
        private final TextView tvPreviewTitle;
        private final TextView tvPreviewText;
        private final ImageButton btnFavorite;

        BundleViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card_bundle);
            ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
            tvAppName = itemView.findViewById(R.id.tv_app_name);
            tvWorkBadge = itemView.findViewById(R.id.tv_work_badge);
            tvBundleTag = itemView.findViewById(R.id.tv_bundle_tag);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvPreviewTitle = itemView.findViewById(R.id.tv_preview_title);
            tvPreviewText = itemView.findViewById(R.id.tv_preview_text);
            btnFavorite = itemView.findViewById(R.id.btn_favorite);
        }

        void bind(NotificationBundle bundle, OnItemClickListener listener) {
            if (bundle == null) return;
            Context ctx = itemView.getContext();
            tvAppName.setText(bundle.appName != null && !bundle.appName.isEmpty() ? bundle.appName : bundle.packageName);
            tvBundleTag.setText(ctx.getString(R.string.bundle_tag, bundle.getCount()));
            tvTime.setText(DateUtils.getTimeString(ctx, bundle.latestTimestamp));

            int bundleUserId = !bundle.notifications.isEmpty() ? bundle.notifications.get(0).userId : 0;
            boolean isWork = com.zygisk_enc.notivault.util.ProfileUtil.isWorkProfile(ctx, bundleUserId);
            if (tvWorkBadge != null) {
                tvWorkBadge.setVisibility(isWork ? View.VISIBLE : View.GONE);
            }

            if (!bundle.notifications.isEmpty()) {
                NotificationEntity latest = bundle.notifications.get(0);
                String title = latest.title != null ? EncryptionHelper.decrypt(latest.title) : "";
                String text = latest.text != null ? EncryptionHelper.decrypt(latest.text) : "";
                String bigText = latest.bigText != null ? EncryptionHelper.decrypt(latest.bigText) : null;
                String displayContent = bigText != null && !bigText.isEmpty() ? bigText : text;

                tvPreviewTitle.setText(title != null && !title.isEmpty() ? title : (latest.appName != null ? latest.appName : ""));
                tvPreviewText.setText(displayContent != null ? displayContent : "");
                tvPreviewTitle.setVisibility(View.VISIBLE);
                tvPreviewText.setVisibility(View.VISIBLE);
            } else {
                tvPreviewTitle.setVisibility(View.GONE);
                tvPreviewText.setVisibility(View.GONE);
            }

            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(ctx).loadInto(
                    ivAppIcon, bundle.packageName, bundleUserId, android.R.drawable.sym_def_app_icon);

            // Check if any notification in the bundle is favorited
            boolean isFavorite = false;
            for (NotificationEntity entity : bundle.notifications) {
                if (entity.isFavorite) {
                    isFavorite = true;
                    break;
                }
            }

            if (isFavorite) {
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
                card.setOnClickListener(v -> listener.onBundleClick(bundle));
                btnFavorite.setOnClickListener(v -> {
                    btnFavorite.animate()
                            .scaleX(1.3f)
                            .scaleY(1.3f)
                            .setDuration(120)
                            .withEndAction(() -> btnFavorite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start())
                            .start();
                    listener.onBundleFavoriteClick(bundle);
                });
            }
        }
    }

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
        private final TextView tvWorkBadge;
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
            tvWorkBadge = itemView.findViewById(R.id.tv_work_badge);
            ivNotificationImage = itemView.findViewById(R.id.iv_notification_image);
            btnFavorite = itemView.findViewById(R.id.btn_favorite);
        }

        void bind(NotificationEntity entity, OnItemClickListener listener, boolean showReadUnreadStatus) {
            Context context = itemView.getContext();

            // App icon loading with memory-safe AppIconLoader
            com.zygisk_enc.notivault.util.AppIconLoader.getInstance(context).loadInto(
                    ivAppIcon, entity.packageName, entity.userId, android.R.drawable.sym_def_app_icon);

            tvAppName.setText(entity.appName);

            boolean isWork = com.zygisk_enc.notivault.util.ProfileUtil.isWorkProfile(context, entity.userId);
            if (tvWorkBadge != null) {
                tvWorkBadge.setVisibility(isWork ? View.VISIBLE : View.GONE);
            }

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
                                    
                                    mainHandler.post(() -> {
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
