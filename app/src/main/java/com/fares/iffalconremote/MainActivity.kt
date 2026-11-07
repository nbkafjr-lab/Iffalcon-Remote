package com.fares.iffalconremote

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val operationInProgress = AtomicBoolean(false)

    private var pairingSession: AtvPairingSession? = null
    private var remoteClient: AtvRemoteClient? = null
    private var pairingServiceName: String? = null

    @Suppress("DEPRECATION")
    private fun hasWifiConnection(): Boolean {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivity.activeNetworkInfo
        return activeNetwork?.isConnected == true &&
            activeNetwork.type == ConnectivityManager.TYPE_WIFI
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val alreadyPaired = File(filesDir, "atv-tv-cert.der").exists()

        setContent {
            var paired by remember { mutableStateOf(alreadyPaired) }
            var pairingStarted by remember { mutableStateOf(false) }
            var pin by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var wifiConnected by remember { mutableStateOf(hasWifiConnection()) }
            var discoveredTv by remember { mutableStateOf<TvEndpoint?>(null) }
            var status by remember {
                mutableStateOf(if (alreadyPaired) "Ready" else "Checking Wi-Fi...")
            }

            fun scanForTv() {
                if (!operationInProgress.compareAndSet(false, true)) return

                busy = true
                wifiConnected = hasWifiConnection()
                discoveredTv = null

                if (!wifiConnected) {
                    status = "Connect your phone to the same Wi-Fi network as the TV."
                    busy = false
                    operationInProgress.set(false)
                    return
                }

                status = "Searching for Android TV on this Wi-Fi..."
                executor.execute {
                    try {
                        val endpoint = AtvDiscovery.findTv(this@MainActivity)
                        runOnUiThread {
                            discoveredTv = endpoint
                            status = "TV found. You can pair now."
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            status = e.message ?: "TV search failed. Try again."
                        }
                    } finally {
                        operationInProgress.set(false)
                        runOnUiThread { busy = false }
                    }
                }
            }

            LaunchedEffect(Unit) {
                if (!alreadyPaired) scanForTv()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("iFFALCON Remote", fontSize = 28.sp)

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    if (wifiConnected) "Wi-Fi: CONNECTED" else "Wi-Fi: NOT CONNECTED",
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (!paired) {
                    if (!pairingStarted) {
                        val tv = discoveredTv
                        if (tv == null) {
                            Text(
                                if (busy) "Searching for available TVs..."
                                else "No TV discovered yet.",
                                fontSize = 16.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { scanForTv() },
                                enabled = !busy,
                                modifier = Modifier
                                    .width(230.dp)
                                    .height(60.dp)
                            ) {
                                Text("SEARCH FOR TV", fontSize = 19.sp)
                            }
                        } else {
                            Text("AVAILABLE TV", fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(tv.serviceName, fontSize = 20.sp)
                            Text("IP address: ${tv.host}", fontSize = 15.sp)

                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = {
                                    if (!operationInProgress.compareAndSet(false, true)) {
                                        return@Button
                                    }

                                    busy = true
                                    status = "Connecting to TV pairing service..."
                                    executor.execute {
                                        try {
                                            val session = AtvPairing.begin(
                                                this@MainActivity,
                                                tv.host
                                            )
                                            pairingSession = session
                                            pairingServiceName = tv.serviceName

                                            runOnUiThread {
                                                pairingStarted = true
                                                status = "Enter the PIN displayed on your TV"
                                            }
                                        } catch (e: Exception) {
                                            runOnUiThread {
                                                status = "Pairing error: ${e.message}"
                                            }
                                        } finally {
                                            operationInProgress.set(false)
                                            runOnUiThread { busy = false }
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier
                                    .width(230.dp)
                                    .height(65.dp)
                            ) {
                                Text("PAIR TV", fontSize = 22.sp)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = { scanForTv() },
                                enabled = !busy
                            ) {
                                Text("SEARCH AGAIN")
                            }
                        }
                    } else {
                        Text(
                            "Enter the 6-character PIN shown on TV",
                            fontSize = 16.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                pin = it.uppercase().take(6)
                            },
                            label = { Text("TV PIN") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii
                            ),
                            enabled = !busy,
                            modifier = Modifier.width(230.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val session = pairingSession
                                if (session == null) {
                                    pairingStarted = false
                                    pin = ""
                                    status = "Pairing session expired. Search for your TV and try again."
                                    return@Button
                                }

                                if (!operationInProgress.compareAndSet(false, true)) {
                                    return@Button
                                }

                                val enteredPin = pin
                                busy = true
                                status = "Verifying PIN..."

                                executor.execute {
                                    try {
                                        session.finish(enteredPin)
                                        pairingSession = null
                                        pairingServiceName?.let { serviceName ->
                                            getSharedPreferences("tv_connection", MODE_PRIVATE)
                                                .edit()
                                                .putString("service_name", serviceName)
                                                .apply()
                                        }

                                        runOnUiThread {
                                            paired = true
                                            pairingStarted = false
                                            pin = ""
                                            status = "Pairing successful. Ready."
                                        }
                                    } catch (e: Exception) {
                                        session.close()
                                        pairingSession = null
                                        pairingServiceName = null

                                        runOnUiThread {
                                            pairingStarted = false
                                            pin = ""
                                            status = "Pairing failed: ${e.message}. Search for your TV and retry."
                                        }
                                    } finally {
                                        operationInProgress.set(false)
                                        runOnUiThread { busy = false }
                                    }
                                }
                            },
                            enabled = pin.length == 6 && !busy,
                            modifier = Modifier
                                .width(230.dp)
                                .height(60.dp)
                        ) {
                            Text("CONFIRM", fontSize = 20.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (!operationInProgress.compareAndSet(false, true)) {
                                return@Button
                            }

                            busy = true
                            status = "Connecting / sending POWER..."

                            executor.execute {
                                try {
                                    var client = remoteClient

                                    if (client?.connected != true) {
                                        client?.close()

                                        val preferredName = getSharedPreferences(
                                            "tv_connection",
                                            MODE_PRIVATE
                                        ).getString("service_name", null)
                                        val endpoint = AtvDiscovery.findTv(
                                            this@MainActivity,
                                            preferredName
                                        )

                                        val newClient = AtvRemoteClient(
                                            context = this@MainActivity,
                                            host = endpoint.host,
                                            onDisconnected = { message ->
                                                runOnUiThread {
                                                    status = "Disconnected: $message"
                                                }
                                            }
                                        )

                                        remoteClient = newClient
                                        newClient.connect()
                                        getSharedPreferences("tv_connection", MODE_PRIVATE)
                                            .edit()
                                            .putString("service_name", endpoint.serviceName)
                                            .apply()
                                        client = newClient
                                    }

                                    checkNotNull(client).pressPower()

                                    runOnUiThread {
                                        status = "POWER command sent"
                                    }
                                } catch (e: Exception) {
                                    remoteClient?.close()
                                    remoteClient = null

                                    runOnUiThread {
                                        status = "Error: ${e.message}"
                                    }
                                } finally {
                                    operationInProgress.set(false)
                                    runOnUiThread { busy = false }
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .width(250.dp)
                            .height(110.dp)
                    ) {
                        Text("POWER", fontSize = 30.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(status, fontSize = 14.sp)
            }
        }
    }

    override fun onDestroy() {
        pairingSession?.close()
        remoteClient?.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
