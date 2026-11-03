package com.lockpath.vegs.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lockpath.vegs.LockPathApp
import com.lockpath.vegs.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Shows a full-screen lock overlay ON TOP of the protected app.
 * Does not start an Activity — avoids task-stack and re-lock loops.
 */
class OverlayLockManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var overlayView: View? = null
    private var currentPackage: String? = null
    private var passwordBuffer = StringBuilder()
    private var isNumeric = true

    val isShowing: Boolean get() = overlayView != null

    fun show(targetPackage: String) {
        if (overlayView != null && currentPackage == targetPackage) return
        hide()
        currentPackage = targetPackage
        passwordBuffer.clear()

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

            title.text = if (langRu) "Введите пароль" else "Enter password"
            pkgTv.text = targetPackage
            errorTv.text = if (langRu) "Неверный пароль" else "Wrong password"
            unlockBtn.text = if (langRu) "Разблокировать" else "Unlock"
            textInput.hint = if (langRu) "Пароль" else "Password"

            // Dots indicator
            fun refreshDots() {
                dots.removeAllViews()
                val count = if (isNumeric) maxOf(passwordBuffer.length, 4) else 0
                if (isNumeric) {
                    repeat(6) { i ->
                        val dot = View(context).apply {
                            val size = (12 * context.resources.displayMetrics.density).toInt()
                            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                marginStart = size / 2
                                marginEnd = size / 2
                            }
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (i < passwordBuffer.length) 0xFF9FA8DA.toInt() else 0x44FFFFFF)
                            }
                        }
                        dots.addView(dot)
                    }
                }
            }
            refreshDots()

            if (isNumeric) {
                textInput.visibility = View.GONE
                unlockBtn.visibility = View.GONE
                keypad.visibility = View.VISIBLE
                val keys = listOf("1","2","3","4","5","6","7","8","9","C","0","⌫")
                val keySize = (64 * context.resources.displayMetrics.density).toInt()
                keys.forEach { key ->
                    val btn = TextView(context).apply {
                        text = key
                        textSize = 22f
                        setTextColor(0xFFFFFFFF.toInt())
                        gravity = Gravity.CENTER
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = keySize
                            height = keySize
                            setMargins(12, 12, 12, 12)
                        }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x33FFFFFF)
                        }
                        setOnClickListener {
                            errorTv.visibility = View.GONE
                            when (key) {
                                "C" -> { passwordBuffer.clear(); refreshDots() }
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
                                        if (passwordBuffer.length >= 4) {
                                            tryUnlock(targetPackage, passwordBuffer.toString(), errorTv, langRu)
                                        }
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
                    val pass = textInput.text?.toString() ?: ""
                    tryUnlock(targetPackage, pass, errorTv, langRu)
                }
            }

            // Block back / outside
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
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Soft input if text mode
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            try {
                wm.addView(view, params)
                overlayView = view
            } catch (e: Exception) {
                // Overlay permission missing
                e.printStackTrace()
                overlayView = null
                currentPackage = null
            }
        }
    }

    private fun tryUnlock(targetPackage: String, password: String, errorTv: TextView, langRu: Boolean) {
        scope.launch {
            val repo = (context.applicationContext as LockPathApp).repository
            val ok = repo.verifyAppPassword(targetPackage, password)
            if (ok) {
                AppLockAccessibilityService.markUnlocked(targetPackage)
                hide()
            } else {
                passwordBuffer.clear()
                errorTv.visibility = View.VISIBLE
                // refresh dots
                overlayView?.findViewById<LinearLayout>(R.id.overlay_dots)?.let { dots ->
                    dots.removeAllViews()
                    repeat(6) { i ->
                        val size = (12 * context.resources.displayMetrics.density).toInt()
                        val dot = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                marginStart = size / 2
                                marginEnd = size / 2
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
    }

    fun hideIfPackage(packageName: String) {
        if (currentPackage == packageName) hide()
    }
}
