package android.protify

import java.io.File
import java.net.URLEncoder

import android.Keys.Internal._
import android.{Aggregate, BuildOutput, Dex}
import com.android.ddmlib.IDevice
import com.hanhuy.sbt.bintray.UpdateChecker
import sbt.Def.Initialize
import sbt._
import sbt.Keys._
import android.Keys._
import com.android.builder.packaging.PackagingUtils
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

object Keys {
  val protifyLayout = InputKey[Unit]("protify-layout", "prototype an android layout on device")
  val protify = TaskKey[Unit]("protify", "live-coding on-device")
  val Protify = config("protify") extend Compile

  @deprecated("use `enablePlugins(AndroidProtify)`", "1.4.0")
  def protifySettings: Seq[Setting[_]] = AndroidProtify.projectSettings
}
/**
 * @author pfnguyen
 */
object AndroidProtify extends AutoPlugin {
  override def requires = android.AndroidApp

  val autoImport = Keys

  import android.protify.Keys._
  import Internal._
  override def projectSettings = List(
    clean <<= clean dependsOn (clean in Protify),
    streams in update <<= (streams in update) dependsOn (protifyLibraryDependencies in Protify, protifyExtractAgent in Protify),
    protify <<= protifyTaskDef,
    protifyLayout <<= protifyLayoutTaskDef(),
    protifyLayout <<= protifyLayout dependsOn (packageResources in Protify, compile in Compile)
  ) ++ inConfig(Protify)(List(
    clean <<= protifyCleanTaskDef,
    // because Keys.install and debug are implicitly 'in Android' (1.5.5+)
    install in Protify <<= protifyInstallTaskDef,
    debug in Protify <<= protifyRunTaskDef(true),
    run <<= protifyRunTaskDef(false),
    protifyExtractAgent <<= protifyExtractAgentTaskDef,
    protifyDexAgent <<= protifyDexAgentTaskDef,
    protifyDexJar <<= protifyDexJarTaskDef,
    protifyLibraryDependencies <<= stableLibraryDependencies,
    protifyPublicResources <<= protifyPublicResourcesTaskDef,
    protifyLayouts <<= protifyLayoutsTaskDef storeAs protifyLayouts triggeredBy (compile in Compile),
    protifyThemes <<= discoverThemes storeAs protifyThemes triggeredBy (compile in Compile),
    protifyLayoutsAndThemes <<= (protifyLayouts,protifyThemes) map ((_,_)) storeAs protifyLayoutsAndThemes triggeredBy (compile in Compile),
    javacOptions := (javacOptions in Compile).value,
    scalacOptions := (scalacOptions in Compile).value,
    sourceGenerators <<= sourceGenerators in Compile,
    packageResources in Protify   := {
      val resapk = (packageResources in Android).value
      val layout = (projectLayout in Android).value
      implicit val out = (outputLayout in Android).value
      val s = streams.value
      FileFunction.cached(s.cacheDirectory / "protify-resapk",
          FilesInfo.hash, FilesInfo.exists) { in =>
        IO.delete(layout.protifyResApkTemp)
        layout.protifyResApkTemp.mkdirs()
        IO.unzip(resapk, layout.protifyResApkTemp)
        IO.delete(layout.protifyResApk)
        val mappings =
          ((PathFinder(layout.protifyResApkTemp) ** android.FileOnlyFilter)
            pair relativeTo(layout.protifyResApkTemp)) ++
          ((PathFinder(layout.mergedAssets) ** android.FileOnlyFilter)
            pair rebase(layout.mergedAssets, "assets"))
        Zip.resources(mappings, layout.protifyResApk)
        Set(layout.protifyResApk)
      }(Set(resapk) ++ (layout.mergedAssets ** android.FileOnlyFilter).get)
      layout.protifyResApk
    },
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
          case g: android.ProjectLayout.Gradle  => gradleLike
          case a: android.ProjectLayout.Ant     => antLike
          case w: android.ProjectLayout.Wrapped => sourcesFor(w.wrapped)
        }
        sourcesFor(layout)
      }
  ) ++ inConfig(Runtime)(Seq(
    dependencyClasspath :=
      dependencyClasspath.value.filterNot(a =>
      a.data.getParentFile.getName == "localAAR-protify-agent.aar" ||
      a.data.getName.startsWith("localAAR-protify-agent"))
  )) ++ inConfig(Android)(List(
    useProguardInDebug  := false,
    localAars           += {
      val layout = (projectLayout in Android).value
      implicit val out = (outputLayout in Android).value
      layout.protifyAgentAar
    },
    dexLegacyMode       := {
      val legacy = dexLegacyMode.value
      val debug = apkbuildDebug.value()
      !debug && legacy
    },
    dexShards           := true,
    dexAggregate        := {
      val opts = dexAggregate.value
      val debug = apkbuildDebug.value()
      val layout = (projectLayout in Android).value
      implicit val out = (outputLayout in Android).value
      if (!debug) {
        // always clean out dex on release builds (clear out shards/multi)
        (layout.dex * "*.dex" get) foreach (_.delete())
        opts
      } else opts.copy(multi = true, mainClassesConfig = file("/"))
    },
    proguardOptions := {
      val debug = apkbuildDebug.value()
      val options = proguardOptions.value
      if (debug) {
        List(
          "-keep class * extends android.app.Application { <init>(...); }"
        ) ++ options
      } else
        options
    },
    proguardConfig := proguardConfig.value filterNot (
      _ contains " com.hanhuy.android.protify.agent"),
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
          debug, apkDebugSigningConfig.value, processManifest.value, (protifyDexAgent in Protify).value, Nil,
          collectJni.value, resourceShrinker.value, minSdkVersion.value.toInt)

      } else Def.task {
        Aggregate.Apkbuild(packagingOptions.value,
          debug, apkDebugSigningConfig.value, processManifest.value, (dex in Android).value, predex.value,
          collectJni.value, resourceShrinker.value, minSdkVersion.value.toInt)

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
      android.Packaging.apkbuild(
        builder.value(s.log),
        android.Packaging.Jars(if (a.apkbuildDebug) Seq(Attributed.blank(dexjar)) else Nil, Nil, dcpAg),
        libraryProject.value,
        a,
        ndkAbiFilter.value.toSet,
        layout.collectJni,
        layout.resources,
        layout.collectResource,
        layout.mergedAssets,
        layout.unsignedApk(a.apkbuildDebug, n),
        logger,
        s)
    },
    install := {
      val all = allDevices.value
      implicit val output = outputLayout.value
      val layout = projectLayout.value
      val pkg = applicationId.value
      val s = streams.value
      install.value
      def installed(d: IDevice): Unit = {
        IO.copyFile(layout.protifyDexHash, layout.protifyInstalledHash(d))
      }

      if (all) android.Commands.deviceList(sdkPath.value, s.log) foreach installed
      else android.Commands.targetDevice(sdkPath.value, s.log) foreach installed
      ()
    },
    processManifest := {
      if (libraryProject.value)
        android.fail("protifySettings cannot be applied to libraryProject")
      val processed = processManifest.value
      if (apkbuildDebug.value()) {
        val pkg = packageForR.value
        val appInfoFile = appInfoDescriptor((resourceManaged in Protify).value)
        import scala.xml._
        import scala.xml.transform._
        object ApplicationTransform extends RewriteRule {

          import android.Resources.ANDROID_NS

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

              //                <activity android:name="com.hanhuy.android.protify.agent.internal.ProtifyActivity"
              //                          android:exported="false"
              //                          android:theme="@style/InternalProtifyDialogTheme"/>

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
          new RuleTransformer(ApplicationTransform).apply(root), "utf-8")
      }
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

  override def globalSettings = (onLoad := onLoad.value andThen { s =>
    Project.runTask(updateCheck in Keys.Protify, s).fold(s)(_._1)
  }) ::
    (updateCheck in Keys.Protify := {
      val log = streams.value.log

      UpdateChecker("pfn", "sbt-plugins", "sbt-android-protify") {
        case Left(t) =>
          log.debug("Failed to load version info: " + t)
        case Right((versions, current)) =>
          log.debug("available versions: " + versions)
          log.debug("current version: " + BuildInfo.version)
          log.debug("latest version: " + current)
          if (versions(BuildInfo.version)) {
            if (BuildInfo.version != current) {
              log.warn(
                s"UPDATE: A newer sbt-android-protify is available:" +
                  s" $current, currently running: ${BuildInfo.version}")
            }
          }
      }
    }) ::
    Nil

  type ResourceId = (String,Int)
  private[android] object Internal {
    val protifyExtractAgent = TaskKey[Unit]("internal-protify-extract-agent", "internal key: extract embedded protify agent aar")
    val protifyLibraryDependencies = TaskKey[Unit]("internal-protify-check-dependencies", "internal key: make sure libraryDependencies are stable")
    val protifyDexAgent = TaskKey[File]("internal-protify-dex-agent", "internal key: dex protify-agent.jar")
    val protifyDexJar = TaskKey[File]("internal-protify-dex-jar", "internal key: create a jar containing all dexes")
    val protifyPublicResources = TaskKey[Unit]("internal-protify-public-resources", "internal key: generate public.xml from R.txt")
    val protifyLayouts = TaskKey[Seq[ResourceId]]("internal-protify-layouts", "internal key: autodetected layout files")
    val protifyThemes = TaskKey[(Seq[ResourceId],Seq[ResourceId])]("internal-protify-themes", "internal key: platform themes, app themes")
    val protifyLayoutsAndThemes = TaskKey[(Seq[ResourceId],(Seq[ResourceId],Seq[ResourceId]))]("internal-protify-layouts-and-themes", "internal key: themes and layouts")
  }

  private[this] def appInfoDescriptor(target: File) =
    target / "protify_application_info.txt"

  val protifyExtractAgentTaskDef = Def.task {
    val layout = (projectLayout in Android).value
    implicit val out = (outputLayout in Android).value
    val buflen = 32768
    val buf = Array.ofDim[Byte](buflen)
    val url = android.Resources.resourceUrl("protify-agent.aar")
    val uc = url.openConnection()
    val lastMod = uc.getLastModified
    uc.getInputStream.close()
    if (layout.protifyAgentAar.lastModified < lastMod) {
      Using.urlInputStream(url) { in =>
        Using.fileOutputStream(false)(layout.protifyAgentAar) { out =>
          Iterator.continually(in.read(buf, 0, buflen)).takeWhile(_ != -1) foreach (out.write(buf, 0, _))
        }
      }
    }
  }

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
      val res = (packageResources in Protify).value
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
        android.fail("No layouts or themes cached, try again?")
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
            "-e"   :: EXTRA_RESOURCES :: s"/data/local/tmp/protify/${f.getName}"  ::
            "-e"   :: EXTRA_RTXT      :: s"/data/local/tmp/protify/${f2.getName}" ::
            "-e"   :: EXTRA_RTXT_HASH :: rTxtHash                         ::
            "--ez" :: EXTRA_APPCOMPAT :: isAppcompat                      ::
            "--ei" :: EXTRA_THEME     :: themeid                          ::
            "--ei" :: EXTRA_LAYOUT    :: resid                            ::
            "-n"   ::
            "com.hanhuy.android.protify/.LayoutReceiver"                  ::
            Nil

        log.debug("Executing: " + cmdS.mkString(" "))
        dev.executeShellCommand("rm -r /data/local/tmp/protify/*", new Commands.ShellResult)
        android.Tasks.logRate(log, s"resources deployed to ${dev.getSerialNumber}:", res.length + rTxt.length) {
          dev.pushFile(res.getAbsolutePath, s"/data/local/tmp/protify/${f.getName}")
          if (rTxt.isFile)
            dev.pushFile(rTxt.getAbsolutePath, s"/data/local/tmp/protify/${f2.getName}")
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
    val dexfiles = dexfile.map(f => (f,f.getName)) ++ predexes.map { f =>
      val name = f.getParentFile.getName.dropRight(4) // ".jar"
      (f, name + ".dex")
    }
    val dexfileHashes = dexfiles map (f => (f._1, Hash.toHex(Hash(f._1)), f._2))
    val cacheDirectory = st.cacheDirectory / "protify"
    val log = st.log
    import com.hanhuy.android.protify.Intents._

    dev => {
      import java.io.File.createTempFile


      val installHash = layout.protifyInstalledHash(dev)
      if (!installHash.isFile)
        android.fail(s"Application has not been installed to ${dev.getSerialNumber}, android:install first")
      val installed = IO.readLines(installHash)
      val hashes = installed.map(_.split(":")(1)).toSet
      val topush = dexfileHashes.filterNot(d => hashes(d._2))

      val devApi = Option(dev.getProperty(IDevice.PROP_BUILD_API_LEVEL)).flatMap(a => Try(a.toInt).toOption).getOrElse(0)
      val v21 = devApi >= 21
      val restopush = if (v21) { // device supports mSplitResDirs
        val SHARD_GOAL = 1000000 // 1MB res file goal
        val MAX_SHARDS = 50
        val shardTemplate = "protify-resources-%s.ap_"
        val tmp = layout.protifyResApkTemp
        val shardTmp = layout.protifyResApkShards / "shards"
        val shards = math.min(math.max(1, res.length / SHARD_GOAL), math.max(10, MAX_SHARDS))
        if (shards > 1) {

          val resourceShards = FileFunction.cached(st.cacheDirectory / "res-shards", FilesInfo.lastModified) { in =>
            shardTmp.mkdirs()
            val resources = tmp ** android.FileOnlyFilter get

            val assets = layout.mergedAssets ** android.FileOnlyFilter get

            val toShard = assets.map { a =>
              val name = a.relativeTo(layout.mergedAssets).fold(
                android.PluginFail(s"$a is not relative to ${layout.mergedAssets}"))(_.getName)
              val shardTarget = 1 + math.abs(name.hashCode % shards)
              (a, shardTmp / f"$shardTarget%02d" / "assets" / name)
            } ++ resources.map { r =>
              val name = r.relativeTo(tmp).fold(android.PluginFail(s"$r is not relative to $tmp"))(_.getPath)
              val shardTarget = 1 + math.abs(name.hashCode % shards)
              (r, shardTmp / f"$shardTarget%02d" / name)
            }

            IO.copy(toShard)

            val resShards = shardTmp * "*" get

            resShards.map { s =>
              val i = s.getName

              val mappings = ((PathFinder(s) ** android.FileOnlyFilter)
                pair relativeTo(s))
              val dest = layout.protifyResApkShards / (shardTemplate format i)
              Zip.resources(mappings, dest)
              dest
            }.toSet
          }(Set(res))
          val resShardHashes = resourceShards.map(r => (r,r.getName)).map (f => (f._1, Hash.toHex(Hash(f._1)), f._2))
          resShardHashes.toList.filterNot(d => hashes(d._2))
        } else {
          val resShardHashes = List(res).map(r => (r,r.getName)).map (f => (f._1, Hash.toHex(Hash(f._1)), f._2))
          resShardHashes.filterNot(d => hashes(d._2))
        }
      } else {
        Nil
      }

      val reslist = restopush map { case (p, _, n) =>
          val t = createTempFile("resources", ".ap_")
          t.delete()
        (p, s"/data/local/tmp/protify/$pkg/${t.getName}", n)
      }
      val dexlist = topush map { case (p, _, n) =>
        val t = createTempFile("classes", ".dex")
        t.delete()
        (p, s"/data/local/tmp/protify/$pkg/${t.getName}", n)
      }
      val restmp = createTempFile("resources", ".ap_")
      restmp.delete()
      val resinfo = createTempFile("res-info", ".txt")
      resinfo.deleteOnExit()
      val dexinfo = createTempFile("dex-info", ".txt")
      dexinfo.deleteOnExit()
      val cmdS =
        "am"   :: "broadcast"       ::
          "-a"   :: intent          ::
          "-e"   :: EXTRA_RESOURCES :: s"/data/local/tmp/protify/$pkg/${restmp.getName}"  ::
          "-e"   :: EXTRA_RES_INFO  :: s"/data/local/tmp/protify/$pkg/${resinfo.getName}" ::
          "-e"   :: EXTRA_DEX_INFO  :: s"/data/local/tmp/protify/$pkg/${dexinfo.getName}" ::
          "-n"   ::
          s"$pkg/com.hanhuy.android.protify.agent.internal.ProtifyReceiver"               ::
          Nil


      IO.write(dexinfo, dexlist.map(d => d._2 + ":" + d._3).mkString("\n"))
      IO.write(resinfo, reslist.map(d => d._2 + ":" + d._3).mkString("\n"))

      log.debug("Executing: " + cmdS.mkString(" "))

      dev.executeShellCommand(s"rm -r /data/local/tmp/protify/$pkg/*", new android.Commands.ShellResult)
      var pushres = false
      var pushdex = dexlist.nonEmpty
      FileFunction.cached(cacheDirectory / dev.safeSerial / "res", FilesInfo.lastModified) { in =>
        pushres = !v21
        in
      }(Set(res))
      val pushlen = (if (pushres) res.length else 0) +
        (if (pushdex) topush.map(_._1.length).sum else 0) +
        (if (restopush.nonEmpty) restopush.map(_._1.length).sum else 0)
      if (pushres || restopush.nonEmpty || pushdex) {
        android.Tasks.logRate(log, s"code deployed to ${dev.getSerialNumber}:", pushlen) {
          if (pushres) {
            val resdest = s"/data/local/tmp/protify/$pkg/${restmp.getName}"
            log.debug(s"Pushing ${res.getAbsolutePath} to $resdest")
            log.info(s"Sending ${res.getName}")
            dev.pushFile(res.getAbsolutePath, resdest)
          }
          if (restopush.nonEmpty) {
            dev.pushFile(resinfo.getAbsolutePath, s"/data/local/tmp/protify/$pkg/${resinfo.getName}")
            reslist.foreach { case (d, p, n) =>
              log.debug(s"Pushing ${d.getAbsolutePath} to $p")
              log.info(s"Sending ${d.getName}")
              dev.pushFile(d.getAbsolutePath, p)
            }
          }
          if (pushdex) {
            dev.pushFile(dexinfo.getAbsolutePath, s"/data/local/tmp/protify/$pkg/${dexinfo.getName}")
            dexlist.foreach { case (d, p, n) =>
              log.debug(s"Pushing ${d.getAbsolutePath} to $p")
              log.info(s"Sending ${d.getName}")
              dev.pushFile(d.getAbsolutePath, p)
            }
          }
        }
        dev.executeShellCommand(cmdS.mkString(" "), new android.Commands.ShellResult)
      }
      resinfo.delete()
      dexinfo.delete()

      val oldhashes = installed.map { i =>
        val split = i.split(":")
        (split(0),split(1))
      }.toMap

      val newhashes = (restopush.map { n => (n._3,n._2) } ++
        topush.map { n => (n._3,n._2) }).toMap

      IO.writeLines(layout.protifyInstalledHash(dev),
        oldhashes ++ newhashes map { case (k,v) => s"$k:$v" } toList)
    }
  }

  val protifyTaskDef = Def.task {
    val res = (packageResources in Protify).value
    val layout = (projectLayout in Android).value
    implicit val output = (outputLayout in Android).value
    val all = (allDevices in Android).value
    val sdk = (sdkPath in Android).value
    val pkg = (applicationId in Android).value
    val st = streams.value
    val dexfile = (dex in Android).value * "*.dex" get
    val predexes = (predex in Android).value flatMap (_._2 * "*.dex" get)

    import com.hanhuy.android.protify.Intents
    val execute = doInstall(Intents.PROTIFY_INTENT, layout, pkg, res, dexfile, predexes, st)
    import android.Commands

    if (all)
      Commands.deviceList(sdk, st.log).par foreach execute
    else
      Commands.targetDevice(sdk, st.log) foreach execute
  }
  val protifyInstallTaskDef = Def.task {
    val res = (packageResources in Protify).value
    val layout = (projectLayout in Android).value
    implicit val output = (outputLayout in Android).value
    val all = (allDevices in Android).value
    val sdk = (sdkPath in Android).value
    val pkg = (applicationId in Android).value
    val st = streams.value
    val dexfile = (dex in Android).value * "*.dex" get
    val predexes = (predex in Android).value flatMap (_._2 * "*.dex" get)

    import com.hanhuy.android.protify.Intents
    val execute = doInstall(Intents.INSTALL_INTENT, layout, pkg, res, dexfile, predexes, st)
    import android.Commands

    if (all)
      Commands.deviceList(sdk, st.log).par foreach execute
    else
      Commands.targetDevice(sdk, st.log) foreach execute
  }
  def protifyRunTaskDef(debug: Boolean): Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val k = (sdkPath in Android).value
    val l = (projectLayout in Android).value
    val p = (applicationId in Android).value
    val s = streams.value
    val all = (allDevices in Android).value
    val isLib = (libraryProject in Android).value
    implicit val output = (outputLayout in Android).value
    if (isLib)
      android.fail("This project is not runnable, it has set 'libraryProject in Android := true")

    val manifestXml = l.processedManifest
    import scala.xml.XML
    import android.Resources.ANDROID_NS
    import android.Commands
    val m = XML.loadFile(manifestXml)
    // if an arg is specified, try to launch that
    android.parsers.activityParser.parsed orElse ((m \\ "activity") find {
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
        val command = "am start %s -n %s" format (if (debug) "-D" else "", intent)
        def execute(d: IDevice): Unit = {
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
        android.fail(
          "No activity found with action 'android.intent.action.MAIN'")
    }

    ()
  } dependsOn (install in Protify)
  val protifyCleanTaskDef = Def.task {
    if (apkbuildDebug.value()) {
      val st = streams.value
      val cacheDirectory = (streams in protify).value.cacheDirectory / "protify"
      val log = st.log
      val all = (allDevices in Android).value
      val sdk = (sdkPath in Android).value
      val pkg = (applicationId in Android).value
      implicit val out = (outputLayout in Android).value
      val layout = (projectLayout in Android).value
      IO.delete(layout.protifyResApkTemp)
      IO.delete(layout.protifyResApkShards)
      layout.protifyResApk.delete()
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
        dev.executeShellCommand(s"rm -r /data/local/tmp/protify/$pkg", new Commands.ShellResult)
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
    }
    ()
  }
  def discoverActivityProxies(analysis: inc.Analysis): Seq[String] =
    Discovery(Set("com.hanhuy.android.protify.ActivityProxy"), Set.empty)(Tests.allDefs(analysis)).collect({
      case (definition, discovered) if !definition.modifiers.isAbstract &&
        discovered.baseClasses("com.hanhuy.android.protify.ActivityProxy") =>
        definition.name }).sorted

  private val protifyDexAgentTaskDef = Def.task {
    implicit val out = (outputLayout in Android).value
    val layout  = (projectLayout  in Android).value
    val u = (unmanagedJars in Compile).value
    val agentJar = u.find(a =>
      a.data.getParentFile.getName == "localAAR-protify-agent.aar" ||
      a.data.getName.startsWith("localAAR-protify-agent")).get.data
    val bldr    = (builder        in Android).value
    val lib     = (libraryProject in Android).value
    val bin     = layout.protifyDexAgent
    val debug   = (apkbuildDebug  in Android).value()
    val s       = streams.value
    bin.mkdirs()

    val f = File.createTempFile("fake-maindex", ".lst")
    f.deleteOnExit()
    val dexOpts = Aggregate.Dex(
      (false,agentJar :: Nil),
      (dexMaxHeap               in Android).value,
      (dexMaxProcessCount       in Android).value,
      true, // enable multidex so that dexInProcess works correctly
      f, // pass a bogus file for main dex list, unused
      (dexMinimizeMain          in Android).value,
      (dexInProcess             in Android).value,
      (buildToolInfo            in Android).value,
      (dexAdditionalParams      in Android).value)
    val d = Dex.dex(bldr(s.log), dexOpts, Nil, None, true, lib, bin, false, debug, s)
    f.delete()
    d
  }
  private val protifyDexJarTaskDef = Def.task {
    implicit val out = (outputLayout in Android).value
    val layout = (projectLayout in Android).value

    val dx = ((dex in Android).value * "*.dex" get) map { f =>
      (f, s"protify-dex/${f.getName}")
    }
    val enumRe = """classes(\d+).dex""".r
    val pd = (predex in Android).value.flatMap(_._2 * "*.dex" get) map { f =>
      val name = f.getParentFile.getName.dropRight(4) // ".jar"
    val ext = f.getName match {
      case enumRe(num) ⇒ s"_$num"
      case _ ⇒ ""
    }
      (f, s"protify-dex/$name$ext.dex")
    }

    // must check FilesInfo.hash because shardedDex copies into final location
    FileFunction.cached(streams.value.cacheDirectory / "protify-dex.jar", FilesInfo.hash) { in =>
      val prefixlen = "protify-dex/".length
      val hashes = (dx ++ pd) map { case (f, path) =>
        path.substring(prefixlen) + ":" + Hash.toHex(Hash(f))
      }
      IO.writeLines(layout.protifyDexHash, hashes)
      IO.jar(dx ++ pd, layout.protifyDexJar, new java.util.jar.Manifest)
      Set(layout.protifyDexJar, layout.protifyDexHash)
    }((dx ++ pd).map(_._1).toSet)

    layout.protifyDexJar
  }

  val stableLibraryDependencies = Def.taskDyn {
    val libcheckdir = streams.value.cacheDirectory / "protify-libcheck"
    val libcheck = (libcheckdir * "*").get.headOption.map(_.getName)
    val moduleHash = Hash.toHex(Hash((libraryDependencies in Scope(This,This,This,This)).value.mkString(";")))
    if (libcheck exists (_ != moduleHash)) Def.task {
      streams.value.log.warn("libraryDependencies have changed, forcing clean build")
      val _ = (clean in Compile).value
    } else Def.task {
      libcheckdir.mkdirs()
      IO.touch(libcheckdir / moduleHash)
    }
  }
  val protifyPublicResourcesTaskDef = Def.task {
    val tools = android.Keys.Internal.buildToolInfo.value
    if (tools.getRevision.getMajor < 24) {
      implicit val out = (outputLayout in Android).value
      val layout = (projectLayout in Android).value
      val rtxt = layout.gen / "R.txt"
      val public = layout.protifyPublicXml
      public.getParentFile.mkdirs()
      val idsfile = layout.protifyIdsXml
      idsfile.getParentFile.mkdirs()
      if (rtxt.isFile) {
        FileFunction.cached(streams.value.cacheDirectory / "public-xml", FilesInfo.hash) { in =>
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
              val nm = allnames.getOrElse(parts(2), parts(2)).trim
              val value = parts(3)
              if ("styleable" != cls)
                (if ("id" != cls || !nm.startsWith("Id.")) s"""  <public type="$cls" name="$nm" id="$value"/>""" :: xs else xs,
                  if ("id" == cls && !nm.startsWith("Id.")) s"""  <item type="id" name="$nm"/>""" :: ys else ys)
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
    }
    ()
  }

  implicit class ProtifyLayout (val layout: ProjectLayout)(implicit m: ProjectLayout => BuildOutput) {
    def protify = layout.intermediates / "protify"
    def protifyDex = protify / "dex"
    def protifyDexAgent = protify / "agent"
    def protifyAgentAar = protify / "protify-agent.aar"
    def protifyDexJar = protify / "protify-dex.jar"
    def protifyDexHash = protify / "protify-dex-hash.txt"
    def protifyIdsXml = layout.generatedRes / "values" / "protify-ids.xml"
    def protifyPublicXml = layout.mergedRes / "values" / "protify-public.xml"
    def protifyInstalledHash(dev: IDevice) = {
      val path = protify / "installed" / dev.safeSerial
      path.getParentFile.mkdirs()
      path
    }
    def protifyResApkShards = protify / "resapk-shards"
    def protifyResApkTemp = protify / "resapk-unpacked"
    def protifyResApk = protify / "protify-resources.ap_"
    def protifyAppInfoDescriptor = protify / "protify_application_info.txt"
    def protifyDescriptorJar = protify / "protify-descriptor.jar"
  }
  implicit class SafeIDevice (val device: IDevice) extends AnyVal {
    def safeSerial = URLEncoder.encode(device.getSerialNumber, "utf-8")
  }

  object Zip {
    // copied from IO.zip and its implementations
    import java.util.zip._

    def resources(sources: Traversable[(File, String)], outputZip: File): Unit =
      archive(sources.toSeq, outputZip)

    private def archive(sources: Seq[(File, String)], outputFile: File) {
      val defaultNoCompress = PackagingUtils.getDefaultNoCompressPredicate
      val noCompress = (s: String) => s.startsWith("res/raw/") || defaultNoCompress(s)
      if (outputFile.isDirectory)
        sys.error("Specified output file " + outputFile + " is a directory.")
      else {
        val outputDir = outputFile.getParentFile
        IO.createDirectory(outputDir)
        withZipOutput(outputFile) { output =>
          val createEntry: (String => ZipEntry) = name => {
            val ze = new ZipEntry(name)
            ze.setMethod(if (noCompress(name)) ZipEntry.STORED else ZipEntry.DEFLATED)
            ze
          }
          writeZip(sources, output)(createEntry)
        }
      }
    }
    private def writeZip(sources: Seq[(File, String)], output: ZipOutputStream)(createEntry: String => ZipEntry) {
      val files = sources.flatMap { case (file, name) => if (file.isFile) (file, normalizeName(name)) :: Nil else Nil }

      val now = System.currentTimeMillis
      // The CRC32 for an empty value, needed to store directories in zip files
      val emptyCRC = new CRC32().getValue

      def makeFileEntry(file: File, name: String, crc: Long) = {
        //			log.debug("\tAdding " + file + " as " + name + " ...")
        val e = createEntry(name)
        e setTime file.lastModified
        if (e.getMethod == ZipEntry.STORED) {
          e.setSize(file.length)
          e.setCrc(crc)
        }
        e
      }
      def addFileEntry(file: File, name: String) {
        val baos = new java.io.ByteArrayOutputStream(file.length.toInt)
        IO.transfer(file, baos)
        val bs = baos.toByteArray
        val crc = new CRC32()
        crc.update(bs)

        output putNextEntry makeFileEntry(file, name, crc.getValue)
        val bais = new java.io.ByteArrayInputStream(bs)
        IO.transfer(bais, output)
        output.closeEntry()
      }

      //Add all files to the generated Zip
      files foreach { case (file, name) => addFileEntry(file, name) }
    }
    private def withZipOutput(file: File)(f: ZipOutputStream => Unit) {
      Using.fileOutputStream(false)(file) { fileOut =>
        val (zipOut, ext) = (new ZipOutputStream(fileOut), "zip")
        try {
          f(zipOut)
        }
        finally {
          zipOut.close()
        }
      }
    }
    private def normalizeName(name: String) = {
      val sep = File.separatorChar
      if (sep == '/') name else name.replace(sep, '/')
    }
  }
}
