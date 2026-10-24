# CYC-718: target eSIM transfer, GMS 26.41.31

Updated 2026-10-23, round 2. Owner decision: implement Google's target flow;
never test a real transfer without Thomas's separate explicit approval and a
**designated test line**. No eSIM, physical phone, carrier endpoint or eUICC has
been used by C. This is a partial implementation, not a carrier/Pixel acceptance
verdict. CYC-718 stays In Progress.

## Version fence

Contracts are pinned to Switch1140078 and GMS26.41.31/264131035. Only clean-room
implementation, short identifiers and protocol descriptions are included here;
private reverse-engineering input/source material is not shipped with this repo.

## Actual receiving flow and owners

1. Switch or EuiccGoogle/LPA selects a source and explicitly starts a target
   connector. Source ACTION_START_SOURCE_D2D_SERVICE is a different/source entry;
   implementing it does not implement receiving transfer.
2. GMS broker333, action com.google.android.gms.esim.service.START, returns
   com.google.android.gms.esim.internal.IEsimTransferService. Permission checks match the real caller UID in the pin: profile1 needs WRITE
   and READ_PRIVILEGED_PHONE_STATE; preparation5 needs READ only; starts6/10/20
   need WRITE only. Other protected APIs need WRITE. Source-Wi-Fi14/status16 and
   feature-disabled18/22 do not add those checks. Our service binds the broker package to
   the UID and binds its returned session Binder to that UID.
3. Target start connectorType1 (source2 is rejected48522) obtains the real pairing
   session/authentication key. iOS XOS carries protobuf at /_callback/esim,
   application/vnd.os-migration.esim+proto, serviceIdcom.google.android.gms.esim.
   Existing authenticated DCT is reused only when supplied credentials match;
   otherwise real supplied credentials create a separate ephemeral controller.
   SupportedServices is2 for SUW session3,1 for standalone eSIM; B defaults remain2.
4. Version/session negotiation returns source profile list. The target resolves
   Android carrierId from MCC/MNC/SPN/GID1/GID2 and applies carrier configuration.
   Source ICCID/profile state and the target UUID stay distinct. The next profile
   request obtains a source-issued temporary token and its actual expiry.
5. For supported token/TS.43 types2/7/11, target carrier entitlement uses appap2009,
   temporary_token, real target IMEI/EID and source identity. ManageSubscription
   operation_type3 requests transfer. AcquireConfiguration reconciles a previously
   dispatched/unknown outcome; an uncertain mutation is never replayed. Carrier
   WebSheet/delayed responses are separate from a usable activation credential.
6. Return ProfileTransferCredential to the caller. **A credential is not an
   installed eSIM.** EuiccGoogle/LPA owns EuiccManager.downloadSubscription,
   resolvable owner consent, ES9+/SM-DP+ installation and the actual eUICC callback.
   Our implementation never directly downloads, deletes or silently activates a
   subscription. It also does not replace EuiccGoogle's privileged entitlement
   UI/carrier authorization. Installed LPA compatibility still needs acceptance.
7. ActivateService tx3 uses a real target embedded subscription ID/ICCID, EID and
   ManageSubscription operation_type10. Operation_type1 is UNSUBSCRIBE: it must
   never be guessed for activation. Activation outcome is separately journalled;
   an unknown dispatch cannot be silently retried.
8. LPA notifies the real result through tx16; GMS sends the matching XOS status.
   Status API integers and XOS integers differ:0→1 success;1→5 ongoing download;
   2→6 denied;3→8 download failure;4→11 unsupported;5→13 carrier locked;
   6→12 user input;7→9 interaction; unknown→0. Session UUID and ICCID must match.
   End reports remaining ongoing operations as failures, never invented success.

## Binder coverage in C round 2

All callbacks are oneway transaction1 with their exact descriptor.

| tx | Input / result | C behavior |
|---|---|---|
|1|retrieve callback →Status+nullable ProfilesTransferData|Real authenticated session profiles;48502 without session|
|2|credential callback+ProfileTransferData →Status+nullable Credential|Bound profile/session + temporary token + target entitlement|
|3|activate callback+targetSubId+ProfileTransferData →Status|Real target subscription and carrier ActivateService; no eUICC installation claim|
|4|sync callback+SyncProfileCredentialRequest →Status|17 unsupported source synchronization|
|5|prepare callback →Status|17; source profile preparation is not target support|
|6|start callback+StartSessionRequest →Status|Target start/negotiation|
|7|end callback+EndSessionRequest →Status|Actual status notifications and cleanup|
|8|QR callback →Status+String|17+null; legacy Bluetooth MAC/AES link not fabricated|
|9/11/21|common Status callback+profile+verified callback|PINverified=false and48503; cancellation signal on11/21|
|10/20|start request+listener →cancel/signal Binder|Start plus real cancellation, no invented progress events|
|12/19|availability callback+sessionType(+connectorType) →Status+bool|Checks real eUICC support/target connector; not proof of carrier admission|
|13/17/23|conversion →Status+nullable conversion credential|17;23 returns its signal Binder|
|14|source Wi-Fi wait →Status+bool|17+false|
|15|pending credentials callback+listener →Status|17; no fabricated pending list|
|16|download callback+profile+LPA result →Status|Validates bound session/profile and sends exact mapped status|
|18|cloud profile callback →Status+nullable ProfilesTransferData|17; no cloud account/credential spoof|
|22|batch verified list →Status+verified flag+signal|Pinned feature-disabled48585+false|

Broker508/actioncom.google.android.gms.esim.provisioning.service.START is a
separate GMS feature-gated provisioning pipeline. The pinned disabled path returns
ConnectionResult16; C preserves that disabled result, not a stub successful
provisioning client. The old source-D2D entry is outside this target implementation.

## Remaining implementation and interoperability gaps

- **Carrier configuration discovery**: C currently consumes explicit CarrierInfo
  overrides from StartSessionRequest6. It does not yet implement Google's complete
  phenotype/carrier configuration discovery. No matching enabled configuration
  produces profile failureType4. This is a local implementation gap, **not** a
  carrier/server refusal and not a claim that all listed US carriers work.
- **PIN verified paths**:9/11/21 return48503 instead of falsely treating successful
  PAKE as the separately required PIN verification. Post-setup legacy Bluetooth
  MAC/AES QR and PIN flow still need implementing from the pinned source.
- **Source deletion required by carrier**: some type11/responseType8 flows notify
  XOS14, issue protobuf envelope7 with an EMPTY deletion request, validate envelope8
  session+ICCID/error, then AcquireConfiguration. C returns48538 for this response;
  it does not issue a deletion. Do not claim verified-before-delete can be applied
  unchanged: some carrier flows intentionally deactivate source before download.
  A future implementation needs durable unknown-outcome reconciliation before any
  retry and a visible interruption explanation; no real-line test is authorized.
- Transfer types3/6 use source-specific credential handling;8 uses other LPA
  flows. They return explicit unsupported in C, not an improvised TS.43 request.
- Carrier EAP-AKA authentication/recovery, retry511 policy, carrier messages,
  broad multi-profile/cloud/conversion flows, and server admission remain incomplete.
- Actual EuiccGoogle binding/service discovery, carrier WebSheet continuation,
  delayed download polling and physical callback integration remain untested.
  Runtime permission grants are also unverified until the combined image boots.
- DCT/PAKE implementation does not establish iPhone eSIM peer acceptance or a valid
  Google Integrity verdict. No Google/OEM/carrier identity or attestation is spoofed.
- Real target identity is sent. Google's test-only vendor/model overrides are not
  copied; carrier admission must apply to the actual Cyclon build/device.

## Image policy and distribution

Thomas chose bundled Google-signed Switch priv-app (#1221/#1227), no setup fetch.
Switch WRITE_EMBEDDED_SUBSCRIPTIONS is now granted in its experiment allowlist.
GMS WRITE_EMBEDDED_SUBSCRIPTIONS and READ_PRIVILEGED_PHONE_STATE accompany BACKUP
and LOCAL_MAC_ADDRESS only in the migration experiment; normal image decisions
explicitly deny these migration additions. GMS DUMP remains denied. Phone/user
administration is not added. The actual production flag/legal review is separate
from granting these necessary test-image permissions. Exact Switch artifacts
remain pinned in test-apks.json. No signed image/release/OTA is produced by C.

## Carrier constraints, current official sources

Apple's current supported US list includes the owner's four examples AT&T,
FirstNet,T-Mobile,Verizon, and also Cricket and Xfinity Mobile. It requires a
supported carrier and compatible Android device; iPhone11+/iOS26 and supported
Android16+ flows use Wi-Fi/Bluetooth. These are public product requirements, not
Cyclon/OEM/backend admission. Carrier account policy, locked devices, enterprise
lines and particular prepaid/MVNO plans may impose additional restrictions; these
must be tested with the designated line rather than inferred from the list.

- Apple: https://support.apple.com/en-us/123878 (Sep17,2026 publication)
- AOSP target/source carrier entitlement and LPA roles:
  https://source.android.com/docs/core/connect/esim-transfer-carrier-integration
- Android actual install/resolution callback API:
  https://developer.android.com/reference/android/telephony/euicc/EuiccManager
- TS.43 constants/target operation fields were cross-checked against AOSP
  android-34 com.android.libraries.entitlement source, not guessed from API names:
  https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/88c7ff1cd72d6305ec59f97aadc2198cc2dc3592/android-34/com/android/libraries/entitlement/

Context7 Android queries returned generic/unrelated entries for these private
contracts; the pinned binaries and primary sources above supply the evidence.

## Test plan

**Current offline tests only:** synthetic profile/token/ICCID, fake authenticated
peer and fake HTTP, no real carrier hostname, no EuiccManager mutation. Assert
pinned protobuf bytes, bounded/malformed SafeParcels, unknown-field preservation,
permission/UID rejection, callback schemas, missing-pair/unsupported source/PIN
errors, carrier401/403/511, expiry, XML entity rejection, source ICCID mismatch,
LPA failure mapping, cancel and closed-peer behavior. Lost/unsupported responses
must reconcile, not repeat ManageSubscription; returned credential must not claim
installed/activated. Exercise B's entire host migration suite after shared hooks.

**Before any designated-line trial:** Thomas explicitly names/approves the test
line and trial. Parent approves combined image/reboot, board grants the7a slot,
phone-claim covers only required changes (whole-device for approved reboot).
Confirm no owner/personal SIM can be selected, label/verify the designated source
line, use independent Wi-Fi, record recovery contacts/procedure and acknowledge
that the carrier may deactivate source during transfer. Confirm authentic LPA,
real permissions, carrier configuration and all required branches above are ready.
No broad source subscription cleanup, no source factory reset, no automatic retry.

**One approved trial:** record protocol stage/status and redacted operation receipt,
never token/activation code/IMEI/EID/ICCID/MSISDN in logs. Use the real owner-facing
selection and consent. On interruption, inspect/reconcile carrier/LPA state before
any retry; an unknown outcome stays unknown. Verify target eUICC installed state,
line identity, carrier registration, data, inbound/outbound call and SMS on the
specified line. eSIM source deactivation must match the actual carrier contract;
never claim it was deferred if the carrier did it earlier. Do not remove source
profile/test fixtures or release the source device until Thomas reviews results
and recovery works. Release the claim; preserve evidence and expected outages.
Repeat only under the same explicit approval scope, otherwise ask for a new trial.
