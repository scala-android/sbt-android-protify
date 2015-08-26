package android.protify

import java.io.File

import android.Keys.Internal._
import android.{Aggregate, Dex, Proguard}
import com.android.ddmlib.IDevice
import sbt.Def.Initialize
import sbt._
import sbt.Keys._
import android.Keys._
import sbt.classpath.ClasspathUtilities
import xsbt.api.Discovery

import scala.annotation.tailrec

import sbt.Cache.seqFormat
import sbt.Cache.StringFormat
import sbt.Cache.IntFormat
import sbt.Cache.tuple2Format

import language.postfixOps
import com.hanhuy.android.protify.BuildInfo

import scala.util.Try

/**
 * @author pfnguyen
 */
object Plugin extends AutoPlugin {
  override def trigger = noTrigger
  override def requires = plugins.JvmPlugin
  val autoImport = Keys
}

object Keys {
  import Internal._
  type ResourceId = (String,Int)
  val protifyLayout = InputKey[Unit]("protify-layout", "prototype an android layout on device")
  val protifyDex = InputKey[Unit]("protify-dex", "prototype code on-device")
  val protify = TaskKey[Unit]("protify", "live-coding on-device")

  private object Internal {
    val Protify = config("protify") extend Compile
    val protifyPublicResources = TaskKey[Unit]("internal-protify-public-resources", "internal key: generate public.xml from R.txt")
    val protifyDexes = TaskKey[Seq[String]]("internal-protify-dexes", "internal key: autodetected classes with ActivityProxy")
    val protifyLayouts = TaskKey[Seq[ResourceId]]("internal-protify-layouts", "internal key: autodetected layout files")
    val protifyThemes = TaskKey[(Seq[ResourceId],Seq[ResourceId])]("internal-protify-themes", "internal key: platform themes, app themes")
    val protifyLayoutsAndThemes = TaskKey[(Seq[ResourceId],(Seq[ResourceId],Seq[ResourceId]))]("internal-protify-layouts-and-themes", "internal key: themes and layouts")
    val ProtifyInternal = config("protify-internal") extend Protify
  }

  lazy val protifySettings: List[Setting[_]] = List(
    clean <<= clean dependsOn (clean in Protify),
    ivyConfigurations := overrideConfigs(Protify)(ivyConfigurations.value),
    libraryDependencies += "com.hanhuy.android" % "protify" % BuildInfo.version % "protify",
    libraryDependencies += "com.hanhuy.android" % "protify-agent" % BuildInfo.version,
    protifyDex <<= protifyDexTaskDef() dependsOn (dex in Protify),
    protify <<= protifyTaskDef,
    protifyLayout <<= protifyLayoutTaskDef(),
    protifyLayout <<= protifyLayout dependsOn (packageResources in Android, compile in Protify),
    watchSources := watchSources.value filterNot { s =>
      s relativeTo (target.value / "protify" / "res") isDefined
    }
  ) ++ inConfig(Protify)(Defaults.compileSettings) ++ inConfig(Protify)(List(
    clean <<= protifyCleanTaskDef,
    protifyPublicResources <<= protifyPublicResourcesTaskDef,
    protifyDexes <<= (compile in Protify) map discoverActivityProxies storeAs protifyDexes triggeredBy compile,
    protifyLayouts <<= protifyLayoutsTaskDef storeAs protifyLayouts triggeredBy (compile in Compile, compile),
    protifyThemes <<= discoverThemes storeAs protifyThemes triggeredBy (compile in Compile, compile),
    protifyLayoutsAndThemes <<= (protifyLayouts,protifyThemes) map ((_,_)) storeAs protifyLayoutsAndThemes triggeredBy (compile in Compile, compile),
    javacOptions := (javacOptions in Compile).value,
    scalacOptions := (scalacOptions in Compile).value,
    sourceGenerators <<= sourceGenerators in Compile,
    unmanagedSourceDirectories :=
      (unmanagedSourceDirectories in Compile).value ++ {
        val layout = (projectLayout in Android).value
        val gradleLike = Seq(
          layout.base / "src" / "protify" / "scala",
          layout.base / "src" / "protify" / "java"
        )
        val antLike = Seq(
          layout.base / "protify"
        )
        @tailrec
        def sourcesFor(p: ProjectLayout): Seq[File] = p match {
          case g: ProjectLayout.Gradle => gradleLike
          case a: ProjectLayout.Ant => antLike
          case w: ProjectLayout.Wrapped => sourcesFor(w.wrapped)
        }
        sourcesFor(layout)
      },
    proguardOptions      := (proguardOptions in Android).value,
    proguardOptions      += "-keep public class * extends com.hanhuy.android.protify.ActivityProxy { *; }",
    proguardAggregate   <<= proguardAggregateTaskDef,
    proguardInputs      <<= proguardInputsTaskDef,
    proguardInputs      <<= proguardInputs dependsOn (sbt.Keys.`package` in Protify),
    proguard            <<= proguardTaskDef,
//    predex              <<= predexTaskDef,
    dexAggregate        <<= dexAggregateTaskDef,
    dexInputs           <<= dexInputsTaskDef,
    dex                 <<= dexTaskDef
  ) ++ inConfig(ProtifyInternal)(
    dependencyClasspath <<= dependencyClasspathTaskDef
  ) ++ inConfig(Android)(List(
    extraResDirectories <+= target (_ / "protify" / "res"),
    collectResources <<= collectResources dependsOn (protifyPublicResources in Protify),
    cleanForR := {
      val ignores: Set[AttributeKey[_]] = Set(protifyLayout.key, protifyDex.key, protify.key)
      val d = (classDirectory in Compile).value
      val s = streams.value
      val g = genPath.value
      val roots = executionRoots.value map (_.key)
      if (!roots.exists(ignores)) {
        FileFunction.cached(s.cacheDirectory / "clean-for-r",
          FilesInfo.hash, FilesInfo.exists) { in =>
          if (in.nonEmpty) {
            s.log.info("Rebuilding all classes because R.java has changed")
            IO.delete(d)
          }
          in
        }(Set(g ** "R.java" get: _*))
      }
      Seq.empty[File]
    },
    cleanForR <<= cleanForR dependsOn rGenerator
  )))

  private val discoverThemes = Def.task {
    val androidJar = (platformJars in Android).value._1
    val resPath = (projectLayout in Android).value.bin / "resources" / "res"
    val log = streams.value.log
    val cl = ClasspathUtilities.toLoader(file(androidJar))
    val style = cl.loadClass("android.R$style")
    type Theme = (String,String)

    val values = (resPath ** "values*" ** "*.xml").get
    import scala.xml._
    val allstyles = values flatMap { f =>
      val xml = XML.loadFile(f)
      (xml \ "style") map { n =>
        val nm = n.attribute("name").head.text.replace('.','_')
        val parent = n.attribute("parent").fold(nm.substring(0, nm.indexOf("_")))(_.text).replace('.','_')
        (nm,parent)
      }
    }
    val tree = allstyles.toMap
    @tailrec
    def isTheme(style: String): Boolean = {
      if (style startsWith "Theme_") true
      else if (tree.contains(style))
        isTheme(tree(style))
      else false
    }
    @tailrec
    def isAppCompatTheme(style: String): Boolean = {
      if (style startsWith "Theme_AppCompat") true
      else if (tree.contains(style))
        isAppCompatTheme(tree(style))
      else false
    }

    val pkg = (packageForR in Android).value
    val loader = ClasspathUtilities.toLoader((classDirectory in Compile).value)
    // TODO fix me, do not assume exists!
    val themes = Try(loader.loadClass(pkg + ".R$style")).toOption.fold(Seq.empty[ResourceId]) { clazz =>
      allstyles.map(_._1) filter isTheme flatMap { t =>
        try {
          val f = clazz.getDeclaredField(t)
          Seq((t, f.getInt(null)))
        } catch {
          case e: Exception =>
            log.warn(s"Unable to lookup field: $t, because ${e.getMessage}")
            Seq.empty
        }
      }
    }
    val appcompat = themes filter (t => isAppCompatTheme(t._1))

    // return (platform + all app themes, appcompat-only themes)
    ((style.getDeclaredFields filter (_.getName startsWith "Theme_") map { f =>
      (f.getName, f.getInt(null))
    } toSeq) ++ themes,appcompat)
  }
  private val protifyLayoutsTaskDef = Def.task {
    val pkg = (packageForR in Android).value
    val loader = ClasspathUtilities.toLoader((classDirectory in Compile).value)
    val clazz = loader.loadClass(pkg + ".R$layout")
    val fields = clazz.getDeclaredFields
    fields.map(f => f.getName -> f.getInt(null)).toSeq
  }

  def protifyLayoutTaskDef(): Initialize[InputTask[Unit]] = {
    val parser = loadForParser(protifyLayoutsAndThemes in Protify) { (s, stored) =>
      import sbt.complete.Parser
      import sbt.complete.DefaultParsers._
      val res = stored.getOrElse((Seq.empty[ResourceId],(Seq(("<no themes>",0)),Seq.empty[ResourceId])))
      val layouts = res._1.map(_._1)
      val themes = res._2._1 map (t => token(t._1))
      EOF.map(_ => None) | (Space ~> Parser.opt(token(NotSpace examples layouts.toSet) ~ Parser.opt((Space ~> Parser.oneOf(themes)) <~ SpaceClass.*)))
    }
    Def.inputTask {
      val res = (packageResources in Android).value
      val l = parser.parsed
      val log = streams.value.log
      val all = (allDevices in Android).value
      val sdk = (sdkPath in Android).value
      val layout = (projectLayout in Android).value
      val rTxt = layout.gen / "R.txt"
      val rTxtHash = if (rTxt.isFile) Hash.toHex(Hash(rTxt)) else "no-r.txt"
      val layouts = loadFromContext(protifyLayouts in Protify, sbt.Keys.resolvedScoped.value, state.value).getOrElse(Nil)
      val themes = loadFromContext(protifyThemes in Protify, sbt.Keys.resolvedScoped.value, state.value).getOrElse((Nil,Nil))
      if (layouts.isEmpty || themes._1.isEmpty) {
        android.Plugin.fail("No layouts or themes cached, try again?")
      }
      if (l.isEmpty) {
        log.info("Previewing R.layout." + layouts.head._1)
      }
      val resid = l.fold(layouts.head._2)(r => layouts.toMap.apply(r._1))
      val appcompat = themes._2.toMap
      val theme = l.flatMap(_._2)
      val themeid = theme.fold(0)(themes._1.toMap.apply)
      log.debug("available layouts: " + layouts)
      import android.Commands
      import com.hanhuy.android.protify.Intents._
      val isAppcompat = theme.fold(false)(appcompat.contains)
      def execute(dev: IDevice): Unit = {
        val f = java.io.File.createTempFile("resources", ".ap_")
        val f2 = java.io.File.createTempFile("RES", ".txt")
        f.delete()
        f2.delete()
        val cmdS =
          "am"   :: "broadcast"     ::
          "-a"   :: LAYOUT_INTENT   ::
          "-e"   :: EXTRA_RESOURCES :: s"/sdcard/protify/${f.getName}"  ::
          "-e"   :: EXTRA_RTXT      :: s"/sdcard/protify/${f2.getName}" ::
          "-e"   :: EXTRA_RTXT_HASH :: rTxtHash                         ::
          "--ez" :: EXTRA_APPCOMPAT :: isAppcompat                      ::
          "--ei" :: EXTRA_THEME     :: themeid                          ::
          "--ei" :: EXTRA_LAYOUT    :: resid                            ::
          "com.hanhuy.android.protify/.LayoutReceiver"                  ::
          Nil

        log.debug("Executing: " + cmdS.mkString(" "))
        dev.executeShellCommand("rm -rf /sdcard/protify/*", new Commands.ShellResult)
        android.Tasks.logRate(log, s"resources deployed to ${dev.getSerialNumber}:", res.length + rTxt.length) {
          dev.pushFile(res.getAbsolutePath, s"/sdcard/protify/${f.getName}")
          if (rTxt.isFile)
            dev.pushFile(rTxt.getAbsolutePath, s"/sdcard/protify/${f2.getName}")
        }
        dev.executeShellCommand(cmdS.mkString(" "), new Commands.ShellResult)
      }
      if (all)
        Commands.deviceList(sdk, log).par foreach execute
      else
        Commands.targetDevice(sdk, log) foreach execute
    }
  }
  def protifyDexTaskDef(): Initialize[InputTask[Unit]] = {
    val parser = loadForParser(protifyDexes in Protify) { (s, stored) =>
      import sbt.complete.Parser
      import sbt.complete.DefaultParsers._
      val proxies = stored.getOrElse(Nil)
      EOF.map(_ => None) | (Space ~> Parser.opt(token(NotSpace examples proxies.toSet) ~ Parser.opt((Space ~> token("appcompat")) <~ SpaceClass.*)))
    }
    Def.inputTask {
      val l = parser.parsed
      val dexes = loadFromContext(protifyDexes in Protify, sbt.Keys.resolvedScoped.value, state.value).getOrElse(Nil)
      val res = (packageResources in Android).value
      val dexfile = (dex in Protify).value ** "*.dex" get
      val log = streams.value.log
      val all = (allDevices in Android).value
      val sdk = (sdkPath in Android).value
      val layout = (projectLayout in Android).value
      val rTxt = layout.gen / "R.txt"
      val rTxtHash = if (rTxt.isFile) Hash.toHex(Hash(rTxt)) else "no-r.txt"
      if (dexes.isEmpty) {
        android.Plugin.fail("No ActivityProxy cached, try again.")
      }
      if (dexfile.size != 1) {
        android.Plugin.fail("There must be only one DEX file (multidex not supported)")
      }
      if (l.isEmpty) {
        log.info("Previewing " + dexes.head)
      }
      log.debug("available proxies: " + dexes)
      val proxyClass = l.fold(dexes.head)(_._1)
      import android.Commands
      import com.hanhuy.android.protify.Intents._
      val isAppcompat = l.fold(false)(_._2.exists(_ == "appcompat"))
      def execute(dev: IDevice): Unit = {
        import java.io.File.createTempFile
        val f = createTempFile("resources", ".ap_")
        val f2 = createTempFile("RES", ".txt")
        val f3 = createTempFile("classes", ".dex")
        f.delete()
        f2.delete()
        f3.delete()
        val cmdS =
          "am"   :: "broadcast"     ::
          "-a"   :: DEX_INTENT      ::
          "-e"   :: EXTRA_RESOURCES :: s"/sdcard/protify/${f.getName}"  ::
          "-e"   :: EXTRA_RTXT      :: s"/sdcard/protify/${f2.getName}" ::
          "-e"   :: EXTRA_DEX       :: s"/sdcard/protify/${f3.getName}" ::
          "-e"   :: EXTRA_CLASS     :: proxyClass                       ::
          "-e"   :: EXTRA_RTXT_HASH :: rTxtHash                         ::
          "--ez" :: EXTRA_APPCOMPAT :: isAppcompat                      ::
          "com.hanhuy.android.protify/.DexReceiver"                     ::
          Nil

        log.debug("Executing: " + cmdS.mkString(" "))
        dev.executeShellCommand("rm -rf /sdcard/protify/*", new Commands.ShellResult)
        android.Tasks.logRate(log, s"code deployed to ${dev.getSerialNumber}:", dexfile.head.length + res.length + rTxt.length) {
          dev.pushFile(res.getAbsolutePath, s"/sdcard/protify/${f.getName}")
          dev.pushFile(dexfile.head.getAbsolutePath, s"/sdcard/protify/${f3.getName}")
          if (rTxt.isFile)
            dev.pushFile(rTxt.getAbsolutePath, s"/sdcard/protify/${f2.getName}")
        }
        dev.executeShellCommand(cmdS.mkString(" "), new Commands.ShellResult)
      }
      if (all)
        Commands.deviceList(sdk, log).par foreach execute
      else
        Commands.targetDevice(sdk, log) foreach execute
    }
  }

  val protifyTaskDef = Def.task {
    val res = (packageResources in Android).value
    val dexfile = (dex in Protify).value ** "*.dex" get
    val st = streams.value
    val cacheDirectory = st.cacheDirectory / "protify"
    val log = st.log
    val all = (allDevices in Android).value
    val sdk = (sdkPath in Android).value
    val pkg = (applicationId in Android).value
    if (dexfile.size != 1) {
      android.Plugin.fail("There must be only one DEX file (multidex not supported)")
    }
    import android.Commands
    import com.hanhuy.android.protify.Intents._
    def execute(dev: IDevice): Unit = {
      import java.io.File.createTempFile
      val f = createTempFile("resources", ".ap_")
      val f3 = createTempFile("classes", ".dex")
      f.delete()
      f3.delete()
      val cmdS =
        "am"   :: "broadcast"     ::
        "-a"   :: PROTIFY_INTENT  ::
        "-e"   :: EXTRA_RESOURCES :: s"/sdcard/protify/$pkg/${f.getName}"  ::
        "-e"   :: EXTRA_DEX       :: s"/sdcard/protify/$pkg/${f3.getName}" ::
        s"$pkg/com.hanhuy.android.protify.agent.internal.ProtifyReceiver"  ::
        Nil

      log.debug("Executing: " + cmdS.mkString(" "))
      dev.executeShellCommand(s"rm -rf /sdcard/protify/$pkg/*", new Commands.ShellResult)
      var pushres = false
      var pushdex = false
      FileFunction.cached(cacheDirectory / dev.getSerialNumber / "res", FilesInfo.lastModified) { in =>
        pushres = true
        in
      }(Set(res))
      FileFunction.cached(cacheDirectory / dev.getSerialNumber / "dex", FilesInfo.lastModified) { in =>
        pushdex = true
        in
      }(Set(dexfile.head))
      val pushlen = (if (pushres) res.length else 0) + (if (pushdex) dexfile.head.length else 0)
      if (pushres || pushdex) {
        android.Tasks.logRate(log, s"code deployed to ${dev.getSerialNumber}:", pushlen) {
          if (pushres)
            dev.pushFile(res.getAbsolutePath, s"/sdcard/protify/$pkg/${f.getName}")
          if (pushdex)
            dev.pushFile(dexfile.head.getAbsolutePath, s"/sdcard/protify/$pkg/${f3.getName}")
        }
        dev.executeShellCommand(cmdS.mkString(" "), new Commands.ShellResult)
      }
    }
    if (all)
      Commands.deviceList(sdk, log).par foreach execute
    else
      Commands.targetDevice(sdk, log) foreach execute
  }
  val protifyCleanTaskDef = Def.task {
    val st = streams.value
    val cacheDirectory = (streams in protify).value.cacheDirectory / "protify"
    val log = st.log
    val all = (allDevices in Android).value
    val sdk = (sdkPath in Android).value
    val pkg = (applicationId in Android).value
    val layout = (projectLayout in Android).value
    (layout.bin /  "resources" / "res" / "values" / "public.xml").delete()
    (layout.gen / "R.txt").delete()
    import android.Commands
    import com.hanhuy.android.protify.Intents._
    def execute(dev: IDevice): Unit = {
      val cmdS =
        "am"   :: "broadcast"     ::
        "-a"   :: CLEAN_INTENT    ::
        s"$pkg/com.hanhuy.android.protify.agent.internal.ProtifyReceiver" ::
        Nil

      log.debug("Executing: " + cmdS.mkString(" "))
      dev.executeShellCommand(s"rm -rf /sdcard/protify/$pkg", new Commands.ShellResult)
      FileFunction.cached(cacheDirectory / dev.getSerialNumber / "res", FilesInfo.lastModified) { in =>
        Set.empty
      }(Set.empty)
      FileFunction.cached(cacheDirectory / dev.getSerialNumber / "dex", FilesInfo.lastModified) { in =>
        Set.empty
      }(Set.empty)
      dev.executeShellCommand(cmdS.mkString(" "), new Commands.ShellResult)
    }
    if (all)
      Commands.deviceList(sdk, log).par foreach execute
    else
      Commands.targetDevice(sdk, log) foreach execute
  }
  def discoverActivityProxies(analysis: inc.Analysis): Seq[String] =
    Discovery(Set("com.hanhuy.android.protify.ActivityProxy"), Set.empty)(Tests.allDefs(analysis)).collect({
        case (definition, discovered) if !definition.modifiers.isAbstract &&
          discovered.baseClasses("com.hanhuy.android.protify.ActivityProxy") =>
          definition.name }).sorted

  val proguardAggregateTaskDef = Def.task {
    Aggregate.Proguard(
      (useProguard        in Android).value,
      (useProguardInDebug in Android).value,
      (proguardScala      in Android).value,
      (proguardConfig     in Android).value,
      proguardOptions.value,
      (proguardCache      in Android).value)
  }
  private val proguardInputsTaskDef = Def.task {
    val u         = (useProguard         in Android).value
    val pgConfig  = (proguardConfig      in Android).value
    val l         = (proguardLibraries   in Android).value
    val (p,x)     = (platformJars        in Android).value
    val s         = (proguardScala       in Android).value
    val pc        = (proguardCache       in Android).value
    val debug     = (apkbuildDebug       in Android).value
    val d         = (dependencyClasspath in ProtifyInternal).value
    val pgOptions = proguardOptions.value
    val c         = sbt.Keys.`package`.value
    val st        = streams.value
    Proguard.proguardInputs(
      u, pgOptions, pgConfig, l, d, p, x, c, s, pc, debug(), st)
  }

  private val proguardTaskDef = Def.task {
    val bldr   = (builder              in Android).value
    val l      = (libraryProject       in Android).value
    val debug  = (apkbuildDebug        in Android).value
    val b      = (binPath              in Android).value
    val ra     = (retrolambdaAggregate in Android).value
    val a      = proguardAggregate.value
    val inputs = proguardInputs.value
    val s      = streams.value
    Proguard.proguard(a, bldr, l, inputs, debug(), b, ra, s)
  }
  val dexAggregateTaskDef = Def.task {
    Aggregate.Dex(
      dexInputs.value,
      (dexMaxHeap               in Android).value,
      (dexMulti                 in Android).value,
      file("/"), //(dexMainFileClassesConfig in Android).value,
      (dexMinimizeMainFile      in Android).value,
      (dexAdditionalParams      in Android).value)
  }
  private val dexInputsTaskDef = Def.task {
    val ra       = (retrolambdaAggregate in Android).value
    val multiDex = (dexMulti             in Android).value
    val b        = (binPath              in Android).value
    val debug    = (apkbuildDebug        in Android).value
    val deps     = (dependencyClasspath  in ProtifyInternal).value
    val classJar = sbt.Keys.`package`.value
    val pa       = proguardAggregate.value
    val in       = proguardInputs.value
    val progOut  = proguard.value
    val s        = streams.value
    Dex.dexInputs(progOut, in, pa, ra, multiDex, b, deps, classJar, debug(), s)
  }
  private val dexTaskDef = Def.task {
    val bldr    = (builder        in Android).value
    val minSdk  = (minSdkVersion  in Android).value
    val lib     = (libraryProject in Android).value
    val bin     = (binPath        in Android).value / "protify"
    val debug   = (apkbuildDebug  in Android).value
    val pg      = proguard.value
    val dexOpts = dexAggregate.value
    val s       = streams.value
    val classes = sbt.Keys.`package`.value
    bin.mkdirs()
    Dex.dex(bldr, dexOpts, Seq.empty, pg, classes, minSdk, lib, bin, debug(), s)
  }
  val dependencyClasspathTaskDef = Def.task {
    val cj = (classesJar in Android).value
    val cp = (dependencyClasspath in Protify).value
    val d  = libraryDependencies.value
    val s  = streams.value
    s.log.debug("Filtering compile:dependency-classpath from: " + cp)
    val pvd = d filter { dep => dep.configurations exists (_ == "provided") }

    cp foreach { a =>
      s.log.debug("%s => %s: %s" format (a.data.getName,
        a.get(configuration.key), a.get(moduleID.key)))
    }
    // it seems internal-dependency-classpath already filters out "provided"
    // from other projects, now, just filter out our own "provided" lib deps
    // do not filter out provided libs for scala, we do that later
    cp filterNot { a =>
      (cj.getAbsolutePath == a.data.getAbsolutePath) || (a.get(moduleID.key) exists { m =>
        m.organization != "org.scala-lang" &&
          (pvd exists (p => m.organization == p.organization &&
            m.name == p.name))
      }) || a.data.getName.startsWith("com.hanhuy.android-protify-agent-")
    }
  }

  val protifyPublicResourcesTaskDef = Def.task {
    val layout = (projectLayout in Android).value
    val rtxt = layout.gen / "R.txt"
    val resbase = layout.bin /  "resources" / "res" / "values"
    resbase.mkdirs()
    val public = resbase / "public.xml"
    val idsfile = target.value / "protify" / "res" / "values" / "ids.xml"
    idsfile.getParentFile.mkdirs()
    if (rtxt.isFile) {
      FileFunction.cached(streams.value.cacheDirectory / "public-xml", FilesInfo.lastModified) { in =>
        val values = (resbase ** "values*" ** "*.xml").get
        import scala.xml._
        val allstyles = values.flatMap { f =>
          val xml = XML.loadFile(f)
          (xml \ "style") map { n =>
            val nm = n.attribute("name").head.text
            (nm.replace('.','_'),nm)
          }
        }.toMap

        streams.value.log.info("Maintaining resource ID consistency")
        val (publics, ids) = Using.fileReader(IO.utf8)(rtxt) { in =>
          IO.foldLines(in, (List("</resources>"), List("</resources>"))) { case ((xs, ys), line) =>
            val parts = line.split(" ")
            val cls = parts(1)
            val nm = if (cls == "style") {
              allstyles.getOrElse(parts(2), parts(2).replace('_', '.'))
            } else parts(2)
            val value = parts(3)
            if ("styleable" != cls)
              ( s"""  <public type="$cls" name="$nm" id="$value"/>""" :: xs,
                if ("id" == cls) s"""  <item type="id" name="$nm"/>""" :: ys else ys)
            else
              (xs, ys)
          }
        }
        if (publics.length > 1)
          IO.writeLines(public, """<?xml version="1.0" encoding="utf-8"?>""" :: "<resources>" :: publics)
        else
          IO.delete(public)
        if (ids.length > 1)
          IO.writeLines(idsfile, """<?xml version="1.0" encoding="utf-8"?>""" :: "<resources>" :: ids)
        else
          IO.delete(idsfile)
        Set(public, idsfile)
      }(Set(rtxt))
    }
    ()
  }
}
