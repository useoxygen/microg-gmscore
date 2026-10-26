/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.math.BigInteger

/** Source-shaped real cryptographic envelopes with durable remote-boundary and owner/reset negative controls. */
class EnclavePinTest {
    private val owner = "synthetic-enclave-gaia"
    private val key = ByteArray(32) { (it + 1).toByte() }
    private fun pair(n: Int) = ByteArray(31) + byteArrayOf(n.toByte()) + SECNamedCurves.getByName("secp256r1").g.multiply(BigInteger.valueOf(n.toLong())).normalize().getEncoded(false)
    private val public = pair(3).copyOfRange(32, 97)
    private val spki get() = EnclaveNoise.publicKey(pair(2).copyOfRange(32, 97)).encoded
    private fun metadataMember(n: Int = 5) = Proto.field(1, EnrollmentWire.memberName(pair(n).copyOfRange(32, 97))) +
        Proto.field(2, pair(n).copyOfRange(32, 97)) + Proto.integer(4, 5) + Proto.field(6, Proto.field(5, Proto.field(2, byteArrayOf(9))))
    private inner class Fixture {
        var disk = byteArrayOf(); var actualOwner = owner; var allowed = true; var epoch = 7
        var members = emptyList<ByteArray>(); var vaults = emptyList<ByteArray>()
        var mode: String? = null; var proofInvalid = false; var afterVaultList: () -> Unit = {}; var beforeJoin: () -> Unit = {}
        val requests = ArrayList<String>(); val uploads = ArrayList<ByteArray>(); val joins = ArrayList<ByteArray>()
        fun state() = EnclavePinState.decode(disk)
        fun verify() { if (!allowed || actualOwner != owner) throw VaultLockedException() }
        fun manager(bound: String = owner) = EnclavePinManager(bound, 42, object : EnclavePinRemote {
            override fun domain(id: String) = Proto.field(1, DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN)) + Proto.integer(2, epoch.toLong())
            override fun members(id: String) = members.map { it.copyOf() }
            override fun vaults(id: String): List<ByteArray> { val result = vaults.map { it.copyOf() }; afterVaultList(); return result }
            override fun transact(commands: ByteArray, signed: Boolean, rapt: String?): ByteArray {
                val body = EnclaveCbor.decode(commands) as List<*>; val first = EnclaveCbor.map(body.first()); val command = first["cmd"] as String
                requests.add(command)
                when (command) {
                    "device/register" -> { assertFalse(signed); assertNull(rapt); assertEquals(EnclavePinPhase.REGISTER_LAUNCHED, state().attempt!!.phase) }
                    "keys/wrap" -> { assertTrue(signed); assertArrayEquals(key, first["key"] as ByteArray); assertEquals(EnclavePinPhase.WRAP_LAUNCHED, state().attempt!!.phase) }
                    else -> { assertTrue(signed); assertEquals("synthetic-fresh-rapt", rapt); assertEquals(EnclavePinPhase.PIN_LAUNCHED, state().attempt!!.phase) }
                }
                assertArrayEquals(commands, state().attempt!!.commands)
                if (mode == command) throw IOException("synthetic lost response")
                val result = when (command) {
                    "device/register" -> listOf(mapOf("ok" to true), mapOf("ok" to mapOf("pub_key" to pair(4).copyOfRange(32, 97), "priv_key" to byteArrayOf(1, 2, 3))))
                    "keys/wrap" -> listOf(mapOf("ok" to byteArrayOf(4, 5, 6)))
                    else -> {
                        val counter = ByteArray(8) { 6 }; val handle = byteArrayOf(3) + ByteArray(16) { 7 }
                        val plain = EnclaveCbor.encode(mapOf(1 to first["pin_hash"]!!, 3 to first["pin_claim_key"]!!, 4 to counter,
                            5 to handle.copyOfRange(1, 17), 6 to 10, 7 to spki))
                        val wrapped = mapOf("app_public_key" to public, "cohort_public_key" to spki, "counter_id" to counter, "vault_handle" to handle,
                            "max_attempts" to 10, "serial" to 10, "certs_in_path" to listOf(byteArrayOf(0x30, 0)),
                            "encrypted_recovery_key" to ByteArray(64) { 4 }, "wrapped_app_private_key" to ByteArray(125) { 5 }, "wrapped_wrapping_key" to ByteArray(64) { 6 })
                        val proof = EnrollmentWire.proof(key, public).also { if (proofInvalid) it[0] = (it[0].toInt() xor 1).toByte() }
                        listOf(mapOf("ok" to mapOf("wrapped" to wrapped, "wrapped_pin" to GpmPinCryptography.sealMetadata(plain, key),
                            "wrapped_sds" to GpmPinCryptography.wrapMember(public, key), "member_proof" to proof)))
                    }
                }
                return EnclaveCbor.encode(result)
            }
            override fun update(request: ByteArray): ByteArray {
                assertEquals(EnclavePinPhase.VAULT_LAUNCHED, state().attempt!!.phase); assertArrayEquals(request, state().attempt!!.update)
                uploads.add(request.copyOf()); vaults = listOf(Proto.field(1, "vaults/synthetic") + Proto.decode(request).one(3))
                if (mode == "update") throw IOException("lost update response")
                if (mode == "account-after-update") actualOwner = "replacement-gaia"
                return byteArrayOf()
            }
            override fun join(request: ByteArray): ByteArray {
                beforeJoin(); assertEquals(EnclavePinPhase.JOIN_LAUNCHED, state().attempt!!.phase); assertArrayEquals(request, state().attempt!!.join)
                joins.add(request.copyOf()); val p = Proto.decode(request)
                val membership = Proto.field(1, DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN)) + p.bytes(3).fold(byteArrayOf()) { out, raw -> out + Proto.field(3, raw) }
                members = listOf(Proto.decode(p.one(2)).replaceRepeated(3, listOf(membership)).encode())
                if (mode == "join") throw IOException("lost join response")
                return byteArrayOf()
            }
        }, { verify(); listOf(DomainLifecycleKey(7, key)) }, ::state, { value -> verify(); disk = value.encode() }, ::verify)
        fun register() { manager().prepareAlias("synthetic-durable-alias"); manager().register(spki, "sw") }
        fun ready() { register(); manager().wrapCurrentKey() }
        fun set(change: Boolean = false) = manager().setPin("123456".toByteArray(), change, "synthetic-fresh-rapt", byteArrayOf(1) to byteArrayOf(2))
    }
    @Test fun realRegistrationAndPinWireCommitOnlyAfterVaultAndCurrentMemberReadback() {
        val f = Fixture(); f.ready(); f.set()
        assertEquals(EnclavePinPhase.CONFIRMED, f.state().attempt!!.phase)
        assertEquals(listOf("device/register", "keys/wrap", "recovery_key_store/wrap_pin_and_secret"), f.requests)
        assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size)
        val attempt = f.state().attempt!!; assertTrue(attempt.commands.isEmpty()); assertTrue(attempt.pinMetadata.isEmpty())
        val pin = Proto.decode(GpmPinRenewalWire.metadata(Proto.decode(attempt.join).one(2)))
        assertEquals(1L, pin.integer(4)); assertEquals(1L, pin.integer(5)); assertEquals(16384L, pin.integer(6)); assertEquals(16, pin.one(7).size)
        assertArrayEquals(key, GpmPinCryptography.unwrapMember(pair(3), Proto.decode(Proto.decode(attempt.join).one(3)).one(2)))
        assertArrayEquals(EnrollmentWire.proof(key, public), Proto.decode(Proto.decode(attempt.join).one(3)).one(3))
        assertFalse(String(f.disk, Charsets.ISO_8859_1).contains("synthetic-fresh-rapt"))
    }
    @Test fun registrationAndPinUnreturnedCommandsStayLaunchedAcrossReopenAndCannotReplay() {
        for (lost in listOf("device/register", "keys/wrap", "recovery_key_store/wrap_pin_and_secret")) {
            val f = Fixture(); f.mode = lost
            assertThrows(Exception::class.java) { f.ready(); f.set() }
            val before = f.requests.toList(); val receipt = f.state().attempt!!
            f.manager().reconcile(); assertEquals(before, f.requests)
            assertThrows(Exception::class.java) { f.set() }; assertEquals(before, f.requests)
            f.manager().close(receipt.id); assertNull(f.state().attempt); assertTrue(f.state().history.isNotEmpty())
        }
    }
    @Test fun committedUploadLostResponseReconcilesWithoutAnotherUploadAndOnlyExplicitlyContinuesJoin() {
        val f = Fixture(); f.ready(); f.mode = "update"
        assertThrows(Exception::class.java) { f.set() }; val id = f.state().attempt!!.id
        assertEquals(EnclavePinPhase.VAULT_LAUNCHED, f.state().attempt!!.phase)
        f.manager().reconcile(); assertEquals(EnclavePinPhase.VAULT_CONFIRMED, f.state().attempt!!.phase); assertTrue(f.joins.isEmpty())
        f.mode = null; f.manager().continueOnce(id)
        assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size); assertEquals(EnclavePinPhase.CONFIRMED, f.state().attempt!!.phase)
    }
    @Test fun committedJoinLostResponseResolvesFromReadbackWithoutReplay() {
        val f = Fixture(); f.ready(); f.mode = "join"
        assertThrows(Exception::class.java) { f.set() }; assertEquals(EnclavePinPhase.JOIN_LAUNCHED, f.state().attempt!!.phase)
        f.manager().reconcile(); f.manager().continueOnce(f.state().attempt!!.id)
        assertEquals(EnclavePinPhase.CONFIRMED, f.state().attempt!!.phase); assertEquals(1, f.joins.size)
    }
    @Test fun missingVaultOrMemberRevokesPriorConfirmation() {
        for (missing in listOf("vault", "member")) {
            val f = Fixture(); f.ready(); f.set()
            if (missing == "vault") f.vaults = emptyList() else f.members = emptyList()
            f.manager().reconcile(); assertEquals(EnclavePinPhase.REVOKED, f.state().attempt!!.phase)
        }
    }
    @Test fun createOverExistingChangeWithoutExistingAndUnprovedMemberProofCannotUpload() {
        val existing = Fixture(); existing.ready(); existing.members = listOf(metadataMember())
        assertThrows(Exception::class.java) { existing.set(false) }; assertTrue(existing.uploads.isEmpty())
        val absent = Fixture(); absent.ready(); assertThrows(Exception::class.java) { absent.set(true) }; assertTrue(absent.uploads.isEmpty())
        val invalid = Fixture(); invalid.ready(); invalid.proofInvalid = true
        assertThrows(Exception::class.java) { invalid.set() }; assertEquals(EnclavePinPhase.PIN_LAUNCHED, invalid.state().attempt!!.phase); assertTrue(invalid.uploads.isEmpty())
    }
    @Test fun changePinsExactPreviousPublicAndStopsIfAnotherDeviceChangesMemberBeforeJoin() {
        val f = Fixture(); f.ready(); val prior = metadataMember(); f.members = listOf(prior); f.mode = "update"
        assertThrows(Exception::class.java) { f.set(true) }
        val attempt = f.state().attempt!!; assertArrayEquals(Proto.decode(prior).one(2), Proto.decode(attempt.join).one(5))
        f.members = listOf(metadataMember(6)); f.mode = null
        assertThrows(Exception::class.java) { f.manager().continueOnce(attempt.id) }; assertTrue(f.joins.isEmpty())
    }
    @Test fun accountReplacementAndEpochChangeDuringIoNeverReleaseConfirmation() {
        val f = Fixture(); f.ready(); f.mode = "account-after-update"
        assertThrows(Exception::class.java) { f.set() }; assertEquals(EnclavePinPhase.VAULT_LAUNCHED, f.state().attempt!!.phase); assertTrue(f.joins.isEmpty())
        val other = Fixture(); other.ready(); other.set(); other.afterVaultList = { other.epoch = 8 }
        assertThrows(Exception::class.java) { other.manager().reconcile() }; assertEquals(EnclavePinPhase.REVOKED, other.state().attempt!!.phase)
    }
    @Test fun ownerCloseDropsHashCommandsRetainsReceiptAndResetClearsAllRegistrationAuthorityBoundedly() {
        val f = Fixture(); f.ready(); f.mode = "recovery_key_store/wrap_pin_and_secret"
        assertThrows(Exception::class.java) { f.set() }; val attempted = f.state().attempt!!
        f.manager().close(attempted.id); assertNull(f.state().attempt); assertTrue(f.state().registered)
        val state = f.state(); val full = EnclavePinState(state.alias, state.spki, state.level, true, state.memberPublic, state.memberPrivate,
            state.epoch, state.wrappedSecret, state.keysDigest, attempted, List(100) { byteArrayOf(it.toByte()) })
        val reset = EnclavePinState.decode(EnclavePinState.clearDomain(full.encode(), "hw_protected"))
        assertFalse(reset.registered); assertTrue(reset.alias.isEmpty()); assertTrue(reset.wrappedSecret.isEmpty()); assertNull(reset.attempt); assertEquals(100, reset.history.size)
        assertArrayEquals(full.encode(), EnclavePinState.clearDomain(full.encode(), "chromesync"))
    }
    @Test fun arbitraryUtf8UsesSourceFormAndScryptAlgorithmAndInvalidUtf8IsRefused() {
        val (command, metadata) = EnclavePinWire.preparePin("eight-ä".toByteArray(), byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val p = Proto.decode(metadata); assertEquals(2L, p.integer(4)); assertEquals(1L, p.integer(5)); assertEquals(16384L, p.integer(6))
        val input = EnclaveCbor.map((EnclaveCbor.decode(command) as List<*>).single()); val hash = input["pin_hash"] as ByteArray
        assertArrayEquals(org.bouncycastle.crypto.generators.SCrypt.generate("eight-ä".toByteArray(), p.one(7), 16384, 8, 1, 32), hash)
        assertThrows(Exception::class.java) { EnclavePinWire.preparePin(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)) }
    }
}
