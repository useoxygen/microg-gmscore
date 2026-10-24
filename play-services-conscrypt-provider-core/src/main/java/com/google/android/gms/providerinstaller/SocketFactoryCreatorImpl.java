/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.providerinstaller;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Keep;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * Dynamite entry point clients load from com.google.android.gms.providerinstaller.dynamite to
 * get an SSLSocketFactory (ISocketFactoryCreator transaction 1: context, key managers, trust
 * managers, flag -> wrapped SSLSocketFactory). Google apps such as Android Switch use it for
 * their HTTPS calls; without it they crash with "Could not load creator class".
 */
@Keep
public class SocketFactoryCreatorImpl extends Binder {
    private static final String TAG = "GmsSocketFactory";
    private static final String DESCRIPTOR = "com.google.android.gms.common.net.ISocketFactoryCreator";
    private static final int TRANSACTION_NEW_SOCKET_FACTORY = 1;

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code != TRANSACTION_NEW_SOCKET_FACTORY) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        data.readStrongBinder(); // context, unused
        Object keyManagers = unwrap(data.readStrongBinder());
        Object trustManagers = unwrap(data.readStrongBinder());
        data.readInt(); // flag, unused
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers instanceof KeyManager[] ? (KeyManager[]) keyManagers : null,
                    trustManagers instanceof TrustManager[] ? (TrustManager[]) trustManagers : null, null);
            reply.writeNoException();
            reply.writeStrongBinder(ObjectWrapper.wrap(context.getSocketFactory()).asBinder());
        } catch (Exception e) {
            Log.w(TAG, "newSocketFactory failed", e);
            reply.writeException(new IllegalStateException(e.getMessage()));
        }
        return true;
    }

    private static Object unwrap(IBinder binder) {
        return binder == null ? null : ObjectWrapper.unwrap(IObjectWrapper.Stub.asInterface(binder));
    }
}
