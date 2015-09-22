package com.hanhuy.android.protify.agent.internal;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * @author pfnguyen
 */
public class ProtifyResources {
    public static boolean updateResourcesFile(Context ctx, String resourcePath) {
        if (resourcePath == null) return false;
        File resapk = new File(resourcePath);
        File cacheres = getResourcesFile(ctx);
        if (resapk.isFile() && resapk.length() > 0) {
            try {
                FileChannel ch = new FileInputStream(resapk).getChannel();
                FileChannel ch2 = new FileOutputStream(cacheres, false).getChannel();
                ch.transferTo(0, resapk.length(), ch2);
                ch.close();
                ch2.close();
            } catch (IOException e) {
                throw new RuntimeException("Cannot copy resource apk: " + e.getMessage(), e);
            }
            return true;
        }
        return false;
    }

    public static File getResourcesFile(Context ctx) {
        File f = new File(ctx.getFilesDir(), "protify-resources");
        f.mkdirs();
        return new File(f, "protify-resources.ap_");
    }
}
