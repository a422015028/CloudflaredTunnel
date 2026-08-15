package com.cloudflared.tunnel.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.cloudflared.tunnel.App;
import com.cloudflared.tunnel.utils.CloudflaredRunner;
import com.cloudflared.tunnel.utils.NotificationHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MonitorService extends Service {

    private static final String TAG = "MonitorService";
    private static final long POLL_INTERVAL = 3000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private boolean lastRunning = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (executor == null || executor.isShutdown()) return;
            executor.execute(() -> {
                boolean running = CloudflaredRunner.isRunning(MonitorService.this);
                if (running != lastRunning) {
                    lastRunning = running;
                    startForeground(1, NotificationHelper.buildForegroundNotification(MonitorService.this, running));
                    Intent broadcast = new Intent(App.ACTION_STATUS_CHANGED);
                    sendBroadcast(broadcast);
                }
                handler.postDelayed(this, POLL_INTERVAL);
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        startForeground(1, NotificationHelper.buildForegroundNotification(this, false));
        handler.postDelayed(pollRunnable, POLL_INTERVAL);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(pollRunnable);
        if (executor != null) executor.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
