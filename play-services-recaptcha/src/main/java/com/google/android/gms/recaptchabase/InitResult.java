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
public final class InitResult extends AbstractSafeParcelable {
    @Constructor
    public InitResult() {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InitResult;
    }

    @Override
    public int hashCode() {
        return InitResult.class.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "InitResult{}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<InitResult> CREATOR = findCreator(InitResult.class);

    public static final class Builder {
        public Builder() {
        }

        public Builder(@NonNull InitResult origin) {
        }

        @NonNull
        public InitResult build() {
            return new InitResult();
        }
    }
}
