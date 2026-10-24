/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.phenotype.FolsomPhenotypeProtocol as Wire
import org.microg.gms.phenotype.FolsomPromptFlagPolicy as Flags

class FolsomPhenotypeProtocolTest {
    private val registration = Wire.Registration(7, ByteArray(32) { 1 }, hex("082a1825"))
    private fun launched() = Wire.begin(Wire.State(registration.identity))
    private fun key(account: Long = -1, version: Long = 7, namespace: String = Flags.NAMESPACE, pkg: String = Wire.PACKAGE) =
        Proto.field(1, namespace) + Proto.integer(2, version) + Proto.field(3, Proto.integer(1, account)) + Proto.field(5, pkg)
    private fun flag(name: String, type: Long, value: Long) = Proto.field(1, name) + Proto.integer(if (type == 2L) 3 else 2, value) + Proto.integer(9, type)
    private fun partition(id: Long, tag: String, flags: List<ByteArray>, action: Long = 0) =
        Proto.field(1, Proto.integer(1, id) + Proto.field(2, tag)) + flags.fold(byteArrayOf()) { out, f -> out + Proto.field(2, f) } +
            if (action == 0L) byteArrayOf() else Proto.integer(3, action)
    private fun app(parts: List<ByteArray>, account: Long = -1, id: ByteArray = key(account)) = Proto.field(1, id) +
        parts.fold(byteArrayOf()) { out, part -> out + Proto.field(2, part) } + Proto.field(3, "literal-experiment") +
        Proto.field(4, "literal-server-token") + Proto.field(7, "literal-application-tag") + Proto.integer(11, 100)
    private fun response(apps: List<ByteArray>, time: Long = 100) = apps.fold(byteArrayOf()) { out, a -> out + Proto.field(1, a) } +
        Proto.field(2, "literal-request-tag") + Proto.field(3, "literal-pseudonym") + Proto.integer(4, time)
    private fun payload(vararg parts: ByteArray) = response(listOf(app(parts.toList())))
    private fun accept(before: Wire.State, raw: ByteArray) = Wire.response(registration, before, before, raw)
    private fun enabledPart(tag: String = "tag-v1") = partition(1, tag, listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 1)))
    private fun hex(raw: String) = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun sourceRequestHasLiteralCommonClientAndDistinctRealAccountAndDeviceScopes() {
        val request = Proto.decode(Wire.request(registration, launched()))
        // Independent literal oracle: hijr4 -> iwkw(client=ANDROID4, device=androidId42/sdk37), hijr7 true.
        assertArrayEquals(hex("220808041204082a18253801"), request.one(1))
        assertEquals(19L, request.integer(4)); assertEquals(1L, request.integer(5))
        val applications = request.bytes(2).map { Proto.decode(Proto.decode(it).one(1)) }
        assertEquals(2, applications.size)
        assertArrayEquals(hex("08ffffffffffffffffff01"), applications[0].one(3))
        assertArrayEquals(hex("0800"), applications[1].one(3))
        applications.forEach {
            assertEquals(Flags.NAMESPACE, it.string(1)); assertEquals(7L, it.integer(2))
            // hikk4 is SFIXED64: literal tag0x21 + eight little-endian FF bytes, not varint tag0x20.
            val beforeBaseline = it.replace(4, null).replace(5, null).encode()
            val withoutPackage = it.replace(5, null).encode()
            assertArrayEquals(beforeBaseline + hex("21ffffffffffffffff"), withoutPackage)
            assertNull(it.integer(4)); assertEquals(Wire.PACKAGE, it.string(5))
        }
        assertNull(request.optional(3)) // No invented initial request/config/dogfood token.
        assertNull(Proto.decode(request.one(1)).optional(5))
    }
    @Test fun genuineDeviceFlagsAndOpaqueTagsCommitTogetherAndSurviveReopenIntoNextActualRequest() {
        val before = launched()
        val next = accept(before, payload(enabledPart(), partition(2, "skip", listOf(flag(Flags.SKIP_CONSENT, 2, 0),
            flag(Flags.NOTIFICATION_DELAY, 1, -1)))))
        assertTrue(next.assigned); assertNull(next.pending)
        assertEquals(mapOf(Flags.ENABLE_NOTIFICATIONS to true, Flags.SKIP_CONSENT to false, Flags.NOTIFICATION_DELAY to -1L), Wire.values(next))
        val reopened = Wire.decode(next.encode()); val request = Proto.decode(Wire.request(registration, Wire.begin(reopened)))
        assertEquals("literal-request-tag", request.string(3))
        assertEquals("literal-pseudonym", Proto.decode(request.one(1)).string(5))
        val device = Proto.decode(request.bytes(2)[0]); val account = Proto.decode(request.bytes(2)[1])
        assertEquals("literal-application-tag", device.string(4)); assertEquals(2, device.bytes(3).size)
        assertTrue(account.bytes(3).isEmpty()); assertNull(account.optional(4))
        assertEquals("literal-experiment", Proto.utf8(reopened.experimentToken!!))
        assertEquals("literal-server-token", reopened.serverToken)
        assertFalse(next.toString().contains("literal")); assertFalse(next.partitions.first().toString().contains("tag"))
    }
    @Test fun independentLiteralServerResponseUsesActualBooleanEnumAndPartitionWire() {
        // Literal hika.response1 -> hijq1 package/version/account-1, hijq2 -> hiki tag and hikf(type2,booltrue).
        // Lengths and field tags are fixed independently of Wire.request/response and the fixture builders above.
        val literal = hex("0a680a330a22") + "com.google.android.gms.auth_folsom".toByteArray() +
            hex("10071a0b08ffffffffffffffffff0112310a0a080112067461672d763112230a1d") +
            "ZuulMvp__enable_notifications".toByteArray() + hex("180148022064")
        val next = accept(launched(), literal)
        assertTrue(next.assigned); assertEquals(100L, next.timestamp)
        assertEquals(mapOf(Flags.ENABLE_NOTIFICATIONS to true), Wire.values(next))
        assertArrayEquals("tag-v1".toByteArray(), next.partitions.single().tag)
    }
    @Test fun accountConfigurationCannotEnableDeviceFlagsOrSubstituteAnyOtherAuthority() {
        val before = launched()
        val onlyAccount = accept(before, response(listOf(app(listOf(enabledPart()), account = 0))))
        assertFalse(onlyAccount.assigned); assertTrue(Wire.values(onlyAccount).isEmpty())
        val device = app(listOf(partition(1, "device", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 0)))))
        val both = accept(before, response(listOf(device, app(listOf(enabledPart()), account = 0))))
        assertEquals(false, Wire.values(both)[Flags.ENABLE_NOTIFICATIONS])
        for (wrong in listOf(key(account = 1), key(version = 8), key(namespace = Flags.NAMESPACE + "__auto"), key(pkg = "foreign.package"))) {
            assertThrows(ProtocolException::class.java) { accept(before, response(listOf(app(listOf(enabledPart()), id = wrong)))) }
        }
        assertThrows(ProtocolException::class.java) { accept(before, response(listOf(device, device))) }
    }
    @Test fun taggedPartitionReplacementAndExplicitDeletionDoNotInventMissingFlagAssignments() {
        val initial = accept(launched(), payload(enabledPart()))
        val second = Wire.begin(initial)
        val unchanged = accept(second, payload(partition(1, "tag-v1", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 0)))))
        assertEquals(true, Wire.values(unchanged)[Flags.ENABLE_NOTIFICATIONS]) // Same immutable partition tag means no change.
        val changed = accept(Wire.begin(unchanged), payload(partition(1, "tag-v2", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 0)))))
        assertEquals(false, Wire.values(changed)[Flags.ENABLE_NOTIFICATIONS])
        val deleted = accept(Wire.begin(changed), payload(partition(1, "ignored", emptyList(), action = 2)))
        assertTrue(Wire.values(deleted).isEmpty())
        assertFalse(Flags.resolve(Flags.NAMESPACE, Wire.values(deleted)).promptEnabled)
    }
    @Test fun malformedTypedFlagsAndPartialAuthenticationCannotReplaceAnEarlierValidSnapshot() {
        val before = launched(); val saved = before.encode()
        val malformed = listOf(
            partition(1, "bad-type", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 1, 1))),
            partition(1, "bad-bool", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 2))),
            partition(1, "duplicates", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 0), flag(Flags.ENABLE_NOTIFICATIONS, 2, 1))),
            partition(1, "mixed-wire", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 1) + Proto.field(3, "hidden"))))
        malformed.forEach { part -> assertThrows(ProtocolException::class.java) { accept(before, response(listOf(app(listOf(part))))) } }
        assertThrows(ProtocolException::class.java) { accept(before, response(listOf(app(listOf(enabledPart())))) + Proto.integer(6, 0)) }
        assertThrows(ProtocolException::class.java) { accept(before, response(listOf(app(listOf(enabledPart())))) + Proto.field(4, "wrong-wire")) }
        assertArrayEquals(saved, before.encode())
    }
    @Test fun actualGenerationCasRejectsDelayedResponseAcrossConcurrentFetchResetSignerOrVersionChange() {
        val first = launched(); val newer = Wire.begin(first); val raw = response(listOf(app(listOf(enabledPart()))))
        assertThrows(ProtocolException::class.java) { Wire.response(registration, first, newer, raw) }
        val differentIdentity = Wire.State(ByteArray(32) { 2 }, first.generation, first.pending)
        assertThrows(ProtocolException::class.java) { Wire.response(registration, first, differentIdentity, raw) }
        val committed = accept(first, raw)
        assertThrows(ProtocolException::class.java) { Wire.response(registration, first, committed, raw) }
        val replay = Wire.begin(committed)
        assertThrows(ProtocolException::class.java) { accept(replay, response(listOf(app(listOf(enabledPart()))), 99)) }
        assertNotEquals(first.pending, newer.pending); assertEquals(first.generation + 1, newer.generation)
    }
    @Test fun sourceSkipPrecedenceRemainsAboveActualServerNotificationAndLocalOverridesAreDistinct() {
        val state = accept(launched(), payload(enabledPart(), partition(2, "skip", listOf(flag(Flags.SKIP_CONSENT, 2, 1)))))
        val server = Wire.values(state)
        assertFalse(Flags.resolve(Flags.NAMESPACE, server).promptEnabled)
        assertTrue(Flags.resolve(Flags.NAMESPACE, server + mapOf(Flags.SKIP_CONSENT to false)).promptEnabled)
        assertEquals(true, Wire.values(Wire.decode(state.encode()))[Flags.SKIP_CONSENT])
    }
    @Test fun malformedTokensDuplicatePartitionsAndBoundedResponsesFailBeforePublication() {
        val before = launched()
        assertThrows(ProtocolException::class.java) { accept(before, ByteArray(Wire.MAX_BYTES + 1)) }
        assertThrows(ProtocolException::class.java) { accept(before, response(listOf(app(listOf(enabledPart(), enabledPart()))))) }
        val invalid = response(listOf(app(listOf(enabledPart())))).let { Proto.decode(it).replace(3, "bad;cookie".toByteArray()).encode() }
        assertThrows(ProtocolException::class.java) { accept(before, invalid) }
        val oversized = response(listOf(app(listOf(enabledPart())))).let { Proto.decode(it).replace(2, ByteArray(Wire.MAX_TOKEN + 1)).encode() }
        assertThrows(ProtocolException::class.java) { accept(before, oversized) }
    }
    @Test fun omittedSourceTimestampAndEmptyPartitionTagNeverInventAReplacement() {
        val before = launched()
        val missingTime = Proto.decode(payload(enabledPart())).replace(4, null).encode()
        val initial = accept(before, missingTime)
        assertEquals(0L, initial.timestamp); assertEquals(true, Wire.values(initial)[Flags.ENABLE_NOTIFICATIONS])
        val emptyTag = partition(1, "", listOf(flag(Flags.ENABLE_NOTIFICATIONS, 2, 0)))
        val retained = accept(Wire.begin(initial), payload(emptyTag))
        assertEquals(true, Wire.values(retained)[Flags.ENABLE_NOTIFICATIONS])
    }
}
