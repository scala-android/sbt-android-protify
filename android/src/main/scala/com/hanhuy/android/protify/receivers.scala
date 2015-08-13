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
  lazy val rtxtloader = new RTxtLoader()
}
class LayoutReceiver extends BroadcastReceiver {
  import Receivers._
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
      LayoutArguments.appcompat = extras.getBoolean(EXTRA_APPCOMPAT, false)

      log.v("Loading R.txt " + System.currentTimeMillis)
      rtxtloader.load(extras.getString(EXTRA_RTXT), extras.getString(EXTRA_RTXT_HASH))
      log.v("Done loading R.txt " + System.currentTimeMillis)

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
