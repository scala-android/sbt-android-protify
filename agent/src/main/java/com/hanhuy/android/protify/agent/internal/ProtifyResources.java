package com.hanhuy.android.protify.agent.internal;

import android.content.Context;
import android.util.Log;

import java.io.*;
import java.nio.channels.FileChannel;

/**
 * @author pfnguyen
 */
public class ProtifyResources {
    private final static String TAG = "ProtifyResources";
    static boolean updateResourcesFile(Context ctx, String resourcePath, String resInfoPath) {
        if (resourcePath == null && resInfoPath == null) return false;
        File resapk = new File(resourcePath);
        File resInfoFile = new File(resInfoPath);
        File cacheres = getResourcesFile(ctx);
        if (resInfoFile.isFile() && resInfoFile.length() > 0) {
            try {
                installFromResInfo(ctx, resInfoFile);
            } catch (IOException e) {
                throw new RuntimeException("Cannot copy resource shards: " + e.getMessage(), e);
            }
            return true;
        } else if (resapk.isFile() && resapk.length() > 0) {
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

    private static void installFromResInfo(Context context, File resInfo) throws IOException {
        StringWriter sw = new StringWriter();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(resInfo), "utf-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sw.write(line + "\n");
            }
            String[] lines = sw.toString().split("\n");
            for (String l : lines) {
                String[] ls = l.split(":");
                String res0 = ls[0];
                String res1 = ls[1];
                Log.v(TAG, "Loading resource shard from " + res0 + " to " + res1);
                File resfile = new File(res0);
                FileChannel ch = new FileInputStream(resfile).getChannel();
                File dest = new File(context.getFilesDir(), res1);
                FileChannel ch2 = new FileOutputStream(dest, false).getChannel();
                try {
                    ch.transferTo(0, resfile.length(), ch2);
                } finally {
                    ch.close();
                    ch2.close();
                }
            }
        } finally {
            r.close();
        }
    }

    public static File[] getResourceFiles(Context ctx) {
        File base = new File(ctx.getFilesDir(), "protify-resources");

        File[] shards = base.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
                return filename.startsWith("protify-resources-") && filename.endsWith(".ap_");
            }
        });
        return shards != null ? shards : new File[] { getResourcesFile(ctx) };
    }

    private static File getResourcesFile(Context ctx) {
        File f = new File(ctx.getFilesDir(), "protify-resources");
        f.mkdirs();
        return new File(f, "protify-resources.ap_");
    }
}
