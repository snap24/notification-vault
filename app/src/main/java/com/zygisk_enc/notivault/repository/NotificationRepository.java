package com.zygisk_enc.notivault.repository;

import android.app.Application;
import androidx.lifecycle.LiveData;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.database.NotificationDao;
import com.zygisk_enc.notivault.database.NotificationEntity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationRepository {

    private final NotificationDao dao;
    private final ExecutorService executor;

    public NotificationRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        dao = db.notificationDao();
        executor = Executors.newSingleThreadExecutor();
    }

    public void insert(NotificationEntity entity) {
        executor.execute(() -> dao.insert(entity));
    }

    public void delete(NotificationEntity entity) {
        executor.execute(() -> dao.delete(entity));
    }

    public void deleteById(long id) {
        executor.execute(() -> dao.deleteById(id));
    }

    public void deleteAll() {
        executor.execute(dao::deleteAll);
    }

    public void deleteOlderThan(long timestamp) {
        executor.execute(() -> dao.deleteOlderThan(timestamp));
    }

    public void markAsRead(long id) {
        executor.execute(() -> dao.markAsRead(id));
    }

    public void setFavorite(long id, boolean isFavorite) {
        executor.execute(() -> dao.setFavorite(id, isFavorite));
    }

    public LiveData<List<NotificationEntity>> getAllNotifications() {
        return dao.getAllNotifications();
    }

    public LiveData<List<NotificationEntity>> getNotificationsByPackage(String packageName) {
        return dao.getNotificationsByPackage(packageName);
    }

    public LiveData<List<NotificationEntity>> searchNotifications(String query) {
        return dao.searchNotifications(query);
    }

    public LiveData<List<AppSummary>> getAppSummaries() {
        return dao.getAppSummaries();
    }

    public LiveData<Integer> getUnreadCount() {
        return dao.getUnreadCount();
    }

    public LiveData<Integer> getCountSince(long startTimestamp) {
        return dao.getCountSince(startTimestamp);
    }

    public LiveData<List<AppSummary>> getTopAppsSince(long startTimestamp, int limit) {
        return dao.getTopAppsSince(startTimestamp, limit);
    }

    public LiveData<List<NotificationEntity>> getFavorites() {
        return dao.getFavorites();
    }

    public LiveData<List<NotificationEntity>> getNotificationsSince(long startTimestamp) {
        return dao.getNotificationsSince(startTimestamp);
    }

    public LiveData<Long> getOldestTimestamp() {
        return dao.getOldestTimestamp();
    }
}
