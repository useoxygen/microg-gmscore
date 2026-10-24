/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.checkin;

import android.app.PendingIntent;
import android.app.AlarmManager;
import android.os.SystemClock;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.core.app.PendingIntentCompat;
import androidx.legacy.content.WakefulBroadcastReceiver;

import org.microg.gms.common.ForegroundServiceContext;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.os.Build.VERSION.SDK_INT;
import static org.microg.gms.checkin.CheckinService.EXTRA_FORCE_CHECKIN;
import static org.microg.gms.checkin.CheckinService.REGULAR_CHECKIN_INTERVAL;

public class TriggerReceiver extends WakefulBroadcastReceiver {
    private static final String TAG = "GmsCheckinTrigger";
    static final String ACTION_NETWORK = "org.microg.gms.checkin.NETWORK_AVAILABLE";
    static final String ACTION_RETRY = "org.microg.gms.checkin.NETWORK_RETRY";
    static final long NETWORK_RETRY_DELAY = 5 * 60 * 1000L;
    private static final CheckinNetworkRetry networkRetry = new CheckinNetworkRetry();

    private static PendingIntent networkIntent(Context context, String action) {
        return PendingIntentCompat.getBroadcast(context, 0,
                new Intent(context, TriggerReceiver.class).setAction(action), FLAG_UPDATE_CURRENT, true);
    }

    private static void unregister(ConnectivityManager cm, PendingIntent intent) {
        if (SDK_INT >= 23) {
            try { cm.unregisterNetworkCallback(intent); }
            catch (IllegalArgumentException ignored) { /* no outstanding registration */ }
        }
    }

    private static void clearNetworkWait(Context context, ConnectivityManager cm) {
        unregister(cm, networkIntent(context, ACTION_NETWORK));
        // Also retire registrations made by older versions (which used an actionless broadcast).
        unregister(cm, networkIntent(context, null));
        ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(networkIntent(context, ACTION_RETRY));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean networkDelivery = ACTION_NETWORK.equals(intent.getAction())
                    || intent.hasExtra(ConnectivityManager.EXTRA_NETWORK);
            if (networkDelivery) clearNetworkWait(context, cm);
            boolean force = "android.provider.Telephony.SECRET_CODE".equals(intent.getAction());

            if (CheckinPreferences.isEnabled(context) || force) {
                if (LastCheckinInfo.read(context).getLastCheckin() > System.currentTimeMillis() - REGULAR_CHECKIN_INTERVAL && !force) {
                    clearNetworkWait(context, cm);
                    CheckinService.schedule(context);
                    return;
                }

                NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                if (networkInfo != null && networkInfo.isConnected() || force) {
                    clearNetworkWait(context, cm);
                    Intent subIntent = new Intent(context, CheckinService.class);
                    subIntent.putExtra(EXTRA_FORCE_CHECKIN, force);
                    startWakefulService(new ForegroundServiceContext(context), subIntent);
                } else if (SDK_INT >= 23) {
                    long now = SystemClock.elapsedRealtime();
                    // A matching physical network can exist while policy blocks this UID. Such a
                    // callback fires immediately; registering it again here used to starve main.
                    NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                    boolean physicalInternet = capabilities != null && capabilities.hasCapability(NET_CAPABILITY_INTERNET);
                    if (networkRetry.shouldRegister(now, networkDelivery, physicalInternet)) {
                        clearNetworkWait(context, cm);
                        cm.registerNetworkCallback(new NetworkRequest.Builder()
                                .addCapability(NET_CAPABILITY_INTERNET).build(), networkIntent(context, ACTION_NETWORK));
                    }
                    AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                    alarms.set(AlarmManager.ELAPSED_REALTIME, now + NETWORK_RETRY_DELAY, networkIntent(context, ACTION_RETRY));
                    Log.d(TAG, "Checkin waiting for usable connectivity; retry in 5 minutes");
                }
            } else {
                clearNetworkWait(context, cm);
                Log.d(TAG, "Ignoring " + intent + ": checkin is disabled");
            }
        } catch (Exception e) {
            Log.w(TAG, e);
        }
    }
}
