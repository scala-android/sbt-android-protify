package com.hanhuy.android.protify.agent.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import com.hanhuy.android.protify.agent.ProtifyApplication;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * @author pfnguyen
 */
@TargetApi(14)
public class LifecycleListener implements Application.ActivityLifecycleCallbacks {
    private final Map<Activity,Long> activities = new IdentityHashMap<Activity,Long>();
    private final static String TAG = "LifecycleListener";
    private LifecycleListener() { }

    private final static LifecycleListener INSTANCE = new LifecycleListener();
    public static LifecycleListener getInstance() {
        return INSTANCE;
    }
    // Sometimes, resuming an activity from the background results in
    // onActivityPaused being called immediately, and we lose our 'top'
    // if we null it out then. stash it away until onDestroy
    private Activity top = null;

    @Override public void onActivityCreated(Activity activity, Bundle bundle) {
        activities.put(activity, System.currentTimeMillis());
    }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle bundle) { }
    @Override public void onActivityDestroyed(Activity activity) {
        if (activity == top) top = null;
        activities.remove(activity);
    }
    // no corollary onActivityPaused because resuming from home results in weird behavior
    @Override public void onActivityPaused(Activity activity) { }

    @Override
    public void onActivityResumed(Activity activity) {
        top = activity;
        // should never be null
        long l = activities.get(activity);
        if (l < ProtifyApplication.getResourceInstallTime())
            activity.recreate();
    }


    public Activity getTopActivity() {
        return top;
    }

}
