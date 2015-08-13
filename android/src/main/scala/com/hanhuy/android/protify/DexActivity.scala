package com.hanhuy.android.protify

import android.app.{ProgressDialog, Activity}
import android.content.{Intent, Context}
import android.os.{Build, Bundle}
import android.support.v7.app.AppCompatActivity
import android.util.AttributeSet
import android.view.{View, MenuItem, Menu}

import Intents._
import android.widget.Toast
import com.hanhuy.android.common.Logcat
import dalvik.system.DexClassLoader

/**
 * @author pfnguyen
 */
object DexActivity {
  def start(ctx: Context) = {
    proxy foreach { case (p,a) => p.onProxyUnload(a) }
    proxy = None
    val intent = new Intent(ctx, if (DexArguments.appcompat)
      classOf[AppCompatDexActivity] else classOf[DexActivity])
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
  }

  var proxy = Option.empty[(ActivityProxy, Activity)]
}

object DexArguments {
  var resources = ""
  var dex       = ""
  var proxy     = ""
  var appcompat = false
}

trait ExternalDexLoader extends Activity with ExternalResourceLoader {
  val log2 = Logcat("ExternalDexLoader")

  override def resPath = Some(DexArguments.resources)

  private[this] var proxy = Option.empty[ActivityProxy]

  lazy val loader = {
    val cache = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
      getCodeCacheDir
    else
      getDir("dex", Context.MODE_PRIVATE)
    log2.v("Loading dex: " + System.currentTimeMillis)
    val cl = new DexClassLoader(DexArguments.dex, cache.getAbsolutePath, null, getClassLoader)
    log2.v("Loaded dex: " + System.currentTimeMillis)
    cl
  }

  override def onNewIntent(intent: Intent) = {
    super.onNewIntent(intent)
    log2.v("Re-launching")
    recreate()
  }

  override def onCreate(savedInstanceState: Bundle) = {
    val clazz = loader.loadClass(DexArguments.proxy)
    try {
      proxy = Some(clazz.newInstance.asInstanceOf[ActivityProxy])
      DexActivity.proxy = proxy map (_ -> this)
    } catch {
      case ex: Exception =>
        Toast.makeText(this,
          "Unable to load proxy: " + ex.getMessage, Toast.LENGTH_LONG).show()
        log2.w(ex.getMessage, ex)
        finish()
    }

    proxy foreach (_.onProxyLoad(this))
    super.onCreate(savedInstanceState)
    proxy foreach (_.onCreate(this, savedInstanceState))
  }
  override def onDestroy() = {
    proxy foreach (_.onDestroy(this))
    super.onDestroy()
    proxy foreach (_.onProxyUnload(this))
    DexActivity.proxy = None
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

  override def onCreateView(name: String, context: Context, attrs: AttributeSet) = try {
    super.onCreateView(name, context, attrs)
  } catch {
    case e: Exception => null
  }


  override def onCreateView(parent: View, name: String, context: Context, attrs: AttributeSet) = try {
    super.onCreateView(parent, name, context, attrs)
  } catch {
    case e: Exception => null
  }
}

class DexActivity extends Activity with ExternalDexLoader with ViewServerSupport
class AppCompatDexActivity extends AppCompatActivity with ExternalDexLoader with ViewServerSupport
