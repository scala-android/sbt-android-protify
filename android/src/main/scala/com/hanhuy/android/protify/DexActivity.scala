package com.hanhuy.android.protify

import android.app.Activity
import android.content.{Intent, Context}
import android.os.{Build, Bundle}
import android.view.{MenuItem, Menu}

import Receivers._
import android.widget.Toast
import com.hanhuy.android.common.Logcat
import dalvik.system.DexClassLoader

import scala.util.{Failure, Success, Try}

/**
 * @author pfnguyen
 */
object DexActivity {
  def start(ctx: Context, dex: String, res: String, cls: String) = {
    val intent = new Intent(ctx, classOf[DexActivity])
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.putExtra(EXTRA_DEX, dex)
    intent.putExtra(EXTRA_RESOURCES, res)
    intent.putExtra(EXTRA_CLASS, cls)
    ctx.startActivity(intent)
  }
}

trait ExternalDexLoader extends Activity with ExternalResourceLoader {
  val log2 = Logcat("ExternalDexLoader")

  def arguments = for {
    intent    <- Option(getIntent)
    extras    <- Option(intent.getExtras)
    dex       <- Option(extras.getString(EXTRA_DEX))
    resources <- Option(extras.getString(EXTRA_RESOURCES))
    cls       <- Option(extras.getString(EXTRA_CLASS))
  } yield (dex, resources, cls)
  override def resPath = arguments map (_._2)

  private[this] var proxy = Option.empty[ActivityProxy]

  override def onCreate(savedInstanceState: Bundle) = {
    if (arguments.isEmpty) {
      Toast.makeText(this, "No DEX to load", Toast.LENGTH_LONG).show()
      finish()
    }
    arguments foreach { case ((dex, res, cls)) =>
      val cache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        getCodeCacheDir
      else
        getDir("dex", Context.MODE_PRIVATE)
      val cl = new DexClassLoader(dex, cache.getAbsolutePath, null, getClassLoader)
      val clazz = cl.loadClass(cls)
      try {
        proxy = Some(clazz.newInstance.asInstanceOf[ActivityProxy])
      } catch {
        case ex: Exception =>
          Toast.makeText(this,
            "Unable to load proxy: " + ex.getMessage, Toast.LENGTH_LONG).show()
          log2.w(ex.getMessage, ex)
          finish()
      }
    }

    proxy foreach (_.onPreCreate(this, savedInstanceState))
    super.onCreate(savedInstanceState)
    proxy foreach (_.onCreate(this, savedInstanceState))
  }
  override def onDestroy() = {
    proxy foreach (_.onDestroy(this))
    super.onDestroy()
  }
  override def onPause() = {
    proxy foreach (_.onPause(this))
    super.onPause()
  }
  override def onResume() = {
    proxy foreach (_.onResume(this))
    super.onResume()
  }
  override def onStart() = {
    proxy foreach (_.onStart(this))
    super.onStart()
  }
  override def onStop() = {
    proxy foreach (_.onStop(this))
    super.onStop()
  }
  override def onCreateOptionsMenu(menu: Menu) = {
    proxy foreach (_.onCreateOptionsMenu(this, menu))
    super.onCreateOptionsMenu(menu)
  }

  override def onOptionsItemSelected(item: MenuItem) = {
    proxy.fold(super.onOptionsItemSelected(item)) (
      _.onOptionsItemSelected(this, item)) || super.onOptionsItemSelected(item)
  }
}

class DexActivity extends Activity with ExternalDexLoader
