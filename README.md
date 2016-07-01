# protify: instantaneous, on-device development for Android

[![Join the chat at https://gitter.im/scala-android/sbt-android](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/scala-android/sbt-android?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Current version is 1.2.6

NOTE: 1.2.0 is the last version published using
`addSbtPlugin("com.hanhuy.sbt" % "android-protify" % "1.2.0")`,
all future updates can be accessed by using
`addSbtPlugin("org.scala-android" % "sbt-android-protify" % VERSION)`

## Features:

* Multiple language support: Java (including retrolambda), Scala, Kotlin
* No code changes to app required!
* Support for appcompat-v7, design and support-v4 libraries
* Most existing android projects
* Android devices api level 4+
* Dex sharding for near-instant deployment

## Demo:

[![Live coding and hot deploy](http://img.youtube.com/vi/LJLLyua0bYA/mqdefault.jpg)](http://www.youtube.com/watch?v=LJLLyua0bYA)

## Getting started:

### Android Studio / Gradle Quick Start

1. Install sbt from http://scala-sbt.org, homebrew, ports, or your
   package manager of choice
2. Optionally add `idea-sbt-plugin` to run SBT inside of Android Studio
3. Install the
   [`sbt-android-gradle`](https://github.com/scala-android/sbt-android/blob/master/GRADLE.md)
   SBT plugin to automatically load your gradle build. From the base of your
   Android project, do:
   * `mkdir project`
   * `echo 'addSbtPlugin("org.scala-android" % "sbt-android-gradle" % "1.2.1")' > project/plugins.sbt`
   * `echo >> project/plugins.sbt`
   * If you have any flavors or build types that must be loaded:
     * `echo 'android.Plugin.withVariant("PROJECT-NAME (e.g. app)", Some("BUILD-TYPE"), Some("FLAVOR"))' > build.sbt`
     * `echo >> build.sbt`
       * Replace `Some(...)` with `None` if you don't have a flavor or build type to apply
4. Install the `sbt-android-protify` plugin, also from the project base, do:
   * `echo 'addSbtPlugin("org.scala-android" % "sbt-android-protify" % "1.2.6")' >> project/plugins.sbt`
   * For every application sub-project, do: `echo 'protifySettings' > APP-PROJECT-DIR/protify.sbt`
5. Launch SBT, `sbt` (first time's gonna take a while, downloading the internet and all)
5. Build and install the application normally, at least once:
   * `PROJECT-NAME/android:install` (or `run` instead of `install`) -- the first
     time will take a while too, since it will download the parts of the
     internet that your app requires
6. Thereafter: `PROJECT-NAME/protify`, do `~PROJECT-NAME/protify` to
   automatically trigger on all source changes

### Everyone else:

1. Install sbt from http://scala-sbt.org, homebrew, ports, or your
   package manager of choice
2. Install [sbt-android](https://github.com/scala-android/sbt-android):
   `echo 'addSbtPlugin("org.scala-android" % "sbt-android" % "1.6.6")' > ~/.sbt/0.13/plugins/android.sbt`
3. Start from an existing or new project (for trivial projects):
   `sbt "gen-android ..."` to create a new project, `sbt gen-android-sbt` to
   generate sbt files in an existing project. Non-trivial projects will need
   more advanced sbt configuration.
   * Alternatively, use `sbt-android-gradle` when working with an existing gradle project:
     * `echo 'addSbtPlugin("org.scala-android" % "sbt-android-gradle" % "1.2.1")' > project/plugins.sbt`
4. Add the protify plugin:
   `echo 'addSbtPlugin("org.scala-android" % "sbt-android-protify" % "1.2.6")' >> project/plugins.sbt`
5. Add `protifySettings`: `echo protifySettings >> build.sbt`
6. Run SBT
7. Select device to run on by using the `devices` and `device` commands. Run
   on all devices by executing `set allDevices in Android := true`
8. `android:run`, and `~protify`
   * Alternatively, high speed turnaround can be achieved with `protify:install`
     and `protify:run` to pretend the app is getting updated, rather than
     using the live-code mechanism.
9. Enjoy

### Full IntelliJ IDEA & Android Studio integration

To make it so that protify can be run when triggered in the IDE with a keystroke,
do the following

1. Install [Scala plugin](https://plugins.jetbrains.com/plugin/1347?pr=idea) in IDE
2. Install [SBT plugin](https://plugins.jetbrains.com/plugin/5007?pr=idea) in IDE
3. Create a new `Run` configuration, press the dropdown next to the play button,
   select `Edit Configurations`
   * Press `+` -> `Android Application`
   * Name the configuration `protify`, `instant run`, or whatever you like
   * Select the `app` module
   * Select `Do not deploy anything`
   * Select `Do not launch activity`
   * For target device, select anything that will not prompt
   * Uncheck `Activate tool window`
   * Remove `Make` from `Before launch`
   * `Before launch` -> `+` -> `Add New Configuration` -> `SBT`
     * In the drop down, edit the text to say `protify`
4. You can now invoke `protify` directly as a run configuration and see changes
   instantly (FSVO instant) appear on-device.

### Vim, etc.

1. Just do any of the above getting started steps and follow your own workflow.

#### LIMITATIONS
  * Manifest changes will require `android:install` again (i.e.
    adding/removing: activities, services, permissions, receivers, etc).
    Incremental deployment cannot modify manifest.
  * Deleting a constant value from `R` classes (removing resources) will
    require running `protify:clean` or else the build will break
  * Object instance state, including singleton and static, will not be restored
    upon deploying new dex code (or resources when on device api level <14).
    All Android `Bundle`d state will be restored in all situations
  * Android instrumented tests are not supported. They will fail to run
    because of the sharded dex and re-located resource files.
  * NDK is not supported at the moment (initial install works, no `protify`
    updates when jni code changes)
  * When target device api level >= 23,
    `android.permission.READ_EXTERNAL_STORAGE` will automatically be granted.
    This means `checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")`
    will always return true while building with protify loaded. You will not be
    able to properly test requesting `READ_EXTERNAL_STORAGE` at runtime when
    using protify. Reading external storage is required to load incremental
    DEX and resource files.

#### TODO (volunteers wanted)
  * Support NDK
  * Write `protify` files into `/data/local/tmp` rather than `/sdcard` to bypass
    `READ_EXTERNAL_STORAGE` permission, eliminates emulator SD card requirement
  * Support instrumented testing. This would need implementation similar to
    `MultiDexTestRunner`
  * Bundle `protify-agent.jar` with sbt plugin so that it does not have to be
    published to maven central
