/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordAutofillFieldTest {
    @Test fun chromePasswordWithoutAutocompleteIsRecognizedFromItsNativeHtmlType() {
        assertEquals(PasswordAutofillField.Kind.PASSWORD,
            PasswordAutofillField.kind(0, emptyList(), "input", listOf("type" to "password"), true))
    }

    @Test fun chromeStandardAutocompleteTokensAreRecognized() {
        assertEquals(PasswordAutofillField.Kind.PASSWORD,
            PasswordAutofillField.kind(0, listOf("current-password"), "input", emptyList(), true))
        assertEquals(PasswordAutofillField.Kind.USERNAME,
            PasswordAutofillField.kind(0, emptyList(), "input", listOf("autocomplete" to "section-login username"), true))
        assertEquals(PasswordAutofillField.Kind.USERNAME,
            PasswordAutofillField.kind(0, emptyList(), "input", listOf("type" to "email"), true))
    }

    @Test fun htmlAttributesDoNotTurnANonInputIntoAPasswordField() {
        assertEquals(PasswordAutofillField.Kind.OTHER,
            PasswordAutofillField.kind(0, emptyList(), "div", listOf("type" to "password"), true))
    }

    @Test fun untrustedPackageCannotRequestBrowserHtmlClassification() {
        assertEquals(PasswordAutofillField.Kind.OTHER,
            PasswordAutofillField.kind(0, listOf("current-password"), "input", listOf("type" to "password"), false))
    }

    @Test fun nativePasswordInputTypeRemainsSupported() {
        assertEquals(PasswordAutofillField.Kind.PASSWORD,
            PasswordAutofillField.kind(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                emptyList(), null, emptyList(), false))
    }
}
