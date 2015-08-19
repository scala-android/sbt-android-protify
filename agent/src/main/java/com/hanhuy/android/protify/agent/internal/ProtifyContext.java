package com.hanhuy.android.protify.agent.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import com.hanhuy.android.protify.agent.Protify;

import java.lang.reflect.Field;

/**
 * @author pfnguyen
 */
public class ProtifyContext extends ContextWrapper {
    private final Context base;
    private Resources resources;
    private boolean needRecreate;

    private final static Field CONTEXT_WRAPPER_MBASE;
    private final static Field CONTEXT_THEME_WRAPPER_MRESOURCES;
    private final static Field CONTEXT_THEME_WRAPPER_MTHEME;

    static {
        try {
            CONTEXT_WRAPPER_MBASE = ContextWrapper.class.getDeclaredField("mBase");
            CONTEXT_WRAPPER_MBASE.setAccessible(true);
            CONTEXT_THEME_WRAPPER_MRESOURCES = ContextThemeWrapper.class.getDeclaredField("mResources");
            CONTEXT_THEME_WRAPPER_MRESOURCES.setAccessible(true);
            CONTEXT_THEME_WRAPPER_MTHEME = ContextThemeWrapper.class.getDeclaredField("mTheme");
            CONTEXT_THEME_WRAPPER_MTHEME.setAccessible(true);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to access required fields: " + e.getMessage(), e);
        }
    }
    ProtifyContext(Context base, Resources res) {
        super(base);
        this.base = base;
        this.resources = res;
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
            if (base instanceof ProtifyContext)
                ((ProtifyContext) base).setNeedsRecreate(true);
            else
                CONTEXT_WRAPPER_MBASE.set(c, new ProtifyContext(base, r));

            // don't let contextwrapper give us resources or theme
            CONTEXT_THEME_WRAPPER_MRESOURCES.set(c, null);
            CONTEXT_THEME_WRAPPER_MTHEME.set(c, null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Unable to update base context: " + e.getMessage(), e);
        }
    }

    @Override
    public Resources getResources() {
        return resources != null ? resources : base.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return resources != null ? resources.getAssets() : base.getAssets();
    }

    public boolean needsRecreate() { return needRecreate; }

    public void setNeedsRecreate(boolean needRecreate) {
        this.needRecreate = needRecreate;
    }

    public static void updateResources(Resources r) {
        updatedResources = r;
        Activity top = Protify.LifecycleListener.getInstance().getTopActivity();
        if (top != null) top.recreate();
    }

    private static Resources updatedResources;
}
