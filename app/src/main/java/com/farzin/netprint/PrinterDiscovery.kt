package com.farzin.netprint

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** Finds printers via mDNS/Bonjour (IPP, RAW 9100, LPD) and via a /24 subnet port scan. */
class PrinterDiscovery(private val context: Context, private val onFound: (NetPrinter) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val types = listOf("_ipp._tcp.", "_pdl-datastream._tcp.", "_printer._tcp.")

    fun startMdns() {
        stopMdns()
        for (type in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) { main.post { queue.addLast(s); resolveNext() } }
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }
            listeners += l
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }
        }
    }

    fun stopMdns() { listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }; listeners.clear() }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val s = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(si: NsdServiceInfo) {
                main.post { resolving = false; toPrinter(si)?.let(onFound); resolveNext() }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun toPrinter(si: NsdServiceInfo): NetPrinter? {
        val host = si.host?.hostAddress ?: return null
        val type = si.serviceType ?: ""
        val txt = { k: String -> si.attributes[k]?.let { String(it) } }
        return when {
            type.contains("_ipp") -> NetPrinter(si.serviceName, host, si.port, Protocol.IPP, txt("rp") ?: "ipp/print")
            type.contains("_pdl") -> NetPrinter(si.serviceName, host, si.port, Protocol.RAW)
            else -> NetPrinter(si.serviceName, host, si.port, Protocol.LPD, queue = txt("rp") ?: "lp")
        }
    }

    /** Scans x.x.x.1-254 on ports 631 / 9100 / 515. Catches printers that don't advertise via Bonjour. */
    suspend fun scanSubnet() = coroutineScope {
        val me = localIpv4() ?: return@coroutineScope
        val prefix = me.substringBeforeLast('.')
        val sem = Semaphore(64)
        val ports = listOf(631 to Protocol.IPP, 9100 to Protocol.RAW, 515 to Protocol.LPD)
        (1..254).map { i ->
            launch(Dispatchers.IO) {
                val h = "$prefix.$i"
                if (h != me) sem.withPermit {
                    for ((port, proto) in ports) if (isOpen(h, port)) {
                        val p = NetPrinter("Printer @ $h", h, port, proto)
                        withContext(Dispatchers.Main) { onFound(p) }
                    }
                }
            }
        }.joinAll()
    }

    private fun isOpen(host: String, port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 350) }; true
    } catch (_: Exception) { false }

    private fun localIpv4(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}
