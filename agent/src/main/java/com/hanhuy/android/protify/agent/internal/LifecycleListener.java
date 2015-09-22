package com.hanhuy.android.protify.agent.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * @author pfnguyen
 */
@TargetApi(14)
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

}
