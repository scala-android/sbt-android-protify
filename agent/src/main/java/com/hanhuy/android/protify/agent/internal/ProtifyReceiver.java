package com.hanhuy.android.protify.agent.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import com.hanhuy.android.protify.Intents;
import com.hanhuy.android.protify.agent.ProtifyApplication;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author pfnguyen
 */
public class ProtifyReceiver extends BroadcastReceiver {
    private final static String TAG = "ProtifyReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        Log.v(TAG, "Received action: " + action);
        boolean ltV14 = Build.VERSION.SDK_INT < 14;
        Activity top = ltV14 ? null : LifecycleListener.getInstance().getTopActivity();
        if (Intents.PROTIFY_INTENT.equals(action)) {
            InstallState result = install(intent.getExtras(), context);
            if (result.dex || (result.resources && ltV14)) {
                Log.v(TAG, "Updated dex, restarting process, top non-null: " +
                        (top != null));
                if (top != null || ltV14) {
                    restartApp(context);
                } else {
                    Process.killProcess(Process.myPid());
                }
            } else if (result.resources) {
                Log.v(TAG, "Updated resources, recreating activities");
                recreateActivity(top);
                if (top == null) {
                    ApplicationInfo info = context.getApplicationInfo();
                    PackageManager pm = context.getPackageManager();
                    Intent mainIntent = new Intent(Intent.ACTION_MAIN);
                    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);
                    for (ResolveInfo ri : activities) {
                        if (info.packageName.equals(ri.activityInfo.packageName)) {
                            Intent main = new Intent();
                            main.setComponent(new ComponentName(info.packageName, ri.activityInfo.name));
                            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(main);
                            break;
                        }
                    }
                } else {
                    Intent bringToFront = (Intent) top.getIntent().clone();
                    bringToFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    context.startActivity(bringToFront);
                }
            }
        } else if (Intents.INSTALL_INTENT.equals(action)) {
            InstallState result = install(intent.getExtras(), context);
            if (result.dex || result.resources) {
                Log.v(TAG, "Installed new resources or dex, restarting process");
                Process.killProcess(Process.myPid());
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
            if (top != null) {
                restartApp(context);
            } else {
                Process.killProcess(Process.myPid());
            }
        }
    }

    private void restartApp(Context context) {
//        Intent reset = new Intent(context, ProtifyActivity.class);
//        reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//        context.startActivity(reset);
        Context application = context.getApplicationContext();
        Restarter.restartApp(
                application, Restarter.getActivities(application, false), true);
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

    @TargetApi(11)
    private static void recreateActivity(Activity a) {
        if (a != null) a.recreate();
    }
}
