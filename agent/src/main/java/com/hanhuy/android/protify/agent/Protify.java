package com.hanhuy.android.protify.agent;

import android.app.Application;
import android.os.Build;
import com.hanhuy.android.protify.agent.internal.*;

/**
 * @author pfnguyen
 */
public class Protify {

    static boolean installed;

    /**
     * Would be nice, but no, Protify cannot be installed inside of an Activity.
     * It must occur during Application.onCreate or Application.attachBaseContext
     *
     * This no longer needs to be called manually, unless one wants to build
     * with the IDE or gradle and not sbt.
     *
     * @deprecated 1.0.0: use automatic installation instead
     */
    @SuppressWarnings("unused")
    @Deprecated
    public static void install(Application app) {
        if (installed) return;
        installed = true;
        if (Build.VERSION.SDK_INT >= 14)
            app.registerActivityLifecycleCallbacks(LifecycleListener.getInstance());
        ProtifyApplication.installExternalResources(app);
        DexLoader.install(app);
    }
}
