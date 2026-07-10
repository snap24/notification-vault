package com.zygisk_enc.notivault.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

@Entity(
    tableName = "toasts",
    indices = {
        @androidx.room.Index("packageName"),
        @androidx.room.Index("timestamp")
    }
)
public class ToastEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String packageName;
    public String appName;
    public String text; // encrypted
    public long timestamp;

    @Ignore
    public String decryptedText;

    public ToastEntity(String packageName, String appName, String text, long timestamp) {
        this.packageName = packageName;
        this.appName = appName;
        this.text = text;
        this.timestamp = timestamp;
    }
}
