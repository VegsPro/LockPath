package com.lockpath.vegs.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.lockpath.vegs.LockPathApp
import com.lockpath.vegs.ui.LockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class AppLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Last time a protected package left the foreground (went to background). */
    private val lastBackgroundTime = ConcurrentHashMap<String, Long>()

    /** Package that was in foreground last time we saw a window change. */
    private var lastForegroundPackage: String? = null

    private var lastLockShownTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        // Ignore our own package (LockActivity / MainActivity)
        if (packageName == this.packageName) return

        val now = System.currentTimeMillis()

        // Debounce rapid events
        if (now - lastLockShownTime < 600) return

        val previous = lastForegroundPackage
        lastForegroundPackage = packageName

        // If we left a protected app → record background time
        if (previous != null && previous != packageName && previous != this.packageName) {
            scope.launch {
                val repo = (application as LockPathApp).repository
                val config = repo.getAppConfig(previous)
                if (config.enabled) {
                    lastBackgroundTime[previous] = now
                }
            }
        }

        // Check if the new foreground app needs locking
        scope.launch {
            val repo = (application as LockPathApp).repository
            val config = repo.getAppConfig(packageName)
            if (!config.enabled) return@launch

            val settings = repo.getSettings()
            val cooldownMs = settings.cooldownSeconds * 1000L

            val bgTime = lastBackgroundTime[packageName] ?: 0L
            val elapsed = now - bgTime

            // Lock if:
            // - cooldown is 0 (always lock on foreground)
            // - or never seen in background before (first launch / cold start)
            // - or cooldown expired
            val shouldLock = cooldownMs == 0L || bgTime == 0L || elapsed >= cooldownMs

            if (shouldLock) {
                lastLockShownTime = now
                // Clear background time so next unlock starts fresh
                lastBackgroundTime.remove(packageName)

                val intent = Intent(this@AppLockAccessibilityService, LockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(LockActivity.EXTRA_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        }
    }

    override fun onInterrupt() {}
}
