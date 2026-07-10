package com.zygisk_enc.notivault.repository;

import android.app.Application;
import androidx.lifecycle.LiveData;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.database.NotificationDao;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.AppRuleDao;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationRepository {

    private final NotificationDao dao;
    private final AppRuleDao ruleDao;
    private final ExecutorService executor;

    public NotificationRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        dao = db.notificationDao();
        ruleDao = db.appRuleDao();
        executor = Executors.newSingleThreadExecutor();
    }

    public void insert(NotificationEntity entity) {
        executor.execute(() -> dao.insert(entity));
    }

    public void delete(NotificationEntity entity) {
        executor.execute(() -> {
            deleteNotificationImage(entity.imagePath);
            dao.delete(entity);
        });
    }

    public void deleteById(long id) {
        executor.execute(() -> {
            NotificationEntity entity = dao.getNotificationByIdSync(id);
            if (entity != null) {
                deleteNotificationImage(entity.imagePath);
                dao.delete(entity);
            }
        });
    }

    public void deleteAll() {
        executor.execute(() -> {
            List<NotificationEntity> list = dao.getAllNotificationsSync();
            if (list != null) {
                for (NotificationEntity entity : list) {
                    deleteNotificationImage(entity.imagePath);
                }
            }
            dao.deleteAll();
        });
    }

    public void deleteOlderThan(long timestamp) {
        executor.execute(() -> {
            List<NotificationEntity> list = dao.getNotificationsOlderThanSync(timestamp);
            if (list != null) {
                for (NotificationEntity entity : list) {
                    deleteNotificationImage(entity.imagePath);
                }
            }
            dao.deleteOlderThan(timestamp);
        });
    }

    private void deleteNotificationImage(String imagePath) {
        if (imagePath != null && !imagePath.isEmpty()) {
            try {
                java.io.File file = new java.io.File(imagePath);
                if (file.exists()) {
                    file.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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

    // App Rules Operations
    public void insertRule(AppRuleEntity rule) {
        executor.execute(() -> ruleDao.insert(rule));
    }

    public void deleteRule(AppRuleEntity rule) {
        executor.execute(() -> ruleDao.delete(rule));
    }

    public void deleteRuleByPackage(String packageName) {
        executor.execute(() -> ruleDao.deleteByPackage(packageName));
    }

    public LiveData<AppRuleEntity> getRule(String packageName) {
        return ruleDao.getRule(packageName);
    }

    public LiveData<List<AppRuleEntity>> getAllRules() {
        return ruleDao.getAllRules();
    }
}
