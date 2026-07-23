package com.simscheduler.util

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

data class DetectedSim(
    val slot: Int,              // Physical slot: 0 or 1
    val carrierName: String,    // "Jio", "LycaMobile" — exact name shown in Settings
    val phoneNumber: String,    // Phone number if available
    val subscriptionId: Int,    // Android subscription ID
    val mcc: String,            // Mobile Country Code
    val mnc: String             // Mobile Network Code
)

object SimDetector {

    private const val TAG = "SimDetector"

    /**
     * Automatically detects all active SIM cards and their carrier names.
     * The carrierName is the EXACT name shown in Android Settings SIM page.
     * This is what SimAccessibilityService will search for on screen.
     */
    fun detectSims(context: Context): List<DetectedSim> {
        val result = mutableListOf<DetectedSim>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            Log.w(TAG, "Android version too old for SubscriptionManager")
            return fallbackDetection(context)
        }

        try {
            val subscriptionManager = context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE
            ) as SubscriptionManager

            val activeSubs = subscriptionManager.activeSubscriptionInfoList
            if (activeSubs.isNullOrEmpty()) {
                Log.w(TAG, "No active subscriptions found")
                return fallbackDetection(context)
            }

            Log.d(TAG, "Found ${activeSubs.size} active SIM(s)")

            for (sub in activeSubs) {
                // Try multiple sources to get the best carrier name
                val carrierName = getBestCarrierName(context, sub.subscriptionId, sub.displayName?.toString())

                val sim = DetectedSim(
                    slot = sub.simSlotIndex,
                    carrierName = carrierName,
                    phoneNumber = sub.number?.trim() ?: "",
                    subscriptionId = sub.subscriptionId,
                    mcc = sub.mccString ?: "",
                    mnc = sub.mncString ?: ""
                )

                result.add(sim)
                Log.d(TAG, "Detected SIM slot=${sim.slot} carrier='${sim.carrierName}' number='${sim.phoneNumber}'")
            }

            // Sort by slot index so SIM 1 (slot 0) is always first
            result.sortBy { it.slot }

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            return fallbackDetection(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting SIMs: ${e.message}")
            return fallbackDetection(context)
        }

        return result
    }

    /**
     * Gets the best possible carrier name from multiple sources.
     * Priority: Network operator name > SIM operator name > Display name > Fallback
     */
    private fun getBestCarrierName(
        context: Context,
        subscriptionId: Int,
        displayName: String?
    ): String {
        val candidates = mutableListOf<String>()

        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val tm = telephonyManager.createForSubscriptionId(subscriptionId)

                // Source 1: Network operator name (what Settings shows as carrier)
                tm.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    candidates.add(it)
                    Log.d(TAG, "  networkOperatorName: '$it'")
                }

                // Source 2: SIM operator name (from SIM card itself)
                tm.simOperatorName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    candidates.add(it)
                    Log.d(TAG, "  simOperatorName: '$it'")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelephonyManager error: ${e.message}")
        }

        // Source 3: Display name set by user/carrier
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            candidates.add(it)
            Log.d(TAG, "  displayName: '$it'")
        }

        // Pick the best name — prefer network operator name as it matches Settings
        val best = candidates.firstOrNull { it.isNotEmpty() } ?: "SIM $subscriptionId"
        Log.d(TAG, "  Best carrier name chosen: '$best'")
        return best
    }

    /**
     * Fallback when permissions not granted or old Android version
     */
    private fun fallbackDetection(context: Context): List<DetectedSim> {
        Log.w(TAG, "Using fallback SIM detection")
        return listOf(
            DetectedSim(0, "SIM 1", "", 0, "", ""),
            DetectedSim(1, "SIM 2", "", 1, "", "")
        )
    }

    /**
     * Refreshes SIM detection — call this when SIM state changes
     */
    fun getSimBySlot(context: Context, slot: Int): DetectedSim? {
        return detectSims(context).firstOrNull { it.slot == slot }
    }
}
