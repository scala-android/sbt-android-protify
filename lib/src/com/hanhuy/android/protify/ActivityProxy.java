package com.hanhuy.android.protify;

import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;

public interface ActivityProxy {
  public void onPreCreate(Activity activity, Bundle state);
  public void onCreate(Activity activity, Bundle state);
  public void onDestroy(Activity activity);
  public void onPause(Activity activity);
  public void onResume(Activity activity);
  public void onStart(Activity activity);
  public void onStop(Activity activity);
  public void onCreateOptionsMenu(Activity activity, Menu menu);
  public boolean onOptionsItemSelected(Activity activity, MenuItem item);

  public static class Simple implements ActivityProxy {
    public void onPreCreate(Activity activity, Bundle state) {}
    public void onCreate(Activity activity, Bundle state) {}
    public void onDestroy(Activity activity) {}
    public void onPause(Activity activity) {}
    public void onResume(Activity activity) {}
    public void onStart(Activity activity) {}
    public void onStop(Activity activity) {}
    public void onCreateOptionsMenu(Activity activity, Menu menu) {}
    public boolean onOptionsItemSelected(Activity activity, MenuItem item) {
      return false;
    }
  }
}
