package com.hanhuy.android.protify.agent.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.hanhuy.android.protify.Intents;
import com.hanhuy.android.protify.agent.Protify;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;

/**
 * @author pfnguyen
 */
public class ProtifyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intents.PROTIFY_INTENT.equals(intent.getAction())) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                String resources = extras.getString(Intents.EXTRA_RESOURCES);
                String dex = extras.getString(Intents.EXTRA_DEX);
                if (resources != null)
                    Protify.updateResources(context, resources);
                if (dex != null) {
                    File dexfile = new File(dex);
                    if (dexfile.isFile() && dexfile.length() > 0) {
                        try {
                            FileChannel ch = new FileInputStream(dexfile).getChannel();
                            File dest = DexLoader.getDexFile(context);
                            FileChannel ch2 = new FileOutputStream(dest, false).getChannel();
                            ch.transferTo(0, dexfile.length(), ch2);
                            ch.close();
                            ch2.close();
                        } catch (Exception e) {
                            throw new RuntimeException("Cannot copy DEX: " + e.getMessage(), e);
                        }
                        Intent reset = new Intent(context, ProtifyActivity.class);
                        reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(reset);
                    }
                }
            }
        }
    }
}
