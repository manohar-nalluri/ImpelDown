package com.impel.touchlock

import android.app.admin.DeviceAdminReceiver

/**
 * Required by [android.app.admin.DevicePolicyManager] so that `lockNow()` is allowed.
 * Declared in the manifest with `android.app.device_admin` metadata (force-lock policy only).
 */
class LockDeviceAdminReceiver : DeviceAdminReceiver()
