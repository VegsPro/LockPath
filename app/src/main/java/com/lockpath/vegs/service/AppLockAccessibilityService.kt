package com.lockpath.vegs.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Detects protected apps and shows/hides an OVERLAY lock on top of them.
 * Overlay is hidden when the app is minimized and shown again when it returns.
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        private val sessionUnlocked = ConcurrentHashMap.newKeySet<String>()
        private val unlockGraceUntil = ConcurrentHashMap<String, Long>()
        private const val UNLOCK_GRACE_MS = 1800L

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
    private var lastUsageCheck = 0L

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

    private val launcherPrefixes = listOf(
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.oppo.launcher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.transsion.launcher",
        "com.nothing.launcher",
        "com.bbk.launcher",
        "com.vivo.launcher",
        "com.realme.launcher",
        "com.coloros.launcher",
        "com.oneplus.launcher",
        "com.asus.launcher"
    )

    private val watchDog = object : Runnable {
        override fun run() {
            try { pollForeground() } catch (_: Exception) {}
            handler.postDelayed(this, 600)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayLockManager(this)
        handler.post(watchDog)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchDog)
        try { overlay.hide() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val eventPkg = event.packageName?.toString()
        val top = resolveTopPackage() ?: eventPkg
        if (top != null) handleTopPackage(top)
    }

    private fun pollForeground() {
        resolveTopPackage()?.let { handleTopPackage(it) }
        val now = System.currentTimeMillis()
        if (now - lastUsageCheck > 500) {
            lastUsageCheck = now
            queryUsageForeground()?.let { handleTopPackage(it) }
        }
    }

    private fun handleTopPackage(topPackage: String) {
        if (topPackage.isBlank()) return
        val now = System.currentTimeMillis()
        if (isNeverLock(topPackage)) return

        val isLauncher = isLauncher(topPackage)
        val isOwn = topPackage == packageName
        val previous = lastTopApp

        // --- LEAVE ---
        if (previous != null && previous != topPackage && previous != packageName && !isNeverLock(previous)) {
            sessionUnlocked.remove(previous)
            unlockGraceUntil.remove(previous)
            lastLeftTime[previous] = now
            // Hide overlay when leaving the protected app
            overlay.hideIfPackage(previous)
        }

        if (isLauncher) {
            // On home: clear all sessions and ALWAYS hide overlay
            for (pkg in sessionUnlocked.toList()) {
                sessionUnlocked.remove(pkg)
                unlockGraceUntil.remove(pkg)
                lastLeftTime[pkg] = now
            }
            overlay.forceHide()
            lastTopApp = topPackage
            return
        }

        // If overlay is showing but top package is no longer that app → hide
        val shown = overlay.shownPackage
        if (overlay.isShowing && shown != null && shown != topPackage && !isOwn) {
            sessionUnlocked.remove(shown)
            unlockGraceUntil.remove(shown)
            lastLeftTime[shown] = now
            overlay.forceHide()
        }

        if (topPackage != previous) lastTopApp = topPackage
        if (isOwn) return

        // Already unlocked this session → ensure overlay hidden
        if (isSessionUnlocked(topPackage, now)) {
            overlay.hideIfPackage(topPackage)
            return
        }

        if (now - lastLockShownTime < 700) return

        scope.launch {
            tryShowLock(topPackage, now)
        }
    }

    private suspend fun tryShowLock(topPackage: String, now: Long) {
        val repo = (application as LockPathApp).repository
        val config = repo.getAppConfig(topPackage)
        if (!config.enabled) {
            overlay.hideIfPackage(topPackage)
            return
        }
        if (isSessionUnlocked(topPackage)) {
            overlay.hideIfPackage(topPackage)
            return
        }

        val settings = repo.getSettings()
        val cooldownMs = settings.cooldownSeconds * 1000L
        val leftAt = lastLeftTime[topPackage] ?: 0L
        val elapsed = now - leftAt

        val shouldLock = when {
            leftAt == 0L -> true
            cooldownMs == 0L -> true
            elapsed >= cooldownMs -> true
            else -> false
        }

        if (!shouldLock) {
            overlay.hideIfPackage(topPackage)
            return
        }

        // Need overlay permission
        if (!Settings.canDrawOverlays(this)) {
            // Fallback: cannot show overlay — user must grant permission
            return
        }

        lastLockShownTime = System.currentTimeMillis()
        overlay.show(topPackage)
    }

    private fun isSessionUnlocked(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        val grace = unlockGraceUntil[packageName] ?: 0L
        if (now < grace) return true
        return sessionUnlocked.contains(packageName)
    }

    private fun isNeverLock(pkg: String) = neverLockPrefixes.any { pkg.startsWith(it) }

    private fun isLauncher(pkg: String) =
        launcherPrefixes.any { pkg.startsWith(it) } || pkg.contains("launcher", ignoreCase = true)

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
            return rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            return try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        }
    }

    private fun queryUsageForeground(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 4000, end) ?: return null
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
