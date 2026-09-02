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
    private final MutableLiveData<Integer> filterProfileMode = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> scrollToTopEvent = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> openSearchEvent = new MutableLiveData<>(false);
    private final MediatorLiveData<List<NotificationEntity>> notifications = new MediatorLiveData<>();
    private LiveData<List<NotificationEntity>> currentSource = null;
    private final MediatorLiveData<List<AppSummary>> appSummaries = new MediatorLiveData<>();
    private final LiveData<Integer> unreadCount;
    private final java.util.concurrent.atomic.AtomicBoolean isClearingAll = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public boolean isClearingAll() {
        return isClearingAll.get();
    }

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
    private static final android.util.LruCache<Long, DecryptedText> decryptedCache = new android.util.LruCache<>(25000);
    private static final MutableLiveData<Long> cacheInvalidationEvent = new MutableLiveData<>(0L);

    public static void clearDecryptedCache() {
        decryptedCache.evictAll();
        cacheInvalidationEvent.postValue(System.currentTimeMillis());
    }

    public static class OperationProgress {
        public static final int TYPE_NONE = 0;
        public static final int TYPE_DECRYPTING = 1;
        public static final int TYPE_DELETING = 2;
        public static final int TYPE_IMPORTING = 3;
        public static final int TYPE_BUNDLING = 4;

        public final int type;
        public final int progress;

        public OperationProgress(int type, int progress) {
            this.type = type;
            this.progress = progress;
        }
    }

    private static final MutableLiveData<OperationProgress> globalOperationProgress =
            new MutableLiveData<>(new OperationProgress(OperationProgress.TYPE_NONE, -1));

    public static void setGlobalOperationProgress(int type, int progress) {
        globalOperationProgress.postValue(new OperationProgress(type, progress));
    }

    private static final MutableLiveData<Boolean> postImportVerificationTrigger =
            new MutableLiveData<>(null);

    public static void triggerPostImportVerification() {
        postImportVerificationTrigger.postValue(true);
    }

    public LiveData<Boolean> getPostImportVerificationTrigger() {
        return postImportVerificationTrigger;
    }

    public void clearPostImportVerificationTrigger() {
        postImportVerificationTrigger.postValue(null);
    }

    public static final int PAGE_SIZE = 3000;
    private final MutableLiveData<Integer> loadProgress = new MutableLiveData<>(-1);
    private final MutableLiveData<OperationProgress> operationProgress = globalOperationProgress;
    private long currentRunToken = 0;
    private List<NotificationEntity> lastRawList = null;
    private final MutableLiveData<Integer> filterLimit = new MutableLiveData<>(PAGE_SIZE);

    public LiveData<Integer> getLoadProgress() {
        return loadProgress;
    }

    public LiveData<OperationProgress> getOperationProgress() {
        return operationProgress;
    }

    private boolean isBatchingUpdates = false;

    private LiveData<List<AppSummary>> currentAppSummariesSource = null;

    public NotificationViewModel(Application application) {
        super(application);
        repository = new NotificationRepository(application);
        int initialProfileMode = com.zygisk_enc.notivault.util.PreferenceUtil.getActiveProfileMode(application);
        filterProfileMode.setValue(initialProfileMode);

        updateAppSummariesSource();
        unreadCount = repository.getUnreadCount();

        notifications.addSource(searchQuery, query -> {
            if (!isBatchingUpdates) {
                resetLimit();
                updateSource();
            }
        });
        notifications.addSource(filterPackage, pkg -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterFavorites, favs -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterDateStart, date -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterDateEnd, date -> { if (!isBatchingUpdates) { resetLimit(); updateSource(); } });
        notifications.addSource(filterProfileMode, mode -> {
            if (!isBatchingUpdates) {
                resetLimit();
                updateAppSummariesSource();
                updateSource();
            }
        });
        notifications.addSource(filterLimit, limit -> {
            if (!isBatchingUpdates && !isResettingLimit) {
                updateSource();
            }
        });
        notifications.addSource(cacheInvalidationEvent, token -> {
            if (token != null && token > 0) {
                lastRawList = null;
                updateSource();
            }
        });

        // Connect to ClearAllService in case deletion was running in background
        if (com.zygisk_enc.notivault.service.ClearAllService.isClearingInProgress()) {
            isClearingAll.set(true);
            notifications.setValue(new java.util.ArrayList<>());
            appSummaries.setValue(new java.util.ArrayList<>());
            int curProg = com.zygisk_enc.notivault.service.ClearAllService.getCurrentProgress();
            operationProgress.setValue(new OperationProgress(OperationProgress.TYPE_DELETING, Math.max(0, curProg)));
        }

        com.zygisk_enc.notivault.service.ClearAllService.getProgressLiveData().observeForever(progress -> {
            if (progress != null && progress >= 0) {
                isClearingAll.set(true);
                notifications.postValue(new java.util.ArrayList<>());
                appSummaries.postValue(new java.util.ArrayList<>());
                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, progress));
            } else if (isClearingAll.get()) {
                isClearingAll.set(false);
                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_NONE, -1));
                mainHandler.post(() -> {
                    updateSource();
                    refreshAppSummaries();
                });
            }
        });
    }

    private void updateAppSummariesSource() {
        if (currentAppSummariesSource != null) {
            appSummaries.removeSource(currentAppSummariesSource);
        }
        int mode = filterProfileMode.getValue() != null ? filterProfileMode.getValue() : 0;
        currentAppSummariesSource = repository.getAppSummaries(mode);
        appSummaries.addSource(currentAppSummariesSource, list -> {
            if (isClearingAll.get()) {
                appSummaries.setValue(new java.util.ArrayList<>());
                return;
            }
            appSummaries.setValue(list != null ? list : new java.util.ArrayList<>());
        });
    }

    private void postDecryptionProgress(long runToken, int progress) {
        if (runToken != currentRunToken) return;
        OperationProgress currentOp = operationProgress.getValue();
        if (currentOp != null && (currentOp.type == OperationProgress.TYPE_DELETING || currentOp.type == OperationProgress.TYPE_IMPORTING || currentOp.type == OperationProgress.TYPE_BUNDLING) && currentOp.progress >= 0) {
            return;
        }
        int clampedProgress = Math.min(100, Math.max(0, progress));
        loadProgress.postValue(clampedProgress);
        operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DECRYPTING, clampedProgress));
    }

    private void clearOperationProgress(long runToken) {
        OperationProgress currentOp = operationProgress.getValue();
        if (currentOp != null && (currentOp.type == OperationProgress.TYPE_DELETING || currentOp.type == OperationProgress.TYPE_IMPORTING || currentOp.type == OperationProgress.TYPE_BUNDLING) && currentOp.progress >= 0) {
            return;
        }
        loadProgress.postValue(-1);
        operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_NONE, -1));
    }

    private boolean isResettingLimit = false;

    private void resetLimit() {
        if (filterLimit.getValue() != null && filterLimit.getValue() == PAGE_SIZE) {
            return;
        }
        isResettingLimit = true;
        filterLimit.setValue(PAGE_SIZE);
        isResettingLimit = false;
    }

    private void updateSource() {
        if (currentSource != null) {
            notifications.removeSource(currentSource);
        }

        lastRawList = null;
        ++currentRunToken;

        String rawQuery = searchQuery.getValue();
        boolean isSearching = rawQuery != null && !rawQuery.trim().isEmpty();
        int limit = filterLimit.getValue() != null ? filterLimit.getValue() : PAGE_SIZE;
        Long dateStart = filterDateStart.getValue();
        Long dateEnd = filterDateEnd.getValue();
        int profileMode = filterProfileMode.getValue() != null ? filterProfileMode.getValue() : 0;

        Boolean favs = filterFavorites.getValue();
        int favsOnly = (favs != null && favs) ? 1 : 0;
        String pkg = filterPackage.getValue();
        if (pkg != null && pkg.isEmpty()) pkg = null;

        if (isSearching) {
            java.util.List<Long> searchHashes = com.zygisk_enc.notivault.util.BlindIndexHelper.extractQueryTokenHashes(rawQuery);
            if (searchHashes.isEmpty()) {
                if (favsOnly == 1) {
                    currentSource = repository.getFavorites(limit, dateStart, dateEnd, profileMode);
                } else if (pkg != null) {
                    currentSource = repository.getNotificationsByPackage(pkg, limit, dateStart, dateEnd, profileMode);
                } else {
                    currentSource = repository.getAllNotifications(limit, dateStart, dateEnd, profileMode);
                }
            } else if (searchHashes.size() == 1) {
                currentSource = repository.searchByTokenHash(searchHashes.get(0), pkg, favsOnly, limit, dateStart, dateEnd, profileMode);
            } else {
                currentSource = repository.searchByTokenHashes(searchHashes, searchHashes.size(), pkg, favsOnly, limit, dateStart, dateEnd, profileMode);
            }
        } else if (favsOnly == 1) {
            currentSource = repository.getFavorites(limit, dateStart, dateEnd, profileMode);
        } else if (pkg != null) {
            currentSource = repository.getNotificationsByPackage(pkg, limit, dateStart, dateEnd, profileMode);
        } else {
            currentSource = repository.getAllNotifications(limit, dateStart, dateEnd, profileMode);
        }

        notifications.addSource(currentSource, list -> {
            if (isClearingAll.get()) {
                notifications.setValue(new java.util.ArrayList<>());
                return;
            }
            final long runToken = ++currentRunToken;

            if (list == null) {
                notifications.setValue(null);
                lastRawList = null;
                return;
            }

            if (isListIdentical(list, lastRawList)) {
                return;
            }
            final int previousCount = lastRawList != null ? lastRawList.size() : 0;
            lastRawList = list;

            com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
                try {
                    while (com.zygisk_enc.notivault.util.BundleManager.isBundlingInProgress()) {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                    if (runToken != currentRunToken || isClearingAll.get()) return;

                    final int total = list.size();
                    if (total == 0) {
                        if (runToken == currentRunToken) {
                            loadProgress.postValue(-1);
                            OperationProgress currentOp = operationProgress.getValue();
                            if (currentOp == null || currentOp.type == OperationProgress.TYPE_DECRYPTING || currentOp.type == OperationProgress.TYPE_NONE) {
                                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_NONE, -1));
                            }
                            notifications.postValue(new java.util.ArrayList<>());
                        }
                        return;
                    }

                    postDecryptionProgress(runToken, 0);

                    final boolean isAppendedBatch = (total > previousCount && previousCount > 0);
                    final int batchStartIndex = isAppendedBatch ? previousCount : 0;
                    final int batchSize = isAppendedBatch ? (total - previousCount) : total;

                    String query = searchQuery.getValue();
                    final String lowerQuery = query != null ? query.toLowerCase().trim() : "";
                    final boolean searchingMode = !lowerQuery.isEmpty();

                    final int cores = com.zygisk_enc.notivault.util.AppExecutor.getCpuCores();
                    final java.util.concurrent.atomic.AtomicInteger processedInBatch = new java.util.concurrent.atomic.AtomicInteger(0);
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(cores);

                    final int chunkSize = Math.max(25, (total + cores - 1) / cores);
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
                                    if (runToken != currentRunToken) return;
                                    NotificationEntity entity = list.get(i);
                                    decryptEntity(entity);
                                    if (i >= batchStartIndex) {
                                        int done = processedInBatch.incrementAndGet();
                                        if (done % 25 == 0 || done == batchSize) {
                                            int progress = Math.min(99, (done * 100) / batchSize);
                                            postDecryptionProgress(runToken, progress);
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

                    java.util.List<NotificationEntity> filteredList = new java.util.ArrayList<>(total);
                    for (int i = 0; i < total; i++) {
                        NotificationEntity entity = list.get(i);
                        if (matchesQuery(entity, searchingMode, lowerQuery)) {
                            filteredList.add(entity);
                        }
                    }

                    if (runToken == currentRunToken) {
                        notifications.postValue(filteredList);
                        postDecryptionProgress(runToken, 100);
                        clearOperationProgress(runToken);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    clearOperationProgress(runToken);
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
            filterLimit.setValue(current + PAGE_SIZE);
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

    public LiveData<Integer> getProfileMode() {
        return filterProfileMode;
    }

    public void setProfileMode(int mode) {
        Integer current = filterProfileMode.getValue();
        if (current != null && current == mode) {
            return;
        }
        com.zygisk_enc.notivault.util.PreferenceUtil.setActiveProfileMode(getApplication(), mode);
        filterProfileMode.setValue(mode);
    }

    public void toggleProfileMode() {
        int current = filterProfileMode.getValue() != null ? filterProfileMode.getValue() : 0;
        setProfileMode(current == 0 ? 1 : 0);
    }

    public void refreshAppSummaries() {
        com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
            if (isClearingAll.get()) {
                appSummaries.postValue(new java.util.ArrayList<>());
                return;
            }
            int mode = filterProfileMode.getValue() != null ? filterProfileMode.getValue() : 0;
            List<AppSummary> list = repository.getAppSummariesSync(mode);
            appSummaries.postValue(list != null ? list : new java.util.ArrayList<>());
        });
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
        if (query != null && query.trim().isEmpty()) {
            if (current != null && !current.isEmpty()) {
                searchQuery.setValue(null);
            }
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
        resetLimit();
        lastRawList = null;
        ++currentRunToken;
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
        if (!isClearingAll.compareAndSet(false, true)) {
            return;
        }

        final long runToken = ++currentRunToken;
        decryptedCache.evictAll();
        lastRawList = null;

        notifications.postValue(new java.util.ArrayList<>());
        appSummaries.postValue(new java.util.ArrayList<>());
        searchQuery.postValue(null);
        loadProgress.postValue(-1);
        operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, 0));

        com.zygisk_enc.notivault.service.ClearAllService.start(getApplication());
    }

    public void deleteByDateRange(long startTime, long endTime) {
        final long runToken = ++currentRunToken;
        decryptedCache.evictAll();
        lastRawList = null;
        loadProgress.postValue(-1);
        operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, 0));

        repository.deleteByDateRange(startTime, endTime, new NotificationRepository.ProgressCallback() {
            @Override
            public void onProgress(int progress) {
                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, progress));
            }

            @Override
            public void onComplete() {
                ++currentRunToken;
                decryptedCache.evictAll();
                lastRawList = null;

                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, 100));
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                loadProgress.postValue(-1);
                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_NONE, -1));

                refreshAppSummaries();
            }
        });
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
        int limit = filterLimit.getValue() != null ? filterLimit.getValue() : PAGE_SIZE;
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
        final long runToken = ++currentRunToken;
        decryptedCache.evictAll();
        lastRawList = null;
        loadProgress.postValue(-1);
        operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, 0));

        repository.deleteByPackages(packages, new NotificationRepository.ProgressCallback() {
            @Override
            public void onProgress(int progress) {
                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, progress));
            }

            @Override
            public void onComplete() {
                ++currentRunToken;
                decryptedCache.evictAll();
                lastRawList = null;

                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_DELETING, 100));
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                loadProgress.postValue(-1);
                operationProgress.postValue(new OperationProgress(OperationProgress.TYPE_NONE, -1));

                refreshAppSummaries();
            }
        });
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

    public void ensureEntityDecrypted(NotificationEntity entity) {
        if (entity != null) {
            decryptEntity(entity);
        }
    }

    private void decryptEntity(NotificationEntity entity) {
        if (!EncryptionHelper.isEncrypted(entity.title) && 
            !EncryptionHelper.isEncrypted(entity.text) && 
            !EncryptionHelper.isEncrypted(entity.bigText)) {
            entity.decryptedTitle = entity.title;
            entity.decryptedText = entity.text;
            entity.decryptedBigText = entity.bigText;
            return;
        }
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
    }

    private boolean matchesQuery(NotificationEntity entity, boolean searchingMode, String lowerQuery) {
        int mode = filterProfileMode.getValue() != null ? filterProfileMode.getValue() : 0;
        if (mode == 0 && entity.userId != 0) return false;
        if (mode == 1 && entity.userId == 0) return false;
        if (!searchingMode) return true;
        boolean appNameMatches = entity.appName != null && entity.appName.toLowerCase().contains(lowerQuery);
        boolean titleMatches = entity.decryptedTitle != null && entity.decryptedTitle.toLowerCase().contains(lowerQuery);
        boolean textMatches = entity.decryptedText != null && entity.decryptedText.toLowerCase().contains(lowerQuery);
        boolean bigTextMatches = entity.decryptedBigText != null && entity.decryptedBigText.toLowerCase().contains(lowerQuery);
        return appNameMatches || titleMatches || textMatches || bigTextMatches;
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
                !equalsNullable(e1.bundleId, e2.bundleId) ||
                !equalsNullable(e1.imagePath, e2.imagePath)) {
                return false;
            }
        }
        return true;
    }

    private boolean equalsNullable(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}
