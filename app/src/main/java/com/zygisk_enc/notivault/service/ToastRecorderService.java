package com.zygisk_enc.notivault.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.ToastEntity;
import com.zygisk_enc.notivault.util.EncryptionHelper;

public class ToastRecorderService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            if (className != null && (className.toString().contains("android.widget.Toast") 
                    || className.toString().contains("android.widget.Toast$TN"))) {
                
                String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "unknown";
                
                // Get the text inside the toast
                if (event.getText() != null && !event.getText().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (CharSequence text : event.getText()) {
                        sb.append(text).append(" ");
                    }
                    String toastText = sb.toString().trim();
                    
                    if (!toastText.isEmpty()) {
                        // Store the toast!
                        saveToast(packageName, toastText);
                    }
                }
            }
        }
    }

    private void saveToast(String packageName, String text) {
        com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(this);
                long now = System.currentTimeMillis();
                ToastEntity lastToast = db.toastDao().getLatestToastSync();
                boolean pkgMatches = lastToast != null && packageName != null && packageName.equals(lastToast.packageName);

                boolean isDuplicate = false;
                if (lastToast != null && pkgMatches) {
                    String lastDecrypted = EncryptionHelper.decrypt(lastToast.text);
                    if (text.equals(lastDecrypted)) {
                        if (Math.abs(now - lastToast.timestamp) <= 1500L) {
                            // System accessibility redraw of the same toast within 1.5s -> ignore
                            return;
                        }
                        isDuplicate = true;
                        int newCount = Math.max(1, lastToast.duplicateCount) + 1;
                        db.toastDao().updateDuplicate(lastToast.id, newCount, now);
                    }
                }

                if (!isDuplicate) {
                    // Get app name from package name
                    String appName = getAppName(packageName);

                    ToastEntity toast = new ToastEntity(packageName, appName, text, now);
                    toast.duplicateCount = 1;
                    db.toastDao().insert(toast);
                }

                com.zygisk_enc.notivault.widget.WidgetHelper.updateAllWidgets(ToastRecorderService.this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onTaskRemoved(android.content.Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        com.zygisk_enc.notivault.util.AppLockManager.reset();
    }
}
