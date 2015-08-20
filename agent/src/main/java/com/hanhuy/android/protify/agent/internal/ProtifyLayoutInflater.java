package com.hanhuy.android.protify.agent.internal;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/**
 * @author pfnguyen
 */
public class ProtifyLayoutInflater extends LayoutInflater {
    private final static String TAG = "ProtifyLayoutInflater";
    private final static String[] PREFIXES = { "android.widget.", "android.webkit." };
    public ProtifyLayoutInflater(Context context) {
        super(context);
    }

    private ProtifyLayoutInflater(LayoutInflater original, Context newContext) {
        super(original, newContext);
    }

    @Override
    public LayoutInflater cloneInContext(Context context) {
        // seems to be the best place to get our resources injected before
        // anything else makes use of them
        ProtifyContext.injectProtifyContext(context);
        return new ProtifyLayoutInflater(this, context);
    }

    @Override
    protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
        for (String p : PREFIXES) {
            try {
                View v = createView(name, p, attrs);
                if (v != null) return v;
            } catch (ClassNotFoundException e) {
                // stupid android API
            }
        }
        return super.onCreateView(name, attrs);
    }

    public static void install(Application c) {
        LayoutInflater l = (LayoutInflater) c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (l instanceof ProtifyLayoutInflater) return;

        Class<?> base = c.getBaseContext().getClass();
        if (CONTEXT_IMPL_CLASS != base)
            throw new UnsupportedOperationException("Unable to install Protify on: " + base);

        if (Build.VERSION.SDK_INT >= 23) {
            V23.install(c);
        } else if (Build.VERSION.SDK_INT >= 14) {
            V14.install(c);
        } else {
            throw new RuntimeException("Unsupported Android version: v" + Build.VERSION.SDK_INT);
        }

        // verify that we actually modified the system service map
        if (!(c.getSystemService(Context.LAYOUT_INFLATER_SERVICE) instanceof ProtifyLayoutInflater)) {
            throw new RuntimeException("Failed to install ProtifyLayoutInflater");
        }
    }

    private final static ClassLoader CONTEXT_LOADER = Context.class.getClassLoader();
    private final static Class<?> CONTEXT_IMPL_CLASS;
    static {
        try {
            CONTEXT_IMPL_CLASS              = CONTEXT_LOADER.loadClass("android.app.ContextImpl");
        } catch (Exception e) {
            throw new RuntimeException("Unable to load required classes: " + e.getMessage(), e);
        }
    }

    private static Class<?> findConcreteFetcher(Class<?> fetcherParent, String prefix) {
        int i = 1;
        boolean search = true;
        Class<?> found = null;
        while (search) {
            try {
                Class<?> anonymous = CONTEXT_LOADER.loadClass(prefix + i);
                if (anonymous != null && fetcherParent.isAssignableFrom(anonymous)) {
                    found = anonymous;
                    search = false;
                }
                i++;
            } catch (ClassNotFoundException e) {
                Log.v(TAG, "Stopped searching for concrete StaticServiceFetcher: " + i);
                search = false;
            }
        }
        if (found == null) {
            throw new RuntimeException(
                    "Unable to find a concrete StaticServiceFetcher after tries: " + i);
        }
        return found;
    }
    // java hackery at its finest!
    // uses an anonymous class that extends StaticServiceFetcher
    // invoke its constructor, replace its mCachedInstance field with
    // our inflater
    private static Object newLayoutFetcher(Context c, Class<?> fetcher, Field field)
            throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, InstantiationException {
        Constructor<?> ctor = fetcher.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object f = ctor.newInstance();
        field.set(f, new ProtifyLayoutInflater(c));
        return fetcher;
    }
    public static class V23 {
        private final static Class<?> FETCHER_CLASS;
        private final static Class<?> CONCRETE_FETCHER_CLASS;
        private final static Field FETCHER_MCACHED_INSTANCE;
        private final static Class<?> SYSTEM_SERVICE_REGISTRY;
        private final static Field SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_NAMES;
        private final static Field SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_FETCHERS;
        static {
            try {
                SYSTEM_SERVICE_REGISTRY  = CONTEXT_LOADER.loadClass("android.app.SystemServiceRegistry");
                FETCHER_CLASS            = CONTEXT_LOADER.loadClass("android.app.SystemServiceRegistry$StaticServiceFetcher");
                FETCHER_MCACHED_INSTANCE = FETCHER_CLASS.getDeclaredField("mCachedInstance");
                CONCRETE_FETCHER_CLASS   = findConcreteFetcher(FETCHER_CLASS, "android.app.SystemServiceRegistry$");
                FETCHER_MCACHED_INSTANCE.setAccessible(true);
                SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_NAMES    = SYSTEM_SERVICE_REGISTRY.getDeclaredField("SYSTEM_SERVICE_NAMES");
                SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_FETCHERS = SYSTEM_SERVICE_REGISTRY.getDeclaredField("SYSTEM_SERVICE_FETCHERS");
                SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_NAMES.setAccessible(true);
                SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_FETCHERS.setAccessible(true);
            } catch (Exception e) {
                throw new RuntimeException("Unable to load required fields: " + e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String,Object> getFetchersMap() throws IllegalAccessException {
            return (Map<String,Object>)SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_FETCHERS.get(null);
        }
        @SuppressWarnings("unchecked")
        private static Map<Class<?>,String> getNamesMap() throws IllegalAccessException {
            return (Map<Class<?>,String>)SYSTEM_SERVICE_REGISTRY_SYSTEM_SERVICE_NAMES.get(null);
        }

        public static void install(Application c) {
            try {
                getNamesMap().put(LayoutInflater.class, Context.LAYOUT_INFLATER_SERVICE);
                getFetchersMap().put(Context.LAYOUT_INFLATER_SERVICE,
                        newLayoutFetcher(c, CONCRETE_FETCHER_CLASS, FETCHER_MCACHED_INSTANCE));
            } catch (Exception e) {
                throw new RuntimeException("Failed to install ProtifyLayoutInflater: " + e.getMessage(), e);
            }
        }
    }

    public static class V14 {
        private final static Class<?> FETCHER_CLASS;
        private final static Class<?> CONCRETE_FETCHER_CLASS;
        private final static Field FETCHER_MCACHED_INSTANCE;
        private final static Field CONTEXT_IMPL_SYSTEM_SERVICE_MAP;
        static {
            try {
                FETCHER_CLASS                   = CONTEXT_LOADER.loadClass("android.app.ContextImpl$StaticServiceFetcher");
                CONTEXT_IMPL_SYSTEM_SERVICE_MAP = CONTEXT_IMPL_CLASS.getDeclaredField("SYSTEM_SERVICE_MAP");
                FETCHER_MCACHED_INSTANCE        = FETCHER_CLASS.getDeclaredField("mCachedInstance");
                CONCRETE_FETCHER_CLASS          = findConcreteFetcher(FETCHER_CLASS, "android.app.ContextImpl$");
                FETCHER_MCACHED_INSTANCE.setAccessible(true);
                CONTEXT_IMPL_SYSTEM_SERVICE_MAP.setAccessible(true);
            } catch (Exception e) {
                throw new RuntimeException("Unable to load required classes: " + e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String,Object> getServiceMap() throws IllegalAccessException {
            return (Map<String,Object>)CONTEXT_IMPL_SYSTEM_SERVICE_MAP.get(null);
        }

        public static void install(Application c) {
            try {
                getServiceMap().put(Context.LAYOUT_INFLATER_SERVICE,
                        newLayoutFetcher(c, CONCRETE_FETCHER_CLASS, FETCHER_MCACHED_INSTANCE));
            } catch (Exception e) {
                throw new RuntimeException("Failed to install ProtifyLayoutInflater: " + e.getMessage(), e);
            }
        }
    }
}
