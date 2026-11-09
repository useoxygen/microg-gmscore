// Modified by Cyclon in 2026: a reply that is present on the wire is kept even when it carries no supplemental action.
/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.droidguard.internal;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import androidx.annotation.Nullable;

public class DroidGuardInitReply implements Parcelable {
    public @Nullable ParcelFileDescriptor pfd;
    public @Nullable Parcelable object;

    public DroidGuardInitReply(@Nullable ParcelFileDescriptor pfd, @Nullable Parcelable object) {
        this.pfd = pfd;
        this.object = object;
    }

    @Override
    public int describeContents() {
        return (pfd != null ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0) | (object != null ? object.describeContents() : 0);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(pfd, flags);
        dest.writeParcelable(object, flags);
    }

    public final static Creator<DroidGuardInitReply> CREATOR = new Creator<DroidGuardInitReply>() {
        @Override
        public DroidGuardInitReply createFromParcel(Parcel source) {
            ParcelFileDescriptor pfd = source.readParcelable(ParcelFileDescriptor.class.getClassLoader());
            Parcelable object = source.readParcelable(getClass().getClassLoader());
            // The reply is present: an absent reply is a null marker in the Parcel and never reaches this creator. A
            // service answers DroidGuardInitReply(null, null) when the client has nothing more to do; returning null
            // here would make the client take that for a service without initWithRequest and initialize again.
            return new DroidGuardInitReply(pfd, object);
        }

        @Override
        public DroidGuardInitReply[] newArray(int size) {
            return new DroidGuardInitReply[size];
        }
    };
}
