package com.hanhuy.android.protify.agent.internal;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.widget.TextView;

/**
 * android recreates the previous activity in the task stack when an application
 * crashes. create a new activity and "crash" it, forcing the underlying
 * activity to be recreated
 * @author pfnguyen
 */
public class ProtifyActivity extends Activity {
    private int asDp(int px, float d) {
        return (int) (px * d);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Protifying code...");
        float d = getResources().getDisplayMetrics().density;
        tv.setPadding(asDp(8, d), asDp(8, d), asDp(8, d), asDp(8, d));
        setContentView(tv);
    }

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
}
