package com.hanhuy.android.protify.agent.internal;

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
        final String action = intent == null ? null : intent.getAction();
        Log.v(TAG, "Received action: " + action);
        Activity top = Build.VERSION.SDK_INT < 14 ? null : LifecycleListener.getInstance().getTopActivity();
        if (Intents.PROTIFY_INTENT.equals(action)) {
            InstallState result = install(intent.getExtras(), context);
            if (result.dex) {
                Log.v(TAG, "Updated dex, restarting process");
                if (top != null) {
                    Intent reset = new Intent(context, ProtifyActivity.class);
                    reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(reset);
                } else {
                    Process.killProcess(Process.myPid());
                }
            } else if (result.resources) {
                Log.v(TAG, "Updated resources, recreating activities");
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
        } else if (Intents.INSTALL_INTENT.equals(action)) {
            InstallState result = install(intent.getExtras(), context);
            if (result.dex || result.resources) {
                Log.v(TAG, "Installed new resources or dex, restarting process");
                Process.killProcess(Process.myPid());
            }
        } else if (Intents.CLEAN_INTENT.equals(action)) {
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
            if (top != null) {
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
            boolean hasRes = ProtifyContext.updateResources(context, resources, false);
            if (hasDex) {
                try {
                    // TODO implement copy into a final ZIP file for v4-13 support
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
