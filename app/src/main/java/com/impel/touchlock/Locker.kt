package com.impel.touchlock

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Turns "lock the phone now" into one call, hiding which of the two supported mechanisms is
 * configured. Both are permission-light; neither needs root.
 */
object Locker {

    fun adminComponent(ctx: Context): ComponentName =
        ComponentName(ctx.applicationContext, LockDeviceAdminReceiver::class.java)

    fun isAdminActive(ctx: Context): Boolean =
        ctx.getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(adminComponent(ctx))

    fun isAccessibilityConnected(): Boolean = LockAccessibilityService.instance != null

    /** True when the user enabled our accessibility service, even if the process was restarted. */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx.applicationContext, LockAccessibilityService::class.java)
        val flatLong = expected.flattenToString()
        val flatShort = expected.flattenToShortString()
        val enabled = try {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Throwable) {
            null
        } ?: return false
        return enabled.split(':').any {
            it.equals(flatLong, ignoreCase = true) || it.equals(flatShort, ignoreCase = true)
        }
    }

    /**
     * @return true when the screen was actually locked.
     */
    fun lock(ctx: Context): Boolean {
        if (Prefs.lockMethod(ctx) == Prefs.LOCK_METHOD_ACCESSIBILITY) {
            val service = LockAccessibilityService.instance
            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // GLOBAL_ACTION_LOCK_SCREEN exists from Android 9.
                if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
                    return true
                }
            }
            // Fall through to device admin when accessibility is not connected (or on Android 8).
        }

        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        return try {
            if (dpm.isAdminActive(adminComponent(ctx))) {
                dpm.lockNow()
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }
}
