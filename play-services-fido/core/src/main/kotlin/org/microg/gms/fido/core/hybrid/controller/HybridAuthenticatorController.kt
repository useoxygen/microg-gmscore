/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.fido.core.hybrid.controller

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.google.android.gms.fido.fido2.api.common.ErrorCode
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.microg.gms.fido.core.RequestHandlingException
import org.microg.gms.fido.core.hybrid.HandshakePhase
import org.microg.gms.fido.core.hybrid.ble.HybridBleAdvertiser
import org.microg.gms.fido.core.hybrid.generateEcKeyPair
import org.microg.gms.fido.core.hybrid.HybridAuthenticatedFrames
import org.microg.gms.fido.core.hybrid.HybridAccountSessions
import org.microg.gms.fido.core.hybrid.HybridConnectionGuard
import org.microg.gms.fido.core.hybrid.model.QrCodeData
import org.microg.gms.fido.core.hybrid.transport.AuthenticatorTunnelTransport
import org.microg.gms.fido.core.hybrid.transport.TunnelCallback
import org.microg.gms.fido.core.hybrid.tryResumeData
import org.microg.gms.fido.core.hybrid.tryResumeWithError
import org.microg.gms.fido.core.hybrid.tunnel.TunnelException
import org.microg.gms.fido.core.hybrid.tunnel.TunnelWebsocket
import org.microg.gms.fido.core.hybrid.utils.CryptoHelper
import org.microg.gms.fido.core.hybrid.utils.NoiseCrypter
import org.microg.gms.fido.core.hybrid.utils.NoiseHandshakeState
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorGetAssertionRequest
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorGetInfoRequest
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorGetInfoResponse
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorMakeCredentialRequest
import org.microg.gms.fido.core.protocol.msgs.Ctap2CommandCode
import org.microg.gms.fido.core.protocol.msgs.Ctap2Request
import org.microg.gms.fido.core.protocol.msgs.Ctap2Response
import org.microg.gms.fido.core.transport.CtapStatus
import java.io.ByteArrayInputStream
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

private const val TAG = "AuthenticatorController"

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class HybridAuthenticatorController(context: Context, private val accountPrfAvailable: Boolean = false) {
    private val connection = HybridConnectionGuard()
    private val messages = Channel<Pair<TunnelWebsocket?, ByteArray>>(16)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var bleAdvertiser: HybridBleAdvertiser? = null
    private var transport: AuthenticatorTunnelTransport? = null
    @Volatile private var handshakePhase = HandshakePhase.NONE
    private var noiseState: NoiseHandshakeState? = null
    @Volatile private var crypter: NoiseCrypter? = null

    private val operationActive = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var activeRequestDigest: ByteArray? = null
    @Volatile private var ownerOperation: Job? = null
    @Volatile private var frameReader: HybridAuthenticatedFrames? = null
    @Volatile private var accountSession: HybridAccountSessions.Session? = null
    fun issueAccountRequest(options: ByteArray, rp: String, hash: ByteArray, create: Boolean): String =
        HybridAccountSessions.issue(accountSession ?: error("Hybrid session unavailable"), options, rp, hash, create,
            activeRequestDigest ?: error("Hybrid request unavailable"))
    fun completeAccountRequest(token: String) { accountSession?.let { HybridAccountSessions.complete(it, token) } }
    private fun invalidate() {
        connection.invalidate()
        ownerOperation?.cancel()
        frameReader = null
        activeRequestDigest = null
        handshakePhase = HandshakePhase.NONE
        crypter = null
        accountSession?.revoke(); accountSession = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun release() {
        invalidate()
        messages.close(); worker.cancel()
        runCatching { bleAdvertiser?.stopAdvertising() }.onFailure {
            Log.w(TAG, "release: stopAdvertising failed")
        }
        bleAdvertiser = null

        runCatching { transport?.stopConnecting() }.onFailure {
            Log.w(TAG, "release: websocket close failed")
        }
        transport = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startBleAdvertiser(eidKey: ByteArray, plaintext: ByteArray, onFailure: (Int) -> Unit) {
        bleAdvertiser = bleAdvertiser ?: HybridBleAdvertiser(adapter, onAdvertiseFailure = onFailure)
        bleAdvertiser!!.startAdvertising(CryptoHelper.generateEid(eidKey, plaintext))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    suspend fun startAuth(qrCodeData: QrCodeData, handleAuthenticator: suspend (Ctap2Request) -> Ctap2Response?, completed: (Boolean) -> Unit) = suspendCancellableCoroutine { cont ->
        val randomSeed = qrCodeData.randomSeed
        val peerPublicKey = qrCodeData.peerPublicKey
        val tunnelId = CryptoHelper.endif(ikm = randomSeed, salt = ByteArray(0), info = byteArrayOf(2, 0, 0, 0), length = 16)
        val eidKey = CryptoHelper.endif(ikm = randomSeed, salt = ByteArray(0), info = byteArrayOf(1, 0, 0, 0), length = 64)

        transport = AuthenticatorTunnelTransport(tunnelId, object : TunnelCallback {
            @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
            override fun onSocketConnect(websocket: TunnelWebsocket?, bytes: ByteArray) {
                runCatching {
                    connection.connected()
                    val generatedPlaintext = CryptoHelper.generatedSeed(bytes)
                    startBleAdvertiser(eidKey, generatedPlaintext) { errorCode ->
                        Log.w(TAG, "BLE advertise failed (errorCode=$errorCode), aborting connection")
                        invalidate()
                        if (!cont.isCompleted) cont.tryResumeWithError(
                            RequestHandlingException(ErrorCode.UNKNOWN_ERR, "BLE advertising failed: errorCode=$errorCode")
                        )
                    }
                    noiseState = NoiseHandshakeState(mode = 3).apply {
                        mixHash(byteArrayOf(1))
                        mixHash(CryptoHelper.uncompress(peerPublicKey))
                        mixKeyAndHash(CryptoHelper.endif(ikm = randomSeed, salt = generatedPlaintext, info = byteArrayOf(3, 0, 0, 0), length = 32))
                    }
                    handshakePhase = HandshakePhase.CLIENT_HELLO_SENT
                }.onFailure {
                    if (!cont.isCompleted) cont.tryResumeWithError(it)
                }
            }

            override fun onSocketError(error: TunnelException) {
                invalidate()
                if (!cont.isCompleted) cont.tryResumeWithError(RequestHandlingException(ErrorCode.UNKNOWN_ERR, error.message ?: "error"))
            }

            override fun onSocketClose() {
                invalidate()
                if (!cont.isCompleted) cont.tryResumeWithError(RequestHandlingException(ErrorCode.UNKNOWN_ERR, "Tunnel closed"))
            }

            override fun onMessage(websocket: TunnelWebsocket?, data: ByteArray) {
                if (!messages.trySend(websocket to data.copyOf()).isSuccess) {
                    invalidate()
                    if (!cont.isCompleted) cont.tryResumeWithError(SecurityException("Hybrid frame queue unavailable"))
                }
            }
        })

        // Never block OkHttp's reader on owner UI: close/error must revoke a pending account request immediately.
        worker.launch {
            for ((websocket, data) in messages) {
                try {
                    when (handshakePhase) {
                        HandshakePhase.CLIENT_HELLO_SENT -> {
                            require(data.size >= 65)
                            handleClientHello(websocket, data, peerPublicKey)
                        }
                        HandshakePhase.READY -> handleCtapRequest(websocket, data, handleAuthenticator) { response, success ->
                            completed.invoke(success)
                            if (!cont.isCompleted) cont.tryResumeData(response)
                        }
                        else -> error("Hybrid session unavailable")
                    }
                } catch (e: Exception) {
                    invalidate()
                    if (!cont.isCompleted) cont.tryResumeWithError(SecurityException("Hybrid request failed"))
                } finally { data.fill(0) }
            }
        }
        transport!!.startConnecting()

        cont.invokeOnCancellation {
            invalidate()
            messages.close(); worker.cancel()
            runCatching { bleAdvertiser?.stopAdvertising() }
            runCatching { transport?.stopConnecting() }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun handleClientHello(ws: TunnelWebsocket?, data: ByteArray, peerPublicKey: ECPublicKey) {
        bleAdvertiser?.stopAdvertising()
        val generation = connection.capture()
        val state = noiseState ?: error("Noise state not initialized")
        val pcEphemeralPubKey = data.copyOfRange(0, 65)
        val clientHelloPayload = data.copyOfRange(65, data.size)
        state.mixHash(pcEphemeralPubKey)
        state.mixKey(pcEphemeralPubKey)
        val decryptedPayload = state.decryptAndHash(clientHelloPayload)
        require(decryptedPayload.isEmpty()) { "Invalid hybrid handshake" }
        val ephemeralKeyPair: Pair<ECPublicKey, ECPrivateKey> = generateEcKeyPair()
        val phoneEphemeralPubKey = CryptoHelper.uncompress(ephemeralKeyPair.first)
        state.mixHash(phoneEphemeralPubKey)
        state.mixKey(phoneEphemeralPubKey)
        val peDh = CryptoHelper.recd(ephemeralKeyPair.second, pcEphemeralPubKey)
        state.mixKey(peDh)
        val pcStaticPubKey = CryptoHelper.uncompress(peerPublicKey)
        val psDh = CryptoHelper.recd(ephemeralKeyPair.second, pcStaticPubKey)
        state.mixKey(psDh)
        val serverHelloPayload = state.encryptAndHash(ByteArray(0))
        val serverHello = phoneEphemeralPubKey + serverHelloPayload
        ws?.send(serverHello)

        val (rxKey, txKey) = state.splitSessionKeys()
        connection.install(generation) {
            crypter = NoiseCrypter(rxKey, txKey)
            frameReader = HybridAuthenticatedFrames(crypter!!, ::invalidate)
            handshakePhase = HandshakePhase.READY
            accountSession = HybridAccountSessions.open(state.handshakeDigest()) {
                connection.live(generation) && handshakePhase == HandshakePhase.READY && crypter != null && transport != null
            }
        }

        val message = encryptGetInfoPayload() ?: error("Failed to encrypt post-handshake message")

        Log.d(TAG, "Encrypted post-handshake message size: ${message.size} bytes")

        ws?.send(message)
        Log.d(TAG, "✓ Post-handshake message sent successfully")
    }

    private fun encryptGetInfoPayload(): ByteArray? {
        val crypter = this.crypter ?: error("Crypter not initialized, cannot send post-handshake message")

        val getInfoBytes = AuthenticatorGetInfoResponse(
            versions = arrayListOf("FIDO_2_0", "FIDO_2_1"),
            aaguid = ByteArray(16),
            extensions = if (accountPrfAvailable) listOf("prf") else emptyList(),
            options = AuthenticatorGetInfoResponse.Companion.Options(
                residentKey = true,
                userPresence = true,
                userVerification = true,
                platformDevice = false),
            transports = listOf("internal", "cable")
        ).encodePayload()

        Log.d(TAG, "GetInfo response size: ${getInfoBytes.size} bytes")

        val getInfoByteString = CBORObject.FromObject(getInfoBytes)

        val postHandshakeMessage = CBORObject.NewMap().apply {
            set(1, getInfoByteString)
            set(3, CBORObject.NewArray().apply {
                Add("dc")
                Add("ctap")
            })
        }

        val messageBytes = postHandshakeMessage.EncodeToBytes()
        Log.d(TAG, "Post-handshake message size: ${messageBytes.size} bytes")
        return crypter.encrypt(messageBytes)
    }

    private fun handleCtapRequest(ws: TunnelWebsocket?, data: ByteArray, handleAuthenticator: suspend (Ctap2Request) -> Ctap2Response?,
        complete: (ByteArray, Boolean) -> Unit) {
        val crypt = this.crypter ?: error("Hybrid session unavailable")
        val generation = connection.capture()
        val decrypted = frameReader?.read(data) ?: throw SecurityException("Hybrid peer disconnected")
        val frameType = decrypted[0].toInt() and 0xFF
        if (frameType == 0x01) {
            if (decrypted.size < 2) {
                ws?.sendCtapResponse(byteArrayOf(CtapStatus.CTAP2_ERR_INVALID_CBOR.code))
                return
            }
            val commandCode = Ctap2CommandCode.entries.find { it.byte == decrypted[1] }
            if (commandCode == null) {
                ws?.sendCtapResponse(byteArrayOf(CtapStatus.CTAP1_ERR_INVALID_COMMAND.code))
                return
            }
            val params = if (decrypted.size > 2) {
                try {
                    Log.d(TAG, "CBOR size: ${decrypted.size - 2} bytes")
                    CBORObject.Read(ByteArrayInputStream(decrypted, 2, decrypted.size - 2))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse CBOR parameters")
                    null
                }
            } else {
                null
            }
            val request = when (commandCode) {
                Ctap2CommandCode.AuthenticatorMakeCredential -> {
                    if (params == null) {
                        ws?.sendCtapResponse(byteArrayOf(CtapStatus.CTAP2_ERR_MISSING_PARAMETER.code))
                        return
                    }
                    AuthenticatorMakeCredentialRequest.decodeFromCbor(params)
                }

                Ctap2CommandCode.AuthenticatorGetAssertion -> {
                    if (params == null) {
                        ws?.sendCtapResponse(byteArrayOf(CtapStatus.CTAP2_ERR_MISSING_PARAMETER.code))
                        return
                    }
                    AuthenticatorGetAssertionRequest.decodeFromCbor(params)
                }

                Ctap2CommandCode.AuthenticatorGetInfo -> {
                    AuthenticatorGetInfoRequest()
                }

                else -> {
                    ws?.sendCtapResponse(byteArrayOf(CtapStatus.CTAP1_ERR_INVALID_COMMAND.code))
                    return
                }
            }
            if (!operationActive.compareAndSet(false, true)) {
                ws?.sendCtapResponse(byteArrayOf(CtapStatus.CTAP1_ERR_CHANNEL_BUSY.code))
                return
            }
            activeRequestDigest = java.security.MessageDigest.getInstance("SHA-256").digest(decrypted)
            val operation = worker.launch(start = CoroutineStart.LAZY) {
                try {
                    val response = handleAuthenticator(request)
                    check(connection.live(generation) && handshakePhase == HandshakePhase.READY && crypt === crypter)
                    val responseBytes = if (response == null) byteArrayOf(CtapStatus.CTAP1_ERR_OTHER.code)
                        else byteArrayOf(0) + response.encodePayloadAsCbor().EncodeToBytes()
                    ws?.sendCtapResponse(responseBytes)
                    if (request !is AuthenticatorGetInfoRequest) complete(responseBytes, response != null)
                } catch (_: CancellationException) {
                    // Shutdown/socket loss revoked authority; never emit a late result or error frame.
                } catch (_: Exception) {
                    if (connection.live(generation) && crypt === crypter) {
                        val refused = byteArrayOf(CtapStatus.CTAP1_ERR_OTHER.code)
                        ws?.sendCtapResponse(refused)
                        complete(refused, false)
                    }
                } finally { activeRequestDigest = null; operationActive.set(false) }
            }
            ownerOperation = operation
            if (connection.live(generation)) operation.start() else operation.cancel()
            return
        }
        // UPDATE/JSON carry no account authority. This receiver does not expose their contents.
        require(frameType in setOf(0x02, 0x03))
    }

    private fun TunnelWebsocket.sendCtapResponse(ctapResponse: ByteArray) {
        try {
            val crypter = crypter ?: error("Crypter not initialized (handshake incomplete)")
            val framedMessage = byteArrayOf(0x01) + ctapResponse
            val encrypted = crypter.encrypt(framedMessage) ?: error("Failed to encrypt CTAP response")
            Log.d(TAG, "Sending encrypted CTAP response: ${encrypted.size} bytes (framed)")
            send(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send CTAP response")
        }
    }
}
