package com.hanhuy.android.protify.agent.internal;

import android.app.Application;
import android.content.Context;
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

        try {
            getServiceMap().put(Context.LAYOUT_INFLATER_SERVICE, newServiceFetcher(c));
        } catch (Exception e) {
            throw new RuntimeException("Failed to install ProtifyLayoutInflater: " + e.getMessage(), e);
        }
        // verify that we actually modified the system service map
        if (!(c.getSystemService(Context.LAYOUT_INFLATER_SERVICE) instanceof ProtifyLayoutInflater)) {
            throw new RuntimeException("Failed to install ProtifyLayoutInflater");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> getServiceMap() throws IllegalAccessException {
        return (Map<String,Object>)CONTEXT_IMPL_SYSTEM_SERVICE_MAP.get(null);
    }

    // java hackery at its finest!
    // uses an anonymous class that extends StaticServiceFetcher
    // invoke its constructor, replace its mCachedInstance field with
    // our inflater
    private static Object newServiceFetcher(Context c)
            throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, InstantiationException {
        Constructor<?> ctor = CONCRETE_FETCHER_CLASS.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object fetcher = ctor.newInstance();
        FETCHER_MCACHED_INSTANCE.set(fetcher, new ProtifyLayoutInflater(c));
        return fetcher;
    }

    private final static Class<?> CONTEXT_IMPL_CLASS;
    private final static ClassLoader CONTEXT_LOADER = Context.class.getClassLoader();
    private final static Class<?> FETCHER_CLASS;
    private final static Class<?> CONCRETE_FETCHER_CLASS;
    private final static Field FETCHER_MCACHED_INSTANCE;
    private final static Field CONTEXT_IMPL_SYSTEM_SERVICE_MAP;
    static {
        try {
            CONTEXT_IMPL_CLASS              = CONTEXT_LOADER.loadClass("android.app.ContextImpl");
            FETCHER_CLASS                   = CONTEXT_LOADER.loadClass("android.app.ContextImpl$StaticServiceFetcher");
            CONTEXT_IMPL_SYSTEM_SERVICE_MAP = CONTEXT_IMPL_CLASS.getDeclaredField("SYSTEM_SERVICE_MAP");
            FETCHER_MCACHED_INSTANCE        = FETCHER_CLASS.getDeclaredField("mCachedInstance");
            FETCHER_MCACHED_INSTANCE.setAccessible(true);
            CONTEXT_IMPL_SYSTEM_SERVICE_MAP.setAccessible(true);

            int i = 1;
            boolean search = true;
            Class<?> found = null;
            while (search) {
                try {
                    Class<?> anonymous = CONTEXT_LOADER.loadClass("android.app.ContextImpl$" + i);
                    if (anonymous != null && FETCHER_CLASS.isAssignableFrom(anonymous)) {
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
            CONCRETE_FETCHER_CLASS = found;
        } catch (Exception e) {
            throw new RuntimeException("Unable to load required classes: " + e.getMessage(), e);
        }
    }
}
