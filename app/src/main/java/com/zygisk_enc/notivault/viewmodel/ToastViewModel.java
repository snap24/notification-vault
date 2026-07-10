package com.zygisk_enc.notivault.viewmodel;

import android.app.Application;
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

    private final java.util.concurrent.ExecutorService decryptionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

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

    public void setDateFilter(Long start, Long end) {
        filterDateStart.setValue(start);
        filterDateEnd.setValue(end);
    }

    private void filterAndDecrypt(List<ToastEntity> list) {
        if (list == null) {
            toasts.setValue(null);
            return;
        }

        decryptionExecutor.execute(() -> {
            try {
                Long dateStart = filterDateStart.getValue();
                Long dateEnd = filterDateEnd.getValue();
                List<ToastEntity> filtered = new ArrayList<>();

                for (ToastEntity entity : list) {
                    // 1. Filter by date
                    if (dateStart != null && dateEnd != null) {
                        if (entity.timestamp < dateStart || entity.timestamp > dateEnd) {
                            continue;
                        }
                    }

                    // 2. Decrypt text
                    if (entity.decryptedText == null) {
                        entity.decryptedText = EncryptionHelper.decrypt(entity.text);
                    }

                    filtered.add(entity);
                }

                toasts.postValue(filtered);
            } catch (Exception e) {
                e.printStackTrace();
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
