package com.zygisk_enc.notivault.viewmodel;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.MediatorLiveData;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.repository.NotificationRepository;
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
            String query = searchQuery.getValue();
            String pkg = filterPackage.getValue();

            if (query == null || query.isEmpty()) {
                if (pkg != null && !pkg.isEmpty()) {
                    currentSource = repository.getNotificationsByPackage(pkg);
                } else {
                    currentSource = repository.getAllNotifications();
                }
            } else {
                currentSource = repository.searchNotifications(query);
            }
        }

        notifications.addSource(currentSource, list -> {
            Long dateStart = filterDateStart.getValue();
            Long dateEnd = filterDateEnd.getValue();
            if (dateStart != null && dateEnd != null) {
                java.util.List<NotificationEntity> filtered = new java.util.ArrayList<>();
                for (NotificationEntity entity : list) {
                    if (entity.timestamp >= dateStart && entity.timestamp <= dateEnd) {
                        filtered.add(entity);
                    }
                }
                notifications.setValue(filtered);
            } else {
                notifications.setValue(list);
            }
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
}
