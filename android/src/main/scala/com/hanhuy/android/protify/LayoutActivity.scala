package com.hanhuy.android.protify

import android.app.Activity
import android.content.{Intent, Context}
import android.content.res.{AssetManager, Resources}
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater

import Receivers._
import android.widget.Toast
import com.hanhuy.android.common.Logcat

/**
 * @author pfnguyen
 */
object LayoutActivity {
  def start(ctx: Context, res: String, layout: Int) = {
    val intent = new Intent(ctx, classOf[LayoutActivity])
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.putExtra(EXTRA_RESOURCES, res)
    intent.putExtra(EXTRA_LAYOUT, layout)
    ctx.startActivity(intent)
  }
}

trait LayoutActivityArguments extends Activity with ExternalResourceLoader {
  def arguments = for {
    intent    <- Option(getIntent)
    extras    <- Option(intent.getExtras)
    resources <- Option(extras.getString(EXTRA_RESOURCES))
    layout     = extras.getInt(EXTRA_LAYOUT, -1) if layout != -1
  } yield (resources, layout)

  def resPath = arguments map (_._1)
  def layoutRes = arguments map (_._2)
  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    log.v("Activity starting: " + System.currentTimeMillis)
    if (layoutRes.isEmpty) {
      Toast.makeText(this, "No resources to render", Toast.LENGTH_LONG).show()
      log.v("No resources found", new Exception("No resources"))
      finish()
    }
    layoutRes foreach { layout =>
      try {
        log.v("Before content set: " + System.currentTimeMillis)
        setContentView(layout)
        log.v("Content set: " + System.currentTimeMillis)
      } catch { case e: Exception =>
        Toast.makeText(this, f"Unable to load requested layout 0x$layout%08x", Toast.LENGTH_LONG).show()
        finish()
      }
    }
  }
}
trait ExternalResourceLoader extends Activity {
  val log = Logcat("ExternalResourceLoader")

  private type PrivateAssetManager = {
    def addAssetPath(s: java.lang.String): Unit
  }

  def resPath: Option[String]

  lazy val resources = {
    val oldres = super.getResources
    resPath match {
      case Some(res) =>
        val f = new java.io.File(res)
        if (!f.exists) {
          Toast.makeText(this, "Resources file does not exist: " + f, Toast.LENGTH_LONG).show()
          oldres
        } else {
          val am = classOf[AssetManager].newInstance
          am.asInstanceOf[PrivateAssetManager].addAssetPath(f.getAbsolutePath)
          new Resources(am, oldres.getDisplayMetrics, oldres.getConfiguration)
        }
      case None =>
        oldres
    }
  }

  override def getResources = if (resPath.isEmpty) super.getResources else resources
  override def getLayoutInflater = LayoutInflater.from(this)
}
// currently does not work at all due to mismatch of R and injected resources
// custom themes that do not depend on AppCompat /may/ work
class AppCompatLayoutActivity extends AppCompatActivity with LayoutActivityArguments {
  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    setTheme(R.style.Theme_AppCompat_Light)
  }
}
class LayoutActivity extends Activity with LayoutActivityArguments
