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

Google sends the prompt to every phone signed in to the account, not only the one under test.

## Reading a failure

Each run writes `reports/report-*.json`, which contains timed steps and the run's `Gms2FA` lines.

| Result | Meaning |
| --- | --- |
| No `received` | Google did not deliver the push. Check readiness: `gms_registered`, `chime_registered`. |
| `route stopped: event=...` | The route dropped the message. The event name gives the reason. |
| `confirmation button not found` | Google's page changed or did not load. The error lists the OCR text of the screen. |

The phone is located by screenshot OCR because Cyclon phones block `uiautomator dump`.

## Result on 2026-10-23

Prometheus (Pixel 8a, GmsCore 252432048): approve and deny both passed. The prompt was shown about 0.6 s after Google sent it.
