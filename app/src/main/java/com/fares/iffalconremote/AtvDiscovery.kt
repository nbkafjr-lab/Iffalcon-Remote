package com.fares.iffalconremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class TvEndpoint(
    val host: String,
    val serviceName: String
)

object AtvDiscovery {
    private const val SERVICE_TYPE = "_androidtvremote2._tcp."
    private const val REMOTE_PORT = 6466
    private const val DISCOVERY_TIMEOUT_SECONDS = 10L

    fun findTv(context: Context, preferredServiceName: String? = null): TvEndpoint {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: error("Android network discovery is unavailable")

        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: error("Wi-Fi service is unavailable")

        val multicastLock = wifi.createMulticastLock("iFFALCONRemote-mDNS")
        multicastLock.setReferenceCounted(false)
        multicastLock.acquire()

        val result = AtomicReference<TvEndpoint?>(null)
        val finished = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val discoveryStarted = AtomicBoolean(false)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryStarted.set(true)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                if (preferredServiceName != null &&
                    serviceInfo.serviceName != preferredServiceName
                ) return
                if (!resolving.compareAndSet(false, true)) return

                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host?.hostAddress
                        if (info.port == REMOTE_PORT && !address.isNullOrBlank()) {
                            result.compareAndSet(
                                null,
                                TvEndpoint(address, info.serviceName)
                            )
                            finished.countDown()
                        } else {
                            resolving.set(false)
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finished.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            nsd.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )

            val found = finished.await(
                DISCOVERY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            check(found && result.get() != null) {
                if (preferredServiceName == null) {
                    "No Android TV Remote service found on this Wi-Fi network"
                } else {
                    "Previously paired TV was not found. Check that it is on the same Wi-Fi network."
                }
            }

            return checkNotNull(result.get())
        } finally {
            if (discoveryStarted.get()) {
                try {
                    nsd.stopServiceDiscovery(listener)
                } catch (_: Exception) {
                }
            }
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
    }
}
