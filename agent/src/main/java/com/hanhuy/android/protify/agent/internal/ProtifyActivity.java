package com.hanhuy.android.protify.agent.internal;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.widget.TextView;

/**
 * android recreates the previous activity (with saved state) in the task stack
 * when an application crashes. create a new activity and "crash" it, forcing
 * the underlying activity to be recreated
 * @author pfnguyen
 */
public class ProtifyActivity extends Activity {
    private final static String STATE_SAVED = "protify.state.SAVED";
    private int asDp(int px, float d) {
        return (int) (px * d);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean isOn = Build.VERSION.SDK_INT < 7 || pm.isScreenOn();
        boolean isKG = km.inKeyguardRestrictedInputMode();
        if (!isOn || isKG) {
            finish();
            return;
        }
        TextView tv = new TextView(this);
        tv.setText("Protifying code...");
        float d = getResources().getDisplayMetrics().density;
        tv.setPadding(asDp(8, d), asDp(8, d), asDp(8, d), asDp(8, d));
        setContentView(tv);
        if (Build.VERSION.SDK_INT >= 11 &&
                (state == null || !state.getBoolean(STATE_SAVED, false))) {
            recreate();
        } else {
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
    }

/*
    @Override
    protected void onPostResume() {
        super.onPostResume();
        // TODO find the correct event where we don't have to hack a delay
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Process.killProcess(Process.myPid());
            }
        }, 1000);
    }
    */

    @Override
    protected void onSaveInstanceState(Bundle state) {
        // ensure state so that the framework will restore our activity/task stack
        state.putBoolean(STATE_SAVED, true);
        super.onSaveInstanceState(state);
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                Process.killProcess(Process.myPid());
            }
        });
    }
}
