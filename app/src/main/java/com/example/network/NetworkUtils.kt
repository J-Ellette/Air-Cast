package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

object NetworkUtils {

    private val random = SecureRandom()

    fun getLocalWifiIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            // Prefer wlan interfaces first
            val ordered = interfaces.sortedByDescending { iface ->
                val n = iface.name.lowercase()
                when {
                    n.startsWith("wlan") -> 3
                    n.startsWith("eth") -> 2
                    else -> 1
                }
            }
            for (iface in ordered) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses.toList()
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun generateAppleStyleMac(): String {
        // Apple OUI prefixes commonly seen on iOS/macOS devices
        val appleOuis = listOf("A4:83:E7", "F0:18:98", "3C:06:30", "DC:A9:04", "88:66:5A")
        val prefix = appleOuis[random.nextInt(appleOuis.size)]
        val b4 = "%02X".format(random.nextInt(256))
        val b5 = "%02X".format(random.nextInt(256))
        val b6 = "%02X".format(random.nextInt(256))
        return "$prefix:$b4:$b5:$b6"
    }

    fun generateDacpId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun generateActiveRemote(): String {
        val num = (100_000_000L + (random.nextLong() and 0x7FFFFFFFL) % 3_000_000_000L)
        return num.toString()
    }

    fun generateSessionUuid(): String = UUID.randomUUID().toString().uppercase()
}
