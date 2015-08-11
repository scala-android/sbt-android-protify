package com.hanhuy.android.protify

import java.io._
import java.lang.reflect.Field

import android.content.{Intent, Context, BroadcastReceiver}
import com.hanhuy.android.common.Logcat

import Intents._
import com.hanhuy.android.common.ManagedResource.ResourceManager

/**
 * @author pfnguyen
 */
object Receivers {
  val log = Logcat("Receivers")
}
class LayoutReceiver extends BroadcastReceiver {
  override def onReceive(context: Context, intent: Intent) = {
    for {
      intent    <- Option(intent)
      action    <- Option(intent.getAction) if action == LAYOUT_INTENT
      extras    <- Option(intent.getExtras)
      resources <- Option(extras.getString(EXTRA_RESOURCES))
      layout     = extras.getInt(EXTRA_LAYOUT, -1) if layout != -1
    } {
      val theme = extras.getInt(EXTRA_THEME, 0)

      LayoutArguments.resources = Some(resources)
      LayoutArguments.layout    = Some(layout)
      LayoutArguments.theme     = if (theme == 0) None else Some(theme)
//      LayoutArguments.appcompat = extras.getBoolean(EXTRA_APPCOMPAT, false)

//      RTxtParser(Option(extras.getString(EXTRA_RTXT)),
//        Option(extras.getString(EXTRA_RTXT_HASH)))

      LayoutActivity.start(context)
    }
  }
}

class DexReceiver extends BroadcastReceiver {
  import Receivers._
  override def onReceive(context: Context, intent: Intent) = {
    log.v("Received intent: " + intent)
    for {
      intent    <- Option(intent)
      action    <- Option(intent.getAction) if action == DEX_INTENT
      extras    <- Option(intent.getExtras)
      resources <- Option(extras.getString(EXTRA_RESOURCES))
      dex       <- Option(extras.getString(EXTRA_DEX))
      cls       <- Option(extras.getString(EXTRA_CLASS))
    } {
      log.v("Launching DexActivity " + System.currentTimeMillis)
      DexActivity.start(context, dex, resources, cls)
    }
  }
}

object RTxtParser {
  val log = Logcat("RTxtParser")
  val appcompatR = "android.support.v7.appcompat.R$"
  val designR = "android.support.design.R$"
  implicit val readerManager = new ResourceManager[Reader] {
    override def dispose(resource: Reader) = resource.close()
  }
  import com.hanhuy.android.common.ManagedResource._
  var lasthash = Option.empty[String]
  var fieldCache = Map.empty[String,Map[String,Array[Field]]]
  def apply(rtxt: Option[String], hash: Option[String]) = {
    if (lasthash != hash) {
      rtxt foreach { r =>
        val f = new File(r)
        if (f.isFile) {
          for { in <- using(new BufferedReader(new InputStreamReader(new FileInputStream(f), "utf-8"))) } {
            fieldCache = Stream.continually(in.readLine).takeWhile(_ != null).foldLeft(fieldCache) { (fields, line) =>
              val parts = line.split(" ")
              parts(0) match {
                case "int" =>
                  val clazz = parts(1)
                  val name = parts(2)
                  val value = parts(3)
                  val f = fieldsFor(clazz, fields)
                  log.v(System.currentTimeMillis + " Setting int field: " + name)
                  f(clazz).get(name).foreach(_.foreach(_.setInt(null, asInt(value))))
                  f
                case "int[]" =>
                  val clazz = parts(1)
                  val name = parts(2)
                  val value = parts.slice(4, parts.length-1).map(v => asInt(v.replaceAll(",","")))
                  val f = fieldsFor(clazz, fields)
                  log.v(System.currentTimeMillis + " Setting int[] field: " + name)
                  f(clazz).get(name).foreach(_.foreach(_.set(null, value)))
                  f
              }
            }
          }
        }
      }

    }
    lasthash = hash
  }

  def fieldsFor(cls: String, cache: Map[String,Map[String,Array[Field]]]): Map[String,Map[String,Array[Field]]] = {
    val appcompat = Class.forName(appcompatR + cls).getDeclaredFields
    val design = Class.forName(designR + cls).getDeclaredFields
    val f = (appcompat ++ design).groupBy(_.getName)
    if (cache.contains(cls)) cache
    else cache + ((cls, f))
  }

  def asInt(s: String): Int = {
    if (s.startsWith("0x")) Integer.parseInt(s.substring(2), 16)
    else Integer.parseInt(s)
  }
}
