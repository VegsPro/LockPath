package com.lockpath.vegs.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.lockpath.vegs.LockPathApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Hard rule for overlay:
 * - Protected app is the top app AND needs lock → SHOW
 * - Anything else (Home, other app, recents, null) → HIDE
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        private val sessionUnlocked = ConcurrentHashMap.newKeySet<String>()
        private val unlockGraceUntil = ConcurrentHashMap<String, Long>()
        private const val UNLOCK_GRACE_MS = 1500L

        fun markUnlocked(packageName: String) {
            if (packageName.isBlank()) return
            sessionUnlocked.add(packageName)
            unlockGraceUntil[packageName] = System.currentTimeMillis() + UNLOCK_GRACE_MS
        }

        fun clearUnlocked(packageName: String) {
            sessionUnlocked.remove(packageName)
            unlockGraceUntil.remove(packageName)
        }

        fun clearAllSessions() {
            sessionUnlocked.clear()
            unlockGraceUntil.clear()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayLockManager

    private val lastLeftTime = ConcurrentHashMap<String, Long>()
    private var lastTopApp: String? = null
    private var lastLockShownTime = 0L

    private val neverLockPrefixes = listOf(
        "com.android.systemui",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.intentresolver",
        "com.android.bluetooth",
        "com.android.phone",
        "com.android.server.telecom",
        "android"
    )

    private val watchDog = object : Runnable {
        override fun run() {
            try {
                syncOverlayWithForeground()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayLockManager(this)
        handler.post(watchDog)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchDog)
        try { overlay.forceHide() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        syncOverlayWithForeground()
    }

    /**
     * Single source of truth: decide show/hide from current top package.
     */
    private fun syncOverlayWithForeground() {
        val top = resolveTopPackage()
            ?: queryUsageForeground()
            ?: run {
                // Unknown top → safe hide
                if (overlay.isShowing) {
                    endSessionForShown()
                    overlay.forceHide()
                }
                return
            }

        if (isNeverLock(top)) {
            // System UI / IME — don't change state based on these alone
            return
        }

        val isOwn = top == packageName
        val isHome = isLauncher(top)
        val previous = lastTopApp

        // Track leave of previous real app
        if (previous != null && previous != top && previous != packageName && !isNeverLock(previous)) {
            sessionUnlocked.remove(previous)
            unlockGraceUntil.remove(previous)
            lastLeftTime[previous] = System.currentTimeMillis()
        }
        if (top != previous && !isNeverLock(top)) {
            lastTopApp = top
        }

        // HARD RULE: not the protected app that needs lock → HIDE
        if (isHome || isOwn) {
            endSessionForShown()
            if (isHome) clearAllSessionsAndMarkLeft()
            overlay.forceHide()
            return
        }

        // If overlay is up for package X but top is Y → hide
        val shown = overlay.shownPackage
        if (overlay.isShowing && shown != null && shown != top) {
            sessionUnlocked.remove(shown)
            unlockGraceUntil.remove(shown)
            lastLeftTime[shown] = System.currentTimeMillis()
            overlay.forceHide()
        }

        // Decide if top needs lock
        scope.launch {
            val repo = (application as LockPathApp).repository
            val config = repo.getAppConfig(top)
            if (!config.enabled) {
                if (overlay.shownPackage == top) overlay.forceHide()
                return@launch
            }

            val now = System.currentTimeMillis()
            if (isSessionUnlocked(top, now)) {
                overlay.forceHide()
                return@launch
            }

            val settings = repo.getSettings()
            val cooldownMs = settings.cooldownSeconds * 1000L
            val leftAt = lastLeftTime[top] ?: 0L
            val shouldLock = when {
                leftAt == 0L -> true
                cooldownMs == 0L -> true
                now - leftAt >= cooldownMs -> true
                else -> false
            }

            if (!shouldLock) {
                if (overlay.shownPackage == top) overlay.forceHide()
                return@launch
            }

            if (!Settings.canDrawOverlays(this@AppLockAccessibilityService)) return@launch

            // Re-check top hasn't changed
            val stillTop = resolveTopPackage() ?: queryUsageForeground()
            if (stillTop != top) return@launch

            if (now - lastLockShownTime < 500 && overlay.isShowing && overlay.shownPackage == top) return@launch
            lastLockShownTime = now
            overlay.show(top)
        }
    }

    private fun endSessionForShown() {
        val shown = overlay.shownPackage ?: return
        sessionUnlocked.remove(shown)
        unlockGraceUntil.remove(shown)
        lastLeftTime[shown] = System.currentTimeMillis()
    }

    private fun clearAllSessionsAndMarkLeft() {
        val now = System.currentTimeMillis()
        for (pkg in sessionUnlocked.toList()) {
            lastLeftTime[pkg] = now
        }
        sessionUnlocked.clear()
        unlockGraceUntil.clear()
    }

    private fun isSessionUnlocked(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        val grace = unlockGraceUntil[packageName] ?: 0L
        if (now < grace) return true
        return sessionUnlocked.contains(packageName)
    }

    private fun isNeverLock(pkg: String) = neverLockPrefixes.any { pkg.startsWith(it) }

    private fun isLauncher(pkg: String) =
        pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("home", ignoreCase = true) && pkg.contains("miui", ignoreCase = true) ||
            pkg == "com.miui.home" ||
            pkg.startsWith("com.android.launcher")

    private fun resolveTopPackage(): String? {
        try {
            val windows = windows
            if (windows != null) {
                var fallback: String? = null
                for (w in windows) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    val pkg = root.packageName?.toString() ?: continue
                    if (isNeverLock(pkg)) continue
                    if (pkg == packageName) continue
                    if (w.isActive || w.isFocused) return pkg
                    if (fallback == null) fallback = pkg
                }
                if (fallback != null) return fallback
            }
            val rootPkg = rootInActiveWindow?.packageName?.toString()
            if (rootPkg != null && !isNeverLock(rootPkg)) return rootPkg
            return rootPkg
        } catch (_: Exception) {
            return try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        }
    }

    private fun queryUsageForeground(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 3000, end) ?: return null
            val event = UsageEvents.Event()
            var lastPkg: String? = null
            var lastTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= lastTs) {
                        lastTs = event.timeStamp
                        lastPkg = event.packageName
                    }
                }
            }
            lastPkg
        } catch (_: Exception) {
            null
        }
    }

    override fun onInterrupt() {}
}
