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

/**
 * Locks protected apps when they come to the foreground.
 *
 * After a successful unlock the package is marked as "session unlocked".
 * It will NOT be locked again until it actually leaves the foreground
 * (goes to background / another app is opened). When it leaves, the
 * cooldown timer starts. Next time it returns after the cooldown —
 * lock is shown again.
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        /** Packages that were successfully unlocked and are still in the current session. */
        private val sessionUnlocked = ConcurrentHashMap.newKeySet<String>()

        /** Call this from LockActivity after correct password. */
        fun markUnlocked(packageName: String) {
            if (packageName.isNotBlank()) {
                sessionUnlocked.add(packageName)
            }
        }

        fun clearUnlocked(packageName: String) {
            sessionUnlocked.remove(packageName)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Last time a protected package left the foreground. */
    private val lastBackgroundTime = ConcurrentHashMap<String, Long>()

    private var lastForegroundPackage: String? = null
    private var lastLockShownTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        // Never react to our own windows
        if (packageName == this.packageName) return

        val now = System.currentTimeMillis()
        val previous = lastForegroundPackage

        // Update foreground tracking
        if (packageName != previous) {
            lastForegroundPackage = packageName

            // Previous app left the foreground → end its unlocked session and start cooldown clock
            if (previous != null && previous != this.packageName) {
                sessionUnlocked.remove(previous)
                scope.launch {
                    val repo = (application as LockPathApp).repository
                    val config = repo.getAppConfig(previous)
                    if (config.enabled) {
                        lastBackgroundTime[previous] = now
                    }
                }
            }
        }

        // Debounce
        if (now - lastLockShownTime < 700) return

        // Already unlocked for this session → do nothing
        if (sessionUnlocked.contains(packageName)) return

        scope.launch {
            val repo = (application as LockPathApp).repository
            val config = repo.getAppConfig(packageName)
            if (!config.enabled) return@launch

            // Race: user might have unlocked while we were suspended
            if (sessionUnlocked.contains(packageName)) return@launch

            val settings = repo.getSettings()
            val cooldownMs = settings.cooldownSeconds * 1000L
            val bgTime = lastBackgroundTime[packageName] ?: 0L
            val elapsed = now - bgTime

            val shouldLock = when {
                // Never been in background in this process → first open, always lock
                bgTime == 0L -> true
                // Cooldown 0 → always lock when coming to foreground (after session ended)
                cooldownMs == 0L -> true
                // Cooldown not yet expired
                elapsed < cooldownMs -> false
                else -> true
            }

            if (shouldLock) {
                lastLockShownTime = System.currentTimeMillis()
                val intent = Intent(this@AppLockAccessibilityService, LockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(LockActivity.EXTRA_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        }
    }

    override fun onInterrupt() {}
}
