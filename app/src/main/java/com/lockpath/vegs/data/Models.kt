package com.lockpath.vegs.data

data class AppInfo(
    val packageName: String,
    val name: String,
    val isSystem: Boolean = false
)

data class AppLockConfig(
    val packageName: String,
    val enabled: Boolean = false,
    val useUniquePassword: Boolean = false,
    val uniquePasswordHash: String = ""
)

data class AppSettings(
    val masterPasswordHash: String = "",
    val sharedPasswordHash: String = "",
    val isNumericKeyboard: Boolean = false,
    val language: String = "ru", // "ru" or "en"
    val disguiseMode: String = "none", // none, calculator, settings, notes, gallery, chrome
    val isSetupComplete: Boolean = false
)
