/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.recaptchabase;

import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelableCreatorAndWriter;

import java.util.Arrays;

/**
 * reCAPTCHA Base execution request. The nonce is bound into the DroidGuard input, the execute ID is attached to the
 * returned payload envelope.
 */
@SafeParcelable.Class
public final class ExecuteRequest extends AbstractSafeParcelable {
    @Field(value = 1, getterName = "getNonce")
    @Nullable
    private final String nonce;
    @Field(value = 2, getterName = "getExecuteId")
    @Nullable
    private final String executeId;

    @Constructor
    public ExecuteRequest(@Param(1) @Nullable String nonce, @Param(2) @Nullable String executeId) {
        this.nonce = nonce;
        this.executeId = executeId;
    }

    @Nullable
    public String getNonce() {
        return nonce;
    }

    @Nullable
    public String getExecuteId() {
        return executeId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExecuteRequest)) return false;
        ExecuteRequest that = (ExecuteRequest) other;
        return equal(nonce, that.nonce) && equal(executeId, that.executeId);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{nonce, executeId});
    }

    /**
     * Deliberately omits the nonce and execute ID: both are request-bound secrets.
     */
    @NonNull
    @Override
    public String toString() {
        return "ExecuteRequest{nonce=" + (nonce == null ? "null" : "<" + nonce.length() + " chars>")
                + ", executeId=" + (executeId == null ? "null" : "<" + executeId.length() + " chars>") + "}";
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<ExecuteRequest> CREATOR = findCreator(ExecuteRequest.class);

    public static final class Builder {
        @Nullable
        private String nonce;
        @Nullable
        private String executeId;

        public Builder() {
        }

        public Builder(@NonNull ExecuteRequest origin) {
            nonce = origin.nonce;
            executeId = origin.executeId;
        }

        @NonNull
        public Builder setNonce(@Nullable String nonce) {
            this.nonce = nonce;
            return this;
        }

        @NonNull
        public Builder setExecuteId(@Nullable String executeId) {
            this.executeId = executeId;
            return this;
        }

        @NonNull
        public ExecuteRequest build() {
            return new ExecuteRequest(nonce, executeId);
        }
    }
}
