package com.audi.rscockpit

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class HomeOverrideService : AccessibilityService() {
    private val stockFytLaunchers = setOf(
        "com.android.launcher3",
        "com.syu.launcher",
        "com.syu.ui",
        "com.fyt.launcher",
        "com.syu.ms"
    )

    private var lastTriggerMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        val isStockHome = pkg in stockFytLaunchers ||
            (pkg.contains("launcher", ignoreCase = true) && pkg != packageName)

        if (isStockHome) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs < 350L) return
            lastTriggerMs = now

            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            try {
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).send()
            } catch (_: Exception) {
                startActivity(intent)
            }
        }
    }

    override fun onInterrupt() {}
}
