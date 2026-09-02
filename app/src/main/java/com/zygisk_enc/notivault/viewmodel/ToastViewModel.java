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
    public static final int PAGE_SIZE = 3000;
    private final MutableLiveData<Long> filterDateStart = new MutableLiveData<>(null);
    private final MutableLiveData<Long> filterDateEnd = new MutableLiveData<>(null);
    private final MutableLiveData<String> filterPackage = new MutableLiveData<>(null);
    private final MutableLiveData<Integer> filterLimit = new MutableLiveData<>(PAGE_SIZE);
    private final MediatorLiveData<List<ToastEntity>> toasts = new MediatorLiveData<>();
    private LiveData<List<ToastEntity>> currentSource = null;
    private final LiveData<List<AppSummary>> appSummaries;
    private final MutableLiveData<Integer> loadProgress = new MutableLiveData<>(-1);
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> scrollToTopEvent = new MutableLiveData<>(false);

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
        if (filterLimit.getValue() != null && filterLimit.getValue() == PAGE_SIZE) {
            return;
        }
        isResettingLimit = true;
        filterLimit.setValue(PAGE_SIZE);
        isResettingLimit = false;
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

    private void updateSource() {
        if (currentSource != null) {
            toasts.removeSource(currentSource);
        }

        int limit = filterLimit.getValue() != null ? filterLimit.getValue() : PAGE_SIZE;
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

    private List<ToastEntity> lastRawList = null;

    private void filterAndDecrypt(List<ToastEntity> list) {
        final long runToken = ++currentRunToken;

        if (list == null) {
            toasts.setValue(null);
            lastRawList = null;
            loadProgress.setValue(-1);
            isLoading.setValue(false);
            return;
        }

        final int previousCount = lastRawList != null ? lastRawList.size() : 0;
        lastRawList = list;

        isLoading.setValue(true);

        com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
            if (runToken != currentRunToken) return;

            final int total = list.size();
            if (total == 0) {
                if (runToken == currentRunToken) {
                    toasts.postValue(new ArrayList<>());
                    loadProgress.postValue(-1);
                    isLoading.postValue(false);
                }
                return;
            }

            loadProgress.postValue(0);

            final boolean isAppendedBatch = (total > previousCount && previousCount > 0);
            final int batchStartIndex = isAppendedBatch ? previousCount : 0;
            final int batchSize = isAppendedBatch ? (total - previousCount) : total;

            List<ToastEntity> result = new ArrayList<>(total);

            for (int i = 0; i < total; i++) {
                if (runToken != currentRunToken) return;
                ToastEntity entity = list.get(i);

                if (!EncryptionHelper.isEncrypted(entity.text)) {
                    entity.decryptedText = entity.text;
                } else if (entity.decryptedText == null) {
                    entity.decryptedText = EncryptionHelper.decrypt(entity.text);
                }
                result.add(entity);

                if (i >= batchStartIndex) {
                    int processedInBatch = (i - batchStartIndex) + 1;
                    if (processedInBatch % 25 == 0 || i == total - 1) {
                        int progress = Math.min(99, (processedInBatch * 100) / batchSize);
                        loadProgress.postValue(progress);
                    }
                }
            }

            if (runToken == currentRunToken) {
                toasts.postValue(result);
                loadProgress.postValue(100);
                loadProgress.postValue(-1);
                isLoading.postValue(false);
            }
        });
    }

    public void clearAllToasts() {
        final long runToken = ++currentRunToken;
        toasts.postValue(new ArrayList<>());
        loadProgress.postValue(-1);
        isLoading.postValue(false);
        com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
            database.toastDao().deleteAll();
            database.checkpointAndVacuum();
        });
    }

    public LiveData<Long> getOldestTimestamp() {
        return database.toastDao().getOldestTimestamp();
    }
}
