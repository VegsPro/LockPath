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
 * Unlock flow:
 * 1. User enters correct password → [markUnlocked] is called.
 * 2. Short grace period (~2.5 s) so LockActivity can close without re-locking.
 * 3. While the protected app stays in the foreground, it remains unlocked.
 * 4. As soon as ANY other package becomes foreground (Home, another app, etc.),
 *    the unlocked session for the previous package is cleared and cooldown starts.
 * 5. Next time the protected app comes to foreground after cooldown → lock again.
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        /** package → timestamp when it was unlocked */
        private val unlockedAt = ConcurrentHashMap<String, Long>()

        /** Grace after unlock so finishing LockActivity doesn't immediately re-lock */
        private const val UNLOCK_GRACE_MS = 2500L

        fun markUnlocked(packageName: String) {
            if (packageName.isNotBlank()) {
                unlockedAt[packageName] = System.currentTimeMillis()
            }
        }

        fun clearUnlocked(packageName: String) {
            unlockedAt.remove(packageName)
        }

        private fun isInGraceOrSession(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
            val t = unlockedAt[packageName] ?: return false
            // Still within grace → always treat as unlocked
            if (now - t < UNLOCK_GRACE_MS) return true
            // After grace: only unlocked if entry still exists (cleared on leave)
            return unlockedAt.containsKey(packageName)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Last time a protected package left the foreground. */
    private val lastBackgroundTime = ConcurrentHashMap<String, Long>()

    private var lastForegroundPackage: String? = null
    private var lastLockShownTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank()) return

        val now = System.currentTimeMillis()
        val previous = lastForegroundPackage

        // Our own UI — ignore for locking, but still track leave of previous app
        val isOwn = packageName == this.packageName

        if (packageName != previous) {
            lastForegroundPackage = packageName

            // Something else came to foreground → previous app left
            if (previous != null && previous != this.packageName) {
                // End unlocked session for the app that left
                unlockedAt.remove(previous)
                scope.launch {
                    val repo = (application as LockPathApp).repository
                    val config = repo.getAppConfig(previous)
                    if (config.enabled) {
                        lastBackgroundTime[previous] = now
                    }
                }
            }
        }

        if (isOwn) return

        // Already unlocked for this session (or still in grace) → allow
        if (isInGraceOrSession(packageName, now)) return

        // Debounce rapid lock launches
        if (now - lastLockShownTime < 800) return

        scope.launch {
            val repo = (application as LockPathApp).repository
            val config = repo.getAppConfig(packageName)
            if (!config.enabled) return@launch

            // Re-check after suspend (user might have unlocked)
            if (isInGraceOrSession(packageName)) return@launch

            val settings = repo.getSettings()
            val cooldownMs = settings.cooldownSeconds * 1000L
            val bgTime = lastBackgroundTime[packageName] ?: 0L
            val elapsed = now - bgTime

            val shouldLock = when {
                // First open in this process (never backgrounded)
                bgTime == 0L -> true
                // Cooldown 0 → lock every time the app returns after leaving
                cooldownMs == 0L -> true
                // Still inside cooldown window
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
