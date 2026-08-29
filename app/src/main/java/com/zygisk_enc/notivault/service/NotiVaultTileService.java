package com.zygisk_enc.notivault.service;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import androidx.preference.PreferenceManager;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.util.AuthActionActivity;
import com.zygisk_enc.notivault.util.PreferenceUtil;
import com.zygisk_enc.notivault.util.ShortcutHelper;
import com.zygisk_enc.notivault.widget.WidgetHelper;

public class NotiVaultTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile != null) {
            boolean isEnabled = PreferenceUtil.isCaptureEnabled(this);
            tile.setState(isEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(getString(isEnabled ? R.string.tile_label_on : R.string.tile_label_off));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_lock));
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean isBiometricEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("biometric_lock", false);

        if (isBiometricEnabled) {
            Intent intent = new Intent(this, AuthActionActivity.class);
            intent.putExtra(AuthActionActivity.EXTRA_ACTION, AuthActionActivity.ACTION_TOGGLE_CAPTURE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(intent);
            }
        } else {
            boolean current = PreferenceUtil.isCaptureEnabled(this);
            boolean next = !current;
            PreferenceUtil.setCaptureEnabled(this, next);
            updateTileState();
            ShortcutHelper.updateDynamicShortcuts(this);
            WidgetHelper.updateAllWidgets(this);
        }
    }
}
