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
    private static final android.util.LruCache<Long, DecryptedText> decryptedCache = new android.util.LruCache<>(500);

    private final MutableLiveData<Integer> loadProgress = new MutableLiveData<>(-1);
    private final java.util.concurrent.ExecutorService decryptionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    public LiveData<Integer> getLoadProgress() {
        return loadProgress;
    }

    public NotificationViewModel(Application application) {
        super(application);
        repository = new NotificationRepository(application);
        appSummaries = repository.getAppSummaries();
        unreadCount = repository.getUnreadCount();

        notifications.addSource(searchQuery, query -> updateSource());
        notifications.addSource(filterPackage, pkg -> updateSource());
        notifications.addSource(filterFavorites, favs -> updateSource());
        notifications.addSource(filterDateStart, date -> updateSource());
        notifications.addSource(filterDateEnd, date -> updateSource());
    }

    private void updateSource() {
        if (currentSource != null) {
            notifications.removeSource(currentSource);
        }

        Boolean favs = filterFavorites.getValue();
        if (favs != null && favs) {
            currentSource = repository.getFavorites();
        } else {
            String pkg = filterPackage.getValue();
            if (pkg != null && !pkg.isEmpty()) {
                currentSource = repository.getNotificationsByPackage(pkg);
            } else {
                currentSource = repository.getAllNotifications();
            }
        }

        notifications.addSource(currentSource, list -> {
            if (list == null) {
                notifications.setValue(null);
                return;
            }

            decryptionExecutor.execute(() -> {
                try {
                    int total = list.size();
                    if (total == 0) {
                        loadProgress.postValue(-1);
                        notifications.postValue(new java.util.ArrayList<>());
                        return;
                    }

                    // Count how many items actually need decryption (not in cache)
                    int itemsToDecrypt = 0;
                    for (NotificationEntity entity : list) {
                        if (decryptedCache.get(entity.id) == null) {
                            itemsToDecrypt++;
                        }
                    }

                    final boolean showProgress = itemsToDecrypt > 0;
                    if (showProgress) {
                        loadProgress.postValue(0);
                    }

                    Long dateStart = filterDateStart.getValue();
                    Long dateEnd = filterDateEnd.getValue();
                    String query = searchQuery.getValue();
                    String lowerQuery = query != null ? query.toLowerCase().trim() : "";

                    java.util.List<NotificationEntity> filtered = new java.util.ArrayList<>();
                    boolean posted5 = false;
                    boolean posted10 = false;
                    boolean posted20 = false;
                    boolean posted30 = false;
                    
                    for (int i = 0; i < total; i++) {
                        NotificationEntity entity = list.get(i);
                        
                        // Check static cache first
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

                        // Report progress if we are displaying it
                        int progress = ((i + 1) * 100) / total;
                        if (showProgress) {
                            loadProgress.postValue(progress);
                        }

                        // 1. Filter by date
                        if (dateStart != null && dateEnd != null) {
                            if (entity.timestamp < dateStart || entity.timestamp > dateEnd) {
                                continue;
                            }
                        }

                        // 2. Filter by search query (case-insensitive on pre-decrypted fields)
                        if (!lowerQuery.isEmpty()) {
                            boolean appNameMatches = entity.appName != null && entity.appName.toLowerCase().contains(lowerQuery);
                            boolean titleMatches = entity.decryptedTitle != null && entity.decryptedTitle.toLowerCase().contains(lowerQuery);
                            boolean textMatches = entity.decryptedText != null && entity.decryptedText.toLowerCase().contains(lowerQuery);
                            boolean bigTextMatches = entity.decryptedBigText != null && entity.decryptedBigText.toLowerCase().contains(lowerQuery);
                            
                            if (!appNameMatches && !titleMatches && !textMatches && !bigTextMatches) {
                                continue;
                            }
                        }

                        filtered.add(entity);

                        // Progressive rendering: post intermediate snapshots to UI
                        if (progress >= 5 && !posted5) {
                            posted5 = true;
                            notifications.postValue(new java.util.ArrayList<>(filtered));
                        } else if (progress >= 10 && !posted10) {
                            posted10 = true;
                            notifications.postValue(new java.util.ArrayList<>(filtered));
                        } else if (progress >= 20 && !posted20) {
                            posted20 = true;
                            notifications.postValue(new java.util.ArrayList<>(filtered));
                        } else if (progress >= 30 && !posted30) {
                            posted30 = true;
                            notifications.postValue(new java.util.ArrayList<>(filtered));
                        }
                    }

                    // Pre-decryption & filtering complete!
                    if (showProgress) {
                        loadProgress.postValue(100);
                        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                        loadProgress.postValue(-1);
                    } else {
                        loadProgress.postValue(-1);
                    }

                    notifications.postValue(filtered);
                } catch (Exception e) {
                    e.printStackTrace();
                    loadProgress.postValue(-1);
                }
            });
        });
    }

    public LiveData<List<NotificationEntity>> getNotifications() {
        return notifications;
    }

    public LiveData<Boolean> getFilterFavorites() {
        return filterFavorites;
    }

    public void setFilterFavorites(boolean favoritesOnly) {
        filterFavorites.setValue(favoritesOnly);
    }

    public LiveData<List<AppSummary>> getAppSummaries() {
        return appSummaries;
    }

    public LiveData<Integer> getUnreadCount() {
        return unreadCount;
    }

    public void setSearchQuery(String query) {
        searchQuery.setValue(query);
    }

    public void setDateFilter(Long start, Long end) {
        filterDateStart.setValue(start);
        filterDateEnd.setValue(end);
    }
    
    public LiveData<Long> getFilterDateStart() {
        return filterDateStart;
    }

    public void setFilterPackage(String packageName) {
        filterPackage.setValue(packageName);
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

    public void markAsRead(long id) {
        repository.markAsRead(id);
    }

    public void delete(NotificationEntity entity) {
        repository.delete(entity);
    }

    public void deleteById(long id) {
        repository.deleteById(id);
    }

    public void deleteAll() {
        repository.deleteAll();
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
        return repository.getFavorites();
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

    public LiveData<List<AppRuleEntity>> getAllRules() {
        return repository.getAllRules();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        decryptionExecutor.shutdownNow();
    }
}
