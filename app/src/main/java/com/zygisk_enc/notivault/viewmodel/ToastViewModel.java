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
    private final MediatorLiveData<List<ToastEntity>> toasts = new MediatorLiveData<>();
    private final LiveData<List<ToastEntity>> rawToastsSource;
    private final MutableLiveData<Integer> loadProgress = new MutableLiveData<>(-1);
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> scrollToTopEvent = new MutableLiveData<>(false);

    private static final LruCache<Long, String> decryptedToastCache = new LruCache<>(5000);
    private final java.util.concurrent.ExecutorService decryptionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile long currentRunToken = 0;

    public ToastViewModel(@NonNull Application application) {
        super(application);
        database = AppDatabase.getInstance(application);
        rawToastsSource = database.toastDao().getAllToasts();

        toasts.addSource(rawToastsSource, list -> filterAndDecrypt(list));
        toasts.addSource(filterDateStart, date -> filterAndDecrypt(rawToastsSource.getValue()));
        toasts.addSource(filterDateEnd, date -> filterAndDecrypt(rawToastsSource.getValue()));
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

    private void filterAndDecrypt(List<ToastEntity> list) {
        final long runToken = ++currentRunToken;

        if (list == null) {
            toasts.setValue(null);
            loadProgress.setValue(-1);
            isLoading.setValue(false);
            return;
        }

        isLoading.setValue(true);

        decryptionExecutor.execute(() -> {
            try {
                if (runToken != currentRunToken) return;

                int total = list.size();
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
                for (ToastEntity entity : list) {
                    if (runToken != currentRunToken) return;
                    if (decryptedToastCache.get(entity.id) == null && entity.decryptedText == null) {
                        itemsToDecrypt++;
                    }
                }

                final boolean showProgress = itemsToDecrypt > 0;
                if (showProgress && runToken == currentRunToken) {
                    loadProgress.postValue(0);
                }

                Long dateStart = filterDateStart.getValue();
                Long dateEnd = filterDateEnd.getValue();
                List<ToastEntity> filtered = new ArrayList<>();

                boolean posted5 = false;
                boolean posted10 = false;
                boolean posted20 = false;
                boolean posted30 = false;

                for (int i = 0; i < total; i++) {
                    if (runToken != currentRunToken) return;

                    ToastEntity entity = list.get(i);

                    // 1. Filter by date FIRST
                    if (dateStart != null && dateEnd != null) {
                        if (entity.timestamp < dateStart || entity.timestamp > dateEnd) {
                            continue;
                        }
                    }

                    // 2. Check cache / decrypt text
                    String cached = decryptedToastCache.get(entity.id);
                    if (cached != null) {
                        entity.decryptedText = cached;
                    } else if (entity.decryptedText == null) {
                        entity.decryptedText = EncryptionHelper.decrypt(entity.text);
                        if (entity.decryptedText != null) {
                            decryptedToastCache.put(entity.id, entity.decryptedText);
                        }
                    }

                    int progress = ((i + 1) * 100) / total;
                    if (showProgress && runToken == currentRunToken) {
                        loadProgress.postValue(progress);
                    }

                    filtered.add(entity);

                    // Progressive batch rendering in sets
                    if (progress >= 5 && !posted5) {
                        posted5 = true;
                        if (runToken == currentRunToken) {
                            toasts.postValue(new ArrayList<>(filtered));
                        }
                    } else if (progress >= 10 && !posted10) {
                        posted10 = true;
                        if (runToken == currentRunToken) {
                            toasts.postValue(new ArrayList<>(filtered));
                        }
                    } else if (progress >= 20 && !posted20) {
                        posted20 = true;
                        if (runToken == currentRunToken) {
                            toasts.postValue(new ArrayList<>(filtered));
                        }
                    } else if (progress >= 30 && !posted30) {
                        posted30 = true;
                        if (runToken == currentRunToken) {
                            toasts.postValue(new ArrayList<>(filtered));
                        }
                    }
                }

                if (runToken == currentRunToken) {
                    toasts.postValue(filtered);
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
        com.zygisk_enc.notivault.util.AppExecutor.execute(() -> database.toastDao().deleteAll());
    }

    public LiveData<Long> getOldestTimestamp() {
        return database.toastDao().getOldestTimestamp();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        decryptionExecutor.shutdownNow();
    }
}
