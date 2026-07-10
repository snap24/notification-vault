package com.zygisk_enc.notivault.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.util.BackupUtil;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import java.util.concurrent.Executor;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final ActivityResultLauncher<String> exportBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"),
            uri -> {
                if (uri != null) {
                    showExportOptionsDialog(uri);
                }
            }
    );

    private final ActivityResultLauncher<String[]> importBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    showImportPasswordDialog(uri);
                }
            }
    );

    private void showExportOptionsDialog(Uri uri) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_export_backup, null);
        TextInputEditText tietPassword = view.findViewById(R.id.tiet_export_password);
        MaterialSwitch switchIncludeMedia = view.findViewById(R.id.switch_include_media);

        final boolean[] exportCompleted = new boolean[]{false};

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Export", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String password = tietPassword.getText() != null ? tietPassword.getText().toString().trim() : "";
                if (password.isEmpty()) {
                    Toast.makeText(requireContext(), "Password cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }

                boolean includeMedia = switchIncludeMedia.isChecked();

                android.content.Intent intent = new android.content.Intent(requireContext(), com.zygisk_enc.notivault.service.BackupService.class);
                intent.putExtra("uri", uri.toString());
                intent.putExtra("password", password);
                intent.putExtra("includeMedia", includeMedia);
                androidx.core.content.ContextCompat.startForegroundService(requireContext(), intent);

                exportCompleted[0] = true;
                Toast.makeText(requireContext(), 
                        "Export started in background. Check the status bar for progress. Please do not close the app from your recent apps list, as this will cause the export to fail.", 
                        Toast.LENGTH_LONG).show();
                dialog.dismiss();
            });
        });

        dialog.setOnDismissListener(d -> {
            if (!exportCompleted[0]) {
                try {
                    requireContext().getContentResolver().delete(uri, null, null);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        dialog.show();
    }

    private void showImportPasswordDialog(Uri uri) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_import_backup, null);
        TextInputEditText tietPassword = view.findViewById(R.id.tiet_import_password);

        new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Decrypt & Import", (dialog, which) -> {
                    String password = tietPassword.getText() != null ? tietPassword.getText().toString().trim() : "";
                    if (password.isEmpty()) {
                        Toast.makeText(requireContext(), "Password cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Toast.makeText(requireContext(), "Importing backup...", Toast.LENGTH_SHORT).show();

                    BackupUtil.importBackup(requireContext(), uri, password, new BackupUtil.BackupCallback() {
                        @Override
                        public void onSuccess() {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> Toast.makeText(requireContext(), 
                                        R.string.backup_import_success, Toast.LENGTH_SHORT).show());
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> Toast.makeText(requireContext(), 
                                        getString(R.string.backup_import_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                            }
                        }
                    });
                })
                .show();
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        androidx.recyclerview.widget.RecyclerView rv = getListView();
        if (rv != null) {
            rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(), 
                          rv.getPaddingBottom() + (int) (80 * getResources().getDisplayMetrics().density));
            rv.setClipToPadding(false);
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        // Notification Access preference
        Preference notifAccessPref = findPreference("notification_access");
        if (notifAccessPref != null) {
            notifAccessPref.setOnPreferenceClickListener(pref -> {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                return true;
            });
        }

        // Auto-delete preference summary
        ListPreference autoDeletePref = findPreference("auto_delete_days");
        if (autoDeletePref != null) {
            autoDeletePref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        // Theme color preference summary
        ListPreference themeColorPref = findPreference("theme_color");
        if (themeColorPref != null) {
            themeColorPref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        // Biometric Switch lock verification before turning off
        Preference biometricPref = findPreference("biometric_lock");
        if (biometricPref != null) {
            biometricPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean isEnabling = (boolean) newValue;
                if (!isEnabling) {
                    // Require identity verification to disable security lock
                    verifyBiometricsToProceed(() -> {
                        if (preference instanceof SwitchPreferenceCompat) {
                            ((SwitchPreferenceCompat) preference).setChecked(false);
                        }
                    }, "Confirm authentication to disable App Lock");
                    return false; // Intercept: don't toggle yet
                }
                return true; // Let enabling proceed directly
            });
        }

        // Export backup click
        Preference exportPref = findPreference("export_backup");
        if (exportPref != null) {
            exportPref.setOnPreferenceClickListener(pref -> {
                boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getBoolean("biometric_lock", false);
                if (isBiometricEnabled) {
                    verifyBiometricsToProceed(() -> {
                        exportBackupLauncher.launch("notivault_backup_" + System.currentTimeMillis() + ".vault");
                    }, "Confirm authentication to export backup");
                } else {
                    exportBackupLauncher.launch("notivault_backup_" + System.currentTimeMillis() + ".vault");
                }
                return true;
            });
        }

        // Import backup click
        Preference importPref = findPreference("import_backup");
        if (importPref != null) {
            importPref.setOnPreferenceClickListener(pref -> {
                importBackupLauncher.launch(new String[]{"application/json", "application/octet-stream", "*/*"});
                return true;
            });
        }

        // Favorites click listener
        Preference favoritesPref = findPreference("favorites_list");
        if (favoritesPref != null) {
            favoritesPref.setOnPreferenceClickListener(pref -> {
                NotificationViewModel viewModel = new ViewModelProvider(requireActivity()).get(NotificationViewModel.class);
                viewModel.setFilterPackage(null);
                viewModel.setFilterFavorites(true);
                androidx.navigation.Navigation.findNavController(requireView()).navigate(R.id.navigation_history);
                return true;
            });
        }

        // App Rules click listener
        Preference rulesPref = findPreference("app_rules");
        if (rulesPref != null) {
            rulesPref.setOnPreferenceClickListener(pref -> {
                NotificationViewModel viewModel = new ViewModelProvider(requireActivity()).get(NotificationViewModel.class);
                com.zygisk_enc.notivault.util.RuleDialogHelper.showRulesListDialog(requireContext(), getViewLifecycleOwner(), viewModel);
                return true;
            });
        }

        // About preference click listener to open GitHub repository link
        Preference aboutPref = findPreference("about");
        if (aboutPref != null) {
            aboutPref.setOnPreferenceClickListener(pref -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/snap24/notification-vault"));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Failed to open link", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
    }

    private void verifyBiometricsToProceed(Runnable onSuccess, String subtitle) {
        Executor executor = ContextCompat.getMainExecutor(requireContext());
        BiometricPrompt biometricPrompt = new BiometricPrompt(this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify Identity")
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if ("auto_delete_days".equals(preference.getKey())) {
            boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("biometric_lock", false);
            if (isBiometricEnabled) {
                verifyBiometricsToProceed(() -> super.onDisplayPreferenceDialog(preference), "Confirm authentication to change Auto Delete settings");
                return;
            }
        }
        super.onDisplayPreferenceDialog(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("theme_color".equals(key) || "pitch_black".equals(key) || "biometric_lock".equals(key)) {
            if (getActivity() != null) {
                getActivity().recreate();
            }
        } else if ("auto_delete_days".equals(key)) {
            int days = PreferenceUtil.getAutoDeleteDays(requireContext());
            if (days > 0) {
                long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
                NotificationViewModel viewModel = new ViewModelProvider(requireActivity()).get(NotificationViewModel.class);
                viewModel.deleteOlderThan(cutoff);
            }
        }
    }
}
