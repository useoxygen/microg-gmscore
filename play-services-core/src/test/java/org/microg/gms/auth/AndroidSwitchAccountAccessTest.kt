/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.microg.gms.common.GooglePackagePermission.ACCOUNT
import org.microg.gms.common.PackageAndCertHash
import org.microg.gms.common.getGooglePackagePermissions

class AndroidSwitchAccountAccessTest {
    private val packageName = "com.google.android.apps.restore"
    private val certs = listOf(
        "e4c43ce273303895c4ebd281cc8f64af74c088b7de703cd327afd984f49d9478",
        "56be132b780656fe2444cd34326eb5d7aac91d2096abf0fe673a99270622ec87"
    )

    @Test fun bothVerifiedSignersGrantOnlyAccountDiscovery() {
        certs.forEach { cert ->
            assertEquals(setOf(ACCOUNT), getGooglePackagePermissions(PackageAndCertHash(packageName, "SHA-256", cert)))
        }
    }

    @Test fun packageNameAndCertificateMustBothMatch() {
        certs.forEach { cert ->
            assertTrue(getGooglePackagePermissions(PackageAndCertHash("test.restore", "SHA-256", cert)).isEmpty())
        }
        assertTrue(getGooglePackagePermissions(PackageAndCertHash(packageName, "SHA-256", "0".repeat(64))).isEmpty())
    }
}
