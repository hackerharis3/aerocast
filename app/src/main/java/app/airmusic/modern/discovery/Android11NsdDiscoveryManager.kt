package app.airmusic.modern.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import app.airmusic.modern.model.SinkProtocol
import app.airmusic.modern.state.MultiRoomStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Clean-room replacement for AirMusic's embedded libjdns_sd.so (com.apple.dnssd.DNSSDEmbedded)
 * and k1.f / k1.m discovery tasks.
 *
 * Compatibility Architecture:
 * - On Android 11–13 (API 30–33): NsdManager.resolveService() fails with FAILURE_ALREADY_ACTIVE (3)
 *   if called concurrently across multiple services. All resolveService() calls are serialized
 *   through [resolveMutex] with a 4,000 ms timeout guard.
 * - On Android 14+ (API 34+): Uses NsdManager.registerServiceInfoCallback for parallel resolution.
 */
class Android11NsdDiscoveryManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val resolveMutex = Mutex()
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val activeListeners = ConcurrentHashMap<String, NsdManager.DiscoveryListener>()
    private val activeApi34Callbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private var discoveryJob: Job? = null

    private val serviceTypeMap = mapOf(
        "_raop._tcp." to SinkProtocol.AIRPLAY,
        "_googlecast._tcp." to SinkProtocol.CHROMECAST,
        "_amzn-wplay._tcp." to SinkProtocol.FIRE_TV,
        "_samsungmsf._tcp." to SinkProtocol.SAMSUNG_MULTIROOM,
        "_snapcast._tcp." to SinkProtocol.SNAPCAST
    )

    fun startDiscovery() {
        stopDiscovery()
        discoveryJob = scope.launch(Dispatchers.IO) {
            serviceTypeMap.forEach { (serviceType, protocol) ->
                registerDiscoveryForType(serviceType, protocol)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null

        activeListeners.forEach { (_, listener) ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
            }
        }
        activeListeners.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activeApi34Callbacks.forEach { (_, callback) ->
                try {
                    nsdManager.unregisterServiceInfoCallback(callback)
                } catch (_: IllegalArgumentException) {
                }
            }
            activeApi34Callbacks.clear()
        }
    }

    private fun registerDiscoveryForType(serviceType: String, protocol: SinkProtocol) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    resolveApi34(serviceInfo, protocol)
                } else {
                    scope.launch(Dispatchers.IO) {
                        resolveLegacyApi30Serialized(serviceInfo, protocol)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    activeApi34Callbacks.remove(serviceInfo.serviceName)?.let { cb ->
                        try {
                            nsdManager.unregisterServiceInfoCallback(cb)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                }
            }
        }

        activeListeners[serviceType] = listener
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
        }
    }

    /**
     * Android 11–13 (API 30–33) serialized resolution using [resolveMutex] to guarantee
     * at most one active NsdManager.resolveService() call at any instant.
     */
    private suspend fun resolveLegacyApi30Serialized(
        rawService: NsdServiceInfo,
        protocol: SinkProtocol
    ) {
        resolveMutex.withLock {
            val resolved = withTimeoutOrNull(4000L) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(serviceInfo)
                        }
                    }
                    try {
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(rawService, resolveListener)
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            resolved?.let { publishResolvedReceiver(it, protocol) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveApi34(serviceInfo: NsdServiceInfo, protocol: SinkProtocol) {
        val key = "${protocol.name}_${serviceInfo.serviceName}"
        if (activeApi34Callbacks.containsKey(key)) return

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
            override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                publishResolvedReceiver(updatedInfo, protocol)
            }
            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() {}
        }

        activeApi34Callbacks[key] = callback
        try {
            nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
        } catch (_: Throwable) {
            activeApi34Callbacks.remove(key)
        }
    }

    private fun publishResolvedReceiver(info: NsdServiceInfo, protocol: SinkProtocol) {
        val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        } ?: return

        val port = info.port.takeIf { it > 0 } ?: protocol.defaultPort
        val txtAttributes = info.attributes.mapValues { (_, bytes) ->
            bytes?.toString(StandardCharsets.UTF_8).orEmpty()
        }

        // Fire TV Whisperplay verification (matches k1.f mode 2 check for "amzn.thin.pl")
        if (protocol == SinkProtocol.FIRE_TV && !info.serviceName.contains("amzn.thin.pl", ignoreCase = true)) {
            return
        }

        val cleanName = when (protocol) {
            SinkProtocol.AIRPLAY -> {
                // Strip "<MAC>@" prefix from RAOP service name (e.g. "AA:BB:CC:DD:EE:FF@Living Room")
                info.serviceName.substringAfter('@', info.serviceName)
            }
            SinkProtocol.CHROMECAST -> {
                txtAttributes["fn"]?.takeIf { it.isNotBlank() } ?: info.serviceName
            }
            else -> info.serviceName
        }

        val deviceId = "${protocol.name.lowercase()}_${hostAddress.replace('.', '_')}_$port"
        MultiRoomStateRepository.upsertDiscoveredReceiver(
            id = deviceId,
            name = cleanName,
            protocol = protocol,
            ipAddress = hostAddress,
            port = port,
            airplayEncryptionTypes = if (protocol == SinkProtocol.AIRPLAY) {
                txtAttributes["et"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            } else emptySet()
        )
    }
}
