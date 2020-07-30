# Defold Push notification documentation

Push notifications are available on iOS and Android (Google using Firebase Cloud Messaging) devices as a [native extension](/manuals/extensions/) and allow your game to inform the player about changes and updates. The core functionality is similar between iOS and Android but there are some platform specific differences that you need to consider.

For a push notification to find its way from the server to the target device, certain bits of information are required for your app. The most complex part consists of security information that you set in the application so the notification system can verify the legitimacy of the client receiving notifications. But you will also need a piece of security information for your notification server so the Apple or Google servers can verify that your server is a legitimate notification sender. Finally, when you send notifications, you need to be able to uniquely direct notifications to a specific user's device. For that you retrieve and use a token that is unique to the particular device (i.e. user).


## Defold setup

### Add project dependencies
You can use the extension in your own project by adding this project as a [Defold library dependency](/manuals/libraries/). Open your `game.project` file and in the dependencies field under project add:

> https://github.com/defold/extension-push/archive/master.zip

Or point to the ZIP file of a [specific release](https://github.com/defold/extension-push/releases) (recommended!).


## Setup for Android
Push notification on Android uses Firebase Cloud Messaging. You need to configure an application in the Firebase Console. The steps below taken from the [official Google Firebase Guides](https://firebase.google.com/docs/android/setup#create-firebase-project).

### Create a Firebase project
* Create a Firebase project in the [Firebase console](https://console.firebase.google.com/), if you don't already have one. Click Add project. If you already have an existing Google project associated with your mobile app, select it from the Project name drop down menu. Otherwise, enter a project name to create a new project.

* Optional: Edit your Project ID. Your project is given a unique ID automatically, and it's used in publicly visible Firebase features such as database URLs and your Firebase Hosting subdomain. You can change it now if you want to use a specific subdomain.

* Follow the remaining setup steps and click Create project (or Add Firebase if you're using an existing project) to begin provisioning resources for your project. This typically takes a few minutes. When the process completes, you'll be taken to the project overview.


### Register your app with Firebase
* In the center of the project overview page, click the Android icon to launch the setup workflow.

* Enter your app's package name in the Android package name field.

* Click Register app


### Add a Firebase configuration file
* Click Download google-services.json to obtain your Firebase Android config file (`google-services.json`).

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


## Source code

The source code is available on [GitHub](https://github.com/defold/extension-push)


## API reference

{% include api_ref.md %}
