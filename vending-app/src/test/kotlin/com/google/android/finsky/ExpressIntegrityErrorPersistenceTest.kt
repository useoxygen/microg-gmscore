/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package com.google.android.finsky

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.vending.VendingPreferences
import com.android.vending.makeTimestamp
import com.google.android.finsky.expressintegrityservice.DeviceIntegrity
import com.google.android.finsky.expressintegrityservice.DeviceIntegrityResponse
import com.google.android.finsky.expressintegrityservice.ExpressIntegrityServiceImpl
import com.google.android.finsky.expressintegrityservice.IntermediateIntegrity
import com.google.android.finsky.expressintegrityservice.IntermediateIntegrityResponseData
import com.google.android.finsky.expressintegrityservice.requestExpressErrorCode
import com.google.android.finsky.model.StandardIntegrityException
import com.google.android.play.core.integrity.protocol.IExpressIntegrityServiceCallback
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.settings.SettingsContract
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver

/** Local settings fixture only; the request service and cache reads/writes run unmodified. */
class ExpressIntegritySettingsProvider : ContentProvider() {
    private var apps = ""
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
        MatrixCursor(requireNotNull(projection)).apply {
            addRow(projection.map {
                when (it) {
                    SettingsContract.SafetyNet.ENABLED -> 1
                    SettingsContract.Vending.PLAY_INTEGRITY_APP_LIST -> apps
                    else -> error("Unexpected settings column: $it")
                }
            })
        }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        apps = requireNotNull(values?.getAsString(SettingsContract.Vending.PLAY_INTEGRITY_APP_LIST))
        return 1
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected delete")
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ExpressIntegrityErrorPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val packageName = "org.example.integrity"
    private val project = 123L
    private val cacheFile get() = File(context.filesDir, "finsky/shared/express_integrity_valuestore.pb")

    @Before fun setUp() {
        cacheFile.delete()
        val uid = 31415
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = this@ExpressIntegrityErrorPersistenceTest.packageName
            applicationInfo = ApplicationInfo().apply { packageName = this@ExpressIntegrityErrorPersistenceTest.packageName; this.uid = uid }
            signatures = arrayOf(Signature(ByteArray(32) { it.toByte() }))
        })
        ShadowBinder.setCallingUid(uid)
        ShadowBinder.setCallingPid(0)
        val authority = SettingsContract.getAuthority(context)
        val provider = ExpressIntegritySettingsProvider().apply {
            attachInfo(context, ProviderInfo().apply { this.authority = authority })
        }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @After fun tearDown() {
        ShadowBinder.reset()
        cacheFile.delete()
    }

    private fun response(error: TestErrorResponse? = null) = IntermediateIntegrityResponse(
        intermediateToken = "local-intermediate-fixture".encodeUtf8(),
        serverGenerated = makeTimestamp(System.currentTimeMillis()),
        errorInfo = error
    ).let { IntermediateIntegrityResponse.ADAPTER.decode(it.encode()) }

    private fun save(response: IntermediateIntegrityResponse, cloudProject: Long = project) = runBlocking {
        val callerKey = ClientKey(generated = makeTimestamp(System.currentTimeMillis()))
        updateLocalExpressFilePB(context, IntermediateIntegrityResponseData(
            intermediateIntegrity = IntermediateIntegrity(
                packageName, cloudProject, RESULT_UN_AUTH, callerKey, response.intermediateToken,
                response.serverGenerated, 0, response.requestExpressErrorCode(), response.integrityAdvice
            ),
            callerKeyMd5 = "local-fixture", appVersionCode = 1,
            deviceIntegrityResponse = DeviceIntegrityResponse(DeviceIntegrity(null, null, null, null), false, "local-fixture", null),
            appAccessRiskVerdictEnabled = null
        ))
    }

    private fun reload() = cacheFile.inputStream().use { ExpressFilePB.ADAPTER.decode(it) }
    private fun record(cloudProject: Long = project) = reload().integrityRequestWrapper.single { it.cloudProjectNumber == cloudProject }

    private fun request(): Bundle {
        assertTrue(callerAppToIntegrityData(context, packageName).allowed)
        assertTrue(VendingPreferences.isDeviceAttestationEnabled(context))
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        }
        val service = ExpressIntegrityServiceImpl(context, owner.lifecycle)
        val completed = CountDownLatch(1)
        var result: Bundle? = null
        val callback = object : IExpressIntegrityServiceCallback.Stub() {
            override fun onWarmUpExpressIntegrityToken(bundle: Bundle) { fail("Unexpected warmup callback") }
            override fun onRequestIntegrityToken(bundle: Bundle) { fail("Unexpected classic callback") }
            override fun onRequestExpressIntegrityToken(bundle: Bundle) { result = bundle; completed.countDown() }
        }
        try {
            service.requestExpressIntegrityToken(Bundle().apply {
                putString(KEY_PACKAGE_NAME, packageName)
                putLong(KEY_CLOUD_PROJECT, project)
                putString(KEY_NONCE, "local-request-fixture")
            }, callback)
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (completed.await(25, TimeUnit.MILLISECONDS)) return requireNotNull(result)
            }
            error("Request callback did not complete")
        } finally {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun assertRejected(code: Int) {
        val result = request()
        assertTrue(result.containsKey(KEY_ERROR))
        assertEquals(code, result.getInt(KEY_ERROR))
        assertFalse(result.containsKey(KEY_TOKEN))
    }

    private fun assertSuccess() {
        val result = request()
        assertFalse("Unexpected request error: ${result.getInt(KEY_ERROR)}", result.containsKey(KEY_ERROR))
        assertFalse(result.getString(KEY_TOKEN).isNullOrEmpty())
    }

    @Test fun requestExpressErrorSurvivesPersistenceAndRejectsTheActualRequest() {
        val code = -17
        save(response(TestErrorResponse(code, TestErrorType.REQUEST_EXPRESS)))
        val persisted = record()
        assertRejected(code)
        assertEquals(code, persisted.deviceIntegrityWrapper?.errorCode)
    }

    @Test fun presentZeroAndUnknownErrorCodesAreNotTreatedAsAbsentOrRemapped() {
        for (code in listOf(0, -12345)) {
            save(response(TestErrorResponse(code, TestErrorType.REQUEST_EXPRESS)))
            val persisted = record()
            assertRejected(code)
            assertEquals(code, persisted.deviceIntegrityWrapper?.errorCode)
        }
    }

    @Test fun normalResponsePreservesAbsentErrorAndStillReturnsASuccessResult() {
        val response = response()
        save(response)
        val wrapper = requireNotNull(record().deviceIntegrityWrapper)
        assertNull(wrapper.errorCode)
        assertEquals(response.intermediateToken, wrapper.deviceIntegrityToken)
        assertEquals(response.serverGenerated, wrapper.serverGenerated)
        assertNotNull(wrapper.creationTime)
        assertSuccess()
    }

    @Test fun missingErrorCodeRemainsAbsent() {
        save(response(TestErrorResponse(testErrorType = TestErrorType.REQUEST_EXPRESS)))
        assertNull(record().deviceIntegrityWrapper?.errorCode)
        assertSuccess()
    }

    @Test fun replacingNormalWithErrorAndThenNormalClearsErrorAndPreservesOtherRecords() {
        save(response(TestErrorResponse(-8, TestErrorType.REQUEST_EXPRESS)), cloudProject = 456L)
        val unrelated = record(456L)
        save(response())
        save(response(TestErrorResponse(-17, TestErrorType.REQUEST_EXPRESS)))
        assertRejected(-17)
        assertEquals(2, reload().integrityRequestWrapper.size)
        assertEquals(unrelated, record(456L))

        save(response())
        assertEquals(2, reload().integrityRequestWrapper.size)
        assertNull(record().deviceIntegrityWrapper?.errorCode)
        assertEquals(unrelated, record(456L))
        assertSuccess()
    }

    @Test fun warmupErrorStillThrowsBeforePersistence() {
        val exception = assertThrows(StandardIntegrityException::class.java) {
            save(response(TestErrorResponse(-8, TestErrorType.WARMUP)))
        }
        assertEquals(-8, exception.code)
        assertFalse(cacheFile.exists())
    }
}
