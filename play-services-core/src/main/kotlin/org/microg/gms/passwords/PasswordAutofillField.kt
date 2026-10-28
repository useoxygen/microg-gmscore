/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View

/** Chrome's native virtual inputs expose HTML type/autocomplete, not Android inputType. */
internal object PasswordAutofillField {
    enum class Kind { PASSWORD, USERNAME, OTHER }

    fun kind(node: AssistStructure.ViewNode, trustedBrowser: Boolean): Kind = kind(
        node.inputType, node.autofillHints.orEmpty().toList(),
        if (trustedBrowser) node.htmlInfo?.tag else null,
        if (trustedBrowser) node.htmlInfo?.attributes.orEmpty().map { it.first to it.second } else emptyList(),
        trustedBrowser
    )

    internal fun kind(inputType: Int, hints: List<String>, htmlTag: String?, attributes: List<Pair<String, String>>, trustedBrowser: Boolean): Kind {
        val variant = inputType and InputType.TYPE_MASK_VARIATION
        val type = inputType and InputType.TYPE_MASK_CLASS
        val input = trustedBrowser && htmlTag.equals("input", ignoreCase = true)
        fun attribute(name: String) = attributes.filter { it.first.equals(name, ignoreCase = true) }
            .singleOrNull()?.second?.lowercase()
        val htmlType = if (input) attribute("type") else null
        val autocomplete = if (input) attribute("autocomplete")?.split(Regex("\\s+")) ?: hints else emptyList()
        if (View.AUTOFILL_HINT_PASSWORD in hints ||
            type == InputType.TYPE_CLASS_TEXT && variant in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            type == InputType.TYPE_CLASS_NUMBER && variant == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
            input && (htmlType == "password" || "current-password" in autocomplete)) return Kind.PASSWORD
        if (hints.any { it == View.AUTOFILL_HINT_USERNAME || it == View.AUTOFILL_HINT_EMAIL_ADDRESS } ||
            input && (htmlType == "email" || autocomplete.any { it == "username" || it == "email" })) return Kind.USERNAME
        return Kind.OTHER
    }
}
