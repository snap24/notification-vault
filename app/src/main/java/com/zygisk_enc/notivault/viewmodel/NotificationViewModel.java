package com.zygisk_enc.notivault.viewmodel;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.MediatorLiveData;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import com.zygisk_enc.notivault.repository.NotificationRepository;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import java.util.List;

public class NotificationViewModel extends AndroidViewModel {

    private final NotificationRepository repository;
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");
    private final MutableLiveData<String> filterPackage = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> filterFavorites = new MutableLiveData<>(false);
    private final MutableLiveData<Long> filterDateStart = new MutableLiveData<>(null);
    private final MutableLiveData<Long> filterDateEnd = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> scrollToTopEvent = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> openSearchEvent = new MutableLiveData<>(false);
    private final MediatorLiveData<List<NotificationEntity>> notifications = new MediatorLiveData<>();
    private LiveData<List<NotificationEntity>> currentSource = null;
    private final LiveData<List<AppSummary>> appSummaries;
    private final LiveData<Integer> unreadCount;

    public static class DecryptedText {
        public final String title;
        public final String text;
        public final String bigText;
        public DecryptedText(String title, String text, String bigText) {
            this.title = title;
            this.text = text;
            this.bigText = bigText;
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<Long, DecryptedText> decryptedCache = new java.util.concurrent.ConcurrentHashMap<>();

    private final MutableLiveData<Integer> loadProgress = new MutableLiveData<>(-1);
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int PARALLEL_THREADS = Math.max(2, Math.min(8, CPU_COUNT));
    private final java.util.concurrent.ExecutorService coordinatorExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService parallelDecryptionPool =
            java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_THREADS, new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "NotiVault-Decryptor-" + count.getAndIncrement());
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });
    private long currentRunToken = 0;
    private List<NotificationEntity> lastRawList = null;
    private final MutableLiveData<Integer> filterLimit = new MutableLiveData<>(500);

    public LiveData<Integer> getLoadProgress() {
        return loadProgress;
    }

    private boolean isBatchingUpdates = false;

    public NotificationViewModel(Application application) {
        super(application);
        repository = new NotificationRepository(application);
        appSummaries = repository.getAppSummaries();
        unreadCount = repository.getUnreadCount();

        notifications.addSource(searchQuery, query -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterPackage, pkg -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterFavorites, favs -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterDateStart, date -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterDateEnd, date -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterLimit, limit -> { if (!isBatchingUpdates) { updateSource(); } });
    }

    private void resetLimit() {
        filterLimit.setValue(500);
    }

    private void updateSource() {
        if (currentSource != null) {
            notifications.removeSource(currentSource);
        }

        final long runToken = ++currentRunToken;
        lastRawList = null;

        String rawQuery = searchQuery.getValue();
        boolean isSearching = rawQuery != null && !rawQuery.trim().isEmpty();
        int limit = isSearching ? Integer.MAX_VALUE : (filterLimit.getValue() != null ? filterLimit.getValue() : 500);
        Long dateStart = filterDateStart.getValue();
        Long dateEnd = filterDateEnd.getValue();

        Boolean favs = filterFavorites.getValue();
        int favsOnly = (favs != null && favs) ? 1 : 0;
        String pkg = filterPackage.getValue();
        if (pkg != null && pkg.isEmpty()) pkg = null;

        if (isSearching) {
            java.util.List<Long> searchHashes = com.zygisk_enc.notivault.util.BlindIndexHelper.extractQueryTokenHashes(rawQuery);
            if (searchHashes.isEmpty()) {
                if (favsOnly == 1) {
                    currentSource = repository.getFavorites(limit, dateStart, dateEnd);
                } else if (pkg != null) {
                    currentSource = repository.getNotificationsByPackage(pkg, limit, dateStart, dateEnd);
                } else {
                    currentSource = repository.getAllNotifications(limit, dateStart, dateEnd);
                }
            } else if (searchHashes.size() == 1) {
                currentSource = repository.searchByTokenHash(searchHashes.get(0), pkg, favsOnly, limit, dateStart, dateEnd);
            } else {
                currentSource = repository.searchByTokenHashes(searchHashes, searchHashes.size(), pkg, favsOnly, limit, dateStart, dateEnd);
            }
        } else if (favsOnly == 1) {
            currentSource = repository.getFavorites(limit, dateStart, dateEnd);
        } else if (pkg != null) {
            currentSource = repository.getNotificationsByPackage(pkg, limit, dateStart, dateEnd);
        } else {
            currentSource = repository.getAllNotifications(limit, dateStart, dateEnd);
        }

        notifications.addSource(currentSource, list -> {
            if (list == null) {
                notifications.setValue(null);
                lastRawList = null;
                return;
            }

            if (runToken != currentRunToken) return;

            if (isListIdentical(list, lastRawList)) {
                return;
            }
            lastRawList = list;

            coordinatorExecutor.execute(() -> {
                try {
                    if (runToken != currentRunToken) return;

                    final int total = list.size();
                    if (total == 0) {
                        if (runToken == currentRunToken) {
                            loadProgress.postValue(-1);
                            notifications.postValue(new java.util.ArrayList<>());
                        }
                        return;
                    }

                    String query = searchQuery.getValue();
                    final String lowerQuery = query != null ? query.toLowerCase().trim() : "";
                    final boolean searchingMode = !lowerQuery.isEmpty();

                    // Count how many items actually need decryption (not in cache)
                    int itemsToDecrypt = 0;
                    for (int i = 0; i < total; i++) {
                        if (runToken != currentRunToken) return;
                        if (decryptedCache.get(list.get(i).id) == null) {
                            itemsToDecrypt++;
                        }
                    }

                    final boolean showProgress = itemsToDecrypt > 0;
                    if (showProgress && runToken == currentRunToken) {
                        loadProgress.postValue(0);
                    }

                    // Slicing across parallel threads
                    int numChunks = Math.min(PARALLEL_THREADS, Math.max(1, (total + 99) / 100));
                    int chunkSize = (total + numChunks - 1) / numChunks;

                    java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(numChunks);
                    @SuppressWarnings("unchecked")
                    final java.util.List<NotificationEntity>[] chunkResults = new java.util.List[numChunks];
                    for (int c = 0; c < numChunks; c++) {
                        chunkResults[c] = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                    }

                    final java.util.concurrent.atomic.AtomicInteger lastMilestone = new java.util.concurrent.atomic.AtomicInteger(0);
                    final java.util.concurrent.atomic.AtomicLong lastPublishTime = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
                    final boolean allowProgressiveListStreaming = searchingMode || (limit <= 500);

                    for (int c = 0; c < numChunks; c++) {
                        final int chunkIndex = c;
                        final int startIdx = c * chunkSize;
                        final int endIdx = Math.min(total, startIdx + chunkSize);

                        parallelDecryptionPool.execute(() -> {
                            try {
                                for (int i = startIdx; i < endIdx; i++) {
                                    if (runToken != currentRunToken) return;

                                    NotificationEntity entity = list.get(i);
                                    DecryptedText cached = decryptedCache.get(entity.id);
                                    if (cached != null) {
                                        entity.decryptedTitle = cached.title;
                                        entity.decryptedText = cached.text;
                                        entity.decryptedBigText = cached.bigText;
                                    } else {
                                        if (entity.decryptedTitle == null) {
                                            entity.decryptedTitle = EncryptionHelper.decrypt(entity.title);
                                        }
                                        if (entity.decryptedText == null) {
                                            entity.decryptedText = EncryptionHelper.decrypt(entity.text);
                                        }
                                        if (entity.decryptedBigText == null) {
                                            entity.decryptedBigText = EncryptionHelper.decrypt(entity.bigText);
                                        }
                                        decryptedCache.put(entity.id, new DecryptedText(
                                                entity.decryptedTitle, entity.decryptedText, entity.decryptedBigText));
                                    }

                                    int processed = processedCount.incrementAndGet();
                                    int progress = (processed * 100) / total;

                                    if (showProgress && (processed % 15 == 0 || processed == total)) {
                                        if (runToken == currentRunToken) {
                                            loadProgress.postValue(progress);
                                        }
                                    }

                                    boolean matches = true;
                                    if (searchingMode) {
                                        boolean appNameMatches = entity.appName != null && entity.appName.toLowerCase().contains(lowerQuery);
                                        boolean titleMatches = entity.decryptedTitle != null && entity.decryptedTitle.toLowerCase().contains(lowerQuery);
                                        boolean textMatches = entity.decryptedText != null && entity.decryptedText.toLowerCase().contains(lowerQuery);
                                        boolean bigTextMatches = entity.decryptedBigText != null && entity.decryptedBigText.toLowerCase().contains(lowerQuery);

                                        if (!appNameMatches && !titleMatches && !textMatches && !bigTextMatches) {
                                            matches = false;
                                        }
                                    }

                                    if (matches) {
                                        chunkResults[chunkIndex].add(entity);
                                    }

                                    // Progressive list streaming enabled for:
                                    // 1) Search mode (entire search dataset)
                                    // 2) Initial feed batch (0 - 500 items)
                                    // For subsequent scroll batches (501+), toolbar pill shows progress while list appends once at 100%
                                    if (allowProgressiveListStreaming) {
                                        int milestone;
                                        if (searchingMode) {
                                            if (progress < 20) {
                                                milestone = progress / 10;
                                            } else if (progress < 25) {
                                                milestone = 2;
                                            } else {
                                                milestone = 2 + (progress / 25);
                                            }
                                        } else {
                                            milestone = progress / 10;
                                        }

                                        boolean milestoneTrigger = (milestone > lastMilestone.get() && lastMilestone.compareAndSet(lastMilestone.get(), milestone));

                                        if (milestoneTrigger && runToken == currentRunToken) {
                                            java.util.List<NotificationEntity> snapshot = new java.util.ArrayList<>();
                                            for (int k = 0; k < numChunks; k++) {
                                                synchronized (chunkResults[k]) {
                                                    snapshot.addAll(chunkResults[k]);
                                                }
                                            }
                                            if (!snapshot.isEmpty() && runToken == currentRunToken) {
                                                notifications.postValue(snapshot);
                                            }
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

                    // Final complete list in strict chronological order
                    java.util.List<NotificationEntity> finalMerged = new java.util.ArrayList<>(total);
                    for (int k = 0; k < numChunks; k++) {
                        synchronized (chunkResults[k]) {
                            finalMerged.addAll(chunkResults[k]);
                        }
                    }

                    if (runToken == currentRunToken) {
                        notifications.postValue(finalMerged);
                    }

                    if (showProgress) {
                        if (runToken == currentRunToken) {
                            loadProgress.postValue(100);
                        }
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                        if (runToken == currentRunToken) {
                            loadProgress.postValue(-1);
                        }
                    } else {
                        if (runToken == currentRunToken) {
                            loadProgress.postValue(-1);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if (runToken == currentRunToken) {
                        loadProgress.postValue(-1);
                    }
                }
            });
        });
    }

    public LiveData<List<NotificationEntity>> getNotifications() {
        return notifications;
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

    public LiveData<Boolean> getFilterFavorites() {
        return filterFavorites;
    }

    public void setFilterFavorites(boolean favoritesOnly) {
        Boolean current = filterFavorites.getValue();
        if (current != null && current == favoritesOnly) {
            return;
        }
        filterFavorites.setValue(favoritesOnly);
    }

    public LiveData<List<AppSummary>> getAppSummaries() {
        return appSummaries;
    }

    public LiveData<Integer> getUnreadCount() {
        return unreadCount;
    }

    public LiveData<String> getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String query) {
        String current = searchQuery.getValue();
        if ((query == null && current == null) || (query != null && query.equals(current))) {
            return;
        }
        searchQuery.setValue(query);
    }

    public void setDateFilter(Long start, Long end) {
        Long currentStart = filterDateStart.getValue();
        Long currentEnd = filterDateEnd.getValue();
        boolean startSame = (start == null && currentStart == null) || (start != null && start.equals(currentStart));
        boolean endSame = (end == null && currentEnd == null) || (end != null && end.equals(currentEnd));
        if (startSame && endSame) {
            return;
        }
        filterDateStart.setValue(start);
        filterDateEnd.setValue(end);
    }
    
    public LiveData<Long> getFilterDateStart() {
        return filterDateStart;
    }

    public void setFilterPackage(String packageName) {
        String current = filterPackage.getValue();
        if ((packageName == null && current == null) || (packageName != null && packageName.equals(current))) {
            return;
        }
        filterPackage.setValue(packageName);
    }

    public void resetAllFilters() {
        boolean changed = false;
        isBatchingUpdates = true;
        try {
            if (searchQuery.getValue() != null && !searchQuery.getValue().isEmpty()) {
                searchQuery.setValue(null);
                changed = true;
            }
            if (filterPackage.getValue() != null && !filterPackage.getValue().isEmpty()) {
                filterPackage.setValue(null);
                changed = true;
            }
            if (filterFavorites.getValue() != null && filterFavorites.getValue()) {
                filterFavorites.setValue(false);
                changed = true;
            }
            if (filterDateStart.getValue() != null || filterDateEnd.getValue() != null) {
                filterDateStart.setValue(null);
                filterDateEnd.setValue(null);
                changed = true;
            }
        } finally {
            isBatchingUpdates = false;
        }
        if (changed) {
            resetLimit();
            updateSource();
        }
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

    public LiveData<Boolean> getOpenSearchEvent() {
        return openSearchEvent;
    }

    public void requestOpenSearch() {
        openSearchEvent.setValue(true);
    }

    public void clearOpenSearchEvent() {
        openSearchEvent.setValue(false);
    }

    public void markAsRead(long id) {
        repository.markAsRead(id);
    }

    public void delete(NotificationEntity entity) {
        repository.delete(entity);
    }

    public void deleteById(long id) {
        repository.deleteById(id);
    }

    public void insert(NotificationEntity entity) {
        repository.insert(entity);
    }

    public void deleteAll() {
        repository.deleteAll();
    }

    public void deleteByDateRange(long startTime, long endTime) {
        repository.deleteByDateRange(startTime, endTime);
    }

    public void deleteByDays(java.util.Collection<Long> daysUtc) {
        repository.deleteByDays(daysUtc);
    }

    public void deleteOlderThan(long timestamp) {
        repository.deleteOlderThan(timestamp);
    }

    public void setFavorite(long id, boolean isFavorite) {
        repository.setFavorite(id, isFavorite);
    }

    public LiveData<Integer> getCountSince(long startTimestamp) {
        return repository.getCountSince(startTimestamp);
    }

    public LiveData<List<AppSummary>> getTopAppsSince(long startTimestamp, int limit) {
        return repository.getTopAppsSince(startTimestamp, limit);
    }

    public LiveData<List<NotificationEntity>> getFavorites() {
        int limit = filterLimit.getValue() != null ? filterLimit.getValue() : 500;
        Long dateStart = filterDateStart.getValue();
        Long dateEnd = filterDateEnd.getValue();
        return repository.getFavorites(limit, dateStart, dateEnd);
    }

    public NotificationRepository getRepository() {
        return repository;
    }

    public LiveData<List<NotificationEntity>> getNotificationsSince(long startTimestamp) {
        return repository.getNotificationsSince(startTimestamp);
    }

    public LiveData<Long> getOldestTimestamp() {
        return repository.getOldestTimestamp();
    }

    public void deleteByPackages(List<String> packages) {
        repository.deleteByPackages(packages);
    }

    public void deleteOlderThanForPackages(long timestamp, List<String> packages) {
        repository.deleteOlderThanForPackages(timestamp, packages);
    }

    public void insertRule(AppRuleEntity rule) {
        repository.insertRule(rule);
    }

    public void deleteRule(AppRuleEntity rule) {
        repository.deleteRule(rule);
    }

    public void deleteRuleByPackage(String packageName) {
        repository.deleteRuleByPackage(packageName);
    }

    public LiveData<AppRuleEntity> getRule(String packageName) {
        return repository.getRule(packageName);
    }

    public LiveData<String> getFilterPackage() {
        return filterPackage;
    }

    public LiveData<List<AppRuleEntity>> getAllRules() {
        return repository.getAllRules();
    }

    private boolean isListIdentical(List<NotificationEntity> list1, List<NotificationEntity> list2) {
        if (list1 == list2) return true;
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            NotificationEntity e1 = list1.get(i);
            NotificationEntity e2 = list2.get(i);
            if (e1.id != e2.id ||
                e1.isRead != e2.isRead ||
                e1.isFavorite != e2.isFavorite ||
                e1.duplicateCount != e2.duplicateCount ||
                e1.timestamp != e2.timestamp ||
                !equalsNullable(e1.imagePath, e2.imagePath)) {
                return false;
            }
        }
        return true;
    }

    private boolean equalsNullable(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        coordinatorExecutor.shutdownNow();
        parallelDecryptionPool.shutdownNow();
    }
}
