package com.zygisk_enc.notivault.fragment;

import android.os.Bundle;
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
import com.zygisk_enc.notivault.MainActivity;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.adapter.AppFilterAdapter;
import com.zygisk_enc.notivault.databinding.FragmentAppsBinding;
import com.zygisk_enc.notivault.viewmodel.NotificationViewModel;
import java.util.ArrayList;
import java.util.Set;

public class AppsFragment extends Fragment {

    private FragmentAppsBinding binding;
    private NotificationViewModel viewModel;
    private AppFilterAdapter adapter;
    private OnBackPressedCallback backPressedCallback;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new com.google.android.material.transition.MaterialFadeThrough());
        setExitTransition(new com.google.android.material.transition.MaterialFadeThrough());
        setReenterTransition(new com.google.android.material.transition.MaterialFadeThrough());
        setReturnTransition(new com.google.android.material.transition.MaterialFadeThrough());
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

                new MaterialAlertDialogBuilder(requireContext())
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
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            };

            boolean isBiometricEnabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("biometric_lock", false);
            if (isBiometricEnabled) {
                verifyBiometricsToProceed(proceedWithDeletion, getString(R.string.auth_delete_app_logs));
            } else {
                proceedWithDeletion.run();
            }
        });

        binding.etSearchApps.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s != null ? s.toString() : "");
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        viewModel.getAppSummaries().observe(getViewLifecycleOwner(), summaries -> {
            if (summaries == null || summaries.isEmpty()) {
                binding.emptyState.setVisibility(View.VISIBLE);
                binding.recyclerView.setVisibility(View.GONE);
                exitSelectionMode();
            } else {
                binding.emptyState.setVisibility(View.GONE);
                binding.recyclerView.setVisibility(View.VISIBLE);
                adapter.submitList(summaries);
            }
        });
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
