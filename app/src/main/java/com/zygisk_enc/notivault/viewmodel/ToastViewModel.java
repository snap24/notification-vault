package com.zygisk_enc.notivault.viewmodel;

import android.app.Application;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.MediatorLiveData;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.database.ToastEntity;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import java.util.List;
import java.util.ArrayList;

public class ToastViewModel extends AndroidViewModel {

    private final AppDatabase database;
    private final MutableLiveData<Long> filterDateStart = new MutableLiveData<>(null);
    private final MutableLiveData<Long> filterDateEnd = new MutableLiveData<>(null);
    private final MutableLiveData<String> filterPackage = new MutableLiveData<>(null);
    private final MutableLiveData<Integer> filterLimit = new MutableLiveData<>(500);
    private final MediatorLiveData<List<ToastEntity>> toasts = new MediatorLiveData<>();
    private LiveData<List<ToastEntity>> currentSource = null;
    private final LiveData<List<AppSummary>> appSummaries;
    private final MutableLiveData<Integer> loadProgress = new MutableLiveData<>(-1);
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> scrollToTopEvent = new MutableLiveData<>(false);

    private static final LruCache<Long, String> decryptedToastCache = new LruCache<>(5000);
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int PARALLEL_THREADS = Math.max(2, Math.min(8, CPU_COUNT));
    private final java.util.concurrent.ExecutorService coordinatorExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService parallelDecryptionPool =
            java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_THREADS, new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "NotiVault-ToastDecryptor-" + count.getAndIncrement());
                }
            });
    private volatile long currentRunToken = 0;

    public ToastViewModel(@NonNull Application application) {
        super(application);
        database = AppDatabase.getInstance(application);
        appSummaries = database.toastDao().getToastAppSummaries();

        toasts.addSource(filterDateStart, date -> { resetLimit(); updateSource(); });
        toasts.addSource(filterDateEnd, date -> { resetLimit(); updateSource(); });
        toasts.addSource(filterPackage, pkg -> { resetLimit(); updateSource(); });
        toasts.addSource(filterLimit, limit -> {
            if (!isResettingLimit) {
                updateSource();
            }
        });
    }

    private boolean isResettingLimit = false;

    private void resetLimit() {
        if (filterLimit.getValue() != null && filterLimit.getValue() == 500) {
            return;
        }
        isResettingLimit = true;
        filterLimit.setValue(500);
        isResettingLimit = false;
    }

    public LiveData<Integer> getFilterLimit() {
        return filterLimit;
    }

    public void loadNextPage() {
        Integer current = filterLimit.getValue();
        if (current != null) {
            filterLimit.setValue(current + 500);
        }
    }

    private void updateSource() {
        if (currentSource != null) {
            toasts.removeSource(currentSource);
        }

        int limit = filterLimit.getValue() != null ? filterLimit.getValue() : 500;
        Long dateStart = filterDateStart.getValue();
        Long dateEnd = filterDateEnd.getValue();
        String pkg = filterPackage.getValue();
        if (pkg != null && pkg.isEmpty()) pkg = null;

        currentSource = database.toastDao().getFilteredToasts(limit, dateStart, dateEnd, pkg);
        toasts.addSource(currentSource, list -> filterAndDecrypt(list));
    }

    public LiveData<List<ToastEntity>> getToasts() {
        return toasts;
    }

    public LiveData<Integer> getLoadProgress() {
        return loadProgress;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    public LiveData<Boolean> getScrollToTopEvent() {
        return scrollToTopEvent;
    }

    public void requestScrollToTop() {
        scrollToTopEvent.setValue(true);
    }

    public void clearScrollToTopEvent() {
        scrollToTopEvent.setValue(false);
    }

    public void setDateFilter(Long start, Long end) {
        filterDateStart.setValue(start);
        filterDateEnd.setValue(end);
        requestScrollToTop();
    }

    public void setFilterPackage(String packageName) {
        filterPackage.setValue(packageName);
        requestScrollToTop();
    }

    public LiveData<String> getFilterPackage() {
        return filterPackage;
    }

    public LiveData<List<AppSummary>> getAppSummaries() {
        return appSummaries;
    }

    public void resetAllFilters() {
        filterDateStart.setValue(null);
        filterDateEnd.setValue(null);
        filterPackage.setValue(null);
        resetLimit();
        requestScrollToTop();
    }

    private void filterAndDecrypt(List<ToastEntity> list) {
        final long runToken = ++currentRunToken;

        if (list == null) {
            toasts.setValue(null);
            loadProgress.setValue(-1);
            isLoading.setValue(false);
            return;
        }

        isLoading.setValue(true);

        coordinatorExecutor.execute(() -> {
            try {
                if (runToken != currentRunToken) return;

                final int total = list.size();
                if (total == 0) {
                    if (runToken == currentRunToken) {
                        loadProgress.postValue(-1);
                        toasts.postValue(new ArrayList<>());
                        isLoading.postValue(false);
                    }
                    return;
                }

                int itemsToDecrypt = 0;
                for (int i = 0; i < total; i++) {
                    ToastEntity entity = list.get(i);
                    if (decryptedToastCache.get(entity.id) == null && entity.decryptedText == null) {
                        itemsToDecrypt++;
                    }
                }

                final boolean showProgress = itemsToDecrypt > 0;
                if (showProgress && runToken == currentRunToken) {
                    loadProgress.postValue(0);
                }

                int limit = filterLimit.getValue() != null ? filterLimit.getValue() : 500;
                final boolean isInitialLoad = (limit <= 500);
                final java.util.concurrent.atomic.AtomicInteger lastMilestone = new java.util.concurrent.atomic.AtomicInteger(0);

                int numChunks = Math.min(PARALLEL_THREADS, Math.max(1, (total + 99) / 100));
                int chunkSize = (total + numChunks - 1) / numChunks;

                java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(numChunks);
                @SuppressWarnings("unchecked")
                final java.util.List<ToastEntity>[] chunkResults = new java.util.List[numChunks];
                for (int c = 0; c < numChunks; c++) {
                    chunkResults[c] = java.util.Collections.synchronizedList(new ArrayList<>());
                }

                for (int c = 0; c < numChunks; c++) {
                    final int chunkIndex = c;
                    final int startIdx = c * chunkSize;
                    final int endIdx = Math.min(total, startIdx + chunkSize);

                    parallelDecryptionPool.execute(() -> {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
                        try {
                            for (int i = startIdx; i < endIdx; i++) {
                                if (runToken != currentRunToken) return;

                                ToastEntity entity = list.get(i);

                                String cached = decryptedToastCache.get(entity.id);
                                if (cached != null) {
                                    entity.decryptedText = cached;
                                } else if (entity.decryptedText == null) {
                                    entity.decryptedText = EncryptionHelper.decrypt(entity.text);
                                    if (entity.decryptedText != null) {
                                        decryptedToastCache.put(entity.id, entity.decryptedText);
                                    }
                                }

                                int processed = processedCount.incrementAndGet();
                                int progress = (processed * 100) / total;

                                if (showProgress && (processed % 10 == 0 || processed == total)) {
                                    if (runToken == currentRunToken) {
                                        loadProgress.postValue(progress);
                                    }
                                }

                                chunkResults[chunkIndex].add(entity);

                                if (isInitialLoad && progress <= 50) {
                                    int milestone = progress / 10;
                                    boolean milestoneTrigger = (milestone > 0 && milestone <= 5 && milestone > lastMilestone.get() && lastMilestone.compareAndSet(lastMilestone.get(), milestone));
                                    if (milestoneTrigger && runToken == currentRunToken) {
                                        List<ToastEntity> snapshot = new ArrayList<>();
                                        for (int k = 0; k < numChunks; k++) {
                                            synchronized (chunkResults[k]) {
                                                snapshot.addAll(chunkResults[k]);
                                            }
                                        }
                                        if (!snapshot.isEmpty() && runToken == currentRunToken) {
                                            toasts.postValue(snapshot);
                                        }
                                    }
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await();
                if (runToken != currentRunToken) return;

                List<ToastEntity> finalMerged = new ArrayList<>(total);
                for (int k = 0; k < numChunks; k++) {
                    synchronized (chunkResults[k]) {
                        finalMerged.addAll(chunkResults[k]);
                    }
                }

                if (runToken == currentRunToken) {
                    toasts.postValue(finalMerged);
                }

                if (showProgress) {
                    loadProgress.postValue(100);
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }
                loadProgress.postValue(-1);
                isLoading.postValue(false);
            } catch (Exception e) {
                e.printStackTrace();
                if (runToken == currentRunToken) {
                    loadProgress.postValue(-1);
                    isLoading.postValue(false);
                }
            }
        });
    }

    public void clearAllToasts() {
        final long runToken = ++currentRunToken;
        decryptedToastCache.evictAll();
        toasts.postValue(new ArrayList<>());
        loadProgress.postValue(-1);
        isLoading.postValue(false);
        com.zygisk_enc.notivault.util.AppExecutor.execute(() -> database.toastDao().deleteAll());
    }

    public LiveData<Long> getOldestTimestamp() {
        return database.toastDao().getOldestTimestamp();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        coordinatorExecutor.shutdownNow();
        parallelDecryptionPool.shutdownNow();
    }
}
