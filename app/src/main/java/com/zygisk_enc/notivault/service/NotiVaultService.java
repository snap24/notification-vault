package com.zygisk_enc.notivault.service;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import java.io.ByteArrayOutputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcelable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotiVaultService extends NotificationListenerService {

    private static final String[] EXCLUDED_PACKAGES = {
            "com.zygisk_enc.notivault",
            "android"
    };

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (!PreferenceUtil.isCaptureEnabled(this)) return;

        String packageName = sbn.getPackageName();
        if (isExcluded(packageName)) return;

        // Skip ongoing / foreground service notifications
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        
        // Skip group summaries (e.g. "X new messages") to prevent duplicate aggregate logs
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCS = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCS = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigTextCS = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

        String title = titleCS != null ? titleCS.toString().trim() : "";
        String text = textCS != null ? textCS.toString().trim() : "";
        String bigText = bigTextCS != null ? bigTextCS.toString().trim() : null;

        long messageTime = sbn.getPostTime();

        // Extract only the newest message and its unique timestamp if available
        Parcelable[] messages = (Parcelable[]) extras.get("android.messages");
        if (messages != null && messages.length > 0) {
            Parcelable lastMsgParcel = messages[messages.length - 1];
            if (lastMsgParcel instanceof Bundle) {
                Bundle msgBundle = (Bundle) lastMsgParcel;
                CharSequence textVal = msgBundle.getCharSequence("text");
                // Fallback representation for empty text (e.g. image/sticker)
                if (textVal == null || textVal.toString().trim().isEmpty()) {
                    String mimeType = msgBundle.getString("type");
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        textVal = "📷 Photo";
                    }
                }
                
                if (textVal != null) {
                    bigText = textVal.toString().trim();
                }

                long msgTime = msgBundle.getLong("time");
                if (msgTime > 0) {
                    messageTime = msgTime;
                }
            }
        }

        // Fallback to text lines (e.g. Gmail)
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder linesBuilder = new StringBuilder();
            for (CharSequence line : lines) {
                if (line != null) {
                    if (linesBuilder.length() > 0) {
                        linesBuilder.append("\n");
                    }
                    linesBuilder.append(line);
                }
            }
            if (linesBuilder.length() > 0 && (bigText == null || bigText.isEmpty())) {
                bigText = linesBuilder.toString();
            }
        }

        // Skip entirely empty notifications
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;

        // Extract picture attachments (if any)
        Uri imageUri = null;
        long imageMsgTime = 0;
        Bitmap chatPictureFallback = null;
        Bitmap mainPicture = null;

        if (messages != null && messages.length > 0) {
            // Find the newest message that contains an image
            for (int i = messages.length - 1; i >= 0; i--) {
                Parcelable msgParcel = messages[i];
                if (msgParcel instanceof Bundle) {
                    Bundle msgBundle = (Bundle) msgParcel;
                    String mimeType = msgBundle.getString("type");
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        // Get the URI and timestamp
                        imageUri = msgBundle.getParcelable("uri");
                        imageMsgTime = msgBundle.getLong("time");

                        // Extract fallbacks on the main thread in case URI decoding fails
                        if (extras.containsKey(Notification.EXTRA_PICTURE)) {
                            Object picture = extras.get(Notification.EXTRA_PICTURE);
                            if (picture instanceof Bitmap) {
                                chatPictureFallback = (Bitmap) picture;
                            } else if (picture instanceof Icon) {
                                chatPictureFallback = getBitmapFromIcon((Icon) picture);
                            }
                        }
                        if (chatPictureFallback == null && extras.containsKey("android.pictureIcon")) {
                            Object pictureIconObj = extras.get("android.pictureIcon");
                            if (pictureIconObj instanceof Icon) {
                                chatPictureFallback = getBitmapFromIcon((Icon) pictureIconObj);
                            }
                        }
                        if (chatPictureFallback == null && extras.containsKey(Notification.EXTRA_LARGE_ICON)) {
                            Object largeIconObj = extras.get(Notification.EXTRA_LARGE_ICON);
                            if (largeIconObj instanceof Bitmap) {
                                chatPictureFallback = (Bitmap) largeIconObj;
                            } else if (largeIconObj instanceof Icon) {
                                chatPictureFallback = getBitmapFromIcon((Icon) largeIconObj);
                            }
                        }
                        break;
                    }
                }
            }
        } else {
            // Non-chat notification fallback: extract directly from picture extras
            if (extras.containsKey(Notification.EXTRA_PICTURE)) {
                Object picture = extras.get(Notification.EXTRA_PICTURE);
                if (picture instanceof Bitmap) {
                    mainPicture = (Bitmap) picture;
                } else if (picture instanceof Icon) {
                    mainPicture = getBitmapFromIcon((Icon) picture);
                }
            }
            if (mainPicture == null && extras.containsKey("android.pictureIcon")) {
                Object pictureIconObj = extras.get("android.pictureIcon");
                if (pictureIconObj instanceof Icon) {
                    mainPicture = getBitmapFromIcon((Icon) pictureIconObj);
                }
            }
        }

        String appName = getAppName(packageName);
        long timestamp = messageTime;

        // Encrypt string fields for security
        String encTitle = EncryptionHelper.encrypt(title);
        String encText = EncryptionHelper.encrypt(text);
        String encBigText = EncryptionHelper.encrypt(bigText);

        NotificationEntity entity = new NotificationEntity(
                packageName, appName, encTitle, encText, encBigText, timestamp);

        final String finalTitle = title;
        final String finalText = text;
        final String finalBigText = bigText;

        final Uri finalImageUri = imageUri;
        final long finalImageMsgTime = imageMsgTime;
        final Bitmap finalChatPictureFallback = chatPictureFallback;
        final Bitmap finalMainPicture = mainPicture;

        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            
            // Check App Rules before inserting (using original plain-text values)
            AppRuleEntity rule = db.appRuleDao().getRuleSync(entity.packageName);
            if (rule != null) {
                if (rule.blockAll) {
                    return; // Skip recording entirely
                }
                
                if (rule.isRuleEnabled) {
                    String content = (finalTitle + " " + finalText + " " + (finalBigText != null ? finalBigText : "")).toLowerCase();
                    
                    // Check block keywords (blacklist)
                    if (rule.blockKeywords != null && !rule.blockKeywords.trim().isEmpty()) {
                        String[] blockWords = rule.blockKeywords.split(",");
                        for (String word : blockWords) {
                            String cleanWord = word.trim().toLowerCase();
                            if (!cleanWord.isEmpty() && content.contains(cleanWord)) {
                                return; // Skip recording
                            }
                        }
                    }
                    
                    // Check allow keywords (whitelist)
                    if (rule.allowKeywords != null && !rule.allowKeywords.trim().isEmpty()) {
                        String[] allowWords = rule.allowKeywords.split(",");
                        boolean matched = false;
                        for (String word : allowWords) {
                            String cleanWord = word.trim().toLowerCase();
                            if (!cleanWord.isEmpty() && content.contains(cleanWord)) {
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            return; // None of the allow keywords matched, skip recording
                        }
                    }
                }
            }

            // Check for duplicate consecutive notifications from the same app (within 10 minutes)
            NotificationEntity lastNotif = db.notificationDao().getLastNotificationForPackage(entity.packageName);
            
            // Background thread image extraction & timestamp validation
            Bitmap finalBitmap = null;
            if (finalImageUri != null) {
                boolean isNewImage = true;
                if (lastNotif != null && finalImageMsgTime > 0 && finalImageMsgTime <= lastNotif.timestamp) {
                    isNewImage = false; // Already recorded this image in a previous notification update
                }
                if (isNewImage) {
                    finalBitmap = getBitmapFromUri(finalImageUri);
                    if (finalBitmap == null) {
                        finalBitmap = finalChatPictureFallback;
                    }
                }
            } else if (finalMainPicture != null) {
                finalBitmap = finalMainPicture;
            }

            boolean isDuplicate = false;
            boolean isPhotoSessionCoalesced = false;

            if (lastNotif != null) {
                // Decrypt last recorded values to compare plain texts
                String lastTitle = EncryptionHelper.decrypt(lastNotif.title);
                String lastText = EncryptionHelper.decrypt(lastNotif.text);
                
                boolean titleMatches = (finalTitle == null && lastTitle == null) || (finalTitle != null && finalTitle.equals(lastTitle));
                boolean textMatches = (finalText == null && lastText == null) || (finalText != null && finalText.equals(lastText));
                boolean timeMatches = Math.abs(entity.timestamp - lastNotif.timestamp) <= 10 * 60 * 1000L;
                boolean photoTimeMatches = Math.abs(entity.timestamp - lastNotif.timestamp) <= 20 * 1000L;

                // Photo Session Coalescing: consecutive photo notifications within 20 seconds (ONLY IF NO NEW IMAGE IS EXTRACTED)
                if (finalBitmap == null && titleMatches && photoTimeMatches && isPhotoMessageText(finalText) && isPhotoMessageText(lastText)) {
                    // Ignore exact timestamp matches only if the text is also identical (indicates an OS re-post)
                    if (entity.timestamp == lastNotif.timestamp && finalText != null && finalText.equals(lastText)) {
                        return;
                    }
                    
                    String newImagePath = lastNotif.imagePath;
                    // Update existing record in-place
                    db.notificationDao().updatePhotoSession(lastNotif.id, entity.text, entity.timestamp, newImagePath, lastNotif.duplicateCount + 1);
                    isPhotoSessionCoalesced = true;
                } else if (titleMatches && textMatches) {
                    // Ignore exact timestamp matches (OS re-post)
                    if (entity.timestamp == lastNotif.timestamp) {
                        return;
                    }
                    
                    // If containing an image but not consecutive bulk photos, log separately
                    if (finalBitmap != null) {
                        isDuplicate = false;
                    } else {
                        if (timeMatches) {
                            isDuplicate = true;
                            db.notificationDao().updateDuplicate(lastNotif.id, lastNotif.duplicateCount + 1, entity.timestamp);
                        }
                    }
                }
            }

            if (!isDuplicate && !isPhotoSessionCoalesced) {
                // Save bitmap to file in background (which will encrypt it)
                if (finalBitmap != null) {
                    entity.imagePath = saveBitmapToFile(finalBitmap, entity.packageName);
                }
                db.notificationDao().insert(entity);
            }
            
            // Throttled auto-deletion (runs at most once every 24 hours)
            long lastDelete = PreferenceUtil.getLastAutoDeleteTime(this);
            long now = System.currentTimeMillis();
            if (now - lastDelete >= 24 * 60 * 60 * 1000L) {
                int days = PreferenceUtil.getAutoDeleteDays(this);
                if (days > 0) {
                    long cutoff = now - (days * 24L * 60L * 60L * 1000L);
                    db.notificationDao().deleteOlderThan(cutoff);
                }
                PreferenceUtil.setLastAutoDeleteTime(this, now);
            }
        });
    }

    private String getAppName(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private boolean isExcluded(String packageName) {
        for (String pkg : EXCLUDED_PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
    }

    private Bitmap getBitmapFromIcon(Icon icon) {
        if (icon == null) return null;
        try {
            Drawable drawable = icon.loadDrawable(this);
            if (drawable != null) {
                int width = drawable.getIntrinsicWidth();
                int height = drawable.getIntrinsicHeight();
                if (width <= 0) width = 128;
                if (height <= 0) height = 128;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
                return bitmap;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String saveBitmapToFile(Bitmap bitmap, String packageName) {
        if (bitmap == null) return null;
        try {
            String filename = "img_" + packageName + "_" + System.currentTimeMillis() + ".jpg";
            java.io.File file = new java.io.File(getFilesDir(), filename);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            byte[] plainBytes = baos.toByteArray();
            
            boolean success = EncryptionHelper.encryptFile(plainBytes, file);
            if (success) {
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private Bitmap getBitmapFromUri(Uri uri) {
        if (uri == null) return null;
        try {
            java.io.InputStream is = getContentResolver().openInputStream(uri);
            if (is != null) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                return bmp;
            }
        } catch (Exception e) {
            // Ignore security or permission exceptions for content URIs
        }
        return null;
    }

    private boolean isPhotoMessageText(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        return t.startsWith("📷") || t.contains("photo") || t.contains("photos") || t.contains("image") || t.contains("images");
    }
}
