package com.defold.push;

import java.io.PrintStream;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class PushDispatchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            String payload = extras.getString("payload");
            boolean remoteOrigin = (extras.getByte("remote") == 1);

            if (remoteOrigin) {
                Push.getInstance().onRemotePush(this, payload, true);
            } else {
                int uid = extras.getInt("uid");
                Push.getInstance().onLocalPush(this, payload, uid, true);
            }
            // Start activity with intent
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        } else {
            Log.e(Push.TAG, "Unable to queue message. extras is null");
        }

        finish();
    }
}
