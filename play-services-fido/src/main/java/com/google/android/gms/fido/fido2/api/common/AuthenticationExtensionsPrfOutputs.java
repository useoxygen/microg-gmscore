/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.fido.fido2.api.common;

import android.os.Parcel;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelableCreatorAndWriter;

import org.microg.gms.utils.ToStringHelper;

@SafeParcelable.Class
public class AuthenticationExtensionsPrfOutputs extends AbstractSafeParcelable {

    @Field(value = 1, getterName = "isEnabled")
    private final boolean enabled;

    @Field(value = 2, getterName = "getResults")
    @Nullable
    private final byte[] results;

    @Constructor
    public AuthenticationExtensionsPrfOutputs(@Param(1) boolean enabled, @Param(2) @Nullable byte[] results) {
        if (results != null && results.length != 32 && results.length != 64) throw new IllegalArgumentException("Invalid PRF result");
        this.enabled = enabled;
        this.results = results == null ? null : results.clone();
    }

    public AuthenticationExtensionsPrfOutputs(boolean enabled, @Nullable byte[] first, @Nullable byte[] second) {
        this(enabled, combine(first, second));
    }

    private static byte[] combine(@Nullable byte[] first, @Nullable byte[] second) {
        if (first == null) {
            if (second != null) throw new IllegalArgumentException("Invalid PRF result");
            return null;
        }
        if (first.length != 32 || (second != null && second.length != 32)) throw new IllegalArgumentException("Invalid PRF result");
        byte[] result = new byte[second == null ? 32 : 64];
        System.arraycopy(first, 0, result, 0, 32);
        if (second != null) System.arraycopy(second, 0, result, 32, 32);
        return result;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public byte[] getFirst() {
        return results == null ? null : java.util.Arrays.copyOfRange(results, 0, 32);
    }

    @Nullable
    public byte[] getSecond() {
        return results == null || results.length != 64 ? null : java.util.Arrays.copyOfRange(results, 32, 64);
    }

    @Nullable
    public byte[] getResults() { return results == null ? null : results.clone(); }

    private String b64url(byte[] v) {
        return Base64.encodeToString(v, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<AuthenticationExtensionsPrfOutputs> CREATOR = findCreator(AuthenticationExtensionsPrfOutputs.class);

    @Override
    public String toString() {
        return "AuthenticationExtensionsPrfOutputs[redacted]";
    }
}
