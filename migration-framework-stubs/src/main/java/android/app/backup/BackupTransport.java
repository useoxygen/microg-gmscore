/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package android.app.backup;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/** Compile-only signatures of Android's hidden BackupTransport API. Never packaged.
 * Runtime implementation and its IBackupTransport Binder are supplied by Android. */
public class BackupTransport {
    public final IBinder getBinder() { throw new RuntimeException("Stub!"); }
    public String name() { throw new RuntimeException("Stub!"); }
    public Intent configurationIntent() { throw new RuntimeException("Stub!"); }
    public String currentDestinationString() { throw new RuntimeException("Stub!"); }
    public Intent dataManagementIntent() { throw new RuntimeException("Stub!"); }
    public CharSequence dataManagementIntentLabel() { throw new RuntimeException("Stub!"); }
    public String transportDirName() { throw new RuntimeException("Stub!"); }
    public int initializeDevice() { throw new RuntimeException("Stub!"); }
    public int performBackup(PackageInfo pkg, ParcelFileDescriptor data, int flags) { throw new RuntimeException("Stub!"); }
    public int performFullBackup(PackageInfo pkg, ParcelFileDescriptor data, int flags) { throw new RuntimeException("Stub!"); }
    public int clearBackupData(PackageInfo pkg) { throw new RuntimeException("Stub!"); }
    public int finishBackup() { throw new RuntimeException("Stub!"); }
    public int sendBackupData(int bytes) { throw new RuntimeException("Stub!"); }
    public RestoreSet[] getAvailableRestoreSets() { throw new RuntimeException("Stub!"); }
    public long getCurrentRestoreSet() { throw new RuntimeException("Stub!"); }
    public int startRestore(long token, PackageInfo[] packages) { throw new RuntimeException("Stub!"); }
    public RestoreDescription nextRestorePackage() { throw new RuntimeException("Stub!"); }
    public int getRestoreData(ParcelFileDescriptor data) { throw new RuntimeException("Stub!"); }
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) { throw new RuntimeException("Stub!"); }
    public int abortFullRestore() { throw new RuntimeException("Stub!"); }
    public void finishRestore() { throw new RuntimeException("Stub!"); }
    public int getTransportFlags() { throw new RuntimeException("Stub!"); }
}
