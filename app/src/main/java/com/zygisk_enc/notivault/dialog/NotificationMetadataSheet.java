package com.zygisk_enc.notivault.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.util.AppIconLoader;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import com.zygisk_enc.notivault.util.ProfileUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class NotificationMetadataSheet {

    private NotificationMetadataSheet() {}

    public static void show(@NonNull Context context, @NonNull NotificationEntity entity) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_notification_metadata, null);
        dialog.setContentView(view);

        ImageView ivIcon = view.findViewById(R.id.iv_meta_icon);
        TextView tvAppName = view.findViewById(R.id.tv_meta_app_name);
        TextView tvPkgName = view.findViewById(R.id.tv_meta_package_name);
        TextView tvProfileBadge = view.findViewById(R.id.tv_meta_profile_badge);
        View btnCopyJson = view.findViewById(R.id.btn_copy_json);

        // 2x2 Quick Specs Boxes
        TextView tvBoxSpace = view.findViewById(R.id.tv_meta_box_space);
        TextView tvBoxDbId = view.findViewById(R.id.tv_meta_box_db_id);
        TextView tvBoxPriority = view.findViewById(R.id.tv_meta_box_priority);
        TextView tvBoxDuplicates = view.findViewById(R.id.tv_meta_box_duplicates);

        // Identity card
        TextView tvRowPkg = view.findViewById(R.id.tv_meta_row_pkg);
        TextView tvRowUserId = view.findViewById(R.id.tv_meta_row_user_id);
        TextView tvRowBundleId = view.findViewById(R.id.tv_meta_row_bundle_id);

        // Timing card
        TextView tvRowTimeExact = view.findViewById(R.id.tv_meta_row_time_exact);
        TextView tvRowEpochMs = view.findViewById(R.id.tv_meta_row_epoch_ms);

        // System attributes card
        TextView tvRowChannel = view.findViewById(R.id.tv_meta_row_channel);
        TextView tvRowSystemId = view.findViewById(R.id.tv_meta_row_system_id);
        TextView tvRowCategory = view.findViewById(R.id.tv_meta_row_category);
        TextView tvRowVisibility = view.findViewById(R.id.tv_meta_row_visibility);
        TextView tvRowActions = view.findViewById(R.id.tv_meta_row_actions);
        TextView tvRowFlags = view.findViewById(R.id.tv_meta_row_flags);

        // Security & Content card
        TextView tvRowEncryption = view.findViewById(R.id.tv_meta_row_encryption);
        TextView tvRowChars = view.findViewById(R.id.tv_meta_row_chars);
        TextView tvRowMedia = view.findViewById(R.id.tv_meta_row_media);

        // Header info
        AppIconLoader.getInstance(context).loadInto(ivIcon, entity.packageName, entity.userId, R.drawable.ic_notification);
        tvAppName.setText(entity.appName != null && !entity.appName.isEmpty() ? entity.appName : entity.packageName);
        tvPkgName.setText(entity.packageName);

        boolean isWork = ProfileUtil.isWorkProfile(context, entity.userId);
        if (isWork) {
            tvProfileBadge.setText(R.string.badge_work);
            tvProfileBadge.setVisibility(View.VISIBLE);
        } else {
            tvProfileBadge.setText(R.string.badge_personal);
            tvProfileBadge.setVisibility(View.VISIBLE);
        }

        // Quick Specs Grid
        String spaceShort = isWork ? ("Work (" + entity.userId + ")") : ("Personal (" + entity.userId + ")");
        if (tvBoxSpace != null) tvBoxSpace.setText(spaceShort);
        if (tvBoxDbId != null) tvBoxDbId.setText("#" + entity.id);
        if (tvBoxDuplicates != null) tvBoxDuplicates.setText(entity.duplicateCount + (entity.duplicateCount == 1 ? " event" : " events"));

        // Section 1: Identity
        tvRowPkg.setText(context.getString(R.string.meta_label_package, entity.packageName));
        String spaceLabel = isWork ? context.getString(R.string.meta_space_work, entity.userId) : context.getString(R.string.meta_space_personal, entity.userId);
        tvRowUserId.setText(context.getString(R.string.meta_label_user_id, spaceLabel));
        tvRowBundleId.setText(context.getString(R.string.meta_label_bundle_id, entity.bundleId != null && !entity.bundleId.isEmpty() ? entity.bundleId : context.getString(R.string.meta_none)));

        // Section 2: Timing
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault());
        tvRowTimeExact.setText(context.getString(R.string.meta_label_exact_time, sdf.format(new Date(entity.timestamp))));
        tvRowEpochMs.setText(context.getString(R.string.meta_label_epoch_ms, String.valueOf(entity.timestamp)));

        // Decrypted texts for content length
        String decTitle = EncryptionHelper.decrypt(entity.title);
        String decText = EncryptionHelper.decrypt(entity.text);
        String decBig = entity.bigText != null ? EncryptionHelper.decrypt(entity.bigText) : "";

        // Section 3: System Attributes
        JSONObject metaObj = null;
        if (entity.metadata != null && !entity.metadata.trim().isEmpty()) {
            try {
                metaObj = new JSONObject(entity.metadata);
            } catch (Exception ignored) {}
        }

        String priority = "Default (0)";
        if (metaObj != null) {
            String channelId = metaObj.optString("channelId", context.getString(R.string.meta_none));
            tvRowChannel.setText(context.getString(R.string.meta_label_channel_id, channelId));

            int notifId = metaObj.optInt("notificationId", 0);
            String tag = metaObj.optString("tag", null);
            String tagStr = (tag == null || tag.equals("null")) ? context.getString(R.string.meta_none) : tag;
            tvRowSystemId.setText(context.getString(R.string.meta_label_system_id, notifId, tagStr));

            String category = metaObj.optString("category", context.getString(R.string.meta_none));
            tvRowCategory.setText(context.getString(R.string.meta_label_category, category));

            priority = metaObj.optString("priority", "Default (0)");

            String visibility = metaObj.optString("visibility", "Default");
            boolean clearable = metaObj.optBoolean("isClearable", true);
            boolean ongoing = metaObj.optBoolean("isOngoing", false);
            String clearableStr = clearable ? context.getString(R.string.meta_yes) : context.getString(R.string.meta_no);
            String ongoingStr = ongoing ? context.getString(R.string.meta_yes) : context.getString(R.string.meta_no);
            tvRowVisibility.setText(context.getString(R.string.meta_label_visibility_ongoing, visibility, clearableStr, ongoingStr));

            int actionsCount = metaObj.optInt("actionsCount", 0);
            JSONArray actionTitles = metaObj.optJSONArray("actionTitles");
            StringBuilder actionsSb = new StringBuilder(String.valueOf(actionsCount));
            if (actionTitles != null && actionTitles.length() > 0) {
                actionsSb.append(" (");
                for (int i = 0; i < actionTitles.length(); i++) {
                    if (i > 0) actionsSb.append(", ");
                    actionsSb.append(actionTitles.optString(i));
                }
                actionsSb.append(")");
            }
            tvRowActions.setText(context.getString(R.string.meta_label_actions, actionsSb.toString()));

            JSONArray flagsArr = metaObj.optJSONArray("flags");
            if (flagsArr != null && flagsArr.length() > 0) {
                StringBuilder flagsSb = new StringBuilder();
                for (int i = 0; i < flagsArr.length(); i++) {
                    if (i > 0) flagsSb.append(", ");
                    flagsSb.append(flagsArr.optString(i));
                }
                tvRowFlags.setText(context.getString(R.string.meta_label_flags, flagsSb.toString()));
            } else {
                tvRowFlags.setText(context.getString(R.string.meta_label_flags, context.getString(R.string.meta_none)));
            }
        } else {
            tvRowChannel.setText(context.getString(R.string.meta_label_channel_id, context.getString(R.string.meta_not_recorded)));
            tvRowSystemId.setText(context.getString(R.string.meta_label_system_id_simple));
            tvRowCategory.setText(context.getString(R.string.meta_label_category, context.getString(R.string.meta_none)));
            tvRowVisibility.setText(context.getString(R.string.meta_label_visibility_ongoing, "Default", context.getString(R.string.meta_yes), context.getString(R.string.meta_no)));
            tvRowActions.setText(context.getString(R.string.meta_label_actions, "0"));
            tvRowFlags.setText(context.getString(R.string.meta_label_flags, context.getString(R.string.meta_not_recorded)));
        }

        if (tvBoxPriority != null) tvBoxPriority.setText(priority);

        // Section 4: Security & Content
        tvRowEncryption.setText(R.string.meta_encryption_value);
        int titleLen = decTitle != null ? decTitle.length() : 0;
        int textLen = decText != null ? decText.length() : 0;
        int bigLen = decBig != null ? decBig.length() : 0;
        tvRowChars.setText(context.getString(R.string.meta_label_content_length, titleLen, Math.max(textLen, bigLen)));

        if (entity.imagePath != null && !entity.imagePath.trim().isEmpty()) {
            String[] parts = entity.imagePath.split("\\|");
            long totalBytes = 0;
            int count = 0;
            for (String p : parts) {
                if (p != null && !p.trim().isEmpty()) {
                    File f = new File(p.trim());
                    if (f.exists()) {
                        totalBytes += f.length();
                        count++;
                    }
                }
            }
            long totalKb = totalBytes / 1024;
            tvRowMedia.setText(context.getString(R.string.meta_label_media_details, count, totalKb));
        } else {
            tvRowMedia.setText(context.getString(R.string.meta_label_media_none));
        }

        // Copy Full JSON
        final JSONObject finalMeta = metaObj;
        btnCopyJson.setOnClickListener(v -> {
            try {
                JSONObject fullJson = new JSONObject();
                fullJson.put("databaseId", entity.id);
                fullJson.put("packageName", entity.packageName);
                fullJson.put("appName", entity.appName);
                fullJson.put("userId", entity.userId);
                fullJson.put("isWorkProfile", isWork);
                fullJson.put("bundleId", entity.bundleId);
                fullJson.put("timestamp", entity.timestamp);
                fullJson.put("formattedTime", sdf.format(new Date(entity.timestamp)));
                fullJson.put("duplicateCount", entity.duplicateCount);
                fullJson.put("titleLength", titleLen);
                fullJson.put("textLength", Math.max(textLen, bigLen));
                fullJson.put("encryption", "AES-256-GCM");

                if (finalMeta != null) {
                    fullJson.put("systemAttributes", finalMeta);
                }

                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("notification_metadata", fullJson.toString(2)));
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        dialog.show();
    }
}
