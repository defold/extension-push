package com.defold.push;

import java.lang.Boolean;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Application;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.OnCompleteListener;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;
import com.google.firebase.messaging.RemoteMessage;

public class Push {

    public static final String TAG = "push";
    public static final String DEFOLD_ACTIVITY = "com.dynamo.android.DefoldActivity";
    public static final String ACTION_FORWARD_PUSH = "com.defold.push.FORWARD";
    public static final String SAVED_PUSH_MESSAGE_NAME = "saved_push_message";
    public static final String SAVED_LOCAL_MESSAGE_NAME = "saved_local_message";
    public static final String NOTIFICATION_CHANNEL_ID = "com.dynamo.android.notification_channel";
    public static final String DEFOLD_NOTIFICATION = ".defold_notification";
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;

    private String senderIdFCM = "";
    private String applicationIdFCM = "";

    private static Push instance;
    private static AlarmManager am = null;
    private IPushListener listener = null;

    private Activity activity;

    // We need to store recieved notifications in memory until
    // a listener has been registered on the Lua side.
    private class StoredNotification {
        public String json = "";
        public int id = 0;
        public boolean wasLocal = false;
        public boolean wasActivated = false;

        public StoredNotification(String json, int id, boolean wasLocal, boolean wasActivated)
        {
            this.json = json;
            this.id = id;
            this.wasLocal = wasLocal;
            this.wasActivated = wasActivated;
        }
    }

    private static ActivityListener defoldActivityListenerInstance;
    private static boolean defoldActivityVisible;
    public static boolean isDefoldActivityVisible() {
        Log.d(TAG, "Tracking Activity isVisible= " + defoldActivityVisible);
        return defoldActivityVisible;
    }

    final private class ActivityListener implements Application.ActivityLifecycleCallbacks {
        public final void onActivityResumed(Activity activity) {
            if (activity.getLocalClassName().equals(DEFOLD_ACTIVITY)) {
                defoldActivityVisible = true;
                Log.d(TAG, "Tracking Activity Resumed "+activity.getLocalClassName());
            }
        }

        public final void onActivityPaused(Activity activity) {
            if (activity.getLocalClassName().equals(DEFOLD_ACTIVITY)) {
                defoldActivityVisible = false;
                Log.d(TAG, "Tracking Activity Paused "+activity.getLocalClassName());
            }
        }

        public final void onActivityDestroyed(Activity activity) {
        }

        public final void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
        }

        public final void onActivityStopped(Activity activity) {
        }

        public final void onActivityStarted(Activity activity) {
        }

        public final void onActivityCreated(Activity activity, Bundle bundle) {
        }
    }

    public void setApplicationListener(Activity activity) {
        defoldActivityVisible = true;
        activity.getApplication().registerActivityLifecycleCallbacks(new ActivityListener());
    }

    private ArrayList<StoredNotification> storedNotifications = new ArrayList<StoredNotification>();

    public void start(Activity activity, IPushListener listener, String senderId, String applicationId, String projectTitle) {
        Log.d(TAG, String.format("Push started (%s %s)", listener, senderId));

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, projectTitle, NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableVibration(true);
            channel.setDescription("");

            NotificationManager notificationManager = (NotificationManager)activity.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
        this.activity = activity;
        this.listener = listener;
        this.senderIdFCM = senderId;
        this.applicationIdFCM = applicationId;
    }

    public void stop() {
        Log.d(TAG, "Push stopped");
        this.listener = null;
    }

    public void flushStoredNotifications() {
        if (this.listener == null) {
            return;
        }

        for (int i = 0; i < storedNotifications.size(); i++) {
            StoredNotification n = storedNotifications.get(i);
            if (n.wasLocal) {
                this.listener.onLocalMessage(n.json, n.id, n.wasActivated);
            } else {
                this.listener.onMessage(n.json, n.wasActivated);
            }
        }

        storedNotifications.clear();
    }

    public void register(final Activity activity) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    startFirebase(activity);
                    loadSavedLocalMessages(activity);
                    loadSavedMessages(activity);
                    // Ensure that stored notifications are sent to the listener
                    // even if the listener was set before calling register.
                    // This can obviously happen if set_listener() is called
                    // before register() but also in the case when the application
                    // was started from a notification.
                    flushStoredNotifications();
                } catch (Throwable e) {
                    Log.e(TAG, "Failed to register", e);
                    sendRegistrationResult(null, e.getLocalizedMessage());
                }
            }
        });
    }

    private int createPendingIntentFlags(int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // https://github.com/defold/extension-push/issues/46
            flags = flags | PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private String createLocalPushNotificationPath(int uid) {
        return String.format("%s_%d", Push.SAVED_LOCAL_MESSAGE_NAME, uid);
    }

    private JSONObject readJson(Context context, String path) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(context.openFileInput(path)))) {
            String json = "";
            String line = r.readLine();
            while (line != null) {
                json += line;
                line = r.readLine();
            }
            return new JSONObject(json);
        } catch (FileNotFoundException e) {
            Log.e(TAG, String.format("No such file '%s'", path), e);
            return null;
        } catch (IOException e) {
            Log.e(TAG, String.format("Failed to read from file '%s'", path), e);
            return null;
        } catch (JSONException e) {
            Log.e(TAG, String.format("Failed to create json object from file '%s'", path), e);
            return null;
        }
    }

    private void storeLocalPushNotification(Context context, int uid, Bundle extras) {
        String path = createLocalPushNotificationPath(uid);
        try (PrintStream os = new PrintStream(context.openFileOutput(path, Context.MODE_PRIVATE))) {
            String json = getJson(extras);
            os.println(json);
            Log.d(TAG, String.format("Stored local notification file: %s", path));
        } catch (IOException e) {
            Log.e(TAG, "Failed to store notification", e);
        }
    }

    private void putValues(Bundle extras, int uid, String title, String message, String payload, long timestamp, int priority, int iconSmall, int iconLarge) {
        extras.putInt("uid", uid);
        extras.putString("title", title);
        extras.putString("message", message);
        extras.putInt("priority", priority);
        extras.putLong("timestamp", timestamp);
        extras.putInt("smallIcon", iconSmall);
        extras.putInt("largeIcon", iconLarge);
        extras.putString("payload", payload);
    }

    private Bundle loadLocalPushNotification(Context context, int uid) {
        JSONObject jo = readJson(context, createLocalPushNotificationPath(uid));
        if (jo == null) {
            Log.e(TAG, String.format("Failed to load local notification: %d", uid));
            return null;
        }
        Bundle extras = new Bundle();
        putValues(extras, jo.optInt("uid"), jo.optString("title"), jo.optString("message"), jo.optString("payload"), jo.optLong("timestamp"),
                    jo.optInt("priority"), jo.optInt("smallIcon"), jo.optInt("largeIcon"));
        return extras;
    }

    private void deleteLocalPushNotification(Context context, int uid) {
        context.deleteFile(createLocalPushNotificationPath(uid));
    }

    private Notification getLocalNotification(final Context appContext, Bundle extras, int uid) {
        Intent new_intent = new Intent(appContext, PushDispatchActivity.class).setAction(Push.ACTION_FORWARD_PUSH);
        new_intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        new_intent.putExtras(extras);
        final int flags = createPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        PendingIntent contentIntent = PendingIntent.getActivity(appContext, uid, new_intent, flags);

        ApplicationInfo info = appContext.getApplicationInfo();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, Push.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(extras.getString("title"))
            .setContentText(extras.getString("message"))
            .setContentIntent(contentIntent)
            .setPriority(extras.getInt("priority"))
            .setWhen(extras.getLong("timestamp"));

        builder.getExtras().putInt("uid", uid);

        // Find icons, if they were supplied
        int smallIconId = extras.getInt("smallIcon");
        int largeIconId = extras.getInt("largeIcon");
        if (smallIconId == 0) {
            smallIconId = info.icon;
            if (smallIconId == 0) {
                smallIconId = android.R.color.transparent;
            }
        }
        if (largeIconId == 0) {
            largeIconId = info.icon;
            if (largeIconId == 0) {
                largeIconId = android.R.color.transparent;
            }
        }
        builder.setSmallIcon(smallIconId);

        try {
            // Get bitmap for large icon resource
            PackageManager pm = appContext.getPackageManager();
            Resources resources = pm.getResourcesForApplication(info);
            Bitmap largeIconBitmap = BitmapFactory.decodeResource(resources, largeIconId);

            builder.setLargeIcon(largeIconBitmap);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("LocalNotificationReceiver", "PackageManager.NameNotFoundException!");
        }

        Notification notification = builder.build();
        notification.defaults = Notification.DEFAULT_ALL;
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
  
        return notification;
    }

    public void loadPendingNotifications(final Activity activity) {
        String[] files = activity.fileList();
        String prefix = String.format("%s_", Push.SAVED_LOCAL_MESSAGE_NAME);
        for (String path : files) {
            if (!path.startsWith(prefix)) {
                continue;
            }

            // These notifications are already registered with the AlarmManager, we just need to store them internally again
            JSONObject jo = readJson(activity, path);
            if (jo == null) {
                Log.e(TAG, String.format("Failed to load local pending notification: %s", path));
                return;
            }
            this.listener.addPendingNotifications(jo.optInt("uid"), jo.optString("title"), jo.optString("message"), jo.optString("payload"),
                                                jo.optLong("timestamp"), jo.optInt("priority"));
        }
    }

    public void scheduleNotification(final Activity activity, int uid, long timestampMillis, String title, String message, String payload, int priority) {

        if (am == null) {
            am = (AlarmManager) activity.getSystemService(activity.ALARM_SERVICE);
        }

        Context appContext = activity.getApplicationContext();
        Intent intent = new Intent(appContext, LocalNotificationReceiver.class);

        Bundle extras = new Bundle();
        String packageName = activity.getPackageName();
        int iconSmall = activity.getResources().getIdentifier("push_icon_small", "drawable", packageName);
        int iconLarge = activity.getResources().getIdentifier("push_icon_large", "drawable", packageName);
        putValues(extras, uid, title, message, payload, timestampMillis, priority, iconSmall, iconLarge);

        storeLocalPushNotification(appContext, uid, extras);

        intent.putExtras(extras);
        intent.setAction("uid" + uid);
        intent.putExtra(packageName + DEFOLD_NOTIFICATION, getLocalNotification(appContext, extras, uid));

        final int flags = createPendingIntentFlags(PendingIntent.FLAG_ONE_SHOT);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags);
        try {
            // from S the use of exact alarms requires additional permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestampMillis, pendingIntent);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, timestampMillis, pendingIntent);
            }
        }
        catch(java.lang.SecurityException e) {
            Log.e(TAG, "Failed to schedule notification", e);
        }
    }

    public void cancelNotification(final Activity activity, int notificationId, String title, String message, String payload, int priority)
    {
        if (am == null) {
            am = (AlarmManager) activity.getSystemService(activity.ALARM_SERVICE);
        }

        if (notificationId < 0) {
            return;
        }

        removeNotification(notificationId);

        Intent intent = new Intent(activity, LocalNotificationReceiver.class);
        intent.setAction("uid" + notificationId);
        final int flags = createPendingIntentFlags(PendingIntent.FLAG_ONE_SHOT);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(activity, 0, intent, flags);
        am.cancel(pendingIntent);
    }

    public void cancelAllIssued(final Activity activity)
    {
        NotificationManager notificationManager = (NotificationManager)activity.getSystemService(NotificationManager.class);
        notificationManager.cancelAll();
    }

    public boolean hasListener() {
        if (this.listener != null) {
            return true;
        }

        return false;
    }

    public static Push getInstance() {
        if (instance == null) {
            instance = new Push();
        }
        return instance;
    }

    private void registerFirebase(Activity activity) {
        if (this.applicationIdFCM == null || this.applicationIdFCM == "") {
            Log.w(Push.TAG, "Fcm Application Id must be set.");
            return;
        }
        if (this.senderIdFCM == null || this.senderIdFCM == "") {
            Log.w(Push.TAG, "Gcm Sender Id must be set.");
            return;
        }

        FirebaseInstanceId.getInstance().getInstanceId()
                .addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
                    @Override
                    public void onComplete(Task<InstanceIdResult> task) {
                        if (!task.isSuccessful()) {
                            Log.w(TAG, "getInstanceId failed", task.getException());
                            sendRegistrationResult(null, task.getException().getLocalizedMessage());
                            return;
                        }

                        // Get new Instance ID token
                        String token = task.getResult().getToken();
                        sendToken(token);
                    }
                });
    }

    public void sendToken(String token) {
        sendRegistrationResult(token, null);
    }

    private boolean checkPlayServices(Activity activity) {
        int resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity);
        if (resultCode != ConnectionResult.SUCCESS) {
            Log.i(TAG, "This device is not supported. Remote push notifications are not supported");
            return false;
        }
        return true;
    }

    private void startFirebase(Activity activity) {
        if (checkPlayServices(activity)) {
            registerFirebase(activity);
        } else {
            Log.w(TAG, "No valid Google Play Services APK found.");
            sendRegistrationResult(null, "Google Play Services not available.");
        }
    }

    private void sendRegistrationResult(String regid, String errorMessage) {
        if (listener != null) {
            listener.onRegistration(regid, errorMessage);
        } else {
            Log.e(TAG, "No listener callback set");
        }
    }

    private void loadSavedMessages(Context context) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(context.openFileInput(SAVED_PUSH_MESSAGE_NAME)))) {
            boolean wasActivated = Boolean.parseBoolean(r.readLine());
            String json = "";
            String line = r.readLine();
            while (line != null) {
                json += line;
                line = r.readLine();
            }
            storedNotifications.add(new StoredNotification(json, 0, false, wasActivated));

        } catch (FileNotFoundException e) {
        } catch (IOException e) {
            Log.e(Push.TAG, "Failed to read push message from disk", e);
        } finally {
            context.deleteFile(SAVED_PUSH_MESSAGE_NAME);
        }
    }

    private void loadSavedLocalMessages(Context context) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(context.openFileInput(SAVED_LOCAL_MESSAGE_NAME)))) {
            int id = Integer.parseInt(r.readLine());
            boolean wasActivated = Boolean.parseBoolean(r.readLine());
            String json = "";
            String line = r.readLine();
            while (line != null) {
                json += line;
                line = r.readLine();
            }
            storedNotifications.add(new StoredNotification(json, id, true, wasActivated));
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
            Log.e(Push.TAG, "Failed to read local message from disk", e);
        } finally {
            context.deleteFile(SAVED_LOCAL_MESSAGE_NAME);
        }
    }

    static JSONObject toJson(Bundle bundle) {
        JSONObject o = new JSONObject();
        for (String k : bundle.keySet()) {
            try {
                o.put(k, bundle.getString(k));
            } catch (JSONException e) {
                Log.e(TAG, "failed to create json-object", e);
            }
        }
        return o;
    }

    static JSONObject toJson(Map<String, String> bundle) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> entry : bundle.entrySet()) {
            try {
                o.put(entry.getKey(), entry.getValue());
            } catch (JSONException e) {
                Log.e(TAG, "failed to create json-object", e);
            }
        }
        return o;
    }

    // https://stackoverflow.com/a/37728241/468516
    private String getJson(final Bundle bundle) {
        if (bundle == null) return null;
        JSONObject jsonObject = new JSONObject();

        for (String key : bundle.keySet()) {
            Object obj = bundle.get(key);
            try {
                jsonObject.put(key, jsonWrapValue(bundle.get(key)));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return jsonObject.toString();
    }

    public static Object jsonWrapValue(Object o) {
        if (o == null) {
            return JSONObject.NULL;
        }
        if (o instanceof JSONArray || o instanceof JSONObject) {
            return o;
        }
        if (o.equals(JSONObject.NULL)) {
            return o;
        }
        try {
            if (o instanceof Collection) {
                return new JSONArray((Collection) o);
            } else if (o.getClass().isArray()) {
                return toJSONArray(o);
            }
            if (o instanceof Map) {
                return new JSONObject((Map) o);
            }
            if (o instanceof Boolean ||
                    o instanceof Byte ||
                    o instanceof Character ||
                    o instanceof Double ||
                    o instanceof Float ||
                    o instanceof Integer ||
                    o instanceof Long ||
                    o instanceof Short ||
                    o instanceof String) {
                return o;
            }
            if (o.getClass().getPackage().getName().startsWith("java.")) {
                return o.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static JSONArray toJSONArray(Object array) throws JSONException {
        JSONArray result = new JSONArray();
        if (!array.getClass().isArray()) {
            throw new JSONException("Not a primitive array: " + array.getClass());
        }
        final int length = Array.getLength(array);
        for (int i = 0; i < length; ++i) {
            result.put(jsonWrapValue(Array.get(array, i)));
        }
        return result;
    }

    void onRemotePush(Context context, String payload, boolean wasActivated) {
        if (listener != null) {
            listener.onMessage(payload, wasActivated);
        }
        else {
            PrintStream os = null;
            try {
                os = new PrintStream(context.openFileOutput(Push.SAVED_PUSH_MESSAGE_NAME, Context.MODE_PRIVATE));
                os.println(wasActivated);
                os.println(payload);
            } catch (Throwable e) {
                Log.e(Push.TAG, "Failed to write push message to disk", e);
            } finally {
                if (os != null) {
                    os.close();
                }
            }
        }
    }

    private void removeNotification(int id) {
        String path = createLocalPushNotificationPath(id);
        boolean deleted = activity.deleteFile(path);
        Log.d(TAG, String.format("Removed local notification file: %s  (%s)", path, Boolean.toString(deleted)));
    }

    void onLocalPush(Context context, String msg, int id, boolean wasActivated) {
        if (listener != null) {
            removeNotification(id);
            listener.onLocalMessage(msg, id, wasActivated);
        }
        else {
            PrintStream os = null;
            try {
                os = new PrintStream(context.openFileOutput(Push.SAVED_LOCAL_MESSAGE_NAME, Context.MODE_PRIVATE));
                os.println(id);
                os.println(wasActivated);
                os.println(msg);
            } catch (Throwable e) {
                Log.e(Push.TAG, "Failed to write push message to disk", e);
            } finally {
                if (os != null) {
                    os.close();
                }
            }
        }
    }

    public void showNotification(Context context, Map<String, String> extras) {
        JSONObject payloadJson = toJson(extras);
        String payloadString = payloadJson.toString();

        // If activity is visible we can just send data to the listener without intent
        if (isDefoldActivityVisible()) {
            onRemotePush(context, payloadString, false);
            return;
        }

        Intent intent = new Intent(context, PushDispatchActivity.class).setAction(ACTION_FORWARD_PUSH);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        intent.putExtra("payload", payloadString);

        Bundle extrasBundle = intent.getExtras();
        extrasBundle.putByte("remote", (byte)1);

        int id = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        final int flags = createPendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, intent, flags);

        String fieldTitle = null;
        String fieldText = null;

        // Try to find field names from manifest file
        PackageManager pm = context.getPackageManager();
        try {
            ComponentName cn = new ComponentName(context, DEFOLD_ACTIVITY);
            ActivityInfo activityInfo = pm.getActivityInfo(cn, PackageManager.GET_META_DATA);

            Bundle bundle = activityInfo.metaData;
            if (bundle != null) {
                fieldTitle = bundle.getString("com.defold.push.field_title", null);
                fieldText = bundle.getString("com.defold.push.field_text", "alert");
            } else {
                Log.w(TAG, "Bundle was null, could not get meta data from manifest.");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not get activity info, needed to get push field conversion.");
        }

        ApplicationInfo info = context.getApplicationInfo();
        String title = info.loadLabel(pm).toString();
        if (fieldTitle != null && extras.get(fieldTitle) != null) {
            title = extras.get(fieldTitle);
        }

        String text = extras.get(fieldText);
        if (text == null) {
            Log.w(TAG, "Missing text field in push message");
            text = "New message";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentText(text);

        // Find icons if they were supplied, fallback to app icon
        int smallIconId = context.getResources().getIdentifier("push_icon_small", "drawable", context.getPackageName());
        int largeIconId = context.getResources().getIdentifier("push_icon_large", "drawable", context.getPackageName());
        if (smallIconId == 0) {
            smallIconId = info.icon;
            if (smallIconId == 0) {
                smallIconId = android.R.color.transparent;
            }
        }
        if (largeIconId == 0) {
            largeIconId = info.icon;
            if (largeIconId == 0) {
                largeIconId = android.R.color.transparent;
            }
        }

        // Get bitmap for large icon resource
        try {
            Resources resources = pm.getResourcesForApplication(info);
            Bitmap largeIconBitmap = BitmapFactory.decodeResource(resources, largeIconId);
            builder.setLargeIcon(largeIconBitmap);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not get application resources.");
        }

        builder.setSmallIcon(smallIconId);
        builder.setContentIntent(contentIntent);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = builder.build();
        notification.defaults = Notification.DEFAULT_ALL;
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        nm.notify(id, notification);
    }

}
