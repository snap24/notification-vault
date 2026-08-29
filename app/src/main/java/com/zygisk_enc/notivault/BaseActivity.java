package com.zygisk_enc.notivault;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zygisk_enc.notivault.util.AppLockManager;
import com.zygisk_enc.notivault.util.ThemeHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

public abstract class BaseActivity extends AppCompatActivity {

    private View lockOverlayView;
    private boolean isAuthenticating = false;
    private final Set<Dialog> activeDialogs = Collections.newSetFromMap(new WeakHashMap<>());

    public static BaseActivity findBaseActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof BaseActivity) {
                return (BaseActivity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static AlertDialog showDialog(Context context, MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.show();
        BaseActivity activity = findBaseActivity(context);
        if (activity != null) {
            activity.registerActiveDialog(dialog);
        }
        return dialog;
    }

    public static <T extends Dialog> T showDialog(Context context, T dialog) {
        dialog.show();
        BaseActivity activity = findBaseActivity(context);
        if (activity != null) {
            activity.registerActiveDialog(dialog);
        }
        return dialog;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            AppLockManager.setUnlocked(savedInstanceState.getBoolean("is_authenticated", AppLockManager.isUnlocked()));
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        setupLockOverlay();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        setupLockOverlay();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        setupLockOverlay();
    }

    private void setupLockOverlay() {
        ViewGroup root = findViewById(android.R.id.content);
        if (root != null && lockOverlayView == null) {
            View existingOverlay = findViewById(R.id.layout_lock_overlay);
            if (existingOverlay != null) {
                lockOverlayView = existingOverlay;
            } else {
                lockOverlayView = LayoutInflater.from(this).inflate(R.layout.view_lock_overlay, root, false);
                root.addView(lockOverlayView);
            }
            MaterialButton btnUnlock = lockOverlayView.findViewById(R.id.btn_unlock);
            if (btnUnlock != null) {
                btnUnlock.setOnClickListener(v -> showBiometricPrompt());
            }
            lockOverlayView.setVisibility(View.GONE);
        }
    }

    public void registerActiveDialog(Dialog dialog) {
        if (dialog != null) {
            activeDialogs.add(dialog);
            dialog.setOnDismissListener(d -> activeDialogs.remove(dialog));
        }
    }

    public void unregisterActiveDialog(Dialog dialog) {
        if (dialog != null) {
            activeDialogs.remove(dialog);
        }
    }

    public void dismissAllOpenDialogs() {
        // Dismiss all DialogFragments (MaterialDatePicker, etc.)
        try {
            dismissDialogsInFragmentManager(getSupportFragmentManager());
        } catch (Exception ignored) {}

        // Dismiss all tracked Dialogs (AlertDialogs, custom dialogs)
        List<Dialog> dialogsCopy = new ArrayList<>(activeDialogs);
        for (Dialog d : dialogsCopy) {
            if (d != null && d.isShowing()) {
                try {
                    d.dismiss();
                } catch (Exception ignored) {}
            }
        }
        activeDialogs.clear();
    }

    private void dismissDialogsInFragmentManager(FragmentManager fm) {
        if (fm == null) return;
        List<Fragment> fragments = fm.getFragments();
        for (Fragment f : fragments) {
            if (f != null) {
                if (f instanceof DialogFragment) {
                    try {
                        ((DialogFragment) f).dismissAllowingStateLoss();
                    } catch (Exception ignored) {}
                }
                dismissDialogsInFragmentManager(f.getChildFragmentManager());
            }
        }
    }

    public void checkBiometricLock() {
        boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("biometric_lock", false);

        if (isBiometricEnabled && !AppLockManager.isUnlocked()) {
            dismissAllOpenDialogs();
            if (lockOverlayView != null) {
                lockOverlayView.setVisibility(View.VISIBLE);
                lockOverlayView.bringToFront();
            }
            showBiometricPrompt();
        } else {
            if (lockOverlayView != null) {
                lockOverlayView.setVisibility(View.GONE);
            }
        }
    }

    public void showBiometricPrompt() {
        if (isAuthenticating || isFinishing() || isDestroyed()) return;
        isAuthenticating = true;

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                isAuthenticating = false;
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                isAuthenticating = false;
                AppLockManager.setUnlocked(true);
                if (lockOverlayView != null) {
                    lockOverlayView.setVisibility(View.GONE);
                }
                onAuthenticated();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                isAuthenticating = false;
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock_prompt_title))
                .setSubtitle(getString(R.string.auth_confirm_unlock))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                                          BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    protected void onAuthenticated() {
        // Optional hook for subclasses
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBiometricLock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!AppLockManager.isUnlocked()) {
            dismissAllOpenDialogs();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("is_authenticated", AppLockManager.isUnlocked());
    }
}
