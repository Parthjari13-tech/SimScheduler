package com.simscheduler.util

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.util.Log
import java.lang.reflect.Method

/**
 * Toggles SIM cards directly via Android's hidden API.
 * No UI changes — works 100% in background like a ghost.
 *
 * Uses reflection to call setSubscriptionEnabled() which is
 * normally restricted to system apps. On Android 9+ this works
 * via the hidden API bridge.
 */
object SimToggleManager {

    private const val TAG = "SimToggle"

    /**
     * Toggle a SIM on or off by slot index.
     * @param simSlot 0 = SIM 1, 1 = SIM 2
     * @param enable  true = turn ON, false = turn OFF
     * @return Result with success flag and message
     */
    fun toggleSim(context: Context, simSlot: Int, enable: Boolean): Result {
        Log.d(TAG, "Toggling slot=$simSlot enable=$enable")

        // Get subscription ID for this slot
        val subId = getSubscriptionId(context, simSlot)
        if (subId == -1) {
            return Result(false, "No SIM found in slot ${simSlot + 1}")
        }

        Log.d(TAG, "Found subId=$subId for slot=$simSlot")

        // Try methods in priority order
        return tryMethod1_setSubscriptionEnabled(context, subId, enable)
            ?: tryMethod2_setUiccApplicationsEnabled(context, subId, enable)
            ?: tryMethod3_setDataEnabled(context, subId, enable)
            ?: Result(false, "Device does not support direct SIM toggle")
    }

    // ── Method 1: setSubscriptionEnabled (Android 9+) ─────────────────────────
    // Most reliable — directly enables/disables the subscription
    private fun tryMethod1_setSubscriptionEnabled(
        context: Context, subId: Int, enable: Boolean
    ): Result? {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Try via reflection — setSubscriptionEnabled is hidden API
                val method: Method = SubscriptionManager::class.java
                    .getDeclaredMethod("setSubscriptionEnabled", Int::class.java, Boolean::class.java)
                method.isAccessible = true
                method.invoke(sm, subId, enable)

                Log.d(TAG, "✅ Method1 success: setSubscriptionEnabled($subId, $enable)")
                Result(true, "SIM ${if (enable) "enabled" else "disabled"} via setSubscriptionEnabled")
            } else null
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Method1 not available: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Method1 failed: ${e.message}")
            null
        }
    }

    // ── Method 2: setUiccApplicationsEnabled (Android 11+) ───────────────────
    // Alternative hidden API for newer devices
    private fun tryMethod2_setUiccApplicationsEnabled(
        context: Context, subId: Int, enable: Boolean
    ): Result? {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager

            val method: Method = SubscriptionManager::class.java
                .getDeclaredMethod("setUiccApplicationsEnabled",
                    Int::class.java, Boolean::class.java)
            method.isAccessible = true
            method.invoke(sm, subId, enable)

            Log.d(TAG, "✅ Method2 success: setUiccApplicationsEnabled($subId, $enable)")
            Result(true, "SIM ${if (enable) "enabled" else "disabled"} via setUiccApplicationsEnabled")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Method2 not available")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Method2 failed: ${e.message}")
            null
        }
    }

    // ── Method 3: ITelephony.setDataEnabled (fallback) ────────────────────────
    // Disables data for the SIM — not full disable but partial
    private fun tryMethod3_setDataEnabled(
        context: Context, subId: Int, enable: Boolean
    ): Result? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
                as android.telephony.TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val tmForSub = tm.createForSubscriptionId(subId)
                val method = tmForSub::class.java
                    .getDeclaredMethod("setDataEnabled", Boolean::class.java)
                method.isAccessible = true
                method.invoke(tmForSub, enable)

                Log.d(TAG, "✅ Method3 success: setDataEnabled($enable)")
                Result(true, "SIM data ${if (enable) "enabled" else "disabled"}")
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Method3 failed: ${e.message}")
            null
        }
    }

    // ── Get subscription ID for a given slot ──────────────────────────────────
    private fun getSubscriptionId(context: Context, simSlot: Int): Int {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager

            val subs = sm.activeSubscriptionInfoList
                ?.sortedBy { it.simSlotIndex }
                ?: return -1

            val sub = subs.getOrNull(simSlot) ?: return -1
            Log.d(TAG, "Slot $simSlot → subId=${sub.subscriptionId}")
            sub.subscriptionId
        } catch (e: Exception) {
            Log.e(TAG, "getSubscriptionId failed: ${e.message}")
            -1
        }
    }

    // ── Check if SIM is currently enabled ─────────────────────────────────────
    fun isSimEnabled(context: Context, simSlot: Int): Boolean {
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager

            val subs = sm.activeSubscriptionInfoList
                ?.sortedBy { it.simSlotIndex }
                ?: return false

            val sub = subs.getOrNull(simSlot) ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val method = SubscriptionManager::class.java
                    .getDeclaredMethod("isSubscriptionEnabled", Int::class.java)
                method.isAccessible = true
                method.invoke(sm, sub.subscriptionId) as Boolean
            } else {
                true // Assume enabled if can't check
            }
        } catch (e: Exception) {
            Log.w(TAG, "isSimEnabled check failed: ${e.message}")
            true
        }
    }

    data class Result(
        val success: Boolean,
        val message: String
    )
}
