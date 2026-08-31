package com.zygisk_enc.notivault.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

@Entity(
    tableName = "toasts",
    indices = {
        @androidx.room.Index("packageName"),
        @androidx.room.Index("timestamp"),
        @androidx.room.Index("userId")
    }
)
public class ToastEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String packageName;
    public String appName;
    public String text; // encrypted
    public long timestamp;
    public int duplicateCount;
    public int userId = 0;

    @Ignore
    public String decryptedText;

    public ToastEntity(String packageName, String appName, String text, long timestamp) {
        this.packageName = packageName;
        this.appName = appName;
        this.text = text;
        this.timestamp = timestamp;
        this.duplicateCount = 1;
        this.userId = 0;
    }
}
