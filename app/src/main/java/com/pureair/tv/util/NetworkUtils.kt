package com.pureair.tv.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object NetworkUtils {

    /**
     * 获取本机局域网 IPv4 地址
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // 优先匹配 wlan0 或 eth0 等 Wi-Fi / 有线物理网卡
            val sortedInterfaces = interfaces.sortedByDescending {
                it.name.startsWith("wlan", ignoreCase = true) || it.name.startsWith("eth", ignoreCase = true)
            }
            for (intf in sortedInterfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (!hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    /**
     * 获取本机 MAC 地址，用于 AirPlay 设备唯一标识 (Device ID)
     * 格式如：00:11:22:33:44:55
     */
    fun getMacAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.equals("wlan0", ignoreCase = true) || intf.name.equals("eth0", ignoreCase = true)) {
                    val macBytes = intf.hardwareAddress ?: continue
                    val sb = StringBuilder()
                    for (b in macBytes) {
                        sb.append(String.format("%02X:", b))
                    }
                    if (sb.isNotEmpty()) {
                        sb.deleteCharAt(sb.length - 1)
                    }
                    return sb.toString().uppercase(Locale.ROOT)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 若在部分 Android 10+ 环境下受限，则根据 IP 生成一个伪随机稳定的虚拟 MAC
        val ipParts = getLocalIpAddress().split(".")
        return if (ipParts.size == 4) {
            String.format(
                Locale.ROOT,
                "5A:91:A0:%02X:%02X:%02X",
                ipParts[1].toIntOrNull() ?: 168,
                ipParts[2].toIntOrNull() ?: 1,
                ipParts[3].toIntOrNull() ?: 100
            )
        } else {
            "5A:91:A0:A1:B2:C3"
        }
    }
}
