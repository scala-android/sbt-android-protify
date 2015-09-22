package com.hanhuy.android.protify.agent.internal;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * @author pfnguyen
 */
public class ExceptionHandler {
    private final static String TAG = "ProtifyExceptionHandler";
    public static Thread.UncaughtExceptionHandler createExceptionHandler(
            final Context ctx,
            final Thread.UncaughtExceptionHandler defaultHandler) {
        return new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.v(TAG, "Caught a fatal error, clearing protify files");
                try {
                    ProtifyContext.getResourcesFile(ctx).delete();
                    File[] files = DexLoader.getDexExtractionDir(ctx).listFiles();
                    if (files != null) {
                        for (File f : files) {
                            f.delete();
                        }
                    }
                } catch (Throwable t) {
                    // noop
                }
                defaultHandler.uncaughtException(thread, throwable);
            }
        };
    }
}
