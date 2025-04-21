package com.platformfactory.pesoftpos

import com.payengine.devicepaymentsdk.PEPaymentDevice
import com.payengine.devicepaymentsdk.interfaces.PEDevice
import com.payengine.devicepaymentsdk.interfaces.PEDeviceDelegate
import com.payengine.devicepaymentsdk.interfaces.PEInitializationDelegate
import com.payengine.devicepaymentsdk.interfaces.PETransactionResultFragment
import com.payengine.devicepaymentsdk.models.DiscoverableDevice
import com.payengine.devicepaymentsdk.models.TerminalInfo
import com.payengine.devicepaymentsdk.models.enums.TransactionMode
import com.payengine.shared.models.transaction.PEPaymentRequest
import com.payengine.shared.models.transaction.PEPaymentResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A thin suspend‑style shim around PayEngine's Android SDK.
 * Mirrors the Swift `PETapToPayShim`, including activation checks.
 */
sealed class PETapError : Throwable() {
    data class InitializationFailed(val error: Throwable) : PETapError()
    data class ConnectionFailed(val device: PEDevice, val error: Throwable) : PETapError()
    data class TransactionFailed(val result: PEPaymentResult) : PETapError()
    object NoAvailableDevice : PETapError()
    data class ActivationRequired(val code: String) : PETapError()
}

object PESoftPOSShim {
    private val logger: Logger = Logger.getLogger(PESoftPOSShim::class.java.name)

    fun logMethodCall(vararg params: Any?) {
        val stackTrace = Thread.currentThread().stackTrace
        // Index 3 usually refers to the caller of this function
        val callerElement = stackTrace.getOrNull(3)

        val methodName = callerElement?.methodName ?: "Unknown Method"
        val className = callerElement?.className?.substringAfterLast('.') ?: "Unknown Class"

        val paramStr = params.joinToString(", ") { it?.toString() ?: "null" }

        logger.info("Called $className.$methodName with parameters: $paramStr")
    }

    // Hold onto our one InitDel and DeviceDel
    private var initDel: InitDel? = null
    private var deviceDel: DeviceDel? = null

    private val sdk: PEPaymentDevice = PEPaymentDevice.shared

    /**
     * If the SDK just signalled `onActivationRequired`, this returns that code.
     */
    suspend fun getActivationCode(): String = suspendCancellableCoroutine { cont ->
        logMethodCall()
        initDel = InitDel(activationCheckCont = null, activationCodeCont = cont)
        sdk.initialize(mode = TransactionMode.DEVICE, delegate = initDel!!)
        cont.invokeOnCancellation { initDel = null }
    }

    /**
     * Returns `true` if initialization completed cleanly, `false` if activation is required.
     */
    suspend fun isActivated(): Boolean = suspendCancellableCoroutine { cont ->
        logMethodCall()
        initDel = InitDel(activationCheckCont = cont, activationCodeCont = null)
        sdk.initialize(mode = TransactionMode.DEVICE, delegate = initDel!!)
        cont.invokeOnCancellation { initDel = null }
    }

    /**
     * Initialize + (optional) auto‑connect. Throws ActivationRequired if needed.
     * @return the connected PEDevice
     */
    suspend fun initializeDevice(mode: TransactionMode = TransactionMode.DEVICE): PEDevice = suspendCancellableCoroutine { cont ->
        logMethodCall()
        deviceDel = DeviceDel(contConnect = cont)
        sdk.connect(deviceDel!!)
        cont.invokeOnCancellation {  }
    }

    fun deinitialize() {
        logMethodCall()
        sdk.deinitilize()
    }

    /**
     * Start a payment on the *shared* device and await its result.
     */
    suspend fun startTransaction(request: PEPaymentRequest): PEPaymentResult = suspendCancellableCoroutine { cont ->
        logMethodCall(request)
        // stash the txn continuation on our existing DeviceDel
        deviceDel?.txnCont?.cancel()
        deviceDel?.txnCont = cont
        sdk.startTransaction(request, transactionResultFragment = object: PETransactionResultFragment {
            override fun onDismissed() {
                logger.info("onDismissed")
            }
        })
        cont.invokeOnCancellation { deviceDel?.txnCont = null }
    }

    // — Internal Delegate for initialization & connect —
    private class InitDel(
        private var cont: CancellableContinuation<PEDevice>? = null,
        private var activationCheckCont: CancellableContinuation<Boolean>? = null,
        private var activationCodeCont: CancellableContinuation<String>? = null
    ) : PEInitializationDelegate {
        override fun willLaunchEducationalScreen() {}
        override fun didLaunchEducationalScreen() {}
        override fun onEducationScreenDismissed() {}
        override fun onDeinitialized() {
            logMethodCall()
        }

        override fun onActivationRequired(activationCode: String) {
            logMethodCall(activationCode)
            // signal activation required
            activationCheckCont?.resume(false)
            activationCodeCont?.resume(activationCode)
            cleanup()
        }

        override fun onInitFailed(error: Error) {
            logMethodCall(error)
            cont?.resumeWithException(PETapError.InitializationFailed(error))
            cleanup()
        }

        override fun onInitialized(availableDevices: List<PEDevice>) {
            logMethodCall(availableDevices)

            activationCheckCont?.let {
                it.resume(true)
                cont = null
                activationCheckCont = null
                activationCodeCont = null

            }

            if (availableDevices.isEmpty()) {
                cont?.resumeWithException(PETapError.NoAvailableDevice)
                cleanup()
                return
            }
        }

        override fun onActivationStarting(terminalInfo: TerminalInfo) {
            logMethodCall(terminalInfo)
        }
    }

    // — Internal Delegate for connection & transaction events —
    private class DeviceDel(
        private val contConnect: CancellableContinuation<PEDevice>?
    ) : PEDeviceDelegate {
        // will be set when startTransaction() is called
        var txnCont: CancellableContinuation<PEPaymentResult>? = null

        override fun onConnected(device: PEDevice) {
            logMethodCall()
            contConnect?.resume(device)
        }

        override fun onConnectionFailed(device: PEDevice, error: Error) {
            logMethodCall(device, error)
            contConnect?.resumeWithException(PETapError.ConnectionFailed(device, error))
        }

        override fun onTransactionCompleted(transaction: PEPaymentResult) {
            logMethodCall(transaction)
            txnCont?.resume(transaction)
            txnCont = null
        }

        override fun onTransactionFailed(transaction: PEPaymentResult) {
            logMethodCall(transaction)
            logger.info("TxnCont is null ${txnCont == null}")
            txnCont?.resumeWithException(PETapError.TransactionFailed(transaction))
            txnCont = null
        }

        // stubs for required callbacks we don't forward:
        override fun onDeviceDiscovered(device: DiscoverableDevice) {
            logMethodCall()
        }
        override fun onDiscoveringDevice(searching: Boolean) {
            logMethodCall()
        }
        override fun onLcdConfirmation(message: String) {
            logMethodCall()
        }
        override fun onLcdMessage(message: String) {
            logMethodCall()
        }
        override fun didStartAuthorization() {
            logMethodCall()
        }

        override fun onActivationProgress(device: PEDevice, completed: Int) {
//            logMethodCall(completed)
        }

        override fun onCardRead(success: Boolean) {
            logMethodCall()
        }
    }

    private fun cleanup() {
        logMethodCall()
        initDel = null
    }
}