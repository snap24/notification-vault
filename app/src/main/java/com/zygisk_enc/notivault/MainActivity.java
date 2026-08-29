package com.zygisk_enc.notivault;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;
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
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.database.AppDatabase;

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
        if (savedInstanceState != null) {
            com.zygisk_enc.notivault.util.AppLockManager.setUnlocked(savedInstanceState.getBoolean("is_authenticated", com.zygisk_enc.notivault.util.AppLockManager.isUnlocked()));
        }
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
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation, (v, insets) -> insets);
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

            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                if (destination.getId() == R.id.navigation_apps) {
                    binding.btnDeleteApps.setVisibility(android.view.View.VISIBLE);
                } else {
                    binding.btnDeleteApps.setVisibility(android.view.View.GONE);
                }
            });
        }

        // Make the bottom navigation card translucent glass-like
        int surfaceColor = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE);
        int glassBgColor = androidx.core.graphics.ColorUtils.setAlphaComponent(surfaceColor, 216); // 85% opacity
        binding.bottomNavigationCard.setCardBackgroundColor(glassBgColor);

        int outlineColor = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, android.graphics.Color.GRAY);
        int glassBorderColor = androidx.core.graphics.ColorUtils.setAlphaComponent(outlineColor, 64); // 25% opacity
        binding.bottomNavigationCard.setStrokeColor(android.content.res.ColorStateList.valueOf(glassBorderColor));

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }

        // Hide bottom navigation card when keyboard is open to prevent UI layout constraints overlapping search
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            boolean keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            binding.bottomNavigationCard.setVisibility(keyboardVisible ? android.view.View.GONE : android.view.View.VISIBLE);
            
            // Adjust bottom margin dynamically to account for system navigation bar gesture line / curved screen vertices
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp = 
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) binding.bottomNavigationCard.getLayoutParams();
            lp.bottomMargin = Math.max(bottomInset, (int)(12 * getResources().getDisplayMetrics().density));
            binding.bottomNavigationCard.setLayoutParams(lp);
            
            return insets;
        });

        // Bind Custom View Source Button Click
        binding.btnViewSource.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/snap24/notification-vault"));
            startActivity(intent);
        });

        // Check permissions in sequence
        checkPermissionsSequence();

        // Request post notification permission for Android 13+ (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) 
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, 
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Trigger auto-delete cleanup on startup
        runAutoDeleteCleanup();

        // Initialize dynamic launcher shortcuts
        com.zygisk_enc.notivault.util.ShortcutHelper.updateDynamicShortcuts(this);

        // Handle launcher shortcut action if any
        handleShortcutIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcutIntent(intent);
    }

    private void handleShortcutIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("shortcut_action");
        if (action == null) return;

        if ("open_favorites".equals(action)) {
            if (navController != null) {
                navController.navigate(R.id.navigation_history);
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                        new androidx.lifecycle.ViewModelProvider(this)
                        .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                vm.setFilterPackage(null);
                vm.setFilterFavorites(true);
                vm.setDateFilter(null, null);
                vm.requestScrollToTop();
            }
        } else if ("open_search".equals(action)) {
            if (navController != null) {
                navController.navigate(R.id.navigation_history);
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                        new androidx.lifecycle.ViewModelProvider(this)
                        .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                vm.requestOpenSearch();
            }
        } else if ("toggle_capture".equals(action)) {
            Intent authIntent = new Intent(this, com.zygisk_enc.notivault.util.AuthActionActivity.class);
            authIntent.putExtra(com.zygisk_enc.notivault.util.AuthActionActivity.EXTRA_ACTION,
                    com.zygisk_enc.notivault.util.AuthActionActivity.ACTION_TOGGLE_CAPTURE);
            startActivity(authIntent);
        }
    }

    private void runAutoDeleteCleanup() {
        int days = PreferenceUtil.getAutoDeleteDays(this);
        if (days > 0) {
            long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
            String mode = PreferenceUtil.getAutoDeleteMode(this);
            com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
                AppDatabase db = AppDatabase.getInstance(MainActivity.this);
                if ("per_app".equals(mode)) {
                    java.util.Set<String> pkgs = PreferenceUtil.getAutoDeletePackages(MainActivity.this);
                    if (pkgs != null && !pkgs.isEmpty()) {
                        java.util.List<String> pkgList = new java.util.ArrayList<>(pkgs);
                        java.util.List<String> imagePaths = db.notificationDao().getOldImagePathsForPackages(cutoff, pkgList);
                        if (imagePaths != null) {
                            for (String p : imagePaths) deleteEncryptedImage(p);
                        }
                        db.notificationDao().deleteOlderThanForPackages(cutoff, pkgList);
                    }
                } else {
                    java.util.List<String> imagePaths = db.notificationDao().getOldImagePaths(cutoff);
                    if (imagePaths != null) {
                        for (String p : imagePaths) deleteEncryptedImage(p);
                    }
                    db.notificationDao().deleteOlderThan(cutoff);
                }
            });
        }
    }

    private void deleteEncryptedImage(String imagePath) {
        if (imagePath != null && !imagePath.isEmpty()) {
            String[] paths = imagePath.split("\\|");
            for (String p : paths) {
                if (p != null && !p.trim().isEmpty()) {
                    try {
                        java.io.File f = new java.io.File(p.trim());
                        if (f.exists()) f.delete();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void checkBiometricLock() {
        boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("biometric_lock", false);
        
        if (isBiometricEnabled && !com.zygisk_enc.notivault.util.AppLockManager.isUnlocked()) {
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
                com.zygisk_enc.notivault.util.AppLockManager.setUnlocked(true);
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
        checkPermissionsSequence();
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

    private androidx.appcompat.app.AlertDialog activePermissionDialog = null;

    private boolean isAccessibilityServiceEnabled() {
        String expectedPackage = getPackageName();
        String expectedClass = com.zygisk_enc.notivault.service.ToastRecorderService.class.getName();
        
        String enabledServicesSetting = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null) return false;
        
        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);
        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName cn = ComponentName.unflattenFromString(componentNameString);
            if (cn != null && cn.getPackageName().equals(expectedPackage) 
                    && cn.getClassName().equals(expectedClass)) {
                return true;
            }
        }
        return false;
    }

    private void checkPermissionsSequence() {
        if (activePermissionDialog != null && activePermissionDialog.isShowing()) {
            return;
        }

        boolean prompted = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("accessibility_prompted", false);

        if (!isNotificationServiceEnabled()) {
            showPermissionDialog();
        } else if (!isAccessibilityServiceEnabled() && !prompted) {
            showAccessibilityPermissionDialog();
        }
    }

    private void showPermissionDialog() {
        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_permission_instruction, null);
        activePermissionDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_required_title)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(R.string.grant_access, (d, which) -> {
                    activePermissionDialog = null;
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                })
                .setNegativeButton(R.string.not_now, (d, which) -> {
                    activePermissionDialog = null;
                    // Proceed to check next permission in sequence
                    checkPermissionsSequence();
                })
                .create();

        activePermissionDialog.setOnShowListener(d -> {
            android.widget.Button positiveButton = activePermissionDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            positiveButton.setEnabled(false);
            new android.os.CountDownTimer(3000, 1000) {
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
        
        activePermissionDialog.show();
    }

    private void showAccessibilityPermissionDialog() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("accessibility_prompted", true).apply();

        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_accessibility_permission_instruction, null);
        activePermissionDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Accessibility Access Required")
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(R.string.grant_access, (d, which) -> {
                    activePermissionDialog = null;
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                })
                .setNegativeButton(R.string.not_now, (d, which) -> {
                    activePermissionDialog = null;
                })
                .create();

        activePermissionDialog.show();
    }

    public void setOnDeleteAppsClickListener(android.view.View.OnClickListener listener) {
        if (binding != null && binding.btnDeleteApps != null) {
            binding.btnDeleteApps.setOnClickListener(listener);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("is_authenticated", com.zygisk_enc.notivault.util.AppLockManager.isUnlocked());
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController != null && navController.navigateUp() || super.onSupportNavigateUp();
    }
}
