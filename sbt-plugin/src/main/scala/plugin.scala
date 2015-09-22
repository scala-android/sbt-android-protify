package android.protify

import java.io.File
import java.net.URLEncoder

import android.Keys.Internal._
import android.{BuildOutput, Aggregate, Dex, Proguard}
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
  import Keys.Internal.Protify
  override def trigger = allRequirements
  override def requires = android.AndroidPlugin

  override def projectSettings = super.projectSettings ++ Seq(
    ivyConfigurations := overrideConfigs(Protify)(ivyConfigurations.value)
  )

  val autoImport = Keys
}

object Keys {
  import Internal._
  type ResourceId = (String,Int)
  val protifyLayout = InputKey[Unit]("protify-layout", "prototype an android layout on device")
  val protify = TaskKey[Unit]("protify", "live-coding on-device")

  private[android] object Internal {
    val Protify = config("protify") extend Compile
    val protifyDexAgent = TaskKey[File]("internal-protify-dex-agent", "internal key: dex protify-agent.jar")
    val protifyDexJar = TaskKey[File]("internal-protify-dex-jar", "internal key: create a jar containing all dexes")
    val protifyPublicResources = TaskKey[Unit]("internal-protify-public-resources", "internal key: generate public.xml from R.txt")
    val protifyLayouts = TaskKey[Seq[ResourceId]]("internal-protify-layouts", "internal key: autodetected layout files")
    val protifyThemes = TaskKey[(Seq[ResourceId],Seq[ResourceId])]("internal-protify-themes", "internal key: platform themes, app themes")
    val protifyLayoutsAndThemes = TaskKey[(Seq[ResourceId],(Seq[ResourceId],Seq[ResourceId]))]("internal-protify-layouts-and-themes", "internal key: themes and layouts")
    val ProtifyInternal = config("protify-internal") extend Protify
  }

  private[this] def appInfoDescriptor(target: File) =
    target / "protify_application_info.txt"

  lazy val protifySettings: List[Setting[_]] = List(
    clean <<= clean dependsOn (clean in Protify),
    libraryDependencies += "com.hanhuy.android" % "protify-agent" % BuildInfo.version,
    protify <<= protifyTaskDef,
    protifyLayout <<= protifyLayoutTaskDef(),
    protifyLayout <<= protifyLayout dependsOn (packageResources in Android, compile in Compile)
  ) ++ inConfig(Protify)(List(
    clean <<= protifyCleanTaskDef,
    install <<= protifyInstallTaskDef,
    run <<= protifyRunTaskDef,
    protifyDexAgent <<= protifyDexAgentTaskDef,
    protifyDexJar <<= protifyDexJarTaskDef,
    protifyPublicResources <<= protifyPublicResourcesTaskDef,
    protifyLayouts <<= protifyLayoutsTaskDef storeAs protifyLayouts triggeredBy (compile in Compile),
    protifyThemes <<= discoverThemes storeAs protifyThemes triggeredBy (compile in Compile),
    protifyLayoutsAndThemes <<= (protifyLayouts,protifyThemes) map ((_,_)) storeAs protifyLayoutsAndThemes triggeredBy (compile in Compile),
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
    proguardInputs      <<= proguardInputs dependsOn (packageT in Compile),
    proguard            <<= proguardTaskDef,
    predex              <<= predexTaskDef,
    dexAggregate        <<= dexAggregateTaskDef,
    dexInputs           <<= dexInputsTaskDef,
    dex                 <<= dexTaskDef
  ) ++ inConfig(ProtifyInternal)(
    dependencyClasspath <<= dependencyClasspathTaskDef
  ) ++ inConfig(Android)(List(
    managedClasspath <+= Def.task {
      // this hack is required because packageApk doesn't take resources from
      // application's classes.jar (or it would get lost during proguard,
      // retrolambda, etc.) Instead, generate a resource-only jar and add it
      // to the classpath where it will be included.
      val resPath = (resourceManaged in Protify).value
      implicit val output = (outputLayout in Android).value
      val layout = (projectLayout in Android).value
      val appInfoFile = appInfoDescriptor(resPath)
      val jarfile = layout.protifyDescriptorJar

      IO.jar(Seq(appInfoFile) pair flat, jarfile, new java.util.jar.Manifest)
      Attributed.blank(jarfile)
    } dependsOn processManifest,
    apkbuildAggregate <<= Def.taskDyn {
      val debug = apkbuildDebug.value()
      if (debug) Def.task {
        Aggregate.Apkbuild(packagingOptions.value,
          debug, (protifyDexAgent in Protify).value, Nil,
          collectJni.value, resourceShrinker.value)

      } else Def.task {
        Aggregate.Apkbuild(packagingOptions.value,
          debug, dex.value, predex.value,
          collectJni.value, resourceShrinker.value)

      }
    },
    apkbuild := {
      implicit val output = outputLayout.value
      val layout = projectLayout.value
      val a = apkbuildAggregate.value
      val n = name.value
      val u = (unmanagedJars in Compile).value
      val m = managedClasspath.value
      val dcp = (dependencyClasspath in Compile).value
      val dcpAg = m ++ u ++ dcp
      val dexjar = (protifyDexJar in Protify).value
      val s = streams.value
      val logger = ilogger.value(s.log)
      android.Packaging.apkbuild(builder.value,
        if (a.apkbuildDebug) Seq(Attributed.blank(dexjar)) else Nil, Nil, dcpAg,
        libraryProject.value, a.packagingOptions, a.resourceShrinker,
        a.dex, a.predex, a.collectJni,
        layout.collectJni, layout.resources, a.apkbuildDebug,
        layout.unsignedApk(a.apkbuildDebug, n), logger, s)
    },
    install := {
      val all = allDevices.value
      implicit val output = outputLayout.value
      val layout = projectLayout.value
      val s = streams.value
      install.value
      def installed(d: IDevice): Unit =
        IO.copyFile(layout.protifyDexHash, layout.protifyInstalledHash(d))

      if (all) android.Commands.deviceList(sdkPath.value, s.log) foreach installed
      else android.Commands.targetDevice(sdkPath.value, s.log) foreach installed
      ()
    },
    processManifest := {
      val processed = processManifest.value
      val pkg = packageForR.value
      val appInfoFile = appInfoDescriptor((resourceManaged in Protify).value)
      import scala.xml._
      import scala.xml.transform._
      object ApplicationTransform extends RewriteRule {
        import android.Tasks.ANDROID_NS
        override def transform(n: Node): Seq[Node] = n match {
          case Elem(prefix, "application", attribs, scope, children @ _*) =>
            val androidPrefix = scope.getPrefix(ANDROID_NS)
            val realApplication = attribs.get(ANDROID_NS, n, "name").fold(
              "android.app.Application") { nm =>
              val appName = nm.head.text
              if (appName.startsWith("."))
                pkg + appName
              else if (appName.contains("."))
                appName
              else
                pkg + "." + appName
            }
            IO.write(appInfoFile, realApplication)
            val attrs = attribs.filterNot(_.prefixedKey == s"$androidPrefix:name")
            val withNameAttr = new PrefixedAttribute(androidPrefix,
              "name", "com.hanhuy.android.protify.agent.ProtifyApplication",
              attrs.foldLeft(Null: MetaData)((a,b) => a.append(b)))
            // ugh, need to create elements instead of xml literals because
            // we want to allow non-'android' namespace prefixes
            val activityName = new PrefixedAttribute(androidPrefix,
              "name", "com.hanhuy.android.protify.agent.internal.ProtifyActivity", Null)
            val activityExported = new PrefixedAttribute(androidPrefix,
              "exported", "false", activityName)
            val activityTheme = new PrefixedAttribute(androidPrefix,
              "theme", "@style/InternalProtifyDialogTheme", activityExported)
            val activityE = new Elem(null, "activity", activityTheme, TopScope,
              minimizeEmpty = true)

//            <activity android:name="com.hanhuy.android.protify.agent.internal.ProtifyActivity"
//                      android:exported="false"
//                      android:theme="@style/InternalProtifyDialogTheme"/>

            import com.hanhuy.android.protify.Intents
            val actionName0 = new PrefixedAttribute(androidPrefix,
              "name", Intents.PROTIFY_INTENT, Null)
            val actionName1 = new PrefixedAttribute(androidPrefix,
              "name", Intents.CLEAN_INTENT, Null)
            val actionName2 = new PrefixedAttribute(androidPrefix,
              "name", Intents.INSTALL_INTENT, Null)
            val intentFilterAction0 = new Elem(null, "action", actionName0, TopScope, minimizeEmpty = true)
            val intentFilterAction1 = new Elem(null, "action", actionName1, TopScope, minimizeEmpty = true)
            val intentFilterAction2 = new Elem(null, "action", actionName2, TopScope, minimizeEmpty = true)
            val intentFilter = new Elem(null, "intent-filter", Null, TopScope, minimizeEmpty = true,
              intentFilterAction0, intentFilterAction1, intentFilterAction2)
            val receiverName = new PrefixedAttribute(androidPrefix,
              "name", "com.hanhuy.android.protify.agent.internal.ProtifyReceiver", Null)
            val receiverPermission = new PrefixedAttribute(androidPrefix,
              "permission", "android.permission.INSTALL_PACKAGES", receiverName)
            val receiverExported = new PrefixedAttribute(androidPrefix,
              "exported", "true", receiverPermission)
            val receiverE = new Elem(null, "receiver", receiverExported, TopScope,
              minimizeEmpty = true, intentFilter)
//            <receiver android:name="com.hanhuy.android.protify.agent.internal.ProtifyReceiver"
//                      android:permission="android.permission.INSTALL_PACKAGES"
//                      android:exported="true">
//              <intent-filter>
//                <action android:name="com.hanhuy.android.protify.action.PROTIFY"/>
//              </intent-filter>
//            </receiver>
            Elem(prefix, "application", withNameAttr, scope, true, children :+ activityE :+ receiverE:_*)
          case x => x
        }
      }
      val root = XML.loadFile(processed)
      XML.save(processed.getAbsolutePath,
        new RuleTransformer(ApplicationTransform).apply(root))
      processed
    },
    collectResources <<= collectResources dependsOn (protifyPublicResources in Protify),
    cleanForR := {
      val ignores: Set[(Option[String],AttributeKey[_])] = Set(
        (None,               protifyLayout.key),
        (None,               protify.key),
        (Some(Protify.name), run.key),
        (Some(Protify.name), install.key)
      )
      implicit val o = outputLayout.value
      val l = projectLayout.value
      val d = (classDirectory in Compile).value
      val s = streams.value

      val roots = executionRoots.value map (r =>
        (r.scope.config.toOption.map(_.name),r.key))
      if (!roots.exists(ignores)) {
        FileFunction.cached(s.cacheDirectory / "clean-for-r",
          FilesInfo.hash, FilesInfo.exists) { in =>
          if (in.nonEmpty) {
            s.log.info("Rebuilding all classes because R.java has changed")
            IO.delete(d)
          }
          in
        }(Set(l.gen ** "R.java" get: _*))
      }
      Seq.empty[File]
    },
    cleanForR <<= cleanForR dependsOn rGenerator
  )))

  private val discoverThemes = Def.task {
    val androidJar = (platformJars in Android).value._1
    implicit val out = (outputLayout in Android).value
    val resPath = (projectLayout in Android).value.mergedRes
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
          "-n"   ::
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

  private[this] def doInstall(intent: String,
                              layout: ProjectLayout,
                              pkg: String,
                              res: File,
                              dexfile: Seq[File],
                              predexes: Seq[File],
                              st: sbt.Keys.TaskStreams)(implicit m: ProjectLayout => BuildOutput): IDevice => Unit = {
    val dexfiles = dexfile ++ predexes
    val dexfileHashes = dexfiles map (f => (f, Hash.toHex(Hash(f))))
    val cacheDirectory = st.cacheDirectory / "protify"
    val log = st.log
    import com.hanhuy.android.protify.Intents._

    dev => {
      import java.io.File.createTempFile

      val installHash = layout.protifyInstalledHash(dev)
      if (!installHash.isFile)
        android.Plugin.fail(s"Application has not been installed to ${dev.getSerialNumber}, android:install first")
      val installed = IO.readLines(installHash)
      val hashes = installed.map(_.split(":")(1)).toSet
      val topush = dexfileHashes.filterNot(d => hashes(d._2))

      val dexlist = topush map { case (p, _) =>
        val t = createTempFile("classes", ".dex")
        t.delete()
        (p, s"/sdcard/protify/$pkg/${t.getName}")
      }
      val restmp = createTempFile("resources", ".ap_")
      restmp.delete()
      val dexinfo = createTempFile("dex-info", ".txt")
      dexinfo.deleteOnExit()
      val cmdS =
        "am"   :: "broadcast"     ::
        "-a"   :: intent          ::
        "-e"   :: EXTRA_RESOURCES :: s"/sdcard/protify/$pkg/${restmp.getName}"  ::
        "-e"   :: EXTRA_DEX_INFO  :: s"/sdcard/protify/$pkg/${dexinfo.getName}" ::
        "-n"   ::
        s"$pkg/com.hanhuy.android.protify.agent.internal.ProtifyReceiver"       ::
        Nil


      IO.write(dexinfo, dexlist.map(d => d._2 + ":" + d._1.getName).mkString("\n"))

      log.debug("Executing: " + cmdS.mkString(" "))

      dev.executeShellCommand(s"rm -r /sdcard/protify/$pkg/*", new android.Commands.ShellResult)
      var pushres = false
      var pushdex = false
      FileFunction.cached(cacheDirectory / dev.safeSerial / "res", FilesInfo.lastModified) { in =>
        pushres = true
        in
      }(Set(res))
      pushdex = dexlist.nonEmpty
      val pushlen = (if (pushres) res.length else 0) + (if (pushdex) topush.map(_._1.length).sum else 0)
      if (pushres || pushdex) {
        android.Tasks.logRate(log, s"code deployed to ${dev.getSerialNumber}:", pushlen) {
          if (pushres)
            dev.pushFile(res.getAbsolutePath, s"/sdcard/protify/$pkg/${restmp.getName}")
          if (pushdex) {
            dev.pushFile(dexinfo.getAbsolutePath, s"/sdcard/protify/$pkg/${dexinfo.getName}")
            dexinfo.delete()
            dexlist.foreach { case (d, p) =>
              dev.pushFile(d.getAbsolutePath, p)
            }
          }
        }
        dev.executeShellCommand(cmdS.mkString(" "), new android.Commands.ShellResult)
      }
      // TODO remove old entries
      IO.writeLines(layout.protifyInstalledHash(dev),
        (topush map (t => t._1.getName + ":" + t._2)) ++ installed)
    }
  }

  val protifyTaskDef = Def.task {
    val res = (packageResources in Android).value
    val layout = (projectLayout in Android).value
    implicit val output = (outputLayout in Android).value
    val all = (allDevices in Android).value
    val sdk = (sdkPath in Android).value
    val pkg = (applicationId in Android).value
    val st = streams.value
    val dexfile = (dex in Protify).value * "*.dex" get
    val predexes = (predex in Protify).value flatMap (_._2 * "*.dex" get)

    import com.hanhuy.android.protify.Intents
    val execute = doInstall(Intents.PROTIFY_INTENT, layout, pkg, res, dexfile, predexes, st)
    import android.Commands

    if (all)
      Commands.deviceList(sdk, st.log).par foreach execute
    else
      Commands.targetDevice(sdk, st.log) foreach execute
  }
  val protifyInstallTaskDef = Def.task {
    val res = (packageResources in Android).value
    val layout = (projectLayout in Android).value
    implicit val output = (outputLayout in Android).value
    val all = (allDevices in Android).value
    val sdk = (sdkPath in Android).value
    val pkg = (applicationId in Android).value
    val st = streams.value
    val dexfile = (dex in Protify).value * "*.dex" get
    val predexes = (predex in Protify).value flatMap (_._2 * "*.dex" get)

    import com.hanhuy.android.protify.Intents
    val execute = doInstall(Intents.INSTALL_INTENT, layout, pkg, res, dexfile, predexes, st)
    import android.Commands

    if (all)
      Commands.deviceList(sdk, st.log).par foreach execute
    else
      Commands.targetDevice(sdk, st.log) foreach execute
  }
  val protifyRunTaskDef: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val k = (sdkPath in Android).value
    val l = (projectLayout in Android).value
    val p = (applicationId in Android).value
    val s = streams.value
    val all = (allDevices in Android).value
    val isLib = (libraryProject in Android).value
    implicit val output = (outputLayout in Android).value
    if (isLib)
      android.Plugin.fail("This project is not runnable, it has set 'libraryProject in Android := true")

    val r = Def.spaceDelimited().parsed
    val manifestXml = l.processedManifest
    import scala.xml.XML
    import android.Tasks.ANDROID_NS
    import android.Commands
    val m = XML.loadFile(manifestXml)
    // if an arg is specified, try to launch that
    (if (r.isEmpty) None else Some(r mkString " ")) orElse ((m \\ "activity") find {
      // runs the first-found activity
      a => (a \ "intent-filter") exists { filter =>
        val attrpath = "@{%s}name" format ANDROID_NS
        (filter \\ attrpath) exists (_.text == "android.intent.action.MAIN")
      }
    } map { activity =>
      val name = activity.attribute(ANDROID_NS, "name") get 0 text

      "%s/%s" format (p, if (name.indexOf(".") == -1) "." + name else name)
    }) match {
      case Some(intent) =>
        val receiver = new Commands.ShellLogging(l => s.log.info(l))
        def execute(d: IDevice): Unit = {
          val command = "am start -n %s" format intent
          s.log.info(s"Running on ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
          s.log.debug("Executing [%s]" format command)
          d.executeShellCommand(command, receiver)
          s.log.debug("run command executed")
        }
        if (all)
          Commands.deviceList(k, s.log).par foreach execute
        else
          Commands.targetDevice(k, s.log) foreach execute
      case None =>
        android.Plugin.fail(
          "No activity found with action 'android.intent.action.MAIN'")
    }

    ()
  } dependsOn install
  val protifyCleanTaskDef = Def.task {
    val st = streams.value
    val cacheDirectory = (streams in protify).value.cacheDirectory / "protify"
    val log = st.log
    val all = (allDevices in Android).value
    val sdk = (sdkPath in Android).value
    val pkg = (applicationId in Android).value
    implicit val out = (outputLayout in Android).value
    val layout = (projectLayout in Android).value
    layout.protifyPublicXml.delete()
    layout.rTxt.delete()
    import android.Commands
    import com.hanhuy.android.protify.Intents._
    def execute(dev: IDevice): Unit = {
      val cmdS =
        "am"   :: "broadcast"     ::
        "-a"   :: CLEAN_INTENT    ::
        "-n"   ::
        s"$pkg/com.hanhuy.android.protify.agent.internal.ProtifyReceiver" ::
        Nil

      log.debug("Executing: " + cmdS.mkString(" "))
      dev.executeShellCommand(s"rm -rf /sdcard/protify/$pkg", new Commands.ShellResult)
      FileFunction.cached(cacheDirectory / dev.safeSerial / "res", FilesInfo.lastModified) { in =>
        Set.empty
      }(Set.empty)
      FileFunction.cached(cacheDirectory / dev.safeSerial / "dex", FilesInfo.lastModified) { in =>
        Set.empty
      }(Set.empty)
      dev.executeShellCommand(cmdS.mkString(" "), new Commands.ShellResult)
      IO.copyFile(layout.protifyDexHash, layout.protifyInstalledHash(dev))
    }
    Try {
      if (all)
        Commands.deviceList(sdk, log).par foreach execute
      else
        Commands.targetDevice(sdk, log) foreach execute
    }
    ()
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
    val c         = (packageT in Compile).value
    val st        = streams.value
    Proguard.proguardInputs(
      u, pgOptions, pgConfig, l, d, p, x, c, s, pc, debug(), st)
  }

  private val proguardTaskDef = Def.task {
    implicit val out = (outputLayout   in Android).value
    val layout = (projectLayout        in Android).value
    val bldr   = (builder              in Android).value
    val l      = (libraryProject       in Android).value
    val debug  = (apkbuildDebug        in Android).value
    val ra     = (retrolambdaAggregate in Android).value
    val a      = proguardAggregate.value
    val inputs = proguardInputs.value
    val s      = streams.value
    Proguard.proguard(a, bldr, l, inputs, debug(), layout.proguardOut, ra, s)
  }
  val dexAggregateTaskDef = Def.task {
    Aggregate.Dex(
      dexInputs.value,
      (dexMaxHeap               in Android).value,
      true,
      file("/"), // pass a bogus file for main dex list, unused
      (dexMinimizeMain          in Android).value,
      (buildTools               in Android).value,
      (dexAdditionalParams      in Android).value)
  }
  private val dexInputsTaskDef = Def.task {
    implicit val out = (outputLayout     in Android).value
    val layout   = (projectLayout        in Android).value
    val ra       = (retrolambdaAggregate in Android).value
    val multiDex = (dexMulti             in Android).value
    val debug    = (apkbuildDebug        in Android).value
    val deps     = (dependencyClasspath  in ProtifyInternal).value
    val classJar = (packageT in Compile).value
    val pa       = proguardAggregate.value
    val in       = proguardInputs.value
    val progOut  = proguard.value
    val s        = streams.value
    Dex.dexInputs(progOut, in, pa, ra, multiDex, layout.dex, deps, classJar, debug(), s)
  }
  private val predexTaskDef = Def.task {
    implicit val output = (outputLayout in Android).value
    val layout = (projectLayout in Android).value
    val opts = dexAggregate.value
    val inputs = opts.inputs._2
    val classes = (packageT in Compile).value
    val pg = proguard.value
    val bldr = (builder in Android).value
    val s = streams.value
    Dex.predex(opts, inputs, true, false, classes, pg, bldr, layout.predex, s)
  }
  private val protifyDexAgentTaskDef = Def.task {
    implicit val out = (outputLayout in Android).value
    val layout  = (projectLayout  in Android).value
    val dcp     = (dependencyClasspath in Compile).value
    val agentJar = dcp.find {
      // oddly moduleID.key is not populated
      _.data.getName startsWith "com.hanhuy.android-protify-agent-"
    }.get.data
    val bldr    = (builder        in Android).value
    val lib     = (libraryProject in Android).value
    val bin     = layout.protifyDexAgent
    val debug   = (apkbuildDebug  in Android).value()
    val pg      = proguard.value
    val dexOpts = dexAggregate.value
    val s       = streams.value
    bin.mkdirs()

    Dex.dex(bldr, dexOpts.copy(multi = false, inputs = (false,agentJar :: Nil)),
      Nil, pg, true, lib, bin, false, debug, s)
  }
  private val protifyDexJarTaskDef = Def.task {
    implicit val out = (outputLayout in Android).value
    val layout = (projectLayout in Android).value

    val dx = (dex.value * "*.dex" get) map { f =>
      (f, s"protify-dex/${f.getName}")
    }
    val pd = predex.value.flatMap(_._2 * "*.dex" get) map { f =>
      val name = f.getParentFile.getName.dropRight(4) // ".jar"
      (f, s"protify-dex/$name.dex")
    }

    val prefixlen = "protify-dex/".length

    val hashes = (dx ++ pd) map { case (f, path) =>
      path.substring(prefixlen) + ":" + Hash.toHex(Hash(f))
    }

    IO.writeLines(layout.protifyDexHash, hashes)

    IO.jar(dx ++ pd, layout.protifyDexJar, new java.util.jar.Manifest)

    layout.protifyDexJar
  }
  private val dexTaskDef = Def.task {
    implicit val out = (outputLayout in Android).value
    val pd      = predex.value
    val layout  = (projectLayout  in Android).value
    val bldr    = (builder        in Android).value
    val lib     = (libraryProject in Android).value
    val bin     = layout.protifyDex
    val debug   = (apkbuildDebug  in Android).value()
    val legacy  = (dexLegacyMode in Android).value
    val pg      = proguard.value
    val dexOpts = dexAggregate.value
    val s       = streams.value
    bin.mkdirs()

    Dex.dex(bldr, dexOpts.copy(multi = true), pd, pg,
      !debug && legacy, lib, bin, debug, debug, s)
  }
  val dependencyClasspathTaskDef = Def.task {
    implicit val out = (outputLayout in Android).value
    val layout = (projectLayout in Android).value
    val cj = layout.classesJar
    val cp = (dependencyClasspath in Compile).value
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
    implicit val out = (outputLayout in Android).value
    val layout = (projectLayout in Android).value
    val rtxt = layout.gen / "R.txt"
    val public = layout.protifyPublicXml
    public.getParentFile.mkdirs()
    val idsfile = layout.protifyIdsXml
    idsfile.getParentFile.mkdirs()
    if (rtxt.isFile) {
      FileFunction.cached(streams.value.cacheDirectory / "public-xml", FilesInfo.lastModified) { in =>
        val values = (layout.mergedRes ** "values*" ** "*.xml").get
        import scala.xml._
        val allnames = values.flatMap { f =>
          if (f.getName == "public.xml") Nil
          else {
            val xml = XML.loadFile(f)
            xml.descendant flatMap { n =>
              n.attribute("name").map { a =>
                val nm = a.text
                (nm.replace('.', '_'), nm)
              }
            }
          }
        }.toMap

        streams.value.log.info("Maintaining resource ID consistency")
        val (publics, ids) = Using.fileReader(IO.utf8)(rtxt) { in =>
          IO.foldLines(in, (List("</resources>"), List("</resources>"))) { case ((xs, ys), line) =>
            val parts = line.split(" ")
            val cls = parts(1)
            val nm = allnames.getOrElse(parts(2), parts(2))
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

  implicit class ProtifyLayout (val layout: ProjectLayout)(implicit m: ProjectLayout => BuildOutput) {
    def protify = layout.intermediates / "protify"
    def protifyDex = protify / "dex"
    def protifyDexAgent = protify / "agent"
    def protifyDexJar = protify / "protify-dex.jar"
    def protifyDexHash = protify / "protify-dex-hash.txt"
    def protifyIdsXml = layout.generatedRes / "values" / "ids.xml"
    def protifyPublicXml = layout.mergedRes / "values" / "public.xml"
    def protifyInstalledHash(dev: IDevice) = {
      val path = protify / "installed" / dev.safeSerial
      path.getParentFile.mkdirs()
      path
    }
    def protifyAppInfoDescriptor = protify / "protify_application_info.txt"
    def protifyDescriptorJar = protify / "protify-descriptor.jar"
  }
  implicit class SafeIDevice (val device: IDevice) extends AnyVal {
    def safeSerial = URLEncoder.encode(device.getSerialNumber, "utf-8")
  }
}
