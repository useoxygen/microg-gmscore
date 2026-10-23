/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.os.Bundle
import android.net.LocalServerSocket
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.DataInputStream
import java.io.DataOutputStream
import org.json.JSONObject
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Explicit stages around an actual Google web-manager edit; no synthetic producer is substituted. */
@RunWith(AndroidJUnit4::class)
class PasswordOfficialClientAndroidTest {
    @Test fun renewTheNativeAccountTokenWithoutOpeningGoogleLoginUi() = withAccount { repository, _ ->
        assumeTrue(arguments.getString("passwordOfficialRefresh") == "cyc653")
        val auth = AuthManager(context, repository.account.name, Constants.GMS_PACKAGE_NAME,
            "oauth2:https://www.googleapis.com/auth/login_manager").apply {
            isGmsApp = true; forceRefreshToken = true
            setPackageSignature(Constants.GMS_PACKAGE_SIGNATURE_SHA1)
        }
        // Deliberately skip background/foreground resolution. A real challenge fails this check.
        val response = auth.requestAuth(false)
        val token = response.auth?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
        assertTrue("Native authentication unexpectedly used its token cache", response.issueAdvice != "stored")
        assertTrue("Renewed native token has no future lifetime", auth.expiry > System.currentTimeMillis() / 1000L)
        val api = PasswordApi(GooglePasswordTransport({ token }, "https://chromesyncpasswords-pa.googleapis.com/".toHttpUrl()))
        val metadata = api.metadata()
        val current = repository.store.locked { it.load().metadata } ?: throw VaultLockedException()
        assertTrue("Renewed authentication changed vault identity", Proto.decode(metadata).string(1) == Proto.decode(current).string(1))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("officialNativeRenewal", "freshAccountToken=true,futureLifetime=true,metadataMatched=true,loginUiUsed=false")
        })
    }

    @Test fun compareTheMaskedOfficialEditorWithTheNativePasswordEntirelyOnPhone() = withAccount { repository, _ ->
        assumeTrue(arguments.getString("passwordOfficialReader") == "cyc653")
        val original = repository.list(false).single { fixture(it.data) }.data
        LocalServerSocket("cyc653-approved-password-compare").use { server ->
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putBoolean("officialReaderReady", true) })
            val poll = StructPollfd().apply { fd = server.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            assertTrue("Phone-only reader helper did not arrive", Os.poll(arrayOf(poll), 30_000) == 1)
            server.accept().use { socket ->
                assertTrue("Unexpected reader peer", socket.peerCredentials.uid in setOf(0, 2000))
                socket.soTimeout = 10_000
                val packet = JSONObject().put("realm", original.realm).put("username", original.username)
                    .put("password", original.password).toString().toByteArray(Charsets.UTF_8)
                try {
                    DataOutputStream(socket.outputStream).apply { writeInt(packet.size); write(packet); flush() }
                } finally { packet.fill(0) }
                val input = DataInputStream(socket.inputStream)
                val ready = input.readBoolean(); val masked = input.readBoolean()
                val present = input.readBoolean(); val matched = input.readBoolean()
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("officialHiddenEditor", "fixtureReady=$ready,passwordMasked=$masked,passwordPresent=$present,passwordValueMatched=$matched")
                })
                assertTrue("Official editor's hidden password did not match the native vault", ready && masked && present && matched)
            }
        }
    }

    @Test fun verifyTheUnchangedOfficialClientCheckpointBeforeRetryingTheUi() = withAccount { repository, checkpoint ->
        val saved = checkpoint.locked { it.load() }
        val original = PasswordData.decode(saved.chromeLocalData.getValue("original"))
        val entries = repository.list(false)
        val current = entries.single { it.id == original.tag() }
        assertUnrelated(saved, entries, original.tag())
        assertTrue("The failed UI attempt changed the original fixture", same(original.encode(), current.data.encode()))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("officialCheckpointState", "originalExact=true,unrelatedUnchanged=true,journalEmpty=true")
        })
    }

    @Test fun checkpointTheSingleApprovedLoginBeforeTheOfficialClientEdit() = withAccount { repository, checkpoint ->
        assertTrue("An earlier official-client checkpoint needs recovery", checkpoint.locked { it.load().chromeLocalData.isEmpty() })
        val entries = repository.list(false)
        val original = entries.single { fixture(it.data) }
        assertTrue("This fixture expects an initially absent default note", original.data.defaultNote == null)
        val unrelated = entries.filter { it.id != original.id }.fold(byteArrayOf()) { out, entry ->
            out + Proto.field(1, Proto.field(1, entry.id) + Proto.field(2, entry.data.encode()))
        }
        checkpoint.locked { session -> session.save(VaultState(chromeLocalData = hashMapOf(
            "original" to original.data.encode(), "chrome" to original.chromeBytes,
            "marker" to marker().toByteArray(Charsets.UTF_8), "unrelated" to unrelated))) }
    }

    @Test fun decryptAndVerifyTheActualOfficialClientNoteWithoutRevealingThePassword() = withAccount { repository, checkpoint ->
        val saved = checkpoint.locked { it.load() }
        val original = PasswordData.decode(saved.chromeLocalData.getValue("original"))
        assertTrue("Checkpoint marker does not match this producer run", Proto.utf8(saved.chromeLocalData.getValue("marker")) == marker())
        val entries = repository.list(false)
        val current = entries.single { fixture(it.data) && it.id == original.tag() }
        assertUnrelated(saved, entries, original.tag())
        assertTrue("Official note did not decrypt to the expected value", current.data.defaultNote?.value == marker())
        assertTrue("Official client changed credential identity or value", current.data.tag() == original.tag() &&
            current.data.password == original.password && current.data.createdWindowsMicros == original.createdWindowsMicros &&
            current.data.displayName == original.displayName)
        val named = original.notes.filter { it.name.isNotEmpty() }.associate { it.name to it.encode() }
        val after = current.data.notes.filter { it.name.isNotEmpty() }.associate { it.name to it.encode() }
        assertTrue("Official client changed named notes", sameMap(named, after))
        checkpoint.locked { session -> val value = session.load()
            value.chromeLocalData["observedOfficialPayload"] = current.data.encode(); session.save(value)
        }
        val state = repository.store.locked { it.load() }
        val resource = Proto.decode(state.records.single { Proto.decode(it).string(2) == original.tag() })
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("officialClientReadback", "noteMatched=true,passwordUnchanged=true,identityUnchanged=true," +
                "creationTimeUnchanged=true,namedNotesUnchanged=true,unrelatedUnchanged=true," +
                "notesFieldPresent=${current.data.encodedNotes != null},encryptedBackupPresent=${resource.optional(10) != null}," +
                "encryptionType=${Proto.decode(Proto.decode(state.metadata!!).one(3)).integer(2)}")
        })
    }

    @Test fun restoreTheOriginalPayloadAfterTheVerifiedOfficialClientEdit() = withAccount(allowOwnRestore = true) { repository, checkpoint ->
        val saved = checkpoint.locked { it.load() }
        val original = PasswordData.decode(saved.chromeLocalData.getValue("original"))
        assertTrue("Restore input is outside the approved fixture", fixture(original))
        val observed = saved.chromeLocalData["observedOfficialPayload"] ?: error("Official producer readback must be verified before restoration")
        assertUnrelated(saved, repository.list(false), original.tag())
        val baseline = baseline(saved)
        SyntheticMetadataRestore.restore(context, repository, checkpoint,
            officialClientExpected = observed, unrelatedBaseline = baseline,
            originalChromeLocalData = Proto.decode(saved.chromeLocalData.getValue("chrome")).optional(3))
        val entries = repository.list(false)
        assertTrue("Exact official-client fixture restoration is unverified", same(original.encode(), entries.single { it.id == original.tag() }.data.encode()))
        assertTrue("Chrome-local metadata was not exactly restored", same(saved.chromeLocalData.getValue("chrome"), entries.single { it.id == original.tag() }.chromeBytes))
        assertTrue("Unrelated data changed during restoration", sameMap(baseline, entries.filter { it.id != original.tag() }.associate { it.id to it.data.encode() }))
    }

    private fun withAccount(allowOwnRestore: Boolean = false, action: (PasswordRepository, AndroidVaultStore) -> Unit) {
        assumeTrue(arguments.getString("passwordLiveOfficial") == "cyc653")
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        val checkpoint = AndroidVaultStore(context, "password-fixture-official-client/" + account.name)
        val original = checkpoint.locked { it.load().chromeLocalData["original"] }?.let(PasswordData::decode)
        assertTrue("Refusing a preexisting mutation journal", repository.store.locked { session ->
            val state = session.load()
            state.preferenceMutations.isEmpty() && (state.mutations.isEmpty() || allowOwnRestore && original != null &&
                fixture(original) && state.mutations.all { it.tag == original.tag() })
        })
        val enabled = repository.enabled
        try { repository.setEnabled(true); repository.refresh(); action(repository, checkpoint) }
        finally { if (!enabled) repository.setEnabled(false) }
    }
    private fun baseline(state: VaultState) = Proto.decode(state.chromeLocalData.getValue("unrelated")).bytes(1).associate {
        val pair = Proto.decode(it); pair.string(1) to pair.one(2)
    }
    private fun assertUnrelated(state: VaultState, entries: List<PasswordView>, tag: String) {
        assertTrue("An unrelated credential changed", sameMap(baseline(state), entries.filter { it.id != tag }.associate { it.id to it.data.encode() }))
    }
    private fun marker() = arguments.getString("passwordOfficialMarker")?.takeIf {
        Regex("cyc654-note-official-[A-Za-z0-9-]{1,80}").matches(it)
    } ?: error("Missing synthetic producer marker")
    private fun fixture(data: PasswordData) = data.realm == "https://cyc651.example.invalid/" &&
        data.username == "cyc651-synthetic-user" && !data.blocked && data.password.isNotEmpty()
    private fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
    private fun sameMap(a: Map<String, ByteArray>, b: Map<String, ByteArray>) = a.keys == b.keys && a.all { (key, value) -> same(value, b.getValue(key)) }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
}
