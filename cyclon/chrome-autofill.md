# Chrome sign-in boundary (CYC-779)

Services supports **owner-initiated Chrome autofill** for forms with explicit
native field origins through Android's native AutofillService. The owner must enable Services as the Android autofill service
and choose **Autofill using another service** in Chrome. Chrome compatibility
mode is refused: it reconstructs page information from accessibility content.
Native requests are accepted only from an installed Chrome channel whose signer
has the existing Google CREDENTIALS permission. Password fields can be identified
from Chrome's native HTML input type and autocomplete metadata, including forms
without an autocomplete attribute. Android displays the suggestion; the existing
owner authentication activity returns its dataset to Android.

Each selected field must carry its own HTTPS scheme and domain. A parent form's
domain is not inherited: Chrome can flatten inputs from multiple frames under
one form. If Chrome omits field-origin forwarding, Services refuses that form.
This reduces compatibility with older Chrome or disabled forwarding but avoids
selecting a parent site's password for a child frame. Explicit matching fields
and password-only forms remain supported by the owner flow.

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

## CYC-781 producer-to-consumer audit

The reopened investigation used the local Chrome **145.0.7632.218**, version
**763221832**, APK SHA-256
`21bef8d34fcce4acb06be1a31142ec42690838824552673670f5cc26b57a27d5`
(rehashed during this pass), and the pinned Google Play services
**26.11.36 / 261136035** decompilation described in
[the UI reference](password-manager-ui-reference.md). The proprietary sources
remain outside this repository. Their obfuscated names below identify the
inspected version; partial decompilation is not runtime acceptance. Public
producer sources are pinned to Chromium tag `145.0.7632.218`.

| Boundary | Observed contract and implication |
| --- | --- |
| Renderer → browser | [`ContentAutofillDriver::Lift`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/autofill/content/browser/content_autofill_driver.cc) sets each field's frame token and origin from the browser's `RenderFrameHost` and its last committed origin. Thus a trustworthy full origin exists inside Chrome; the HTML form's own attributes do not authorize it. |
| Browser → Java form | [`FormDataAndroidBridgeImpl`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/android_autofill/browser/form_data_android_bridge_impl.cc) transfers the form origin; [`FormFieldDataAndroidBridgeImpl`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/android_autofill/browser/form_field_data_android_bridge_impl.cc) separately transfers the serialized field origin, including a nondefault port. Pinned APK `FormData.c` and `FormFieldData.m` are the corresponding Java values. |
| Java form → Android | [`FormData.fillViewStructure`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/android_autofill/browser/java/src/org/chromium/components/autofill/FormData.java) assigns focus and a virtual field ID. It passes the form origin to `setWebDomain`; per-field origins go through the same method only when `ANDROID_AUTOFILL_FORWARD_IFRAME_ORIGIN` is enabled. Pinned APK `defpackage/p3p.java:174,198` has both calls and the feature branch. No separate full-origin extra is emitted. Android then discards ports. |
| Android → Google detector | Pinned GMS `defpackage/ardt.java:39-43,101-105` reads each node's `AutofillId`, focus and `getWebDomain`; this reader supplies no hidden full-origin channel. Services cannot recover discarded information from Google's field detector. |
| Chrome hints Binder | [`IAutofillHintsService`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/android_autofill/browser/java/src/org/chromium/components/autofill_public/IAutofillHintsService.aidl) only registers a callback. [`ViewType`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/android_autofill/browser/java/src/org/chromium/components/autofill_public/ViewType.java) contains an AutofillId and type/prediction strings, without origin or a fill operation. The APK `on1` Binder and `ViewType.writeToParcel` agree. |
| Dataset → browser | [`AutofillRequest.autofill`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/android_autofill/browser/java/src/org/chromium/components/autofill/AutofillRequest.java) accepts IDs from its current form session and updates the matching field. The ID encodes a session and field index; it is not a one-use credential capability, and this method does not require that the field is still focused. |
| Browser → renderer fill | [`AndroidAutofillProvider`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/android_autofill/browser/android_autofill_provider.cc) retains the queried field's global ID, frame host and origin internally. It hides the popup when that frame is deleted or navigates to another document, and resets session state when its manager is reset. [`AutofillDriverRouter`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/autofill/core/browser/foundations/autofill_driver_router.cc) routes permitted fields back to their owning frames. These browser safeguards do not export the full origin or make external dataset IDs single use. |

The Chrome first-party password-store backend is not a substitute. Its
[`getLoginsForSignonRealm`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/chrome/browser/password_manager/android/java/src/org/chromium/chrome/browser/password_manager/PasswordStoreAndroidBackendDispatcherBridgeImpl.java)
passes a realm into a password-list query; the pinned APK's dispatcher creates
`ListOptions` with that query. It has no focused-field token, and the same
backend supports listing all autofillable logins. Authorizing a store read would
not prove delivery to the requested field or invalidate a previously returned
password when focus changes. No private backend was repurposed for agent grants.

The remaining boundary is therefore precise: Chrome knows the browser-verified
frame origin and field identity, but the stock Android contract does not export
both full authority and a revocable fill operation to Services/Core. Combining
an unrelated top-tab AssistContent URL with these IDs cannot restore that binding.

### Minimum browser-side integration

A Chromium integration can reuse that native field/frame identity. It must expose
a Core-only or Services-only Binder API that captures the focused password input,
returns its browser-verified full HTTPS origin plus a random one-use handle, and
fills only through that handle. The stored handle needs the tab/WebContents,
RenderFrameHost/document generation, global field ID and exact origin. It must
expire on navigation, reload, frame deletion, tab changes, focus changes,
cancellation and process death, and recheck the live field at final dispatch.
Reusing the 16-bit virtual session/index pair alone is insufficient.

For the initial contract, reject child-frame inputs rather than infer main-frame
ownership from matching hostnames; same-host different-port frames must remain
distinct. The API must verify the actual installed authorized caller UID/signing
identity, return only nonsecret status, and release a secret only after Core's
durable browser-UID privacy fence is acknowledged. Returning a populated Android
dataset without this handshake is not an agent-safe implementation. These are
requirements for new Chromium code, not features present in the stock APK.

The field-origin owner-flow regression tests added during this pass cover missing
child metadata under an HTTPS parent, explicit iframe metadata, mismatched
username/password fields, insecure schemes and malformed domain components.
Execution belongs to the parent task's gathered checks; this investigation did
not run a new build, individual test suite or physical-phone command.
