package com.hanhuy.android.protify.agent;

import android.app.Application;
import com.hanhuy.android.protify.agent.internal.DexLoader;
import com.hanhuy.android.protify.agent.internal.LifecycleListener;
import com.hanhuy.android.protify.agent.internal.ProtifyContext;
import com.hanhuy.android.protify.agent.internal.ProtifyLayoutInflater;

/**
 * @author pfnguyen
 */
public class Protify {

    private static boolean installed;

    /**
     * Would be nice, but no, Protify cannot be installed inside of an Activity.
     * It must occur during Application.onCreate or Application.attachBaseContext
     */
    public static void install(Application app) {
        if (installed) return;
        installed = true;
        Thread.setDefaultUncaughtExceptionHandler(
                LifecycleListener.getInstance().createExceptionHandler(app,
                        Thread.getDefaultUncaughtExceptionHandler()));
        app.registerActivityLifecycleCallbacks(LifecycleListener.getInstance());
        ProtifyLayoutInflater.install(app);
        ProtifyContext.loadResources(app);
        DexLoader.install(app);
    }
}
