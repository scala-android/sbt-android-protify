# protify: instantaneous, on-device development for Android

[![Join the chat at https://gitter.im/pfn/protify](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/pfn/protify?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Current version is 1.1.10

## Features:

* Multiple language support: Java, Scala, Kotlin
* No code changes to app required!
* Support for appcompat-v7, design and support-v4 libraries
* Most existing android projects
* Android devices api level 4+
* Dex sharding for near-instant deployment

## Demos:

* [Live coding](https://youtu.be/LJLLyua0bYA)

## Getting started:

### Android Studio / Gradle Quick Start

1. Install sbt from http://scala-sbt.org, homebrew, ports, or your
   package manager of choice
2. Optionally add `idea-sbt-plugin` to run SBT inside of Android Studio
3. Install the `android-gradle-build` SBT plugin to automatically load your
   gradle build. From the base of your Android project, do:
   * `mkdir project`
   * `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-gradle-build" % "1.1.3")' > project/plugins.sbt`
   * `echo >> project/plugins.sbt`
   * `echo 'object Build extends android.GradleBuild' > project/gradle.scala`
   * If you have any flavors or build types that must be loaded:
     * `echo 'android.Plugin.withVariant("PROJECT-NAME (e.g. app)", Some("BUILD-TYPE"), Some("FLAVOR"))' > build.sbt`
     * `echo >> build.sbt`
       * Replace `Some(...)` with `None` if you don't have a flavor or build type to apply
4. Install the `android-protify` SBT plugin, also from the project base, do:
   * `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-protify" % "1.1.10")' > project/plugins.sbt`
   * For every application sub-project, do: `echo 'protifySettings' > APP-PROJECT-DIR/protify.sbt`
5. Launch SBT, `sbt`
5. Build and install the application normally, at least once:
   * `PROJECT-NAME/android:install` (or `run` instead of `install`)
6. Thereafter: `PROJECT-NAME/protify`, do `~PROJECT-NAME/protify` to
   automatically trigger on all source changes

### Everyone else:

1. Install sbt from http://scala-sbt.org, homebrew, ports, or your
   package manager of choice
2. Install [android-sdk-plugin](https://github.com/pfn/android-sdk-plugin):
   `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.5.4")' > ~/.sbt/0.13/plugins/android.sbt`
3. Start from an existing or new project (for trivial projects):
   `sbt "gen-android ..."` to create a new project, `sbt gen-android-sbt` to
   generate sbt files in an existing project. Non-trivial projects will need
   more advanced sbt configuration.
   * Alternatively, use `android-gradle-build` when working with an existing gradle project:
     * `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-gradle-build" % "1.1.3")' > project/plugins.sbt`
     * `echo 'object Build extends android.GradleBuild' > project/build.scala`
4. Add the protify plugin:
   `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-protify" % "1.1.10")' >> project/plugins.sbt`
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
  * Deleting a constant value from `R` classes (removing resources) will
    require running `protify:clean` or else the build will break
  * Singleton state will not be restored upon deploying new dex code.
    (or resources when on device api level <14)
  * NDK is not supported at the moment

### IntelliJ integration

1. It works automatically if you're already using `android-sdk-plugin` and SBT

### Vim, etc.

1. Just do any of the above getting started steps and follow your own workflow.
