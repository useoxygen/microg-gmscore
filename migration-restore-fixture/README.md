# Synthetic D2D app-data fixture

Opt-in `-PmigrationLab=true :migration-restore-fixture:assembleDebug` only; not an
image app. Install the same signed APK on the authorized source and destination.
Launch `.FixtureActivity --ez seed true` **only on the source**. Launch without
extras on destination before/after copying. A real restored result must contain
100003 bytes, matching source SHA256, and shared-preference counter712. Destination
seeding invalidates the test. The Activity requires DUMP; it is not a public data
injection endpoint. Cloud backups are excluded, device transfer is allowed.

Switch may filter an unlisted fixture app; report that explicitly and use the
C-owned install pipeline/framework gate. Never fabricate a Play install/restore
receipt or bypass source/target signer and app backup policies.
