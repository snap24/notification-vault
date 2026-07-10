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

    @Query("SELECT * FROM toasts ORDER BY timestamp DESC")
    LiveData<List<ToastEntity>> getAllToasts();

    @Query("SELECT * FROM toasts ORDER BY timestamp DESC")
    List<ToastEntity> getAllToastsSync();

    @Query("DELETE FROM toasts")
    void deleteAll();

    @Query("SELECT MIN(timestamp) FROM toasts")
    androidx.lifecycle.LiveData<Long> getOldestTimestamp();
}
