package com.pureair.tv.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 设备硬件与品牌型号信息工具类
 * 动态识别当前电视/手机硬件的品牌和型号，用于界面展示与 AirPlay 广播
 */
object DeviceUtils {

    /**
     * 判断当前设备是否为小米/红米系列 (MIUI / HyperOS)
     */
    fun isXiaomi(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        return manufacturer.contains("xiaomi", ignoreCase = true) ||
               brand.contains("xiaomi", ignoreCase = true) ||
               brand.contains("redmi", ignoreCase = true)
    }

    /**
     * 检查小米 MIUI / HyperOS 特有的「后台弹出界面」权限
     * OpCode: 10021 (MIUI OP_BACKGROUND_START_ACTIVITY)
     * 返回: true=已允许, false=被禁止/未开启, null=非小米系统或不支持通过此方式检测
     */
    fun checkMiuiBackgroundStartAllowed(context: Context): Boolean? {
        return try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return null
            val method = appOpsManager.javaClass.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(
                appOpsManager,
                10021, // OP_BACKGROUND_START_ACTIVITY in MIUI
                Process.myUid(),
                context.packageName
            ) as? Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 动态获取规范的品牌与型号显示名称
     * 示例:
     * - 索尼电视: "Sony XR-65X91J"
     * - 小米手机: "Xiaomi MI 6"
     */
    fun getDeviceDisplayName(): String {
        val manufacturer = formatBrand(Build.MANUFACTURER.orEmpty().trim())
        val model = Build.MODEL.orEmpty().trim()

        return when {
            model.isBlank() && manufacturer.isBlank() -> "Apple投屏"
            manufacturer.isBlank() -> model
            model.isBlank() -> manufacturer
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    private fun formatBrand(raw: String): String {
        if (raw.isBlank()) return raw
        val lower = raw.lowercase()
        return when (lower) {
            "sony" -> "Sony"
            "xiaomi" -> "Xiaomi"
            "huawei" -> "Huawei"
            "honor" -> "Honor"
            "oppo" -> "OPPO"
            "vivo" -> "vivo"
            "oneplus" -> "OnePlus"
            "samsung" -> "Samsung"
            "hisense" -> "海信"
            "tcl" -> "TCL"
            "skyworth" -> "创维"
            "changhong" -> "长虹"
            "konka" -> "康佳"
            "letv" -> "乐视"
            else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
