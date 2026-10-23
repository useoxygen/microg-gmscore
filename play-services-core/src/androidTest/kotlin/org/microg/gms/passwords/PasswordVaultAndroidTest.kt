/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.net.Uri
import androidx.credentials.provider.CallingAppInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic, uniquely named vaults only; never selects an Android/Google account or performs RPCs. */
@RunWith(AndroidJUnit4::class)
class PasswordVaultAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun identity() = "password-android-fixture/" + UUID.randomUUID()
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fixtureState(marker: String) = VaultState(chromeLocalData = hashMapOf("fixture" to marker.toByteArray()))

    @Test fun passwordDialogCallbackRejectsObscuredInputBeforeDelegating() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        var delivered = 0
        val callback = java.lang.reflect.Proxy.newProxyInstance(android.view.Window.Callback::class.java.classLoader,
            arrayOf(android.view.Window.Callback::class.java)) { _, method, _ ->
            if (method.name == "dispatchTouchEvent") { delivered++; true } else if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        } as android.view.Window.Callback
        val guard = PasswordDialogTouchGuard(callback)
        val pointer = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = android.view.MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f }
        for (flags in listOf(0, android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED, android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
            val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 1, arrayOf(pointer), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
            try { assertEquals(flags == 0, guard.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        assertEquals(1, delivered)
    }

    @Test fun automaticSaveAtomicallyRespectsBlockedSitesAndPreservesMetadata() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = identity()
        val store = AndroidVaultStore(context, identity)
        val vault = DevicePasswordVault(context, identity)
        val blocked = PasswordData.blockedSite("https://example.invalid/path", 1234L)
        val original = PasswordData.create(blocked.realm, blocked.realm, "synthetic", "before", 1234L)
            .withDisplayName("Fixture name").withDefaultNote("Fixture note", 1234L)
        val other = PasswordData.create("https://other.invalid/", "https://other.invalid/", "synthetic", "unrelated", 1234L)
        fun wrapped(value: PasswordData) = Proto.field(1, value.encode())
        try {
            vault.upsert(wrapped(original)); vault.upsert(wrapped(other))
            val proposed = original.withPassword("after", 2345L)
            // Simulate a block added after the caller inspected the old save suggestions.
            assertTrue(vault.acceptsAutomaticSave(original.realm))
            vault.upsert(wrapped(blocked))
            assertFalse(vault.acceptsAutomaticSave(original.realm))
            assertThrows(PasswordSaveBlockedException::class.java) { vault.upsertAutomatic(wrapped(proposed)) }
            assertArrayEquals(original.encode(), DevicePasswordVault(context, identity).list().single { it.id == original.tag() }.data.encode())
            vault.delete(wrapped(blocked))
            vault.upsertAutomatic(wrapped(proposed))
            val reopened = DevicePasswordVault(context, identity).list()
            val updated = reopened.single { it.id == original.tag() }.data
            assertEquals("after", updated.password)
            assertEquals("Fixture name", updated.displayName)
            assertEquals("Fixture note", updated.defaultNote!!.value)
            assertArrayEquals(other.encode(), reopened.single { it.id == other.tag() }.data.encode())
        } finally { store.clear() }
    }

    @Test fun clientTagMatchesGooglesAndroidUriEncodingForPunctuationAndUnicode() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val origin = "https://example.invalid/a'b"
        val realm = "https://example.invalid/"
        val username = (32..127).map(Int::toChar).joinToString("") + "é😀"
        val escaped = listOf(origin, "", username, "", realm).joinToString("|") {
            Uri.encode(it, "!$&'()*+,-./;=@_~ ").replace(" ", "%20")
        }
        val expected = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1")
            .digest(Proto.field(45873, byteArrayOf()) + escaped.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        assertEquals(expected, PasswordData.create(realm, origin, username, "synthetic", 1234L).tag())
    }

    @Test fun encryptedStateReopensAndNeverWritesPlaintextToDatabaseOrJournal() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = identity(); val store = AndroidVaultStore(context, identity)
        val marker = "synthetic-only-" + UUID.randomUUID()
        try {
            store.locked { it.save(fixtureState(marker)) }
            assertArrayEquals(marker.toByteArray(), AndroidVaultStore(context, identity).locked { it.load().chromeLocalData["fixture"] })
            for (file in context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("password-vaults.db") }) {
                assertFalse("Plaintext reached persistent storage", file.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
            }
            assertFalse(context.getDatabasePath("password-vaults.db").exists())
        } finally { store.clear() }
    }

    @Test fun lostKeystoreKeyFailsClosedWithoutReplacingVaultBytes() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = identity(); val store = AndroidVaultStore(context, identity)
        try {
            store.locked { it.save(fixtureState("synthetic")) }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "microg.passwords.v1." + hash(identity)
            keyStore.deleteEntry(alias)
            assertThrows(VaultLockedException::class.java) { AndroidVaultStore(context, identity).locked { it.load() } }
            assertFalse(keyStore.containsAlias(alias))
        } finally { store.clear() }
    }

    @Test fun copiedCiphertextCannotCrossAccountBoundary() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val a = identity(); val b = identity()
        val first = AndroidVaultStore(context, a); val second = AndroidVaultStore(context, b)
        try {
            first.locked { it.save(fixtureState("first synthetic value")) }
            second.locked { it.save(fixtureState("second synthetic value")) }
            SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "password-vaults.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val ciphertext = db.query("vaults", arrayOf("ciphertext"), "account_id=?", arrayOf(hash(a)), null, null, null)
                    .use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getBlob(0) }
                db.update("vaults", ContentValues().apply { put("ciphertext", ciphertext) }, "account_id=?", arrayOf(hash(b)))
            }
            assertThrows(VaultLockedException::class.java) { second.locked { it.load() } }
            assertArrayEquals("first synthetic value".toByteArray(), first.locked { it.load().chromeLocalData["fixture"] })
        } finally { first.clear(); second.clear() }
    }

    @Test fun revokedAccountGuardBlocksBothReadAndPostNetworkCommit() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = identity(); val allowed = AtomicBoolean(true)
        val store = AndroidVaultStore(context, identity) { if (!allowed.get()) throw VaultLockedException() }
        try {
            store.locked { session ->
                session.save(fixtureState("before removal")); allowed.set(false)
                assertThrows(VaultLockedException::class.java) { session.save(fixtureState("must not commit")) }
                assertThrows(VaultLockedException::class.java) { session.load() }
            }
            allowed.set(true)
            assertArrayEquals("before removal".toByteArray(), store.locked { it.load().chromeLocalData["fixture"] })
        } finally { allowed.set(true); store.clear() }
    }

    @Test fun settingsReadFromDatabaseAcrossRepositoryInstances() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = identity(); val first = AndroidVaultStore(context, identity); val second = AndroidVaultStore(context, identity)
        try {
            first.setEnabled(true); assertTrue(second.enabled)
            second.setEnabled(false); assertFalse(first.enabled)
        } finally { first.clear() }
    }

    @Test fun untrustedAppCannotDelegateAWebsiteOriginEvenWhenClaimingChromePackage() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        // GmsCore deliberately reports Google's spoofed signature. Use the separately installed
        // fixture package's real signing identity to model an untrusted third-party caller.
        val fixture = InstrumentationRegistry.getInstrumentation().context
        val signing = fixture.packageManager.getPackageInfo(fixture.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signing.apkContentsSigners.single().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertFalse("Fixture unexpectedly has the trusted browser certificate",
            fingerprint == "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83")
        assertThrows(IllegalStateException::class.java) { PasswordCaller.from(CallingAppInfo(context.packageName, signing, "https://example.invalid")) }
        assertThrows(IllegalStateException::class.java) { PasswordCaller.from(CallingAppInfo("com.android.chrome", signing, "https://example.invalid")) }
        val caller = PasswordCaller.from(CallingAppInfo(context.packageName, signing))
        assertTrue(caller.realms.single().startsWith("android://"))
        assertTrue(caller.realms.single().endsWith("@${context.packageName}/"))
    }
}
