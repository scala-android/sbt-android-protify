# protify: instantaneous, on-device development for Android

[![Join the chat at https://gitter.im/pfn/protify](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/pfn/protify?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Current version is 1.1.1

## Features:

* Multiple language support: Java, Scala, Kotlin
* No code changes to app required!
* appcompat-v7, design and support-v4 libraries
* Most existing android projects
* Android devices v4+
* Dex sharding for near-instant deployment

## Demos:

* [Live coding](https://www.youtube.com/watch?v=4MaGxkqopII)

## Getting started:

### Live coding

1. Install sbt from http://scala-sbt.org, homebrew, ports, or your
   package manager of choice
2. Install [android-sdk-plugin](https://github.com/pfn/android-sdk-plugin):
   `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.5.0")' > ~/.sbt/0.13/plugins/android.sbt`
3. Start from an existing or new project (for trivial projects):
   `sbt "gen-android ..."` to create a new project, `sbt gen-android-sbt` to
   generate sbt files in an existing project. Non-trivial projects will need
   more advanced sbt configuration.
   * Alternatively, use `android-gradle-build` when working with an existing gradle project:
     * `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-gradle-build" % "0.9.3")' > project/plugins.sbt`
     * `echo 'object Build extends android.GradleBuild' > project/build.scala`
4. Add the protify plugin:
   `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-protify" % "1.1.1")' >> project/plugins.sbt`
5. Add `protifySettings`: `echo protifySettings >> build.sbt`
6. Run SBT
7. Select device to run on by using the `devices` and `device` commands. Run
   on all devices by executing `set allDevices in Android := true`
8. `android:run`, and `~protify`
   * Alternatively, high speed turnaround can be achieved with `protify:install`
     and `protify:run` to pretend the app is getting updated, rather than
     using the live-code mechanism.
9. Enjoy

LIMITATIONS:
  * Deleting a resource will require running `protify:clean` or else the build
    will break
  * Singleton state will not be restored upon deploying new dex code.
    (or resources when on device api level <14)

### Android Studio / Gradle integration

1. sync project in Android Studio
2. Optionally add `idea-sbt-plugin` to run SBT inside of Android Studio

### IntelliJ integration

1. It works automatically if you're already using `android-sdk-plugin` and SBT

### Vim, etc.

1. Just do any of the above getting started steps and follow your own workflow.
