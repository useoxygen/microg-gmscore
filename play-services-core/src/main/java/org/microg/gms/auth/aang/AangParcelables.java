/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.aang;

import android.net.Network;
import org.microg.safeparcel.AutoSafeParcelable;
import java.util.List;

/** Field IDs from Switch 1140078 iiu/iiv/iix/iiy/iiz/ijb/ijc and GMS GetTokenRequest. */
public final class AangParcelables {
    private AangParcelables() {}

    public static class GoogleAccount extends AutoSafeParcelable {
        @Field(1) public String gaiaId;
        @Field(2) public String accountType;
        @Field(3) public String accountName;
        public static final Creator<GoogleAccount> CREATOR = new AutoCreator<>(GoogleAccount.class);
    }

    public static class GetAccountsRequest extends AutoSafeParcelable {
        @Field(1) public String accountType;
        @Field(2) public List<String> capabilities;
        @Field(3) public List<String> accountFeatures;
        @Field(4) public boolean includeRestrictedAccounts;
        @Field(5) public boolean includeTransientAccounts;
        public static final Creator<GetAccountsRequest> CREATOR = new AutoCreator<>(GetAccountsRequest.class);
    }

    public static class GetAccountsResponse extends AutoSafeParcelable {
        @Field(1) public List<GoogleAccount> accounts;
        // Field 2 is restricted-account metadata; requests for it are unsupported.
        public static final Creator<GetAccountsResponse> CREATOR = new AutoCreator<>(GetAccountsResponse.class);
    }

    public static class GetTokenRequest extends AutoSafeParcelable {
        @Field(1) public GoogleAccount account;
        @Field(2) public String gaiaId;
        @Field(3) public List<String> oauth2Scopes;
        @Field(4) public List<String> webLoginUrls;
        @Field(5) public List<String> clientLoginScopes;
        @Field(6) public List<String> oauth2TokenIdScopes;
        @Field(7) public int delegationType;
        @Field(8) public String delegateeUserId;
        @Field(9) public boolean handleNotification;
        @Field(10) public byte[] tokenRequestOptions;
        @Field(11) public String clientPackageName;
        @Field(12) public boolean suppressProgressScreen;
        @Field(13) public Network network;
        @Field(14) public boolean useNewExceptions;
        @Field(15) public int clientVersion;
        public static final Creator<GetTokenRequest> CREATOR = new AutoCreator<>(GetTokenRequest.class);
    }

    public static class TokenMetadata extends AutoSafeParcelable {
        @Field(1) public Long expiry;
        @Field(2) public List<String> grantedScopes;
        public static final Creator<TokenMetadata> CREATOR = new AutoCreator<>(TokenMetadata.class);
    }

    public static class GetTokenResponse extends AutoSafeParcelable {
        @Field(1) public String token;
        @Field(2) public TokenMetadata metadata;
        public static final Creator<GetTokenResponse> CREATOR = new AutoCreator<>(GetTokenResponse.class);
    }

    public static class HasCapabilitiesRequest extends AutoSafeParcelable {
        @Field(1) public GoogleAccount account;
        @Field(2) public List<String> capabilities;
        public static final Creator<HasCapabilitiesRequest> CREATOR = new AutoCreator<>(HasCapabilitiesRequest.class);
    }
}
