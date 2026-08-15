package com.cloudflared.tunnel.tile;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.cloudflared.tunnel.R;
import com.cloudflared.tunnel.model.Config;
import com.cloudflared.tunnel.service.TunnelService;
import com.cloudflared.tunnel.utils.CloudflaredRunner;
import com.cloudflared.tunnel.utils.ConfigManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RequiresApi(api = Build.VERSION_CODES.N)
public class QuickTileService extends TileService {

    private static final String TAG = "QuickTileService";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        executor.execute(() -> {
            boolean running = CloudflaredRunner.isRunning(this);
            if (running) {
                Intent intent = new Intent(this, TunnelService.class);
                intent.setAction(TunnelService.ACTION_STOP);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } else {
                ConfigManager cm = new ConfigManager(this);
                Config config = cm.getDefaultConfig();
                if (config != null) {
                    Intent intent = new Intent(this, TunnelService.class);
                    intent.setAction(TunnelService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            updateTile();
        });
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean running = CloudflaredRunner.isRunning(this);
        if (running) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("Stop Tunnel");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Start Tunnel");
        }
        tile.updateTile();
    }
}
