package com.hanhuy.android.protify.agent.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import com.hanhuy.android.protify.Intents;

import java.util.List;

/**
 * @author pfnguyen
 */
public class ProtifyReceiver2 extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean ltV14 = Build.VERSION.SDK_INT < 14;
        Activity top = ltV14 ? null : LifecycleListener.getInstance().getTopActivity();
        if (Intents.RECREATE_INTENT.equals(action)) {
            if (!ltV14)
                recreate(context, top);
            else
                restart(context, top, ltV14);
        } else if (Intents.RESTART_INTENT.equals(action)) {
            restart(context, top, ltV14);
        } else if (Intents.STOP_INTENT.equals(action)) {
            stop();
        }
    }

    static void recreate(Context context, Activity top) {
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
    static void restart(Context context, Activity top, boolean ltV14) {
        if (top != null || ltV14) {
            Intent reset = new Intent(context, ProtifyActivity.class);
            reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(reset);
        } else {
            Process.killProcess(Process.myPid());
        }
    }

    static void stop() {
        Process.killProcess(Process.myPid());
    }

    @TargetApi(11)
    private static void recreateActivity(Activity a) {
        if (a != null) a.recreate();
    }
}
