package com.defold.push;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.app.NotificationManager;
import android.app.Notification;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import android.content.pm.ApplicationInfo;
import androidx.core.graphics.drawable.IconCompat;
import android.os.Build;

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
            String packageName = context.getPackageName();
            Notification notification = intent.getParcelableExtra(packageName + Push.DEFOLD_NOTIFICATION);
            if (notification != null) {
                NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, notification);
                int smallIconId = context.getResources().getIdentifier("push_icon_small", "drawable", packageName);
                if (smallIconId == 0) {
                    ApplicationInfo info = context.getApplicationInfo();
                    smallIconId = info.icon;
                    if (smallIconId == 0) {
                        smallIconId = android.R.color.transparent;
                    }
                }
                notificationBuilder.setSmallIcon(smallIconId);
                if (Build.VERSION.SDK_INT >= 23) {
                    notificationBuilder.setSmallIcon(IconCompat.createWithResource(context, smallIconId));
                }
                nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                Notification newNotification = notificationBuilder.build();
                newNotification.defaults = Notification.DEFAULT_ALL;
                newNotification.flags |= Notification.FLAG_AUTO_CANCEL;
                nm.notify(id, newNotification);    
            }
        }
    }

}
