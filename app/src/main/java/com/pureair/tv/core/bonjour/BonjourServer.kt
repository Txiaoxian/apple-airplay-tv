package com.pureair.tv.core.bonjour

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.pureair.tv.App
import com.pureair.tv.R
import com.pureair.tv.core.crypto.AirPlayCredentials
import com.pureair.tv.util.DeviceUtils
import com.pureair.tv.util.LogEventBus
import com.pureair.tv.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.HashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS / Bonjour 广播服务管理
 * 单一注册原则，避免同名服务冲突，补齐 iOS 屏幕镜像必须的 pi/psi 属性
 */
class BonjourServer {

    companion object {
        private const val TAG = "BonjourServer"
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."
        const val DEFAULT_AIRPLAY_PORT = 7000
        const val ASCII_HOSTNAME = "PureAir-TV"

        fun buildAirPlayTxtRecord(macAddress: String): Map<String, String> {
            val txt = HashMap<String, String>()
            txt["deviceid"] = macAddress
            txt["features"] = "0x5A7FFEE6,0x1E"
            txt["flags"] = "0x4"
            txt["model"] = "AppleTV5,3"
            txt["srcvers"] = "220.68"
            txt["vv"] = "2"
            txt["pk"] = AirPlayCredentials.publicKeyHex
            txt["pi"] = AirPlayCredentials.FIXED_PI
            txt["psi"] = AirPlayCredentials.FIXED_PI
            return txt
        }
    }

    private var jmDNS: JmDNS? = null
    private var airPlayServiceInfo: ServiceInfo? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    suspend fun start(
        deviceName: String = DeviceUtils.getDeviceDisplayName(),
        airPlayPort: Int = DEFAULT_AIRPLAY_PORT
    ) = withContext(Dispatchers.IO) {
        if (isRegistered) return@withContext

        val localIpStr = NetworkUtils.getLocalIpAddress()
        val macAddress = NetworkUtils.getMacAddress()
        Log.i(TAG, "Starting Bonjour on IP: $localIpStr, MAC: $macAddress, Device: $deviceName")

        // 仅使用系统级 NsdManager 进行单一无冲突注册
        val nsdSuccess = registerViaNsdManager(deviceName, airPlayPort, macAddress)
        if (!nsdSuccess) {
            // 若 NsdManager 失败，才使用 JmDNS 作为备选，绝不同时注册
            registerViaJmDns(deviceName, airPlayPort, localIpStr, macAddress)
        }

        isRegistered = true
    }

    private fun registerViaNsdManager(deviceName: String, port: Int, mac: String): Boolean {
        return try {
            nsdManager = App.instance.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) return false

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_airplay._tcp."
                setPort(port)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("deviceid", mac)
                    setAttribute("features", "0x5A7FFEE6,0x1E")
                    setAttribute("model", "AppleTV5,3")
                    setAttribute("srcvers", "220.68")
                    setAttribute("flags", "0x4")
                    setAttribute("vv", "2")
                    setAttribute("pk", AirPlayCredentials.publicKeyHex)
                    // 关键补齐：iOS 屏幕镜像强制要求的 pi 与 psi 标识
                    setAttribute("pi", AirPlayCredentials.FIXED_PI)
                    setAttribute("psi", AirPlayCredentials.FIXED_PI)
                }
            }

            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Log.i(TAG, "Android NsdManager successfully registered: ${serviceInfo?.serviceName}")
                    LogEventBus.log("mDNS 广播已就绪: ${serviceInfo?.serviceName} (系统级)")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "NsdManager registration failed: $errorCode")
                    LogEventBus.log("系统级 mDNS 注册失败: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    Log.i(TAG, "NsdManager unregistered")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "NsdManager unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error registering via NsdManager", e)
            false
        }
    }

    private fun registerViaJmDns(deviceName: String, port: Int, localIpStr: String, mac: String) {
        try {
            val localInetAddr = InetAddress.getByName(localIpStr)
            jmDNS = JmDNS.create(localInetAddr, ASCII_HOSTNAME)

            val airPlayProps = buildAirPlayTxtRecord(mac)
            airPlayServiceInfo = ServiceInfo.create(
                AIRPLAY_SERVICE_TYPE,
                deviceName,
                port,
                0,
                0,
                airPlayProps
            )
            jmDNS?.registerService(airPlayServiceInfo)
            Log.i(TAG, "JmDNS fallback registered.")
            LogEventBus.log("mDNS 广播已就绪: $deviceName (JmDNS)")
        } catch (e: Exception) {
            Log.e(TAG, "JmDNS fallback registration error", e)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            nsdRegistrationListener?.let {
                nsdManager?.unregisterService(it)
            }
            nsdRegistrationListener = null
            nsdManager = null

            jmDNS?.unregisterAllServices()
            jmDNS?.close()
            jmDNS = null
            airPlayServiceInfo = null
            isRegistered = false
            Log.i(TAG, "Bonjour servers stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Bonjour", e)
        }
    }
}
