# Google Contacts access lab

This optional debug app checks whether a selected Google account can authorize
the production People API client. It reads every contacts page and verifies the
sync-token response without changing Google contacts or the Android provider.
It does not implement periodic sync or replace GmsCore. A separately guarded
disposable test exercises live create/edit/delete after read authorization works.

Add `modules.contactsLab=true` to the ignored `local.properties`, then build:

```sh
./gradlew :people-sync-lab:assembleDebug :people-sync-lab:lintDebug
```

The app ID is `org.microg.gms.contacts.lab`. Release variants are disabled and
the module is excluded by default. Runtime entry points refuse user builds.
Installation alongside upstream-signed microG preserves its accounts and signer.

For a background check on the exact authorized test device:

```sh
adb -s DEVICE_SERIAL install -r people-sync-lab/build/outputs/apk/debug/people-sync-lab-debug.apk
adb -s DEVICE_SERIAL shell am start-foreground-service \
  -n org.microg.gms.contacts.lab/.ContactsProbeService --es accountName ACCOUNT_EMAIL
adb -s DEVICE_SERIAL shell run-as org.microg.gms.contacts.lab cat files/access-probe.json
```

The brief foreground-service notification does not take over the screen. Missing
consent is recorded instead of opening an authorization activity. A successful
read-only check leaves both Google contacts and provider rows unchanged. The
receipt contains only an account hash, counts, HTTP status/allowlisted error
reason, and an exception class; it never contains tokens or contact content.
Tokens remain inside the authorized Android process and normal microG token
cache. Do not collect unfiltered authenticator logs: installed upstream builds
may log sensitive authentication data.

Interactive account/Google consent requires a short exclusive screen window:

```sh
adb -s DEVICE_SERIAL shell am start -n org.microg.gms.contacts.lab/.ContactsLabActivity \
  --es accountName ACCOUNT_EMAIL
```

Tap **Verify Google contacts access**. The app requests only
`https://www.googleapis.com/auth/contacts.readonly`, using its actual package and
certificate. It supplies no package/certificate overrides or OAuth client secret.
Google can reject this identity with `UNREGISTERED_ON_API_CONSOLE` until the
corresponding Android OAuth client is registered.

The interactive check uses the public `GoogleAuthUtil` SDK against microG's auth
service. Its recovery intent uses a microG-created PendingIntent to open consent.
An AccountManager activity overload cannot launch the raw private consent
activity returned by the currently installed upstream build. Background checks
still use AccountManager and never launch a recovery activity. The Google SDK is
a dependency of this optional test app only, not the production sync libraries.
The installed build's AppCompat redirect activity has an incompatible NoDisplay
theme and crashes after launching consent. For that exact microG redirect only,
the lab sends the embedded PendingIntent directly after checking its creator is
`com.google.android.gms`. This opens the same consent activity with the same
identity checks. The native redirect theme fix requires separate signed delivery.

Register this test app's own Android package and signing-certificate SHA-1 in an
authorized Google Cloud project, enable the People API, and configure consent
for the selected test user. Obtain the exact certificate from the built artifact:

```sh
apksigner verify --print-certs people-sync-lab/build/outputs/apk/debug/people-sync-lab-debug.apk
```

For the explicitly authorized physical 00555 only, after releasing its screen
from other device sessions, prepare a disposable two-way test:

```sh
python3 people-sync-lab/tools/prepare-roundtrip.py --serial 3B241JEKB00555 --account ACCOUNT_EMAIL
```

Tap **Test disposable contact round trip** and complete normal Google contacts
consent. This requests full contacts scope for the temporary test only. A fresh
private device/fingerprint/nonce/account proof expires after five minutes. The
test uses the production engine and Android store with a unique synthetic
provider account. Cloud writes are fenced to one uniquely named contact created
by this run. It verifies phone create/edit/delete, a Google edit pulled to the
provider, Google-side absence, fixture-account cleanup, and unchanged existing
Google/provider fingerprints. It never configures sync on the real Google
provider account. The receipt is `files/roundtrip-receipt.json`.

Interrupted/uncertain effects retain `files/roundtrip-state.json` and provider
journals for reconciliation. A new run refuses while that journal exists. Do not
delete it, repeat a create, or uninstall the lab before resolving a cleanup-required
receipt. Receipts omit tokens and contact content.

The temporary app's registration does not validate GmsCore's OAuth identity or
its delivery/signing migration. Native settings, account retention, background
sync, renewal/revocation and an actual Google two-way round trip still require
their own acceptance checks. Remove only this lab package when the test is done.
