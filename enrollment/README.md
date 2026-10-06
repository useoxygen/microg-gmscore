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
