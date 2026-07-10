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
                // Encrypt the toast text using our local Keystore
                String encryptedText = EncryptionHelper.encrypt(text);
                
                // Get app name from package name
                String appName = getAppName(packageName);
                
                ToastEntity toast = new ToastEntity(packageName, appName, encryptedText, System.currentTimeMillis());
                AppDatabase.getInstance(this).toastDao().insert(toast);
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
}
