package com.fares.iffalconremote

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.Surface
import androidx.compose.material.ButtonDefaults
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

        window.statusBarColor = android.graphics.Color.rgb(7, 13, 25)
        window.navigationBarColor = android.graphics.Color.rgb(7, 13, 25)

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
                color = Color(0xFF070D19),
                contentColor = Color(0xFFEAF2FF)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                if (!paired) {
                    Text("iFFALCON Remote", fontSize = 27.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (wifiConnected) "Wi-Fi: CONNECTED" else "Wi-Fi: NOT CONNECTED",
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }

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
                    // Reference-inspired glass remote. All actions still use the existing remote client.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF151D30),
                            shape = RoundedCornerShape(26.dp),
                            elevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = Color(0xFF263653),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        "TV",
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                        color = Color(0xFFEAF2FF),
                                        fontSize = 15.sp
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "iFFALCON Remote",
                                    color = Color(0xFFF4F7FF),
                                    fontSize = 22.sp
                                )
                                Spacer(Modifier.weight(1f))
                                Surface(
                                    color = Color(0xFF123B35),
                                    shape = RoundedCornerShape(30.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(9.dp),
                                            color = Color(0xFF68E84B),
                                            shape = CircleShape
                                        ) {}
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            "Paired",
                                            color = Color(0xFF79F05C),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        Button(
                            onClick = { runRemoteAction("POWER") { it.pressPower() } },
                            enabled = !busy,
                            shape = RoundedCornerShape(50.dp),
                            border = BorderStroke(2.dp, Color(0xFF159EFF)),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF101D35),
                                contentColor = Color.White,
                                disabledBackgroundColor = Color(0xFF101D35),
                                disabledContentColor = Color.Gray
                            ),
                            modifier = Modifier
                                .width(230.dp)
                                .height(66.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Canvas(Modifier.size(30.dp)) {
                                    val ring = Stroke(
                                        width = size.minDimension * 0.10f,
                                        cap = StrokeCap.Round
                                    )
                                    drawArc(
                                        color = Color(0xFF20B7FF),
                                        startAngle = 135f,
                                        sweepAngle = 270f,
                                        useCenter = false,
                                        topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
                                        size = Size(size.width * 0.68f, size.height * 0.68f),
                                        style = ring
                                    )
                                    drawLine(
                                        color = Color(0xFF20B7FF),
                                        start = Offset(size.width / 2f, size.height * 0.06f),
                                        end = Offset(size.width / 2f, size.height * 0.49f),
                                        strokeWidth = size.minDimension * 0.10f,
                                        cap = StrokeCap.Round
                                    )
                                }
                                Text("POWER", fontSize = 21.sp)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { runRemoteAction("VOLUME DOWN") { it.pressKey(25) } },
                                enabled = !busy,
                                shape = RoundedCornerShape(40.dp),
                                border = BorderStroke(1.dp, Color(0xFF405681)),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF172238),
                                    contentColor = Color(0xFFEAF2FF)
                                ),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) { Text("−  VOL", fontSize = 15.sp) }

                            Button(
                                onClick = { runRemoteAction("MUTE") { it.pressKey(164) } },
                                enabled = !busy,
                                shape = RoundedCornerShape(40.dp),
                                border = BorderStroke(1.dp, Color(0xFF405681)),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF172238),
                                    contentColor = Color(0xFFEAF2FF)
                                ),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) { Text("MUTE", fontSize = 14.sp) }

                            Button(
                                onClick = { runRemoteAction("VOLUME UP") { it.pressKey(24) } },
                                enabled = !busy,
                                shape = RoundedCornerShape(40.dp),
                                border = BorderStroke(1.dp, Color(0xFF405681)),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF172238),
                                    contentColor = Color(0xFFEAF2FF)
                                ),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) { Text("+  VOL", fontSize = 15.sp) }
                        }

                        Spacer(Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { runRemoteAction("BACK") { it.pressKey(4) } },
                                enabled = !busy,
                                shape = RoundedCornerShape(40.dp),
                                border = BorderStroke(1.dp, Color(0xFF52658F)),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF172238),
                                    contentColor = Color(0xFFEAF2FF)
                                ),
                                modifier = Modifier.weight(1f).height(58.dp)
                            ) { Text("←   BACK", fontSize = 16.sp) }

                            Button(
                                onClick = { runRemoteAction("HOME") { it.pressKey(3) } },
                                enabled = !busy,
                                shape = RoundedCornerShape(40.dp),
                                border = BorderStroke(1.dp, Color(0xFF52658F)),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF172238),
                                    contentColor = Color(0xFFEAF2FF)
                                ),
                                modifier = Modifier.weight(1f).height(58.dp)
                            ) { Text("⌂   HOME", fontSize = 16.sp) }
                        }

                        Spacer(Modifier.height(18.dp))

                        Surface(
                            color = Color(0xFF0B1425),
                            shape = RoundedCornerShape(32.dp),
                            border = BorderStroke(1.dp, Color(0xFF1B2B49))
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Button(
                                    onClick = { runRemoteAction("UP") { it.pressKey(19) } },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, Color(0xFF405681)),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF19243B),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(width = 92.dp, height = 66.dp)
                                ) { Text("⌃", fontSize = 34.sp) }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { runRemoteAction("LEFT") { it.pressKey(21) } },
                                        enabled = !busy,
                                        shape = RoundedCornerShape(24.dp),
                                        border = BorderStroke(1.dp, Color(0xFF405681)),
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = Color(0xFF19243B),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.size(82.dp)
                                    ) { Text("‹", fontSize = 42.sp) }

                                    Button(
                                        onClick = { runRemoteAction("OK") { it.pressKey(23) } },
                                        enabled = !busy,
                                        shape = CircleShape,
                                        border = BorderStroke(3.dp, Color(0xFF079FFF)),
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = Color(0xFF111E36),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.size(94.dp)
                                    ) { Text("OK", fontSize = 21.sp) }

                                    Button(
                                        onClick = { runRemoteAction("RIGHT") { it.pressKey(22) } },
                                        enabled = !busy,
                                        shape = RoundedCornerShape(24.dp),
                                        border = BorderStroke(1.dp, Color(0xFF405681)),
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = Color(0xFF19243B),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.size(82.dp)
                                    ) { Text("›", fontSize = 42.sp) }
                                }

                                Button(
                                    onClick = { runRemoteAction("DOWN") { it.pressKey(20) } },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, Color(0xFF405681)),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = Color(0xFF19243B),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(width = 92.dp, height = 66.dp)
                                ) { Text("⌄", fontSize = 34.sp) }
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF405176)))
                            Text(
                                "  ⌨  KEYBOARD  ",
                                color = Color(0xFFDCE7FF),
                                fontSize = 17.sp
                            )
                            Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF405176)))
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = textToSend,
                            onValueChange = { textToSend = it },
                            placeholder = { Text("Type something…", color = Color(0xFF8294B9)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                keyboardType = KeyboardType.Text
                            ),
                            enabled = !busy,
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = Color(0xFFF4F7FF),
                                focusedBorderColor = Color(0xFF159EFF),
                                unfocusedBorderColor = Color(0xFF465A84),
                                cursorColor = Color(0xFF159EFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(62.dp)
                                .background(Color(0xFF151F35), RoundedCornerShape(22.dp))
                        )

                        Spacer(Modifier.height(12.dp))

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
                                shape = RoundedCornerShape(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF087CF0),
                                    contentColor = Color.White,
                                    disabledBackgroundColor = Color(0xFF294A73),
                                    disabledContentColor = Color(0xFFB5C7E2)
                                ),
                                modifier = Modifier.weight(1f).height(58.dp)
                            ) { Text("➤  SEND", fontSize = 16.sp) }

                            Button(
                                onClick = { runRemoteAction("BACKSPACE") { it.pressKey(67) } },
                                enabled = !busy,
                                shape = RoundedCornerShape(40.dp),
                                border = BorderStroke(1.dp, Color(0xFF52658F)),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF172238),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1f).height(58.dp)
                            ) { Text("⌫  BACKSPACE", fontSize = 12.sp) }

                            Button(
                                onClick = { runRemoteAction("ENTER") { it.pressKey(66) } },
                                enabled = !busy,
                                shape = RoundedCornerShape(40.dp),
                                border = BorderStroke(1.dp, Color(0xFF52658F)),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF172238),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1f).height(58.dp)
                            ) { Text("↵  ENTER", fontSize = 12.sp) }
                        }

                        Spacer(Modifier.height(16.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF151D30),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Color(0xFF293958)),
                            elevation = 5.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 3.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                @Composable
                                fun BottomAction(itemModifier: Modifier, icon: String, label: String, action: () -> Unit) {
                                    Column(
                                        modifier = itemModifier,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Button(
                                            onClick = action,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                backgroundColor = Color.Transparent,
                                                contentColor = Color(0xFFE4ECFF)
                                            ),
                                            elevation = null,
                                            contentPadding = PaddingValues(4.dp),
                                            modifier = Modifier.size(42.dp)
                                        ) { Text(icon, fontSize = 25.sp) }
                                        Text(label, color = Color(0xFFE4ECFF), fontSize = 10.sp)
                                    }
                                }

                                BottomAction(Modifier.weight(1f), "⚙", "Settings") {
                                    startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                                BottomAction(Modifier.weight(1f), "◉", "Wi-Fi") {
                                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                }
                                BottomAction(Modifier.weight(1f), "B", "Bluetooth") {
                                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                }
                                BottomAction(Modifier.weight(1f), "▧", "Wallpaper") {
                                    startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
                                }
                                BottomAction(Modifier.weight(1f), "⠿", "More") {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                }
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