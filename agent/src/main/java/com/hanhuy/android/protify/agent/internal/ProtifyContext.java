package com.hanhuy.android.protify.agent.internal;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.view.ContextThemeWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;

/**
 * @author pfnguyen
 */
public class ProtifyContext extends ContextWrapper {
    public final static Method ASSET_MANAGER_ADD_ASSET_PATH;
    static {
        try {
            ASSET_MANAGER_ADD_ASSET_PATH = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        } catch (Exception e) {
            throw new RuntimeException("Unable to find AssetManager.addAssetPath: " + e.getMessage(), e);
        }
    }

    private final Context base;
    private final Resources resources;
    private static long resourcesLoaded;
    private final long resourcesSet;
    private final static String TAG = "ProtifyContext";

    private final static Field CONTEXT_WRAPPER_MBASE;
    private final static Field CONTEXT_THEME_WRAPPER_MRESOURCES;
    private final static Field CONTEXT_THEME_WRAPPER_MTHEME;

    private final static Class<?> APPCOMPAT_CONTEXT_THEME_WRAPPER;
    private final static Field APPCOMPAT_CONTEXT_THEME_WRAPPER_MTHEME;

    static {
        Field mBase = null;
        Field mResources = null;
        Field mTheme = null;
        try {
            mBase = ContextWrapper.class.getDeclaredField("mBase");
            mBase.setAccessible(true);
            mResources = ContextThemeWrapper.class.getDeclaredField("mResources");
            mResources.setAccessible(true);
            mTheme = ContextThemeWrapper.class.getDeclaredField("mTheme");
            mTheme.setAccessible(true);
        } catch (Exception e) {
            android.util.Log.v("ProtifyContext", "Context fields not available: " + e.getMessage(), e);
        }
        CONTEXT_WRAPPER_MBASE = mBase;
        CONTEXT_THEME_WRAPPER_MRESOURCES = mResources;
        CONTEXT_THEME_WRAPPER_MTHEME = mTheme;

        Class<?> appcompatContextThemeWrapper = null;
        Field appcompatContextThemeWrapperMTheme = null;
        try {
            appcompatContextThemeWrapper =
                    ProtifyContext.class.getClassLoader().loadClass(
                            "android.support.v7.internal.view.ContextThemeWrapper");
            appcompatContextThemeWrapperMTheme = appcompatContextThemeWrapper.getDeclaredField("mTheme");
            appcompatContextThemeWrapperMTheme.setAccessible(true);
        } catch (Exception e) {
            Log.v(TAG, "appcompat-v7 not detected");
        }

        APPCOMPAT_CONTEXT_THEME_WRAPPER = appcompatContextThemeWrapper;
        APPCOMPAT_CONTEXT_THEME_WRAPPER_MTHEME = appcompatContextThemeWrapperMTheme;
    }

    private static boolean isAppCompatContextThemeWrapper(Context c) {
        return APPCOMPAT_CONTEXT_THEME_WRAPPER != null &&
                APPCOMPAT_CONTEXT_THEME_WRAPPER.isAssignableFrom(c.getClass());
    }
    ProtifyContext(Context base, Resources res) {
        super(base);
        this.base = base;
        this.resources = res;
        resourcesSet = System.currentTimeMillis();
    }

    public static void injectProtifyContext(Context ctx) {
        injectProtifyContext(ctx, updatedResources);
    }

    private static void injectProtifyContext(Context ctx, Resources r) {
        if (!(ctx instanceof ContextWrapper))
            return;
        ContextWrapper c = (ContextWrapper) ctx;
        Context base = c.getBaseContext();
        try {
            CONTEXT_WRAPPER_MBASE.set(c, new ProtifyContext(base, r));

            // clear out cached theme/resources in base
            if (c instanceof ContextThemeWrapper) {
                CONTEXT_THEME_WRAPPER_MRESOURCES.set(c, null);
                CONTEXT_THEME_WRAPPER_MTHEME.set(c, null);
            } else if (isAppCompatContextThemeWrapper(c)) {
                APPCOMPAT_CONTEXT_THEME_WRAPPER_MTHEME.set(c, null);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Unable to update base context: " + e.getMessage(), e);
        }
    }

    public static boolean updateResources(Context ctx, String resourcePath, boolean recreate) {
        if (resourcePath == null) return false;
        File resapk = new File(resourcePath);
        File cacheres = getResourcesFile(ctx);
        if (resapk.isFile() && resapk.length() > 0) {
            try {
                FileChannel ch = new FileInputStream(resapk).getChannel();
                FileChannel ch2 = new FileOutputStream(cacheres, false).getChannel();
                ch.transferTo(0, resapk.length(), ch2);
                ch.close();
                ch2.close();
            } catch (IOException e) {
                throw new RuntimeException("Cannot copy resource apk: " + e.getMessage(), e);
            }
            if (recreate)
                loadResources(ctx);
            return true;
        }
        return false;
    }

    public static void loadResources(Context ctx) {
        File cacheres = getResourcesFile(ctx);
        if (!cacheres.isFile() || cacheres.length() < 1) return;
        try {
            AssetManager am = AssetManager.class.newInstance();
            ASSET_MANAGER_ADD_ASSET_PATH.invoke(am, cacheres.getAbsolutePath());
            Resources orig = ctx.getResources();
            updateResources(new Resources(
                    am, orig.getDisplayMetrics(), orig.getConfiguration()));
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to update application resources: " + e.getMessage(), e);
        }
        resourcesLoaded = System.currentTimeMillis();
    }

    public static File getResourcesFile(Context ctx) {
        File f = new File(ctx.getFilesDir(), "protify-resources");
        f.mkdirs();
        return new File(f, "protify-resources.ap_");
    }

    @Override
    public Resources getResources() {
        return resources != null ? resources : base.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return resources != null ? resources.getAssets() : base.getAssets();
    }

    public boolean needsRecreate() { return resourcesLoaded > resourcesSet; }

    private static void updateResources(Resources r) {
        updatedResources = r;
        if (Build.VERSION.SDK_INT >= 14) {
            Activity top = LifecycleListener.getInstance().getTopActivity();
            if (top != null) top.recreate();
        }
    }

    private static Resources updatedResources;
}
