package com.fares.iffalconremote

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val alreadyPaired = File(filesDir, "atv-tv-cert.der").exists()

        setContent {
            var paired by remember { mutableStateOf(alreadyPaired) }
            var pairingStarted by remember { mutableStateOf(false) }
            var pin by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var status by remember {
                mutableStateOf(
                    if (alreadyPaired) "Ready" else "Pair your TV first"
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("iFFALCON Remote", fontSize = 28.sp)

                Spacer(modifier = Modifier.height(45.dp))

                if (!paired) {
                    if (!pairingStarted) {
                        Button(
                            onClick = {
                                if (!operationInProgress.compareAndSet(false, true)) {
                                    return@Button
                                }

                                busy = true
                                status = "Connecting to TV..."

                                executor.execute {
                                    try {
                                        val session = AtvPairing.begin(this@MainActivity)
                                        pairingSession = session

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
                                    status = "Pairing session expired. Tap PAIR TV to try again."
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

                                        runOnUiThread {
                                            paired = true
                                            pairingStarted = false
                                            pin = ""
                                            status = "Pairing successful. Ready."
                                        }
                                    } catch (e: Exception) {
                                        session.close()
                                        pairingSession = null

                                        runOnUiThread {
                                            pairingStarted = false
                                            pin = ""
                                            status = "Pairing failed: ${e.message}. Tap PAIR TV to retry."
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

                                        val newClient = AtvRemoteClient(
                                            context = this@MainActivity,
                                            onDisconnected = { message ->
                                                runOnUiThread {
                                                    status = "Disconnected: $message"
                                                }
                                            }
                                        )

                                        remoteClient = newClient
                                        newClient.connect()
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

                Spacer(modifier = Modifier.height(28.dp))

                Text(status, fontSize = 15.sp)
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
