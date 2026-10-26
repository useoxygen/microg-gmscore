# Reported Play services version

microG reports one Play services version to apps (the package's `versionCode`) and to
Google's servers (`Constants.GMS_VERSION_CODE`, sent in auth, check-in, GCM/IID,
DroidGuard, CryptAuth, passwords, Find My Device and Wallet requests). Upstream's
release pins its own version (`v0.3.17.252432` reports 25.24.32). Cyclon chooses the
reported version in `version.json` (`reportedGmsVersion`) and allocates delivery
version codes inside it.

## Why it matters

Google's client libraries compare the installed Play services version with each
API's minimum (`GmsClient.getMinApkVersion()`). If it is lower, the call fails with
`SERVICE_VERSION_UPDATE_REQUIRED` and the library posts "Update Google Play services
to use APP", a dead end on a phone whose Play services come with the OS.

A higher version is not free either. Apps branch on it: above an API's minimum they
bind that API instead of an older path. If microG does not implement the newer API,
a feature that works today stops working. So the version only rises as far as the
APIs microG serves.

## Current choice: 25.41.32 (2026-10-25)

`tools/scan-gms-min-versions.py APK` lists every Play services client in an APK with
its minimum version. Ten current Google apps (Switch 1140078, Maps, Gmail, Photos,
YouTube, Google app, Meet, Gboard, Gemini, Authenticator; pulled from a founders
phone) need more than 25.24 for only three APIs:

| API | Service | Minimum | Clients |
|---|---|---|---|
| Notification telemetry | 449 | 25.36 | all ten |
| Throttling telemetry | 483 | 25.36 | all ten |
| Cloud Messaging | `cloudmessaging.service.START` | 26.12 | Maps, Photos, Gmail, Meet, Google app, YouTube |

Switch also enables its `post_setup_pair` D2D feature from 25.41, which
`SwitchSupportService` advertises. At 25.24 every Google app posted the update
notification as soon as it reported notification telemetry.

So the reported version is 25.41.32, and services 449 and 483 accept and discard
their reports (`ClientTelemetryService.kt`). It stays below 26.12 because microG has no
Cloud Messaging service: above it those apps would leave the GCM path microG
implements. `cyclon/tests` asserts that ceiling.

## Raising it again

1. Scan current versions of the Google apps customers use and note every API above
   the version you want.
2. Make sure microG serves each of those APIs, or that apps fall back cleanly.
3. Set `reportedGmsVersion` and a fresh `gmsVersionCode`/`vendingVersionCode`
   allocation; it must stay above every shipped APK.
4. Device acceptance: Google sign-in, push (FCM), Google prompt, passwords, Workspace
   mail, Play Integrity, and no "Update Google Play services" notification from the
   scanned apps.
