package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.net.Uri;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BackupUtil {

    public interface BackupCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    public static void exportBackup(Context context, Uri fileUri, BackupCallback callback) {
        new Thread(() -> {
            try {
                List<NotificationEntity> notifications = AppDatabase.getInstance(context)
                        .notificationDao().getAllNotificationsSync();

                JSONArray jsonArray = new JSONArray();
                for (NotificationEntity notif : notifications) {
                    JSONObject obj = new JSONObject();
                    obj.put("packageName", notif.packageName);
                    obj.put("appName", notif.appName);
                    obj.put("title", notif.title);
                    obj.put("text", notif.text);
                    obj.put("bigText", notif.bigText != null ? notif.bigText : JSONObject.NULL);
                    obj.put("timestamp", notif.timestamp);
                    obj.put("isRead", notif.isRead ? 1 : 0);
                    obj.put("isFavorite", notif.isFavorite ? 1 : 0);
                    jsonArray.put(obj);
                }

                String jsonString = jsonArray.toString(2);

                try (OutputStream os = context.getContentResolver().openOutputStream(fileUri)) {
                    if (os != null) {
                        os.write(jsonString.getBytes(StandardCharsets.UTF_8));
                        callback.onSuccess();
                    } else {
                        callback.onFailure(new Exception("Output stream is null"));
                    }
                }
            } catch (Exception e) {
                callback.onFailure(e);
            }
        }).start();
    }

    public static void importBackup(Context context, Uri fileUri, BackupCallback callback) {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (InputStream is = context.getContentResolver().openInputStream(fileUri);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONArray jsonArray = new JSONArray(sb.toString());
                List<NotificationEntity> notifications = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    NotificationEntity notif = new NotificationEntity(
                            obj.optString("packageName", "unknown"),
                            obj.optString("appName", "Unknown App"),
                            obj.optString("title", ""),
                            obj.optString("text", ""),
                            obj.isNull("bigText") ? null : obj.optString("bigText"),
                            obj.optLong("timestamp", System.currentTimeMillis())
                    );
                    notif.isRead = obj.optInt("isRead", 0) == 1;
                    notif.isFavorite = obj.optInt("isFavorite", 0) == 1;
                    notifications.add(notif);
                }

                // Insert into database
                for (NotificationEntity notif : notifications) {
                    AppDatabase.getInstance(context).notificationDao().insert(notif);
                }
                callback.onSuccess();
            } catch (Exception e) {
                callback.onFailure(e);
            }
        }).start();
    }
}
