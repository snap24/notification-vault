package com.zygisk_enc.notivault.util;

import android.content.Context;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.SearchTokenEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlindIndexManager {

    private static final AtomicBoolean isIndexing = new AtomicBoolean(false);

    public static void ensureDatabaseIndexed(Context context) {
        if (context == null) return;
        if (!isIndexing.compareAndSet(false, true)) return;

        Context appCtx = context.getApplicationContext();
        AppExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appCtx);
                final int batchSize = 250;

                while (true) {
                    List<NotificationEntity> unindexed = db.notificationDao().getUnindexedNotifications(batchSize);
                    if (unindexed == null || unindexed.isEmpty()) {
                        break;
                    }

                    List<SearchTokenEntity> allTokens = new ArrayList<>(unindexed.size() * 10);

                    for (NotificationEntity entity : unindexed) {
                        String decTitle = entity.decryptedTitle;
                        if (decTitle == null && entity.title != null) {
                            decTitle = EncryptionHelper.decrypt(entity.title);
                        }

                        boolean isUnrecoverable = EncryptionHelper.isEncrypted(decTitle);

                        String decText = entity.decryptedText;
                        String decBigText = entity.decryptedBigText;

                        if (!isUnrecoverable) {
                            if (decText == null && entity.text != null) {
                                decText = EncryptionHelper.decrypt(entity.text);
                            }
                            if (decBigText == null && entity.bigText != null) {
                                decBigText = EncryptionHelper.decrypt(entity.bigText);
                            }
                        } else {
                            // Do not tokenize encrypted ciphertext into search tokens
                            decTitle = null;
                            decText = null;
                            decBigText = null;
                        }

                        Set<Long> tokens = BlindIndexHelper.extractTokenHashesForNotification(
                                entity.appName, decTitle, decText, decBigText);

                        if (tokens != null && !tokens.isEmpty()) {
                            for (Long hash : tokens) {
                                allTokens.add(new SearchTokenEntity(hash, entity.id));
                            }
                        } else {
                            // Sentinel token (hash=0L) marks notification as indexed in search_tokens table
                            // so NOT EXISTS query never returns it again, completely preventing infinite loops.
                            allTokens.add(new SearchTokenEntity(0L, entity.id));
                        }
                    }

                    if (!allTokens.isEmpty()) {
                        db.searchTokenDao().insertAll(allTokens);
                    }

                    if (unindexed.size() < batchSize) {
                        break;
                    }

                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isIndexing.set(false);
            }
        });
    }
}
