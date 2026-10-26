/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.esim

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter as Writer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

internal class EsimFailure(val status: Int) : Exception("eSIM operation failed (status=$status)")
internal class EsimTargetIdentity(val imei: String, val eid: String, val model: String = Build.MODEL,
    val manufacturer: String = Build.MANUFACTURER, val software: String = "${Build.VERSION.RELEASE}/${Build.ID}/${Build.VERSION.INCREMENTAL}") {
    override fun toString() = "EsimTargetIdentity[redacted]"
}
internal fun interface EsimEntitlementHttp { fun get(url: okhttp3.HttpUrl): Pair<Int, String> }

/** TS.43 target fast-token flow. No EAP-AKA source authentication is synthesized on the target. */
internal class EsimEntitlement(private val identity: () -> EsimTargetIdentity,
    private val http: EsimEntitlementHttp = network(), private val now: () -> Long = System::currentTimeMillis) {
    fun retrieve(profile: EsimProfile, reconcile: Boolean = false, onDispatch: () -> Unit = {}): EsimWireRecord {
        if (!profile.activationCode.isNullOrBlank()) {
            if (profile.activationExpiry > 0 && profile.activationExpiry <= now()) throw EsimFailure(48552)
            return credential(profile.activationCode)
        }
        val values = request(profile, if (reconcile) "AcquireConfiguration" else "ManageSubscription", if (reconcile) null else 3, onDispatch = onDispatch)
        return response(values)
    }
    fun activate(profile: EsimProfile, targetIccid: String, onDispatch: () -> Unit = {}) {
        require(targetIccid.matches(Regex("[0-9]{10,32}")))
        val values = request(profile, "ManageSubscription", 10, targetIccid, onDispatch)
        if (values["OperationResult"] != "0") throw EsimFailure(48537)
    }
    private fun request(profile: EsimProfile, operation: String, operationType: Int?, targetIccid: String? = null, onDispatch: () -> Unit): Map<String, String> {
        if (profile.token.isNullOrBlank()) throw EsimFailure(48507)
        if (profile.tokenExpiry > 0 && profile.tokenExpiry <= now()) throw EsimFailure(48552)
        val target = identity()
        if (!target.imei.matches(Regex("[0-9]{14,17}")) || !target.eid.matches(Regex("[0-9]{32}"))) throw EsimFailure(48535)
        val endpoint = try { requireNotNull(profile.entitlementUrl).let { (if ("://" in it) it else "https://$it").toHttpUrl() } }
        catch (_: Exception) { throw EsimFailure(48507) }
        require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null)
        require(endpoint.querySize == 0 && endpoint.host != "localhost" && !endpoint.host.endsWith(".local") && ':' !in endpoint.host)
        val version = profile.metadata.obj(19)?.use { it.text(19) }?.takeIf { it.matches(Regex("[0-9]{1,2}(\\.[0-9]{1,2})?")) } ?: throw EsimFailure(48569)
        val builder = endpoint.newBuilder().addQueryParameter("app", "ap2009").addQueryParameter("vers", "0")
            .addQueryParameter("entitlement_version", version).addQueryParameter("temporary_token", profile.token)
            .addQueryParameter("operation", operation).addQueryParameter("terminal_id", target.imei)
            .addQueryParameter("terminal_vendor", target.manufacturer).addQueryParameter("terminal_model", target.model)
            .addQueryParameter("terminal_sw_version", target.software).addQueryParameter("target_terminal_id", target.imei)
            .addQueryParameter("target_terminal_eid", target.eid).addQueryParameter("old_terminal_iccid", profile.iccid)
        profile.device?.text(2)?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("old_terminal_id", it) }
        operationType?.let { builder.addQueryParameter("operation_type", it.toString()) }
        targetIccid?.let { builder.addQueryParameter("target_terminal_iccid", it) }
        val url = builder.build()
        onDispatch() // Durable intent only after local validation, immediately before possible network effects.
        val reply = try { http.get(url) } catch (_: Exception) { throw EsimFailure(7) }
        if (reply.first == 511 || reply.first == 401 || reply.first == 403) throw EsimFailure(48552)
        if (reply.first !in 200..299) throw EsimFailure(7)
        return parse(reply.second)
    }
    companion object {
        fun identity(context: Context): EsimTargetIdentity {
            val euicc = context.getSystemService(EuiccManager::class.java) ?: throw EsimFailure(17)
            if (!euicc.isEnabled) throw EsimFailure(17)
            val phone = context.getSystemService(TelephonyManager::class.java) ?: throw EsimFailure(17)
            return EsimTargetIdentity(phone.imei ?: "", euicc.eid ?: "")
        }
        private fun network(): EsimEntitlementHttp {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .dns { hostname -> okhttp3.Dns.SYSTEM.lookup(hostname).also { addresses ->
                    require(addresses.isNotEmpty() && addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress })
                } }.connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
            return EsimEntitlementHttp { url ->
                client.newCall(Request.Builder().url(url).header("Accept", "text/vnd.wap.connectivity-xml").build()).execute().use { response ->
                    val body = requireNotNull(response.body); require(body.contentLength() <= 1024 * 1024)
                    val stream = body.byteStream(); val bytes = stream.readNBytes(1024 * 1024 + 1); require(bytes.size <= 1024 * 1024)
                    response.code to bytes.toString(Charsets.UTF_8)
                }
            }
        }
        internal fun parse(xml: String): Map<String, String> {
            require(xml.length <= 1024 * 1024 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml)); val paths = mutableListOf<String>(); val values = linkedMapOf<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (parser.name == "characteristic") paths += parser.getAttributeValue(null, "type").orEmpty()
                    if (parser.name == "parm" && "APPLICATION" in paths) {
                        val key = parser.getAttributeValue(null, "name") ?: ""
                        val value = parser.getAttributeValue(null, "value") ?: ""
                        require(key.length <= 128 && value.length <= 65536 && !values.containsKey(key))
                        values[key] = value
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "characteristic") { require(paths.isNotEmpty()); paths.removeAt(paths.lastIndex) }
                parser.next()
            }
            return values
        }
        internal fun response(values: Map<String, String>): EsimWireRecord {
            if (values["OperationResult"] != "0") throw EsimFailure(48537)
            return when (values["SubscriptionResult"]) {
                "2" -> {
                    val code = values["ProfileActivationCode"].orEmpty()
                    val smdp = values["ProfileSmdpAddress"].orEmpty()
                    if (code.isBlank()) throw EsimFailure(48539)
                    credential(if (code.startsWith("LPA:")) code else if (smdp.isNotBlank()) "LPA:1\$$smdp\$$code" else code)
                }
                "1" -> {
                    val url = values["SubscriptionServiceURL"].orEmpty()
                    if (url.isBlank() || !url.toHttpUrl().isHttps) throw EsimFailure(48543)
                    EsimWireRecord.build { parcel ->
                        Writer.write(parcel, 2, false); Writer.write(parcel, 4, false); Writer.write(parcel, 7, 5)
                        EsimWireRecord.build { sheet ->
                            Writer.write(sheet, 1, url, false)
                            Writer.write(sheet, 2, values["SubscriptionServiceUserData"], true)
                            Writer.write(sheet, 3, values["SubscriptionServiceContentsType"], true)
                        }.use { Writer.write(parcel, 5, it, 0, false) }
                    }
                }
                "5" -> EsimWireRecord.build { Writer.write(it, 2, false); Writer.write(it, 4, true); Writer.write(it, 7, 7) }
                else -> throw EsimFailure(48538)
            }
        }
        fun credential(code: String): EsimWireRecord {
            require(code.isNotBlank() && code.length <= 65536)
            return EsimWireRecord.build { Writer.write(it, 1, code, false); Writer.write(it, 2, false); Writer.write(it, 4, false); Writer.write(it, 7, 1) }
        }
    }
}
