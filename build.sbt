import java.io.BufferedReader

import bintray.Keys._

import scala.annotation.tailrec

// val desktop = project.in(file("desktop"))

val rtxtGenerator = TaskKey[Seq[File]]("rtxt-generator")
val buildInfoGenerator = TaskKey[Seq[File]]("build-info-generator")

val common = project.in(file("common")).settings(
  crossPaths := false,
  autoScalaLibrary := false,
  organization := "com.hanhuy.android",
  name := "protify-common",
  javacOptions in Compile ++= "-target" :: "1.7" :: "-source" :: "1.7" :: Nil,
  exportJars := true,
  sourceGenerators in Compile <+= buildInfoGenerator,
  buildInfoGenerator := {
    val dest = (sourceManaged in Compile).value / "BuildInfo.java"
    val info =
      s"""
        |package com.hanhuy.android.protify;
        |public class BuildInfo {
        |    public static String version = "${version.value}";
        |}
      """.stripMargin
    IO.writeLines(dest, info :: Nil)
    dest :: Nil
  }
)

val plugin = project.in(file("sbt-plugin")).settings(
  bintrayPublishSettings ++ scriptedSettings ++
    addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.5.0")
).settings(
  name := "android-protify",
  organization := "com.hanhuy.sbt",
  scalacOptions ++= Seq("-deprecation","-Xlint","-feature", "-unchecked"),
  sbtPlugin := true,
  repository in bintray := "sbt-plugins",
  publishMavenStyle := false,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  scriptedLaunchOpts ++= Seq("-Xmx1024m",
    "-Dplugin.version=" + version.value
  ),
  bintrayOrganization in bintray := None,
  mappings in (Compile, packageBin) ++= (mappings in (Compile, packageBin) in common).value
).dependsOn(common % "provided")

val lib = project.in(file("lib")).settings(androidBuildJar).settings(
  platformTarget in Android := "android-15",
  autoScalaLibrary := false,
  organization := "com.hanhuy.android",
  name := "protify",
  publishMavenStyle := true,
  javacOptions in Compile ++= "-target" :: "1.7" :: "-source" :: "1.7" :: Nil,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  pomIncludeRepository := { _ => false },
  pomExtra :=
    <scm>
      <url>git@github.com:pfn/protify.git</url>
      <connection>scm:git:git@github.com:pfn/protify.git</connection>
    </scm>
    <developers>
      <developer>
        <id>pfnguyen</id>
        <name>Perry Nguyen</name>
        <url>https://github.com/pfn</url>
      </developer>
    </developers>,
  licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php")),
  homepage := Some(url("https://github.com/pfn/protify"))
)

val agent = project.in(file("agent")).settings(androidBuildAar).settings(
  platformTarget in Android := "android-15",
  mappings in (Compile, packageBin) ++= (mappings in (Compile, packageBin) in common).value,
  javacOptions in (Compile,doc) ~= {
    _.foldRight(List.empty[String]) { (x, a) =>
      if ("-bootclasspath" == x) {
        import java.io.File._
        x :: (System.getProperty("java.home") + separator + "lib" + separator + "rt.jar" + pathSeparator + a.head) :: a.tail
      }
      else x :: a
    }
  },
  autoScalaLibrary := false,
  organization := "com.hanhuy.android",
  name := "protify-agent",
  publishMavenStyle := true,
  javacOptions in Compile ++= "-target" :: "1.7" :: "-source" :: "1.7" :: Nil,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  pomIncludeRepository := { _ => false },
  pomExtra :=
    <scm>
      <url>git@github.com:pfn/protify.git</url>
      <connection>scm:git:git@github.com:pfn/protify.git</connection>
    </scm>
      <developers>
        <developer>
          <id>pfnguyen</id>
          <name>Perry Nguyen</name>
          <url>https://github.com/pfn</url>
        </developer>
      </developers>,
  licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php")),
  homepage := Some(url("https://github.com/pfn/protify"))
) dependsOn(common % "provided")

val mobile = project.in(file("android")).settings(androidBuild).settings(
  platformTarget in Android := "android-22",
  versionName in Android := Some(version.value),
  versionCode in Android := Some(1),
  minSdkVersion in Android := "16",
  targetSdkVersion in Android := "22",
  debugIncludesTests in Android := false,
  scalaVersion := "2.11.7",
  javacOptions in Compile ++= "-target" :: "1.7" :: "-source" :: "1.7" :: Nil,
  sourceGenerators in Compile <+= rtxtGenerator,
  rtxtGenerator <<= Def.task {
    val layout = (projectLayout in Android).value
    val rtxt = layout.gen / "R.txt"
    val aars = target.value / "aars"
    val librtxt = aars ** "R.txt" get
    val appcompatR = librtxt.filter(_.getParentFile.getName.startsWith("com.android.support-appcompat-v7")).head
    val designR = librtxt.filter(_.getParentFile.getName.startsWith("com.android.support-design")).head
    def collectConst(in: BufferedReader) = {
      IO.foldLines(in, Map.empty[String,Set[String]]) { (m, line) =>
        val parts = line.split(" ")
        val clazz = parts(1)
        val name = parts(2)
        m + ((clazz, m.getOrElse(clazz, Set.empty) + name))
      }
    }
    val appcompatConst = Using.fileReader(IO.utf8)(appcompatR)(collectConst)
    val designConst    = Using.fileReader(IO.utf8)(designR)(collectConst)

    val rloader = layout.gen / "com" / "hanhuy" / "android" / "protify" / "RTxtLoader.java"
    val intTemplate =
      """
        |            case "%s:%s":
        |                %s
        |                %s
        |                break;
      """.stripMargin
    val arrayTemplate =
      """
        |            case "%s:%s":
        |                %s
        |                %s
        |                break;
      """.stripMargin
    val template =
      """
        |package com.hanhuy.android.protify;
        |public class RTxtLoader extends RTxtLoaderBase {
        |    @Override public void setInt(String clazz, String name, int value) {
        |        switch (clazz + ":" + name) {
        |%s
        |        }
        |    }
        |    @Override public void setIntArray(String clazz, String name, int[] value) {
        |        switch (clazz + ":" + name) {
        |%s
        |        }
        |    }
        |}
      """.stripMargin
    if (!rtxt.isFile) android.Plugin.fail("R.txt does not exist yet")
    val (vals, arys) = Using.fileReader(IO.utf8)(rtxt) { in =>
      IO.foldLines(in, (List.empty[String],List.empty[String])) { case ((values, arrays), line) =>
        val parts = line.split(" ")
        parts(0) match {
          case "int" =>
            val clazz = parts(1)
            val name = parts(2)
            val c = intTemplate format (clazz, name,
              if (designConst.getOrElse(clazz, Set.empty)(name)) s"android.support.design.R.$clazz.$name = value;" else "",
              if (appcompatConst.getOrElse(clazz, Set.empty)(name)) s"android.support.v7.appcompat.R.$clazz.$name = value;" else "")
            (c :: values, arrays)
          case "int[]" =>
            val clazz = parts(1)
            val name = parts(2)
            val c = arrayTemplate format (clazz, name,
              if (designConst.getOrElse(clazz, Set.empty)(name)) s"android.support.design.R.$clazz.$name = value;" else "",
              if (appcompatConst.getOrElse(clazz, Set.empty)(name)) s"android.support.v7.appcompat.R.$clazz.$name = value;" else "")
            (values, c :: arrays)
        }
      }
    }
    IO.writeLines(rloader,
      template.format(vals.mkString, arys.mkString) :: Nil)
    Seq(rloader)
  } dependsOn (rGenerator in Android),
  rGenerator in Android := {
    val r = (rGenerator in Android).value

    def exists(in: BufferedReader)(f: String => Boolean): Boolean = {
      @tailrec
      def readLine(accum: Boolean): Boolean = {
        val line = in.readLine()
        if (accum || (line eq null)) accum else readLine(f(line))
      }
      readLine(false)
    }

    val supportR = r filter {
      Using.fileReader(IO.utf8)(_) { in =>
        exists(in)(_ contains "package android.support")
      }
    }
    supportR foreach { s =>
      val lines = IO.readLines(s)
      IO.writeLines(s, lines map (_.replaceAll(" final ", " ")))
    }
    r
  },
  proguardOptions in Android ++=
    "-keep class scala.runtime.BoxesRunTime { *; }" :: // for debugging only
    "-keep class android.support.** { *; }" ::
    "-keep class com.hanhuy.android.protify.ActivityProxy {*;}" ::
    "-keep class com.hanhuy.android.protify.ActivityProxy$Simple {*;}" ::
    Nil,
  libraryDependencies ++=
    "com.hanhuy.android" %% "scala-common" % "1.0" ::
    "com.hanhuy.android" % "viewserver" % "1.0.3" ::
    "com.android.support" % "appcompat-v7" % "22.2.1" ::
    "com.android.support" % "design" % "22.2.1" ::
    Nil,
  manifestPlaceholders in Android := Map(
    "vmSafeMode" -> (apkbuildDebug in Android).value().toString
  )
).dependsOn(lib, common)

val test1 = android.Plugin.flavorOf(mobile, "test1").settings(
  applicationId in Android := "com.hanhuy.android.protify.tests",
  debugIncludesTests in Android := true,
  instrumentTestRunner in Android :=
    "android.support.test.runner.AndroidJUnitRunner",
  libraryDependencies ++=
    "com.android.support.test" % "runner" % "0.3" ::
    "com.android.support.test.espresso" % "espresso-core" % "2.2" ::
    Nil,
  packagingOptions in Android := PackagingOptions(excludes = Seq("LICENSE.txt")),
  proguardOptions in Android ++=
    "-dontwarn junit.**" ::
    "-dontwarn java.beans.**" ::
    "-dontwarn java.lang.management.**" ::
    "-dontwarn javax.lang.model.element.**" ::
    "-dontwarn org.jmock.**" ::
    "-dontwarn org.easymock.**" ::
    "-dontwarn com.google.appengine.**" ::
    "-keepclasseswithmembers class * { @org.junit.Test <methods>; }" ::
    "-keepclassmembers class scala.reflect.ScalaSignature { java.lang.String bytes(); }" ::
    "-keep class android.support.test.** { *; }" ::
    Nil
)

test <<= test in (test1,Android)

Keys.`package` in Android <<= Keys.`package` in (mobile,Android)

version in Global := "1.1.4-SNAPSHOT"
