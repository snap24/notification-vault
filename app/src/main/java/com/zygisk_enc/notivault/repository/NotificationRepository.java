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

    private final Application application;
    private final NotificationDao dao;
    private final AppRuleDao ruleDao;
    private final ExecutorService executor;

    public NotificationRepository(Application application) {
        this.application = application;
        AppDatabase db = AppDatabase.getInstance(application);
        dao = db.notificationDao();
        ruleDao = db.appRuleDao();
        executor = Executors.newSingleThreadExecutor();
    }

    public void insert(NotificationEntity entity) {
        executor.execute(() -> {
            dao.insert(entity);
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    public void delete(NotificationEntity entity) {
        executor.execute(() -> {
            deleteNotificationImage(entity.imagePath);
            dao.delete(entity);
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    public void deleteById(long id) {
        executor.execute(() -> {
            NotificationEntity entity = dao.getNotificationByIdSync(id);
            if (entity != null) {
                deleteNotificationImage(entity.imagePath);
                dao.delete(entity);
                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
            }
        });
    }

    public interface ProgressCallback {
        void onProgress(int progress);
        void onComplete();
    }

    public void deleteAll() {
        deleteAll(null);
    }

    public void deleteAll(ProgressCallback callback) {
        executor.execute(() -> {
            try {
                if (callback != null) callback.onProgress(0);
                List<NotificationEntity> list = dao.getAllNotificationsSync();
                if (list != null && !list.isEmpty()) {
                    int total = list.size();
                    for (int i = 0; i < total; i++) {
                        deleteNotificationImage(list.get(i).imagePath);
                        if (callback != null && (i % 25 == 0 || i == total - 1)) {
                            callback.onProgress((i * 100) / total);
                        }
                    }
                }
                dao.deleteAll();
                if (callback != null) callback.onProgress(100);
                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
            } finally {
                if (callback != null) callback.onComplete();
            }
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
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    public void deleteOlderThanForPackages(long timestamp, List<String> packages) {
        executor.execute(() -> {
            if (packages == null || packages.isEmpty()) return;
            List<String> imagePaths = dao.getOldImagePathsForPackages(timestamp, packages);
            if (imagePaths != null) {
                for (String path : imagePaths) {
                    deleteNotificationImage(path);
                }
            }
            dao.deleteOlderThanForPackages(timestamp, packages);
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    public void deleteByDateRange(long startTime, long endTime) {
        deleteByDateRange(startTime, endTime, null);
    }

    public void deleteByDateRange(long startTime, long endTime, ProgressCallback callback) {
        executor.execute(() -> {
            try {
                if (callback != null) callback.onProgress(0);
                List<String> imagePaths = dao.getOldImagePaths(endTime);
                if (imagePaths != null && !imagePaths.isEmpty()) {
                    int total = imagePaths.size();
                    for (int i = 0; i < total; i++) {
                        deleteNotificationImage(imagePaths.get(i));
                        if (callback != null && (i % 20 == 0 || i == total - 1)) {
                            callback.onProgress((i * 100) / total);
                        }
                    }
                }
                dao.deleteByDateRange(startTime, endTime);
                if (callback != null) callback.onProgress(100);
                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
            } finally {
                if (callback != null) callback.onComplete();
            }
        });
    }

    public void deleteByDays(java.util.Collection<Long> daysUtc) {
        executor.execute(() -> {
            for (Long dayUtc : daysUtc) {
                java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                utc.setTimeInMillis(dayUtc);
                java.util.Calendar local = java.util.Calendar.getInstance();
                local.set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH), utc.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0);
                local.set(java.util.Calendar.MILLISECOND, 0);
                long start = local.getTimeInMillis();
                local.add(java.util.Calendar.DAY_OF_YEAR, 1);
                long end = local.getTimeInMillis() - 1;
                dao.deleteByDateRange(start, end);
            }
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    private void deleteNotificationImage(String imagePath) {
        if (imagePath != null && !imagePath.isEmpty()) {
            String[] paths = imagePath.split("\\|");
            for (String p : paths) {
                if (p != null && !p.trim().isEmpty()) {
                    try {
                        java.io.File file = new java.io.File(p.trim());
                        if (file.exists()) {
                            file.delete();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public void deleteByPackages(java.util.List<String> packages) {
        deleteByPackages(packages, null);
    }

    public void deleteByPackages(java.util.List<String> packages, ProgressCallback callback) {
        executor.execute(() -> {
            try {
                if (callback != null) callback.onProgress(0);
                java.util.List<String> imagePaths = dao.getImagePathsForPackages(packages);
                if (imagePaths != null && !imagePaths.isEmpty()) {
                    int total = imagePaths.size();
                    for (int i = 0; i < total; i++) {
                        deleteNotificationImage(imagePaths.get(i));
                        if (callback != null && (i % 20 == 0 || i == total - 1)) {
                            callback.onProgress((i * 100) / total);
                        }
                    }
                }
                dao.deleteByPackages(packages);
                if (callback != null) callback.onProgress(100);
                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (callback != null) callback.onComplete();
            }
        });
    }

    public void markAsRead(long id) {
        executor.execute(() -> dao.markAsRead(id));
    }

    public void setFavorite(long id, boolean isFavorite) {
        executor.execute(() -> dao.setFavorite(id, isFavorite));
    }

    public LiveData<List<NotificationEntity>> getAllNotifications(int limit, Long dateStart, Long dateEnd) {
        return dao.getAllNotifications(limit, dateStart, dateEnd);
    }

    public LiveData<List<NotificationEntity>> getNotificationsByPackage(String packageName, int limit, Long dateStart, Long dateEnd) {
        return dao.getNotificationsByPackage(packageName, limit, dateStart, dateEnd);
    }

    public LiveData<List<NotificationEntity>> searchNotifications(String query) {
        return dao.searchNotifications(query);
    }

    public LiveData<List<NotificationEntity>> searchByTokenHash(long tokenHash, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd) {
        return dao.searchByTokenHash(tokenHash, packageName, isFavoriteOnly, limit, dateStart, dateEnd);
    }

    public LiveData<List<NotificationEntity>> searchByTokenHashes(List<Long> tokenHashes, int tokenCount, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd) {
        return dao.searchByTokenHashes(tokenHashes, tokenCount, packageName, isFavoriteOnly, limit, dateStart, dateEnd);
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

    public LiveData<List<NotificationEntity>> getFavorites(int limit, Long dateStart, Long dateEnd) {
        return dao.getFavorites(limit, dateStart, dateEnd);
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
