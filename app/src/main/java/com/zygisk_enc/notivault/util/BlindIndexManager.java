package com.zygisk_enc.notivault.util;

import android.content.Context;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.SearchTokenEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlindIndexManager {

    private static final AtomicBoolean isIndexing = new AtomicBoolean(false);

    public static void ensureDatabaseIndexed(Context context) {
        if (context == null) return;
        if (!isIndexing.compareAndSet(false, true)) return;

        Context appCtx = context.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appCtx);
                final int batchSize = 100;

                while (true) {
                    List<NotificationEntity> unindexed = db.notificationDao().getUnindexedNotifications(batchSize);
                    if (unindexed == null || unindexed.isEmpty()) {
                        break;
                    }

                    List<SearchTokenEntity> allTokens = new ArrayList<>();
                    for (NotificationEntity entity : unindexed) {
                        String decTitle = entity.decryptedTitle != null ? entity.decryptedTitle : EncryptionHelper.decrypt(entity.title);
                        String decText = entity.decryptedText != null ? entity.decryptedText : EncryptionHelper.decrypt(entity.text);
                        String decBigText = entity.decryptedBigText != null ? entity.decryptedBigText : EncryptionHelper.decrypt(entity.bigText);

                        Set<Long> tokens = BlindIndexHelper.extractTokenHashesForNotification(
                                entity.appName, decTitle, decText, decBigText);
                        for (Long hash : tokens) {
                            allTokens.add(new SearchTokenEntity(hash, entity.id));
                        }
                    }

                    if (!allTokens.isEmpty()) {
                        db.searchTokenDao().insertAll(allTokens);
                    }

                    if (unindexed.size() < batchSize) {
                        break;
                    }

                    // Yield CPU briefly to avoid contention
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isIndexing.set(false);
            }
        });
    }
}
