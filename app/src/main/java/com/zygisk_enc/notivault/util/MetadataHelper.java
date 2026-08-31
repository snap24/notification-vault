package com.zygisk_enc.notivault.util;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class MetadataHelper {

    private MetadataHelper() {}

    /**
     * Extracts all extended Android system metadata from StatusBarNotification into a structured JSON string.
     */
    @Nullable
    public static String extractJson(@NonNull StatusBarNotification sbn, @NonNull Notification notification, @NonNull Context context) {
        try {
            JSONObject obj = new JSONObject();

            obj.put("notificationId", sbn.getId());
            if (sbn.getTag() != null) obj.put("tag", sbn.getTag());
            if (sbn.getKey() != null) obj.put("key", sbn.getKey());
            if (sbn.getGroupKey() != null) obj.put("groupKey", sbn.getGroupKey());
            if (sbn.getOverrideGroupKey() != null) obj.put("overrideGroupKey", sbn.getOverrideGroupKey());
            obj.put("postTime", sbn.getPostTime());
            obj.put("isClearable", sbn.isClearable());
            obj.put("isOngoing", sbn.isOngoing());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (notification.getChannelId() != null) {
                    obj.put("channelId", notification.getChannelId());
                }
                if (notification.getShortcutId() != null) {
                    obj.put("shortcutId", notification.getShortcutId());
                }
            }

            if (notification.category != null) {
                obj.put("category", notification.category);
            }

            obj.put("priority", parsePriority(notification.priority));
            obj.put("priorityRaw", notification.priority);
            obj.put("flagsRaw", notification.flags);

            List<String> flagNames = parseFlags(notification.flags);
            JSONArray flagsArray = new JSONArray();
            for (String f : flagNames) {
                flagsArray.put(f);
            }
            obj.put("flags", flagsArray);

            obj.put("visibility", parseVisibility(notification.visibility));
            if (notification.number > 0) {
                obj.put("badgeNumber", notification.number);
            }

            if (notification.actions != null) {
                obj.put("actionsCount", notification.actions.length);
                JSONArray actionsArray = new JSONArray();
                for (Notification.Action action : notification.actions) {
                    if (action != null && action.title != null) {
                        actionsArray.put(action.title.toString());
                    }
                }
                obj.put("actionTitles", actionsArray);
            } else {
                obj.put("actionsCount", 0);
            }

            return obj.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String parsePriority(int priority) {
        switch (priority) {
            case Notification.PRIORITY_MAX: return "Max (+2)";
            case Notification.PRIORITY_HIGH: return "High (+1)";
            case Notification.PRIORITY_DEFAULT: return "Default (0)";
            case Notification.PRIORITY_LOW: return "Low (-1)";
            case Notification.PRIORITY_MIN: return "Min (-2)";
            default: return String.valueOf(priority);
        }
    }

    private static String parseVisibility(int visibility) {
        switch (visibility) {
            case Notification.VISIBILITY_PUBLIC: return "Public";
            case Notification.VISIBILITY_PRIVATE: return "Private";
            case Notification.VISIBILITY_SECRET: return "Secret";
            default: return "Default";
        }
    }

    private static List<String> parseFlags(int flags) {
        List<String> list = new ArrayList<>();
        if ((flags & Notification.FLAG_ONGOING_EVENT) != 0) list.add("FLAG_ONGOING_EVENT");
        if ((flags & Notification.FLAG_AUTO_CANCEL) != 0) list.add("FLAG_AUTO_CANCEL");
        if ((flags & Notification.FLAG_NO_CLEAR) != 0) list.add("FLAG_NO_CLEAR");
        if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0) list.add("FLAG_GROUP_SUMMARY");
        if ((flags & Notification.FLAG_INSISTENT) != 0) list.add("FLAG_INSISTENT");
        if ((flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) list.add("FLAG_ONLY_ALERT_ONCE");
        if ((flags & Notification.FLAG_LOCAL_ONLY) != 0) list.add("FLAG_LOCAL_ONLY");
        if ((flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) list.add("FLAG_FOREGROUND_SERVICE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if ((flags & Notification.FLAG_BUBBLE) != 0) list.add("FLAG_BUBBLE");
        }
        return list;
    }
}
