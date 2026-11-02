package com.lockpath.vegs.ui

object S {
    fun get(lang: String): Lang = if (lang == "en") En else Ru

    interface Lang {
        val appName: String
        val enterPassword: String
        val wrongPassword: String
        val setupTitle: String
        val createMasterPassword: String
        val confirmPassword: String
        val next: String
        val save: String
        val apps: String
        val settings: String
        val search: String
        val enableProtection: String
        val useUniquePassword: String
        val uniquePassword: String
        val sharedPassword: String
        val changeMaster: String
        val changeShared: String
        val numericKeyboard: String
        val numericKeyboardDesc: String
        val language: String
        val disguise: String
        val disguiseNone: String
        val disguiseCalculator: String
        val disguiseSettings: String
        val disguiseNotes: String
        val disguiseGallery: String
        val disguiseChrome: String
        val accessibilityTitle: String
        val accessibilityDesc: String
        val openAccessibility: String
        val unlock: String
        val cancel: String
        val passwordsMatch: String
        val passwordsDontMatch: String
        val minLength: String
        val systemApps: String
        val userApps: String
        val protectionOn: String
        val protectionOff: String
    }

    object Ru : Lang {
        override val appName = "LockPath"
        override val enterPassword = "Введите пароль"
        override val wrongPassword = "Неверный пароль"
        override val setupTitle = "Настройка"
        override val createMasterPassword = "Создайте мастер-пароль"
        override val confirmPassword = "Подтвердите пароль"
        override val next = "Далее"
        override val save = "Сохранить"
        override val apps = "Приложения"
        override val settings = "Настройки"
        override val search = "Поиск…"
        override val enableProtection = "Защита паролем"
        override val useUniquePassword = "Уникальный пароль"
        override val uniquePassword = "Уникальный пароль"
        override val sharedPassword = "Общий пароль"
        override val changeMaster = "Изменить мастер-пароль"
        override val changeShared = "Изменить общий пароль"
        override val numericKeyboard = "Цифровая клавиатура"
        override val numericKeyboardDesc = "Если пароль состоит только из цифр — показывать цифровую клавиатуру"
        override val language = "Язык"
        override val disguise = "Маскировка"
        override val disguiseNone = "Без маскировки"
        override val disguiseCalculator = "Калькулятор"
        override val disguiseSettings = "Настройки"
        override val disguiseNotes = "Заметки"
        override val disguiseGallery = "Галерея"
        override val disguiseChrome = "Chrome"
        override val accessibilityTitle = "Служба спец. возможностей"
        override val accessibilityDesc = "Для блокировки приложений необходимо включить службу LockPath в настройках специальных возможностей."
        override val openAccessibility = "Открыть настройки"
        override val unlock = "Разблокировать"
        override val cancel = "Отмена"
        override val passwordsMatch = "Пароли совпадают"
        override val passwordsDontMatch = "Пароли не совпадают"
        override val minLength = "Минимум 4 символа"
        override val systemApps = "Системные"
        override val userApps = "Пользовательские"
        override val protectionOn = "Защита включена"
        override val protectionOff = "Защита выключена"
    }

    object En : Lang {
        override val appName = "LockPath"
        override val enterPassword = "Enter password"
        override val wrongPassword = "Wrong password"
        override val setupTitle = "Setup"
        override val createMasterPassword = "Create master password"
        override val confirmPassword = "Confirm password"
        override val next = "Next"
        override val save = "Save"
        override val apps = "Apps"
        override val settings = "Settings"
        override val search = "Search…"
        override val enableProtection = "Password protection"
        override val useUniquePassword = "Unique password"
        override val uniquePassword = "Unique password"
        override val sharedPassword = "Shared password"
        override val changeMaster = "Change master password"
        override val changeShared = "Change shared password"
        override val numericKeyboard = "Numeric keyboard"
        override val numericKeyboardDesc = "If password contains only digits — show numeric keypad"
        override val language = "Language"
        override val disguise = "Disguise"
        override val disguiseNone = "No disguise"
        override val disguiseCalculator = "Calculator"
        override val disguiseSettings = "Settings"
        override val disguiseNotes = "Notes"
        override val disguiseGallery = "Gallery"
        override val disguiseChrome = "Chrome"
        override val accessibilityTitle = "Accessibility Service"
        override val accessibilityDesc = "To lock apps you must enable LockPath service in Accessibility settings."
        override val openAccessibility = "Open settings"
        override val unlock = "Unlock"
        override val cancel = "Cancel"
        override val passwordsMatch = "Passwords match"
        override val passwordsDontMatch = "Passwords do not match"
        override val minLength = "Minimum 4 characters"
        override val systemApps = "System"
        override val userApps = "User"
        override val protectionOn = "Protection on"
        override val protectionOff = "Protection off"
    }
}
