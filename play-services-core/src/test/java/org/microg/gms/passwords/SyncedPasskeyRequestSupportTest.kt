/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.credentials.provider.SyncedPasskeyRequestSupport

/** The actual raw provider gate used by both advertising and redemption; real org.json, no Android runtime. */
class SyncedPasskeyRequestSupportTest {
    private fun create(algorithms: List<Int> = listOf(-7)) = JSONObject()
        .put("pubKeyCredParams", JSONArray().apply { algorithms.forEach { put(JSONObject().put("type", "public-key").put("alg", it)) } })
    @Test fun eitherAcceptedSpellingAllowsOnlySupportedCreateCredPropsAndReturnsTheRequestedOutput() {
        assertTrue(SyncedPasskeyRequestSupport.create(create().toString()))
        assertTrue(SyncedPasskeyRequestSupport.get("{}"))
        for (field in listOf("extensions", "authenticationExtensions")) {
            val value = create().put(field, JSONObject().put("credProps", true)).toString()
            assertTrue(SyncedPasskeyRequestSupport.create(value)); assertTrue(SyncedPasskeyRequestSupport.credProps(value))
            assertFalse(SyncedPasskeyRequestSupport.get(value))
            val disabled = create().put(field, JSONObject().put("credProps", false)).toString()
            assertTrue(SyncedPasskeyRequestSupport.create(disabled)); assertFalse(SyncedPasskeyRequestSupport.credProps(disabled))
            assertTrue(SyncedPasskeyRequestSupport.get(JSONObject().put(field, JSONObject()).toString()))
        }
    }
    @Test fun advancedOrUnknownOptionsCannotDisappearUnderEitherRawSpelling() {
        for (field in listOf("extensions", "authenticationExtensions")) {
            for (name in listOf("hmac-secret", "largeBlob", "credBlob", "payment", "appid", "fidoAppIdExtension", "uvm", "devicePubKey", "unknownFutureExtension")) {
                val extensions = JSONObject().put(name, JSONObject())
                assertFalse("$field/$name", SyncedPasskeyRequestSupport.create(create().put(field, extensions).toString()))
                assertFalse("$field/$name", SyncedPasskeyRequestSupport.get(JSONObject().put(field, extensions).toString()))
            }
        }
    }
    @Test fun simultaneousRepresentationsAreRefusedEvenIfOneIsEmptyOrBothMatch() {
        for (first in listOf(JSONObject(), JSONObject().put("credProps", true), JSONObject().put("prf", JSONObject()))) {
            for (second in listOf(JSONObject(), JSONObject().put("credProps", true), JSONObject().put("payment", true))) {
                val json = create().put("extensions", first).put("authenticationExtensions", second).toString()
                assertFalse(SyncedPasskeyRequestSupport.create(json)); assertFalse(SyncedPasskeyRequestSupport.get(json))
                assertFalse(SyncedPasskeyRequestSupport.credProps(json))
            }
        }
    }
    @Test fun malformedExtensionContainersAndCredPropsTypesCannotBeCoercedIntoSupport() {
        for (field in listOf("extensions", "authenticationExtensions")) {
            for (value in listOf(JSONObject.NULL, "", "{}", 1, true, JSONArray())) {
                val json = create().put(field, value).toString()
                assertFalse(SyncedPasskeyRequestSupport.create(json)); assertFalse(SyncedPasskeyRequestSupport.get(json))
            }
            for (value in listOf(JSONObject.NULL, "true", "false", 1, JSONObject(), JSONArray())) {
                val json = create().put(field, JSONObject().put("credProps", value)).toString()
                assertFalse(SyncedPasskeyRequestSupport.create(json)); assertFalse(SyncedPasskeyRequestSupport.get(json))
            }
        }
        assertFalse(SyncedPasskeyRequestSupport.create("{")); assertFalse(SyncedPasskeyRequestSupport.get("{"))
    }
    @Test fun algorithmAttestationAndAttachmentLimitsStillApplyAfterAliasExtraction() {
        val emptyAlias = JSONObject()
        assertTrue(SyncedPasskeyRequestSupport.create(create(listOf(-257, -7)).put("authenticationExtensions", emptyAlias).toString()))
        assertFalse(SyncedPasskeyRequestSupport.create(create(listOf(-257)).put("authenticationExtensions", emptyAlias).toString()))
        for (attestation in listOf("direct", "indirect", "enterprise"))
            assertFalse(SyncedPasskeyRequestSupport.create(create().put("attestation", attestation).toString()))
        assertFalse(SyncedPasskeyRequestSupport.create(create().put("authenticatorSelection", JSONObject().put("authenticatorAttachment", "cross-platform")).toString()))
        assertTrue(SyncedPasskeyRequestSupport.create(create().put("attestation", "none")
            .put("authenticatorSelection", JSONObject().put("authenticatorAttachment", "platform")).toString()))
    }
}
