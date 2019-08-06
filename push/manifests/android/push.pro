-keep public class com.google.firebase.**
-keep public class com.google.firebase.FirebaseApp
-keep public class com.google.firebase.FirebaseOptions
-keep public class com.google.firebase.iid.FirebaseInstanceId {*;}
-keep public class com.google.firebase.iid.InstanceIdResult
-keep public class com.google.firebase.messaging.RemoteMessage
-keep public class com.google.android.gms.gcm.GoogleCloudMessaging

-dontwarn com.google.firebase.messaging.R
-dontwarn com.google.firebase.messaging.R$*

#Defold

-keep class com.defold.push.** {
    public <methods>;
}