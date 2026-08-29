package com.zygisk_enc.notivault.database;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(
    tableName = "search_tokens",
    primaryKeys = {"tokenHash", "notificationId"},
    foreignKeys = @ForeignKey(
        entity = NotificationEntity.class,
        parentColumns = "id",
        childColumns = "notificationId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {
        @Index(value = {"tokenHash"}),
        @Index(value = {"notificationId"})
    }
)
public class SearchTokenEntity {

    public long tokenHash;

    public long notificationId;

    public SearchTokenEntity(long tokenHash, long notificationId) {
        this.tokenHash = tokenHash;
        this.notificationId = notificationId;
    }
}
