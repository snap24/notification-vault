package com.zygisk_enc.notivault.util;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.database.AppDatabase;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified database integrity verification engine and legacy record recovery controller.
 * Can be safely invoked from Settings, Encryption Settings, or automatically after
 * backup import and streak bundling completes.
 */
public class DatabaseIntegrityHelper {

    private static final AtomicBoolean isVerifying = new AtomicBoolean(false);

    public static boolean isVerifying() {
        return isVerifying.get();
    }

    /**
     * Executes database integrity check with live progress modal.
     * Guaranteed crash-safe across Activity lifecycles.
     */
    public static void runIntegrityCheck(Activity activity, Runnable onFinished) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (onFinished != null) onFinished.run();
            return;
        }

        if (!isVerifying.compareAndSet(false, true)) {
            // Already verifying
            return;
        }

        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_database_migration, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_migration_title);
        TextView tvMessage = dialogView.findViewById(R.id.tv_migration_message);
        LinearProgressIndicator progressIndicator = dialogView.findViewById(R.id.progress_migration);
        TextView tvProgressText = dialogView.findViewById(R.id.tv_migration_progress_text);

        tvTitle.setText(R.string.verify_dialog_title);
        tvMessage.setText(R.string.verify_dialog_message);
        progressIndicator.setProgressCompat(0, false);
        tvProgressText.setText("0%");

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);

        try {
            dialog.show();
        } catch (Exception e) {
            isVerifying.set(false);
            if (onFinished != null) onFinished.run();
            return;
        }

        AppExecutor.execute(() -> {
            boolean ok = true;
            LegacyRecordConverter.ScanResult scanResult = null;
            try {
                AppDatabase db = AppDatabase.getInstance(activity);
                // Simple read validation to verify page decryption integrity
                db.notificationDao().getRecentNotificationsSync(10);
                db.toastDao().getAllToastsSync();
                scanResult = LegacyRecordConverter.scanLegacyRecords(activity, (current, total, percentage) -> {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        progressIndicator.setProgressCompat(percentage, true);
                        tvProgressText.setText(activity.getString(R.string.verify_dialog_progress, current, total, percentage));
                    });
                });
            } catch (Exception e) {
                ok = false;
                e.printStackTrace();
            }

            final boolean finalOk = ok;
            final LegacyRecordConverter.ScanResult finalScan = scanResult;

            activity.runOnUiThread(() -> {
                isVerifying.set(false);
                dismissDialogSafely(dialog);

                if (activity.isFinishing() || activity.isDestroyed()) {
                    if (onFinished != null) onFinished.run();
                    return;
                }

                if (finalOk && finalScan != null && finalScan.unrecoverableCount > 0) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.verify_unrecoverable_title)
                            .setMessage(activity.getString(R.string.verify_unrecoverable_message, finalScan.unrecoverableCount))
                            .setPositiveButton(R.string.verify_action_keep, (d, w) ->
                                    runManualOptimization(activity, LegacyRecordConverter.ACTION_PLACEHOLDER, onFinished))
                            .setNeutralButton(R.string.verify_action_delete, (d, w) ->
                                    runManualOptimization(activity, LegacyRecordConverter.ACTION_DELETE, onFinished))
                            .setNegativeButton(R.string.cancel, (d, w) -> {
                                if (onFinished != null) onFinished.run();
                            })
                            .show();
                } else if (finalOk && finalScan != null && finalScan.recoverableCount > 0) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.verify_recoverable_title)
                            .setMessage(activity.getString(R.string.verify_recoverable_message, finalScan.recoverableCount))
                            .setPositiveButton(R.string.verify_action_optimize, (d, w) ->
                                    runManualOptimization(activity, LegacyRecordConverter.ACTION_PLACEHOLDER, onFinished))
                            .setNegativeButton(R.string.cancel, (d, w) -> {
                                if (onFinished != null) onFinished.run();
                            })
                            .show();
                } else {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(finalOk ? R.string.verify_success_title : R.string.verify_failed_title)
                            .setMessage(finalOk ? R.string.verify_success_message : R.string.verify_failed_message)
                            .setPositiveButton(android.R.string.ok, (d, w) -> {
                                if (onFinished != null) onFinished.run();
                            })
                            .setOnDismissListener(d -> {
                                if (onFinished != null) onFinished.run();
                            })
                            .show();
                }
            });
        });
    }

    private static void runManualOptimization(Activity activity, int action, Runnable onFinished) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (onFinished != null) onFinished.run();
            return;
        }

        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_database_migration, null);
        LinearProgressIndicator progressIndicator = dialogView.findViewById(R.id.progress_migration);
        TextView tvProgressText = dialogView.findViewById(R.id.tv_migration_progress_text);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);

        try {
            dialog.show();
        } catch (Exception e) {
            if (onFinished != null) onFinished.run();
            return;
        }

        LegacyRecordConverter.setMigrationCompleted(activity, false);
        LegacyRecordConverter.convertAll(activity, action, new LegacyRecordConverter.MigrationProgressListener() {
            @Override
            public void onProgress(int current, int total, int percentage) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    progressIndicator.setProgressCompat(percentage, true);
                    tvProgressText.setText(activity.getString(R.string.migration_dialog_progress, current, total, percentage));
                });
            }

            @Override
            public void onComplete() {
                activity.runOnUiThread(() -> {
                    dismissDialogSafely(dialog);
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        if (onFinished != null) onFinished.run();
                        return;
                    }
                    Toast.makeText(activity, R.string.migration_dialog_complete, Toast.LENGTH_SHORT).show();
                    if (onFinished != null) onFinished.run();
                });
            }

            @Override
            public void onError(Exception e) {
                activity.runOnUiThread(() -> {
                    dismissDialogSafely(dialog);
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        if (onFinished != null) onFinished.run();
                        return;
                    }
                    Toast.makeText(activity, "Optimization error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    if (onFinished != null) onFinished.run();
                });
            }
        });
    }

    private static void dismissDialogSafely(Dialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Exception ignored) {}
    }
}
