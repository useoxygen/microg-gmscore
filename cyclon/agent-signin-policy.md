# Per-login device authentication

The owner controls two local properties of a saved login: Ask / Allow sign-in /
Never, and **Require device authentication**. Requiring authentication disables
Allow sign-in (an existing Allow becomes Ask), preserves Never, and needs a
configured Android screen lock. Removing the requirement does not restore an old
Allow grant; the owner can explicitly choose a new access level. Both transitions
rotate the incarnation so a waiting request cannot inherit a different policy.

Core receives only account metadata, including `require_authentication`. For a
strict login it asks the native broker to redeem and opens the returned Services
PendingIntent. The broker keeps the selected entry and one-use request in memory;
the Intent contains only a handle. `PasswordAgentAuthenticationActivity` displays
the actual package or HTTPS origin and username in a fresh system biometric or device-credential
challenge. Its native foreground/focus proof is checked again under the vault and
grant locks immediately before the private ResultReceiver handover. An unlocked
phone, a caller-provided boolean, restored activity state, or a stale reference is
not authentication. Cancellation, expiry and process death invalidate the request.

The legacy agent picker enforces the same Never and authentication rules. This
does not change owner-initiated Android Autofill, which has its own authentication
and origin checks. Grants and authentication requirements are not uploaded to
Google. Test or device evidence is reported separately from this protocol.

This policy authorizes password use only. It does not implement the additional
confirmation gates for payments, bookings, sending messages or security changes.
Matching Core/controller builds now have a separate authenticated local Chrome
path bound to a main-frame document and one-use field object. Its host/component
and device acceptance are tracked separately. The native whole-UID privacy fence
remains in place after delivery; generic post-login continuation remains open.
