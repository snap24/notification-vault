package com.zygisk_enc.notivault.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SearchTokenDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<SearchTokenEntity> tokens);

    @Query("DELETE FROM search_tokens WHERE notificationId = :notificationId")
    void deleteByNotificationId(long notificationId);

    @Query("DELETE FROM search_tokens WHERE notificationId IN (:notificationIds)")
    void deleteByNotificationIds(List<Long> notificationIds);

    @Query("DELETE FROM search_tokens WHERE notificationId IN (SELECT id FROM notifications WHERE isFavorite = 0)")
    void deleteSearchTokensForNonFavorites();

    @Query("DELETE FROM search_tokens")
    void deleteAll();

    @Query("SELECT DISTINCT notificationId FROM search_tokens WHERE tokenHash = :tokenHash")
    List<Long> getNotificationIdsForToken(long tokenHash);
}
