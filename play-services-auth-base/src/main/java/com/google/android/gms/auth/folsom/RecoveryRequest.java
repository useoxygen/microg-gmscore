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
import java.util.Arrays;
import java.util.Objects;

@SafeParcelable.Class
public class RecoveryRequest extends AbstractSafeParcelable {
    @Field(1) public String accountName;
    @Field(2) public String accountType;
    @Field(3) public byte[] secretHash;
    @Field(4) public byte[] vaultParameters;
    @Field(5) public byte[] vaultChallenge;
    @Field(6) public byte[] vaultMetadata;

    public RecoveryRequest() {}

    @Constructor
    public RecoveryRequest(@Param(1) String accountName, @Param(2) String accountType,
                           @Param(3) byte[] secretHash, @Param(4) byte[] vaultParameters,
                           @Param(5) byte[] vaultChallenge, @Param(6) byte[] vaultMetadata) {
        this.accountName = accountName;
        this.accountType = accountType;
        this.secretHash = copy(secretHash);
        this.vaultParameters = copy(vaultParameters);
        this.vaultChallenge = copy(vaultChallenge);
        this.vaultMetadata = copy(vaultMetadata);
    }

    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }

    public String getAccountName() { return accountName; }
    public String getAccountType() { return accountType; }
    public byte[] getSecretHash() { return copy(secretHash); }
    public byte[] getVaultParameters() { return copy(vaultParameters); }
    public byte[] getVaultChallenge() { return copy(vaultChallenge); }
    public byte[] getVaultMetadata() { return copy(vaultMetadata); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RecoveryRequest)) return false;
        RecoveryRequest value = (RecoveryRequest) other;
        return Objects.equals(accountName, value.accountName) && Objects.equals(accountType, value.accountType)
                && Arrays.equals(secretHash, value.secretHash) && Arrays.equals(vaultParameters, value.vaultParameters)
                && Arrays.equals(vaultChallenge, value.vaultChallenge) && Arrays.equals(vaultMetadata, value.vaultMetadata);
    }
    @Override public int hashCode() {
        return Objects.hash(accountName, accountType, Arrays.hashCode(secretHash), Arrays.hashCode(vaultParameters),
                Arrays.hashCode(vaultChallenge), Arrays.hashCode(vaultMetadata));
    }
    @NonNull @Override public String toString() { return "RecoveryRequest[redacted]"; }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<RecoveryRequest> CREATOR = findCreator(RecoveryRequest.class);
}
