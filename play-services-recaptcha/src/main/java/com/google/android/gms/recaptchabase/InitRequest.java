/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.recaptchabase;

import android.os.Parcel;

import androidx.annotation.NonNull;

import com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelableCreatorAndWriter;

/**
 * Empty reCAPTCHA Base parcel. It has no fields, but the SafeParcel object header is still written and required.
 */
@SafeParcelable.Class
public final class InitRequest extends AbstractSafeParcelable {
    @Constructor
    public InitRequest() {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InitRequest;
    }

    @Override
    public int hashCode() {
        return InitRequest.class.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "InitRequest{}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<InitRequest> CREATOR = findCreator(InitRequest.class);

    public static final class Builder {
        public Builder() {
        }

        public Builder(@NonNull InitRequest origin) {
        }

        @NonNull
        public InitRequest build() {
            return new InitRequest();
        }
    }
}
