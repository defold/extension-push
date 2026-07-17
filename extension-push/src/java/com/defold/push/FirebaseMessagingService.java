package com.defold.push;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.google.firebase.messaging.RemoteMessage;

public class FirebaseMessagingService extends com.google.firebase.messaging.FirebaseMessagingService {
    private String TAG = "push-firebase";
    public FirebaseMessagingService() {
        super();
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.d(TAG, "From: " + remoteMessage.getFrom());

        // Check if message contains a data payload.
        if (remoteMessage.getData().size() > 0) {
            Log.d(TAG, "Message data payload: " + remoteMessage.getData());
            Push.getInstance().showNotification(this, remoteMessage.getData());
        }
    }

    /** Called after FCM registers or refreshes this Firebase installation. */
    @Override
    public void onRegistered(String installationId) {
        super.onRegistered(installationId);
        Log.d(TAG, "Registered Firebase installation ID: " + installationId);
        Push.getInstance().sendRegistrationId(installationId);
    }
}
