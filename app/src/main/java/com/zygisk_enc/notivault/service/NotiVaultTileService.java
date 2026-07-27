package com.zygisk_enc.notivault.service;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.graphics.drawable.Icon;
import com.zygisk_enc.notivault.R;
import com.zygisk_enc.notivault.util.PreferenceUtil;

public class NotiVaultTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            boolean isEnabled = PreferenceUtil.isCaptureEnabled(this);
            tile.setState(isEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(isEnabled ? "Vault Logging On" : "Vault Logging Off");
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_lock));
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile != null) {
            boolean isEnabled = PreferenceUtil.isCaptureEnabled(this);
            boolean newEnabled = !isEnabled;
            PreferenceUtil.setCaptureEnabled(this, newEnabled);
            tile.setState(newEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(newEnabled ? "Vault Logging On" : "Vault Logging Off");
            tile.updateTile();
        }
    }
}
