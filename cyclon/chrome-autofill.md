# Chrome sign-in boundary (CYC-779)

Services supports **owner-initiated Chrome autofill** through Android's native
AutofillService. The owner must enable Services as the Android autofill service
and choose **Autofill using another service** in Chrome. Chrome compatibility
mode is refused: it reconstructs page information from accessibility content.
Native requests are accepted only from an installed Chrome channel whose signer
has the existing Google CREDENTIALS permission. Password fields can be identified
from Chrome's native HTML input type and autocomplete metadata, including forms
without an autocomplete attribute. Android displays the suggestion; the existing
owner authentication activity returns its dataset to Android.

This does **not** enable unattended `security.password.fill` in Chrome. Ask,
Always and Never remain assistant grants; they do not make this owner picker an
assistant redemption path. No Always dataset bypass or agent fallback is added.
Neither host classifier tests nor successful owner autofill establish assistant
privacy, and no physical-device acceptance has been performed for this change.

## Why the assistant path remains closed

Android's [ViewNode API](https://developer.android.com/reference/android/app/assist/AssistStructure.ViewNode#getWebDomain())
exposes a document's scheme and host. Its
[implementation](https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/app/assist/AssistStructure.java)
parses `setWebDomain` with `Uri` and retains the host, discarding the port.
Consequently, these values cannot prove the exact HTTPS origin required for an
assistant grant, including the difference between default HTTPS and port 8443.

Chrome does supply the exact top-tab URL through
[`ChromeActivity.onProvideAssistContent`](https://chromium.googlesource.com/chromium/src/+/main/chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java),
outside incognito and overview mode. Android can retrieve this with a privileged
[`requestAssistContextExtras`](https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/app/IActivityTaskManager.aidl)
request. However, Chrome's
[`ViewStructureBuilder`](https://chromium.googlesource.com/chromium/src/+/main/content/public/android/java/src/org/chromium/content/browser/accessibility/ViewStructureBuilder.java)
for that assist snapshot exposes text, HTML attributes and bounds without a
focused-field identifier or a per-frame origin. A top-tab URL therefore cannot
authorize a password for an arbitrary focused field, which may belong to a
different frame. A URL read from the address bar is not a replacement.

Completing assistant support needs a browser-owned interface that reports the
exact focused frame origin and an opaque, one-use field/document handle, then
rechecks that handle and origin while performing the fill. Core must also install
its durable whole-Chrome-UID privacy fence **before** a secret is delivered: a
page can echo or reveal a password after autofill. Android's native dataset
picker still controls ordinary AutofillService selection; returning a dataset
does not programmatically select it on behalf of Core. See Android's
[autofill service guide](https://developer.android.com/identity/autofill/autofill-services).
