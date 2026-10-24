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
- `--locked` locks the phone with a temporary PIN (7316). It checks the lock screen, unlocks like the owner, answers the prompt, and removes the PIN at the end. Run it under a `--whole` claim.
- `--signin` signs in with the password from the Keychain item `cyclon-test-google-cora`, instead of using recovery. Google asks the phone at sign-in only on accounts with 2-Step Verification.

Google sends the prompt to every phone signed in to the account, not only the one under test.

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
  - An unanswered prompt was still shown 12 minutes later. Google sent nothing after the first ~1.5 s. #40 adds a notification timeout from the prompt's `expiryTime`.
