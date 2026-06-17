package com.zygisk_enc.notivault.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(NotificationEntity notification);

    @Delete
    void delete(NotificationEntity notification);

    @Query("DELETE FROM notifications WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM notifications")
    void deleteAll();

    @Query("DELETE FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0")
    void deleteOlderThan(long timestamp);

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    LiveData<List<NotificationEntity>> getAllNotifications();

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    List<NotificationEntity> getAllNotificationsSync();

    @Query("SELECT * FROM notifications WHERE packageName = :packageName ORDER BY timestamp DESC")
    LiveData<List<NotificationEntity>> getNotificationsByPackage(String packageName);

    @Query("SELECT * FROM notifications WHERE (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR appName LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    LiveData<List<NotificationEntity>> searchNotifications(String query);

    @Query("SELECT packageName, appName, COUNT(*) as count FROM notifications GROUP BY packageName ORDER BY count DESC")
    LiveData<List<AppSummary>> getAppSummaries();

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    void markAsRead(long id);

    @Query("UPDATE notifications SET isFavorite = :isFavorite WHERE id = :id")
    void setFavorite(long id, boolean isFavorite);

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    LiveData<Integer> getUnreadCount();

    @Query("SELECT COUNT(*) FROM notifications WHERE timestamp >= :startTimestamp")
    LiveData<Integer> getCountSince(long startTimestamp);

    @Query("SELECT packageName, appName, COUNT(*) as count FROM notifications WHERE timestamp >= :startTimestamp GROUP BY packageName ORDER BY count DESC LIMIT :limit")
    LiveData<List<AppSummary>> getTopAppsSince(long startTimestamp, int limit);

    @Query("SELECT * FROM notifications WHERE isFavorite = 1 ORDER BY timestamp DESC")
    LiveData<List<NotificationEntity>> getFavorites();

    @Query("SELECT * FROM notifications WHERE timestamp >= :startTimestamp ORDER BY timestamp DESC")
    LiveData<List<NotificationEntity>> getNotificationsSince(long startTimestamp);

    @Query("SELECT MIN(timestamp) FROM notifications")
    LiveData<Long> getOldestTimestamp();
}
