# Cyclon Services health acceptance

The health screen reads local state. It does not sign in, register the device,
enable a service, reconnect push, request a sync, or request a position. Recovery
buttons open the existing settings; those screens retain their consent flows.
The report preview is frozen, contains only enums and numbers, and is shared only
when the owner chooses the Android share action. No report is written to storage.

Host validation: `:play-services-core:testMapboxDefaultDebugUnitTest` covers Off,
Paused, Unknown, a disconnected enabled push service, multiple Contacts accounts,
permission failures, and report rejection of raw error text. CI also runs the
existing Contacts engine tests and artifact notice/version gates.

UI acceptance on an isolated emulator:

1. Open Service health from the main screen. Disabled services show Off; no
   Google account shows Off with the existing no-account explanation.
2. With networking disabled, enable push in the fixture's existing settings.
   Return to health: enabled without a live connection shows Needs attention,
   rather than Connected or Unknown. Return from settings refreshes observations.
3. Open each recovery destination and Self-Check, then return. Opening health
   must not change service settings or grant permissions.
4. Preview the report, return, and use the Android share chooser without selecting
   a recipient. Compare the shared text with the frozen preview.
5. Repeat navigation and report review in light/dark mode and at 200% font scale.
   The parent settings scroll container must reach every action without a crash.
6. Confirm an unprivileged external caller cannot access the push state provider.

Before product delivery, validate the exact dedicated-signed system APKs on a
scoped, owner-authorized device and retain version/hash receipts separately:

- A real app receives a notification; its registration and actual push connection
  agree with native settings before/after Wi-Fi/mobile changes and a reboot.
- A designated test account completes Contacts sync. Health shows its recorded
  success; missing permission, revoked authorization, paused Android sync, a
  second unsynced account, and opted-out sync remain distinguishable. Use only
  explicitly designated test data for any contact writes.
- Maps acquires an actual position with a consented provider. Ready on health
  remains a configuration observation, not proof of provider coverage.
- A no-wipe upgrade preserves accounts, provider selection, sync modes and app
  registrations. Off services stay Off.

These device checks are independent of a merged PR, a debug fixture, host tests,
or a recorded check-in. None establishes Google certification or an Integrity
verdict. The existing reliability drafts retain their own signed-device gates.
