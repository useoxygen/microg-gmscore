/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cryptauth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CryptAuthLogShapeTest {
    @Test
    fun verboseLogsShowFieldNamesNeverValues() {
        val body = JSONObject()
            .put("randomSessionId", "c2Vzc2lvbi1zZWNyZXQ")
            .put("clientMetadata", JSONObject().put("gcmRegistrationId", "registration-secret"))
            .put("enrollSingleKeyRequests", listOf("key-proof-secret"))
        val shape = jsonShape(body)
        assertTrue(shape, shape.startsWith("fields=[clientMetadata,enrollSingleKeyRequests,randomSessionId] chars="))
        for (secret in listOf("c2Vzc2lvbi1zZWNyZXQ", "registration-secret", "key-proof-secret", "gcmRegistrationId")) {
            assertFalse(shape.contains(secret))
        }
    }
}
