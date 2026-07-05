package com.cocog.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * The onboarding gate (D18): the capture service may run only when RECORD_AUDIO is
 * granted AND the app is exempt from battery optimizations. Anything less is
 * "half-permissioned" and must be refused.
 */
object Permissions {

    fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasUnrestrictedBattery(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** True only when fully permissioned. */
    fun hasAll(context: Context): Boolean =
        hasRecordAudio(context) && hasUnrestrictedBattery(context)
}
