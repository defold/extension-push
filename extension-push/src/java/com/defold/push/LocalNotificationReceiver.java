package com.defold.push;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Notification;
import android.os.Bundle;

public class LocalNotificationReceiver extends BroadcastReceiver {

    NotificationManager nm;

    public LocalNotificationReceiver() {
        super();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = intent.getExtras();
        int id = extras.getInt("uid");

        if (Push.isDefoldActivityVisible()) {
            // If activity is visible we can just send data to the listener without intent
            Push.getInstance().onLocalPush(context, extras.getString("payload"), id, false);
        } else {
            Notification notification = intent.getParcelableExtra(context.getPackageName() + Push.DEFOLD_NOTIFICATION);
            nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(id, notification);
        }

    }

}
