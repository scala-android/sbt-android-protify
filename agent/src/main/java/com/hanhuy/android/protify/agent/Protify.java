package com.hanhuy.android.protify.agent;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import com.hanhuy.android.protify.agent.internal.DexLoader;
import com.hanhuy.android.protify.agent.internal.ProtifyContext;
import com.hanhuy.android.protify.agent.internal.ProtifyLayoutInflater;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;

/**
 * @author pfnguyen
 */
public class Protify {

    private final static Method ASSET_MANAGER_ADD_ASSET_PATH;
    static {
        try {
            ASSET_MANAGER_ADD_ASSET_PATH = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        } catch (Exception e) {
            throw new RuntimeException("Unable to find AssetManager.addAssetPath: " + e.getMessage(), e);
        }
    }

    /**
     * Would be nice, but no, Protify cannot be installed inside of an Activity,
     * at the moment, it must occur during Application.onCreate
     * @param context
     */
    public static void install(Context context) {
        Application app = (Application) (context instanceof Application ? context : context.getApplicationContext());
        app.registerActivityLifecycleCallbacks(LifecycleListener.getInstance());
        ProtifyLayoutInflater.install(app);
        loadInitialResources(context);
        DexLoader.install(context);
    }

    public static class LifecycleListener implements Application.ActivityLifecycleCallbacks {
        private LifecycleListener() { }

        private final static LifecycleListener INSTANCE = new LifecycleListener();
        public static LifecycleListener getInstance() {
            return INSTANCE;
        }
        private Activity top = null;
        @Override public void onActivityCreated(Activity activity, Bundle bundle) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle bundle) { }
        @Override public void onActivityDestroyed(Activity activity) { }

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity.getBaseContext() instanceof ProtifyContext) {
                ProtifyContext p = (ProtifyContext) activity.getBaseContext();
                if (p.needsRecreate()) activity.recreate();
            }
            top = activity;
        }
        @Override
        public void onActivityPaused(Activity activity) {
            top = null;
        }

        public Activity getTopActivity() {
            return top;
        }
    }

    public static void updateResources(Context ctx, String resourcePath) {
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
            loadInitialResources(ctx);
        }
    }

    private static void loadInitialResources(Context ctx) {
        File cacheres = getResourcesFile(ctx);
        if (!cacheres.isFile() || cacheres.length() < 1) return;
        try {
            AssetManager am = AssetManager.class.newInstance();
            ASSET_MANAGER_ADD_ASSET_PATH.invoke(am, cacheres.getAbsolutePath());
            Resources orig = ctx.getResources();
            ProtifyContext.updateResources(new Resources(
                    am, orig.getDisplayMetrics(), orig.getConfiguration()));
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to update application resources: " + e.getMessage(), e);
        }
    }

    public static File getResourcesFile(Context ctx) {
        File f = new File(ctx.getFilesDir(), "protify-resources");
        f.mkdirs();
        return new File(f, "protify-resources.ap_");
    }
}
