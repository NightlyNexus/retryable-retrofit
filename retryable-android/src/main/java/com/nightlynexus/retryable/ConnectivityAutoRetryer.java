package com.nightlynexus.retryable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;

public final class ConnectivityAutoRetryer {
  private static final IntentFilter CONNECTIVITY_FILTER = new IntentFilter(CONNECTIVITY_ACTION);

  private final Context context;
  private final BroadcastReceiver receiver;

  public ConnectivityAutoRetryer(final RetryableCalls retryableCalls, Context context) {
    this.context = context;
    receiver = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        ConnectivityManager manager =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = manager.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null
            && activeNetwork.isConnectedOrConnecting();
        if (isConnected) {
          retryableCalls.retryAllCalls();
        }
      }
    };
  }

  public void register() {
    context.registerReceiver(receiver, CONNECTIVITY_FILTER);
  }

  public void unregister() {
    context.unregisterReceiver(receiver);
  }
}
