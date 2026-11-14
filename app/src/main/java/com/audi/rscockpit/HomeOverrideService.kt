package com.audi.rscockpit

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class HomeOverrideService : AccessibilityService() {
    private val stockFytLaunchers = setOf(
        "com.android.launcher3",
        "com.syu.launcher",
        "com.syu.ui",
        "com.fyt.launcher"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg in stockFytLaunchers || (pkg.contains("launcher", ignoreCase = true) && pkg != packageName)) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
        }
    }

    override fun onInterrupt() {}
}
