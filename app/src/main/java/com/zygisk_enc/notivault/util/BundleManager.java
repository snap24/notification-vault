package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationDao;
import com.zygisk_enc.notivault.database.NotificationDao.BundleScanItem;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BundleManager {

    public static final int BUNDLE_MIN_THRESHOLD = 10;
    private static final String PREF_IS_BUNDLING_COMPLETED = "is_db_bundling_completed";
    private static final String PREF_BUNDLING_ATTEMPT_COUNT = "db_bundling_attempt_count";
    private static final String PREF_LAST_WEEKLY_BUNDLING = "last_weekly_bundling_timestamp";
    private static final long WEEK_IN_MILLIS = 7L * 24 * 60 * 60 * 1000L;

    private static final AtomicBoolean isBundlingActive = new AtomicBoolean(false);

    private BundleManager() {}

    public static boolean isBundlingInProgress() {
        return isBundlingActive.get();
    }

    public interface BundlingCallback {
        void onProgress(int progress);
        void onComplete();
        void onError(Exception e);
    }

    /**
     * Trigger 1 & 4: App launch check (first 3 opens guard + weekly maintenance check).
     */
    public static void checkAndTriggerAppLaunchBundling(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();

        AppExecutor.execute(() -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
            boolean isCompleted = prefs.getBoolean(PREF_IS_BUNDLING_COMPLETED, false);
            int attemptCount = prefs.getInt(PREF_BUNDLING_ATTEMPT_COUNT, 0);
            long lastWeekly = prefs.getLong(PREF_LAST_WEEKLY_BUNDLING, 0L);
            long now = System.currentTimeMillis();

            if (!isCompleted && attemptCount < 3) {
                // Initial run (first 3 opens guard)
                prefs.edit().putInt(PREF_BUNDLING_ATTEMPT_COUNT, attemptCount + 1).apply();
                runFullDbBundling(appContext, new BundlingCallback() {
                    @Override public void onProgress(int progress) {}
                    @Override
                    public void onComplete() {
                        prefs.edit()
                                .putBoolean(PREF_IS_BUNDLING_COMPLETED, true)
                                .putLong(PREF_LAST_WEEKLY_BUNDLING, System.currentTimeMillis())
                                .apply();
                    }
                    @Override public void onError(Exception e) {}
                });
            } else if (isCompleted && (now - lastWeekly >= WEEK_IN_MILLIS || lastWeekly == 0L)) {
                // Weekly maintenance check
                runFullDbBundling(appContext, new BundlingCallback() {
                    @Override public void onProgress(int progress) {}
                    @Override
                    public void onComplete() {
                        prefs.edit().putLong(PREF_LAST_WEEKLY_BUNDLING, System.currentTimeMillis()).apply();
                    }
                    @Override public void onError(Exception e) {}
                });
            }
        });
    }

    /**
     * Trigger 2: Post-backup import bundling.
     */
    public static void triggerPostImportBundling(Context context, Runnable onFinished) {
        if (context == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        Context appContext = context.getApplicationContext();
        runFullDbBundling(appContext, new BundlingCallback() {
            @Override public void onProgress(int progress) {}
            @Override
            public void onComplete() {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
                prefs.edit()
                        .putBoolean(PREF_IS_BUNDLING_COMPLETED, true)
                        .putLong(PREF_LAST_WEEKLY_BUNDLING, System.currentTimeMillis())
                        .apply();
                if (onFinished != null) onFinished.run();
            }
            @Override
            public void onError(Exception e) {
                if (onFinished != null) onFinished.run();
            }
        });
    }

    /**
     * Trigger 3: Manual user button in Settings.
     */
    public static void triggerManualBundling(Context context, BundlingCallback callback) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        runFullDbBundling(appContext, new BundlingCallback() {
            @Override
            public void onProgress(int progress) {
                if (callback != null) callback.onProgress(progress);
            }

            @Override
            public void onComplete() {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
                prefs.edit()
                        .putBoolean(PREF_IS_BUNDLING_COMPLETED, true)
                        .putLong(PREF_LAST_WEEKLY_BUNDLING, System.currentTimeMillis())
                        .apply();
                if (callback != null) callback.onComplete();
            }

            @Override
            public void onError(Exception e) {
                if (callback != null) callback.onError(e);
            }
        });
    }

    /**
     * Core database bundling engine: Scans SQLite records and tags streaks >= 10 with a unique bundleId.
     */
    public static void runFullDbBundling(Context context, BundlingCallback callback) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();

        if (!isBundlingActive.compareAndSet(false, true)) {
            // Already running
            return;
        }

        AppExecutor.execute(() -> {
            try {
                NotificationViewModel.setGlobalOperationProgress(
                        NotificationViewModel.OperationProgress.TYPE_BUNDLING, 0);
                if (callback != null) callback.onProgress(0);

                NotificationDao dao = AppDatabase.getInstance(appContext).notificationDao();
                List<BundleScanItem> allItems = dao.getAllBundleScanItemsSync();

                if (allItems == null || allItems.isEmpty()) {
                    finishBundling(callback);
                    return;
                }

                final int totalItems = allItems.size();
                int processedCount = 0;
                int currentStreakStart = 0;

                while (currentStreakStart < totalItems) {
                    BundleScanItem firstInStreak = allItems.get(currentStreakStart);
                    String pkg = firstInStreak.packageName;
                    String currentDateGroup = DateUtils.getDateGroupKey(firstInStreak.timestamp);
                    int streakEnd = currentStreakStart + 1;

                    while (streakEnd < totalItems) {
                        BundleScanItem next = allItems.get(streakEnd);
                        if (pkg != null && pkg.equals(next.packageName) &&
                                currentDateGroup.equals(DateUtils.getDateGroupKey(next.timestamp))) {
                            streakEnd++;
                        } else {
                            break;
                        }
                    }

                    int streakLength = streakEnd - currentStreakStart;
                    if (streakLength >= BUNDLE_MIN_THRESHOLD) {
                        // Generate deterministic bundle ID using package + oldest item timestamp + start ID
                        BundleScanItem oldestInStreak = allItems.get(streakEnd - 1);
                        String targetBundleId = "bundle_" + pkg + "_" + oldestInStreak.timestamp + "_" + firstInStreak.id;

                        List<Long> idsToUpdate = new ArrayList<>();
                        for (int i = currentStreakStart; i < streakEnd; i++) {
                            BundleScanItem item = allItems.get(i);
                            if (item.bundleId == null || !targetBundleId.equals(item.bundleId)) {
                                idsToUpdate.add(item.id);
                            }
                        }

                        if (!idsToUpdate.isEmpty()) {
                            // Update in batches of 500
                            final int BATCH_SIZE = 500;
                            for (int b = 0; b < idsToUpdate.size(); b += BATCH_SIZE) {
                                int bEnd = Math.min(b + BATCH_SIZE, idsToUpdate.size());
                                dao.updateBundleIdForIds(idsToUpdate.subList(b, bEnd), targetBundleId);
                            }
                        }
                    } else {
                        // Streak < 10: Clear bundleId if any item was previously bundled
                        List<Long> idsToClear = new ArrayList<>();
                        for (int i = currentStreakStart; i < streakEnd; i++) {
                            BundleScanItem item = allItems.get(i);
                            if (item.bundleId != null) {
                                idsToClear.add(item.id);
                            }
                        }

                        if (!idsToClear.isEmpty()) {
                            final int BATCH_SIZE = 500;
                            for (int b = 0; b < idsToClear.size(); b += BATCH_SIZE) {
                                int bEnd = Math.min(b + BATCH_SIZE, idsToClear.size());
                                dao.updateBundleIdForIds(idsToClear.subList(b, bEnd), null);
                            }
                        }
                    }

                    processedCount += streakLength;
                    int progress = (processedCount * 100) / totalItems;
                    if (processedCount % 50 == 0 || processedCount == totalItems) {
                        NotificationViewModel.setGlobalOperationProgress(
                                NotificationViewModel.OperationProgress.TYPE_BUNDLING, progress);
                        if (callback != null) callback.onProgress(progress);
                    }

                    currentStreakStart = streakEnd;
                }

                finishBundling(callback);
            } catch (Exception e) {
                isBundlingActive.set(false);
                NotificationViewModel.setGlobalOperationProgress(
                        NotificationViewModel.OperationProgress.TYPE_NONE, -1);
                if (callback != null) callback.onError(e);
            }
        });
    }

    private static void finishBundling(BundlingCallback callback) {
        NotificationViewModel.setGlobalOperationProgress(
                NotificationViewModel.OperationProgress.TYPE_BUNDLING, 100);
        if (callback != null) callback.onProgress(100);

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        isBundlingActive.set(false);
        NotificationViewModel.setGlobalOperationProgress(
                NotificationViewModel.OperationProgress.TYPE_NONE, -1);

        // Clear decrypted cache to trigger fresh, synchronized feed reload
        NotificationViewModel.clearDecryptedCache();

        if (callback != null) callback.onComplete();
    }
}
