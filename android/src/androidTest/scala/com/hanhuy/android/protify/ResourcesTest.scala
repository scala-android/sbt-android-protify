package com.hanhuy.android.protify
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import android.support.test.rule.ActivityTestRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert._
import android.content.res.Resources
import android.content.res.AssetManager

import com.hanhuy.android.keepshare.{R => TestR}

@RunWith(classOf[AndroidJUnit4])
class ResourcesTest {
  @Test def shouldReadResources() = {
    val ctx = InstrumentationRegistry.getContext
    val in = ctx.getAssets.open("resources-release.ap_")
    val out = ctx.openFileOutput("resources-release.ap_", 0)
    val b = Array.ofDim[Byte](32768)
    Stream.continually(in.read(b, 0, 32768)).takeWhile(
      _ != -1).foreach (out.write(b, 0, _))
    in.close()
    out.close()
    val f = new java.io.File(ctx.getFilesDir, "resources-release.ap_")
    assertEquals(1380246, f.length)
    val inf = ctx.getPackageManager.getPackageArchiveInfo(f.getAbsolutePath, 0)
    assertNotNull(inf)
    val am2 = classOf[AssetManager].newInstance
    am2.asInstanceOf[PrivateAssetManager].addAssetPath(f.getAbsolutePath)
    val oldres = ctx.getResources
    val res = new Resources(
      am2, oldres.getDisplayMetrics, oldres.getConfiguration)
    val xpp = res.getLayout(TestR.layout.pin_setup)
    assertNotNull(xpp)
    xpp.close()
    assertEquals("KeepShare Lite", res.getString(TestR.string.appname))
    assertEquals("content://com.hanhuy.android.keepshare.lite/entry",
       res.getString(TestR.string.search_suggest_intent_data))
    assertEquals("com.hanhuy.android.keepshare.lite",
       res.getString(TestR.string.search_suggest_authority))
    assertEquals("KeepShare Lite Form Filler",
       res.getString(TestR.string.accessibility_service_label))
  }
  private type PrivateAssetManager = {
    def addAssetPath(s: java.lang.String): Unit
  }
}
