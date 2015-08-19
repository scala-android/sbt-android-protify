# protify: fast, on-device live-preview for layouts and code

[![Join the chat at https://gitter.im/pfn/protify](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/pfn/protify?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

## Supports:

* Java
* Scala
* appcompat-v7, design and support-v4 libraries
* Most existing android projects

## Demos:

* [Live layout preview](https://www.youtube.com/watch?v=sgT9RA4SONU)
* [Live code preview](https://youtu.be/g63I87UZ6bg?t=3m10s)

## Getting started:

### Protify App

1. Install sbt from http://scala-sbt.org, homebrew, ports, or your
   package manager of choice
2. Build the *Protify* *Android* application: `sbt mobile/android:package`
3. Install APK to all desired devices

### Live Preview

1. Install [android-sdk-plugin](https://github.com/pfn/android-sdk-plugin):
   `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.4.10")' > ~/.sbt/0.13/plugins/android.sbt`
2. Start from an existing or new project (for trivial projects):
   `sbt "gen-android ..."` to create a new project, `sbt gen-android-sbt` to
   generate sbt files in an existing project. Non-trivial projects will need
   more advanced sbt configuration.
3. Add the protify plugin:
   `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-protify" % "0.3")' > project/plugins.sbt`
4. Add `protifySettings`: `echo protifySettings >> build.sbt`
5. Run SBT
6. Select device to run on by using the `devices` and `device` commands. Run
   on all devices by executing `set allDevices in Android := true`
7. Start protifying: `protify-layout` and `protify-dex`. The former can take an
   optional layout name followed by a theme name (unqualified, no R.layout or
   R.style). Appcompat will automatically be loaded if an appcompat theme is
   detected (custom themes that derive from appcompat themes will also be
   detected). `protify-dex` takes an argument which is a fully qualified class
   name that extends from `com.hanhuy.android.protify.ActivityProxy`, an
   optional argument, `appcompat`, declares that the proxy should run within
   AppCompatActivity.

### Android Studio / Gradle integration

1. Gradle: add a build flavor for `protify`
2. add the dependency `compile 'com.hanhuy.android:protify:0.3'` to the flavor
3. add the source directory `src/protify/java` to the flavor
4. sync project in Android Studio
5. Optionally add `idea-sbt-plugin` to run SBT inside of Android Studio

### IntelliJ integration

1. It works automatically if you're already using `android-sdk-plugin` and SBT
2. Mark `src/protify/*` as source roots
3. Add ~/.ivy/cache/com.hanhuy.android/protify/jars/protify-0.3.jar to your
   module dependencies

### Vim, etc.

1. Just do any of the above steps and follow your own workflow.
