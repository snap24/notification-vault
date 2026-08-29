package com.zygisk_enc.notivault;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.CompositeDateValidator;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.zygisk_enc.notivault.adapter.ToastAdapter;
import com.zygisk_enc.notivault.databinding.ActivityToastHistoryBinding;
import com.zygisk_enc.notivault.viewmodel.ToastViewModel;
import java.util.Calendar;
import java.util.concurrent.Executor;

public class ToastHistoryActivity extends AppCompatActivity {

    private ActivityToastHistoryBinding binding;
    private ToastViewModel viewModel;
    private ToastAdapter adapter;
    private Long oldestToastTimestamp = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply theme color
        String themePref = PreferenceManager.getDefaultSharedPreferences(this).getString("theme_color", "grey");
        boolean isPitchBlack = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pitch_black", false);
        boolean isNightMode = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) 
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        
        if (isPitchBlack && isNightMode) {
            setTheme(R.style.Theme_NotiVault_Black);
        } else {
            if ("blue".equals(themePref)) {
                setTheme(R.style.Theme_NotiVault_Blue);
            } else if ("green".equals(themePref)) {
                setTheme(R.style.Theme_NotiVault_Green);
            } else if ("orange".equals(themePref)) {
                setTheme(R.style.Theme_NotiVault_Orange);
            } else if ("purple".equals(themePref)) {
                setTheme(R.style.Theme_NotiVault_Purple);
            } else {
                setTheme(R.style.Theme_NotiVault_Grey);
            }
        }

        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            com.zygisk_enc.notivault.util.AppLockManager.setUnlocked(
                    savedInstanceState.getBoolean("is_authenticated", com.zygisk_enc.notivault.util.AppLockManager.isUnlocked())
            );
        }
        binding = ActivityToastHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> onBackPressed());

        viewModel = new ViewModelProvider(this).get(ToastViewModel.class);

        // Bind Source Button
        binding.btnViewSource.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/snap24/notification-vault"));
            startActivity(intent);
        });

        setupRecyclerView();
        setupDatePicker();
        setupClearAll();

        binding.btnGrantAccessibility.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show();
            }
        });

        viewModel.getToasts().observe(this, toasts -> updateUI());
        viewModel.getIsLoading().observe(this, loading -> updateUI());

        viewModel.getLoadProgress().observe(this, progress -> {
            if (progress == null || progress < 0) {
                binding.tvLoadProgress.setVisibility(View.GONE);
            } else {
                binding.tvLoadProgress.setVisibility(View.VISIBLE);
                binding.tvLoadProgress.setText("Decrypting... " + progress + "%");
            }
        });

        viewModel.getScrollToTopEvent().observe(this, scroll -> {
            if (scroll != null && scroll) {
                LinearLayoutManager lm = (LinearLayoutManager) binding.recyclerView.getLayoutManager();
                if (lm != null) {
                    lm.scrollToPositionWithOffset(0, 0);
                } else {
                    binding.recyclerView.scrollToPosition(0);
                }
            }
        });

        // Observe oldest toast timestamp for calendar picker constraints
        viewModel.getOldestTimestamp().observe(this, timestamp -> {
            oldestToastTimestamp = timestamp;
        });
    }

    private void updateUI() {
        java.util.List<com.zygisk_enc.notivault.database.ToastEntity> toasts = viewModel.getToasts().getValue();
        Boolean isLoading = viewModel.getIsLoading().getValue();
        if (isLoading == null) isLoading = false;

        boolean isAccessibilityEnabled = isAccessibilityServiceEnabled();

        if (toasts == null || toasts.isEmpty()) {
            binding.recyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.VISIBLE);
            
            if (!isAccessibilityEnabled) {
                binding.tvEmptyTitle.setText(R.string.empty_toasts_title);
                binding.tvAccessibilityHint.setText(R.string.empty_toasts_desc);
                binding.btnGrantAccessibility.setVisibility(View.VISIBLE);
            } else if (isLoading) {
                binding.tvEmptyTitle.setText(R.string.loading_toasts);
                binding.tvAccessibilityHint.setText(R.string.decrypting_secure_log);
                binding.btnGrantAccessibility.setVisibility(View.GONE);
            } else {
                binding.tvEmptyTitle.setText(R.string.empty_toasts_title);
                binding.tvAccessibilityHint.setText(R.string.toasts_active_desc);
                binding.btnGrantAccessibility.setVisibility(View.GONE);
            }
        } else {
            binding.recyclerView.setVisibility(View.VISIBLE);
            binding.emptyState.setVisibility(View.GONE);
            adapter.submitList(toasts, () -> {
                Boolean scroll = viewModel.getScrollToTopEvent().getValue();
                if (scroll != null && scroll) {
                    viewModel.clearScrollToTopEvent();
                    LinearLayoutManager lm = (LinearLayoutManager) binding.recyclerView.getLayoutManager();
                    if (lm != null) {
                        lm.scrollToPositionWithOffset(0, 0);
                    } else {
                        binding.recyclerView.scrollToPosition(0);
                    }
                }
            });
        }
    }

    private void setupRecyclerView() {
        adapter = new ToastAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
    }

    private void setupDatePicker() {
        binding.btnOpenCalendar.setOnClickListener(v -> {
            MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
            builder.setTitleText(R.string.desc_select_date);

            CalendarConstraints.Builder constraintsBuilder = new CalendarConstraints.Builder();
            long todayUtc = MaterialDatePicker.todayInUtcMilliseconds();
            constraintsBuilder.setEnd(todayUtc);
            
            long startUtc = todayUtc;
            if (oldestToastTimestamp != null && oldestToastTimestamp > 0) {
                Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                Calendar localOld = Calendar.getInstance();
                localOld.setTimeInMillis(oldestToastTimestamp);
                c.set(localOld.get(Calendar.YEAR), localOld.get(Calendar.MONTH), localOld.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
                startUtc = c.getTimeInMillis();
            }
            // Ensure start isn't after end
            if (startUtc > todayUtc) startUtc = todayUtc;
            
            constraintsBuilder.setStart(startUtc);
            
            java.util.List<CalendarConstraints.DateValidator> validators = new java.util.ArrayList<>();
            validators.add(DateValidatorPointBackward.before(todayUtc + 1));
            validators.add(DateValidatorPointForward.from(startUtc - 1));
            constraintsBuilder.setValidator(CompositeDateValidator.allOf(validators));
            
            constraintsBuilder.setOpenAt(todayUtc);
            builder.setCalendarConstraints(constraintsBuilder.build());

            MaterialDatePicker<Long> picker = builder.build();
            picker.addOnPositiveButtonClickListener(selection -> {
                Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                utc.setTimeInMillis(selection);
                
                Calendar startCal = Calendar.getInstance();
                startCal.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
                startCal.set(Calendar.MILLISECOND, 0);

                Calendar endCal = Calendar.getInstance();
                endCal.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 23, 59, 59);
                endCal.set(Calendar.MILLISECOND, 999);

                viewModel.setDateFilter(startCal.getTimeInMillis(), endCal.getTimeInMillis());
                String dateStr = (utc.get(Calendar.MONTH) + 1) + "/" + utc.get(Calendar.DAY_OF_MONTH) + "/" + utc.get(Calendar.YEAR);
                binding.tvActiveFilters.setText(getString(R.string.date_filter_format, dateStr));
                binding.btnReloadAll.setVisibility(View.VISIBLE);
            });
            picker.show(getSupportFragmentManager(), "DATE_PICKER");
        });

        // Setup reload button click to clear filter
        binding.btnReloadAll.setOnClickListener(v -> {
            viewModel.setDateFilter(null, null);
            binding.tvActiveFilters.setText(R.string.showing_all_toasts);
            binding.btnReloadAll.setVisibility(View.GONE);
            Snackbar.make(binding.getRoot(), R.string.filters_reset, Snackbar.LENGTH_SHORT).show();
        });

        binding.btnOpenCalendar.setOnLongClickListener(v -> {
            viewModel.setDateFilter(null, null);
            binding.tvActiveFilters.setText(R.string.showing_all_toasts);
            binding.btnReloadAll.setVisibility(View.GONE);
            Snackbar.make(binding.getRoot(), R.string.date_filter_cleared, Snackbar.LENGTH_SHORT).show();
            return true;
        });
    }

    private void setupClearAll() {
        binding.btnClearAll.setOnClickListener(v -> {
            Runnable proceedToClear = () -> {
                new MaterialAlertDialogBuilder(ToastHistoryActivity.this)
                        .setTitle(R.string.clear_toast_history_title)
                        .setMessage(R.string.clear_toast_history_desc)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.clear, (dialog, which) -> {
                            viewModel.clearAllToasts();
                            Toast.makeText(ToastHistoryActivity.this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show();
                        })
                        .show();
            };

            boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("biometric_lock", false);
            if (isBiometricEnabled) {
                verifyBiometricsToProceed(proceedToClear, getString(R.string.auth_clear_toasts));
            } else {
                proceedToClear.run();
            }
        });
    }

    private void verifyBiometricsToProceed(Runnable onSuccess, String subtitle) {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                runOnUiThread(onSuccess);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Notification Vault Identity Verification")
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private boolean isAccessibilityServiceEnabled() {
        String expectedPackage = getPackageName();
        String expectedClass = com.zygisk_enc.notivault.service.ToastRecorderService.class.getName();
        
        String enabledServicesSetting = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null) return false;
        
        android.text.TextUtils.SimpleStringSplitter colonSplitter = new android.text.TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);
        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(componentNameString);
            if (cn != null && cn.getPackageName().equals(expectedPackage) 
                    && cn.getClassName().equals(expectedClass)) {
                return true;
            }
        }
        return false;
    }

    private void checkBiometricLock() {
        boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("biometric_lock", false);

        if (isBiometricEnabled && !com.zygisk_enc.notivault.util.AppLockManager.isUnlocked()) {
            binding.layoutLockOverlay.setVisibility(View.VISIBLE);
            binding.btnUnlock.setOnClickListener(v -> showBiometricPrompt());
            showBiometricPrompt();
        } else {
            binding.layoutLockOverlay.setVisibility(View.GONE);
        }
    }

    private void showBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                com.zygisk_enc.notivault.util.AppLockManager.setUnlocked(true);
                binding.layoutLockOverlay.setVisibility(View.GONE);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Notification Vault Lock")
                .setSubtitle("Confirm biometric authentication to unlock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBiometricLock();
        if (adapter != null && adapter.getItemCount() == 0) {
            if (isAccessibilityServiceEnabled()) {
                binding.tvAccessibilityHint.setText("Toasts will appear here as they are shown on screen.");
                binding.btnGrantAccessibility.setVisibility(View.GONE);
            } else {
                binding.tvAccessibilityHint.setText("Turn on Accessibility access to log background toasts.");
                binding.btnGrantAccessibility.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("is_authenticated", com.zygisk_enc.notivault.util.AppLockManager.isUnlocked());
    }
}
