package com.lockpath.vegs.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.lockpath.vegs.LockPathApp
import com.lockpath.vegs.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OverlayLockManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var overlayView: View? = null
    private var currentPackage: String? = null
    private val passwordBuffer = StringBuilder()
    private var isNumeric = true
    private var verifying = false
    private var supportsLightHaptics = false

    val isShowing: Boolean get() = overlayView != null
    val shownPackage: String? get() = currentPackage

    init {
        supportsLightHaptics = detectLightHaptics()
    }

    fun show(targetPackage: String) {
        if (overlayView != null && currentPackage == targetPackage) return
        hide()
        currentPackage = targetPackage
        passwordBuffer.clear()
        verifying = false

        scope.launch {
            val repo = (context.applicationContext as LockPathApp).repository
            val settings = repo.getSettings()
            isNumeric = settings.isNumericKeyboard
            val langRu = settings.language != "en"

            val view = LayoutInflater.from(context).inflate(R.layout.overlay_lock, null)
            val title = view.findViewById<TextView>(R.id.overlay_title)
            val pkgTv = view.findViewById<TextView>(R.id.overlay_package)
            val errorTv = view.findViewById<TextView>(R.id.overlay_error)
            val dots = view.findViewById<LinearLayout>(R.id.overlay_dots)
            val keypad = view.findViewById<GridLayout>(R.id.overlay_keypad)
            val textInput = view.findViewById<EditText>(R.id.overlay_password_input)
            val unlockBtn = view.findViewById<Button>(R.id.overlay_unlock_btn)
            val card = view.findViewById<View>(R.id.overlay_card)
            val root = view.findViewById<FrameLayout>(R.id.overlay_root)

            title.text = if (langRu) "Введите пароль" else "Enter password"
            pkgTv.text = targetPackage
            errorTv.visibility = View.GONE
            unlockBtn.text = if (langRu) "Разблокировать" else "Unlock"
            textInput.hint = if (langRu) "Пароль" else "Password"

            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f * context.resources.displayMetrics.density
                setColor(0xCC1C1C24.toInt())
                setStroke(
                    (1 * context.resources.displayMetrics.density).toInt(),
                    0x66FFFFFF
                )
            }

            // Close (X) button — top-right of full overlay
            val density = context.resources.displayMetrics.density
            val closeBtn = TextView(context).apply {
                text = "✕"
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                val size = (44 * density).toInt()
                val pad = (12 * density).toInt()
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = (48 * density).toInt()
                    marginEnd = (20 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x55FFFFFF)
                }
                setOnClickListener {
                    lightTick(it)
                    // Hide overlay and go Home so user leaves the protected app
                    hide()
                    AppLockAccessibilityService.clearUnlocked(targetPackage)
                    try {
                        val home = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(home)
                    } catch (_: Exception) {
                    }
                }
            }
            root.addView(closeBtn)

            fun refreshDots() {
                dots.removeAllViews()
                if (!isNumeric) return
                val size = (11 * density).toInt()
                val gap = (7 * density).toInt()
                repeat(6) { i ->
                    val filled = i < passwordBuffer.length
                    val dot = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginStart = gap / 2
                            marginEnd = gap / 2
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (filled) 0xFFE8EAF6.toInt() else 0x44FFFFFF)
                        }
                    }
                    dots.addView(dot)
                }
            }
            refreshDots()

            if (isNumeric) {
                textInput.visibility = View.GONE
                unlockBtn.visibility = View.GONE
                keypad.visibility = View.VISIBLE
                // ✓ instead of C — manual confirm
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✓", "0", "⌫")
                val keySize = (62 * density).toInt()
                val margin = (8 * density).toInt()
                keys.forEach { key ->
                    val btn = TextView(context).apply {
                        text = key
                        textSize = if (key == "✓") 24f else 22f
                        setTextColor(
                            if (key == "✓") 0xFF81C784.toInt() else 0xFFFFFFFF.toInt()
                        )
                        gravity = Gravity.CENTER
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = keySize
                            height = keySize
                            setMargins(margin, margin, margin, margin)
                        }
                        background = context.getDrawable(R.drawable.bg_key_glass)
                        setOnClickListener {
                            lightTick(it)
                            errorTv.visibility = View.GONE
                            when (key) {
                                "✓" -> {
                                    val pass = passwordBuffer.toString()
                                    if (pass.length >= 4) {
                                        tryUnlock(targetPackage, pass, errorTv, showError = true)
                                    } else {
                                        errorTv.text = if (langRu) "Минимум 4 цифры" else "Min 4 digits"
                                        errorTv.visibility = View.VISIBLE
                                    }
                                }
                                "⌫" -> {
                                    if (passwordBuffer.isNotEmpty()) {
                                        passwordBuffer.deleteCharAt(passwordBuffer.length - 1)
                                        refreshDots()
                                    }
                                }
                                else -> {
                                    if (passwordBuffer.length < 12) {
                                        passwordBuffer.append(key)
                                        refreshDots()
                                    }
                                }
                            }
                        }
                    }
                    keypad.addView(btn)
                }
            } else {
                keypad.visibility = View.GONE
                textInput.visibility = View.VISIBLE
                unlockBtn.visibility = View.VISIBLE
                unlockBtn.setOnClickListener {
                    lightTick(it)
                    val pass = textInput.text?.toString() ?: ""
                    tryUnlock(targetPackage, pass, errorTv, showError = true)
                }
            }

            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.setOnKeyListener { _, keyCode, _ ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    params.setBlurBehindRadius(60)
                } catch (_: Exception) {
                }
            }

            try {
                wm.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                e.printStackTrace()
                overlayView = null
                currentPackage = null
            }
        }
    }

    private fun tryUnlock(
        targetPackage: String,
        password: String,
        errorTv: TextView,
        showError: Boolean
    ) {
        if (verifying) return
        verifying = true
        scope.launch {
            try {
                val repo = (context.applicationContext as LockPathApp).repository
                val ok = repo.verifyAppPassword(targetPackage, password)
                if (ok) {
                    AppLockAccessibilityService.markUnlocked(targetPackage)
                    hide()
                } else {
                    if (showError) {
                        errorTv.visibility = View.VISIBLE
                        errorTv.text = "Неверный пароль"
                    }
                    passwordBuffer.clear()
                    overlayView?.findViewById<LinearLayout>(R.id.overlay_dots)?.let { dots ->
                        dots.removeAllViews()
                        val density = context.resources.displayMetrics.density
                        val size = (11 * density).toInt()
                        val gap = (7 * density).toInt()
                        repeat(6) {
                            val dot = View(context).apply {
                                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                    marginStart = gap / 2
                                    marginEnd = gap / 2
                                }
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(0x44FFFFFF)
                                }
                            }
                            dots.addView(dot)
                        }
                    }
                    overlayView?.findViewById<EditText>(R.id.overlay_password_input)?.text?.clear()
                }
            } finally {
                verifying = false
            }
        }
    }

    fun hide() {
        val v = overlayView ?: return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
        overlayView = null
        currentPackage = null
        passwordBuffer.clear()
        verifying = false
    }

    fun hideIfPackage(packageName: String) {
        if (currentPackage == packageName) hide()
    }

    fun forceHide() {
        hide()
    }

    private fun lightTick(view: View) {
        if (!supportsLightHaptics) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun detectLightHaptics(): Boolean {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.hasAmplitudeControl() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            } else false
        } catch (_: Exception) {
            false
        }
    }
}
