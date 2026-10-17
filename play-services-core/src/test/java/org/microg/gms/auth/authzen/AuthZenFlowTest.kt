/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.cryptauth.CryptAuthUserKey
import securegcm.Type
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Push → verified store → notification → consent → send, with synthetic keys, a synthetic profile and fakes. */
class AuthZenFlowTest {
    private val account = "owner.secret@example.com"
    private val otherAccount = "other.secret@example.com"
    private val clock = TestClock()
    private val storage = InMemoryStorage()
    private val logs = mutableListOf<String>()
    private val diagnostics = AuthZenDiagnostics(Random(7)) { logs += it }
    private val keys = AuthZenKeyRegistry(storage, TestLock)
    private val repository = AuthZenRepository(storage, TestLock)
    private val profile = SyntheticProfile()
    private val userKey = CryptAuthUserKey.generate()
    private val masterMaterial = ByteArray(32) { (it * 7 + 3).toByte() }
    private val keyHandle = ByteArray(32) { (it + 100).toByte() }

    private var blocker: AuthZenBlocker? = null
    private var accounts = listOf(otherAccount, account)
    private var accountPresent = true
    private val shown = mutableListOf<AuthZenRecord>()
    private val canceled = mutableListOf<String>()
    private var notificationsAllowed = true
    private val posts = mutableListOf<Pair<AuthZenEndpoint, String>>()
    private var response: AuthZenHttpResult = AuthZenHttpResult.Response(200, "{\"recorded\":true}", null)

    private val notifier = object : AuthZenIngress.Notifier {
        override fun show(record: AuthZenRecord) = notificationsAllowed.also { if (it) shown += record }
        override fun cancel(handle: String) {
            canceled += handle
        }
    }

    private fun ingress(profiles: List<AuthZenProfile> = listOf(profile), labCapture: Boolean = false) = AuthZenIngress(
        diagnostics, { blocker }, { labCapture }, { accounts }, keys, AuthZenDecoder(profiles), repository, notifier, AlertLimiter(), { clock.now() }, SecureRandom(),
    )

    private val api = AuthZenApi(object : AuthZenTransport {
        override fun post(endpoint: AuthZenEndpoint, bearerToken: String, body: String): AuthZenHttpResult {
            posts += endpoint to body
            return response
        }
    }, object : AuthZenTokenProvider {
        override fun token(account: String) = "token-secret"
        override fun invalidate(account: String, token: String) = Unit
    })

    private fun consent(profiles: List<AuthZenProfile> = listOf(profile), api: AuthZenApi? = this.api) = AuthZenConsent(
        repository,
        AuthZenSender(repository, keys, profiles, api, { accountPresent }, { true }, { userKey.keyPair to userKey.keyMaterial }, diagnostics, { clock.now() }),
        diagnostics, { clock.now() },
    )

    private fun enroll(account: String = this.account, handle: ByteArray = keyHandle) {
        keys.stage(account, AuthZenKeyType.RAW256, masterMaterial, handle)
        keys.markSent(account, handle)
        keys.commit(account, handle, userKey.handle)
    }

    private fun push(type: Type = Type.TX_REQUEST, body: ByteArray = syntheticRequestBody(created = clock.wall, expires = clock.wall + 5 * 60_000L), handle: ByteArray = keyHandle, key: ByteArray = masterMaterial) =
        SecureGcm.signcryptServerMessage(type, body, SecretKeySpec(key, "AES"), handle).encode().toByteString().base64Url()

    private fun deliver(payload: String = push(), ingress: AuthZenIngress = ingress()) = ingress.onMessage(SecureGcm.SENDER_ID, null, payload)

    private fun pendingRecord(): AuthZenRecord {
        enroll()
        assertTrue(deliver())
        return shown.single()
    }

    @Test
    fun aVerifiedRequestIsStoredForTheKeysAccountAndNotified() {
        val record = pendingRecord()
        assertEquals(account, record.account)
        assertEquals(AuthZenState.VERIFIED_PENDING, record.state)
        assertEquals(listOf(32, 57, 81), record.displayChoices.sorted())
        assertTrue(logs.any { it.startsWith("event=store") && it.contains("result=inserted") })
        assertTrue(logs.any { it.startsWith("event=ui_posted") })
        // Same push again: deduplicated, not shown twice.
        deliver(push())
        assertEquals(1, shown.size)
    }

    @Test
    fun gnotsAndOtherMessagesAreLeftToTheExistingRoutes() {
        assertFalse(ingress().onMessage(SecureGcm.SENDER_ID, "notifications", push()))
        assertFalse(ingress().onMessage(SecureGcm.SENDER_ID, null, null))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun theDefaultBuildOnlyCountsMessages() {
        enroll()
        blocker = AuthZenBlocker.FEATURE_OFF
        assertTrue(deliver())
        assertEquals(listOf("ingress", "readiness"), logs.map { it.substringBefore(' ').removePrefix("event=") })
        assertTrue(logs.last().contains("reason=feature_off"))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun withoutAVerifiedProfileRequestsAreUnsupported() {
        enroll()
        assertTrue(deliver(ingress = ingress(profiles = AuthZenProfiles.verified)))
        assertTrue(logs.any { it.startsWith("event=unsupported") && it.contains("reason=no_verified_profile") })
        assertTrue(shown.isEmpty())
        assertTrue(AuthZenProfiles.verified.isEmpty())
    }

    @Test
    fun unknownKeysForgedMessagesAndAmbiguousHandlesAreDropped() {
        deliver()
        assertTrue(logs.last().contains("event=key_lookup") && logs.last().contains("found=false"))
        enroll()
        deliver(push(key = ByteArray(32)))
        assertTrue(logs.last().contains("event=crypto_failed"))
        enroll(otherAccount)
        deliver()
        assertTrue(logs.last().contains("matches=2"))
        assertTrue(shown.isEmpty())
        assertTrue(repository.all(clock.now()).isEmpty())
    }

    @Test
    fun envelopeRejectionsHappenBeforeCryptography() {
        val ingress = ingress()
        ingress.onMessage("123", null, push())
        ingress.onMessage(SecureGcm.SENDER_ID, null, "!!!")
        ingress.onMessage(SecureGcm.SENDER_ID, null, "A".repeat(MAX_ENCODED_PAYLOAD + 4))
        assertEquals(listOf("reason=sender", "reason=encoding", "reason=too_large"), logs.map { it.substringAfterLast(' ') })
    }

    @Test
    fun aVerifiedCancellationClosesTheNotification() {
        val record = pendingRecord()
        deliver(push(Type.TX_CANCEL_REQUEST, JSONObject().put("id", "tx-1").toString().toByteArray()))
        assertEquals(listOf(record.handle), canceled)
        assertEquals(AuthZenState.CANCELED, repository.find(record.handle, clock.now())!!.state)
    }

    @Test
    fun blockedNotificationsAreReportedNotBypassed() {
        enroll()
        notificationsAllowed = false
        deliver()
        assertTrue(logs.last().startsWith("event=notifications_blocked"))
    }

    @Test
    fun alertsAreRateLimited() {
        enroll()
        val limited = AuthZenIngress(diagnostics, { null }, { false }, { accounts }, keys, AuthZenDecoder(listOf(profile)), repository, notifier, AlertLimiter(perAccount = 1), { clock.now() })
        deliver(push(body = syntheticRequestBody("tx-1", clock.wall)), limited)
        deliver(push(body = syntheticRequestBody("tx-2", clock.wall)), limited)
        assertEquals(1, shown.size)
        assertTrue(logs.any { it.contains("suppressed=true") })
    }

    @Test
    fun openingRenderingAndResumingNeverSend() {
        val record = pendingRecord()
        val consent = consent()
        repeat(3) { assertTrue(consent.open(record.handle) is ConsentStep.Show) }
        clock.advance(10_000L)
        consent.open(record.handle)
        assertTrue(posts.isEmpty())
        assertEquals(AuthZenState.PRESENTED, repository.find(record.handle, clock.now())!!.state)
    }

    @Test
    fun yesNeedsTheNumberAndAFreshBoundAuthentication() {
        val record = pendingRecord()
        val consent = consent()
        consent.open(record.handle)
        assertTrue(consent.approve(record.handle, null) is ConsentStep.Show)
        val first = (consent.approve(record.handle, 32) as ConsentStep.Authenticate).binding
        assertTrue(consent.authenticated(first, OwnerAuthResult.CANCELED) is ConsentStep.Show)
        assertTrue(consent.authenticated(first, OwnerAuthResult.SUCCESS) is ConsentStep.Show)
        assertTrue(posts.isEmpty())

        val second = (consent.approve(record.handle, 32) as ConsentStep.Authenticate).binding
        val submitted = consent.authenticated(second, OwnerAuthResult.SUCCESS) as ConsentStep.Submitted
        assertEquals(AuthZenState.APPROVED_CONFIRMED, (submitted.report as SendReport.Sent).state)
        assertEquals(1, posts.size)
        assertEquals(AuthZenEndpoint.REPLY_TX, posts.single().first)
        // The reply is bound to the exact request bytes that were verified.
        val (decision, reply) = profile.replies.single()
        assertEquals(AuthZenDecision.APPROVE, decision)
        assertTrue(reply.toByteString().startsWith(record.request!!.originalBody.toByteString()))
        // Repeating the callback cannot send again.
        consent.authenticated(second, OwnerAuthResult.SUCCESS)
        assertEquals(1, posts.size)
    }

    @Test
    fun theReplyIsSigncryptedWithThePublicKeyAndTheAuthZenKey() {
        val record = pendingRecord()
        var captured: ByteArray? = null
        val profile = object : AuthZenProfile by profile {
            override fun replyEnvelope(account: String, signcryptedReply: ByteArray) = JSONObject().also { captured = signcryptedReply }
        }
        val consent = consent(listOf(profile))
        consent.open(record.handle)
        consent.deny(record.handle)
        val received = SecureGcm.verifyDecryptClientMessage(SecureMessageParser.decode(captured!!), userKey.keyPair.public as ECPublicKey, SecretKeySpec(masterMaterial, "AES"))
        assertEquals(Type.TX_REPLY, received.type)
        assertArrayEquals(userKey.keyMaterial, SecureMessageParser.unverifiedHeader(SecureMessageParser.decode(captured!!)).verification_key_id!!.toByteArray())
    }

    @Test
    fun aWrongNumberIsSentAsMismatchWithoutAuthentication() {
        val record = pendingRecord()
        val consent = consent()
        consent.open(record.handle)
        assertTrue(consent.approve(record.handle, 57) is ConsentStep.Submitted)
        assertEquals(AuthZenDecision.MISMATCH, profile.replies.single().first)
    }

    @Test
    fun noIsSentWithoutAuthenticationAndDismissIsNothing() {
        val record = pendingRecord()
        val consent = consent()
        consent.open(record.handle)
        assertTrue(posts.isEmpty())
        consent.deny(record.handle)
        assertEquals(AuthZenDecision.DENY, profile.replies.single().first)
        assertEquals(AuthZenState.DENIED_CONFIRMED, repository.find(record.handle, clock.now())!!.state)
    }

    @Test
    fun withoutAVerifiedProfileNothingIsSent() {
        val record = pendingRecord()
        val consent = consent(profiles = AuthZenProfiles.verified, api = null)
        consent.open(record.handle)
        val step = consent.deny(record.handle) as ConsentStep.Submitted
        assertEquals(AuthZenBlocker.PROTOCOL_NOT_VERIFIED, (step.report as SendReport.NotReady).blocker)
        // Reopening shows the stored decision's state, not fresh Yes/No controls.
        assertTrue(((consent.open(record.handle) as ConsentStep.Submitted).report) is SendReport.NotReady)
        assertTrue(posts.isEmpty())
        assertTrue(logs.any { it.contains("reason=protocol_not_verified") })
    }

    @Test
    fun expiryOrAccountRemovalBeforeSendingStopsIt() {
        val record = pendingRecord()
        val consent = consent()
        consent.open(record.handle)
        val binding = (consent.approve(record.handle, 32) as ConsentStep.Authenticate).binding
        clock.advance(5 * 60_000L)
        assertTrue(consent.authenticated(binding, OwnerAuthResult.SUCCESS) is ConsentStep.Closed)

        deliver(push(body = syntheticRequestBody("tx-2", clock.wall)))
        val second = shown.last()
        consent.open(second.handle)
        accountPresent = false
        consent.deny(second.handle)
        assertEquals(AuthZenState.CANCELED, repository.find(second.handle, clock.now())!!.state)
        assertTrue(posts.isEmpty())
    }

    @Test
    fun anUncertainSendIsNeverRepeated() {
        val record = pendingRecord()
        response = AuthZenHttpResult.Interrupted
        val consent = consent()
        consent.open(record.handle)
        val binding = (consent.approve(record.handle, 32) as ConsentStep.Authenticate).binding
        val report = (consent.authenticated(binding, OwnerAuthResult.SUCCESS) as ConsentStep.Submitted).report as SendReport.Sent
        assertEquals(AuthZenState.OUTCOME_UNKNOWN, report.state)
        assertTrue(consent.approve(record.handle, 32) is ConsentStep.Closed)
        assertEquals(1, posts.size)
    }

    /** Review round 1: a 429 with Retry-After 120 s was followed by a second POST when the screen was reopened. */
    @Test
    fun retryAfterIsStoredAndEnforcedAtTheSendBoundary() {
        val record = pendingRecord()
        response = AuthZenHttpResult.Response(429, "{}", 120L)
        val consent = consent()
        consent.open(record.handle)
        val binding = (consent.approve(record.handle, 32) as ConsentStep.Authenticate).binding
        val first = (consent.authenticated(binding, OwnerAuthResult.SUCCESS) as ConsentStep.Submitted).report
        assertEquals(120_000L, (first as SendReport.RetryLater).waitMillis)
        assertEquals(1, posts.size)
        // Reopening at once, from a new screen or process, sends nothing until the wait is over.
        response = AuthZenHttpResult.Response(200, "{\"recorded\":true}", null)
        assertEquals(120_000L, ((consent().open(record.handle) as ConsentStep.Submitted).report as SendReport.RetryLater).waitMillis)
        clock.advance(119_000L)
        assertEquals(1_000L, ((consent().open(record.handle) as ConsentStep.Submitted).report as SendReport.RetryLater).waitMillis)
        assertEquals(1, posts.size)
        clock.advance(1_000L)
        assertEquals(AuthZenState.APPROVED_CONFIRMED, ((consent().open(record.handle) as ConsentStep.Submitted).report as SendReport.Sent).state)
        assertEquals(2, posts.size)
    }

    @Test
    fun aRetryAfterBeyondTheExpiryMeansTheDecisionIsNeverSent() {
        val record = pendingRecord()
        response = AuthZenHttpResult.Response(429, "{}", 3600L)
        val consent = consent()
        consent.open(record.handle)
        consent.deny(record.handle)
        response = AuthZenHttpResult.Response(200, "{\"recorded\":true}", null)
        clock.advance(5 * 60_000L)
        assertTrue(consent.open(record.handle) is ConsentStep.Closed)
        assertEquals(AuthZenState.EXPIRED, repository.find(record.handle, clock.now())!!.state)
        assertEquals(1, posts.size)
    }

    @Test
    fun anUnparsedSuccessIsNotShownAsApproved() {
        val record = pendingRecord()
        response = AuthZenHttpResult.Response(200, "{}", null)
        val consent = consent()
        consent.open(record.handle)
        consent.deny(record.handle)
        assertEquals(AuthZenState.OUTCOME_UNKNOWN, repository.find(record.handle, clock.now())!!.state)
    }

    @Test
    fun logsCarryNoAccountsNumbersKeysOrPayloads() {
        val record = pendingRecord()
        deliver(push(), ingress(labCapture = true))
        val consent = consent()
        consent.open(record.handle)
        val binding = (consent.approve(record.handle, 32) as ConsentStep.Authenticate).binding
        consent.authenticated(binding, OwnerAuthResult.SUCCESS)
        val text = logs.joinToString("\n")
        val forbidden = listOf(account, otherAccount, "token-secret", "tx-1", "Chrome", record.handle, keyHandle.toByteString().hex(), keyHandle.toByteString().base64())
        for (secret in forbidden) assertFalse(secret, text.contains(secret))
        for (number in listOf(32, 57, 81)) assertFalse("number $number", Regex("=$number\\b").containsMatchIn(text))
        assertTrue(text.contains("event=shape"))
    }
}
