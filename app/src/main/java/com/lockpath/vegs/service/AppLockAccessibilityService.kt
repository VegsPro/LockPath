package com.lockpath.vegs.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
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

/**
 * App lock via Accessibility + optional UsageStats.
 *
 * Password is required when a protected app becomes foreground AFTER it was left.
 * While the user stays inside the same app — never re-lock.
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        private val sessionUnlocked = ConcurrentHashMap.newKeySet<String>()
        private val unlockGraceUntil = ConcurrentHashMap<String, Long>()
        private const val UNLOCK_GRACE_MS = 2000L

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

    private val lastLeftTime = ConcurrentHashMap<String, Long>()
    private var lastTopApp: String? = null
    private var lastLockShownTime = 0L
    private var lastUsageCheck = 0L

    /** Packages that must NEVER trigger a lock screen (IME, system UI chrome). */
    private val neverLockPrefixes = listOf(
        "com.android.systemui",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.github.inputmethod",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.intentresolver",
        "com.android.bluetooth",
        "com.android.phone",
        "com.android.server.telecom",
        "android"
    )

    /** Home / launcher packages — seeing these means user LEFT the previous app. */
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
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
        "com.bbk.launcher",
        "com.vivo.launcher",
        "com.realme.launcher",
        "com.coloros.launcher",
        "com.oneplus.launcher",
        "com.asus.launcher",
        "com.lge.launcher",
        "com.sonyericsson.home",
        "com.android.launcher2"
    )

    private val watchDog = object : Runnable {
        override fun run() {
            try {
                pollForeground()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 700)
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
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val eventPkg = event.packageName?.toString()
        val top = resolveTopPackage() ?: eventPkg
        if (top != null) {
            handleTopPackage(top)
        }
    }

    private fun pollForeground() {
        // Accessibility windows
        resolveTopPackage()?.let { handleTopPackage(it) }
        // UsageStats as second source (works better on some OEMs when going Home)
        val now = System.currentTimeMillis()
        if (now - lastUsageCheck > 600) {
            lastUsageCheck = now
            queryUsageForeground()?.let { handleTopPackage(it) }
        }
    }

    private fun handleTopPackage(topPackage: String) {
        if (topPackage.isBlank()) return
        val now = System.currentTimeMillis()

        // IME / pure system chrome — ignore completely
        if (isNeverLock(topPackage)) return

        val isLauncher = isLauncher(topPackage)
        val isOwn = topPackage == packageName
        val previous = lastTopApp

        // --- LEAVE DETECTION ---
        // User went to launcher / another app / our lock screen → previous app left
        if (previous != null &&
            previous != topPackage &&
            previous != packageName &&
            !isNeverLock(previous)
        ) {
            // End unlock session for the app that left
            sessionUnlocked.remove(previous)
            unlockGraceUntil.remove(previous)
            lastLeftTime[previous] = now
        }

        // If we see launcher — force-clear ALL sessions (user is on home screen)
        if (isLauncher) {
            for (pkg in sessionUnlocked.toList()) {
                sessionUnlocked.remove(pkg)
                unlockGraceUntil.remove(pkg)
                lastLeftTime[pkg] = now
            }
            lastTopApp = topPackage
            return
        }

        if (topPackage != previous) {
            lastTopApp = topPackage
        }

        // Our own UI — do not lock
        if (isOwn) return

        // Still unlocked this session
        if (isSessionUnlocked(topPackage, now)) return

        if (now - lastLockShownTime < 900) return

        scope.launch {
            tryLock(topPackage, now)
        }
    }

    private suspend fun tryLock(topPackage: String, now: Long) {
        val repo = (application as LockPathApp).repository
        val config = repo.getAppConfig(topPackage)
        if (!config.enabled) return
        if (isSessionUnlocked(topPackage)) return

        // Double-check top package hasn't changed
        val currentTop = resolveTopPackage() ?: queryUsageForeground()
        if (currentTop != null && currentTop != topPackage && !isNeverLock(currentTop)) {
            // Top already changed — don't lock stale package
            if (isLauncher(currentTop) || currentTop != topPackage) return
        }

        val settings = repo.getSettings()
        val cooldownMs = settings.cooldownSeconds * 1000L
        val leftAt = lastLeftTime[topPackage] ?: 0L
        val elapsed = now - leftAt

        val shouldLock = when {
            leftAt == 0L -> true                 // never left = first open in process
            cooldownMs == 0L -> true             // always lock on return
            elapsed >= cooldownMs -> true        // cooldown over after leave
            else -> false
        }

        if (!shouldLock) return

        lastLockShownTime = System.currentTimeMillis()
        val intent = Intent(this, LockActivity::class.java).apply {
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

    private fun isSessionUnlocked(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        val grace = unlockGraceUntil[packageName] ?: 0L
        if (now < grace) return true
        return sessionUnlocked.contains(packageName)
    }

    private fun isNeverLock(pkg: String): Boolean =
        neverLockPrefixes.any { pkg.startsWith(it) }

    private fun isLauncher(pkg: String): Boolean =
        launcherPrefixes.any { pkg.startsWith(it) } ||
            pkg.contains("launcher", ignoreCase = true)

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
            if (rootPkg != null && !isNeverLock(rootPkg) && rootPkg != packageName) return rootPkg
            return rootPkg
        } catch (_: Exception) {
            return try {
                rootInActiveWindow?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Secondary foreground detection via UsageStats.
     * Requires PACKAGE_USAGE_STATS (user grants in settings).
     * Especially helpful when Accessibility misses Home press on some OEMs.
     */
    private fun queryUsageForeground(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val end = System.currentTimeMillis()
            val start = end - 5000
            val events = usm.queryEvents(start, end) ?: return null
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
