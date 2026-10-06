# Cyclon first-boot enrollment

`EnrollmentActivity` owns optional Google enrollment inside microG. LineageOS's
device-specific setup wrapper launches its permission-protected alias and
continues the wizard when the activity returns accepted or deferred. An internal
microG settings entry resumes deferred enrollment after setup. Account
tokens and the actual check-in ID remain inside microG. Caller-supplied URLs,
IDs and scripts are never accepted.

The activity uses the standard authenticator for account addition and microG's
existing weblogin flow for Google's registration page. Its page adapter checks
the Google account, fills the ID without floating-point conversion, attempts
the checkbox once and submits after verification. Image challenges remain
interactive. This revision has no model puzzle solver.

Google can return `WILL_NOT_SIGN_IN` for silent web login; in that case the
activity opens Google's normal registration/sign-in page. Authentication may
need another password/MFA interaction. The navigation guard includes Google's
specific `gds.google.com/web/landing` and `myaccount.google.com/accounts/SetOSID`
session redirects, without admitting other subdomains or paths. Polling is
bounded to ten minutes and can be resumed with Check registration.

The registered-ID list is the acceptance signal. A private account/ID-scoped
receipt records admission before submission and prevents automatic replay after
process loss. No auth URL, account token, CAPTCHA response or raw page text is
logged or exported by this implementation. Google navigation is restricted to
HTTPS account/registration origins, file/content access and mixed content are
disabled, and SSL errors are never bypassed.

```sh
node --test enrollment/test_enrollment.mjs
ANDROID_HOME=/path/to/sdk ./gradlew --no-configuration-cache \
  :people-sync:test :play-services-core:assembleMapboxDefaultDebug
```

APK assembly and the page fixtures are separate from live Google, setup-wizard,
dedicated-signer and physical-phone acceptance. Cyclon's production source and
immutable APK locks must be advanced together after those checks; this source
PR alone does not change shipped artifacts.

Live validation on a fresh Cyclon ARM64 emulator reached this screen from the
real setup wizard after network/date setup, added a designated Google account
through the authenticator, and registered the emulator's real check-in ID.
The tester completed Google's image puzzle; the page adapter submitted and
confirmed the exact ID in Google's list. Continue and Set up later both returned
to restore; Back returned to enrollment. After stopping the app and changing
the local receipt from accepted to attempted as a recovery fixture, reopening
reconciled Google's existing ID list and restored accepted without another
submission. This does not simulate every possible network/process-loss timing.

The emulator used a compiled debug microG APK and a repacked, public-test-key
wizard fixture. Dedicated signing, a Soong system image and physical-phone
acceptance are still pending.
