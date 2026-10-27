/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.telephony.PhoneNumberUtils
import java.util.Locale

/** Read-only reconstruction from mapped inputs, never from the values being verified. */
internal class ContactProviderNormalization(private val provider: ContentProviderClient, private val context: Context) {
    fun reconstruct(mime: String?, values: ContentValues): ContentValues? = when (mime) {
        StructuredName.CONTENT_ITEM_TYPE -> name(values)
        Phone.CONTENT_ITEM_TYPE -> phone(values)
        else -> null
    }

    private fun name(values: ContentValues): ContentValues? {
        // LineageOS lineage-23.0 ContactsProvider2.completeName invokes the same
        // fixStructuredNameComponents as insertion, without touching the database.
        // Do not submit existing display/style values omitted by the mapper.
        val uri = ContactsContract.AUTHORITY_URI.buildUpon().appendPath("complete_name")
        for (column in (1..9).map { "data$it" }) values.getAsString(column)?.let { uri.appendQueryParameter(column, it) }
        return try {
            provider.query(uri.build(), (1..11).map { "data$it" }.toTypedArray(), null, null, null)?.use {
                ContactFieldMapper.readRows(it).singleOrNull()
            }
        } catch (error: IllegalArgumentException) { null } // A different provider may not implement completion.
        catch (error: UnsupportedOperationException) { null }
    }

    private fun phone(values: ContentValues): ContentValues? {
        if (Build.VERSION.SDK_INT < 21) return null
        val number = values.getAsString(Phone.NUMBER) ?: return null
        val country = countryIso() ?: return null
        return ContentValues(values).apply { put(Phone.NORMALIZED_NUMBER, PhoneNumberUtils.formatNumberToE164(number, country)) }
    }

    private fun countryIso(): String? = try {
        // Same source/fallback as LineageOS CountryMonitor.getCountryIso. These
        // framework methods are hidden on some builds; failure supplies no proof.
        val detector = context.getSystemService("country_detector")
        val country = detector?.javaClass?.getMethod("detectCountry")?.invoke(detector)
        val iso = if (country == null) Locale.getDefault().country
            else country.javaClass.getMethod("getCountryIso").invoke(country) as? String
        iso?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.uppercase(Locale.ROOT)
    } catch (error: ReflectiveOperationException) { null }
    catch (error: RuntimeException) { null }
}
