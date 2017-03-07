/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hanhuy.android.protify.agent.internal;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

/**
 * Handler capable of restarting parts of the application in order for changes to become
 * apparent to the user:
 * <ul>
 *     <li> Apply a tiny change immediately (possible if we can detect that the change
 *          is only used in a limited context (such as in a layout) and we can directly
 *          poke the view hierarchy and schedule a paint
 *     <li> Apply a change to the current activity. We can restart just the activity
 *          while the app continues running.
 *     <li> Restart the app with state persistence (simulates what happens when a user
 *          puts an app in the background, then it gets killed by the memory monitor,
 *          and then restored when the user brings it back
 *     <li> Restart the app completely.
 * </ul>
 */
public class Restarter {
    private final static String LOG_TAG = "ProtifyRestarter";

    /**
     * Attempt to restart the app. Ideally this should also try to preserve as much state as
     * possible:
     * <ul>
     *     <li>The current activity</li>
     *     <li>If possible, state in the current activity, and</li>
     *     <li>The activity stack</li>
     * </ul>
     *
     * This may require some framework support. Apparently it may already be possible
     * (Dianne says to put the app in the background, kill it then restart it; need to
     * figure out how to do this.)
     */
    public static void restartApp(@Nullable Context appContext,
                                  @NonNull Collection<Activity> knownActivities,
                                  boolean toast) {
        if (!knownActivities.isEmpty()) {
            // Can't live patch resources; instead, try to restart the current activity
            Activity foreground = getForegroundActivity(appContext);

            if (foreground != null) {
                // http://stackoverflow.com/questions/6609414/howto-programatically-restart-android-app
                //noinspection UnnecessaryLocalVariable
                if (toast) {
                    showToast(foreground, "Protifying code...");
                }
                if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                    Log.v(LOG_TAG, "RESTARTING APP");
                }
                @SuppressWarnings("UnnecessaryLocalVariable") // fore code clarify
                Context context = foreground;
                Intent original = foreground.getIntent();
                Intent intent = original != null ? original : new Intent(context, foreground.getClass());
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
                        intent, PendingIntent.FLAG_CANCEL_CURRENT);
                AlarmManager mgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent);
                if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                    Log.v(LOG_TAG, "Scheduling activity " + foreground
                            + " to start after exiting process");
                }
            } else {
                showToast(knownActivities.iterator().next(), "Unable to restart app");
                if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
                    Log.v(LOG_TAG, "Couldn't find any foreground activities to restart " +
                            "for resource refresh");
                }
            }
            System.exit(0);
        }
    }

    static void showToast(@NonNull final Activity activity, @NonNull final String text) {
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "About to show toast for activity " + activity + ": " + text);
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context context = activity.getApplicationContext();
                    if (context instanceof ContextWrapper) {
                        Context base = ((ContextWrapper) context).getBaseContext();
                        if (base == null) {
                            if (Log.isLoggable(LOG_TAG, Log.WARN)) {
                                Log.w(LOG_TAG, "Couldn't show toast: no base context");
                            }
                            return;
                        }
                    }

                    // For longer messages, leave the message up longer
                    int duration = Toast.LENGTH_SHORT;
                    if (text.length() >= 60 || text.indexOf('\n') != -1) {
                        duration = Toast.LENGTH_LONG;
                    }

                    // Avoid crashing when not available, e.g.
                    //   java.lang.RuntimeException: Can't create handler inside thread that has
                    //        not called Looper.prepare()
                    Toast.makeText(activity, text, duration).show();
                } catch (Throwable e) {
                    if (Log.isLoggable(LOG_TAG, Log.WARN)) {
                        Log.w(LOG_TAG, "Couldn't show toast", e);
                    }
                }
            }
        });
    }

    @Nullable
    public static Activity getForegroundActivity(@Nullable Context context) {
        List<Activity> list = getActivities(context, true);
        return list.isEmpty() ? null : list.get(0);
    }

    // http://stackoverflow.com/questions/11411395/how-to-get-current-foreground-activity-context-in-android
    @NonNull
    public static List<Activity> getActivities(@Nullable Context context, boolean foregroundOnly) {
        List<Activity> list = new ArrayList<Activity>();
        try {
            Class activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = getActivityThread(context, activityThreadClass);
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);

            // TODO: On older platforms, cast this to a HashMap

            Collection c;
            Object collection = activitiesField.get(activityThread);

            if (collection instanceof HashMap) {
                // Older platforms
                Map activities = (HashMap) collection;
                c = activities.values();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                    collection instanceof ArrayMap) {
                ArrayMap activities = (ArrayMap) collection;
                c = activities.values();
            } else {
                return list;
            }
            for (Object activityRecord : c) {
                Class activityRecordClass = activityRecord.getClass();
                if (foregroundOnly) {
                    Field pausedField = activityRecordClass.getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (pausedField.getBoolean(activityRecord)) {
                        continue;
                    }
                }
                Field activityField = activityRecordClass.getDeclaredField("activity");
                activityField.setAccessible(true);
                Activity activity = (Activity) activityField.get(activityRecord);
                if (activity != null) {
                    list.add(activity);
                }
            }
        } catch (Throwable ignore) {
        }
        return list;
    }

    @Nullable
    public static Object getActivityThread(@Nullable Context context,
                                            @Nullable Class<?> activityThread) {
        try {
            if (activityThread == null) {
                activityThread = Class.forName("android.app.ActivityThread");
            }
            Method m = activityThread.getMethod("currentActivityThread");
            m.setAccessible(true);
            Object currentActivityThread = m.invoke(null);
            if (currentActivityThread == null && context != null) {
                // In older versions of Android (prior to frameworks/base 66a017b63461a22842)
                // the currentActivityThread was built on thread locals, so we'll need to try
                // even harder
                Field mLoadedApk = context.getClass().getField("mLoadedApk");
                mLoadedApk.setAccessible(true);
                Object apk = mLoadedApk.get(context);
                Field mActivityThreadField = apk.getClass().getDeclaredField("mActivityThread");
                mActivityThreadField.setAccessible(true);
                currentActivityThread = mActivityThreadField.get(apk);
            }
            return currentActivityThread;
        } catch (Throwable ignore) {
            return null;
        }
    }
}
