// Modified by Cyclon in 2026: isInitialized() asks the service whether the handle initialized, not only whether it is alive.
// A framed flow's usable snapshot response is framed with the handle's client trace (CYC-1544).
/*
 * SPDX-FileCopyrightText: 2022 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard;

import android.util.Log;

import com.google.android.gms.droidguard.DroidGuardHandle;
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest;
import com.google.android.gms.droidguard.internal.IDroidGuardHandle;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class DroidGuardHandleImpl implements DroidGuardHandle {
    private static final String TAG = "DroidGuardHandle";

    /** {@code getInitState} of a service that does not have the transaction (its empty reply reads as 0). */
    public static final int INIT_STATE_UNKNOWN = 0;
    public static final int INIT_STATE_INITIALIZED = 1;
    public static final int INIT_STATE_NOT_INITIALIZED = 2;

    private final DroidGuardApiClient apiClient;
    private final DroidGuardResultsRequest request;
    /** The init trace of a framed flow's handle; null for every other handle. */
    private final DroidGuardClientTrace trace;
    private IDroidGuardHandle handle;
    private byte[] error;

    public DroidGuardHandleImpl(DroidGuardApiClient apiClient, DroidGuardResultsRequest request, IDroidGuardHandle handle) {
        this(apiClient, request, handle, null);
    }

    public DroidGuardHandleImpl(DroidGuardApiClient apiClient, DroidGuardResultsRequest request, IDroidGuardHandle handle, DroidGuardClientTrace trace) {
        this.apiClient = apiClient;
        this.request = request;
        this.handle = handle;
        this.trace = trace;
    }

    public DroidGuardHandleImpl(DroidGuardApiClient apiClient, DroidGuardResultsRequest request, String error) {
        this.apiClient = apiClient;
        this.request = request;
        this.trace = null;
        this.error = Utils.getErrorBytes(error);
    }

    @Override
    public String snapshot(Map<String, String> data) {
        byte[] result;
        DroidGuardClientTrace snapshotTrace = null;
        if (error != null) {
            result = error;
        } else {
            if (trace != null) {
                snapshotTrace = trace.copy();
                snapshotTrace.record(DroidGuardClientTrace.SNAPSHOT_START);
            }
            ArrayBlockingQueue<byte[]> resultQueue = new ArrayBlockingQueue<>(1);
            apiClient.runOnHandler(() -> {
                byte[] innerResult;
                try {
                    innerResult = handle.snapshot(data);
                    if (innerResult == null) {
                        error = Utils.getErrorBytes("Received null");
                        innerResult = error;
                    }
                } catch (Exception e) {
                    error = Utils.getErrorBytes("Snapshot failed: " + e);
                    innerResult = error;
                }
                resultQueue.offer(innerResult);
            });
            try {
                result = resultQueue.poll(request.getTimeoutMillis(), TimeUnit.MILLISECONDS);
                if (result == null) {
                    result = Utils.getErrorBytes("Snapshot timeout: " + request.getTimeoutMillis() + " ms");
                }
            } catch (InterruptedException e) {
                result = Utils.getErrorBytes("Results transfer failed: " + e);
            }
        }
        if (snapshotTrace != null && DroidGuardResponseFrame.shouldFrame(result)) {
            snapshotTrace.record(DroidGuardClientTrace.SNAPSHOT_END);
            result = DroidGuardResponseFrame.frame(result, snapshotTrace, DroidGuardResponseFrame.clientUuid(apiClient.getContext()));
        }
        return Utils.toBase64(result);
    }

    @Override
    public boolean isOpened() {
        return handle != null && error == null && handle.asBinder().pingBinder();
    }

    /**
     * Whether the service's handle initialized its DroidGuard runtime. {@link #isOpened()} only shows that the handle's
     * Binder is alive: the service keeps an initialization failure to itself and answers later snapshots with error
     * markers. A service without the {@code getInitState} transaction answers {@link #INIT_STATE_UNKNOWN}; then
     * {@link #isOpened()} decides, as before. Makes a synchronous Binder call.
     */
    public boolean isInitialized() {
        IDroidGuardHandle handle = this.handle;
        if (handle == null || error != null || !handle.asBinder().pingBinder()) return false;
        try {
            int state = handle.getInitState();
            return state == INIT_STATE_INITIALIZED || state == INIT_STATE_UNKNOWN;
        } catch (Exception e) {
            Log.w(TAG, "Init state unavailable");
            return false;
        }
    }

    @Override
    public void close() {
        apiClient.runOnHandler(() -> {
            if (handle != null) {
                try {
                    handle.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error while closing handle.");
                }
                apiClient.markHandleClosed();
                handle = null;
            }
        });
    }
}
