package com.hanhuy.android.protify

import android.app.Activity
import android.content.{Intent, Context}
import android.content.res.{Configuration, AssetManager, Resources}
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.{TypedValue, AttributeSet, DisplayMetrics}
import android.view.LayoutInflater

import android.widget.Toast
import com.android.debug.hv.ViewServer
import com.hanhuy.android.common.Logcat

/**
 * @author pfnguyen
 */
object LayoutArguments {
  var resources = Option.empty[String]
  var layout    = Option.empty[Int]
  var theme     = Option.empty[Int]
  var appcompat = false
}

object LayoutActivity {
  val log = Logcat("LayoutActivity")
  def start(ctx: Context) = {
    if (LayoutArguments.appcompat)
      log.v("Launching AppCompatLayoutActivity")

    val intent = new Intent(ctx, if (LayoutArguments.appcompat)
      classOf[AppCompatLayoutActivity] else classOf[LayoutActivity])
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
  }
}

trait LayoutActivityArguments extends Activity with ExternalResourceLoader {
  def resPath = LayoutArguments.resources
  def layoutRes = LayoutArguments.layout

  var lastTheme = Option.empty[Int]

  override def onNewIntent(intent: Intent) = {
    super.onNewIntent(intent)
    log.v("Re-launching")
    recreate()
  }

  override def onCreate(savedInstanceState: Bundle) = {
    LayoutArguments.theme foreach setTheme
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
        lastTheme = LayoutArguments.theme
        setContentView(layout)
        log.v("Content set: " + System.currentTimeMillis)
      } catch { case e: Exception =>
        val res = resources
        log.w(f"Unable to load requested layout 0x$layout%08x", e)
        Toast.makeText(this, f"Unable to load requested layout 0x$layout%08x: " + e.getMessage, Toast.LENGTH_LONG).show()
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

  private[this] var resourcesCache = Option.empty[Resources]

  def resources = resourcesCache getOrElse {
    val oldres = super.getResources
    resPath match {
      case Some(res) =>
        val f = new java.io.File(res)
        if (!f.exists) {
          log.w("Resources file does not exist: " + f)
          oldres
        } else {
          val am = classOf[AssetManager].newInstance
          am.asInstanceOf[PrivateAssetManager].addAssetPath(f.getAbsolutePath)
          log.v("Loaded resources from: " + res)
          val r = new ResourcesWrapper(am, oldres.getDisplayMetrics, oldres.getConfiguration, oldres)
          resourcesCache = Some(r)
          r
        }
      case None =>
        oldres
    }
  }

  override def getResources = if (resPath.isEmpty) {
    log.w("No resources available yet", new Exception("not ready"))
    super.getResources
  } else resources
  override def getLayoutInflater = LayoutInflater.from(this)
}
// currently does not work at all due to mismatch of R and injected resources
// custom themes that do not depend on AppCompat /may/ work
class AppCompatLayoutActivity extends AppCompatActivity with LayoutActivityArguments with ViewServerSupport
class LayoutActivity extends Activity with LayoutActivityArguments with ViewServerSupport

class ResourcesWrapper(am: AssetManager, dm: DisplayMetrics, c: Configuration, res: Resources) extends Resources(am, dm, c) {
  override def getIntArray(id: Int) = try {
    super.getIntArray(id)
  } catch {
    case e: Resources.NotFoundException => res.getIntArray(id)
  }

  override def getValue(id: Int, outValue: TypedValue, resolveRefs: Boolean) = try {
    super.getValue(id, outValue, resolveRefs)
  } catch {
    case e: Resources.NotFoundException => res.getValue(id, outValue, resolveRefs)
  }

  override def getValue(name: String, outValue: TypedValue, resolveRefs: Boolean) = try {
    super.getValue(name, outValue, resolveRefs)
  } catch {
    case e: Resources.NotFoundException => res.getValue(name, outValue, resolveRefs)
  }

  override def openRawResource(id: Int) = try {
    super.openRawResource(id)
  } catch {
    case e: Resources.NotFoundException => res.openRawResource(id)
  }

  override def openRawResource(id: Int, value: TypedValue) = try {
    super.openRawResource(id, value)
  } catch {
    case e: Resources.NotFoundException => res.openRawResource(id, value)
  }

  override def getDimensionPixelOffset(id: Int) = try {
    super.getDimensionPixelOffset(id)
  } catch {
    case e: Resources.NotFoundException => res.getDimensionPixelOffset(id)
  }

  override def getDimension(id: Int) = try {
    super.getDimension(id)
  } catch {
    case e: Resources.NotFoundException => res.getDimension(id)
  }

  override def getLayout(id: Int) = try {
    super.getLayout(id)
  } catch {
    case e: Resources.NotFoundException => res.getLayout(id)
  }

  override def openRawResourceFd(id: Int) = try {
    super.openRawResourceFd(id)
  } catch {
    case e: Resources.NotFoundException => res.openRawResourceFd(id)
  }

  override def getDimensionPixelSize(id: Int) = try {
    super.getDimensionPixelSize(id)
  } catch {
    case e: Resources.NotFoundException => res.getDimensionPixelSize(id)
  }

  override def getValueForDensity(id: Int, density: Int, outValue: TypedValue, resolveRefs: Boolean) = try {
    super.getValueForDensity(id, density, outValue, resolveRefs)
  } catch {
    case e: Resources.NotFoundException => res.getValueForDensity(id, density, outValue, resolveRefs)
  }

  override def getDrawable(id: Int) = try {
    super.getDrawable(id)
  } catch {
    case e: Resources.NotFoundException => res.getDrawable(id)
  }

  override def getDrawable(id: Int, theme: Resources#Theme) = try {
    super.getDrawable(id, theme)
  } catch {
    case e: Resources.NotFoundException => res.getDrawable(id, theme)
  }

  override def getResourceEntryName(resid: Int) = try {
    super.getResourceEntryName(resid)
  } catch {
    case e: Resources.NotFoundException => res.getResourceEntryName(resid)
  }

  override def parseBundleExtra(tagName: String, attrs: AttributeSet, outBundle: Bundle) = try {
    super.parseBundleExtra(tagName, attrs, outBundle)
  } catch {
    case e: Resources.NotFoundException => res.parseBundleExtra(tagName, attrs, outBundle)
  }

  override def getResourceTypeName(resid: Int) = try {
    super.getResourceTypeName(resid)
  } catch {
    case e: Resources.NotFoundException => res.getResourceTypeName(resid)
  }

  override def getMovie(id: Int) = try {
    super.getMovie(id)
  } catch {
    case e: Resources.NotFoundException => res.getMovie(id)
  }

  override def getColor(id: Int) = try {
    super.getColor(id)
  } catch {
    case e: Resources.NotFoundException => res.getColor(id)
  }

  override def getBoolean(id: Int) = try {
    super.getBoolean(id)
  } catch {
    case e: Resources.NotFoundException => res.getBoolean(id)
  }

  override def getFraction(id: Int, base: Int, pbase: Int) = try {
    super.getFraction(id, base, pbase)
  } catch {
    case e: Resources.NotFoundException => res.getFraction(id, base, pbase)
  }

  override def getStringArray(id: Int) = try {
    super.getStringArray(id)
  } catch {
    case e: Resources.NotFoundException => res.getStringArray(id)
  }

  override def getResourceName(resid: Int) = try {
    super.getResourceName(resid)
  } catch {
    case e: Resources.NotFoundException => res.getResourceName(resid)
  }

  override def getQuantityString(id: Int, quantity: Int, formatArgs: AnyRef*) = try {
    super.getQuantityString(id, quantity, formatArgs: _*)
  } catch {
    case e: Resources.NotFoundException => res.getQuantityString(id, quantity, formatArgs: _*)
  }

  override def getQuantityString(id: Int, quantity: Int) = try {
    super.getQuantityString(id, quantity)
  } catch {
    case e: Resources.NotFoundException => res.getQuantityString(id, quantity)
  }

  override def getResourcePackageName(resid: Int) = try {
    super.getResourcePackageName(resid)
  } catch {
    case e: Resources.NotFoundException => res.getResourcePackageName(resid)
  }

  override def getXml(id: Int) = try {
    super.getXml(id)
  } catch {
    case e: Resources.NotFoundException => res.getXml(id)
  }

  override def getInteger(id: Int) = try {
    super.getInteger(id)
  } catch {
    case e: Resources.NotFoundException => res.getInteger(id)
  }

  override def getColorStateList(id: Int) = try {
    super.getColorStateList(id)
  } catch {
    case e: Resources.NotFoundException => res.getColorStateList(id)
  }

  override def getAnimation(id: Int) = try {
    super.getAnimation(id)
  } catch {
    case e: Resources.NotFoundException => res.getAnimation(id)
  }

  override def obtainAttributes(set: AttributeSet, attrs: Array[Int]) = try {
    super.obtainAttributes(set, attrs)
  } catch {
    case e: Resources.NotFoundException => res.obtainAttributes(set, attrs)
  }

  override def obtainTypedArray(id: Int) = try {
    super.obtainTypedArray(id)
  } catch {
    case e: Resources.NotFoundException => res.obtainTypedArray(id)
  }

  override def getText(id: Int) = try {
    super.getText(id)
  } catch {
    case e: Resources.NotFoundException => res.getText(id)
  }

  override def getText(id: Int, `def`: CharSequence) = try {
    super.getText(id, `def`)
  } catch {
    case e: Resources.NotFoundException => res.getText(id, `def`)
  }

  override def getIdentifier(name: String, defType: String, defPackage: String) = try {
    super.getIdentifier(name, defType, defPackage)
  } catch {
    case e: Resources.NotFoundException => res.getIdentifier(name, defType, defPackage)
  }

  override def getTextArray(id: Int) = try {
    super.getTextArray(id)
  } catch {
    case e: Resources.NotFoundException => res.getTextArray(id)
  }

  override def getQuantityText(id: Int, quantity: Int) = try {
    super.getQuantityText(id, quantity)
  } catch {
    case e: Resources.NotFoundException => res.getQuantityText(id, quantity)
  }

  override def getString(id: Int) =  try{
    super.getString(id)
  } catch {
    case e: Resources.NotFoundException => res.getString(id)
  }

  override def getString(id: Int, formatArgs: AnyRef*) = try {
    super.getString(id, formatArgs: _*)
  } catch {
    case e: Resources.NotFoundException => res.getString(id, formatArgs: _*)
  }

  override def getDrawableForDensity(id: Int, density: Int) = try {
    super.getDrawableForDensity(id, density)
  } catch {
    case e: Resources.NotFoundException => res.getDrawableForDensity(id, density)
  }

  override def getDrawableForDensity(id: Int, density: Int, theme: Resources#Theme) = try {
    super.getDrawableForDensity(id, density, theme)
  } catch {
    case e: Resources.NotFoundException => res.getDrawableForDensity(id, density, theme)
  }
}

trait ViewServerSupport extends Activity {
  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    ViewServer.get(this).addWindow(this)
  }

  override def onDestroy() = {
    super.onDestroy()
    ViewServer.get(this).removeWindow(this)
  }

  override def onResume() = {
    super.onResume()
    ViewServer.get(this).setFocusedWindow(this)
  }
}
