# Passwords RPC contracts not established by the pinned client

Part of CYC-687. These five methods are present in the Passwords service inventory, but their request/response schemas and an active producer are not established by the pinned reference:

| RPC | Admission descriptor |
| --- | --- |
| `EnableTrustedVaultPassphrase` | `ialk` |
| `RotateTrustedVaultPassphraseKey` | `iall` |
| `ClearTrustedVaultPassphrase` | `ialm` |
| `ResetWebauthnCredentials` | `ialn` |
| `MarkForEnableTrustedVaultPassphraseGroup` | `ialp` |

These are distinct from Security Domain Service enrollment, rotation and reset, and from individual passkey CRUD. No implementation or host, provider or device acceptance is claimed for these five RPCs.

## Reference and bounded evidence

The reference is Google Play services **26.11.36 (260400-892401869)**, version `261136035`. The inspected APK SHA-256 was rechecked as:

`35f23040de6362a77103edb4b23825cfed7310ab6efe4d59310f817d79e6c1c2`

The `iamh` inventory lists the 29 methods of `google.internal.chrome.sync.passwords.v1.Passwords`, the production host `chromesyncpasswords-pa.googleapis.com`, and its OAuth scope admission. For the five methods above, `ialk`, `iall`, `ialm`, `ialn` and `ialp` provide method names and admission information; they do not bind request or response message types. The generated `iakv`/`iaku` stub shells do not provide these method implementations.

The investigation used the following independent checks:

- Search all extracted GMS Java sources, supplemental extracted producer files, pinned Chrome sources and available protobuf sources for the five exact names and references to `iamh.q` through `iamh.u`. The names appeared only in `iamh` and their admission descriptor classes.
- Inspect the matching base APK's DEX string tables and native libraries directly. Each full method name appeared as one bare inventory string in `classes13.dex`; none appeared as a fully qualified RPC call path. The extra substring occurrence of `EnableTrustedVaultPassphrase` belongs to `MarkForEnableTrustedVaultPassphraseGroup`.
- Search DEX/native contents of the ten available official bundled Chimera, Chrome and Trichrome APKs. None contained the five method names.
- Compare with proven active producers, such as the separately extracted `bfdu` and `bfdi` implementations. Those bind a fully qualified method path to concrete request and response message classes. An equivalent binding was not found for these five methods.

These checks are bounded to the pinned artifacts. They establish a missing verified contract in this reference, not that the methods never exist in another client, module or server configuration. No proprietary implementation or APK is checked into this repository. The investigation performed source reads and artifact inspection; it did not execute application tests, install software, contact a provider account, or send an RPC.

## Why generic Nigori support does not supply these contracts

The published Chromium 145 `nigori_specifics.proto`, `nigori_sync_bridge_impl.cc` and `pending_local_nigori_commit.cc` establish Nigori encryption, keybag handling and allowed passphrase-type transitions, including keystore/trusted-vault transitions. They do not define the five Passwords RPC request envelopes, key-version admission, client-token behavior, response outcomes or authoritative readback conditions.

In particular, the evidence does not establish:

- Enable/rotate: the exact keybag and version payload, source eligibility checks, required account proof and commit/readback contract.
- Clear: the exact destructive scope, required proof, resulting encryption metadata and preservation rules.
- WebAuthn reset: its relationship to password metadata, passkey generations and hardware-domain state, or how an uncertain reset is reconciled.
- Group marking: the assignment value and rollout semantics, or whether it is an owner-facing action at all.

Guessing these fields, copying a similarly named Security Domain RPC, or substituting a legacy Sync commit would produce a different, unverified operation. A transport enum or an HTTP-success stub would not close the implementation gap.

## Required evidence to implement

An independently verifiable request/response descriptor plus the actual producer/consumer chain is needed, from an official schema or another explicitly pinned reference. It must identify the bytes sent, prerequisites, success/refusal outcomes, key and metadata transitions, and reconciliation after a lost reply. Only then can a connected owner flow and durable mutation journal be implemented and tested. Provider admission and device acceptance are later, separate checks; they are not the reason these five implementations remain unavailable.
