package com.cloudflared.tunnel;

import android.app.Application;
import android.app.NotificationChannel;

public class App extends Application {

    public static final String CHANNEL_ID = "cloudflared_channel";
    public static final String ACTION_STATUS_CHANGED = "com.cloudflared.tunnel.STATUS_CHANGED";

    @Override
    public void onCreate() {
        super.onCreate();
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Cloudflared Tunnel", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Tunnel running status");
            nm.createNotificationChannel(channel);
        }
    }
}
