package com.zygisk_enc.notivault.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zygisk_enc.notivault.AppRuleEditActivity;
import com.zygisk_enc.notivault.BaseActivity;
import com.zygisk_enc.notivault.MainActivity;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.adapter.AppFilterAdapter;
import com.zygisk_enc.notivault.database.AppSummary;
import com.zygisk_enc.notivault.databinding.FragmentAppsBinding;
import com.zygisk_enc.notivault.util.AutoDeleteDialogHelper;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class AppsFragment extends Fragment {

    private FragmentAppsBinding binding;
    private NotificationViewModel viewModel;
    private AppFilterAdapter adapter;
    private OnBackPressedCallback backPressedCallback;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAppsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(NotificationViewModel.class);

        adapter = new AppFilterAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setHasFixedSize(true);
        binding.recyclerView.setItemViewCacheSize(25);
        binding.recyclerView.setAdapter(adapter);

        // System back navigation callback when selection mode is active
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelectionMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);

        adapter.setOnAppClickListener(summary -> {
            viewModel.setFilterPackage(summary.packageName);
            Navigation.findNavController(view).navigate(R.id.navigation_history);
        });

        adapter.setOnAppLongClickListener(this::showAppActionsDialog);

        // Top Toolbar Delete Button (beside Source button)
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).setOnDeleteAppsClickListener(v -> {
                if (adapter.getItemCount() == 0) return;
                toggleSelectionMode();
            });
        }

        // Selection changed listener
        adapter.setOnSelectionChangedListener((selectedCount, totalCount) -> {
            binding.tvSelectedCount.setText(getString(R.string.selected_count_format, selectedCount));
            binding.btnDeleteSelected.setEnabled(selectedCount > 0);

            binding.cbSelectAll.setOnCheckedChangeListener(null);
            binding.cbSelectAll.setChecked(totalCount > 0 && selectedCount == totalCount);
            setupSelectAllListener();
        });

        setupSelectAllListener();
        setupSortingChips();

        // Cancel selection button
        binding.btnCancelSelection.setOnClickListener(v -> exitSelectionMode());

        // Delete selected button
        binding.btnDeleteSelected.setOnClickListener(v -> {
            Set<String> selected = adapter.getSelectedPackages();
            if (selected.isEmpty()) return;

            Runnable proceedWithDeletion = () -> {
                int count = selected.size();
                String message = count == 1
                        ? getString(R.string.delete_app_logs_message_singular, count)
                        : getString(R.string.delete_app_logs_message_plural, count);

                BaseActivity.showDialog(requireContext(), new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.delete_app_logs_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.delete, (dialog, which) -> {
                            viewModel.deleteByPackages(new ArrayList<>(selected));
                            exitSelectionMode();
                            String toastMsg = count == 1
                                    ? getString(R.string.toast_deleted_apps_logs_singular, count)
                                    : getString(R.string.toast_deleted_apps_logs_plural, count);
                            Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.cancel, null));
            };

            boolean isBiometricEnabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("biometric_lock", false);
            if (isBiometricEnabled) {
                verifyBiometricsToProceed(proceedWithDeletion, getString(R.string.auth_delete_app_logs));
            } else {
                proceedWithDeletion.run();
            }
        });

        binding.etSearchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s != null ? s.toString() : "");
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        viewModel.getAppSummaries().observe(getViewLifecycleOwner(), summaries -> {
            if (summaries == null || summaries.isEmpty()) {
                binding.emptyState.setVisibility(View.VISIBLE);
                binding.recyclerView.setVisibility(View.GONE);
                binding.tvAppsHeaderSummary.setText(R.string.zero_tracked_apps);
                binding.tvTotalNotificationsBadge.setText(R.string.zero_alerts);
                exitSelectionMode();
            } else {
                binding.emptyState.setVisibility(View.GONE);
                binding.recyclerView.setVisibility(View.VISIBLE);
                adapter.submitList(summaries);

                int totalAlerts = 0;
                for (AppSummary s : summaries) {
                    totalAlerts += s.count;
                }
                binding.tvAppsHeaderSummary.setText(getString(R.string.apps_header_summary_format, summaries.size()));
                binding.tvTotalNotificationsBadge.setText(getString(R.string.total_notifications_badge_format, totalAlerts));
            }
        });
    }

    private void setupSortingChips() {
        binding.chipGroupSort.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);

            if (checkedId == R.id.chip_sort_volume) {
                adapter.setSortMode(AppFilterAdapter.SORT_VOLUME_DESC);
            } else if (checkedId == R.id.chip_sort_name) {
                adapter.setSortMode(AppFilterAdapter.SORT_NAME_ASC);
            } else if (checkedId == R.id.chip_sort_least) {
                adapter.setSortMode(AppFilterAdapter.SORT_VOLUME_ASC);
            }
        });
    }

    private void showAppActionsDialog(AppSummary summary) {
        String appName = summary.appName != null ? summary.appName : summary.packageName;
        String[] options = new String[]{
                getString(R.string.action_view_all_notifications),
                getString(R.string.action_configure_rules),
                getString(R.string.action_set_auto_delete),
                getString(R.string.action_clear_app_logs)
        };

        BaseActivity.showDialog(requireContext(), new MaterialAlertDialogBuilder(requireContext())
                .setTitle(appName)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            viewModel.setFilterPackage(summary.packageName);
                            if (getView() != null) {
                                Navigation.findNavController(getView()).navigate(R.id.navigation_history);
                            }
                            break;
                        case 1:
                            Intent ruleIntent = new Intent(requireContext(), AppRuleEditActivity.class);
                            ruleIntent.putExtra(AppRuleEditActivity.EXTRA_PACKAGE_NAME, summary.packageName);
                            ruleIntent.putExtra(AppRuleEditActivity.EXTRA_APP_NAME, appName);
                            startActivity(ruleIntent);
                            break;
                        case 2:
                            showAppAutoDeletePicker(summary);
                            break;
                        case 3:
                            confirmClearAppLogs(summary);
                            break;
                    }
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private void showAppAutoDeletePicker(AppSummary summary) {
        int globalDays = PreferenceUtil.getGlobalAutoDeleteDays(requireContext());
        String globalLabel = globalDays == 0 ? getString(R.string.chip_rule_never_delete) : getString(R.string.chip_rule_x_days, globalDays);

        String[] options = new String[]{
                getString(R.string.retention_use_global_default, globalLabel),
                getString(R.string.retention_after_1_day),
                getString(R.string.retention_after_x_days, 2),
                getString(R.string.retention_after_x_days, 3),
                getString(R.string.retention_after_x_days, 7),
                getString(R.string.retention_after_x_days, 14),
                getString(R.string.retention_after_x_days, 30),
                getString(R.string.retention_never_delete),
                getString(R.string.retention_custom_days)
        };

        Integer currentRule = PreferenceUtil.getAppAutoDeleteRule(requireContext(), summary.packageName);
        int selectedIdx = 0;
        if (currentRule == null) selectedIdx = 0;
        else if (currentRule == 1) selectedIdx = 1;
        else if (currentRule == 2) selectedIdx = 2;
        else if (currentRule == 3) selectedIdx = 3;
        else if (currentRule == 7) selectedIdx = 4;
        else if (currentRule == 14) selectedIdx = 5;
        else if (currentRule == 30) selectedIdx = 6;
        else if (currentRule == -1) selectedIdx = 7;
        else selectedIdx = 8;

        BaseActivity.showDialog(requireContext(), new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.retention_dialog_title_format, (summary.appName != null ? summary.appName : summary.packageName)))
                .setSingleChoiceItems(options, selectedIdx, (d, which) -> {
                    d.dismiss();
                    switch (which) {
                        case 0:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, null);
                            Toast.makeText(requireContext(), R.string.toast_using_global_default, Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, 1);
                            Toast.makeText(requireContext(), R.string.toast_auto_delete_set_1_day, Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, 2);
                            Toast.makeText(requireContext(), getString(R.string.toast_auto_delete_set_x_days, 2), Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, 3);
                            Toast.makeText(requireContext(), getString(R.string.toast_auto_delete_set_x_days, 3), Toast.LENGTH_SHORT).show();
                            break;
                        case 4:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, 7);
                            Toast.makeText(requireContext(), getString(R.string.toast_auto_delete_set_x_days, 7), Toast.LENGTH_SHORT).show();
                            break;
                        case 5:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, 14);
                            Toast.makeText(requireContext(), getString(R.string.toast_auto_delete_set_x_days, 14), Toast.LENGTH_SHORT).show();
                            break;
                        case 6:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, 30);
                            Toast.makeText(requireContext(), getString(R.string.toast_auto_delete_set_x_days, 30), Toast.LENGTH_SHORT).show();
                            break;
                        case 7:
                            PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, -1);
                            Toast.makeText(requireContext(), R.string.toast_never_auto_delete, Toast.LENGTH_SHORT).show();
                            break;
                        case 8:
                            int currentCustom = (currentRule != null && currentRule > 0) ? currentRule : 7;
                            AutoDeleteDialogHelper.showCustomDaysDialog(requireContext(), currentCustom, days -> {
                                int saveVal = days <= 0 ? -1 : days;
                                PreferenceUtil.setAppAutoDeleteRule(requireContext(), summary.packageName, saveVal);
                                if (saveVal == -1) {
                                    Toast.makeText(requireContext(), R.string.toast_never_auto_delete, Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(requireContext(), getString(R.string.toast_auto_delete_set_x_days, days), Toast.LENGTH_SHORT).show();
                                }
                            });
                            break;
                    }
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private void confirmClearAppLogs(AppSummary summary) {
        String appName = summary.appName != null ? summary.appName : summary.packageName;
        Runnable proceed = () -> {
            BaseActivity.showDialog(requireContext(), new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_app_logs_title)
                    .setMessage(getString(R.string.confirm_delete_app_logs_message, summary.count, appName))
                    .setPositiveButton(R.string.delete, (dialog, which) -> {
                        viewModel.deleteByPackages(Collections.singletonList(summary.packageName));
                        Toast.makeText(requireContext(), getString(R.string.toast_cleared_logs_for_app, appName), Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.cancel, null));
        };

        boolean isBiometricEnabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("biometric_lock", false);
        if (isBiometricEnabled) {
            verifyBiometricsToProceed(proceed, getString(R.string.auth_delete_app_logs));
        } else {
            proceed.run();
        }
    }

    private void setupSelectAllListener() {
        binding.cbSelectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                adapter.selectAll();
            } else {
                adapter.deselectAll();
            }
        });
    }

    private void toggleSelectionMode() {
        boolean next = !adapter.isSelectionMode();
        adapter.setSelectionMode(next);
        binding.layoutBatchActions.setVisibility(next ? View.VISIBLE : View.GONE);
        binding.layoutHeaderStats.setVisibility(next ? View.GONE : View.VISIBLE);
        backPressedCallback.setEnabled(next);
        if (next) {
            binding.cbSelectAll.setChecked(false);
            binding.tvSelectedCount.setText(getString(R.string.selected_count_format, 0));
            binding.btnDeleteSelected.setEnabled(false);
        }
    }

    private void exitSelectionMode() {
        adapter.setSelectionMode(false);
        binding.layoutBatchActions.setVisibility(View.GONE);
        binding.layoutHeaderStats.setVisibility(View.VISIBLE);
        backPressedCallback.setEnabled(false);
    }

    private void verifyBiometricsToProceed(Runnable onSuccess, String subtitle) {
        java.util.concurrent.Executor executor = androidx.core.content.ContextCompat.getMainExecutor(requireContext());
        androidx.biometric.BiometricPrompt biometricPrompt = new androidx.biometric.BiometricPrompt(this,
                executor, new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(onSuccess);
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
            }
        });

        androidx.biometric.BiometricPrompt.PromptInfo promptInfo = new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.verify_identity))
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).setOnDeleteAppsClickListener(null);
        }
        binding = null;
    }
}
