package com.zygisk_enc.notivault.util;

import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.service.NotiVaultTileService;
import com.zygisk_enc.notivault.widget.WidgetHelper;
import java.util.concurrent.Executor;

public class AuthActionActivity extends AppCompatActivity {

    public static final String EXTRA_ACTION = "auth_action";
    public static final String ACTION_TOGGLE_CAPTURE = "toggle_capture";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("biometric_lock", false);

        if (!isBiometricEnabled) {
            performAction();
            finish();
            return;
        }

        promptBiometricAuth();
    }

    private void promptBiometricAuth() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        finish();
                    }

                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        performAction();
                        finish();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.auth_dialog_title))
                .setSubtitle(getString(R.string.auth_toggle_capture_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG |
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void performAction() {
        String action = getIntent().getStringExtra(EXTRA_ACTION);
        if (ACTION_TOGGLE_CAPTURE.equals(action)) {
            boolean current = PreferenceUtil.isCaptureEnabled(this);
            boolean next = !current;
            PreferenceUtil.setCaptureEnabled(this, next);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(
                        this,
                        new ComponentName(this, NotiVaultTileService.class)
                );
            }

            Toast.makeText(this, next ? R.string.capture_resumed_toast : R.string.capture_paused_toast, Toast.LENGTH_SHORT).show();
            ShortcutHelper.updateDynamicShortcuts(this);
            WidgetHelper.updateAllWidgets(this);
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
