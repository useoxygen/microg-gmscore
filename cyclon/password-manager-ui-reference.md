# Password manager UI reference

CYC-779 follows the familiar structure of Google's password manager while adding
per-login Cyclon sign-in permission. This is a source comparison, not a claim of
pixel or runtime parity.

The pinned reference recorded in `password-manager-parity.json` is Google Play
services **26.11.36 (260400-892401869)**, version code **261136035**, APK SHA-256
`35f23040de6362a77103edb4b23825cfed7310ab6efe4d59310f817d79e6c1c2`.
The inspected decompilation is `/tmp/cyc654-reference/gms-decompiled/sources/`.
Its `com/google/android/gms/credential/manager/PasswordManagerChimeraActivity.java:59`
header agrees with that version. The APK was not rehashed during this UI pass.

Line anchors below refer to that pinned decompilation, relative to its `sources/`
directory. Obfuscated class names and line numbers are version-specific.

| Reference evidence | Choice in Cyclon Services |
| --- | --- |
| `defpackage/bmxb.java:19-24` defines Credentials, Checkup and Settings destinations. `defpackage/bmxl.java:40-50` binds a pager and navigation bar. | Separate Passwords, Checkup and Settings tabs instead of placing every management action before the login list. Tabs change local UI state; selecting one does not start a refresh. |
| `defpackage/bncc.java:105-121` creates `pwm_settings_screen` and embeds blocklist and ChromeSync settings. `defpackage/bkga.java:214-227` binds export and import settings. | Keep sync, encryption, import/export, recovery and never-save controls under Settings. Preserve their existing actions and owner checks. |
| `defpackage/blxk.java:33-48` distinguishes passwords, search, add, settings, detail, edit and checkup screens. | Open a saved login's details from its list row; show its existing Edit, Move and Delete actions there. Search and Add stay with the Passwords list. |
| `defpackage/bmez.java:34-50` selects compact password-row layouts. `defpackage/bmex.java:25-35` binds an affiliated group's account count and row click. `defpackage/bmem.java:30-59` binds its icon and display name. | Use compact app/site and username rows which open details. Existing per-login identity remains unchanged; this does not introduce Google's affiliated-group model or account-count aggregation. |

The **Sign in with Cyclon** section in each login's details, its last-used value,
and the Ask each time / Allow sign-in / Never dialog are Cyclon additions. They
are not attributed to Google's UI. The choices retain explicit Save and Cancel,
describe revocation, and distinguish using a saved login from reading its
password. Locking clears the detail/permission UI; removing a login clears its
detail selection.

## Limits of the comparison

The decompilation's `resources/` directory is empty in the inspected tree, so the
evidence above establishes screen structure, resource identifiers and bindings,
not the resolved text, dimensions, colors or icon assets. The reference contains
runtime choices between `pwm_home_screen_old` and `pwm_home_screen`
(`defpackage/bmxl.java:37-40,77`) and theme selection
(`PasswordManagerChimeraActivity.java:198-202`). No Google runtime cohort was
opened or visually compared in this pass. Cyclon uses its existing Material
theme and a bounded tab/detail adaptation; exact navigation placement and
visual parity are unverified.

This UI pass checked whitespace, XML parsing, unique string names and string
references. It did not run an APK build or device tests. Existing or subsequent
permission/device acceptance must be reported separately from Google visual
parity. The comparison made no changes to vault, broker or permission semantics.
