# Google prompt end-to-end test

Checks the GNOTS route from PR #23 against Google's real servers.

1. A fresh Chrome profile on the Mac starts account recovery ("Forgot password?") for a test account. Google then asks the account's phones to confirm.
2. On the phone, the script waits for the `Gms2FA` events `received`, `fetched` and `shown`.
3. It taps the notification, then taps "Yes, it's me" (or "No, don't allow" with `--deny`) on Google's page.
4. It checks that the browser moved past "Check your phone", or that Google reports the refusal.

The run never sets a new password.

## Requirements

- A Mac with Google Chrome and Xcode command-line tools.
- `npm install && npm run build-ocr` in this directory.
- The password of the test account is not needed. The account `coraliss.mail@gmail.com`, or `GPROMPT_EMAIL`, must be signed in on the phone in GmsCore.
- The phone must be unlocked, with adb access. On shared dev phones, take a claim first:

```sh
phone-claim run SERIAL --for "Google prompt e2e" --uses com.google.android.gms -- node e2e.mjs SERIAL
phone-claim run SERIAL --for "Google prompt e2e" --uses com.google.android.gms -- node e2e.mjs SERIAL --deny
```

Modes:
- `--deny` taps "No, don't allow" and expects Google to stop the attempt.
- `--locked` locks the phone with a temporary PIN (7316). It checks the lock screen, unlocks like the owner, answers the prompt, and removes the PIN at the end, also after an error or Ctrl-C. It then checks that no PIN is left. Run it under a `--whole` claim.
- `--signin` signs in with the password from the Keychain item `cyclon-test-google-cora`, instead of using recovery. Google asks the phone at sign-in only on accounts with 2-Step Verification.

`observe.mjs SERIAL 5 30 90 2.5m 4m` leaves one prompt unanswered. It notes which prompt notification this run posted (older ones are ignored), reports at each check whether that one is still up, then prints the run's `Gms2FA` events. With #40 a prompt stays up until Google's expiry, about 2–3 minutes, and is then removed.

Google sends the prompt to every phone signed in to the account, not only the one under test. A phone that is still on a build before #40 reports "dismissed" as soon as it shows a prompt, and Google then withdraws the prompt from all devices about 1.5 s later (`dismissed_remote`). For tests of expiry or of prompts staying up, keep the test account only on phones with #40.

`resend.mjs SERIAL 230 40 100` leaves one prompt unanswered and clicks Google's "Resend it" after 40 and 100 s. It prints the ids of the posted prompt notifications over time; read the `Gms2FA` events afterwards.

With the full-screen change (`claude/google-prompt-fullscreen-resend`), an unlocked phone in use opens Google's page as soon as the prompt arrives (`event=opened_direct`). `e2e.mjs` still taps the notification; that page replaces the first one and reports the prompt as opened.

The route's events are also kept in a journal that survives logcat rotation (last 400 lines, epoch-millisecond times):

```sh
adb -s SERIAL shell dumpsys activity service com.google.android.gms/org.microg.gms.gcm.McsService
```

Many recoveries or denials in a row make Google pause prompts for the account for a few hours ("Too many failed attempts"). Wrong passwords count too. The script then fails with a clear message. Keep runs few, for example before a microG release.

## Reading a failure

Each run writes `reports/report-*.json`, which contains timed steps and the run's `Gms2FA` lines.

| Result | Meaning |
| --- | --- |
| No `received` | Google did not deliver the push. Check readiness: `gms_registered`, `chime_registered`. |
| `route stopped: event=...` | The route dropped the message. The event name gives the reason. |
| `confirmation button not found` | Google's page changed or did not load. The error lists the OCR text of the screen. |

The phone is located by screenshot OCR because Cyclon phones block `uiautomator dump`.

## Results on 2026-10-23

- Prometheus (Pixel 8a, GmsCore 252432048): approve and deny both passed. The prompt was shown about 0.6 s after Google sent it.
- Dev 7a (GmsCore 252432049):
  - Approve, deny and `--locked` passed.
  - An unanswered prompt was still shown 12 minutes later. Google sent nothing after the first ~1.5 s.
- Prometheus (GmsCore 252432061, with #40):
  - An unanswered prompt stayed up until Google's expiry (`timeout_min=2`), then was removed and reported as dismissed.
  - Approve and deny passed; the phone reported "opened" when the page opened.
  - A push delivered 4 minutes late was not shown (`event=expired`).

## Results on 2026-10-28 (resend)

Dev 7a (GmsCore 254132101, before the full-screen change), one Google account, `resend.mjs` with two resends:
- Each "Resend it" reached the phone about 3 s later as a new push with a **new GNOTS unique id**. Each was fetched and shown as a new notification (`received`, `fetched count=1`, `shown timeout_min=2`). Nothing was deduplicated or removed.
- Each notification was heads-up for about 5.7 s (`airtimeMs` in `dumpsys notification`) and then stayed only in the shade. This matches the field report from the founders' Pixel 8a, where the owner did not notice the prompt.
- Google named the recipients as "your Android, Pixel 7a, and 2 more", so the test account is signed in on other phones too.
