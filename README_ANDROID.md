# Android implementation notes

Local notifications are received by the LocalNotificationReceiver:

1. If Defold is running it will try to send it directly to callback or save on disk if callback isn't registered.
2. If Defold is not running it will create a notification and set it to open the PushDispatchActivity (see below).

Remote notifications are received using the FirebaseMessagingService. When a message is received it will call Push.showNotification():

1. If Defold is running it will try to send it directly to callback or save on disk if callback isn't registered.
2. If Defold is not running it will create a notification and set it to open the PushDispatchActivity (see below).

The PushDispatchActivity acts as an intermediate activity for reading notification info and forwarding this to the application. The activity will depending on application state do either of the following:

1. If the application is running and has a push listener it will notify the listener of the notification
2. If the application doesn't have a push listener it will save the notification info to disk and then launch the main activity

Saved notifications are triggered when push.set_listener() is called. Notifications are also triggered if a listener is set and push.register() is called.
