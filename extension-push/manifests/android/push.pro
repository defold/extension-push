-keep public class com.google.firebase.**
-keep public class com.google.firebase.FirebaseApp
-keep public class com.google.firebase.FirebaseOptions
-keep public class com.google.firebase.messaging.FirebaseMessaging {*;}
-keep public class com.google.firebase.messaging.RemoteMessage

-dontwarn com.google.firebase.messaging.R
-dontwarn com.google.firebase.messaging.R$*

#Defold

-keep class com.defold.push.** {
    public <methods>;
}
