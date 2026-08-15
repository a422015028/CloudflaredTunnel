package com.cloudflared.tunnel.service;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.cloudflared.tunnel.App;
import com.cloudflared.tunnel.model.Config;
import com.cloudflared.tunnel.utils.CloudflaredRunner;
import com.cloudflared.tunnel.utils.ConfigManager;
import com.cloudflared.tunnel.utils.NotificationHelper;

public class TunnelService extends Service {

    private static final String TAG = "TunnelService";
    public static final String ACTION_START = "com.cloudflared.tunnel.START";
    public static final String ACTION_STOP = "com.cloudflared.tunnel.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        startForeground(1, NotificationHelper.buildForegroundNotification(this, false));

        new Thread(() -> {
            if (ACTION_STOP.equals(action)) {
                CloudflaredRunner.stop(this);
                sendStatusBroadcast();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }

            ConfigManager cm = new ConfigManager(this);
            Config config = cm.getDefaultConfig();
            if (config == null) {
                Log.w(TAG, "No default config");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }

            boolean ok = CloudflaredRunner.start(this,
                    config.getToken(),
                    config.getProtocol());

            if (ok) {
                startForeground(1, NotificationHelper.buildForegroundNotification(this, true));
            }
            sendStatusBroadcast();
        }).start();

        return START_STICKY;
    }

    private void sendStatusBroadcast() {
        Intent broadcast = new Intent(App.ACTION_STATUS_CHANGED);
        sendBroadcast(broadcast);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        CloudflaredRunner.stop(this);
        sendStatusBroadcast();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
