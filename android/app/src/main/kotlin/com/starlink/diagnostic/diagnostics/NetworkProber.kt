package com.starlink.diagnostic.diagnostics

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Kotlin-side network path prober (phone -> router -> dish). The gRPC probe
 * itself runs through the Python layer (real gRPC call), everything here is
 * plain socket-level checks so we can tell a router problem apart from a
 * dish problem.
 */
object NetworkProber {

    data class RawProbe(
        val phoneIp: String?,
        val gateway: String?,
        val tcp9200Ok: Boolean?,
        val icmpOk: Boolean?,
        val tcpErrorAr: String?,
    )

    fun phoneIpOnWifi(): String? = try {
        val candidates = mutableListOf<String>()
        val ifs = NetworkInterface.getNetworkInterfaces()
        while (ifs.hasMoreElements()) {
            val nif = ifs.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name ?: ""
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    // prefer wlan (Starlink router connection)
                    val ip = a.hostAddress ?: continue
                    if (name.startsWith("wlan")) return ip
                    candidates.add(ip)
                }
            }
        }
        candidates.firstOrNull()
    } catch (_: Exception) {
        null
    }

    fun wifiGateway(context: Context): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo
        if (dhcp != null && dhcp.gateway != 0) {
            Formatter.formatIpAddress(dhcp.gateway)
        } else null
    } catch (_: Exception) {
        null
    }

    suspend fun probeTcp(host: String, port: Int, timeoutMs: Int = 3500): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    true to null
                }
            } catch (e: Exception) {
                false to when (e) {
                    is java.net.SocketTimeoutException -> "انتهت المهلة أثناء الوصول إلى %s:%d".format(host, port)
                    is java.net.ConnectException -> "رفض الاتصال — %s:%d غير متاح".format(host, port)
                    is java.net.UnknownHostException -> "عنوان غير معروف: %s".format(host)
                    else -> "فشل الاتصال: %s".format(e.message ?: e.javaClass.simpleName)
                }
            }
        }

    suspend fun probeIcmp(host: String, timeoutMs: Int = 2000): Boolean =
        withContext(Dispatchers.IO) {
            try {
                InetAddress.getByName(host).isReachable(timeoutMs)
            } catch (_: Exception) {
                false
            }
        }

    suspend fun probe(context: Context, dishHost: String, dishPort: Int): RawProbe =
        withContext(Dispatchers.IO) {
            val phone = phoneIpOnWifi()
            val gw = wifiGateway(context)
            val (tcpOk, tcpErr) = probeTcp(dishHost, dishPort)
            // ICMP is often blocked on Android without root; informational only.
            val icmp = probeIcmp(dishHost)
            RawProbe(
                phoneIp = phone,
                gateway = gw,
                tcp9200Ok = tcpOk,
                icmpOk = icmp,
                tcpErrorAr = tcpErr,
            )
        }
}
