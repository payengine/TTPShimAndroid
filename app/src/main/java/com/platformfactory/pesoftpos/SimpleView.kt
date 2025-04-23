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

            // Customize card read error messages
            override fun cardReaderMessageMapper(code: Int, message: String): String {
                return when (code) {
                    // NFC not available
                    PEError.CardError.CODE_EMV_NFC_PERMISSION_MISS,
                    PEError.CardError.CODE_EMV_NFC_DISABLED -> "NFC is disabled. Please enable NFC in your settings"

                    else -> "There was an error reading the card. Please try again using a different card and ensure it is held still and close to the readers"
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
                            "sales_tax" to "0.40", // Level 2 data
                            "order_number" to "ORD123455", // Level 2 data
                            "internalTransactionID" to "A1234545",
                            //"gateway_id" to "cea013fd-ac46-4e47-a2dc-a1bc3d89bf0c" // Route to specific gateway - Change it to valid gateway ID
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
