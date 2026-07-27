package com.simscheduler.util

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

data class DetectedSim(
    val slot: Int,           // 0 = SIM 1, 1 = SIM 2
    val carrierName: String, // Exact name shown in Settings
    val phoneNumber: String, // Phone number
    val subscriptionId: Int  // Android internal ID
)

object SimDetector {

    private const val TAG = "SimDetector"

    fun detectSims(context: Context): List<DetectedSim> {
        Log.d(TAG, "=== Starting SIM Detection ===")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            Log.w(TAG, "Android too old — using fallback")
            return fallback()
        }

        return try {
            val subscriptionManager = context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE
            ) as SubscriptionManager

            val activeSubs = subscriptionManager.activeSubscriptionInfoList

            if (activeSubs.isNullOrEmpty()) {
                Log.w(TAG, "No active SIMs found — using fallback")
                return fallback()
            }

            Log.d(TAG, "Total active SIMs found: ${activeSubs.size}")

            val result = activeSubs
                .sortedBy { it.simSlotIndex } // Always sort by slot
                .map { sub -> buildSimInfo(context, sub) }

            result.forEach {
                Log.d(TAG, "✅ Slot ${it.slot}: '${it.carrierName}' | ${it.phoneNumber}")
            }

            result

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            fallback()
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed: ${e.message}")
            fallback()
        }
    }

    // ── Build full SIM info for one subscription ──────────────────────────────
    private fun buildSimInfo(context: Context, sub: SubscriptionInfo): DetectedSim {
        val slot = sub.simSlotIndex
        val subId = sub.subscriptionId
        val phoneNumber = sub.number?.trim() ?: ""

        Log.d(TAG, "--- Reading SIM in slot $slot (subId=$subId) ---")

        // Try every possible source for carrier name
        val carrierName = getBestCarrierName(context, sub)

        Log.d(TAG, "  Final carrier name: '$carrierName'")

        return DetectedSim(
            slot = slot,
            carrierName = carrierName,
            phoneNumber = phoneNumber,
            subscriptionId = subId
        )
    }

    // ── Try multiple sources to get the best carrier name ────────────────────
    private fun getBestCarrierName(context: Context, sub: SubscriptionInfo): String {
        val subId = sub.subscriptionId
        val slot  = sub.simSlotIndex
        val candidates = mutableListOf<Pair<String, String>>() // name → source

        try {
            val telephony = context.getSystemService(
                Context.TELEPHONY_SERVICE
            ) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Create TelephonyManager scoped to THIS specific SIM
                // This is the KEY — without this it always reads SIM 1
                val tmForSim = telephony.createForSubscriptionId(subId)

                // Source 1: Network operator name (what network broadcasts)
                // This is the most likely to match what Settings shows
                tmForSim.networkOperatorName
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { candidates.add(it to "networkOperatorName") }

                // Source 2: SIM operator name (stored on SIM chip)
                tmForSim.simOperatorName
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { candidates.add(it to "simOperatorName") }

                // Source 3: Carrier name (Android 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    tmForSim.simCarrierIdName
                        ?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { candidates.add(it.toString() to "simCarrierIdName") }
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "TelephonyManager error for slot $slot: ${e.message}")
        }

        // Source 4: Display name from SubscriptionInfo
        sub.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { candidates.add(it to "displayName") }

        // Source 5: Carrier name from SubscriptionInfo (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                sub.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { candidates.add(it to "carrierName") }
            } catch (e: Exception) { /* ignore */ }
        }

        // Log all candidates so we can debug
        Log.d(TAG, "  Carrier name candidates for slot $slot:")
        candidates.forEach { (name, source) ->
            Log.d(TAG, "    [$source] → '$name'")
        }

        // Pick the best one
        // Priority: networkOperatorName first (most reliable for Settings matching)
        val best = candidates.firstOrNull { it.first.isNotEmpty() }?.first
            ?: "SIM ${slot + 1}"

        return best
    }

    // ── Fallback when detection fails ─────────────────────────────────────────
    private fun fallback(): List<DetectedSim> {
        Log.w(TAG, "Using fallback SIM names")
        return listOf(
            DetectedSim(0, "SIM 1", "", 0),
            DetectedSim(1, "SIM 2", "", 1)
        )
    }

    // ── Get SIM by slot number ────────────────────────────────────────────────
    fun getSimBySlot(context: Context, slot: Int): DetectedSim? {
        return detectSims(context).firstOrNull { it.slot == slot }
    }

    // ── Get all carrier name candidates for a slot (for diagnostic) ───────────
    fun getAllCandidatesForSlot(context: Context, slot: Int): Map<String, String> {
        val result = mutableMapOf<String, String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return result

        try {
            val sm = context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE
            ) as SubscriptionManager

            val sub = sm.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == slot } ?: return result

            val tm = context.getSystemService(
                Context.TELEPHONY_SERVICE
            ) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val tmSim = tm.createForSubscriptionId(sub.subscriptionId)
                result["networkOperatorName"] = tmSim.networkOperatorName ?: "null"
                result["simOperatorName"]     = tmSim.simOperatorName ?: "null"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    result["simCarrierIdName"] = tmSim.simCarrierIdName?.toString() ?: "null"
                }
            }

            result["displayName"]  = sub.displayName?.toString() ?: "null"
            result["number"]       = sub.number ?: "null"
            result["mcc"]          = sub.mccString ?: "null"
            result["mnc"]          = sub.mncString ?: "null"
            result["subscriptionId"] = sub.subscriptionId.toString()

        } catch (e: Exception) {
            result["error"] = e.message ?: "unknown"
        }

        return result
    }
}
