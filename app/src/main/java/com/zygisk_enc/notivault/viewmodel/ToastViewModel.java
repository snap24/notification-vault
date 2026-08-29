package com.zygisk_enc.notivault.viewmodel;

import android.app.Application;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.MediatorLiveData;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.ToastEntity;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import java.util.List;
import java.util.ArrayList;

public class ToastViewModel extends AndroidViewModel {

    private final AppDatabase database;
    private final MutableLiveData<Long> filterDateStart = new MutableLiveData<>(null);
    private final MutableLiveData<Long> filterDateEnd = new MutableLiveData<>(null);
    private final MutableLiveData<String> filterPackage = new MutableLiveData<>(null);
    private final MediatorLiveData<List<ToastEntity>> toasts = new MediatorLiveData<>();
    private final LiveData<List<ToastEntity>> rawToastsSource;
    private final LiveData<List<com.zygisk_enc.notivault.database.AppSummary>> appSummaries;
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
                    Thread t = new Thread(r, "NotiVault-ToastDecryptor-" + count.getAndIncrement());
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });
    private volatile long currentRunToken = 0;

    public ToastViewModel(@NonNull Application application) {
        super(application);
        database = AppDatabase.getInstance(application);
        rawToastsSource = database.toastDao().getAllToasts();
        appSummaries = database.toastDao().getToastAppSummaries();

        toasts.addSource(rawToastsSource, list -> filterAndDecrypt(list));
        toasts.addSource(filterDateStart, date -> filterAndDecrypt(rawToastsSource.getValue()));
        toasts.addSource(filterDateEnd, date -> filterAndDecrypt(rawToastsSource.getValue()));
        toasts.addSource(filterPackage, pkg -> filterAndDecrypt(rawToastsSource.getValue()));
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

    public LiveData<List<com.zygisk_enc.notivault.database.AppSummary>> getAppSummaries() {
        return appSummaries;
    }

    public void resetAllFilters() {
        filterDateStart.setValue(null);
        filterDateEnd.setValue(null);
        filterPackage.setValue(null);
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

                // Count items needing decryption
                int itemsToDecrypt = 0;
                for (int i = 0; i < total; i++) {
                    if (runToken != currentRunToken) return;
                    ToastEntity entity = list.get(i);
                    if (decryptedToastCache.get(entity.id) == null && entity.decryptedText == null) {
                        itemsToDecrypt++;
                    }
                }

                final boolean showProgress = itemsToDecrypt > 0;
                if (showProgress && runToken == currentRunToken) {
                    loadProgress.postValue(0);
                }

                final Long dateStart = filterDateStart.getValue();
                final Long dateEnd = filterDateEnd.getValue();
                final String filterPkg = filterPackage.getValue();

                // Slicing across parallel multi-core threads
                int numChunks = Math.min(PARALLEL_THREADS, Math.max(1, (total + 49) / 50));
                int chunkSize = (total + numChunks - 1) / numChunks;

                java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(numChunks);
                @SuppressWarnings("unchecked")
                final java.util.List<ToastEntity>[] chunkResults = new java.util.List[numChunks];
                for (int c = 0; c < numChunks; c++) {
                    chunkResults[c] = java.util.Collections.synchronizedList(new ArrayList<>());
                }

                final java.util.concurrent.atomic.AtomicInteger lastMilestone = new java.util.concurrent.atomic.AtomicInteger(0);

                for (int c = 0; c < numChunks; c++) {
                    final int chunkIndex = c;
                    final int startIdx = c * chunkSize;
                    final int endIdx = Math.min(total, startIdx + chunkSize);

                    parallelDecryptionPool.execute(() -> {
                        try {
                            for (int i = startIdx; i < endIdx; i++) {
                                if (runToken != currentRunToken) return;

                                ToastEntity entity = list.get(i);

                                // 1. Filter by date
                                if (dateStart != null && dateEnd != null) {
                                    if (entity.timestamp < dateStart || entity.timestamp > dateEnd) {
                                        continue;
                                    }
                                }

                                // 2. Filter by app package
                                if (filterPkg != null && !filterPkg.isEmpty()) {
                                    if (!filterPkg.equalsIgnoreCase(entity.packageName)) {
                                        continue;
                                    }
                                }

                                // 3. Check cache / decrypt text
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

                                // Progressive batch streaming for fast visual feedback
                                int milestone = progress / 10;
                                boolean milestoneTrigger = (milestone > lastMilestone.get() && lastMilestone.compareAndSet(lastMilestone.get(), milestone));

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
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                // Wait for all parallel workers
                latch.await();

                if (runToken != currentRunToken) return;

                // Final complete list in strict order
                List<ToastEntity> finalMerged = new ArrayList<>(total);
                for (int k = 0; k < numChunks; k++) {
                    synchronized (chunkResults[k]) {
                        finalMerged.addAll(chunkResults[k]);
                    }
                }

                if (runToken == currentRunToken) {
                    toasts.postValue(finalMerged);
                    if (showProgress) {
                        loadProgress.postValue(100);
                        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                    }
                    loadProgress.postValue(-1);
                    isLoading.postValue(false);
                }
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
