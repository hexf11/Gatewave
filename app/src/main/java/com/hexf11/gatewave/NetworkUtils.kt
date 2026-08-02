package com.hexf11.gatewave

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

// VPN 处于活动状态时仍需枚举底层 Wi-Fi；activeNetwork 只会返回 VPN。
@Suppress("DEPRECATION")
internal object NetworkUtils {
    @JvmStatic
    fun findLanIpv4(context: Context): String {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager != null) {
            for (network in connectivityManager.allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities == null ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                ) {
                    continue
                }
                val properties = connectivityManager.getLinkProperties(network) ?: continue
                for (linkAddress in properties.linkAddresses) {
                    val address = linkAddress.address
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        }

        runCatching {
            for (networkInterface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (address in Collections.list(networkInterface.inetAddresses)) {
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        }
        return "0.0.0.0"
    }

    @JvmStatic
    fun isAllowedClient(address: InetAddress?): Boolean =
        address != null && (
            address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                isIpv6UniqueLocal(address)
            )

    @JvmStatic
    fun isSameWifiSubnet(context: Context, remote: InetAddress?): Boolean {
        if (remote == null) return false
        if (remote.isLoopbackAddress) return true
        if (remote !is Inet4Address) return false
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities == null ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ) {
                continue
            }
            val properties = connectivityManager.getLinkProperties(network) ?: continue
            for (local in properties.linkAddresses) {
                if (local.address is Inet4Address &&
                    matchesPrefix(local.address, remote, local.prefixLength)
                ) {
                    return true
                }
            }
        }
        return false
    }

    @JvmStatic
    fun isBlockedTarget(address: InetAddress?): Boolean =
        address == null ||
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            address.isMulticastAddress ||
            isIpv6UniqueLocal(address)

    private fun isIpv6UniqueLocal(address: InetAddress): Boolean {
        val raw = address.address
        return raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC
    }

    private fun matchesPrefix(left: InetAddress, right: InetAddress, prefixLength: Int): Boolean {
        val a = left.address
        val b = right.address
        if (a.size != b.size || prefixLength !in 0..(a.size * 8)) return false
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (a[index] != b[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = 0xFF shl (8 - remainingBits)
        return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
    }
}
