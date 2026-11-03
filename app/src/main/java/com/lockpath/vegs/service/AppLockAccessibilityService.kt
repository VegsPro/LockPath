package com.lockpath.vegs.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.lockpath.vegs.LockPathApp
import com.lockpath.vegs.ui.LockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        /** package → time when user successfully unlocked it */
        private val unlockedAt = ConcurrentHashMap<String, Long>()

        /** After unlock, ignore re-lock for this long (LockActivity closing) */
        private const val UNLOCK_GRACE_MS = 2000L

        fun markUnlocked(packageName: String) {
            if (packageName.isNotBlank()) {
                unlockedAt[packageName] = System.currentTimeMillis()
            }
        }

        fun clearUnlocked(packageName: String) {
            unlockedAt.remove(packageName)
        }

        fun clearAllUnlocked() {
            unlockedAt.clear()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val lastBackgroundTime = ConcurrentHashMap<String, Long>()
    private var lastForegroundPackage: String? = null
    private var lastLockShownTime = 0L

    /** Periodically verify that unlocked apps are still on top */
    private val watchDog = object : Runnable {
        override fun run() {
            try {
                checkUnlockedStillForeground()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 800)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(watchDog)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchDog)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val packageName = event.packageName?.toString()
        val now = System.currentTimeMillis()

        // Determine real top package (more reliable than event alone)
        val topPackage = resolveTopPackage() ?: packageName ?: return
        if (topPackage.isBlank()) return

        val isOwn = topPackage == this.packageName

        // If top package changed away from a previously unlocked app → end session
        if (topPackage != lastForegroundPackage) {
            val previous = lastForegroundPackage
            lastForegroundPackage = topPackage

            if (previous != null && previous != this.packageName && previous != topPackage) {
                endSession(previous, now)
            }
        }

        // Any non-own package that is not in the unlocked set means other UI is showing
        // → clear sessions for apps that are no longer top
        if (!isOwn) {
            for (pkg in unlockedAt.keys().toList()) {
                if (pkg != topPackage) {
                    endSession(pkg, now)
                }
            }
        }

        if (isOwn) return

        // Still in grace / active unlock for this package
        if (isUnlocked(topPackage, now)) return

        if (now - lastLockShownTime < 900) return

        scope.launch {
            val repo = (application as LockPathApp).repository
            val config = repo.getAppConfig(topPackage)
            if (!config.enabled) return@launch
            if (isUnlocked(topPackage)) return@launch

            val settings = repo.getSettings()
            val cooldownMs = settings.cooldownSeconds * 1000L
            val bgTime = lastBackgroundTime[topPackage] ?: 0L
            val elapsed = now - bgTime

            val shouldLock = when {
                bgTime == 0L -> true
                cooldownMs == 0L -> true
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
                    putExtra(LockActivity.EXTRA_PACKAGE, topPackage)
                }
                startActivity(intent)
            }
        }
    }

    private fun endSession(packageName: String, now: Long = System.currentTimeMillis()) {
        unlockedAt.remove(packageName)
        lastBackgroundTime[packageName] = now
    }

    private fun isUnlocked(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        val t = unlockedAt[packageName] ?: return false
        if (now - t < UNLOCK_GRACE_MS) return true
        return unlockedAt.containsKey(packageName)
    }

    /**
     * Walk active windows and pick the most likely top application package.
     * Ignores our own package when LockActivity is showing.
     */
    private fun resolveTopPackage(): String? {
        try {
            val windows = windows ?: return rootInActiveWindow?.packageName?.toString()
            var fallback: String? = null
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (pkg == packageName) continue
                if (w.isActive || w.isFocused) return pkg
                if (fallback == null) fallback = pkg
            }
            if (fallback != null) return fallback
            return rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            return try {
                rootInActiveWindow?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Watchdog: if unlocked app is no longer on top, end its session */
    private fun checkUnlockedStillForeground() {
        if (unlockedAt.isEmpty()) return
        val top = resolveTopPackage() ?: return
        val now = System.currentTimeMillis()
        for (pkg in unlockedAt.keys().toList()) {
            val unlockTime = unlockedAt[pkg] ?: continue
            if (now - unlockTime < UNLOCK_GRACE_MS) continue
            if (pkg != top && top != packageName) {
                endSession(pkg, now)
            }
        }
    }

    override fun onInterrupt() {}
}
