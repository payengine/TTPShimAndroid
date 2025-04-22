package com.platformfactory.pesoftpos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.payengine.devicepaymentsdk.PEPaymentDevice
import com.payengine.devicepaymentsdk.interfaces.PECustomization
import com.payengine.shared.PEError
import com.payengine.shared.flavors.PEHost
import com.payengine.shared.models.transaction.PEPaymentRequest
import kotlinx.coroutines.launch
import java.util.logging.Logger


fun mapErrorCodeToMessage(code: String, message: String): String {
    val errorCode = code.toIntOrNull() ?: return "$code: $message"

    return when (errorCode) {
        PEError.CODE_INVALID_MID -> "Invalid Merchant ID"
        PEError.CODE_DISCONNECTED -> "Device was disconnected during operation"
        PEError.CODE_VERSION_UNSUPPORTED -> "Device firmware version is not supported"
        PEError.CODE_UNSUPPORTED_DEVICE -> "Connected device is not supported by the SDK"
        PEError.CODE_USB_UNRECOGNIZED -> "USB device was not recognized by the system"
        PEError.CODE_CONNECT_TIMEOUT -> "Connection attempt timed out"
        PEError.CODE_NO_DEVICE_AVAILABLE -> "No payment device is available for connection"
        PEError.CODE_DEVICE_NOT_CONNECTED -> "No device is currently connected"
        PEError.CODE_CONNECTION_IN_PROGRESS -> "A connection attempt is already in progress"
        PEError.CODE_INITIALIZATION_FAILED -> "SDK initialization failed: $message"
        PEError.CODE_TRANSACTION_NOT_ALLOWED -> "Transaction is not allowed in the current state"
        PEError.CODE_COMPANION_MODE_ONLY -> "Transaction requires Companion mode; device-initiated is not allowed"
        PEError.CODE_TRANSACTION_IN_PROGRESS -> "Another transaction is already in progress"
        PEError.CODE_UNSUPPORTED_TRANSACTION_TYPE -> "Requested transaction type is not supported"
        PEError.CODE_TRANSACTION_FAILURE -> "General transaction processing failure"
        PEError.CODE_CONTEXT_INVALID -> "Android context is invalid or destroyed"
        PEError.CODE_INVALID_CURRENCY -> "Currency code is invalid or not supported"
        PEError.CODE_SERVER_ERROR -> "Server communication error"
        PEError.CODE_EMV_KERNEL_ERROR -> "EMV kernel processing error"
        PEError.CODE_CARD_READER_ACTIVATION_ERROR -> "Card reader activation failed"
        PEError.CODE_CARD_READER_TIMEOUT -> "Card reader timed out"
        // handle other code
        else -> "$code: $message"
    }
}

@Composable
fun SimpleView(modifier: Modifier = Modifier) {
    val logger: Logger = Logger.getLogger("ShimScreen")

    var transactionAmount by remember { mutableStateOf("") }
    var transactionLoading by remember { mutableStateOf(false) }
    var currentStatus by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun runTransaction(transactionAmount: String) {
        transactionLoading = true

        PEPaymentDevice.setHost(PEHost.Sandbox)
        PEPaymentDevice.registerCustomization(object: PECustomization {
            // Control retry
            override fun shouldRetryIfTimeout(): Boolean {
                return true
            }

            // Card read success message
            override val cardReadSuccessMessage: String
                get() = "Done"

            // Hide or show card read message
            override val hideCardReadSuccessMessage: Boolean
                get() = false

            override fun cardReaderMessageMapper(code: String, message: String): String {
                return mapErrorCodeToMessage(code, message)
            }

        })

        PEPaymentDevice.setContext(context)

        try {
            val isActivated = PETapToPayShim.isActivated()
            if (!isActivated) {
                val activationCode = PETapToPayShim.getActivationCode()
                // Use this code to activate merchant's device using backend API call
                currentStatus = "Activation code: $activationCode"
                transactionLoading = false
                return
            }

            // Step 1. Read Error. Please try again, holding your card steady near the reader
            PETapToPayShim.initializeDevice()

            // Step 2. Parse and create request
            val decimalAmount = transactionAmount.toBigDecimalOrNull()
            if (decimalAmount != null) {
                val request = PEPaymentRequest(transactionAmount = decimalAmount,
                    transactionData = mapOf(
                        "transactionMonitoringBypass" to true, // To by pass monitoring rules
                        "data" to mapOf(
                            "sales_tax" to "1.00", // Level 2 data
                            "order_number" to "XXX123455", // Level 2 data
                            "gateway_id" to "cea013fd-ac46-4e47-a2dc-a1bc3d89bf0c" // Route to specific gateway - Change it to valid gateway ID
                        )
                    ),
                    currencyCode = "USD")

                // Run transaction
                val result = PETapToPayShim.startTransaction(request)
                currentStatus =  "✅ Transaction succeeded: ${result.transactionId}"
            }
        } catch (e: PETapError.TransactionFailed) {
            var message = e.result.responseMessage ?: e.message
            if (e.result.error is PEError) {
                message = mapErrorCodeToMessage((e.result.error as PEError).code.toString(), (e.result.error as PEError).message)
            } else if (e.result.responseCode != null) {
                message = mapErrorCodeToMessage(e.result.responseCode.toString(), e.result.responseMessage.toString())
            }
            currentStatus =  "❌ Transaction failed: $message"
        } catch (e: Throwable) {
            var message = e.message
            if (e is PEError) {
                message = mapErrorCodeToMessage(e.code.toString(), e.message)
            }
            currentStatus =  "❌ PE Flow failed: $message"
        } finally {
            transactionLoading = false
            println(currentStatus)
            PETapToPayShim.deinitialize()
        }
    }


    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {

        // Title
        Row(modifier = Modifier.padding(top = 30.dp)) {
            Text("Transaction Amount:", modifier = Modifier.padding(start = 8.dp))
            Spacer(modifier = Modifier.weight(1f))
        }

        // Text Field
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = transactionAmount,
                onValueChange = { transactionAmount = it },
                label = { Text("Enter Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
        }

        // Start Transaction Button
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    coroutineScope.launch {
                        runTransaction(transactionAmount)
                    }
                },
                enabled = transactionAmount.isNotEmpty() && !transactionLoading,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Start Transaction")
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (transactionLoading) {
                CircularProgressIndicator(
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        Text(currentStatus, modifier = Modifier.padding(vertical = 20.dp))

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Preview(showBackground = true)
@Composable
fun SimpleViewPreview() {
    SimpleView()
}
