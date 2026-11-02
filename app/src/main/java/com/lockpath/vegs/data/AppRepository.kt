package com.lockpath.vegs.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lockpath_prefs")

class AppRepository(private val context: Context) {

    private val dataStore = context.dataStore

    private object Keys {
        val MASTER_HASH = stringPreferencesKey("master_hash")
        val SHARED_HASH = stringPreferencesKey("shared_hash")
        val NUMERIC_KB = booleanPreferencesKey("numeric_kb")
        val LANGUAGE = stringPreferencesKey("language")
        val DISGUISE = stringPreferencesKey("disguise")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val APP_CONFIGS = stringPreferencesKey("app_configs") // JSON-like simple format
        val SALT = stringPreferencesKey("salt")
        val COOLDOWN = intPreferencesKey("cooldown_seconds")
    }

    val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            masterPasswordHash = prefs[Keys.MASTER_HASH] ?: "",
            sharedPasswordHash = prefs[Keys.SHARED_HASH] ?: "",
            isNumericKeyboard = prefs[Keys.NUMERIC_KB] ?: false,
            language = prefs[Keys.LANGUAGE] ?: "ru",
            disguiseMode = prefs[Keys.DISGUISE] ?: "none",
            isSetupComplete = prefs[Keys.SETUP_DONE] ?: false,
            cooldownSeconds = prefs[Keys.COOLDOWN] ?: 0
        )
    }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun setMasterPassword(password: String) {
        val salt = getOrCreateSalt()
        val hash = hashPassword(password, salt)
        dataStore.edit { it[Keys.MASTER_HASH] = hash }
    }

    suspend fun setSharedPassword(password: String) {
        val salt = getOrCreateSalt()
        val hash = hashPassword(password, salt)
        dataStore.edit { it[Keys.SHARED_HASH] = hash }
    }

    suspend fun setNumericKeyboard(enabled: Boolean) {
        dataStore.edit { it[Keys.NUMERIC_KB] = enabled }
    }

    suspend fun setLanguage(lang: String) {
        dataStore.edit { it[Keys.LANGUAGE] = lang }
    }

    suspend fun setDisguiseMode(mode: String) {
        dataStore.edit { it[Keys.DISGUISE] = mode }
    }

    suspend fun setSetupComplete(done: Boolean) {
        dataStore.edit { it[Keys.SETUP_DONE] = done }
    }

    suspend fun setCooldownSeconds(seconds: Int) {
        dataStore.edit { it[Keys.COOLDOWN] = seconds.coerceAtLeast(0) }
    }

    suspend fun verifyMasterPassword(password: String): Boolean {
        val settings = getSettings()
        if (settings.masterPasswordHash.isEmpty()) return true
        val salt = getOrCreateSalt()
        return hashPassword(password, salt) == settings.masterPasswordHash
    }

    suspend fun verifySharedPassword(password: String): Boolean {
        val settings = getSettings()
        if (settings.sharedPasswordHash.isEmpty()) return false
        val salt = getOrCreateSalt()
        return hashPassword(password, salt) == settings.sharedPasswordHash
    }

    suspend fun verifyAppPassword(packageName: String, password: String): Boolean {
        val config = getAppConfig(packageName)
        return if (config.useUniquePassword && config.uniquePasswordHash.isNotEmpty()) {
            val salt = getOrCreateSalt()
            hashPassword(password, salt) == config.uniquePasswordHash
        } else {
            verifySharedPassword(password)
        }
    }

    // App configs stored as "pkg|enabled|useUnique|hash;pkg2|..."
    suspend fun getAllAppConfigs(): Map<String, AppLockConfig> {
        val raw = dataStore.data.first()[Keys.APP_CONFIGS] ?: ""
        if (raw.isEmpty()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size >= 4) {
                parts[0] to AppLockConfig(
                    packageName = parts[0],
                    enabled = parts[1] == "1",
                    useUniquePassword = parts[2] == "1",
                    uniquePasswordHash = parts[3]
                )
            } else null
        }.toMap()
    }

    suspend fun getAppConfig(packageName: String): AppLockConfig {
        return getAllAppConfigs()[packageName] ?: AppLockConfig(packageName)
    }

    suspend fun saveAppConfig(config: AppLockConfig) {
        val all = getAllAppConfigs().toMutableMap()
        all[config.packageName] = config
        val serialized = all.values.joinToString(";") {
            "${it.packageName}|${if (it.enabled) "1" else "0"}|${if (it.useUniquePassword) "1" else "0"}|${it.uniquePasswordHash}"
        }
        dataStore.edit { it[Keys.APP_CONFIGS] = serialized }
    }

    suspend fun setAppUniquePassword(packageName: String, password: String) {
        val salt = getOrCreateSalt()
        val hash = hashPassword(password, salt)
        val current = getAppConfig(packageName)
        saveAppConfig(current.copy(uniquePasswordHash = hash, useUniquePassword = true))
    }

    fun getInstalledApps(): List<AppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return packages
            .filter { it.packageName != context.packageName }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    name = pm.getApplicationLabel(it).toString(),
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private suspend fun getOrCreateSalt(): String {
        val existing = dataStore.data.first()[Keys.SALT]
        if (existing != null) return existing
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(salt, Base64.NO_WRAP)
        dataStore.edit { it[Keys.SALT] = encoded }
        return encoded
    }

    private fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = (password + salt).toByteArray(Charsets.UTF_8)
        val hash = digest.digest(input)
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
