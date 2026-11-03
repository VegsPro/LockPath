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

/**
 * Password is required only when a protected app comes to foreground
 * AFTER it was actually left (minimized / switched away).
 * While the user stays inside the app — never re-lock, regardless of cooldown.
 */
class AppLockAccessibilityService : AccessibilityService() {

    companion object {
        /** Packages currently allowed without password (unlocked this session). */
        private val sessionUnlocked = ConcurrentHashMap.newKeySet<String>()

        /** Grace so LockActivity.finish() does not immediately re-lock. */
        private const val UNLOCK_GRACE_MS = 2500L
        private val unlockGraceUntil = ConcurrentHashMap<String, Long>()

        fun markUnlocked(packageName: String) {
            if (packageName.isBlank()) return
            sessionUnlocked.add(packageName)
            unlockGraceUntil[packageName] = System.currentTimeMillis() + UNLOCK_GRACE_MS
        }

        fun clearUnlocked(packageName: String) {
            sessionUnlocked.remove(packageName)
            unlockGraceUntil.remove(packageName)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /** Last time the user left this protected package (went to another real app / home). */
    private val lastLeftTime = ConcurrentHashMap<String, Long>()

    private var lastTopApp: String? = null
    private var lastLockShownTime = 0L

    private val systemPackagePrefixes = listOf(
        "com.android.systemui",
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
        "com.blackberry.launcher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.github.inputmethod",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.intentresolver",
        "com.android.bluetooth",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.settings.intelligence",
        "android"
    )

    private val watchDog = object : Runnable {
        override fun run() {
            try {
                onTopChanged(resolveTopPackage(), fromWatchdog = true)
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 1000)
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

        val fromEvent = event.packageName?.toString()
        val top = resolveTopPackage() ?: fromEvent ?: return
        onTopChanged(top, fromWatchdog = false)
    }

    private fun onTopChanged(topPackage: String?, fromWatchdog: Boolean) {
        if (topPackage.isNullOrBlank()) return
        val now = System.currentTimeMillis()

        // Ignore pure system / IME noise for "leave" detection
        if (isIgnoredPackage(topPackage)) {
            return
        }

        val previous = lastTopApp
        if (topPackage == previous) {
            // Same app still on top — never lock while staying inside it
            return
        }

        lastTopApp = topPackage

        // Previous real app left the screen → end its unlock session + start cooldown clock
        if (previous != null &&
            previous != packageName &&
            !isIgnoredPackage(previous)
        ) {
            sessionUnlocked.remove(previous)
            unlockGraceUntil.remove(previous)
            lastLeftTime[previous] = now
        }

        // Our own LockActivity / MainActivity
        if (topPackage == packageName) return

        // Currently unlocked (or still in post-unlock grace)
        if (isSessionUnlocked(topPackage, now)) return

        if (now - lastLockShownTime < 1000) return

        scope.launch {
            val repo = (application as LockPathApp).repository
            val config = repo.getAppConfig(topPackage)
            if (!config.enabled) return@launch
            if (isSessionUnlocked(topPackage)) return@launch

            val settings = repo.getSettings()
            val cooldownMs = settings.cooldownSeconds * 1000L
            val leftAt = lastLeftTime[topPackage] ?: 0L
            val elapsed = now - leftAt

            // Lock only if the app was left before and cooldown has passed (or cooldown=0).
            // First launch in process (never left) → also lock.
            val shouldLock = when {
                leftAt == 0L -> true                          // first open
                cooldownMs == 0L -> true                      // lock every return
                elapsed >= cooldownMs -> true                 // cooldown expired after leave
                else -> false                                // still inside cooldown after leave
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

    private fun isSessionUnlocked(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        val grace = unlockGraceUntil[packageName] ?: 0L
        if (now < grace) return true
        return sessionUnlocked.contains(packageName)
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        if (pkg == packageName) return true
        return systemPackagePrefixes.any { pkg.startsWith(it) }
    }

    private fun resolveTopPackage(): String? {
        try {
            val windows = windows
            if (windows != null) {
                var fallback: String? = null
                for (w in windows) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    val pkg = root.packageName?.toString() ?: continue
                    if (isIgnoredPackage(pkg) && pkg != packageName) continue
                    if (pkg == packageName) continue
                    if (w.isActive || w.isFocused) return pkg
                    if (fallback == null) fallback = pkg
                }
                if (fallback != null) return fallback
            }
            return rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            return try {
                rootInActiveWindow?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onInterrupt() {}
}
