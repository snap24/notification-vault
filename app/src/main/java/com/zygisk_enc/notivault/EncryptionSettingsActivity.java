package com.zygisk_enc.notivault;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.util.AppExecutor;
import com.zygisk_enc.notivault.util.DatabaseKeyManager;
import java.io.File;

public class EncryptionSettingsActivity extends BaseActivity {

    private MaterialToolbar toolbar;
    private TextView tvStatusTitle;
    private TextView tvStatusSubtitle;
    private TextView tvAlgorithmValue;
    private TextView tvKeyProviderValue;
    private TextView tvKeyAliasValue;
    private TextView tvCaptureStatusValue;
    private TextView tvTotalNotifsValue;
    private TextView tvTotalToastsValue;
    private TextView tvDbSizeValue;
    private MaterialButton btnVerifyIntegrity;
    private MaterialButton btnMasterWipe;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_encryption_settings);

        toolbar = findViewById(R.id.toolbar_encryption);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvStatusTitle = findViewById(R.id.tv_enc_status_title);
        tvStatusSubtitle = findViewById(R.id.tv_enc_status_subtitle);
        tvAlgorithmValue = findViewById(R.id.tv_algorithm_value);
        tvKeyProviderValue = findViewById(R.id.tv_key_provider_value);
        tvKeyAliasValue = findViewById(R.id.tv_key_alias_value);
        tvCaptureStatusValue = findViewById(R.id.tv_capture_status_value);
        tvTotalNotifsValue = findViewById(R.id.tv_total_notifs_value);
        tvTotalToastsValue = findViewById(R.id.tv_total_toasts_value);
        tvDbSizeValue = findViewById(R.id.tv_db_size_value);
        btnVerifyIntegrity = findViewById(R.id.btn_verify_integrity);
        btnMasterWipe = findViewById(R.id.btn_master_wipe);
        progressBar = findViewById(R.id.progress_loading);

        loadMetadata();

        btnVerifyIntegrity.setOnClickListener(v -> runIntegrityCheck());
        btnMasterWipe.setOnClickListener(v -> confirmMasterWipe());
    }

    private void loadMetadata() {
        progressBar.setVisibility(View.VISIBLE);
        AppExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            int notifCount = db.notificationDao().getTotalCountSync();
            int toastCount = db.toastDao().getTotalCountSync();

            File dbFile = getDatabasePath(AppDatabase.DATABASE_NAME);
            long dbBytes = 0;
            if (dbFile != null && dbFile.exists()) {
                dbBytes += dbFile.length();
                File parent = dbFile.getParentFile();
                if (parent != null) {
                    File walFile = new File(parent, AppDatabase.DATABASE_NAME + "-wal");
                    File shmFile = new File(parent, AppDatabase.DATABASE_NAME + "-shm");
                    if (walFile.exists()) dbBytes += walFile.length();
                    if (shmFile.exists()) dbBytes += shmFile.length();
                }
            }
            String sizeStr;
            if (dbBytes < 1024) {
                sizeStr = dbBytes + " B";
            } else if (dbBytes < 1024 * 1024) {
                sizeStr = String.format(java.util.Locale.US, "%.1f KB", dbBytes / 1024.0);
            } else {
                sizeStr = String.format(java.util.Locale.US, "%.2f MB", dbBytes / (1024.0 * 1024.0));
            }

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                tvTotalNotifsValue.setText(String.valueOf(notifCount));
                tvTotalToastsValue.setText(String.valueOf(toastCount));
                tvDbSizeValue.setText(sizeStr);

                tvAlgorithmValue.setText("SQLCipher 256-bit AES-CBC (Page-Level)");
                tvKeyProviderValue.setText(DatabaseKeyManager.getKeyStoreProvider() + " " + getString(R.string.encryption_hardware_spec));
                tvKeyAliasValue.setText(DatabaseKeyManager.getMasterKeyAlias());
                tvCaptureStatusValue.setText(R.string.encryption_status_capture_active);
            });
        });
    }

    private void runIntegrityCheck() {
        if (isFinishing() || isDestroyed()) return;

        btnVerifyIntegrity.setEnabled(false);
        com.zygisk_enc.notivault.util.DatabaseIntegrityHelper.runIntegrityCheck(this, () -> {
            runOnUiThread(() -> {
                btnVerifyIntegrity.setEnabled(true);
                loadMetadata();
            });
        });
    }

    private void confirmMasterWipe() {
        if (isFinishing() || isDestroyed()) return;

        int errorColor = com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorError, 0xFFB00020);
        android.graphics.drawable.Drawable warningDrawable =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_warning);
        if (warningDrawable != null) {
            warningDrawable = warningDrawable.mutate();
            warningDrawable.setTint(errorColor);
        }

        new MaterialAlertDialogBuilder(this)
                .setIcon(warningDrawable)
                .setTitle(R.string.master_wipe_confirm_title)
                .setMessage(R.string.master_wipe_confirm_message)
                .setPositiveButton(R.string.master_wipe_action_wipe, (d, w) -> {
                    boolean isBiometricEnabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                            .getBoolean("biometric_lock", false);
                    if (isBiometricEnabled) {
                        verifyBiometricsToProceed(this::performMasterWipe);
                    } else {
                        performMasterWipe();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void verifyBiometricsToProceed(Runnable onSuccess) {
        java.util.concurrent.Executor executor = androidx.core.content.ContextCompat.getMainExecutor(this);
        androidx.biometric.BiometricPrompt biometricPrompt = new androidx.biometric.BiometricPrompt(this,
                executor, new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@androidx.annotation.NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }
        });

        androidx.biometric.BiometricPrompt.PromptInfo promptInfo = new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.verify_identity))
                .setSubtitle(getString(R.string.master_wipe_confirm_title))
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void performMasterWipe() {
        if (isFinishing() || isDestroyed()) return;

        int errorColor = com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorError, 0xFFB00020);

        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_database_migration, null);
        android.widget.ImageView ivIcon = dialogView.findViewById(R.id.iv_migration_icon);
        android.widget.TextView tvTitle = dialogView.findViewById(R.id.tv_migration_title);
        android.widget.TextView tvMessage = dialogView.findViewById(R.id.tv_migration_message);
        com.google.android.material.progressindicator.LinearProgressIndicator progressIndicator =
                dialogView.findViewById(R.id.progress_migration);
        android.widget.TextView tvProgressText = dialogView.findViewById(R.id.tv_migration_progress_text);

        if (ivIcon != null) {
            ivIcon.setImageResource(R.drawable.ic_warning);
            ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(errorColor));
        }
        tvTitle.setText(R.string.master_wipe_confirm_title);
        tvTitle.setTextColor(errorColor);
        tvMessage.setText(R.string.master_wipe_progress);
        progressIndicator.setIndeterminate(true);
        progressIndicator.setIndicatorColor(errorColor);
        tvProgressText.setVisibility(android.view.View.GONE);

        androidx.appcompat.app.AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();

        com.zygisk_enc.notivault.util.MasterWipeHelper.executeMasterWipe(this, () -> {
            if (progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            Toast.makeText(this, R.string.master_wipe_success, Toast.LENGTH_LONG).show();
            loadMetadata();
        });
    }
}
