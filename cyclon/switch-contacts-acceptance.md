# Switch contacts: implementation and device acceptance

Part of CYC-700. Source/host implementation only until these checks run on the
installed image. This workstream does not install, publish, merge, use adb or touch
a phone. The parent owns device acceptance.

Switch 1140078 decompile evidence (under
`~/research/cyc700-switch/android-switch-1140078/src/sources/defpackage/`):

- `hmg.java:1059-1069` and `eil.java:678-686`: Google-account raw inserts preserve
  source IDs without microG ownership. `eif.java:14` does not read an original
  etag/baseline; these cannot be recovered from transferred fields.
- `rct.java:131,164,603-604`, `rcq.java:149-150`: the exact namespace
  `com.google.android.apps.pixelmigrate` has
  `D2d__enable_contacts_restore_to_google_account=false` by default.
- `dou.java:2469`, `dou.java:247`, `eib.java:72`, `eil.java:1145-1157`: the iPhone
  writer passes no destination when that flag is off, producing local contacts.
  The iPhone DTO converter (`dou.java:182-242`) supplies no Google source ID.
- `dsc.java:599-633` chooses an actually visible source-backup/first Google account
  when the flag is enabled; `ecl.java:139-159,748-787` discloses the destination.
  `iim.java:34-75` falls back from AANG to the existing auth-account provider.
  Therefore AANG support alone does not fix the disabled writer destination.

microG previously returned no provider flags for this namespace. The narrow local
configuration enables Switch's existing Google-destination path in both direct
provider and service reads. It does not change Android's default contact account,
move existing DEVICE rows, enable sync, or release the migration upload hold.

Run with disposable contacts and record raw-account/source/ownership/dirty/journal
state, raw and aggregate counts, and actual Google-side data after each step:

1. **Android source with Google contacts.** Transfer a Google contact whose remote
   CONTACT identity is known. Confirm the imported raw row retains its ID, gains
   microG ownership and the verified People resource/etag, and a later download
   creates no second raw row. Include a contact edited before the first sync: its
   local values must survive; without an original baseline, divergent values must
   report a conflict and must not overwrite Google silently.
2. **iPhone into Google.** With one Google account, confirm Switch displays that
   account before Copy and imported raw contacts actually use it, with no source
   ID initially. Sync must assign real Google identities. Repeat with no Google
   account (truthful local destination), then multiple accounts (the disclosed
   destination must match actual raw ownership). Do not infer ownership from the
   “Copied from iOS” group's account.
3. **Both directions.** After a clean adoption, edit a name/phone, add a photo,
   favorite and label on the phone and sync; inspect actual Google values. Edit
   them remotely and sync back. Delete a disposable adopted contact locally and
   verify remote deletion; delete another remotely and verify local removal.
4. **Second transfer.** Record raw and aggregate counts after the first transfer
   and sync. Edit one adopted contact, remove a favorite/label, then repeat the
   same transfer. Both counts must stay stable for identical imports, and newer
   edits/favorite/label choices must remain. Follow with a full download/restart.
   Divergent copies with unaccounted data must be retained for review, never
   deleted to make counts pass.
5. **Owner policy.** Repeat while sync is Off and Download only. Neither may
   upload or change the saved mode. With developer migration hold enabled, verify
   adoption can retain dirty data but no mutation reaches Google until owner
   resume. Also verify disabled new-contact uploads and adoption floors.
6. **Foreign identity.** Insert a disposable Google-account fixture with a source
   ID absent from that account, plus another account/dataset/adapter row. None
   may gain ownership. Unknown IDs must not block unrelated downloads, deletions,
   photos or checkpoint progress. Turning sync off must retain unverified rows.

Ambiguous identities and differing pre-adoption values need an explicit owner
resolution workflow (CYC-810); this change retains them and reports review/conflict instead
of guessing which data wins. Native-provider behavior, real Google acceptance and
Switch's live multi-account UI remain device checks.
