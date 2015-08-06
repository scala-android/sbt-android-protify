package com.hanhuy.android.protify

import android.content.{Intent, Context, BroadcastReceiver}
import com.hanhuy.android.common.Logcat

/**
 * @author pfnguyen
 */
object Receivers {
  val log = Logcat("Receivers")
  val DEX_INTENT = "com.hanhuy.android.protify.action.DEX"
  val LAYOUT_INTENT = "com.hanhuy.android.protify.action.LAYOUT"
  val EXTRA_LAYOUT = "com.hanhuy.android.protify.extra.LAYOUT"
  val EXTRA_DEX = "com.hanhuy.android.protify.extra.DEX"
  val EXTRA_CLASS = "com.hanhuy.android.protify.extra.CLASS"
  val EXTRA_RESOURCES = "com.hanhuy.android.protify.extra.RESOURCES"
}
class LayoutReceiver extends BroadcastReceiver {
  import Receivers._
  override def onReceive(context: Context, intent: Intent) = {
    log.v("Received intent: " + intent)
    for {
      intent    <- Option(intent)
      action    <- Option(intent.getAction) if action == LAYOUT_INTENT
      extras    <- Option(intent.getExtras)
      resources <- Option(extras.getString(EXTRA_RESOURCES))
      layout     = extras.getInt(EXTRA_LAYOUT, -1) if layout != -1
    } {
      log.v("Launching LayoutActivity " + System.currentTimeMillis)
      LayoutActivity.start(context, resources, layout)
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
