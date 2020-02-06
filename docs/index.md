---
layout: default
---

# Defold Push API documentation

Functions for interacting with push notifications. Supported on iOS and Android.

# Usage
To use this library in your Defold project, add the following URL to your <code class="inline-code-block">game.project</code> dependencies:

    https://github.com/defold/extension-push/archive/master.zip

We recommend using a link to a zip file of a [specific release](https://github.com/defold/extension-push/releases).

# Dependencies

Defold 1.2.164 and below does not use Gradle. You need to add the following dependencies to your `game.project` file:

    https://github.com/defold/android-base-extensions/releases/download/1.0.0/firebase-core-16.0.8.zip
    https://github.com/defold/android-base-extensions/releases/download/1.0.0/firebase-messaging-17.3.4.zip
    https://github.com/defold/android-base-extensions/releases/download/1.0.0/gps-base-16.0.1.zip
    https://github.com/defold/android-base-extensions/releases/download/1.0.0/gps-measurement-16.4.0.zip
    https://github.com/defold/android-base-extensions/releases/download/1.0.0/support-v4-26.1.0.zip

Defold 1.2.165 and above uses Gradle to resolve dependencies. This means that there is no need to add any additional library projects to your `game.project` file.

## Source code

The source code is available on [GitHub](https://github.com/defold/extension-push)


# API reference

{% include api_ref.md %}
