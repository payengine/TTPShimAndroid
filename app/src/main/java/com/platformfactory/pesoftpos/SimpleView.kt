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

@Composable
fun SimpleView(modifier: Modifier = Modifier) {
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

            // Override card read error message
            override fun cardReaderMessageMapper(code: Int, message: String): String {
                return when (code) {
                    PEError.CardError.CODE_EMV_KERNEL_NOT_AVAILABLE -> "No compatible EMV kernel found."
                    PEError.CardError.CODE_EMV_NFC_PERMISSION_MISS -> "NFC permission is missing. Please enable it in your settings."
                    PEError.CardError.CODE_EMV_NFC_CAMERA_PERMISSION_MISS -> "Camera permission is required for this transaction."
                    PEError.CardError.CODE_EMV_NFC_NETWORK_UNCONNECTTED -> "No network connection. Please check your internet."
                    PEError.CardError.CODE_EMV_NFC_DISABLED -> "NFC is disabled. Please enable it to continue."
                    PEError.CardError.CODE_EMV_ADB_ENABLED -> "Developer mode is enabled. Please disable ADB for security."
                    PEError.CardError.CODE_EMV_SIDE_LOADED -> "App was side-loaded. Please install from the official store."
                    PEError.CardError.CODE_ERR_USERCANCEL -> "The transaction was cancelled by the user."
                    PEError.CardError.CODE_ERR_TIMEOUT -> "The transaction timed out. Please try again."
                    PEError.CardError.CODE_ERR_NOTACCEPT -> "The card issuer did not accept the transaction."
                    PEError.CardError.CODE_ERR_APPEXP -> "The card has expired."
                    PEError.CardError.CODE_ERR_BLACKLIST -> "This card is blacklisted and cannot be used."
                    PEError.CardError.CODE_ERR_TRANSEXCEEDED -> "Transaction amount exceeds the contactless limit."
                    PEError.CardError.CODE_ERR_NOAMT -> "No amount was entered for the transaction."
                    PEError.CardError.CODE_ERR_PINBLOCK -> "The PIN is blocked. Please contact your bank."
                    PEError.CardError.CODE_ERR_NOTALLOWED -> "This card is not allowed for the transaction."
                    PEError.CardError.CODE_ERR_SEE_PHONE -> "Please follow instructions on your phone screen."
                    PEError.CardError.CODE_AMEX_EMV_TRANS_ERROR -> "AMEX transaction declined."
                    PEError.CardError.CODE_DISCOVER_EMV_KERNEL_DECLINED -> "Discover transaction declined."
                    else -> "Error code $code: $message"
                }
            }

        })

        PEPaymentDevice.setContext(context)

        try {
            val isActivated = PESoftPOSShim.isActivated()
            if (!isActivated) {
                val activationCode = PESoftPOSShim.getActivationCode()
                // Use this code to activate merchant's device using backend API call
                currentStatus = "Activation code: $activationCode"
                transactionLoading = false
                return
            }

            // Step 1. Read Error. Please try again, holding your card steady near the reader
            PESoftPOSShim.initializeDevice()

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
                val result = PESoftPOSShim.startTransaction(request)
                currentStatus =  "✅ Transaction succeeded: ${result.transactionId}"
            }
        } catch (e: PETapError.TransactionFailed) {
            currentStatus =  "❌ Transaction failed: ${e.result.responseMessage ?: e.result.error?.message}"
        } catch (e: Throwable) {
            currentStatus =  "❌ PE Flow failed: $e"
        } finally {
            transactionLoading = false
            println(currentStatus)
            //PESoftPOSShim.deinitialize()
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
