package com.cloudflared.tunnel.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.cloudflared.tunnel.utils.ConfigManager;
import com.cloudflared.tunnel.model.Config;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Log.i(TAG, "Boot completed, starting monitor service");
        Intent monitorIntent = new Intent(context, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(monitorIntent);
        } else {
            context.startService(monitorIntent);
        }

        ConfigManager cm = new ConfigManager(context);
        Config config = cm.getDefaultConfig();
        if (config != null) {
            Intent tunnelIntent = new Intent(context, TunnelService.class);
            tunnelIntent.setAction(TunnelService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(tunnelIntent);
            } else {
                context.startService(tunnelIntent);
            }
        }
    }
}
