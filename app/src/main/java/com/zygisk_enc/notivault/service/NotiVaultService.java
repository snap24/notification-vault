package com.zygisk_enc.notivault.service;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.NotificationEntity;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import com.zygisk_enc.notivault.util.AppLockManager;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.EncryptionHelper;
import com.zygisk_enc.notivault.util.MetadataHelper;
import java.io.ByteArrayOutputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcelable;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

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

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        // Ignore synthetic Android OS group summary containers (e.g. "WhatsApp", "2 new messages")
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }

        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCS = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (titleCS == null) {
            titleCS = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
        }
        CharSequence textCS = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigTextCS = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence subTextCS = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        CharSequence infoTextCS = extras.getCharSequence(Notification.EXTRA_INFO_TEXT);
        CharSequence summaryTextCS = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);

        String title = titleCS != null ? titleCS.toString().trim() : "";
        String text = textCS != null ? textCS.toString().trim() : "";
        String bigText = bigTextCS != null ? bigTextCS.toString().trim() : null;

        // Fallback for primary text from secondary text fields if text is empty
        if (text.isEmpty()) {
            if (subTextCS != null && !subTextCS.toString().trim().isEmpty()) {
                text = subTextCS.toString().trim();
            } else if (infoTextCS != null && !infoTextCS.toString().trim().isEmpty()) {
                text = infoTextCS.toString().trim();
            } else if (summaryTextCS != null && !summaryTextCS.toString().trim().isEmpty()) {
                text = summaryTextCS.toString().trim();
            }
        }

        long messageTime = sbn.getPostTime();

        // Extract latest message for messaging apps (Discord, Telegram, WhatsApp, Messenger, etc.)
        Parcelable[] messages = (Parcelable[]) extras.get("android.messages");
        if (messages != null && messages.length > 0) {
            Parcelable lastMsgParcel = messages[messages.length - 1];
            if (lastMsgParcel instanceof Bundle) {
                Bundle msgBundle = (Bundle) lastMsgParcel;
                CharSequence sender = msgBundle.getCharSequence("sender");
                CharSequence textVal = msgBundle.getCharSequence("text");
                if (textVal == null || textVal.toString().trim().isEmpty()) {
                    String mimeType = msgBundle.getString("type");
                    if (mimeType != null && mimeType.startsWith("image/")) {
                        textVal = "📷 Photo";
                    }
                }
                
                if (textVal != null && !textVal.toString().trim().isEmpty()) {
                    text = textVal.toString().trim();
                    if (sender != null && !sender.toString().trim().isEmpty()) {
                        bigText = sender.toString().trim() + ": " + text;
                    } else {
                        bigText = text;
                    }
                }

                long msgTime = msgBundle.getLong("time");
                if (msgTime > 0) {
                    messageTime = msgTime;
                }
            }
        }

        // Fallback to text lines (e.g. Gmail, group summaries, batched Discord/Facebook alerts)
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0 && (messages == null || messages.length == 0)) {
            CharSequence lastLine = lines[lines.length - 1];
            if (lastLine != null && !lastLine.toString().trim().isEmpty()) {
                text = lastLine.toString().trim();
                if (bigText == null || bigText.isEmpty()) {
                    bigText = text;
                }
            }
        }

        // Extract picture attachments (if any)
        List<Uri> imageUris = new ArrayList<>();
        Bitmap mainPicture = null;

        // For MessagingStyle (e.g. WhatsApp, Telegram, Signal, Discord):
        if (messages != null && messages.length > 0) {
            long latestMsgTime = messageTime;
            for (Parcelable p : messages) {
                if (p instanceof Bundle) {
                    long t = ((Bundle) p).getLong("time");
                    if (t > latestMsgTime) {
                        latestMsgTime = t;
                    }
                }
            }

            for (Parcelable p : messages) {
                if (p instanceof Bundle) {
                    Bundle msgBundle = (Bundle) p;
                    long msgTime = msgBundle.getLong("time");
                    // Only extract photos belonging to the current burst (within 10s of latest message)
                    if (latestMsgTime > 0 && msgTime > 0 && (latestMsgTime - msgTime) > 10 * 1000L) {
                        continue; // Skip old unread backlog photos
                    }
                    String mimeType = msgBundle.getString("type");
                    if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("sticker/"))) {
                        Uri uri = msgBundle.getParcelable("uri");
                        if (uri != null && !imageUris.contains(uri)) {
                            imageUris.add(uri);
                        }
                    }
                }
            }
        }
        
        // Fallback for standard single notifications (e.g. Screenshots, Instagram posts, MMS, etc.)
        if (imageUris.isEmpty()) {
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

        // Skip truly empty notifications (no text, no bigText, no subText, and no image)
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text) && TextUtils.isEmpty(bigText) && mainPicture == null && imageUris.isEmpty()) {
            return;
        }

        int userId = sbn.getUser() != null ? sbn.getUser().hashCode() : 0;
        String appName = getAppName(packageName, userId);
        long timestamp = messageTime;

        // Encrypt string fields for security
        String encTitle = EncryptionHelper.encrypt(title);
        String encText = EncryptionHelper.encrypt(text);
        String encBigText = EncryptionHelper.encrypt(bigText);

        NotificationEntity entity = new NotificationEntity(
                packageName, appName, encTitle, encText, encBigText, timestamp);
        entity.userId = userId;

        if (PreferenceUtil.isExtendedMetadataEnabled(this)) {
            entity.metadata = MetadataHelper.extractJson(sbn, notification, this);
        }

        final String finalTitle = title;
        final String finalText = text;
        final String finalBigText = bigText;

        final List<Uri> finalImageUris = imageUris;
        final Bitmap finalMainPicture = mainPicture;

        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            
            // Check App Rules before inserting (using original plain-text values)
            AppRuleEntity rule = db.appRuleDao().getRuleSync(entity.packageName);
            if (rule != null) {
                if (rule.isRuleEnabled) {
                    String content = (finalTitle + " " + finalText + " " + (finalBigText != null ? finalBigText : "")).trim();
                    
                    // Check block keywords (blacklist)
                    if (rule.blockKeywords != null && !rule.blockKeywords.trim().isEmpty()) {
                        String[] blockWords = rule.blockKeywords.split(",");
                        for (String word : blockWords) {
                            if (containsWord(content, word)) {
                                return; // Skip recording
                            }
                        }
                    }
                    
                    // Check allow keywords (whitelist)
                    if (rule.allowKeywords != null && !rule.allowKeywords.trim().isEmpty()) {
                        String[] allowWords = rule.allowKeywords.split(",");
                        boolean matched = false;
                        for (String word : allowWords) {
                            if (containsWord(content, word)) {
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            return; // None of the allow keywords matched, skip recording
                        }
                    }
                } else if (rule.blockAll) {
                    return; // Skip recording entirely
                }
            }

            // Check for duplicate consecutive notifications against the global latest notification card
            NotificationEntity lastNotif = db.notificationDao().getLatestNotificationSync();
            boolean pkgMatches = lastNotif != null && entity.packageName != null && entity.packageName.equals(lastNotif.packageName);
            
            // Background thread image extraction
            List<byte[]> incomingPlainByteList = new ArrayList<>();
            if (finalImageUris != null && !finalImageUris.isEmpty()) {
                for (Uri u : finalImageUris) {
                    Bitmap bmp = getBitmapFromUri(u);
                    if (bmp != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                        incomingPlainByteList.add(baos.toByteArray());
                    }
                }
            }
            if (incomingPlainByteList.isEmpty() && finalMainPicture != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                finalMainPicture.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                incomingPlainByteList.add(baos.toByteArray());
            }

            // Suppress images that are already saved in lastNotif's imagePath
            List<byte[]> newImagesToSave = new ArrayList<>();
            for (byte[] b : incomingPlainByteList) {
                if (lastNotif != null && pkgMatches && lastNotif.imagePath != null && isDuplicateImage(b, lastNotif.imagePath)) {
                    continue; // Already saved in this album
                }
                newImagesToSave.add(b);
            }

            boolean isDuplicate = false;
            boolean isPhotoSessionCoalesced = false;
            boolean isIncomingImage = !incomingPlainByteList.isEmpty();
            boolean lastIsImage = (lastNotif != null && pkgMatches && lastNotif.imagePath != null && !lastNotif.imagePath.isEmpty());
            boolean isMediaEvent = isIncomingImage || isPhotoMessageText(finalText) || isPhotoMessageText(finalBigText);

            if (lastNotif != null && pkgMatches) {
                // Decrypt last recorded values to compare plain texts
                String lastTitle = EncryptionHelper.decrypt(lastNotif.title);
                String lastText = EncryptionHelper.decrypt(lastNotif.text);
                
                boolean titleMatches = (finalTitle == null && lastTitle == null) || (finalTitle != null && finalTitle.equals(lastTitle));
                boolean textMatches = (finalText == null && lastText == null) || (finalText != null && finalText.equals(lastText));
                boolean photoTimeMatches = Math.abs(entity.timestamp - lastNotif.timestamp) <= 15 * 1000L;
                boolean lastIsPhotoPlaceholder = (lastNotif.imagePath == null || lastNotif.imagePath.isEmpty()) && isPhotoMessageText(lastText);

                // 1. IMAGE SESSION COALESCING: Group/upgrade if previous is an active image card or photo placeholder within 15s
                if ((lastIsImage || lastIsPhotoPlaceholder) && titleMatches && photoTimeMatches && (isIncomingImage || isMediaEvent)) {
                    String updatedImagePath = lastNotif.imagePath;
                    for (byte[] newBytes : newImagesToSave) {
                        String savedPath = saveEncryptedBytesToFile(newBytes, entity.packageName);
                        if (savedPath != null) {
                            if (updatedImagePath == null || updatedImagePath.isEmpty()) {
                                updatedImagePath = savedPath;
                            } else if (!updatedImagePath.contains(savedPath)) {
                                updatedImagePath = updatedImagePath + "|" + savedPath;
                            }
                        }
                    }
                    int newCount = (updatedImagePath != null && !updatedImagePath.isEmpty())
                            ? updatedImagePath.split("\\|").length : 1;

                    // Format text to show the aggregate count: "📷 X photos" (or preserve user caption if present)
                    String displayText;
                    if (finalBigText != null && !isGenericPhotoText(finalBigText)) {
                        displayText = finalBigText;
                    } else if (finalText != null && !isGenericPhotoText(finalText)) {
                        displayText = finalText;
                    } else {
                        displayText = (newCount > 1 ? "📷 " + newCount + " photos" : "📷 Photo");
                    }

                    String encDisplay = EncryptionHelper.encrypt(displayText);
                    db.notificationDao().updatePhotoSession(lastNotif.id, encDisplay, encDisplay, entity.timestamp, updatedImagePath, newCount);
                    isPhotoSessionCoalesced = true;

                // 2. TEXT DUPLICATE MERGING: Group consecutive identical text messages
                } else if (!isIncomingImage && !lastIsImage && !isMediaEvent && titleMatches && textMatches) {
                    if (Math.abs(entity.timestamp - lastNotif.timestamp) <= 1500L) {
                        // OS redraw / system update of the same notification within 1.5s -> ignore
                        return;
                    }
                    isDuplicate = true;
                    db.notificationDao().updateDuplicate(lastNotif.id, lastNotif.duplicateCount + 1, entity.timestamp);
                }
            }

            if (!isDuplicate && !isPhotoSessionCoalesced) {
                // Save bitmaps to files in background (which will encrypt them)
                if (!newImagesToSave.isEmpty()) {
                    StringBuilder imagePathsBuilder = new StringBuilder();
                    for (byte[] b : newImagesToSave) {
                        String savedPath = saveEncryptedBytesToFile(b, entity.packageName);
                        if (savedPath != null) {
                            if (imagePathsBuilder.length() > 0) imagePathsBuilder.append("|");
                            imagePathsBuilder.append(savedPath);
                        }
                    }
                    if (imagePathsBuilder.length() > 0) {
                        entity.imagePath = imagePathsBuilder.toString();
                        int photoCount = entity.imagePath.split("\\|").length;
                        if (photoCount > 1) {
                            entity.duplicateCount = photoCount;
                        }
                        if (isGenericPhotoText(finalText) && (finalBigText == null || isGenericPhotoText(finalBigText))) {
                            String caption = (photoCount > 1 ? "📷 " + photoCount + " photos" : "📷 Photo");
                            entity.text = EncryptionHelper.encrypt(caption);
                            entity.bigText = EncryptionHelper.encrypt(caption);
                        }
                    }
                }
                long rowId = db.notificationDao().insert(entity);
                if (rowId > 0) {
                    java.util.Set<Long> tokenHashes = com.zygisk_enc.notivault.util.BlindIndexHelper.extractTokenHashesForNotification(
                            entity.appName, finalTitle, finalText, finalBigText);
                    if (!tokenHashes.isEmpty()) {
                        java.util.List<com.zygisk_enc.notivault.database.SearchTokenEntity> tokens = new java.util.ArrayList<>(tokenHashes.size());
                        for (Long hash : tokenHashes) {
                            tokens.add(new com.zygisk_enc.notivault.database.SearchTokenEntity(hash, rowId));
                        }
                        db.searchTokenDao().insertAll(tokens);
                    }
                }
            }
            
            // Throttled auto-deletion (runs at most once every 24 hours)
            long lastDelete = PreferenceUtil.getLastAutoDeleteTime(this);
            long now = System.currentTimeMillis();
            if (now - lastDelete >= 24 * 60 * 60 * 1000L) {
                com.zygisk_enc.notivault.util.AutoDeleteDialogHelper.executeAutoDelete(this, db);
            }

            com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(NotiVaultService.this);
        });
    }

    private void deleteEncryptedFile(String imagePath) {
        if (imagePath != null && !imagePath.isEmpty()) {
            String[] paths = imagePath.split("\\|");
            for (String p : paths) {
                if (p != null && !p.trim().isEmpty()) {
                    try {
                        java.io.File f = new java.io.File(p.trim());
                        if (f.exists()) f.delete();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private String getAppName(String packageName, int userId) {
        return com.zygisk_enc.notivault.util.ProfileUtil.getAppLabel(this, packageName, userId, null);
    }

    private boolean isExcluded(String packageName) {
        if (getPackageName().equals(packageName)) return true;
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

    private String saveEncryptedBytesToFile(byte[] plainBytes, String packageName) {
        if (plainBytes == null || plainBytes.length == 0) return null;
        try {
            String filename = "img_" + packageName + "_" + System.currentTimeMillis() + "_" + System.nanoTime() + ".jpg";
            java.io.File file = new java.io.File(getFilesDir(), filename);
            boolean success = EncryptionHelper.encryptFile(plainBytes, file);
            if (success) {
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String saveBitmapToFile(Bitmap bitmap, String packageName) {
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            byte[] plainBytes = baos.toByteArray();
            return saveEncryptedBytesToFile(plainBytes, packageName);
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

    private boolean isGenericPhotoText(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        String t = text.trim().toLowerCase();
        return t.equals("📷 photo") || t.equals("photo") || t.equals("📷 photos") 
                || t.equals("photos") || t.equals("📷 image") || t.equals("image")
                || t.equals("📷 sticker") || t.equals("sticker") || t.equals("📷 video")
                || t.equals("video") || t.matches(".*\\d+\\s*(new\\s*)?(photos?|images?|videos?).*")
                || t.matches("📷\\s*\\d+\\s*(photos?|images?|videos?).*")
                || t.matches(".*\\d+\\s*new\\s*messages?.*");
    }

    private boolean isPhotoMessageText(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        return t.startsWith("📷") || t.contains("photo") || t.contains("photos") 
                || t.contains("image") || t.contains("images") || t.contains("sticker") 
                || t.contains("stickers") || t.contains("gif") || t.contains("media") 
                || t.contains("picture") || t.contains("video");
    }

    private static String computeSha256(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isDuplicateImage(byte[] incomingBytes, String existingImagePaths) {
        if (incomingBytes == null || existingImagePaths == null || existingImagePaths.isEmpty()) return false;
        try {
            String incomingHash = computeSha256(incomingBytes);
            if (incomingHash == null) return false;

            String[] paths = existingImagePaths.split("\\|");
            for (String p : paths) {
                if (p == null || p.trim().isEmpty()) continue;
                java.io.File file = new java.io.File(p.trim());
                if (!file.exists()) continue;

                byte[] decryptedBytes = EncryptionHelper.decryptFile(file);
                if (decryptedBytes == null) continue;

                // Step 1: Byte Length Check (0 CPU time)
                if (incomingBytes.length != decryptedBytes.length) {
                    continue; // Sizes differ -> different images
                }

                // Step 2 & 3: Fast SHA-256 Hash Matching
                String savedHash = computeSha256(decryptedBytes);
                if (incomingHash.equals(savedHash)) {
                    return true; // 100% Identical duplicate image!
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean containsWord(String content, String keyword) {
        if (content == null || keyword == null) return false;
        String cleanKeyword = keyword.trim();
        if (cleanKeyword.isEmpty()) return false;
        try {
            // Match standalone whole word surrounded by start/end of string, whitespace, or punctuation
            String regex = "(?i)(^|[\\s\\p{Punct}])" + Pattern.quote(cleanKeyword) + "([\\s\\p{Punct}]|$)";
            Pattern pattern = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);
            return pattern.matcher(content).find();
        } catch (Exception e) {
            return content.toLowerCase().contains(cleanKeyword.toLowerCase());
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        AppLockManager.reset();
    }
}
