/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A direct proof is independent of branding/grouping and never treats Android app links as credential delegation. */
class DigitalAssetLinks(private val loadDocument: (String) -> ByteArray) {
    fun allows(site: AffiliationFacet, app: AffiliationFacet, appStatements: ByteArray): Boolean {
        if (site.kind != AffiliationFacet.Kind.HTTPS || app.kind != AffiliationFacet.Kind.ANDROID) return false
        return try {
            val loaded = HashMap<String, List<JSONObject>>()
            var total = 0
            fun document(raw: ByteArray): List<JSONObject> {
                total += raw.size
                if (raw.size > MAX_DOCUMENT_BYTES || total > MAX_TOTAL_BYTES) throw ProtocolException()
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                val tokener = JSONTokener(text)
                val array = tokener.nextValue() as? JSONArray ?: throw ProtocolException()
                if (tokener.nextClean() != '\u0000' || array.length() > 1000) throw ProtocolException()
                return (0 until array.length()).map { array.getJSONObject(it) }
            }
            fun expanded(raw: ByteArray, active: Set<String>): List<JSONObject> {
                val result = ArrayList<JSONObject>()
                for (statement in document(raw)) {
                    if (!statement.has("include")) { result.add(statement); continue }
                    val include = statement.getString("include")
                    val url = include.toHttpUrlOrNull() ?: throw ProtocolException()
                    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                        url.fragment != null || include.length > 4096 || include in active) throw ProtocolException()
                    val values = loaded[include] ?: run {
                        if (loaded.size >= 10 || active.size >= 10) throw ProtocolException()
                        // Mark before descending, so a cycle or repeated include cannot evade the bound.
                        loaded[include] = emptyList()
                        expanded(loadDocument(url.toString()), active + include).also { loaded[include] = it }
                    }
                    result.addAll(values)
                    if (result.size > 10_000) throw ProtocolException()
                }
                return result
            }
            val application = expanded(appStatements, emptySet())
            fun delegates(statements: List<JSONObject>, target: AffiliationFacet): Boolean = statements.any { statement ->
                val relations = statement.optJSONArray("relation") ?: return@any false
                if ((0 until relations.length()).none { relations.opt(it) == RELATION }) return@any false
                val asset = statement.optJSONObject("target") ?: return@any false
                when (target.kind) {
                    AffiliationFacet.Kind.HTTPS -> asset.opt("namespace") == "web" &&
                        runCatching { AffiliationFacet.parse(asset.getString("site")) == target }.getOrDefault(false)
                    AffiliationFacet.Kind.ANDROID -> {
                        val identity = target.id.removePrefix("android://").split('@')
                        val digest = Base64.getUrlDecoder().decode(identity[0])
                        val fingerprint = digest.joinToString(":") { "%02X".format(it.toInt() and 255) }
                        val certificates = asset.optJSONArray("sha256_cert_fingerprints")
                        asset.opt("namespace") == "android_app" && asset.opt("package_name") == identity[1] &&
                            certificates != null && (0 until certificates.length()).any { certificates.opt(it) == fingerprint }
                    }
                    else -> false
                }
            }
            // The signed installed app must name this exact origin before the vault contacts
            // any saved credential's website. Scanning unrelated entries creates no egress.
            if (!delegates(application, site)) return false
            val endpoint = site.id + "/.well-known/assetlinks.json"
            val website = loaded[endpoint] ?: expanded(loadDocument(endpoint), setOf(endpoint))
            // Reciprocal declarations and the site's self reference prevent one-sided app-name claims.
            delegates(website, site) && delegates(website, app)
        } catch (_: Exception) { false }
    }

    companion object {
        const val RELATION = "delegate_permission/common.get_login_creds"
        const val MAX_DOCUMENT_BYTES = 256 * 1024
        const val MAX_TOTAL_BYTES = 1024 * 1024
    }
}

/** Reads only the current installed APK's own metadata after checking its actual signing certificate. */
class PasswordAssetLinks(private val context: Context) {
    fun isInstalled(app: AffiliationFacet): Boolean = runCatching { certifiedPackage(app) != null }.getOrDefault(false)
    private fun certifiedPackage(app: AffiliationFacet): android.content.pm.PackageInfo? {
        if (Build.VERSION.SDK_INT < 28 || app.kind != AffiliationFacet.Kind.ANDROID) return null
        val identity = app.id.removePrefix("android://").split('@')
        val info = context.packageManager.getPackageInfo(identity[1], PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        if (signers.size != 1 || !MessageDigest.isEqual(Base64.getUrlDecoder().decode(identity[0]),
                MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray()))) return null
        return info
    }
    private fun appStatements(app: AffiliationFacet): ByteArray? {
        val info = certifiedPackage(app) ?: return null
        val application = info.applicationInfo ?: return null
        val resource = application.metaData?.getInt("asset_statements", 0) ?: return null
        if (resource == 0) return null
        return context.packageManager.getResourcesForApplication(application).getString(resource).toByteArray(Charsets.UTF_8)
    }

    fun allows(site: AffiliationFacet, app: AffiliationFacet, budgetMillis: Long = 10_000): Boolean {
        if (Build.VERSION.SDK_INT < 28 || site.kind != AffiliationFacet.Kind.HTTPS || app.kind != AffiliationFacet.Kind.ANDROID) return false
        if (budgetMillis <= 0) return false
        return try {
            val statements = appStatements(app) ?: return false
            val deadline = android.os.SystemClock.elapsedRealtime() + budgetMillis.coerceAtMost(10_000)
            DigitalAssetLinks { url ->
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw ProtocolException()
                fetch(url, deadline - android.os.SystemClock.elapsedRealtime())
            }.allows(site, app, statements)
        } catch (_: Exception) { false }
    }

    private fun fetch(value: String, remainingMillis: Long): ByteArray {
        val url = value.toHttpUrlOrNull() ?: throw ProtocolException()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            !url.host.contains('.') || ':' in url.host || url.host.all { it in "0123456789." }) throw ProtocolException()
        val client = HTTP.newBuilder().callTimeout(remainingMillis.coerceIn(1, 10_000), TimeUnit.MILLISECONDS).build()
        return client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store").build())
            .execute().use { response ->
                if (response.code != 200 || response.body?.contentType()?.let { it.type == "application" && it.subtype == "json" } != true) throw ProtocolException()
                val body = response.body ?: throw ProtocolException()
                if (body.contentLength() > DigitalAssetLinks.MAX_DOCUMENT_BYTES) throw ProtocolException()
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > DigitalAssetLinks.MAX_DOCUMENT_BYTES - output.size()) throw ProtocolException()
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
    }
    companion object {
        private val HTTP = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false).cache(null).connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("Public affiliation host required")
                }
            }).build()
        internal fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            val first = bytes[0].toInt() and 255
            if (bytes.size == 16) return first and 0xfe != 0xfc && first != 0
            val second = bytes[1].toInt() and 255
            return first != 0 && first != 127 && first < 224 && !(first == 100 && second in 64..127) &&
                !(first == 169 && second == 254) && !(first == 192 && second == 0) && !(first == 198 && second in 18..19)
        }
    }
}
