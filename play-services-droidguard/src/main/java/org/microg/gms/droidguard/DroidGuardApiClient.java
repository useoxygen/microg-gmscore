// Modified by Cyclon in 2026: the legacy init runs only when initWithRequest returned no reply; incomplete replies are
// ignored, and a handle whose initialization failed is closed on the service. A handle of a framed flow carries its
// client trace (CYC-1544).
/*
 * SPDX-FileCopyrightText: 2022 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.gms.droidguard.DroidGuardHandle;
import com.google.android.gms.droidguard.internal.DroidGuardInitReply;
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest;
import com.google.android.gms.droidguard.internal.IDroidGuardHandle;
import com.google.android.gms.droidguard.internal.IDroidGuardService;

import org.microg.gms.common.GmsClient;
import org.microg.gms.common.GmsService;
import com.google.android.gms.common.api.internal.ConnectionCallbacks;
import com.google.android.gms.common.api.internal.OnConnectionFailedListener;

public class DroidGuardApiClient extends GmsClient<IDroidGuardService> {
    private static final String TAG = "DroidGuardApiClient";
    private final Context context;
    private int openHandles = 0;
    private Handler handler;
    private HandleProxyFactory factory;

    public DroidGuardApiClient(Context context, ConnectionCallbacks callbacks, OnConnectionFailedListener connectionFailedListener) {
        super(context, callbacks, connectionFailedListener, GmsService.DROID_GUARD.ACTION);
        this.context = context;
        serviceId = GmsService.DROID_GUARD.SERVICE_ID;

        HandlerThread thread = new HandlerThread("DG");
        thread.start();
        handler = new Handler(thread.getLooper());

        factory = new HandleProxyFactory(context);
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public DroidGuardHandle openHandle(String flow, DroidGuardResultsRequest request) {
        return openHandle(flow, request, DroidGuardResponseFrame.startTrace(flow));
    }

    /**
     * Opens a handle for {@code flow}. {@code trace}, started when the caller requested the handle, is null unless the
     * flow's responses are framed ({@link DroidGuardResponseFrame#startTrace}); it records when initialization ended.
     */
    public DroidGuardHandle openHandle(String flow, DroidGuardResultsRequest request, DroidGuardClientTrace trace) {
        IDroidGuardHandle handle = null;
        try {
            handle = getServiceInterface().getHandle();
            request.setOpenHandles(openHandles);
            DroidGuardInitReply reply = handle.initWithRequest(flow, request);
            if (reply == null) {
                // No reply at all: a service without initWithRequest (its empty reply reads as null), or one that
                // answers null. Only then does the one-way legacy init start the runtime; after a reply it would start
                // a second runtime without the request's options.
                handle.init(flow);
            } else {
                handleInitReply(reply);
            }
            openHandles++;
            if (trace != null) trace.record(DroidGuardClientTrace.INIT_END);
            return new DroidGuardHandleImpl(this, request, handle, trace);
        } catch (Exception e) {
            if (handle != null) {
                try {
                    handle.close();
                } catch (Exception closeError) {
                    Log.w(TAG, "Error while closing handle after failed initialization.");
                }
            }
            return new DroidGuardHandleImpl(this, request, "Initialization failed: " + e);
        }
    }

    private void handleInitReply(DroidGuardInitReply reply) {
        ParcelFileDescriptor pfd = reply.pfd;
        try {
            if (pfd != null && reply.object instanceof Bundle) {
                Log.w(TAG, "DroidGuardInitReply suggests additional actions in main thread");
                handleDroidGuardData(pfd, (Bundle) reply.object);
            } else if (pfd != null || reply.object != null) {
                Log.w(TAG, "Ignoring incomplete DroidGuardInitReply");
            }
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error while closing DroidGuardInitReply file descriptor.");
                }
            }
        }
    }

    private void handleDroidGuardData(ParcelFileDescriptor pfd, Bundle bundle) {
        String vmKey = bundle.getString("h");
        if (vmKey == null) {
            throw new RuntimeException("Missing vmKey");
        }
        HandleProxy proxy = factory.createHandle(vmKey, pfd, bundle);
        proxy.init();
        proxy.close();
    }

    public void markHandleClosed() {
        if (openHandles == 0) {
            Log.w(TAG, "Can't mark handle closed if none is open");
            return;
        }
        openHandles--;
        if (openHandles == 0) disconnect();
    }

    public void runOnHandler(Runnable runnable) {
        if (Looper.myLooper() == handler.getLooper()) {
            runnable.run();
        } else {
            handler.post(runnable);
        }
    }

    @Override
    protected IDroidGuardService interfaceFromBinder(IBinder binder) {
        return IDroidGuardService.Stub.asInterface(binder);
    }
}
