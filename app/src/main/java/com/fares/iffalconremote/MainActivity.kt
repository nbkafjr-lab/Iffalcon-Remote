package com.fares.iffalconremote

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.Surface
import androidx.compose.material.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
            var textToSend by remember { mutableStateOf("") }
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

            fun runRemoteAction(label: String, action: (AtvRemoteClient) -> Unit) {
                if (!operationInProgress.compareAndSet(false, true)) return

                busy = true
                status = "$label..."

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

                        action(checkNotNull(client))

                        runOnUiThread {
                            status = "$label command sent"
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
            }

            LaunchedEffect(Unit) {
                if (!alreadyPaired) scanForTv()
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF081321),
                contentColor = Color(0xFFEAF2FF)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                Text("iFFALCON Remote", fontSize = 27.sp)

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    if (wifiConnected) "Wi-Fi: CONNECTED" else "Wi-Fi: NOT CONNECTED",
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

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
                    // Premium dark remote interface. Remote actions and protocol calls are unchanged.
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF111E30),
                        shape = RoundedCornerShape(28.dp),
                        elevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        "YOUR TV REMOTE",
                                        color = Color(0xFF91A7C2),
                                        fontSize = 11.sp,
                                        letterSpacing = 1.5.sp
                                    )
                                    Text(
                                        "Living Room",
                                        color = Color(0xFFF4F8FF),
                                        fontSize = 21.sp
                                    )
                                }
                                Surface(
                                    color = Color(0xFF123B3B),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(7.dp),
                                            color = Color(0xFF45E0B1),
                                            shape = CircleShape
                                        ) {}
                                        Spacer(modifier = Modifier.width(7.dp))
                                        Text("PAIRED", color = Color(0xFF8AF0D0), fontSize = 10.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = { runRemoteAction("POWER") { it.pressPower() } },
                                enabled = !busy,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF26364B),
                                    contentColor = Color(0xFFFF7185),
                                    disabledBackgroundColor = Color(0xFF26364B),
                                    disabledContentColor = Color(0xFF8A96A8)
                                ),
                                modifier = Modifier.size(66.dp)
                            ) {
                                Text("⏻", fontSize = 29.sp)
                            }
                            Spacer(modifier = Modifier.height(7.dp))
                            Text("POWER", color = Color(0xFF91A7C2), fontSize = 10.sp, letterSpacing = 1.5.sp)

                            Spacer(modifier = Modifier.height(20.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { runRemoteAction("BACK") { it.pressKey(4) } },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF24364D),
                                        contentColor = Color(0xFFEAF2FF)
                                    ),
                                    modifier = Modifier.weight(1f).height(50.dp)
                                ) { Text("‹   BACK", fontSize = 13.sp) }

                                Button(
                                    onClick = { runRemoteAction("HOME") { it.pressKey(3) } },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF24364D),
                                        contentColor = Color(0xFFEAF2FF)
                                    ),
                                    modifier = Modifier.weight(1f).height(50.dp)
                                ) { Text("⌂   HOME", fontSize = 13.sp) }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            Text(
                                "NAVIGATION",
                                color = Color(0xFF91A7C2),
                                fontSize = 11.sp,
                                letterSpacing = 2.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Surface(
                                color = Color(0xFF0B1626),
                                shape = RoundedCornerShape(30.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Button(
                                        onClick = { runRemoteAction("UP") { it.pressKey(19) } },
                                        enabled = !busy,
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = Color(0xFF24364D),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.size(64.dp)
                                    ) { Text("▲", fontSize = 21.sp) }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { runRemoteAction("LEFT") { it.pressKey(21) } },
                                            enabled = !busy,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                backgroundColor = Color(0xFF24364D),
                                                contentColor = Color.White
                                            ),
                                            modifier = Modifier.size(64.dp)
                                        ) { Text("◀", fontSize = 20.sp) }

                                        Button(
                                            onClick = { runRemoteAction("OK") { it.pressKey(23) } },
                                            enabled = !busy,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                backgroundColor = Color(0xFF25B9D2),
                                                contentColor = Color(0xFF061521)
                                            ),
                                            modifier = Modifier.size(70.dp)
                                        ) { Text("OK", fontSize = 17.sp) }

                                        Button(
                                            onClick = { runRemoteAction("RIGHT") { it.pressKey(22) } },
                                            enabled = !busy,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                backgroundColor = Color(0xFF24364D),
                                                contentColor = Color.White
                                            ),
                                            modifier = Modifier.size(64.dp)
                                        ) { Text("▶", fontSize = 20.sp) }
                                    }

                                    Button(
                                        onClick = { runRemoteAction("DOWN") { it.pressKey(20) } },
                                        enabled = !busy,
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = Color(0xFF24364D),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.size(64.dp)
                                    ) { Text("▼", fontSize = 21.sp) }
                                }
                            }

                            Spacer(modifier = Modifier.height(26.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "KEYBOARD",
                                    color = Color(0xFF91A7C2),
                                    fontSize = 11.sp,
                                    letterSpacing = 2.sp
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text("PHONE TO TV", color = Color(0xFF5E7898), fontSize = 9.sp)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = textToSend,
                                onValueChange = { textToSend = it },
                                label = { Text("Type something…", color = Color(0xFF91A7C2)) },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    keyboardType = KeyboardType.Text
                                ),
                                minLines = 2,
                                maxLines = 4,
                                enabled = !busy,
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    textColor = Color(0xFFF4F8FF),
                                    focusedBorderColor = Color(0xFF25B9D2),
                                    unfocusedBorderColor = Color(0xFF40536B),
                                    cursorColor = Color(0xFF25B9D2)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val text = textToSend
                                        runRemoteAction("SEND TEXT") { it.sendText(text) }
                                    },
                                    enabled = !busy && textToSend.isNotEmpty(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF25B9D2),
                                        contentColor = Color(0xFF061521)
                                    ),
                                    modifier = Modifier.weight(1.3f).height(48.dp)
                                ) { Text("SEND", fontSize = 12.sp) }

                                Button(
                                    onClick = { runRemoteAction("BACKSPACE") { it.pressKey(67) } },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF24364D),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.weight(0.8f).height(48.dp)
                                ) { Text("⌫", fontSize = 19.sp) }

                                Button(
                                    onClick = { runRemoteAction("ENTER") { it.pressKey(66) } },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF24364D),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) { Text("ENTER", fontSize = 11.sp) }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(status, fontSize = 13.sp)
                }
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