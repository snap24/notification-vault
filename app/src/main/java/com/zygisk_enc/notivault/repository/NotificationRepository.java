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

    public void deleteAll() {
        executor.execute(() -> {
            List<NotificationEntity> list = dao.getAllNotificationsSync();
            if (list != null) {
                for (NotificationEntity entity : list) {
                    deleteNotificationImage(entity.imagePath);
                }
            }
            dao.deleteAll();
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
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
        executor.execute(() -> {
            dao.deleteByDateRange(startTime, endTime);
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
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
        executor.execute(() -> {
            try {
                java.util.List<String> imagePaths = dao.getImagePathsForPackages(packages);
                if (imagePaths != null) {
                    for (String path : imagePaths) {
                        deleteNotificationImage(path);
                    }
                }
                dao.deleteByPackages(packages);
                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
            } catch (Exception e) {
                e.printStackTrace();
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
