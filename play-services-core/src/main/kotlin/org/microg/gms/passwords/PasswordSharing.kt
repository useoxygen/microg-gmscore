/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.accounts.AccountManager
import java.security.MessageDigest
import java.util.UUID

class PasswordSharingIncoming(val invitation: SharingInvitation, val passwords: List<PasswordData>) {
    override fun toString() = "PasswordSharingIncoming[redacted]"
}
class PasswordSharingScreen(val recipients: List<SharingRecipient>, val incoming: List<PasswordSharingIncoming>,
    val passwords: List<PasswordView>, val operations: List<SharingOperation>, val keysAvailable: Boolean) {
    override fun toString() = "PasswordSharingScreen[redacted]"
}

/** Sending remains owner-only; incoming invitations also have the source's automatic receiver. */
class PasswordSharing(context: Context, private val repository: PasswordRepository, private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val api = PasswordSharingApi(repository.sharingTransport())
    private val journal = SharingJournal(repository.store)
    private fun check() { authorize(); if (!repository.enabled) throw VaultLockedException() }
    private fun destination(parent: String): PasswordTransferEndpoint {
        val bound = PasswordRepository(context, repository.account, parent)
        // The new instance must not adopt a removed/re-added same-name account.
        repository.store.locked { it.load() }
        return bound
    }

    private fun <T> withKeys(metadata: ByteArray, action: (PasswordSharingKeys) -> T): T {
        val keys = repository.store.locked { session ->
            check()
            val state = session.load()
            val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
            if (state.pendingMetadata != null || state.metadata?.let { Proto.decode(it).string(1) } != parent) throw VaultResetException()
            val vaultKeys = repository.sharingKeys(metadata, state)
            try { PasswordSharingKeys.read(metadata, vaultKeys) } finally { vaultKeys.destroy() }
        }
        try { return action(keys) } finally { keys.destroy() }
    }

    /** Worker entry point. Only already opted-in accounts receive; no outgoing send is resumed. */
    fun processIncoming(): Int {
        check()
        val metadata = api.metadata()
        val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        fun verify() {
            check()
            repository.store.locked { session ->
                val state = session.load() // Also verifies the repository's captured stable Gaia ID.
                if (state.pendingMetadata != null || state.metadata?.let { Proto.decode(it).string(1) } != parent) throw VaultResetException()
            }
        }
        verify()
        val userId = AccountManager.get(context).getUserData(repository.account, "GoogleUserId") ?: throw VaultLockedException()
        verify()
        val notifications = PasswordSharingNotifications(context, repository.account, userId)
        val clientToken = token(parent)
        return try {
            SharingIncomingBackground(api, journal, destination(parent), ::verify, notifications::received).synchronize(parent, clientToken) { invitation ->
                withKeys(metadata) { keys -> invitation.passwordBatch(keys, System.currentTimeMillis()) }
            }
        } finally { clientToken.fill(0) }
    }
    fun load(): PasswordSharingScreen {
        check()
        val passwords = repository.list(true)
        val metadata = api.metadata()
        val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        val recipients = try { api.recipients() } catch (failure: PasswordRpcException) {
            // bmib maps jmqf.g / jmqc.f (NOT_FOUND=5) to NotFamilyMember.
            // Permission/authentication failures keep their failure state.
            if (failure.code == 5 && !failure.outcomeUnknown) emptyList() else throw failure
        }
        var available = true
        val incoming = try { withKeys(metadata) { keys ->
            api.incoming(parent).mapNotNull { invitation ->
                runCatching { PasswordSharingIncoming(invitation, invitation.passwords(keys, System.currentTimeMillis())) }.getOrNull()
            }
        } } catch (_: VaultLockedException) { available = false; emptyList() }
        check()
        repository.store.locked { it.load() }
        return PasswordSharingScreen(recipients, incoming, passwords, journal.operations(), available)
    }
    private fun token(parent: String): ByteArray = repository.store.locked { session ->
        check(); session.load().deviceRegistration?.takeIf { it.parent == parent }?.token?.copyOf() ?: throw VaultLockedException()
    }

    /** The selected recipient must still be listed with the same server key at the send boundary. */
    fun send(original: ByteArray, selected: SharingRecipient): String {
        check()
        repository.refresh()
        val data = PasswordData.decode(Proto.decode(original).one(1))
        if (repository.pending(data.tag())) throw PendingMutationException()
        val committed = repository.committed(data.tag()) ?: throw ConflictException()
        if (!PasswordTransferEngine.samePassword(committed, original)) throw ConflictException()
        val recipient = api.recipients().singleOrNull { it.sameKey(selected) } ?: throw ConflictException()
        val metadata = api.metadata()
        val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        val invitationId = UUID.randomUUID().toString()
        val plaintext = SharingPasswordGroup.encode(data)
        val invitation = try { withKeys(metadata) { keys ->
            val sender = keys.latest()
            Proto.field(1, invitationId) + Proto.field(2, recipient.userId) + Proto.field(3, sender.seal(plaintext, recipient)) +
                Proto.integer(6, recipient.version) + Proto.integer(7, sender.version)
        } } finally { plaintext.fill(0) }
        val request = api.outgoingRequest(parent, invitation, token(parent))
        val operation = SharingOperation(invitationId, SharingOperationKind.SEND, SharingOperationPhase.PREPARED, parent,
            data.tag() + ":" + recipient.userId, SharingOperation.digest(original), request)
        check(); journal.prepare(operation)
        SharingSender(api, journal).launch(invitationId, ::check)
        return invitationId
    }
    fun accept(selected: PasswordSharingIncoming): String {
        check(); repository.refresh()
        val metadata = api.metadata(); val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        val invitation = api.incoming(parent).singleOrNull { it.name == selected.invitation.name &&
            MessageDigest.isEqual(it.encode(), selected.invitation.encode()) } ?: throw ConflictException()
        val passwords = withKeys(metadata) { invitation.passwords(it, System.currentTimeMillis()) }
        val id = UUID.randomUUID().toString()
        val clientToken = token(parent)
        val operation = SharingOperation(id, SharingOperationKind.ACCEPT, SharingOperationPhase.PREPARED, parent,
            invitation.name, SharingOperation.digest(invitation.encode()), Proto.field(3, clientToken), passwords.map { Proto.field(1, it.encode()) })
        check(); journal.prepare(operation)
        SharingIncomingConsumer(api, journal, destination(parent), ::check).resume(id)
        return id
    }
    fun dismiss(selected: PasswordSharingIncoming): String {
        check(); repository.refresh()
        val parent = PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1))
        val current = api.incoming(parent).singleOrNull { it.name == selected.invitation.name &&
            MessageDigest.isEqual(it.encode(), selected.invitation.encode()) } ?: throw ConflictException()
        val id = UUID.randomUUID().toString()
        journal.prepare(SharingOperation(id, SharingOperationKind.DISMISS, SharingOperationPhase.PREPARED, parent,
            current.name, SharingOperation.digest(current.encode()), Proto.field(3, token(parent))))
        SharingIncomingConsumer(api, journal, destination(parent), ::check).resume(id)
        return id
    }
    fun resume(id: String) { check(); SharingIncomingConsumer(api, journal, destination(journal.get(id).parent), ::check).resume(id) }
    fun resolveUnknownSend(id: String, acknowledgesPossibleDelivery: Boolean) {
        check()
        journal.resolveUnknownSend(id, acknowledgesPossibleDelivery, System.currentTimeMillis(), ::check)
    }
}
