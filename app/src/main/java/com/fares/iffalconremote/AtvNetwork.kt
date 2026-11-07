package com.fares.iffalconremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Socket

/**
 * Explicitly routes TV TCP sockets over the phone's active Wi-Fi network.
 * The socket must still be unconnected when this function is called.
 */
internal object AtvNetwork {
    fun bindSocketToActiveWifi(context: Context, socket: Socket) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val network = connectivity.activeNetwork
            ?: throw IllegalStateException("No active Wi-Fi network is available")
        val capabilities = connectivity.getNetworkCapabilities(network)

        check(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "The active network is not Wi-Fi. Connect to the same Wi-Fi as the TV."
        }

        network.bindSocket(socket)
    }
}
