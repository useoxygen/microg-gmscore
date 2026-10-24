# Cyclon Android Switch cable support (CYC-714)

This is a draft integration on top of CYC-700's `dd935664`. Source implementation
and host tests do not establish physical-device acceptance or Pixel parity.
Do not publish/pin it until the unmanaged-iPhone plan in the Cyclon image repo
passes. No Google APK or decompiled source is part of this change.

## Play setup and app installation

On the Cyclon image, **FakeStore** (`vending-app`, `com.android.vending`) answers
`com.android.vending.setup.IPlaySetupServiceV2.BIND`, with Binder descriptor
`com.android.vending.setup.IPlaySetupServiceV2`. Only Android Switch with its
recognized signing certificate and ACCOUNT package permission may use it.

Switch's `INSTALL_SELECTED_APPS` broadcast is delivered to Switch's own receiver;
it is not an installation action for Play/Aurora. The literal `PLAY_SETUP_SERVICE`
was not found in the pinned Switch source; it must not be invented as an action.

The cable path uses:

- tx7: account name + package-name array → compatible `document_groups`. Each
  group contains `title` and a `documents` Bundle array. A document supplies
  `package_name`, `title`, `install_size`, `version_code`, `availability` and
  `is_paid`. `availability=1` means actually offered; an unavailable document
  includes `availability=2` and a Cyclon `error` reason. No unavailable app is
  turned into an available app to defeat the filter.
- tx8: Bundle[] legacy selected documents; tx12: options Bundle containing
  `documents`. Persist selection/expected version before dispatch. A successful
  synchronous response means accepted for scheduling, not installed.
- tx9: final-hold ResultReceiver. Result1 releases setup to Aurora's background
  work, with `cyclon_app_results`, `all_installed` and explicit
  `cyclon_hold_state=released_to_aurora`. It does not assert all apps installed.
- tx22 shares availability lookup, tx3 reports no early update. Optional Google
  cloud/source-device/preinstall collections are empty. P2P transfer-order tx20
  and Google restore/install machinery are not implemented. This is not a Play
  Store emulator or a replacement for all20 setup operations.

`FILTERED_BY_PLAY` is created when an iPhone app has a free Android match but
that package is omitted from Play's compatible-document response. On the tested
image there was no setup service at all. This is not evidence that all323 apps
were incompatible with the phone.

The Aurora companion PR adds a narrow service `com.aurora.store.migration.BIND`,
descriptor `com.aurora.store.migration.IMigrationStore`: tx1 name→details Bundle,
tx2 name→enqueue/status Bundle, tx3 name→durable download/PackageManager status.
Only the installed system `com.android.vending` UID may call it. FakeStore also
requires Aurora to be a system app. Keep the two changes together; absent Aurora
bridge returns a real `store_unavailable` failure.

Aurora uses its existing app mirror first and a specifically selected anonymous
account for Play fallback. The owner's Google account name from Switch is
ignored. A persistent per-package anonymous policy also applies inside the
DownloadWorker; removing/changing accounts cannot make an interrupted migration
purchase as the owner. Missing anonymous credentials, paid/unavailable apps,
network/install failures and unsupported packages remain per-app failures.
Normal Aurora download validation, SHA-256 checks, split installation and error
receipts remain authoritative; the bridge does not download or install APKs.

FakeStore's persisted JobScheduler selection survives process death/reboot.
After an uncertain enqueue reply it checks Aurora's durable state before trying
again. Downloading is not installed, and an installed version below the selected
version cannot pass. The owner can inspect **App install results** from the
Cyclon companion and retry failed apps. Aurora retains its normal cancel controls.
Host tests exercise unknown enqueue outcomes, restart, old-version false success
and persisted unavailable reasons. Full323-app timing/cancellation/restore and
Google setup progress callbacks still require physical validation.

## Contacts: Google default and developer controls

Thomas's round2 decision supersedes the earlier default-off upload/hold proposal.
Imported iOS contacts belong to the Google account chosen in Switch and upload
normally, matching its disclosure. New-contact uploads also follow Google defaults.
An unconfigured account now defaults to TWO_WAY; account add/boot/package replacement
and the setup hint initialize sync. An existing explicit OFF/download-only owner
choice is preserved. Device-only contacts remain outside Google-account scope.

Old experimental hold/new-upload/adoption-floor preferences do not suppress normal
customer behavior. A DEBUG-only **developer contact controls** opt-in (default off)
can enable per-account fixture restrictions and a persistent all-mutation hold.
The protected beginMigration call only starts that hold when the developer has
opted in; the companion can launch without a hold. Developer hold survives
success/cancel/restart until explicit resume; disabling developer controls restores
normal Google behavior. The HTTP-boundary fence remains for opted-in tests.
The provider explicitly enforces NETWORK_SETUP_WIZARD on call(), not just in XML.

Tests cover normal Google defaults, old-preference migration, saved OFF, explicit
developer hold/restart and permission rejection. ContactsProvider instrumentation
is compiled, not executed. Real account sync/visibility remains an acceptance gate.

## Migrate helper and media

The existing signature-bound helper broker retains protocol v1. tx3 registers a bounded pipe and acknowledges immediately, then copies a single-name artifact
asynchronously into private `.pending` staging under GMS's private no-backup
`migration-staging` directory. Traversal fails; each stream is bounded by available device storage with 256 MiB reserved for OS/metadata. Large MMS archives do not hit an arbitrary 512 MiB ceiling. Concurrent disk exhaustion remains an honest IO failure. It closes
received file descriptors. tx4 requires both EOF and remote success before atomically
publishing the final name. tx5 aborts, closes and discards the private write and
reports failure. Unique temporary identities keep a late failed writer away from
a retry's files. A fresh helper starts a fresh staging batch before its
first write so old files cannot masquerade as this transfer's artifacts. Existing
staging is moved to private `migration-history/<random-id>` without deleting it;
verification/owner-directed cleanup is required before removing prior results. This
batch policy must be revisited together with a resumable BackupManager transport.

Tx7 writes actual `restore_token_file` and `@pm@` metadata through Android's
BackupDataOutput, then asks the **installed** MediaProvider to scan
`external_primary`. Hidden-constructor access must be authorized by the image;
reflection/IO/provider errors return failure, never invented success. This creates the package-manager envelope and, only if a real converted
telephony archive was published, its actual installed telephony version and
SHA-256 signer metadata. It does not fabricate telephony payloads or generate
destination-signature metadata for arbitrary Android app archives. Tx6 now opens an explicit local-cache
BackupManager restore session, obtains the actual restore set and calls restoreAll;
only the real RestoreObserver completion can report success. A 180-second timeout,
missing set/session, denied hidden API or transport failure reports failure. It
never selects this transport for normal backup or changes backup enablement.
Helper11 and credential-recovery9/10 remain honest failure (CYC-715). Agent A supplies the
target restore-only `.backup.BackupTransportService`; the image experiment
allowlists that component while preserving Seedvault/AOSP local transports and
does not select it for normal backup.

The retained cable log has no post-copy scan request and explicitly logs that
system restore/final hold during transfer is disabled. Switch has its own scan
code, but its general scan lives in later finalization. This supports a skipped
finalization path, not a demonstrated GMS broadcast or MediaProvider bug. The
image companion also scans when the full SINGLE_RUN_D2D wizard returns, including
partial cancellation. No filesystem watcher, periodic library scan or fake
`PACKAGE_ADDED` broadcast is added. Gallery visibility is still an acceptance gate.

## Remaining matrix boundaries

Reconcile with `~/research/cyc700-switch/parity/D-GAPS.{md,json}` by stable IDs.
Framework contacts/calendar/SMS/MMS/call-log writes belong to Switch/providers;
those are not made successful merely by exposing GMS services. Notes are explicitly not copied until Cyclon has a notes app (CYC-715).
Wi-Fi export is an Android source-side API, distinct from iPhone cable settings
import; current empty export is not proof of Wi-Fi parity. Contacts third-party
restore, its dynamic consent activity, credential import and optional Google
cloud APIs remain separate gaps. The stacked image change supplies the narrow
local SUW providers/router/keepalive/portal and a durable onboarding-task client;
actual task-graph inputs and live Switch acceptance remain separate qualification. Logging-only services may be no-ops, but no consent API
may default to permission to upload. Thomas selected the CYC-654 microG credential provider for passwords/passkeys;
CYC-716 records Switch's IdentityCredentials352 URI/Bundle producer and the missing
provider-events handoff. No imported credential success is claimed. Cyclon retains
its layout, with optional mapped-favorites suggestions tracked CYC-717. Switch is
bundled as a pinned priv-app (CYC-719), legal review pending before wider launch.
Target eSIM contracts/implementation and explicit gaps/test safety are documented
in [esim-target.md](../cyclon/esim-target.md), CYC-718. No real-line test is authorized.


## Validation scope

Round2 full B+C host regression:76 core tests (migration/contact/eSIM) plus4
FakeStore tests pass, zero failures/errors/skips. Includes11 new synthetic eSIM
protocol/permission/callback/cancel/unknown-reply tests. GmsCore/FakeStore debug
APKs build and Android instrumentation Kotlin compiles. Setup host10, image/media14
and permissions20+11 external-vendor skips are separately verified in image#1227;
Aurora's unchanged head retains12 passing focused tests from round1.

Real-pipe acknowledgement-before-EOF and ContactsProvider instrumentation have
not been executed here. Host shadows do not prove kernel streaming, carrier or
provider behavior. No physical phone, carrier endpoint or eUICC has been used.
The combined A/B/C image and unmanaged-iPhone acceptance remain open.
