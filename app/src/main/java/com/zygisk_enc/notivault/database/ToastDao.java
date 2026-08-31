package com.zygisk_enc.notivault.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ToastDao {
    @Insert
    void insert(ToastEntity toast);

    @Insert
    void insertAll(List<ToastEntity> toasts);

    @Query("SELECT * FROM toasts ORDER BY timestamp DESC")
    LiveData<List<ToastEntity>> getAllToasts();

    @Query("SELECT * FROM toasts " +
           "WHERE (:dateStart IS NULL OR :dateEnd IS NULL OR (timestamp >= :dateStart AND timestamp <= :dateEnd)) " +
           "AND (:packageName IS NULL OR packageName = :packageName) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    LiveData<List<ToastEntity>> getFilteredToasts(int limit, Long dateStart, Long dateEnd, String packageName);

    @Query("SELECT * FROM toasts ORDER BY timestamp DESC")
    List<ToastEntity> getAllToastsSync();

    @Query("DELETE FROM toasts")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM toasts WHERE timestamp >= :startTimestamp")
    int getCountSinceSync(long startTimestamp);

    @Query("SELECT COUNT(*) FROM toasts " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp")
    int getCountSinceSync(long startTimestamp, int profileMode);

    @Query("SELECT COUNT(*) FROM toasts WHERE timestamp >= :startTimestamp AND timestamp <= :endTimestamp")
    int getToastCountBetweenSync(long startTimestamp, long endTimestamp);

    @Query("SELECT COUNT(*) FROM toasts " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp AND timestamp <= :endTimestamp")
    int getToastCountBetweenSync(long startTimestamp, long endTimestamp, int profileMode);

    @Query("SELECT MIN(timestamp) FROM toasts")
    androidx.lifecycle.LiveData<Long> getOldestTimestamp();

    @Query("SELECT packageName, appName, COUNT(*) as count, userId FROM toasts GROUP BY packageName, userId ORDER BY count DESC")
    LiveData<List<AppSummary>> getToastAppSummaries();

    @Query("SELECT packageName, appName, COUNT(*) as count, userId FROM toasts GROUP BY packageName, userId ORDER BY count DESC")
    List<AppSummary> getToastAppSummariesSync();

    @Query("SELECT * FROM toasts WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT 1")
    ToastEntity getLastToastForPackageSync(String packageName);

    @Query("SELECT * FROM toasts ORDER BY timestamp DESC LIMIT 1")
    ToastEntity getLatestToastSync();

    @Query("UPDATE toasts SET duplicateCount = :count, timestamp = :timestamp WHERE id = :id")
    void updateDuplicate(long id, int count, long timestamp);
}
