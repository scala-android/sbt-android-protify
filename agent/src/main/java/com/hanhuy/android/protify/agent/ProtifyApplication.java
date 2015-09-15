package com.hanhuy.android.protify.agent;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import com.hanhuy.android.protify.agent.internal.DexLoader;
import com.hanhuy.android.protify.agent.internal.LifecycleListener;
import com.hanhuy.android.protify.agent.internal.ProtifyContext;
import com.hanhuy.android.protify.agent.internal.ProtifyLayoutInflater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * some of this is straight up ripped off from bazelbuild's StubApplication
 * @author pfnguyen
 */
@SuppressWarnings("unused")
public class ProtifyApplication extends Application {
    private final String realApplicationClass;
    private Application realApplication;

    public ProtifyApplication() {
        String[] applicationInfo = getResourceAsString("protify_application_info.txt").split("\n");
        realApplicationClass = applicationInfo[0].trim();
        android.util.Log.d("ProtifyApplication", "Real application class: [" + realApplicationClass + "]");
        Protify.installed = true;
    }

    @Override
    protected void attachBaseContext(Context base) {
        DexLoader.install(base);

        createRealApplication();
        super.attachBaseContext(base);

        try {
            Method attachBaseContext = ContextWrapper.class.getDeclaredMethod(
                    "attachBaseContext", Context.class);
            attachBaseContext.setAccessible(true);
            attachBaseContext.invoke(realApplication, base);

        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Thread.setDefaultUncaughtExceptionHandler(
                LifecycleListener.getInstance().createExceptionHandler(
                        realApplication,
                        Thread.getDefaultUncaughtExceptionHandler()));
        realApplication.registerActivityLifecycleCallbacks(LifecycleListener.getInstance());
        ProtifyLayoutInflater.install(realApplication);
        ProtifyContext.loadResources(realApplication);
    }

    @Override
    public void onCreate() {
        installRealApplication();
        super.onCreate();
        realApplication.onCreate();
    }

    @SuppressWarnings("unchecked")
    private void installRealApplication() {
        // StubApplication is created by reflection in Application#handleBindApplication() ->
        // LoadedApk#makeApplication(), and its return value is used to set the Application field in all
        // sorts of Android internals.
        //
        // Fortunately, Application#onCreate() is called quite soon after, so what we do is monkey
        // patch in the real Application instance in StubApplication#onCreate().
        //
        // A few places directly use the created Application instance (as opposed to the fields it is
        // eventually stored in). Fortunately, it's easy to forward those to the actual real
        // Application class.
        try {
            // Find the ActivityThread instance for the current thread
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method m = activityThread.getMethod("currentActivityThread");
            m.setAccessible(true);
            Object currentActivityThread = m.invoke(null);

            // Find the mInitialApplication field of the ActivityThread to the real application
            Field mInitialApplication = activityThread.getDeclaredField("mInitialApplication");
            mInitialApplication.setAccessible(true);
            Application initialApplication = (Application) mInitialApplication.get(currentActivityThread);
            if (initialApplication == this) {
                mInitialApplication.set(currentActivityThread, realApplication);
            }

            // Replace all instance of the stub application in ActivityThread#mAllApplications with the
            // real one
            Field mAllApplications = activityThread.getDeclaredField("mAllApplications");
            mAllApplications.setAccessible(true);
            List<Application> allApplications = (List<Application>) mAllApplications
                    .get(currentActivityThread);
            for (int i = 0; i < allApplications.size(); i++) {
                if (allApplications.get(i) == this) {
                    allApplications.set(i, realApplication);
                }
            }

            // Figure out how loaded APKs are stored.

            // API version 8 has PackageInfo, 10 has LoadedApk. 9, I don't know.
            Class<?> loadedApkClass;
            try {
                loadedApkClass = Class.forName("android.app.LoadedApk");
            } catch (ClassNotFoundException e) {
                loadedApkClass = Class.forName("android.app.ActivityThread$PackageInfo");
            }
            Field mApplication = loadedApkClass.getDeclaredField("mApplication");
            mApplication.setAccessible(true);
            Field mResDir = loadedApkClass.getDeclaredField("mResDir");
            mResDir.setAccessible(true);

            // 10 doesn't have this field, 14 does. Fortunately, there are not many Honeycomb devices
            // floating around.
            Field mLoadedApk = null;
            try {
                mLoadedApk = Application.class.getDeclaredField("mLoadedApk");
            } catch (NoSuchFieldException e) {
                // According to testing, it's okay to ignore this.
            }

            // Enumerate all LoadedApk (or PackageInfo) fields in ActivityThread#mPackages and
            // ActivityThread#mResourcePackages and do two things:
            //   - Replace the Application instance in its mApplication field with the real one
            //   - Replace mResDir to point to the external resource file instead of the .apk. This is
            //     used as the asset path for new Resources objects.
            //   - Set Application#mLoadedApk to the found LoadedApk instance
            for (String fieldName : new String[] { "mPackages", "mResourcePackages" }) {
                Field field = activityThread.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(currentActivityThread);

                for (Map.Entry<String, WeakReference<?>> entry :
                        ((Map<String, WeakReference<?>>) value).entrySet()) {
                    Object loadedApk = entry.getValue().get();
                    if (loadedApk == null) {
                        continue;
                    }

                    if (mApplication.get(loadedApk) == this) {
                        mApplication.set(loadedApk, realApplication);
//                        if (externalResourceFile != null) {
//                            mResDir.set(loadedApk, externalResourceFile);
//                        }

                        if (mLoadedApk != null) {
                            mLoadedApk.set(realApplication, loadedApk);
                        }
                    }
                }
            }
        } catch (IllegalAccessException | NoSuchFieldException | NoSuchMethodException |
                ClassNotFoundException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    private String getResourceAsString(String resource) {
        InputStream resourceStream = null;
        // try-with-resources would be much nicer, but that requires SDK level 19, and we want this code
        // to be compatible with earlier Android versions
        try {
            resourceStream = getClass().getClassLoader().getResourceAsStream(resource);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = resourceStream.read(buffer)) != -1) {
                baos.write(buffer, 0, length);
            }

            return new String(baos.toByteArray(), "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (resourceStream != null) {
                try {
                    resourceStream.close();
                } catch (IOException e) {
                    // Not much we can do here
                }
            }
        }
    }

    private void createRealApplication() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Application> realClass =
                    (Class<? extends Application>) Class.forName(realApplicationClass);
            Constructor<? extends Application> ctor = realClass.getConstructor();
            realApplication = ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
