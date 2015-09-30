package com.hanhuy.android.protify.agent.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import com.hanhuy.android.protify.Intents;
import com.hanhuy.android.protify.agent.ProtifyApplication;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author pfnguyen
 */
public class ProtifyReceiver extends BroadcastReceiver {

    static void broadcast(Context c, String action) {
        Intent bc = new Intent(c, ProtifyReceiver2.class);
        bc.setAction(action);
        c.sendBroadcast(bc);
    }

    private final static String TAG = "ProtifyReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        Log.v(TAG, "Received action: " + action);
        if (Intents.PROTIFY_INTENT.equals(action)) {
            InstallState result = install(intent.getExtras(), context);
            if (result.dex) {
                Log.v(TAG, "Updated dex, restarting process");
                broadcast(context, Intents.RESTART_INTENT);
            } else if (result.resources) {
                Log.v(TAG, "Updated resources, recreating activities");
                broadcast(context, Intents.RECREATE_INTENT);
            }
        } else if (Intents.INSTALL_INTENT.equals(action)) {
            InstallState result = install(intent.getExtras(), context);
            if (result.dex || result.resources) {
                Log.v(TAG, "Installed new resources or dex, restarting process");
                broadcast(context, Intents.STOP_INTENT);
            }
        } else if (Intents.CLEAN_INTENT.equals(action)) {
            Log.v(TAG, "Clearing resources and dex from cache");
            try {
                ProtifyResources.getResourcesFile(context).delete();
                File[] files = DexLoader.getDexExtractionDir(context).listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            } catch (Throwable t) {
                // noop don't care
            }
            broadcast(context, Intents.RESTART_INTENT);
        }
    }

    private InstallState install(Bundle extras, Context context) {
        if (extras != null) {
            String resources = extras.getString(Intents.EXTRA_RESOURCES);
            String dexInfo = extras.getString(Intents.EXTRA_DEX_INFO);
            File dexInfoFile = dexInfo == null ? null : new File(dexInfo);
            boolean hasDex = dexInfoFile != null && dexInfoFile.isFile() && dexInfoFile.length() > 0;
            boolean hasRes = ProtifyResources.updateResourcesFile(context, resources);
            if (hasRes) ProtifyApplication.installExternalResources(context);
            if (hasDex) {
                try {
                    StringWriter sw = new StringWriter();
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(new FileInputStream(dexInfoFile), "utf-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        sw.write(line + "\n");
                    }
                    String[] lines = sw.toString().split("\n");
                    for (String l : lines) {
                        String[] ls = l.split(":");
                        String dex = ls[0];
                        String dexName = ls[1];
                        Log.v(TAG, "Loading DEX from " + dex + " to " + dexName);
                        File dexDir = DexLoader.getDexExtractionDir(context);
                        if (Build.VERSION.SDK_INT >= 14) {
                            File dexfile = new File(dex);
                            FileChannel ch = new FileInputStream(dexfile).getChannel();
                            File dest = new File(dexDir, dexName);
                            FileChannel ch2 = new FileOutputStream(dest, false).getChannel();
                            ch.transferTo(0, dexfile.length(), ch2);
                            ch.close();
                            ch2.close();
                        } else {
                            File dexfile = new File(dex);
                            File dest = new File(dexDir, dexName + DexExtractor.ZIP_SUFFIX);
                            ZipOutputStream zout = new ZipOutputStream(
                                    new BufferedOutputStream(new FileOutputStream(dest)));
                            FileInputStream fin = new FileInputStream(dexfile);
                            try {
                                ZipEntry classesDex = new ZipEntry("classes.dex");
                                classesDex.setTime(dexfile.lastModified());
                                zout.putNextEntry(classesDex);
                                byte[] buffer = new byte[0x4000];
                                int read;
                                while ((read = fin.read(buffer)) != -1) {
                                    zout.write(buffer, 0, read);
                                }
                                zout.closeEntry();
                            } finally {
                                fin.close();
                                zout.close();
                            }
                        }
                    }
                    r.close();
                } catch (Exception e) {
                    throw new RuntimeException("Cannot copy DEX: " + e.getMessage(), e);
                }
            }
            return new InstallState(hasRes, hasDex);
        }
        return InstallState.NONE;
    }

    final static class InstallState {
        public final static InstallState NONE = new InstallState(false, false);
        public final boolean resources;
        public final boolean dex;

        public InstallState(boolean resources, boolean dex) {
            this.resources = resources;
            this.dex = dex;
        }
    }
}
