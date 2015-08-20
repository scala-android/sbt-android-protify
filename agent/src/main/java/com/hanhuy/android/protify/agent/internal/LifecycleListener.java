package com.hanhuy.android.protify.agent.internal;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * @author pfnguyen
 */
public class LifecycleListener implements Application.ActivityLifecycleCallbacks {
    private final static String TAG = "LifecycleListener";
    private LifecycleListener() { }

    private final static LifecycleListener INSTANCE = new LifecycleListener();
    public static LifecycleListener getInstance() {
        return INSTANCE;
    }
    // Sometimes, resuming an activity from the background results in
    // onActivityPaused being called immediately, and we lose our 'top'
    // if we null it out then. stash it away in a weakref instead.
    private WeakReference<Activity> top = new WeakReference<Activity>(null);

    @Override public void onActivityCreated(Activity activity, Bundle bundle) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle bundle) { }
    @Override public void onActivityDestroyed(Activity activity) { }
    // no corollary onActivityPaused because resuming from home results in weird behavior
    @Override public void onActivityPaused(Activity activity) { }

    @Override
    public void onActivityResumed(Activity activity) {
        if (activity.getBaseContext() instanceof ProtifyContext) {
            top = new WeakReference<Activity>(activity);
            ProtifyContext p = (ProtifyContext) activity.getBaseContext();
            if (p.needsRecreate())
                activity.recreate();
        }
    }


    public Activity getTopActivity() {
        return top.get();
    }

    public Thread.UncaughtExceptionHandler createExceptionHandler(
            final Context ctx,
            final Thread.UncaughtExceptionHandler defaultHandler) {
        return new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.v(TAG, "Caught a fatal error, clearing protify files");
                try {
                    ProtifyContext.getResourcesFile(ctx).delete();
                    DexLoader.getDexFile(ctx).delete();
                } catch (Throwable t) {
                    // noop
                }
                defaultHandler.uncaughtException(thread, throwable);
            }
        };
    }
}