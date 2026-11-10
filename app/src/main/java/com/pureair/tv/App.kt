package com.pureair.tv

import android.app.Application
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {

    companion object {
        const val TAG = "PureAirTV"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSecurityProvider()
        Log.i(TAG, "PureAir-TV Application Initialized.")
    }

    /**
     * 注册 BouncyCastle 安全提供程序，支持高强度加解密套件
     */
    private fun initSecurityProvider() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (provider == null) {
            Security.addProvider(BouncyCastleProvider())
            Log.d(TAG, "BouncyCastle provider added.")
        }
    }
}
