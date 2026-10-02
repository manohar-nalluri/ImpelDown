package com.impel.touchlock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * *Optional* alternative to device admin, chosen in settings.
 *
 * 1. `GLOBAL_ACTION_LOCK_SCREEN` (Android 9+) locks the phone without device admin.
 * 2. As a bonus, `TYPE_TOUCH_INTERACTION_START` tells the running bubble service that the user
 *    just touched the screen, which is what lets the bubble fade out completely while watching
 *    a video and pop back the moment the screen is touched anywhere.
 *
 * It never reads window content (`canRetrieveWindowContent=false`) and does no work beyond those
 * two callbacks — no polling, no timers.
 */
class LockAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            BubbleService.onUserTouchGlobal()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Live instance, or null when the user has not enabled the service. */
        @Volatile
        var instance: LockAccessibilityService? = null
            private set
    }
}
