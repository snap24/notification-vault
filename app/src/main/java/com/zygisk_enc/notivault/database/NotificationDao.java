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
    long insert(NotificationEntity notification);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertAll(List<NotificationEntity> notifications);

    @Delete
    void delete(NotificationEntity notification);

    @Query("DELETE FROM notifications WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM notifications WHERE isFavorite = 0")
    void deleteAll();

    @Query("DELETE FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0")
    void deleteOlderThan(long timestamp);

    @Query("DELETE FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0 AND packageName NOT IN (:excludedPackages)")
    void deleteOlderThanExcludingPackages(long timestamp, List<String> excludedPackages);

    @Query("SELECT imagePath FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0 AND packageName NOT IN (:excludedPackages) AND imagePath IS NOT NULL")
    List<String> getOldImagePathsExcludingPackages(long timestamp, List<String> excludedPackages);

    @Query("DELETE FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0 AND packageName = :packageName")
    void deleteOlderThanForPackage(long timestamp, String packageName);

    @Query("SELECT imagePath FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0 AND packageName = :packageName AND imagePath IS NOT NULL")
    List<String> getOldImagePathsForPackage(long timestamp, String packageName);

    @Query("DELETE FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0 AND packageName IN (:packages)")
    void deleteOlderThanForPackages(long timestamp, List<String> packages);

    @Query("SELECT imagePath FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0 AND packageName IN (:packages) AND imagePath IS NOT NULL")
    List<String> getOldImagePathsForPackages(long timestamp, List<String> packages);

    @Query("SELECT imagePath FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0 AND imagePath IS NOT NULL")
    List<String> getOldImagePaths(long timestamp);

    @Query("DELETE FROM notifications WHERE timestamp >= :startTime AND timestamp <= :endTime AND isFavorite = 0")
    void deleteByDateRange(long startTime, long endTime);

    @Query("DELETE FROM notifications WHERE packageName IN (:packages)")
    void deleteByPackages(List<String> packages);

    @Query("SELECT imagePath FROM notifications WHERE packageName IN (:packages) AND imagePath IS NOT NULL")
    List<String> getImagePathsForPackages(List<String> packages);

    @Query("SELECT * FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND (:dateStart IS NULL OR :dateEnd IS NULL OR (timestamp >= :dateStart AND timestamp <= :dateEnd)) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    LiveData<List<NotificationEntity>> getAllNotifications(int limit, Long dateStart, Long dateEnd, int profileMode);

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC, id DESC")
    List<NotificationEntity> getAllNotificationsSync();

    @Query("SELECT * FROM notifications " +
           "WHERE packageName = :packageName " +
           "AND ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND (:dateStart IS NULL OR :dateEnd IS NULL OR (timestamp >= :dateStart AND timestamp <= :dateEnd)) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    LiveData<List<NotificationEntity>> getNotificationsByPackage(String packageName, int limit, Long dateStart, Long dateEnd, int profileMode);

    @Query("SELECT * FROM notifications WHERE (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR appName LIKE '%' || :query || '%') ORDER BY timestamp DESC, id DESC")
    LiveData<List<NotificationEntity>> searchNotifications(String query);

    @Query("SELECT * FROM notifications " +
           "WHERE id IN (SELECT notificationId FROM search_tokens WHERE tokenHash = :tokenHash) " +
           "AND (:packageName IS NULL OR packageName = :packageName) " +
           "AND (:isFavoriteOnly = 0 OR isFavorite = 1) " +
           "AND ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND (:dateStart IS NULL OR :dateEnd IS NULL OR (timestamp >= :dateStart AND timestamp <= :dateEnd)) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    LiveData<List<NotificationEntity>> searchByTokenHash(long tokenHash, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd, int profileMode);

    @Query("SELECT * FROM notifications " +
           "WHERE id IN (" +
           "    SELECT notificationId FROM search_tokens " +
           "    WHERE tokenHash IN (:tokenHashes) " +
           "    GROUP BY notificationId " +
           "    HAVING COUNT(DISTINCT tokenHash) >= :tokenCount" +
           ") " +
           "AND (:packageName IS NULL OR packageName = :packageName) " +
           "AND (:isFavoriteOnly = 0 OR isFavorite = 1) " +
           "AND ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND (:dateStart IS NULL OR :dateEnd IS NULL OR (timestamp >= :dateStart AND timestamp <= :dateEnd)) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    LiveData<List<NotificationEntity>> searchByTokenHashes(List<Long> tokenHashes, int tokenCount, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd, int profileMode);

    @Query("SELECT * FROM notifications WHERE id NOT IN (SELECT DISTINCT notificationId FROM search_tokens) ORDER BY id DESC LIMIT :limit")
    List<NotificationEntity> getUnindexedNotifications(int limit);

    @Query("SELECT packageName, appName, COUNT(*) as count, userId FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "GROUP BY packageName, userId ORDER BY count DESC")
    LiveData<List<AppSummary>> getAppSummaries(int profileMode);

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    void markAsRead(long id);

    @Query("UPDATE notifications SET isFavorite = :isFavorite WHERE id = :id")
    void setFavorite(long id, boolean isFavorite);

    @Query("SELECT COUNT(*) FROM notifications " +
           "WHERE isRead = 0 " +
           "AND ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0))")
    LiveData<Integer> getUnreadCount(int profileMode);

    @Query("SELECT COUNT(*) FROM notifications WHERE timestamp >= :startTimestamp")
    LiveData<Integer> getCountSince(long startTimestamp);

    @Query("SELECT COUNT(*) FROM notifications WHERE timestamp >= :startTimestamp")
    int getCountSinceSync(long startTimestamp);

    @Query("SELECT COUNT(*) FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp")
    int getCountSinceSync(long startTimestamp, int profileMode);

    @Query("SELECT COUNT(*) FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp AND timestamp <= :endTimestamp")
    int getCountBetweenSync(long startTimestamp, long endTimestamp, int profileMode);

    @Query("SELECT COUNT(*) FROM notifications " +
           "WHERE isFavorite = 1 " +
           "AND ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp AND timestamp <= :endTimestamp")
    int getFavoritesCountBetweenSync(long startTimestamp, long endTimestamp, int profileMode);

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC, id DESC LIMIT :limit")
    List<NotificationEntity> getRecentNotificationsSync(int limit);

    @Query("SELECT * FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    List<NotificationEntity> getRecentNotificationsSync(int limit, int profileMode);

    @Query("SELECT * FROM notifications WHERE packageName = :packageName ORDER BY timestamp DESC, id DESC LIMIT :limit")
    List<NotificationEntity> getRecentNotificationsByPackageSync(String packageName, int limit);

    @Query("SELECT * FROM notifications " +
           "WHERE packageName = :packageName " +
           "AND ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    List<NotificationEntity> getRecentNotificationsByPackageSync(String packageName, int limit, int profileMode);

    @Query("SELECT packageName, appName, COUNT(*) as count, userId FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "GROUP BY packageName, userId ORDER BY count DESC")
    List<AppSummary> getAppSummariesSync(int profileMode);

    @Query("SELECT packageName, appName, COUNT(*) as count, userId FROM notifications GROUP BY packageName, userId ORDER BY count DESC")
    List<AppSummary> getAppSummariesSync();

    @Query("SELECT packageName, appName, COUNT(*) as count, userId FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp AND timestamp <= :endTimestamp " +
           "GROUP BY packageName, userId ORDER BY count DESC LIMIT :limit")
    List<AppSummary> getTopAppsBetweenSync(long startTimestamp, long endTimestamp, int limit, int profileMode);

    @Query("SELECT timestamp FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp AND timestamp <= :endTimestamp ORDER BY timestamp ASC")
    List<Long> getTimestampsBetweenSync(long startTimestamp, long endTimestamp, int profileMode);

    @Query("SELECT packageName, appName, COUNT(*) as count, userId FROM notifications " +
           "WHERE ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND timestamp >= :startTimestamp GROUP BY packageName, userId ORDER BY count DESC LIMIT :limit")
    LiveData<List<AppSummary>> getTopAppsSince(long startTimestamp, int limit, int profileMode);

    @Query("SELECT * FROM notifications " +
           "WHERE isFavorite = 1 " +
           "AND ((:profileMode = -1) OR (:profileMode = 0 AND userId = 0) OR (:profileMode = 1 AND userId != 0)) " +
           "AND (:dateStart IS NULL OR :dateEnd IS NULL OR (timestamp >= :dateStart AND timestamp <= :dateEnd)) " +
           "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    LiveData<List<NotificationEntity>> getFavorites(int limit, Long dateStart, Long dateEnd, int profileMode);

    @Query("SELECT * FROM notifications WHERE timestamp >= :startTimestamp ORDER BY timestamp DESC, id DESC")
    LiveData<List<NotificationEntity>> getNotificationsSince(long startTimestamp);

    @Query("SELECT MIN(timestamp) FROM notifications")
    LiveData<Long> getOldestTimestamp();

    @Query("SELECT * FROM notifications WHERE id = :id LIMIT 1")
    NotificationEntity getNotificationByIdSync(long id);

    @Query("SELECT * FROM notifications WHERE timestamp < :timestamp AND isFavorite = 0")
    List<NotificationEntity> getNotificationsOlderThanSync(long timestamp);

    @Query("SELECT * FROM notifications WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT 1")
    NotificationEntity getLastNotificationForPackage(String packageName);

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC LIMIT 1")
    NotificationEntity getLatestNotificationSync();

    @Query("UPDATE notifications SET duplicateCount = :count, timestamp = :timestamp, isRead = 0 WHERE id = :id")
    void updateDuplicate(long id, int count, long timestamp);

    @Query("UPDATE notifications SET text = :text, bigText = :bigText, timestamp = :timestamp, imagePath = :imagePath, duplicateCount = :count, isRead = 0 WHERE id = :id")
    void updatePhotoSession(long id, String text, String bigText, long timestamp, String imagePath, int count);

    public static class BundleScanItem {
        public long id;
        public String packageName;
        public String bundleId;
        public long timestamp;
    }

    @Query("SELECT id, packageName, bundleId, timestamp FROM notifications ORDER BY timestamp DESC, id DESC")
    List<BundleScanItem> getAllBundleScanItemsSync();

    @Query("UPDATE notifications SET bundleId = :bundleId WHERE id IN (:ids)")
    void updateBundleIdForIds(List<Long> ids, String bundleId);

    @Query("UPDATE notifications SET bundleId = NULL WHERE bundleId IS NOT NULL")
    void clearAllBundleIds();

    @Query("SELECT * FROM notifications WHERE bundleId = :bundleId ORDER BY timestamp DESC, id DESC")
    List<NotificationEntity> getNotificationsByBundleIdSync(String bundleId);
}
