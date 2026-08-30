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

public class ToastHistoryActivity extends BaseActivity {

    private ActivityToastHistoryBinding binding;
    private ToastViewModel viewModel;
    private ToastAdapter adapter;
    private Long oldestToastTimestamp = null;
    private String formattedDateFilter = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        setupAppPicker();
        setupDatePicker();
        setupClearAll();

        binding.btnGrantAccessibility.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, R.string.toast_open_accessibility_failed, Toast.LENGTH_SHORT).show();
            }
        });

        viewModel.getToasts().observe(this, toasts -> updateUI());
        viewModel.getIsLoading().observe(this, loading -> updateUI());

        viewModel.getLoadProgress().observe(this, progress -> {
            if (progress == null || progress < 0 || progress >= 100) {
                binding.cardToolbarDecryption.setVisibility(View.GONE);
            } else {
                binding.cardToolbarDecryption.setVisibility(View.VISIBLE);
                binding.tvToolbarDecryption.setText(getString(R.string.decrypting_progress, progress));
            }
        });

        viewModel.getScrollToTopEvent().observe(this, scroll -> {
            if (scroll != null && scroll) {
                animateScrollToTop();
            }
        });

        // Observe oldest toast timestamp for calendar picker constraints
        viewModel.getOldestTimestamp().observe(this, timestamp -> {
            oldestToastTimestamp = timestamp;
        });
    }

    private void animateScrollToTop() {
        LinearLayoutManager lm = (LinearLayoutManager) binding.recyclerView.getLayoutManager();
        if (lm != null) {
            lm.scrollToPositionWithOffset(0, 0);
        } else {
            binding.recyclerView.scrollToPosition(0);
        }

        float slideDistance = 24 * getResources().getDisplayMetrics().density;
        binding.recyclerView.setTranslationY(slideDistance);
        binding.recyclerView.setAlpha(0.3f);
        binding.recyclerView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .start();
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
                    animateScrollToTop();
                }
            });
        }
    }

    private void setupRecyclerView() {
        adapter = new ToastAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.recyclerView.setLayoutManager(layoutManager);
        binding.recyclerView.setAdapter(adapter);

        binding.recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                int visibleCount = layoutManager.getChildCount();
                int totalCount = layoutManager.getItemCount();
                int firstVisiblePos = layoutManager.findFirstVisibleItemPosition();

                if ((visibleCount + firstVisiblePos) >= totalCount - 100 && firstVisiblePos >= 0) {
                    Integer currentLimit = viewModel.getFilterLimit().getValue();
                    java.util.List<com.zygisk_enc.notivault.database.ToastEntity> currentList = viewModel.getToasts().getValue();
                    int currentRawCount = currentList != null ? currentList.size() : totalCount;
                    if (currentLimit != null && currentRawCount >= currentLimit) {
                        viewModel.loadNextPage();
                    }
                }
            }
        });
    }

    private void setupAppPicker() {
        binding.btnFilterApp.setOnClickListener(v -> showAppFilterBottomSheet());
        binding.btnFilterApp.setOnLongClickListener(v -> {
            viewModel.setFilterPackage(null);
            updateFilterDisplay();
            Snackbar.make(binding.getRoot(), R.string.app_filter_cleared, Snackbar.LENGTH_SHORT).show();
            return true;
        });

        binding.chipActiveAppFilter.setOnCloseIconClickListener(v -> {
            viewModel.setFilterPackage(null);
            updateFilterDisplay();
            Snackbar.make(binding.getRoot(), R.string.app_filter_cleared, Snackbar.LENGTH_SHORT).show();
        });

        binding.chipActiveDateFilter.setOnCloseIconClickListener(v -> {
            viewModel.setDateFilter(null, null);
            formattedDateFilter = null;
            updateFilterDisplay();
            Snackbar.make(binding.getRoot(), R.string.date_filter_cleared, Snackbar.LENGTH_SHORT).show();
        });
    }

    private void showAppFilterBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_toast_app_filter, null);
        dialog.setContentView(sheetView);

        androidx.recyclerview.widget.RecyclerView rvApps = sheetView.findViewById(R.id.rv_apps);
        android.widget.TextView tvNoApps = sheetView.findViewById(R.id.tv_no_apps);
        android.widget.EditText etSearch = sheetView.findViewById(R.id.et_search_apps);
        View btnShowAll = sheetView.findViewById(R.id.btn_show_all_apps);

        com.zygisk_enc.notivault.adapter.AppFilterAdapter appAdapter =
                new com.zygisk_enc.notivault.adapter.AppFilterAdapter();
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setAdapter(appAdapter);

        appAdapter.setOnAppClickListener(summary -> {
            viewModel.setFilterPackage(summary.packageName);
            updateFilterDisplay();
            dialog.dismiss();
        });

        btnShowAll.setOnClickListener(v -> {
            viewModel.setFilterPackage(null);
            updateFilterDisplay();
            dialog.dismiss();
        });

        viewModel.getAppSummaries().observe(this, summaries -> {
            if (summaries == null || summaries.isEmpty()) {
                rvApps.setVisibility(View.GONE);
                tvNoApps.setVisibility(View.VISIBLE);
            } else {
                rvApps.setVisibility(View.VISIBLE);
                tvNoApps.setVisibility(View.GONE);
                appAdapter.submitList(summaries);
            }
        });

        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    appAdapter.filter(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

        dialog.setOnShowListener(dialogInterface -> {
            com.google.android.material.bottomsheet.BottomSheetDialog d =
                    (com.google.android.material.bottomsheet.BottomSheetDialog) dialogInterface;
            android.widget.FrameLayout bottomSheet =
                    d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<android.widget.FrameLayout> behavior =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                int targetHeight = (int) (screenHeight * 0.90);
                behavior.setSkipCollapsed(true);
                behavior.setPeekHeight(targetHeight);
                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                bottomSheet.getLayoutParams().height = targetHeight;
            }
        });

        dialog.show();
    }

    private void updateFilterDisplay() {
        String pkg = viewModel.getFilterPackage().getValue();
        boolean hasAppFilter = (pkg != null && !pkg.isEmpty());
        boolean hasDateFilter = (formattedDateFilter != null && !formattedDateFilter.isEmpty());

        boolean hasAnyFilter = hasAppFilter || hasDateFilter;

        if (!hasAnyFilter) {
            binding.tvActiveFilters.setText(R.string.showing_all_toasts);
            binding.scrollActiveFilters.setVisibility(View.GONE);
            binding.btnReloadAll.setVisibility(View.GONE);
        } else {
            binding.scrollActiveFilters.setVisibility(View.VISIBLE);
            binding.btnReloadAll.setVisibility(View.VISIBLE);

            String appLabel = pkg;
            if (hasAppFilter) {
                try {
                    android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                    CharSequence name = getPackageManager().getApplicationLabel(ai);
                    if (name != null && name.length() > 0) {
                        appLabel = name.toString();
                    }
                } catch (Exception ignored) {}

                binding.chipActiveAppFilter.setVisibility(View.VISIBLE);
                binding.chipActiveAppFilter.setText(getString(R.string.app_filter_format, appLabel));
            } else {
                binding.chipActiveAppFilter.setVisibility(View.GONE);
            }

            if (hasDateFilter) {
                binding.chipActiveDateFilter.setVisibility(View.VISIBLE);
                binding.chipActiveDateFilter.setText(getString(R.string.date_filter_format, formattedDateFilter));
            } else {
                binding.chipActiveDateFilter.setVisibility(View.GONE);
            }

            if (hasAppFilter && hasDateFilter) {
                binding.tvActiveFilters.setText(getString(R.string.app_and_date_filter_format, appLabel, formattedDateFilter));
            } else if (hasAppFilter) {
                binding.tvActiveFilters.setText(getString(R.string.app_filter_format, appLabel));
            } else {
                binding.tvActiveFilters.setText(getString(R.string.date_filter_format, formattedDateFilter));
            }
        }
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
                formattedDateFilter = (utc.get(Calendar.MONTH) + 1) + "/" + utc.get(Calendar.DAY_OF_MONTH) + "/" + utc.get(Calendar.YEAR);
                updateFilterDisplay();
            });
            picker.show(getSupportFragmentManager(), "DATE_PICKER");
        });

        // Setup reload button click to clear all filters
        binding.btnReloadAll.setOnClickListener(v -> {
            viewModel.resetAllFilters();
            formattedDateFilter = null;
            updateFilterDisplay();
            Snackbar.make(binding.getRoot(), R.string.filters_reset, Snackbar.LENGTH_SHORT).show();
        });

        binding.btnOpenCalendar.setOnLongClickListener(v -> {
            viewModel.setDateFilter(null, null);
            formattedDateFilter = null;
            updateFilterDisplay();
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
                .setTitle(getString(R.string.biometric_identity_verification))
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

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null && adapter.getItemCount() == 0) {
            if (isAccessibilityServiceEnabled()) {
                binding.tvAccessibilityHint.setText(R.string.toasts_active_desc);
                binding.btnGrantAccessibility.setVisibility(View.GONE);
            } else {
                binding.tvAccessibilityHint.setText(R.string.empty_toasts_desc);
                binding.btnGrantAccessibility.setVisibility(View.VISIBLE);
            }
        }
    }
}
