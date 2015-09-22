package com.hanhuy.android.protify.agent.internal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import com.hanhuy.android.protify.Intents;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.List;

/**
 * @author pfnguyen
 */
public class ProtifyReceiver extends BroadcastReceiver {
    private final static String TAG = "ProtifyReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intents.PROTIFY_INTENT.equals(intent.getAction())) {
            InstallState result = install(intent.getExtras(), context);
            Activity top = LifecycleListener.getInstance().getTopActivity();
            if (result.dex) {
                if (top != null) {
                    Intent reset = new Intent(context, ProtifyActivity.class);
                    reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(reset);
                } else {
                    Process.killProcess(Process.myPid());
                }
            } else if (result.resources) {
                ProtifyContext.loadResources(context);
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
                    bringToFront.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    context.startActivity(bringToFront);
                }
            }
        } else if (intent != null && Intents.INSTALL_INTENT.equals(intent.getAction())) {
            InstallState result = install(intent.getExtras(), context);
            if (result.dex || result.resources)
                Process.killProcess(Process.myPid());
        } else if (intent != null && Intents.CLEAN_INTENT.equals(intent.getAction())) {
            Log.v(TAG, "Clearing resources and dex from cache");
            try {
                ProtifyContext.getResourcesFile(context).delete();
                File[] files = DexLoader.getDexExtractionDir(context).listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            } catch (Throwable t) {
                // noop don't care
            }
            if (LifecycleListener.getInstance().getTopActivity() != null) {
                Intent reset = new Intent(context, ProtifyActivity.class);
                reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(reset);
            } else {
                Process.killProcess(Process.myPid());
            }
        }
    }

    private InstallState install(Bundle extras, Context context) {
        if (extras != null) {
            String resources = extras.getString(Intents.EXTRA_RESOURCES);
            String[] dex = extras.getStringArray(Intents.EXTRA_DEX);
            String[] dexNames = extras.getStringArray(Intents.EXTRA_DEX_NAMES);
            boolean hasDex = dex != null && dexNames != null &&
                    dex.length == dexNames.length && dex.length > 0;
            if (resources != null)
                ProtifyContext.updateResources(context, resources, false);
            File resapk = new File(resources);
            if (hasDex) {
                try {
                    for (int i = 0; i < dex.length; i++) {
                        Log.v(TAG, "Loading DEX from " + dex[i] + " to " + dexNames[i]);
                        File dexfile = new File(dex[i]);
                        FileChannel ch = new FileInputStream(dexfile).getChannel();
                        File dexDir = DexLoader.getDexExtractionDir(context);
                        File dest = new File(dexDir, dexNames[i]);
                        FileChannel ch2 = new FileOutputStream(dest, false).getChannel();
                        ch.transferTo(0, dexfile.length(), ch2);
                        ch.close();
                        ch2.close();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Cannot copy DEX: " + e.getMessage(), e);
                }
            }
            return new InstallState((resapk.isFile() && resapk.length() > 0), hasDex);
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
