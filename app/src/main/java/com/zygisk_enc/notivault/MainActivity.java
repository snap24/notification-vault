package com.zygisk_enc.notivault;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zygisk_enc.notivault.databinding.ActivityMainBinding;
import com.zygisk_enc.notivault.service.NotiVaultService;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;

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
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        AppBarConfiguration appBarConfig = new AppBarConfiguration.Builder(
                R.id.navigation_history,
                R.id.navigation_apps,
                R.id.navigation_stats,
                R.id.navigation_settings
        ).build();

        if (navController != null) {
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfig);
            NavigationUI.setupWithNavController(binding.bottomNavigation, navController);
            binding.bottomNavigation.setOnItemSelectedListener(item -> {
                if (item.getItemId() == R.id.navigation_history) {
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                            new androidx.lifecycle.ViewModelProvider(this)
                            .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                    vm.setFilterPackage(null);
                    vm.setFilterFavorites(false);
                    vm.setDateFilter(null, null);
                    vm.requestScrollToTop();
                }
                return NavigationUI.onNavDestinationSelected(item, navController);
            });

            binding.bottomNavigation.setOnItemReselectedListener(item -> {
                if (item.getItemId() == R.id.navigation_history) {
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                            new androidx.lifecycle.ViewModelProvider(this)
                            .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                    vm.setFilterPackage(null);
                    vm.setFilterFavorites(false);
                    vm.setDateFilter(null, null);
                    vm.requestScrollToTop();
                }
            });
        }

        // Hide bottom navigation when keyboard is open to prevent UI layout constraints overlapping search
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            boolean keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            binding.bottomNavigation.setVisibility(keyboardVisible ? android.view.View.GONE : android.view.View.VISIBLE);
            return insets;
        });

        // Bind Custom View Source Button Click
        binding.btnViewSource.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/snap24/com.zygisk_enc.fingerprintreset"));
            startActivity(intent);
        });

        // Check notification listener permission
        if (!isNotificationServiceEnabled()) {
            showPermissionDialog();
        }
    }

    private boolean isAuthenticated = false;

    private void checkBiometricLock() {
        boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("biometric_lock", false);
        
        if (isBiometricEnabled && !isAuthenticated) {
            binding.layoutLockOverlay.setVisibility(android.view.View.VISIBLE);
            binding.btnUnlock.setOnClickListener(v -> showBiometricPrompt());
            showBiometricPrompt();
        } else {
            binding.layoutLockOverlay.setVisibility(android.view.View.GONE);
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
                isAuthenticated = true;
                binding.layoutLockOverlay.setVisibility(android.view.View.GONE);
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
    }

    @Override
    protected void onStop() {
        super.onStop();
        isAuthenticated = false;
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && pkgName.equals(cn.getPackageName())) return true;
            }
        }
        return false;
    }

    private void showPermissionDialog() {
        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_permission_instruction, null);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_required_title)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(R.string.grant_access, (d, which) ->
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                .setNegativeButton(R.string.not_now, null)
                .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            positiveButton.setEnabled(false);
            new android.os.CountDownTimer(8000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    positiveButton.setText(getString(R.string.grant_access) + " (" + ((millisUntilFinished / 1000) + 1) + "s)");
                }

                @Override
                public void onFinish() {
                    positiveButton.setEnabled(true);
                    positiveButton.setText(R.string.grant_access);
                }
            }.start();
        });
        
        dialog.show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController != null && navController.navigateUp() || super.onSupportNavigateUp();
    }
}
