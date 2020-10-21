---
title: Defold Push notification documentation
brief: This manual covers how to setup and use push notifications in Defold.
---

# Defold Push notification documentation

Push notifications are available on iOS and Android (Google using Firebase Cloud Messaging) devices as a [native extension](/manuals/extensions/) and allow your game to inform the player about changes and updates. The core functionality is similar between iOS and Android but there are some platform specific differences that you need to consider.

For a push notification to find its way from the server to the target device, certain bits of information are required for your app. The most complex part consists of security information that you set in the application so the notification system can verify the legitimacy of the client receiving notifications. But you will also need a piece of security information for your notification server so the Apple or Google servers can verify that your server is a legitimate notification sender. Finally, when you send notifications, you need to be able to uniquely direct notifications to a specific user's device. For that you retrieve and use a token that is unique to the particular device (i.e. user).


## Installation
You can use the extension in your own project by adding this project as a [Defold library dependency](/manuals/libraries/). Open your `game.project` file and in the dependencies field under project add:

https://github.com/defold/extension-push/archive/master.zip

Or point to the ZIP file of a [specific release](https://github.com/defold/extension-push/releases) (recommended!).


## iOS setup
::: sidenote
To get acquainted with the Apple Push Notification Service, a good idea is to start by reading [Apple's own documentation on how the service works](https://developer.apple.com/library/ios/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/Chapters/ApplePushService.html).
:::

On iOS, you need the following information to send notifications:

* Push Notifications must be enabled in the App ID.
* A provisioning profile containing this valid App ID is also required.
* You also need an Apple Push Notification Service SSL Certificate to be allowed to send notification data to the Apple Push Notification server from your messaging server application.

To get everything in place, head over to the [Apple Developer Member center](https://developer.apple.com/membercenter). Edit your AppID to enable Push Notifications.

![AppID push notifications](push_ios_app_id.png)

You also need to create an Apple Push Notification service SSL certificate:

![APN SSL certificate](push_ios_certificate.png)

The certificate will be needed on your server that will send out push notifications. While developing, you can download and install the certificate on your machine and run a push test app such as [APNS-Pusher](https://github.com/KnuffApp/APNS-Pusher) or [NWPusher](https://github.com/noodlewerk/NWPusher).

Make sure that you create a new provisioning profile from the AppID, and that you get it onto your device. You can do that manually from the "Member Center" page or through Xcode.

![Provisioning profile](push_ios_provisioning_profile.png)

Note that it can take a while for Apple's sandbox servers to update so you might not get push to work immediately. Be patient.

Now it's time to run some test code:

<a name="above-code"></a>
```lua
local function push_listener(self, payload, origin)
    -- The payload arrives here.
    pprint(payload)
end

function init(self)
    local sysinfo = sys.get_sys_info()
    if sysinfo.system_name == "Android" then
        msg.post("#", "push_android")
    elseif sysinfo.system_name == "iPhone OS" then
        msg.post("#", "push_ios")
    end
end

function on_message(self, message_id, message)
    if message_id == hash("push_ios") then
        local alerts = {push.NOTIFICATION_BADGE, push.NOTIFICATION_SOUND, push.NOTIFICATION_ALERT}
        push.register(alerts, function (self, token, error)
            if token then
                local t = ""
                for i = 1,#token do
                    t = t .. string.format("%02x", string.byte(token, i))
                end
                -- Print the device token
                print(t)
            else
                -- Error
                print(error.error)
            end
        end)
        push.set_listener(push_listener)
    elseif message_id == hash("push_android") then
        push.register(nil, function (self, token, error)
            if token then
                -- Print the device token
                print(token)
            else
                -- Error
                print(error.error)
            end
        end)
        push.set_listener(push_listener)
    end
end
```

If all goes well the notification listener will be registered and we get a token that we can use:

```txt
DEBUG:SCRIPT: 1f8ba7869b84b10df69a07aa623cd7f55f62bca22cef61b51fedac643ec61ad8
```

If you're running a push test app, you can now try to send notifications to your device using the device token and the APN service SSL certificate.

![Pusher test](push_ios_pusher.png)

The notification should arrive at the client soon after you send it, from within your test application, arriving to the function `push_listener()`:

```txt
DEBUG:SCRIPT:
{
  aps = {
    badge = 42,
    alert = Testing.. (1),
    sound = default,
  }
}
```

And from the iOS homescreen:

![iOS notification](push_ios_notification.png)

If you wish to update the badge count from within the application, use the `push.set_badge_count()` function.


## Android setup
Push notification on Android uses Firebase Cloud Messaging. You need to configure an application in the Firebase Console. The steps below taken from the [official Google Firebase Guides](https://firebase.google.com/docs/android/setup#create-firebase-project).

::: sidenote
Firebase has extensive documentation for Firebase Cloud Messaging. We encourage you to start by reading it on https://firebase.google.com/docs/cloud-messaging/
:::

### Create a Firebase project
* Create a Firebase project in the [Firebase console](https://console.firebase.google.com/), if you don't already have one. Click Add project. If you already have an existing Google project associated with your mobile app, select it from the Project name drop down menu. Otherwise, enter a project name to create a new project.

* Optional: Edit your Project ID. Your project is given a unique ID automatically, and it's used in publicly visible Firebase features such as database URLs and your Firebase Hosting subdomain. You can change it now if you want to use a specific subdomain.

* Follow the remaining setup steps and click Create project (or Add Firebase if you're using an existing project) to begin provisioning resources for your project. This typically takes a few minutes. When the process completes, you'll be taken to the project overview.


### Register your app with Firebase
* In the center of the project overview page, click the Android icon to launch the setup workflow.

* Enter your app's package name in the Android package name field.

* Click Register app

![Google Cloud Messaging sender ID](push_fcm_register.png)


### Add a Firebase configuration file
* Click Download google-services.json to obtain your Firebase Android config file (`google-services.json`).

![Google Cloud Messaging sender ID](push_fcm_download_json.png)


* Run `generate_xml_from_google_services_json.py` or `generate_xml_from_google_services_json.exe` (both from [Firebase C++ SDK](https://github.com/firebase/firebase-cpp-sdk)) to convert the previously downloaded `google-services.json` to an Android resource XML:

```
$ ./generate_xml_from_google_services_json.py -i google-services.json -o google-services.xml
```

* Copy the generated `google-services.xml` file to a folder structure like this:

```
<project_root>
 |
 +-bundle
    |
    +-android
       |
       +-res
          |
          +-values
             |
             +-google-services.xml
```

* Open `game.project` and set the `Bundle Resources` entry under the `Project` section to `/bundle` to match the folder created in the step above. Read more about the `Bundle Resources` setting in the [Defold manual](https://www.defold.com/manuals/project-settings/#_project).

Now everything is ready on the client. The [above code](#above-code) example works for Android as well. Run it and copy the device token id.

```txt
DEBUG:SCRIPT: APA91bHkcKm0QHAMUCEQ_Dlpq2gzset6vh0cz46kDDV6230C5rFivyWZMCxGXcjxRDKg1PK4z1kWg3xnUVqSDiO_4_RiG8b8HeYJfaoW1ho4ukWYXjq5RE0Sy-JTyrhqRusUP_BxRTcE
```

Before we can send any messages we need to get a key that will be used for authentication against the Firebase servers. You will find the key under *Settings* and *Cloud Messaging* on the Firebase dashboard.

![Server Key location](push_fcm_server_key.png)

Now we have all information we need. Firebase's notifications can be sent through a Web API so we can use *curl* to send test messages:

```sh
$ curl  -X POST  -H "Content-type: application/json"  -H 'Authorization: key=SERVER_KEY' -d '{"registration_ids" : ["TOKEN_ID"], "data": {"alert": "Hello"}}' https://fcm.googleapis.com/fcm/send
```

Replace `SERVER_KEY` and `TOKEN_ID` with your specific keys.


## Local push notifications
Local push notifications are supported as well as remote ones. After the regular setup you can schedule a local notification:

```lua
-- Schedule a local push in 3 seconds
local payload = '{"data" : {"field" : "Some value", "field2" : "Other value"}}'
id, err = push.schedule(3, "A notification!", "Hello there", payload, { action = "get going" })
```

The id is uniquely identifying this scheduled notification and can be stored for later. The final parameter to `push.schedule()` takes a table with platform specific settings:

action
: (iOS only). The alert action string to be used as the title of the right button of the alert or the value of the unlock slider, where the value replaces "unlock" in "slide to unlock" text.

badge_count
: (iOS only). The numeric value of the icon badge. Set to 0 to clear the badge.

priority
: (Android only). The priority is a hint to the device UI about how the notification should be displayed. There are five priority levels:

  - push.PRIORITY_MIN
  - push.PRIORITY_LOW
  - push.PRIORITY_DEFAULT
  - push.PRIORITY_HIGH
  - push.PRIORITY_MAX

  Unless specified, the max priority level is used.


## Inspecting scheduled notifications
The API provides two functions to inspect what is currently scheduled.

```lua
n = push.get_scheduled(id)
pprint(n)
```

Which results in a table containing all details on the scheduled notification:

```txt
DEBUG:SCRIPT:
{
  payload = {"data":{"field":"Some value","field2":"Other value"}},
  title = A notification!,
  priority = 2,
  seconds = 19.991938,
  message = Hello there,
}
```

Note that `seconds` indicates the number of seconds left for the notification to fire. It is also possible to retreive a table with _all_ scheduled notifications:

```lua
all_n = push.get_all_scheduled()
pprint(all_n)
```

Which results in a table pairing notification id's with their respective data:

```txt
DEBUG:SCRIPT:
{
  0 = {
    payload = {"data":{"field":"Some value","field2":"Other value"}},
    title = A notification!,
    priority = 2,
    seconds = 6.009774,
    message = Hey hey,
  }
  1 = {
    payload = {"data":{"field":"Some value","field2":"Other value"}},
    title = Another notification!,
    priority = 2,
    seconds = 12.652521,
    message = Hello there,
  }
  2 = {
    payload = {"data":{"field":"Some value","field2":"Other value"}},
    title = Hey, much notification!,
    priority = 2,
    seconds = 15.553719,
    message = Please answer!,
  }
}
```


## Source code

The source code is available on [GitHub](https://github.com/defold/extension-push)


## API reference
