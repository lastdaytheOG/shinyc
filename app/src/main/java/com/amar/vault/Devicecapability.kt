package com.amar.vault

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * Detects device hardware capabilities and selects the optimal
 * QWEN model variant for current conditions.
 *
 * Snapdragon 695 (Adreno 619) specific tuning:
 * - 4 big cores (Cortex-A78) + 4 little cores (Cortex-A55)
 * - Vulkan 1.1 support, subgroup ops available
 * - 8GB RAM total, ~3-4GB free typical
 * - Thermal throttle starts at ~42°C skin temp
 */
object DeviceCapability {

    private const val TAG = "DeviceCapability"

    enum class ChipTier { FLAGSHIP, MID, LOW }

    enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

    data class ModelConfig(
        val fileName: String,        // GGUF filename in app assets/files
        val contextSize: Int,        // n_ctx for llama.cpp
        val maxGenTokens: Int,       // max tokens to generate
        val nGpuLayers: Int,         // layers to offload to Vulkan
        val nThreads: Int,           // CPU threads for non-GPU work
        val displayName: String,     // For UI display
    )

    data class DeviceState(
        val chipTier: ChipTier,
        val freeRamGB: Float,
        val totalRamGB: Float,
        val thermalStatus: ThermalStatus,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val bigCoreCount: Int,
    )

    data class DeviceProfile(
        val ramGb: Float,
        val cpuTier: ChipTier,
        val abi: String,
        val freeStorageGb: Float,
        val thermalLevel: ThermalStatus,
        val androidVersion: Int,
        val supported: Boolean,
        val bigCoreCount: Int
    )

    /**
     * Reads static and environmental hardware specs for Onboarding checks.
     */
    fun getDeviceProfile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalGB = memInfo.totalMem / (1024f * 1024f * 1024f)

        val stat = StatFs(context.filesDir.path)
        val freeStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024f * 1024f * 1024f)

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        val supported = Build.SUPPORTED_ABIS.contains("arm64-v8a")

        return DeviceProfile(
            ramGb = totalGB,
            cpuTier = detectChipTier(),
            abi = abi,
            freeStorageGb = freeStorageGb,
            thermalLevel = getThermalStatus(context),
            androidVersion = Build.VERSION.SDK_INT,
            supported = supported,
            bigCoreCount = getBigCoreCount()
        )
    }

    /**
     * Reads current device state — fast, can be called before every query.
     */
    fun getDeviceState(context: Context): DeviceState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val freeGB = memInfo.availMem / (1024f * 1024f * 1024f)
        val totalGB = memInfo.totalMem / (1024f * 1024f * 1024f)

        return DeviceState(
            chipTier = detectChipTier(),
            freeRamGB = freeGB,
            totalRamGB = totalGB,
            thermalStatus = getThermalStatus(context),
            batteryPercent = getBatteryPercent(context),
            isCharging = isCharging(context),
            bigCoreCount = getBigCoreCount(),
        )
    }

    /**
     * Selects the optimal model config for current device conditions.
     * Called before each inference or when conditions change.
     */
    fun selectModel(state: DeviceState): ModelConfig {
        // ── EMERGENCY: very low resources ────────────────────────
        if (state.freeRamGB < 1.0f || (state.batteryPercent < 10 && !state.isCharging)) {
            Log.d(TAG, "EMERGENCY lane: freeRAM=${state.freeRamGB}GB battery=${state.batteryPercent}%")
            return ModelConfig(
                fileName = "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf",
                contextSize = 1024,
                maxGenTokens = 100,
                nGpuLayers = 0,  // CPU only to save power
                nThreads = 2,
                displayName = "Qwen 1.5B Minimal",
            )
        }

        // ── THERMAL CRITICAL ─────────────────────────────────────
        if (state.thermalStatus >= ThermalStatus.SEVERE) {
            Log.d(TAG, "THERMAL lane: status=${state.thermalStatus}")
            return ModelConfig(
                fileName = "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf",
                contextSize = 1024,
                maxGenTokens = 100,
                nGpuLayers = 0,
                nThreads = 2,
                displayName = "Qwen 1.5B Cool-down",
            )
        }

        // ── FLAGSHIP + PLENTY RAM ────────────────────────────────
        if (state.chipTier == ChipTier.FLAGSHIP && state.freeRamGB >= 4.0f
            && state.thermalStatus < ThermalStatus.MODERATE) {
            return ModelConfig(
                fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
                contextSize = 8192,
                maxGenTokens = 500,
                nGpuLayers = 0,  // all layers on GPU
                nThreads = 4,
                displayName = "Qwen 3B Full Quality",
            )
        }

        // ── FLAGSHIP CONSTRAINED ─────────────────────────────────
        if (state.chipTier == ChipTier.FLAGSHIP && state.freeRamGB >= 2.5f) {
            return ModelConfig(
                fileName = "qwen2.5-3b-instruct-iq3_xxs.gguf",
                contextSize = 4096,
                maxGenTokens = 300,
                nGpuLayers = 0,
                nThreads = 4,
                displayName = "Qwen 3B Compact",
            )
        }

        // ── MID-RANGE GOOD CONDITIONS (Snapdragon 695 lands here) ─
        if (state.chipTier == ChipTier.MID && state.freeRamGB >= 2.0f
            && state.thermalStatus < ThermalStatus.MODERATE) {
            Log.d(TAG, "MID OVERDRIVE lane: SD695 — IQ3 fast mode")
            return ModelConfig(
                fileName = "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf",
                contextSize = 1024,
                maxGenTokens = 100,
                nGpuLayers = 0,
                nThreads = 4,
                displayName = "Qwen 1.5B Fast",
            )
        }

        // ── MID-RANGE STANDARD ───────────────────────────────────
        if (state.chipTier == ChipTier.MID && state.freeRamGB >= 1.2f) {
            return ModelConfig(
                fileName = "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf",
                contextSize = 2048,
                maxGenTokens = 200,
                nGpuLayers = 0,
                nThreads = state.bigCoreCount.coerceIn(2, 4),
                displayName = "Qwen 1.5B Standard",
            )
        }

        // ── FALLBACK ─────────────────────────────────────────────
        Log.d(TAG, "FALLBACK lane")
        return ModelConfig(
            fileName = "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf",
            contextSize = 1024,
            maxGenTokens = 100,
            nGpuLayers = 0,
            nThreads = 2,
            displayName = "Qwen 1.5B Safe",
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Hardware detection
    // ═══════════════════════════════════════════════════════════════

    private fun detectChipTier(): ChipTier {
        val board = Build.BOARD.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.lowercase()
        } else {
            ""
        }

        // Flagship indicators
        val flagshipKeywords = listOf(
            "sm8", "taro", "cape", "kalama", "pineapple",  // Snapdragon 8 series
            "mt6985", "mt6989", "mt6991",                   // Dimensity 9000 series
            "tensor", "gs",                                  // Google Tensor
            "exynos2", "exynos990",                          // Samsung flagship
        )
        if (flagshipKeywords.any { soc.contains(it) || board.contains(it) || hardware.contains(it) }) {
            return ChipTier.FLAGSHIP
        }

        // Mid-range indicators
        val midKeywords = listOf(
            "sm6", "sm7",                                    // Snapdragon 6/7 series
            "holi", "blair", "yupik", "kodiak",             // SD695, SD778G, SD7 Gen 1
            "mt6877", "mt6893", "mt6886",                    // Dimensity 1000/1200
            "mt6855", "mt6878",                              // Dimensity 7000 series
            "exynos1",                                       // Samsung mid-range
        )
        if (midKeywords.any { soc.contains(it) || board.contains(it) || hardware.contains(it) }) {
            return ChipTier.MID
        }

        // Default to LOW for unknown chipsets
        return ChipTier.LOW
    }

    private fun getThermalStatus(context: Context): ThermalStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                else -> ThermalStatus.NONE
            }
        }
        // Fallback: read thermal zone (less reliable)
        return try {
            val temp = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toInt()
            val celsius = temp / 1000
            when {
                celsius >= 50 -> ThermalStatus.CRITICAL
                celsius >= 45 -> ThermalStatus.SEVERE
                celsius >= 40 -> ThermalStatus.MODERATE
                celsius >= 35 -> ThermalStatus.LIGHT
                else -> ThermalStatus.NONE
            }
        } catch (e: Exception) {
            ThermalStatus.NONE
        }
    }

    private fun getBatteryPercent(context: Context): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter) ?: return 100
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    private fun isCharging(context: Context): Boolean {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter) ?: return false
        val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Count big cores by reading max CPU frequencies.
     * Big cores typically run at 2GHz+, little cores at 1.8GHz or less.
     * Snapdragon 695: 4 big (A78 @2.2GHz) + 4 little (A55 @1.8GHz)
     */
    private fun getBigCoreCount(): Int {
        return try {
            val cpuCount = Runtime.getRuntime().availableProcessors()
            var bigCores = 0
            for (i in 0 until cpuCount) {
                val maxFreq = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (maxFreq.exists()) {
                    val freq = maxFreq.readText().trim().toLongOrNull() ?: 0
                    if (freq >= 2000000) bigCores++ // 2GHz+ = big core
                }
            }
            bigCores.coerceAtLeast(2) // Minimum 2
        } catch (e: Exception) {
            4 // Default assumption
        }
    }
}