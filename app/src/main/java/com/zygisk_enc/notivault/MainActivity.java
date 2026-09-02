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

public class MainActivity extends BaseActivity {

    private ActivityMainBinding binding;
    private NavController navController;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener profilePrefListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation, (v, insets) -> insets);
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfig);
            NavigationUI.setupWithNavController(binding.bottomNavigation, navController);
            binding.bottomNavigation.setOnItemSelectedListener(item -> {
                if (item.getItemId() == R.id.action_switch_profile) {
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                            new androidx.lifecycle.ViewModelProvider(this)
                            .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                    vm.toggleProfileMode();
                    binding.bottomNavigation.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                    return false;
                }
                if (item.getItemId() == R.id.navigation_history) {
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                            new androidx.lifecycle.ViewModelProvider(this)
                            .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                    vm.resetAllFilters();
                    vm.requestScrollToTop();
                }
                return NavigationUI.onNavDestinationSelected(item, navController);
            });

            binding.bottomNavigation.setOnItemReselectedListener(item -> {
                if (item.getItemId() == R.id.action_switch_profile) {
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                            new androidx.lifecycle.ViewModelProvider(this)
                            .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                    vm.toggleProfileMode();
                    binding.bottomNavigation.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                    return;
                }
                if (item.getItemId() == R.id.navigation_history) {
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                            new androidx.lifecycle.ViewModelProvider(this)
                            .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                    vm.resetAllFilters();
                    vm.requestScrollToTop();
                }
            });

            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                if (destination.getId() == R.id.navigation_settings) {
                    binding.btnToolbarWidgets.setVisibility(android.view.View.VISIBLE);
                } else {
                    binding.btnToolbarWidgets.setVisibility(android.view.View.GONE);
                }

                if (destination.getId() != R.id.navigation_history) {
                    setToolbarScrollDate(null, false);
                }
            });
        }

        binding.btnToolbarWidgets.setOnClickListener(v -> showWidgetsGuideDialog());

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

        if (com.zygisk_enc.notivault.util.DatabaseMigrationHelper.needsMigration(this)) {
            showLegacyDatabaseMigrationDialog();
        } else {
            initAppServicesAndViewModel();
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
        checkPendingPostImportVerification();
    }

    private void handleShortcutIntent(Intent intent) {
        if (intent == null) return;

        // Check if intent came from tapping a widget feed notification item
        String pkgName = intent.getStringExtra("package_name");
        if (pkgName != null && !pkgName.trim().isEmpty()) {
            if (navController != null) {
                navController.navigate(R.id.navigation_history);
                com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                        new androidx.lifecycle.ViewModelProvider(this)
                        .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                vm.setFilterFavorites(false);
                vm.setDateFilter(null, null);
                vm.setFilterPackage(pkgName.trim());
                vm.requestScrollToTop();
            }
            intent.removeExtra("package_name");
            intent.removeExtra("notification_id");
            return;
        }

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

    private void updateSearchCountPill(String query, java.util.List<?> list) {
        boolean shouldShow = query != null && !query.trim().isEmpty();
        int newVisibility = shouldShow ? android.view.View.VISIBLE : android.view.View.GONE;
        if (binding.cardToolbarSearchCount.getVisibility() != newVisibility) {
            androidx.transition.TransitionManager.beginDelayedTransition(binding.layoutToolbarPills, new androidx.transition.ChangeBounds().setDuration(200));
            binding.cardToolbarSearchCount.setVisibility(newVisibility);
        }
        if (shouldShow) {
            int count = list != null ? list.size() : 0;
            binding.tvToolbarSearchCount.setText(getString(R.string.search_found_count, count));
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

    private void setupProfileSwitcher(com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm) {
        boolean hasWorkProfile = com.zygisk_enc.notivault.util.ProfileUtil.hasWorkProfile(this);
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp =
                (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) binding.bottomNavigationCard.getLayoutParams();
        int marginHorizontal = (int) ((hasWorkProfile ? 24 : 52) * getResources().getDisplayMetrics().density);
        lp.leftMargin = marginHorizontal;
        lp.rightMargin = marginHorizontal;
        binding.bottomNavigationCard.setLayoutParams(lp);

        android.view.Menu menu = binding.bottomNavigation.getMenu();
        if (hasWorkProfile) {
            if (menu.findItem(R.id.action_switch_profile) == null) {
                android.view.MenuItem switchItem = menu.add(
                        android.view.Menu.NONE,
                        R.id.action_switch_profile,
                        4,
                        R.string.nav_switch
                );
                switchItem.setIcon(R.drawable.sel_nav_switch);
            }
        } else {
            menu.removeItem(R.id.action_switch_profile);
        }

        vm.getProfileMode().observe(this, mode -> {
            int currentMode = mode != null ? mode : 0;
            if (currentMode == 0) {
                // Personal Profile Active
                binding.cardToolbarWorkTag.setVisibility(android.view.View.GONE);
            } else {
                // Work Profile Active
                binding.cardToolbarWorkTag.setVisibility(android.view.View.VISIBLE);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.zygisk_enc.notivault.viewmodel.NotificationViewModel notifViewModel =
                new androidx.lifecycle.ViewModelProvider(this)
                .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
        setupProfileSwitcher(notifViewModel);
        checkPermissionsSequence();
        checkPendingPostImportVerification();
    }

    private void checkPendingPostImportVerification() {
        boolean pending = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("pending_post_import_verify", false);
        boolean fromIntent = getIntent() != null && getIntent().getBooleanExtra("trigger_verify_integrity", false);
        if (pending || fromIntent) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().remove("pending_post_import_verify").apply();
            if (getIntent() != null) {
                getIntent().removeExtra("trigger_verify_integrity");
            }
            com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                    new androidx.lifecycle.ViewModelProvider(this)
                    .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
            vm.clearPostImportVerificationTrigger();

            com.zygisk_enc.notivault.util.DatabaseIntegrityHelper.runIntegrityCheck(this, () -> {
                vm.refreshAppSummaries();
            });
        }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private androidx.appcompat.app.AlertDialog activePermissionDialog = null;

    private boolean isAccessibilityServiceEnabled() {
        String expectedPackage = getPackageName();
        String expectedClass = com.zygisk_enc.notivault.service.ToastRecorderService.class.getName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;

        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServices);
        while (colonSplitter.hasNext()) {
            String componentName = colonSplitter.next();
            ComponentName cn = ComponentName.unflattenFromString(componentName);
            if (cn != null && cn.getPackageName().equals(expectedPackage)
                    && cn.getClassName().equals(expectedClass)) {
                return true;
            }
        }
        return false;
    }

    private void checkPermissionsSequence() {
        if (!isNotificationServiceEnabled()) {
            showNotificationPermissionDialog();
        } else if (!isAccessibilityServiceEnabled()) {
            boolean prompted = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("accessibility_prompted", false);
            if (!prompted) {
                showAccessibilityPermissionDialog();
            }
        }
    }

    private void showNotificationPermissionDialog() {
        if (activePermissionDialog != null && activePermissionDialog.isShowing()) {
            return;
        }

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
                    checkPermissionsSequence();
                })
                .create();

        activePermissionDialog.setOnShowListener(d -> {
            android.widget.Button positiveButton = activePermissionDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            positiveButton.setEnabled(false);
            new android.os.CountDownTimer(3000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    positiveButton.setText(getString(R.string.grant_access_countdown, ((millisUntilFinished / 1000) + 1)));
                }

                @Override
                public void onFinish() {
                    positiveButton.setEnabled(true);
                    positiveButton.setText(R.string.grant_access);
                }
            }.start();
        });
        
        showDialog(this, activePermissionDialog);
    }

    private void showAccessibilityPermissionDialog() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("accessibility_prompted", true).apply();

        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_accessibility_permission_instruction, null);
        activePermissionDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.accessibility_permission_required_title)
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

        showDialog(this, activePermissionDialog);
    }

    private void showWidgetsGuideDialog() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_widgets_guide, null);
        dialog.setContentView(dialogView);

        android.view.View btnClose = dialogView.findViewById(R.id.btn_close_dialog);
        android.view.View btnGotIt = dialogView.findViewById(R.id.btn_got_it);
        android.view.View cardPinSection = dialogView.findViewById(R.id.card_pin_widgets_section);
        com.google.android.material.button.MaterialButton btnPinDashboard = dialogView.findViewById(R.id.btn_pin_dashboard);
        com.google.android.material.button.MaterialButton btnPinFeed = dialogView.findViewById(R.id.btn_pin_feed);
        com.google.android.material.button.MaterialButton btnPinCapture = dialogView.findViewById(R.id.btn_pin_capture);

        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
        if (btnGotIt != null) btnGotIt.setOnClickListener(v -> dialog.dismiss());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.appwidget.AppWidgetManager appWidgetManager =
                    getSystemService(android.appwidget.AppWidgetManager.class);
            if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported()) {
                if (cardPinSection != null) cardPinSection.setVisibility(android.view.View.VISIBLE);

                if (btnPinDashboard != null) {
                    btnPinDashboard.setOnClickListener(v -> {
                        ComponentName provider = new ComponentName(this, com.zygisk_enc.notivault.widget.VaultDashboardWidgetProvider.class);
                        appWidgetManager.requestPinAppWidget(provider, null, null);
                    });
                }
                if (btnPinFeed != null) {
                    btnPinFeed.setOnClickListener(v -> {
                        ComponentName provider = new ComponentName(this, com.zygisk_enc.notivault.widget.NotificationFeedWidgetProvider.class);
                        appWidgetManager.requestPinAppWidget(provider, null, null);
                    });
                }
                if (btnPinCapture != null) {
                    btnPinCapture.setOnClickListener(v -> {
                        ComponentName provider = new ComponentName(this, com.zygisk_enc.notivault.widget.CaptureWidgetProvider.class);
                        appWidgetManager.requestPinAppWidget(provider, null, null);
                    });
                }
            } else if (cardPinSection != null) {
                cardPinSection.setVisibility(android.view.View.GONE);
            }
        } else if (cardPinSection != null) {
            cardPinSection.setVisibility(android.view.View.GONE);
        }

        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        dialog.setOnShowListener(d -> {
            dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        });

        showDialog(this, dialog);
    }

    private void showLegacyDatabaseMigrationDialog() {
        if (isFinishing() || isDestroyed()) return;

        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_database_migration, null);
        com.google.android.material.progressindicator.LinearProgressIndicator progressBar =
                dialogView.findViewById(R.id.progress_migration);
        android.widget.TextView tvProgressText = dialogView.findViewById(R.id.tv_migration_progress_text);

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        com.zygisk_enc.notivault.util.DatabaseMigrationHelper.migrateAsync(this, new com.zygisk_enc.notivault.util.DatabaseMigrationHelper.MigrationProgressListener() {
            @Override
            public void onProgress(int current, int total, int percentage) {
                runOnUiThread(() -> {
                    progressBar.setProgressCompat(percentage, true);
                    tvProgressText.setText(getString(R.string.migration_dialog_progress, current, total, percentage));
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    com.google.android.material.snackbar.Snackbar.make(
                            binding.getRoot(), R.string.migration_dialog_complete, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
                    initAppServicesAndViewModel();
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    android.widget.Toast.makeText(MainActivity.this, "Migration error: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    initAppServicesAndViewModel();
                });
            }
        });
    }

    private void initAppServicesAndViewModel() {
        if (isFinishing() || isDestroyed()) return;

        // Ensure database search tokens are indexed in background
        com.zygisk_enc.notivault.util.BlindIndexManager.ensureDatabaseIndexed(this);

        // Check and trigger background database bundling (first 3 opens + weekly maintenance)
        com.zygisk_enc.notivault.util.BundleManager.checkAndTriggerAppLaunchBundling(this);

        // Check and perform one-time migration for legacy per-record encrypted entries
        checkAndPerformLegacyMigration();

        com.zygisk_enc.notivault.viewmodel.NotificationViewModel notifViewModel =
                new androidx.lifecycle.ViewModelProvider(this)
                .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);

        profilePrefListener = (prefs, key) -> {
            if ("force_show_work_profile".equals(key)) {
                com.zygisk_enc.notivault.util.ProfileUtil.invalidateCache();
                runOnUiThread(() -> {
                    setupProfileSwitcher(notifViewModel);
                    boolean isEnabled = prefs.getBoolean("force_show_work_profile", false);
                    if (!isEnabled && notifViewModel.getProfileMode().getValue() != null && notifViewModel.getProfileMode().getValue() != 0) {
                        notifViewModel.setProfileMode(0);
                    }
                });
            }
        };
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(profilePrefListener);

        final android.os.Handler progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable hideProgressRunnable = () -> {
            if (binding != null && binding.cardToolbarDecryption != null) {
                binding.cardToolbarDecryption.animate().cancel();
                binding.cardToolbarDecryption.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                    if (binding != null) {
                        binding.cardToolbarDecryption.setVisibility(android.view.View.GONE);
                    }
                }).start();
            }
        };

        notifViewModel.getOperationProgress().observe(this, op -> {
            boolean shouldShow = op != null && op.type != com.zygisk_enc.notivault.viewmodel.NotificationViewModel.OperationProgress.TYPE_NONE && op.progress >= 0;
            if (shouldShow) {
                progressHandler.removeCallbacks(hideProgressRunnable);
                if (binding.cardToolbarDecryption.getVisibility() != android.view.View.VISIBLE) {
                    binding.cardToolbarDecryption.animate().cancel();
                    binding.cardToolbarDecryption.setAlpha(0f);
                    binding.cardToolbarDecryption.setVisibility(android.view.View.VISIBLE);
                    binding.cardToolbarDecryption.animate().alpha(1f).setDuration(120).start();
                }
                int progress = Math.min(100, Math.max(0, op.progress));
                binding.progressToolbarDecryption.setProgressCompat(progress, true);
                if (progress >= 100) {
                    progressHandler.removeCallbacks(hideProgressRunnable);
                    progressHandler.postDelayed(hideProgressRunnable, 200);
                }
            } else {
                progressHandler.removeCallbacks(hideProgressRunnable);
                progressHandler.postDelayed(hideProgressRunnable, 80);
            }
        });

        // Observe search query and notifications to update search count pill
        notifViewModel.getSearchQuery().observe(this, query -> updateSearchCountPill(query, notifViewModel.getNotifications().getValue()));
        notifViewModel.getNotifications().observe(this, list -> updateSearchCountPill(notifViewModel.getSearchQuery().getValue(), list));

        // Observe post-import verification event
        notifViewModel.getPostImportVerificationTrigger().observe(this, trigger -> {
            if (trigger != null && trigger) {
                notifViewModel.clearPostImportVerificationTrigger();
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().remove("pending_post_import_verify").apply();
                com.zygisk_enc.notivault.util.DatabaseIntegrityHelper.runIntegrityCheck(this, () -> {
                    notifViewModel.refreshAppSummaries();
                });
            }
        });

        // Setup Profile Switcher (Personal vs Work Profile)
        setupProfileSwitcher(notifViewModel);

        // Trigger auto-delete cleanup on startup
        runAutoDeleteCleanup();

        // Handle launcher shortcut action if any
        handleShortcutIntent(getIntent());
    }

    private void checkAndPerformLegacyMigration() {
        if (com.zygisk_enc.notivault.util.LegacyRecordConverter.isMigrationCompleted(this)) {
            return;
        }
        com.zygisk_enc.notivault.util.AppExecutor.execute(() -> {
            int legacyCount = com.zygisk_enc.notivault.util.LegacyRecordConverter.countLegacyRecords(this);
            if (legacyCount > 0) {
                runOnUiThread(() -> showMigrationDialog(legacyCount));
            } else {
                com.zygisk_enc.notivault.util.LegacyRecordConverter.setMigrationCompleted(this, true);
            }
        });
    }

    private void showMigrationDialog(int totalRecords) {
        if (isFinishing() || isDestroyed()) return;

        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_database_migration, null);
        com.google.android.material.progressindicator.LinearProgressIndicator progressBar =
                dialogView.findViewById(R.id.progress_migration);
        android.widget.TextView tvProgressText = dialogView.findViewById(R.id.tv_migration_progress_text);

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        com.zygisk_enc.notivault.util.LegacyRecordConverter.convertAll(this, com.zygisk_enc.notivault.util.LegacyRecordConverter.ACTION_PLACEHOLDER, new com.zygisk_enc.notivault.util.LegacyRecordConverter.MigrationProgressListener() {
            @Override
            public void onProgress(int current, int total, int percentage) {
                runOnUiThread(() -> {
                    progressBar.setProgressCompat(percentage, true);
                    tvProgressText.setText(getString(R.string.migration_dialog_progress, current, total, percentage));
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    com.google.android.material.snackbar.Snackbar.make(
                            binding.getRoot(), R.string.migration_dialog_complete, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();

                    // Refresh feed with clean plaintext
                    com.zygisk_enc.notivault.viewmodel.NotificationViewModel vm =
                            new androidx.lifecycle.ViewModelProvider(MainActivity.this)
                            .get(com.zygisk_enc.notivault.viewmodel.NotificationViewModel.class);
                    vm.resetAllFilters();
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                });
            }
        });
    }

    public void setToolbarScrollDate(String label, boolean visible) {
        if (binding == null) return;
        if (visible && label != null && !label.isEmpty()) {
            binding.tvToolbarScrollDate.setText(label);
            if (binding.cardToolbarScrollDate.getVisibility() != android.view.View.VISIBLE) {
                binding.cardToolbarScrollDate.animate().cancel();
                binding.cardToolbarScrollDate.setAlpha(0f);
                binding.cardToolbarScrollDate.setVisibility(android.view.View.VISIBLE);
                binding.cardToolbarScrollDate.animate().alpha(1f).setDuration(150).start();
            }
        } else {
            if (binding.cardToolbarScrollDate.getVisibility() != android.view.View.GONE) {
                binding.cardToolbarScrollDate.animate().cancel();
                binding.cardToolbarScrollDate.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                    if (binding != null) binding.cardToolbarScrollDate.setVisibility(android.view.View.GONE);
                }).start();
            }
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController != null && navController.navigateUp() || super.onSupportNavigateUp();
    }

    @Override
    protected void onDestroy() {
        if (profilePrefListener != null) {
            PreferenceManager.getDefaultSharedPreferences(this)
                    .unregisterOnSharedPreferenceChangeListener(profilePrefListener);
        }
        if (!isChangingConfigurations()) {
            com.zygisk_enc.notivault.viewmodel.NotificationViewModel.clearDecryptedCache();
        }
        super.onDestroy();
    }
}
