/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.assist.AssistStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PasswordAutofillTargetTest {
    private fun node(domain: String? = null, scheme: String? = null) =
        ReflectionHelpers.newInstance(AssistStructure.ViewNode::class.java).also {
            ReflectionHelpers.setField(it, "mWebDomain", domain)
            ReflectionHelpers.setField(it, "mWebScheme", scheme)
        }

    @Test fun explicitSameSiteFieldsKeepOwnerAutofillWorking() {
        assertEquals("https://accounts.example/", PasswordAutofillTarget.browserRealm(
            node("accounts.example", "https"), node("accounts.example", "https")))
        assertEquals("https://accounts.example/", PasswordAutofillTarget.browserRealm(
            node("accounts.example", "https"), null))
    }

    @Test fun parentFormDomainDoesNotAuthorizeAChildWithNoFrameOrigin() {
        val child = node()
        val parent = node("accounts.example", "https")
        ReflectionHelpers.setField(parent, "mChildren", arrayOf(child))
        assertThrows(SecurityException::class.java) { PasswordAutofillTarget.browserRealm(parent.getChildAt(0), null) }
    }

    @Test fun missingUsernameOriginCannotBorrowThePasswordOrigin() {
        assertThrows(SecurityException::class.java) {
            PasswordAutofillTarget.browserRealm(node("accounts.example", "https"), node())
        }
    }

    @Test fun explicitIframeOriginWinsOverTheParentFormDomain() {
        val child = node("login.other.example", "https")
        val parent = node("accounts.example", "https")
        ReflectionHelpers.setField(parent, "mChildren", arrayOf(child))
        assertEquals("https://login.other.example/", PasswordAutofillTarget.browserRealm(parent.getChildAt(0), null))
    }

    @Test fun crossFrameUsernameAndPasswordAreRefused() {
        assertThrows(SecurityException::class.java) {
            PasswordAutofillTarget.browserRealm(node("accounts.example", "https"), node("login.other.example", "https"))
        }
    }

    @Test fun missingOrInsecureSchemeIsRefused() {
        for (scheme in arrayOf(null, "http")) assertThrows(SecurityException::class.java) {
            PasswordAutofillTarget.browserRealm(node("accounts.example", scheme), null)
        }
    }

    @Test fun malformedDomainsCannotSupplyAPortOrUrlComponents() {
        for (domain in listOf("accounts.example:8443", "accounts.example/path", "accounts.example?query", "user@accounts.example")) {
            assertThrows(SecurityException::class.java) { PasswordAutofillTarget.browserRealm(node(domain, "https"), null) }
        }
    }
}
