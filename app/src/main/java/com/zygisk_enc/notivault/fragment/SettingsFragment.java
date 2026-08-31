package com.zygisk_enc.notivault.fragment;

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
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
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
import com.zygisk_enc.notivault.BaseActivity;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.util.AppLockManager;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import java.util.concurrent.Executor;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final ActivityResultLauncher<String> exportBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"),
            uri -> {
                AppLockManager.setExpectingActivityResult(false);
                if (uri != null) {
                    showExportOptionsDialog(uri);
                }
            }
    );

    private final ActivityResultLauncher<String[]> importBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                AppLockManager.setExpectingActivityResult(false);
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
                .setPositiveButton(R.string.export_button, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String password = tietPassword.getText() != null ? tietPassword.getText().toString().trim() : "";
                if (password.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.toast_password_empty, Toast.LENGTH_SHORT).show();
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
                        R.string.toast_export_started, 
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

        BaseActivity.showDialog(requireContext(), dialog);
    }

    private void showImportPasswordDialog(Uri uri) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_import_backup, null);
        TextInputEditText tietPassword = view.findViewById(R.id.tiet_import_password);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.decrypt_and_import, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String password = tietPassword.getText() != null ? tietPassword.getText().toString().trim() : "";
                if (password.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.toast_password_empty, Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent intent = new Intent(requireContext(), com.zygisk_enc.notivault.service.BackupService.class);
                intent.setAction(com.zygisk_enc.notivault.service.BackupService.ACTION_IMPORT);
                intent.putExtra("uri", uri.toString());
                intent.putExtra("password", password);
                androidx.core.content.ContextCompat.startForegroundService(requireContext(), intent);

                Toast.makeText(requireContext(), 
                        R.string.toast_import_started, 
                        Toast.LENGTH_LONG).show();
                dialog.dismiss();
            });
        });

        BaseActivity.showDialog(requireContext(), dialog);
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        androidx.recyclerview.widget.RecyclerView rv = getListView();
        if (rv != null) {
            rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(), 
                          rv.getPaddingBottom() + (int) (120 * getResources().getDisplayMetrics().density));
            rv.setClipToPadding(false);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        // Unified Auto-Delete History preference
        Preference autoDeleteHistoryPref = findPreference("auto_delete_history");
        if (autoDeleteHistoryPref != null) {
            updateAutoDeleteSummary(autoDeleteHistoryPref);
            autoDeleteHistoryPref.setOnPreferenceClickListener(pref -> {
                boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getBoolean("biometric_lock", false);
                if (isBiometricEnabled) {
                    verifyBiometricsToProceed(() -> {
                        startActivity(new Intent(requireContext(), com.zygisk_enc.notivault.AutoDeleteRulesActivity.class));
                    }, getString(R.string.auth_manage_auto_delete));
                } else {
                    startActivity(new Intent(requireContext(), com.zygisk_enc.notivault.AutoDeleteRulesActivity.class));
                }
                return true;
            });
        }

        // App language preference
        ListPreference languagePref = findPreference("app_language");
        if (languagePref != null) {
            LocaleListCompat currentLocales = AppCompatDelegate.getApplicationLocales();
            if (currentLocales.isEmpty()) {
                languagePref.setValue("system");
            } else {
                String tag = currentLocales.get(0).toLanguageTag();
                if (tag.startsWith("de")) languagePref.setValue("de");
                else if (tag.startsWith("es")) languagePref.setValue("es");
                else if (tag.startsWith("fr")) languagePref.setValue("fr");
                else if (tag.startsWith("ru")) languagePref.setValue("ru");
                else if (tag.startsWith("pt")) languagePref.setValue("pt-BR");
                else if (tag.startsWith("zh")) languagePref.setValue("zh-CN");
                else if (tag.startsWith("it")) languagePref.setValue("it");
                else if (tag.startsWith("ja")) languagePref.setValue("ja");
                else if (tag.startsWith("ko")) languagePref.setValue("ko");
                else if (tag.startsWith("tr")) languagePref.setValue("tr");
                else if (tag.startsWith("ar")) languagePref.setValue("ar");
                else if (tag.startsWith("pl")) languagePref.setValue("pl");
                else if (tag.startsWith("in") || tag.startsWith("id")) languagePref.setValue("in");
                else if (tag.startsWith("en")) languagePref.setValue("en");
                else languagePref.setValue("system");
            }

            languagePref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            languagePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String langTag = (String) newValue;
                if ("system".equals(langTag)) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
                } else {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langTag));
                }
                return true;
            });
        }

        // Theme color preference summary
        ListPreference themeColorPref = findPreference("theme_color");
        if (themeColorPref != null) {
            themeColorPref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        // Biometric Switch lock verification before turning off
        Preference biometricPref = findPreference("biometric_lock");
        Preference widgetFilterAuthPref = findPreference("widget_filter_auth");

        if (widgetFilterAuthPref != null) {
            boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("biometric_lock", false);
            widgetFilterAuthPref.setEnabled(isBiometricEnabled);
        }

        if (biometricPref != null) {
            biometricPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean isEnabling = (boolean) newValue;
                if (widgetFilterAuthPref != null) {
                    widgetFilterAuthPref.setEnabled(isEnabling);
                }
                if (!isEnabling) {
                    // Require identity verification to disable security lock
                    verifyBiometricsToProceed(() -> {
                        if (preference instanceof SwitchPreferenceCompat) {
                            ((SwitchPreferenceCompat) preference).setChecked(false);
                        }
                        if (widgetFilterAuthPref != null) {
                            widgetFilterAuthPref.setEnabled(false);
                        }
                    }, getString(R.string.auth_disable_lock));
                    return false; // Intercept: don't toggle yet
                }
                return true; // Let enabling proceed directly
            });
        }

        if (widgetFilterAuthPref != null) {
            widgetFilterAuthPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean isEnabling = (boolean) newValue;
                if (!isEnabling) {
                    verifyBiometricsToProceed(() -> {
                        if (preference instanceof SwitchPreferenceCompat) {
                            ((SwitchPreferenceCompat) preference).setChecked(false);
                        }
                    }, getString(R.string.auth_confirm_unlock));
                    return false;
                }
                return true;
            });
        }

        // FLAG_SECURE switch live listener with biometric authentication
        Preference flagSecurePref = findPreference("flag_secure");
        if (flagSecurePref != null) {
            flagSecurePref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (boolean) newValue;
                boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getBoolean("biometric_lock", false);

                Runnable applyChange = () -> {
                    if (preference instanceof SwitchPreferenceCompat) {
                        ((SwitchPreferenceCompat) preference).setChecked(enabled);
                    }
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putBoolean("flag_secure", enabled)
                            .apply();
                    if (getActivity() != null) {
                        if (enabled) {
                            getActivity().getWindow().setFlags(
                                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                            );
                        } else {
                            getActivity().getWindow().clearFlags(
                                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                            );
                        }
                    }
                };

                if (isBiometricEnabled) {
                    verifyBiometricsToProceed(applyChange, getString(R.string.auth_toggle_flag_secure));
                    return false;
                } else {
                    if (getActivity() != null) {
                        if (enabled) {
                            getActivity().getWindow().setFlags(
                                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                            );
                        } else {
                            getActivity().getWindow().clearFlags(
                                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                            );
                        }
                    }
                    return true;
                }
            });
        }

        // Extended Metadata Capture switch with biometric authentication
        Preference metadataCapturePref = findPreference("capture_extended_metadata");
        if (metadataCapturePref != null) {
            metadataCapturePref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (boolean) newValue;
                boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getBoolean("biometric_lock", false);

                Runnable applyChange = () -> {
                    if (preference instanceof SwitchPreferenceCompat) {
                        ((SwitchPreferenceCompat) preference).setChecked(enabled);
                    }
                    PreferenceUtil.setExtendedMetadataEnabled(requireContext(), enabled);
                };

                if (isBiometricEnabled) {
                    verifyBiometricsToProceed(applyChange, getString(R.string.auth_toggle_metadata_capture));
                    return false;
                } else {
                    return true;
                }
            });
        }

        // Export backup click
        Preference exportPref = findPreference("export_backup");
        if (exportPref != null) {
            exportPref.setOnPreferenceClickListener(pref -> {
                boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getBoolean("biometric_lock", false);
                Runnable proceed = () -> {
                    AppLockManager.setExpectingActivityResult(true);
                    exportBackupLauncher.launch("notivault_backup_" + System.currentTimeMillis() + ".vault");
                };
                if (isBiometricEnabled) {
                    verifyBiometricsToProceed(proceed, getString(R.string.auth_export_backup));
                } else {
                    proceed.run();
                }
                return true;
            });
        }

        // Import backup click
        Preference importPref = findPreference("import_backup");
        if (importPref != null) {
            importPref.setOnPreferenceClickListener(pref -> {
                boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getBoolean("biometric_lock", false);
                Runnable proceed = () -> {
                    AppLockManager.setExpectingActivityResult(true);
                    importBackupLauncher.launch(new String[]{"application/json", "application/octet-stream", "*/*"});
                };
                if (isBiometricEnabled) {
                    verifyBiometricsToProceed(proceed, getString(R.string.auth_import_backup));
                } else {
                    proceed.run();
                }
                return true;
            });
        }

        // Bundle missed logs click
        Preference bundleMissedLogsPref = findPreference("bundle_missed_logs");
        if (bundleMissedLogsPref != null) {
            bundleMissedLogsPref.setOnPreferenceClickListener(pref -> {
                Toast.makeText(requireContext(), R.string.toast_bundling_started, Toast.LENGTH_SHORT).show();
                com.zygisk_enc.notivault.util.BundleManager.triggerManualBundling(requireContext(), new com.zygisk_enc.notivault.util.BundleManager.BundlingCallback() {
                    @Override public void onProgress(int progress) {}
                    @Override
                    public void onComplete() {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(requireContext(), R.string.toast_bundling_complete, Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                    @Override public void onError(Exception e) {}
                });
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

        // Home screen shortcuts click listener
        Preference pinShortcutsPref = findPreference("pin_shortcuts");
        if (pinShortcutsPref != null) {
            pinShortcutsPref.setOnPreferenceClickListener(pref -> {
                com.zygisk_enc.notivault.util.ShortcutHelper.showPinShortcutsDialog(requireContext());
                return true;
            });
        }

        // App Rules click listener
        Preference rulesPref = findPreference("app_rules");
        if (rulesPref != null) {
            rulesPref.setOnPreferenceClickListener(pref -> {
                Intent intent = new Intent(requireContext(), com.zygisk_enc.notivault.AppRulesActivity.class);
                startActivity(intent);
                return true;
            });
        }

        // About preference click listener to open About Notification Vault dialog
        Preference aboutPref = findPreference("about");
        if (aboutPref != null) {
            aboutPref.setOnPreferenceClickListener(pref -> {
                showAboutDialog();
                return true;
            });
        }
    }

    private void showAboutDialog() {
        if (getContext() == null) return;
        String versionName = "3.0.0";
        try {
            versionName = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.mipmap.ic_launcher)
                .setTitle(R.string.pref_about_title)
                .setMessage("Notification Vault v" + versionName + "\n\nEncrypted, private local notification log manager.\n\n• Zero Tracking & No Ads\n• Local AES-256 KeyStore Encryption\n• Work & Personal Profile Isolation\n• Instant Fast Search & Smart Bundling\n\nOpen-source project under MIT License.")
                .setPositiveButton("GitHub", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/snap24/notification-vault"));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), R.string.toast_failed_to_open_link, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.close, null);
        BaseActivity.showDialog(requireContext(), builder);
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
                .setTitle(getString(R.string.verify_identity))
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        super.onDisplayPreferenceDialog(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(this);
        Preference autoDeleteHistoryPref = findPreference("auto_delete_history");
        if (autoDeleteHistoryPref != null) {
            updateAutoDeleteSummary(autoDeleteHistoryPref);
        }
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
        } else if ("auto_delete_days".equals(key) || "auto_delete_app_rules".equals(key)) {
            Preference autoDeleteHistoryPref = findPreference("auto_delete_history");
            if (autoDeleteHistoryPref != null) {
                updateAutoDeleteSummary(autoDeleteHistoryPref);
            }
        } else if ("capture_enabled".equals(key)) {
            // Notify the QS tile to refresh its state from the updated preference
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.service.quicksettings.TileService.requestListeningState(
                    requireContext(),
                    new android.content.ComponentName(requireContext(), com.zygisk_enc.notivault.service.NotiVaultTileService.class)
                );
            }
        } else if ("qs_tile_enabled".equals(key)) {
            boolean tileEnabled = sharedPreferences.getBoolean("qs_tile_enabled", false);
            PreferenceUtil.setTileServiceEnabled(requireContext(), tileEnabled);
        }
    }

    private void updateAutoDeleteSummary(Preference pref) {
        if (pref == null || getContext() == null) return;
        int globalDays = PreferenceUtil.getGlobalAutoDeleteDays(requireContext());
        java.util.Map<String, Integer> appRules = PreferenceUtil.getAppAutoDeleteRules(requireContext());
        int customCount = appRules.size();

        String globalLabel = globalDays == 0 ? getString(R.string.never)
                : globalDays == 1 ? getString(R.string.after_1_day)
                : getString(R.string.after_x_days, globalDays);

        if (customCount > 0) {
            pref.setSummary(getString(R.string.auto_delete_summary_format, globalLabel, customCount));
        } else {
            pref.setSummary(getString(R.string.auto_delete_summary_simple, globalLabel));
        }
    }
}
