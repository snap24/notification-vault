package com.zygisk_enc.notivault.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "notifications",
    indices = {
        @androidx.room.Index("packageName"),
        @androidx.room.Index("timestamp")
    }
)
public class NotificationEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String packageName;
    public String appName;
    public String title;
    public String text;
    public String bigText;
    public long timestamp;
    public boolean isRead;
    public boolean isFavorite;
    public int duplicateCount;
    public String imagePath;

    @androidx.room.Ignore
    public String decryptedTitle = null;
    @androidx.room.Ignore
    public String decryptedText = null;
    @androidx.room.Ignore
    public String decryptedBigText = null;

    public NotificationEntity(String packageName, String appName, String title,
                              String text, String bigText, long timestamp) {
        this.packageName = packageName;
        this.appName = appName;
        this.title = title != null ? title : "";
        this.text = text != null ? text : "";
        this.bigText = bigText;
        this.timestamp = timestamp;
        this.isRead = false;
        this.isFavorite = false;
        this.duplicateCount = 1;
        this.imagePath = null;
    }
}
