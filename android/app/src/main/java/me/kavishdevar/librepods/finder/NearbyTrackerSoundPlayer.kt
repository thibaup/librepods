package me.kavishdevar.librepods.finder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

enum class NearbySoundStatus {
    SEARCHING,
    READY,
    CONNECTING,
    PLAYING,
    STOPPING,
    COMPLETED,
    FAILED
}
enum class NearbySoundProtocol(val label: String) {
    DULT("Tracker safety"),
    APPLE_FIND_MY("Apple Find My")
}

data class NearbySoundState(
    val status: NearbySoundStatus = NearbySoundStatus.SEARCHING,
    val protocol: NearbySoundProtocol? = null,
    val message: String = "Start finding to detect a nearby sound-capable AirPods advertisement."
) {
    val canStart: Boolean
        get() = status == NearbySoundStatus.READY ||
            status == NearbySoundStatus.COMPLETED ||
            status == NearbySoundStatus.FAILED

    val busy: Boolean
        get() = status == NearbySoundStatus.CONNECTING ||
            status == NearbySoundStatus.PLAYING ||
            status == NearbySoundStatus.STOPPING
}

internal data class NearbySoundProtocolSpec(
    val protocol: NearbySoundProtocol,
    val characteristicUuid: UUID,
    val startOpcode: ByteArray,
    val stopOpcode: ByteArray,
    val matchesService: (UUID) -> Boolean
)

internal object NearbySoundProtocolSpecs {
    val dultServiceUuid: UUID = UUID.fromString("15190001-12F4-C226-88ED-2AC5579F2A85")
    val dultCharacteristicUuid: UUID = UUID.fromString("8E0C0001-1D68-FB92-BF61-48377421680E")
    val findMyCharacteristicUuid: UUID = UUID.fromString("4F860003-943B-49EF-BED4-2F730304427A")

    private val dult = NearbySoundProtocolSpec(
        protocol = NearbySoundProtocol.DULT,
        characteristicUuid = dultCharacteristicUuid,
        startOpcode = byteArrayOf(0x00, 0x03),
        stopOpcode = byteArrayOf(0x01, 0x03),
        matchesService = { it == dultServiceUuid }
    )

    private val findMy = NearbySoundProtocolSpec(
        protocol = NearbySoundProtocol.APPLE_FIND_MY,
        characteristicUuid = findMyCharacteristicUuid,
        startOpcode = byteArrayOf(0x01, 0x00, 0x03),
        stopOpcode = byteArrayOf(0x01, 0x01, 0x03),
        matchesService = { it.toString().contains("fd44", ignoreCase = true) }
    )

    fun candidates(serviceUuids: Collection<UUID>): List<NearbySoundProtocolSpec> =
        listOf(dult, findMy).filter { spec -> serviceUuids.any(spec.matchesService) }
}

/**
 * Plays the anti-tracking/non-owner sound exposed by a nearby, separated Find My accessory.
 *
 * The caller may also supply a separated, non-owner advertisement because that state cannot be
 * matched to the selected device's IRK. The finder UI must disclose that limitation and obtain
 * explicit confirmation before using such a target. This controller does not use Apple account
 * state; the account-backed owner sound command lives in the Find My feature.
 */
internal class NearbyTrackerSoundPlayer(
    context: Context,
    private val onStateChanged: (NearbySoundState) -> Unit
) {
    private enum class WriteKind { START, STOP }

    private enum class GattWriteType { DESCRIPTOR, CHARACTERISTIC }

    private enum class OperationStage {
        NONE,
        CONNECTING,
        DISCOVERING_SERVICES,
        PROTOCOL_FALLBACK,
        DESCRIPTOR_WRITE,
        START_WRITE,
        START_RESPONSE,
        STOP_WRITE,
        STOP_RESPONSE
    }

    private data class InFlightGattWrite(
        val type: GattWriteType,
        val kind: WriteKind,
        val characteristic: BluetoothGattCharacteristic,
        val descriptor: BluetoothGattDescriptor? = null,
        val commandValue: ByteArray? = null
    )

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var latestDevice: BluetoothDevice? = null
    private var activeGatt: BluetoothGatt? = null
    private var gattConnected = false
    private var candidates: List<NearbySoundProtocolSpec> = emptyList()
    private var candidateIndex = -1
    private var activeSpec: NearbySoundProtocolSpec? = null
    private var activeCharacteristic: BluetoothGattCharacteristic? = null
    private var inFlightWrite: InFlightGattWrite? = null
    private var lastWriteKind: WriteKind? = null
    private var waitingForDultResponse: WriteKind? = null
    private var waitingForDultSoundCompleted = false
    private var bufferedDultResponseKind: WriteKind? = null
    private var bufferedDultResponseStatus: Int? = null
    private var bufferedDultSoundCompleted = false
    private var startMayHaveReachedAccessory = false
    private var soundStarted = false
    private var stopRequested = false
    private var sessionActive = false
    private var closingGatt = false
    private var teardownInProgress = false
    private var teardownCallback: (() -> Unit)? = null
    private var onSessionFinished: (() -> Unit)? = null
    private var operationStage = OperationStage.NONE
    private var lastPublishedState = NearbySoundState()

    private val operationTimeout = Runnable {
        synchronized(this) {
            if (!sessionActive || closingGatt) return@synchronized
            if (teardownInProgress) {
                finalizeTeardown()
                return@synchronized
            }

            when (operationStage) {
                OperationStage.CONNECTING,
                OperationStage.DISCOVERING_SERVICES,
                OperationStage.PROTOCOL_FALLBACK,
                OperationStage.DESCRIPTOR_WRITE -> finish(
                    NearbySoundStatus.FAILED,
                    "Timed out connecting to the nearby AirPods. Keep the case close and try again."
                )

                OperationStage.START_WRITE -> finish(
                    NearbySoundStatus.FAILED,
                    "Timed out sending the nearby sound request. Keep the AirPods close and try again."
                )

                OperationStage.START_RESPONSE -> {
                    // The DULT START write has already completed at the GATT transport layer. If
                    // its protocol response never arrives, do not overlap it with a fallback START;
                    // teardown will best-effort serialize STOP before closing instead.
                    waitingForDultResponse = null
                    finish(
                        NearbySoundStatus.FAILED,
                        "Timed out waiting for the tracker to accept the nearby sound request."
                    )
                }

                OperationStage.STOP_WRITE,
                OperationStage.STOP_RESPONSE -> finish(
                    NearbySoundStatus.FAILED,
                    "The sound played, but the stop request timed out.",
                    stopAlreadyHandled = true
                )

                OperationStage.NONE -> Unit
            }
        }
    }

    private val forceCloseTimeout = Runnable {
        synchronized(this) {
            if (teardownInProgress) finalizeTeardown()
        }
    }

    private val automaticStop: Runnable = Runnable {
        synchronized(this) {
            if (sessionActive && !teardownInProgress && soundStarted && !stopRequested) stopSound()
        }
    }

    fun beginFinding() = synchronized(this) {
        cancelSession(clearTarget = true, notifySessionFinished = false)
        publish(
            NearbySoundStatus.SEARCHING,
            message = "Waiting for a verified nearby AirPods advertisement…"
        )
    }

    fun endFinding() = synchronized(this) {
        cancelSession(clearTarget = true, notifySessionFinished = false)
        publish(
            NearbySoundStatus.SEARCHING,
            message = "Start finding to detect a nearby sound-capable AirPods advertisement."
        )
    }

    fun updateTarget(device: BluetoothDevice, advertisementConnectable: Boolean) = synchronized(this) {
        latestDevice = device
        // Do not let the next advertisement immediately hide a useful failure message from
        // the user. Apple advertisements can rotate/toggle addresses, so address equality is
        // not a reliable session boundary. Expiry or restarting the finder returns to SEARCHING.
        if (!sessionActive && !teardownInProgress &&
            (lastPublishedState.status == NearbySoundStatus.SEARCHING ||
                lastPublishedState.status == NearbySoundStatus.READY)
        ) {
            publish(
                NearbySoundStatus.READY,
                message = if (advertisementConnectable) {
                    "AirPods detected. Sound works only while they expose a separated Find My service."
                } else {
                    "AirPods detected. Android reports a non-connectable advertisement, but a nearby sound connection can still be attempted."
                }
            )
        }
    }

    fun expireTarget(force: Boolean = false): Unit = synchronized(this) {
        if (sessionActive || teardownInProgress || latestDevice == null) return@synchronized
        if (!force &&
            (lastPublishedState.status == NearbySoundStatus.FAILED ||
                lastPublishedState.status == NearbySoundStatus.COMPLETED)
        ) return@synchronized
        latestDevice = null
        publish(
            NearbySoundStatus.SEARCHING,
            message = "The AirPods signal is stale. Move closer and wait for a fresh advertisement."
        )
    }

    @SuppressLint("MissingPermission")
    fun play(onFinished: () -> Unit): Boolean = synchronized(this) {
        if (sessionActive || teardownInProgress || activeGatt != null) return false
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            publish(NearbySoundStatus.FAILED, message = "Nearby devices permission is required.")
            return false
        }
        val target = latestDevice ?: run {
            publish(
                NearbySoundStatus.FAILED,
                message = "No current AirPods advertisement. Start finding and wait for an RSSI reading."
            )
            return false
        }

        sessionActive = true
        closingGatt = false
        teardownInProgress = false
        teardownCallback = null
        onSessionFinished = onFinished
        gattConnected = false
        candidates = emptyList()
        candidateIndex = -1
        activeSpec = null
        activeCharacteristic = null
        inFlightWrite = null
        lastWriteKind = null
        waitingForDultResponse = null
        waitingForDultSoundCompleted = false
        bufferedDultResponseKind = null
        bufferedDultResponseStatus = null
        bufferedDultSoundCompleted = false
        startMayHaveReachedAccessory = false
        soundStarted = false
        stopRequested = false
        publish(NearbySoundStatus.CONNECTING, message = "Connecting to the nearby AirPods…")
        armOperationTimeout(OperationStage.CONNECTING, CONNECTION_TIMEOUT_MS)

        return try {
            activeGatt = target.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler
            )
            if (activeGatt == null) {
                finish(NearbySoundStatus.FAILED, "Android could not open a Bluetooth LE connection.")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start nearby sound GATT session", t)
            finish(NearbySoundStatus.FAILED, "Could not connect: ${t.message ?: "Bluetooth error"}.")
            false
        }
    }

    fun stopSound(): Unit = synchronized(this) {
        if (!sessionActive || teardownInProgress || stopRequested) return

        val spec = activeSpec
        val gatt = activeGatt
        val characteristic = activeCharacteristic

        if (spec == null || gatt == null || characteristic == null) {
            finish(NearbySoundStatus.READY, "Sound request cancelled.")
            return
        }

        stopRequested = true
        handler.removeCallbacks(automaticStop)
        publish(
            NearbySoundStatus.STOPPING,
            protocol = spec.protocol,
            message = "Stopping nearby sound…"
        )

        val pendingWrite = inFlightWrite
        if (pendingWrite != null) {
            // A descriptor or START write is still owned by Android. Do not enqueue STOP until
            // its callback releases the one-write-at-a-time GATT slot.
            return
        }

        if (!startMayHaveReachedAccessory && !soundStarted) {
            finish(NearbySoundStatus.READY, "Sound request cancelled.")
            return
        }

        requestStop(gatt, characteristic, spec, forTeardown = false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(this@NearbyTrackerSoundPlayer) {
                if (closingGatt || gatt !== activeGatt) return

                if (status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {
                    gattConnected = true
                    if (teardownInProgress) {
                        continueTeardown()
                        return
                    }
                    publish(NearbySoundStatus.CONNECTING, message = "Checking nearby sound services…")
                    armOperationTimeout(OperationStage.DISCOVERING_SERVICES, GATT_STAGE_TIMEOUT_MS)
                    if (!gatt.discoverServices()) {
                        finish(NearbySoundStatus.FAILED, "Could not inspect the AirPods sound service.")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gattConnected = false
                    if (teardownInProgress) {
                        finalizeTeardown()
                    } else {
                        finish(
                            NearbySoundStatus.FAILED,
                            "The AirPods disconnected before accepting the sound request."
                        )
                    }
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    gattConnected = false
                    if (teardownInProgress) {
                        finalizeTeardown()
                    } else {
                        finish(
                            NearbySoundStatus.FAILED,
                            "Bluetooth connection failed (GATT $status). Keep the AirPods close and retry."
                        )
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(this@NearbyTrackerSoundPlayer) {
                if (closingGatt || gatt !== activeGatt || !sessionActive) return
                if (teardownInProgress) {
                    finalizeTeardown()
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(NearbySoundStatus.FAILED, "AirPods service discovery failed (GATT $status).")
                    return
                }
                candidates = NearbySoundProtocolSpecs.candidates(gatt.services.map { it.uuid })
                candidateIndex = -1
                if (candidates.isEmpty()) {
                    finish(
                        NearbySoundStatus.FAILED,
                        "No nearby sound service was exposed. Close the case, move away briefly, then scan again."
                    )
                    return
                }
                tryNextProtocol(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            synchronized(this@NearbyTrackerSoundPlayer) {
                if (closingGatt || gatt !== activeGatt || !sessionActive) return
                val pending = inFlightWrite ?: return
                if (pending.type != GattWriteType.DESCRIPTOR || pending.descriptor !== descriptor) return

                inFlightWrite = null
                clearOperationTimeout()

                if (teardownInProgress) {
                    // START has not been sent yet; cancellation after a CCCD callback can close
                    // without manufacturing a START solely to issue a STOP afterward.
                    continueTeardown()
                    return
                }

                if (stopRequested) {
                    finish(NearbySoundStatus.READY, "Sound request cancelled.")
                    return
                }

                val characteristic = activeCharacteristic ?: run {
                    finish(NearbySoundStatus.FAILED, "Lost the Bluetooth sound connection.")
                    return
                }
                val commandValue = pending.commandValue ?: run {
                    finish(NearbySoundStatus.FAILED, "Lost the pending Bluetooth sound command.")
                    return
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "CCCD write failed with $status; trying the command without indications")
                }
                writeCommand(gatt, characteristic, pending.kind, commandValue)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(this@NearbyTrackerSoundPlayer) {
                if (closingGatt || gatt !== activeGatt || !sessionActive) return
                val pending = inFlightWrite ?: return
                if (pending.type != GattWriteType.CHARACTERISTIC || pending.characteristic !== characteristic) return

                inFlightWrite = null
                clearOperationTimeout()
                lastWriteKind = pending.kind
                val spec = activeSpec ?: run {
                    if (teardownInProgress) finalizeTeardown()
                    else finish(NearbySoundStatus.FAILED, "Lost the Bluetooth sound connection.")
                    return
                }

                if (teardownInProgress) {
                    handleTeardownCharacteristicWrite(gatt, characteristic, spec, pending.kind, status)
                    return
                }

                if (pending.kind == WriteKind.START) {
                    handleStartWriteComplete(gatt, characteristic, spec, status)
                } else {
                    handleStopWriteComplete(gatt, characteristic, spec, status)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            synchronized(this@NearbyTrackerSoundPlayer) {
                if (closingGatt || gatt !== activeGatt || !sessionActive ||
                    characteristic !== activeCharacteristic
                ) {
                    return
                }
                val spec = activeSpec ?: return
                if (spec.protocol != NearbySoundProtocol.DULT || value.size < 2) return

                when {
                    isDultOpcode(value, DULT_COMMAND_RESPONSE) -> {
                        if (value.size < DULT_COMMAND_RESPONSE_SIZE) return
                        val inFlightKind = inFlightWrite
                            ?.takeIf { it.type == GattWriteType.CHARACTERISTIC }
                            ?.kind
                        val expectedKind = waitingForDultResponse ?: inFlightKind ?: return
                        val expectedOpcode = if (expectedKind == WriteKind.START) {
                            spec.startOpcode
                        } else {
                            spec.stopOpcode
                        }

                        // Command_Response is only relevant to the command currently awaiting a
                        // protocol response (or its still-in-flight transport write). Delayed START
                        // responses while stopping, delayed STOP responses after completion, and
                        // responses for other opcodes are ignored.
                        if (!dultResponseMatchesCommand(value, expectedOpcode)) return

                        if (teardownInProgress) return
                        val responseStatus = dultResponseStatus(value)
                        if (waitingForDultResponse == null) {
                            // Android normally delivers the write callback before a resulting
                            // indication, but buffer an exact matching response if callback delivery
                            // is reversed. It is consumed only after transport success.
                            bufferedDultResponseKind = expectedKind
                            bufferedDultResponseStatus = responseStatus
                            return
                        }
                        handleDultResponse(gatt, characteristic, spec, expectedKind, responseStatus)
                    }

                    isDultOpcode(value, DULT_SOUND_COMPLETED) -> {
                        if (teardownInProgress) return
                        if (!waitingForDultSoundCompleted) {
                            val pendingStop = inFlightWrite?.let {
                                it.type == GattWriteType.CHARACTERISTIC && it.kind == WriteKind.STOP
                            } == true
                            if (pendingStop) bufferedDultSoundCompleted = true
                            return
                        }

                        completeDultStop(spec)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleStartWriteComplete(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        spec: NearbySoundProtocolSpec,
        status: Int
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (stopRequested) {
                // Android accepted the START write call earlier, so the accessory may still have
                // observed it even though the callback failed. Serialize a best-effort STOP now
                // that the write slot is free.
                requestStop(gatt, characteristic, spec, forTeardown = false)
            } else {
                startMayHaveReachedAccessory = false
                tryNextProtocol(gatt)
            }
            return
        }

        if (spec.protocol == NearbySoundProtocol.DULT) {
            if (stopRequested) {
                // STOP was requested while START owned the serialized write slot. The START
                // transport operation is now complete, so enqueue STOP without ever declaring
                // PLAYING; any delayed START Command_Response is unrelated to the STOP wait.
                requestStop(gatt, characteristic, spec, forTeardown = false)
            } else {
                waitingForDultResponse = WriteKind.START
                armOperationTimeout(OperationStage.START_RESPONSE, DULT_RESPONSE_TIMEOUT_MS)
                consumeBufferedDultResponse(gatt, characteristic, spec, WriteKind.START)
            }
            return
        }

        // fd44 keeps the existing pragmatic behavior: the characteristic write callback is the
        // command acknowledgement. STOP is still serialized and cannot overlap this START write.
        soundStarted = true
        if (stopRequested) {
            requestStop(gatt, characteristic, spec, forTeardown = false)
        } else {
            publishPlaying(spec)
        }
    }

    private fun handleStopWriteComplete(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        spec: NearbySoundProtocolSpec,
        status: Int
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            finish(
                NearbySoundStatus.FAILED,
                "The sound played, but the stop request failed (GATT $status).",
                spec.protocol,
                stopAlreadyHandled = true
            )
            return
        }

        if (spec.protocol == NearbySoundProtocol.DULT) {
            // GATT success is transport-only for DULT. A successful STOP response is not itself
            // completion; completion requires Sound_Completed (0x0303).
            waitingForDultResponse = WriteKind.STOP
            waitingForDultSoundCompleted = true
            armOperationTimeout(OperationStage.STOP_RESPONSE, DULT_RESPONSE_TIMEOUT_MS)
            consumeBufferedDultResponse(gatt, characteristic, spec, WriteKind.STOP)
            if (sessionActive && !teardownInProgress && bufferedDultSoundCompleted) {
                bufferedDultSoundCompleted = false
                completeDultStop(spec)
            }
            return
        }

        startMayHaveReachedAccessory = false
        soundStarted = false
        finish(
            NearbySoundStatus.COMPLETED,
            "Nearby sound request completed.",
            spec.protocol,
            stopAlreadyHandled = true
        )
    }

    @SuppressLint("MissingPermission")
    private fun handleDultResponse(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        spec: NearbySoundProtocolSpec,
        kind: WriteKind,
        responseStatus: Int
    ) {
        if (kind == WriteKind.START) {
            waitingForDultResponse = null
            clearOperationTimeout()

            if (responseStatus != DULT_RESPONSE_SUCCESS) {
                startMayHaveReachedAccessory = false
                soundStarted = false
                if (stopRequested) {
                    finish(NearbySoundStatus.READY, "Sound request cancelled.")
                } else {
                    tryNextProtocol(gatt)
                }
                return
            }

            // DULT is PLAYING only after a 0x0302 response whose CommandOpCode is exactly
            // Sound_Start (0x0300) and whose ResponseStatus is Success.
            soundStarted = true
            if (stopRequested) {
                requestStop(gatt, characteristic, spec, forTeardown = false)
            } else {
                publishPlaying(spec)
            }
            return
        }

        if (responseStatus != DULT_RESPONSE_SUCCESS) {
            waitingForDultResponse = null
            waitingForDultSoundCompleted = false
            clearOperationTimeout()
            finish(
                NearbySoundStatus.FAILED,
                "The sound played, but the tracker rejected the stop request (status $responseStatus).",
                spec.protocol,
                stopAlreadyHandled = true
            )
            return
        }

        // A successful matching STOP Command_Response only confirms command acceptance. Keep
        // waiting for the required Sound_Completed (0x0303) indication.
        waitingForDultResponse = null
        armOperationTimeout(OperationStage.STOP_RESPONSE, DULT_RESPONSE_TIMEOUT_MS)
    }

    private fun consumeBufferedDultResponse(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        spec: NearbySoundProtocolSpec,
        kind: WriteKind
    ) {
        if (bufferedDultResponseKind != kind) return
        val responseStatus = bufferedDultResponseStatus ?: return
        bufferedDultResponseKind = null
        bufferedDultResponseStatus = null
        handleDultResponse(gatt, characteristic, spec, kind, responseStatus)
    }

    private fun completeDultStop(spec: NearbySoundProtocolSpec) {
        waitingForDultSoundCompleted = false
        waitingForDultResponse = null
        bufferedDultResponseKind = null
        bufferedDultResponseStatus = null
        bufferedDultSoundCompleted = false
        startMayHaveReachedAccessory = false
        soundStarted = false
        clearOperationTimeout()
        finish(
            NearbySoundStatus.COMPLETED,
            "Nearby sound request completed.",
            spec.protocol,
            stopAlreadyHandled = true
        )
    }

    private fun publishPlaying(spec: NearbySoundProtocolSpec) {
        clearOperationTimeout()
        publish(
            NearbySoundStatus.PLAYING,
            protocol = spec.protocol,
            message = "Sound requested. Listen near the case and earbuds."
        )
        handler.removeCallbacks(automaticStop)
        handler.postDelayed(automaticStop, SOUND_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun tryNextProtocol(gatt: BluetoothGatt) {
        if (inFlightWrite != null) {
            finish(
                NearbySoundStatus.FAILED,
                "Bluetooth sound sequencing failed while switching protocols."
            )
            return
        }

        handler.removeCallbacks(automaticStop)
        waitingForDultResponse = null
        waitingForDultSoundCompleted = false
        bufferedDultResponseKind = null
        bufferedDultResponseStatus = null
        bufferedDultSoundCompleted = false
        lastWriteKind = null
        startMayHaveReachedAccessory = false
        soundStarted = false
        activeCharacteristic = null
        activeSpec = null
        armOperationTimeout(OperationStage.PROTOCOL_FALLBACK, GATT_STAGE_TIMEOUT_MS)

        while (++candidateIndex < candidates.size) {
            val spec = candidates[candidateIndex]
            val service = gatt.services.firstOrNull { spec.matchesService(it.uuid) } ?: continue
            val characteristic = service.getCharacteristic(spec.characteristicUuid) ?: continue
            activeSpec = spec
            activeCharacteristic = characteristic
            publish(
                NearbySoundStatus.CONNECTING,
                protocol = spec.protocol,
                message = "Requesting sound through ${spec.protocol.label}…"
            )
            enableIndicationsAndWrite(gatt, characteristic, WriteKind.START, spec.startOpcode)
            return
        }

        finish(
            NearbySoundStatus.FAILED,
            "The detected AirPods did not accept a nearby sound command. They may not be separated from their owner."
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableIndicationsAndWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        kind: WriteKind,
        value: ByteArray
    ) {
        val notificationsEnabled = gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (notificationsEnabled && cccd != null) {
            val cccdValue = if (
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            ) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (writeDescriptorSerialized(gatt, cccd, characteristic, kind, value, cccdValue)) return
        }
        writeCommand(gatt, characteristic, kind, value)
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorSerialized(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        characteristic: BluetoothGattCharacteristic,
        kind: WriteKind,
        commandValue: ByteArray,
        descriptorValue: ByteArray
    ): Boolean {
        if (inFlightWrite != null) {
            finish(NearbySoundStatus.FAILED, "Bluetooth sound sequencing attempted overlapping GATT writes.")
            return true
        }

        inFlightWrite = InFlightGattWrite(
            type = GattWriteType.DESCRIPTOR,
            kind = kind,
            characteristic = characteristic,
            descriptor = descriptor,
            commandValue = commandValue
        )
        val result = runCatching { gatt.writeDescriptor(descriptor, descriptorValue) }
            .getOrElse {
                Log.w(TAG, "Unable to queue CCCD write", it)
                WRITE_QUEUE_ERROR
            }

        if (result == BluetoothStatusCodes.SUCCESS) {
            armOperationTimeout(OperationStage.DESCRIPTOR_WRITE, GATT_STAGE_TIMEOUT_MS)
            return true
        }

        inFlightWrite = null
        clearOperationTimeout()
        Log.w(TAG, "Unable to queue CCCD write ($result); writing command directly")
        return false
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        kind: WriteKind,
        value: ByteArray
    ) {
        if (inFlightWrite != null) {
            finish(NearbySoundStatus.FAILED, "Bluetooth sound sequencing attempted overlapping GATT writes.")
            return
        }

        inFlightWrite = InFlightGattWrite(
            type = GattWriteType.CHARACTERISTIC,
            kind = kind,
            characteristic = characteristic
        )
        lastWriteKind = kind

        val result = runCatching {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        }.getOrElse {
            Log.w(TAG, "Unable to queue $kind characteristic write", it)
            WRITE_QUEUE_ERROR
        }

        if (result == BluetoothStatusCodes.SUCCESS) {
            if (kind == WriteKind.START) startMayHaveReachedAccessory = true
            armOperationTimeout(
                if (kind == WriteKind.START) OperationStage.START_WRITE else OperationStage.STOP_WRITE,
                GATT_STAGE_TIMEOUT_MS
            )
            return
        }

        inFlightWrite = null
        clearOperationTimeout()

        if (teardownInProgress) {
            finalizeTeardown()
        } else if (kind == WriteKind.START) {
            startMayHaveReachedAccessory = false
            if (stopRequested) {
                finish(NearbySoundStatus.READY, "Sound request cancelled.")
            } else {
                tryNextProtocol(gatt)
            }
        } else {
            finish(
                NearbySoundStatus.FAILED,
                "Android rejected the Bluetooth stop command ($result).",
                stopAlreadyHandled = true
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestStop(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        spec: NearbySoundProtocolSpec,
        forTeardown: Boolean
    ) {
        handler.removeCallbacks(automaticStop)
        if (!forTeardown && !stopRequested) stopRequested = true
        waitingForDultResponse = null
        waitingForDultSoundCompleted = false
        bufferedDultResponseKind = null
        bufferedDultResponseStatus = null
        bufferedDultSoundCompleted = false
        writeCommand(gatt, characteristic, WriteKind.STOP, spec.stopOpcode)
    }

    private fun isDultOpcode(value: ByteArray, opcode: Int): Boolean {
        if (value.size < 2) return false
        return (value[0].toInt() and 0xff) == (opcode and 0xff) &&
            (value[1].toInt() and 0xff) == ((opcode ushr 8) and 0xff)
    }

    private fun dultResponseMatchesCommand(value: ByteArray, commandOpcode: ByteArray): Boolean {
        if (value.size < DULT_COMMAND_RESPONSE_SIZE || commandOpcode.size < 2) return false
        return value[2] == commandOpcode[0] && value[3] == commandOpcode[1]
    }

    private fun dultResponseStatus(value: ByteArray): Int =
        (value[4].toInt() and 0xff) or ((value[5].toInt() and 0xff) shl 8)

    private fun armOperationTimeout(stage: OperationStage, delayMs: Long) {
        handler.removeCallbacks(operationTimeout)
        operationStage = stage
        handler.postDelayed(operationTimeout, delayMs)
    }

    private fun clearOperationTimeout() {
        handler.removeCallbacks(operationTimeout)
        operationStage = OperationStage.NONE
    }

    @SuppressLint("MissingPermission")
    private fun finish(
        status: NearbySoundStatus,
        message: String,
        protocol: NearbySoundProtocol? = activeSpec?.protocol,
        stopAlreadyHandled: Boolean = false
    ) {
        if (!sessionActive || teardownInProgress) return

        handler.removeCallbacks(automaticStop)
        clearOperationTimeout()

        val shouldStopBeforeClose = !stopAlreadyHandled &&
            gattConnected &&
            startMayHaveReachedAccessory &&
            activeGatt != null &&
            activeCharacteristic != null &&
            activeSpec != null

        if (shouldStopBeforeClose) {
            // Publish the terminal state now so FAILED remains sticky while teardown performs its
            // bounded best-effort STOP. No teardown callback publishes another state afterward.
            publish(status, protocol, message)
            val callback = onSessionFinished
            onSessionFinished = null
            beginTeardown(callback)
            return
        }

        val callback = onSessionFinished
        onSessionFinished = null
        closeGattNow()
        publish(status, protocol, message)
        callback?.let { handler.post(it) }
    }

    @SuppressLint("MissingPermission")
    private fun cancelSession(clearTarget: Boolean, notifySessionFinished: Boolean) {
        handler.removeCallbacks(automaticStop)
        clearOperationTimeout()
        if (clearTarget) latestDevice = null

        if (teardownInProgress) {
            if (!notifySessionFinished) teardownCallback = null
            onSessionFinished = null
            return
        }

        val callback = if (notifySessionFinished) onSessionFinished else null
        onSessionFinished = null

        if (!sessionActive && activeGatt == null) {
            resetSessionState()
            callback?.let { handler.post(it) }
            return
        }

        if (gattConnected && startMayHaveReachedAccessory &&
            activeGatt != null && activeCharacteristic != null && activeSpec != null
        ) {
            beginTeardown(callback)
        } else if (inFlightWrite?.type == GattWriteType.CHARACTERISTIC &&
            inFlightWrite?.kind == WriteKind.START
        ) {
            // writeCharacteristic returned SUCCESS, so START may have escaped even though the
            // callback is still pending. Wait for that callback and serialize STOP afterward.
            beginTeardown(callback)
        } else {
            closeGattNow()
            callback?.let { handler.post(it) }
        }
    }

    private fun beginTeardown(callback: (() -> Unit)?) {
        if (teardownInProgress) {
            teardownCallback = callback
            return
        }

        teardownInProgress = true
        teardownCallback = callback
        handler.removeCallbacks(automaticStop)
        clearOperationTimeout()
        handler.removeCallbacks(forceCloseTimeout)
        handler.postDelayed(forceCloseTimeout, FORCE_CLOSE_TIMEOUT_MS)
        continueTeardown()
    }

    @SuppressLint("MissingPermission")
    private fun continueTeardown() {
        if (!teardownInProgress) return
        val gatt = activeGatt
        if (gatt == null || !gattConnected) {
            finalizeTeardown()
            return
        }

        // Never add a descriptor/characteristic write while Android still owns one.
        if (inFlightWrite != null) return

        val characteristic = activeCharacteristic
        val spec = activeSpec

        // If a serialized STOP already reached the transport and DULT is only waiting for its
        // protocol response/completion indication, teardown has already met its best-effort STOP
        // obligation. Do not manufacture a duplicate STOP.
        if (waitingForDultResponse == WriteKind.STOP || waitingForDultSoundCompleted) {
            finalizeTeardown()
            return
        }

        if (startMayHaveReachedAccessory && characteristic != null && spec != null) {
            // A cancellation that arrived while START was in flight was deferred until the START
            // write callback released the single GATT write slot. If DULT had begun waiting for a
            // START Command_Response, STOP now supersedes that wait; a delayed START response will
            // be ignored because the expected opcode changes to Sound_Stop.
            waitingForDultResponse = null
            requestStop(gatt, characteristic, spec, forTeardown = true)
            return
        }

        finalizeTeardown()
    }

    @SuppressLint("MissingPermission")
    private fun handleTeardownCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        spec: NearbySoundProtocolSpec,
        kind: WriteKind,
        status: Int
    ) {
        if (kind == WriteKind.STOP) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Best-effort teardown STOP failed with GATT $status")
            }
            // Teardown only promises a serialized best-effort STOP before close. Normal DULT STOP
            // completion still requires Sound_Completed; teardown is bounded and closes after the
            // STOP transport callback instead of extending the lifetime indefinitely.
            finalizeTeardown()
            return
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "START write callback failed during teardown with GATT $status")
        }
        // writeCharacteristic returned SUCCESS before this callback, so START may have escaped
        // regardless of callback status. The write slot is now free: make exactly one serialized
        // STOP attempt before closing. DULT does not need to reach PLAYING during teardown.
        waitingForDultResponse = null
        startMayHaveReachedAccessory = true
        requestStop(gatt, characteristic, spec, forTeardown = true)
    }

    @SuppressLint("MissingPermission")
    private fun finalizeTeardown() {
        if (!teardownInProgress) return
        handler.removeCallbacks(forceCloseTimeout)
        clearOperationTimeout()
        val callback = teardownCallback
        teardownCallback = null
        closeGattNow()
        callback?.let { handler.post(it) }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattNow() {
        handler.removeCallbacks(operationTimeout)
        handler.removeCallbacks(automaticStop)
        handler.removeCallbacks(forceCloseTimeout)
        operationStage = OperationStage.NONE
        closingGatt = true
        activeGatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        activeGatt = null
        gattConnected = false
        sessionActive = false
        teardownInProgress = false
        resetSessionState()
    }

    private fun resetSessionState() {
        candidates = emptyList()
        candidateIndex = -1
        activeCharacteristic = null
        activeSpec = null
        inFlightWrite = null
        lastWriteKind = null
        waitingForDultResponse = null
        waitingForDultSoundCompleted = false
        bufferedDultResponseKind = null
        bufferedDultResponseStatus = null
        bufferedDultSoundCompleted = false
        startMayHaveReachedAccessory = false
        soundStarted = false
        stopRequested = false
        operationStage = OperationStage.NONE
    }

    private fun publish(
        status: NearbySoundStatus,
        protocol: NearbySoundProtocol? = null,
        message: String
    ) {
        val state = NearbySoundState(status, protocol, message)
        lastPublishedState = state
        val protocolName = protocol?.name ?: "none"
        Log.i(TAG, "state=$status protocol=$protocolName message=$message")
        onStateChanged(state)
    }

    private companion object {
        const val TAG = "NearbyAirPodsSound"
        const val CONNECTION_TIMEOUT_MS = 12_000L
        const val GATT_STAGE_TIMEOUT_MS = 5_000L
        const val DULT_RESPONSE_TIMEOUT_MS = 5_000L
        const val FORCE_CLOSE_TIMEOUT_MS = 4_000L
        const val SOUND_DURATION_MS = 8_000L

        const val DULT_COMMAND_RESPONSE = 0x0302
        const val DULT_SOUND_COMPLETED = 0x0303
        const val DULT_COMMAND_RESPONSE_SIZE = 6
        const val DULT_RESPONSE_SUCCESS = 0x0000
        const val WRITE_QUEUE_ERROR = -1

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
