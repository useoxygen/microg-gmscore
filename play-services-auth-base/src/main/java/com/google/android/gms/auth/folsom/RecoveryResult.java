/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.auth.folsom;

import android.os.Parcel;

import androidx.annotation.NonNull;

import com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelableCreatorAndWriter;

@SafeParcelable.Class
public class RecoveryResult extends AbstractSafeParcelable {
    @Field(1) public int result;
    @Field(2) public long retryAfter;

    public RecoveryResult() {}

    @Constructor
    public RecoveryResult(@Param(1) int result, @Param(2) long retryAfter) {
        this.result = result;
        this.retryAfter = retryAfter;
    }

    public int getResult() { return result; }
    public long getRetryAfter() { return retryAfter; }

    @Override public boolean equals(Object other) {
        return other instanceof RecoveryResult && result == ((RecoveryResult) other).result
                && retryAfter == ((RecoveryResult) other).retryAfter;
    }
    @Override public int hashCode() { return java.util.Objects.hash(result, retryAfter); }
    @NonNull @Override public String toString() { return "RecoveryResult[redacted]"; }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<RecoveryResult> CREATOR = findCreator(RecoveryResult.class);
}
