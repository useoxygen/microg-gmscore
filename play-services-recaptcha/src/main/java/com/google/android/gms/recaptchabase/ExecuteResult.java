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

import org.microg.gms.common.Hide;

import java.util.Arrays;

/**
 * reCAPTCHA Base execution result. Field 1 carries the base64 payload envelope that every client reads. Field 2 carries
 * the same envelope in its byte form, which newer Play services versions add and older clients ignore.
 */
@SafeParcelable.Class
public final class ExecuteResult extends AbstractSafeParcelable {
    @Field(value = 1, getterName = "getPayload")
    @Nullable
    private final String payload;
    @Field(value = 2, getterName = "getPayloadBytes")
    @Nullable
    private final byte[] payloadBytes;

    @Constructor
    @Hide
    public ExecuteResult(@Param(1) @Nullable String payload, @Param(2) @Nullable byte[] payloadBytes) {
        this.payload = payload;
        this.payloadBytes = payloadBytes;
    }

    @Nullable
    public String getPayload() {
        return payload;
    }

    @Hide
    @Nullable
    public byte[] getPayloadBytes() {
        return payloadBytes;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ExecuteResult)) return false;
        ExecuteResult that = (ExecuteResult) other;
        return (payload == null ? that.payload == null : payload.equals(that.payload)) && Arrays.equals(payloadBytes, that.payloadBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{payload, Arrays.hashCode(payloadBytes)});
    }

    /**
     * Deliberately omits the payload, which carries an attestation token.
     */
    @NonNull
    @Override
    public String toString() {
        return "ExecuteResult{payload=" + (payload == null ? "null" : "<" + payload.length() + " chars>")
                + ", payloadBytes=" + (payloadBytes == null ? "null" : "<" + payloadBytes.length + " bytes>") + "}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<ExecuteResult> CREATOR = findCreator(ExecuteResult.class);

    public static final class Builder {
        @Nullable
        private String payload;
        @Nullable
        private byte[] payloadBytes;

        public Builder() {
        }

        public Builder(@NonNull ExecuteResult origin) {
            payload = origin.payload;
            payloadBytes = origin.payloadBytes;
        }

        @NonNull
        public Builder setPayload(@Nullable String payload) {
            this.payload = payload;
            return this;
        }

        @Hide
        @NonNull
        public Builder setPayloadBytes(@Nullable byte[] payloadBytes) {
            this.payloadBytes = payloadBytes;
            return this;
        }

        @NonNull
        public ExecuteResult build() {
            return new ExecuteResult(payload, payloadBytes);
        }
    }
}
