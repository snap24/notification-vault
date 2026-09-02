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

    public static boolean isClearingAllActive() {
        return com.zygisk_enc.notivault.service.ClearAllService.isClearingInProgress();
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
            android.os.PowerManager pm = (android.os.PowerManager) application.getSystemService(android.content.Context.POWER_SERVICE);
            android.os.PowerManager.WakeLock wakeLock = null;
            if (pm != null) {
                try {
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NotiVault:DeletionWakeLock");
                    wakeLock.acquire(180 * 1000L); // Hold for up to 3 minutes
                } catch (Exception ignored) {}
            }

            try {
                if (callback != null) callback.onProgress(0);
                Long maxIdObj = dao.getMaxId();
                if (maxIdObj == null || maxIdObj <= 0) {
                    if (callback != null) {
                        callback.onProgress(100);
                        callback.onComplete();
                    }
                    return;
                }
                final long maxId = maxIdObj;
                final long cutOffTime = System.currentTimeMillis();

                int totalCount = dao.getCountUpTo(maxId, cutOffTime);
                if (totalCount <= 0) {
                    if (callback != null) {
                        callback.onProgress(100);
                        callback.onComplete();
                    }
                    return;
                }

                // 0% -> 10%: Clean media attachments
                if (callback != null) callback.onProgress(5);
                List<String> imagePaths = dao.getDeletableImagePathsUpTo(maxId, cutOffTime);
                deleteNotificationImagesParallel(imagePaths);
                if (callback != null) callback.onProgress(10);

                // 10% -> 75%: Delete records in measured batches with calculated accurate progress
                AppDatabase db = AppDatabase.getInstance(application);
                final int BATCH_SIZE = 500;
                int deletedSoFar = 0;
                int lastReportedProgress = 10;

                while (deletedSoFar < totalCount) {
                    int deleted = dao.deleteBatchUpTo(maxId, cutOffTime, BATCH_SIZE);
                    if (deleted <= 0) {
                        break;
                    }
                    deletedSoFar += deleted;
                    int progress = 10 + (int) (((long) deletedSoFar * 65) / totalCount);
                    if (progress > lastReportedProgress) {
                        lastReportedProgress = Math.min(75, progress);
                        if (callback != null) callback.onProgress(lastReportedProgress);
                    }
                }

                // Sweep any remaining records
                dao.deleteAllUpTo(maxId, cutOffTime);
                if (callback != null) callback.onProgress(75);

                // 75% -> 80%: Prepare checkpoint
                if (callback != null) callback.onProgress(80);

                // 80% -> 100%: SQLite VACUUM and WAL truncate
                db.checkpointAndVacuum();

                if (callback != null) callback.onProgress(100);
                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try {
                        wakeLock.release();
                    } catch (Exception ignored) {}
                }
                if (callback != null) callback.onComplete();
            }
        });
    }

    public void deleteOlderThan(long timestamp) {
        executor.execute(() -> {
            List<NotificationEntity> list = dao.getNotificationsOlderThanSync(timestamp);
            if (list != null) {
                List<String> paths = new java.util.ArrayList<>();
                for (NotificationEntity entity : list) {
                    if (entity.imagePath != null) paths.add(entity.imagePath);
                }
                deleteNotificationImagesParallel(paths);
            }
            dao.deleteOlderThan(timestamp);
            AppDatabase.getInstance(application).checkpointAndVacuum();
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    public void deleteOlderThanForPackages(long timestamp, List<String> packages) {
        executor.execute(() -> {
            if (packages == null || packages.isEmpty()) return;
            List<String> imagePaths = dao.getOldImagePathsForPackages(timestamp, packages);
            deleteNotificationImagesParallel(imagePaths);
            dao.deleteOlderThanForPackages(timestamp, packages);
            AppDatabase.getInstance(application).checkpointAndVacuum();
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    public void deleteByDateRange(long startTime, long endTime) {
        deleteByDateRange(startTime, endTime, null);
    }

    public void deleteByDateRange(long startTime, long endTime, ProgressCallback callback) {
        executor.execute(() -> {
            try {
                if (callback != null) callback.onProgress(15);
                List<String> imagePaths = dao.getImagePathsForDateRange(startTime, endTime);
                deleteNotificationImagesParallel(imagePaths);
                if (callback != null) callback.onProgress(45);

                AppDatabase db = AppDatabase.getInstance(application);
                db.runInTransaction(() -> {
                    dao.deleteByDateRange(startTime, endTime);
                });
                if (callback != null) callback.onProgress(75);

                db.checkpointAndVacuum();

                if (callback != null) callback.onProgress(100);
                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
            } catch (Exception e) {
                e.printStackTrace();
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
            AppDatabase.getInstance(application).checkpointAndVacuum();
            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(application);
        });
    }

    private void deleteNotificationImagesParallel(List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        int cores = com.zygisk_enc.notivault.util.AppExecutor.getCpuCores();
        int total = imagePaths.size();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(cores);
        int chunkSize = Math.max(10, (total + cores - 1) / cores);

        for (int c = 0; c < cores; c++) {
            final int startIdx = c * chunkSize;
            final int endIdx = Math.min(startIdx + chunkSize, total);
            if (startIdx >= total) {
                latch.countDown();
                continue;
            }

            com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
                try {
                    for (int i = startIdx; i < endIdx; i++) {
                        deleteNotificationImage(imagePaths.get(i));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException ignored) {}
    }

    private void deleteNotificationImage(String imagePath) {
        if (imagePath != null && !imagePath.isEmpty()) {
            String[] paths = imagePath.split("\\|");
            for (String path : paths) {
                String trimmed = path.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        java.io.File file = new java.io.File(trimmed);
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
                if (packages == null || packages.isEmpty()) return;
                if (callback != null) callback.onProgress(15);
                java.util.List<String> imagePaths = dao.getImagePathsForPackages(packages);
                deleteNotificationImagesParallel(imagePaths);
                if (callback != null) callback.onProgress(45);

                AppDatabase db = AppDatabase.getInstance(application);
                db.runInTransaction(() -> {
                    dao.deleteByPackages(packages);
                });
                if (callback != null) callback.onProgress(75);

                db.checkpointAndVacuum();

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

    public LiveData<List<NotificationEntity>> getAllNotifications(int limit, Long dateStart, Long dateEnd, int profileMode) {
        return dao.getAllNotifications(limit, dateStart, dateEnd, profileMode);
    }

    public LiveData<List<NotificationEntity>> getAllNotifications(int limit, Long dateStart, Long dateEnd) {
        return dao.getAllNotifications(limit, dateStart, dateEnd, -1);
    }

    public LiveData<List<NotificationEntity>> getNotificationsByPackage(String packageName, int limit, Long dateStart, Long dateEnd, int profileMode) {
        return dao.getNotificationsByPackage(packageName, limit, dateStart, dateEnd, profileMode);
    }

    public LiveData<List<NotificationEntity>> getNotificationsByPackage(String packageName, int limit, Long dateStart, Long dateEnd) {
        return dao.getNotificationsByPackage(packageName, limit, dateStart, dateEnd, -1);
    }

    public LiveData<List<NotificationEntity>> searchNotifications(String query) {
        return dao.searchNotifications(query);
    }

    public LiveData<List<NotificationEntity>> searchByTokenHash(long tokenHash, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd, int profileMode) {
        return dao.searchByTokenHash(tokenHash, packageName, isFavoriteOnly, limit, dateStart, dateEnd, profileMode);
    }

    public LiveData<List<NotificationEntity>> searchByTokenHash(long tokenHash, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd) {
        return dao.searchByTokenHash(tokenHash, packageName, isFavoriteOnly, limit, dateStart, dateEnd, -1);
    }

    public LiveData<List<NotificationEntity>> searchByTokenHashes(List<Long> tokenHashes, int tokenCount, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd, int profileMode) {
        return dao.searchByTokenHashes(tokenHashes, tokenCount, packageName, isFavoriteOnly, limit, dateStart, dateEnd, profileMode);
    }

    public LiveData<List<NotificationEntity>> searchByTokenHashes(List<Long> tokenHashes, int tokenCount, String packageName, int isFavoriteOnly, int limit, Long dateStart, Long dateEnd) {
        return dao.searchByTokenHashes(tokenHashes, tokenCount, packageName, isFavoriteOnly, limit, dateStart, dateEnd, -1);
    }

    public LiveData<List<AppSummary>> getAppSummaries(int profileMode) {
        return dao.getAppSummaries(profileMode);
    }

    public LiveData<List<AppSummary>> getAppSummaries() {
        return dao.getAppSummaries(-1);
    }

    public List<AppSummary> getAppSummariesSync(int profileMode) {
        return dao.getAppSummariesSync(profileMode);
    }

    public List<AppSummary> getAppSummariesSync() {
        return dao.getAppSummariesSync(-1);
    }

    public LiveData<Integer> getUnreadCount(int profileMode) {
        return dao.getUnreadCount(profileMode);
    }

    public LiveData<Integer> getUnreadCount() {
        return dao.getUnreadCount(-1);
    }

    public LiveData<Integer> getCountSince(long startTimestamp) {
        return dao.getCountSince(startTimestamp);
    }

    public LiveData<List<AppSummary>> getTopAppsSince(long startTimestamp, int limit, int profileMode) {
        return dao.getTopAppsSince(startTimestamp, limit, profileMode);
    }

    public LiveData<List<AppSummary>> getTopAppsSince(long startTimestamp, int limit) {
        return dao.getTopAppsSince(startTimestamp, limit, -1);
    }

    public LiveData<List<NotificationEntity>> getFavorites(int limit, Long dateStart, Long dateEnd, int profileMode) {
        return dao.getFavorites(limit, dateStart, dateEnd, profileMode);
    }

    public LiveData<List<NotificationEntity>> getFavorites(int limit, Long dateStart, Long dateEnd) {
        return dao.getFavorites(limit, dateStart, dateEnd, -1);
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
