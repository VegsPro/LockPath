package com.lockpath.vegs.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lockpath.vegs.data.AppRepository
import com.lockpath.vegs.ui.MainActivity
import com.lockpath.vegs.ui.S
import com.lockpath.vegs.ui.components.NumericKeypad
import com.lockpath.vegs.ui.components.PasswordField
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: AppRepository, onBack: () -> Unit, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var settings by remember { mutableStateOf<com.lockpath.vegs.data.AppSettings?>(null) }
    LaunchedEffect(Unit) { settings = repo.getSettings() }

    val lang = S.get(settings?.language ?: "ru")
    var showMasterDialog by remember { mutableStateOf(false) }
    var showSharedDialog by remember { mutableStateOf(false) }
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lang.settings) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Accessibility
            Text(lang.accessibilityTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(lang.accessibilityDesc, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }) { Text(lang.openAccessibility) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Language
            Text(lang.language, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = settings?.language == "ru",
                    onClick = {
                        scope.launch {
                            repo.setLanguage("ru")
                            settings = repo.getSettings()
                        }
                    },
                    label = { Text("Русский") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = settings?.language == "en",
                    onClick = {
                        scope.launch {
                            repo.setLanguage("en")
                            settings = repo.getSettings()
                        }
                    },
                    label = { Text("English") }
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Numeric keyboard
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lang.numericKeyboard, style = MaterialTheme.typography.titleMedium)
                    Text(lang.numericKeyboardDesc, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings?.isNumericKeyboard ?: false,
                    onCheckedChange = {
                        scope.launch {
                            repo.setNumericKeyboard(it)
                            settings = repo.getSettings()
                        }
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Passwords
            Button(onClick = { showMasterDialog = true; newPass = ""; confirmPass = "" }, modifier = Modifier.fillMaxWidth()) {
                Text(lang.changeMaster)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showSharedDialog = true; newPass = ""; confirmPass = "" }, modifier = Modifier.fillMaxWidth()) {
                Text(lang.changeShared)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Disguise
            Text(lang.disguise, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val disguises = listOf(
                "none" to lang.disguiseNone,
                "calculator" to lang.disguiseCalculator,
                "settings" to lang.disguiseSettings,
                "notes" to lang.disguiseNotes,
                "gallery" to lang.disguiseGallery,
                "chrome" to lang.disguiseChrome
            )
            disguises.forEach { (mode, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = settings?.disguiseMode == mode,
                        onClick = {
                            scope.launch {
                                applyDisguise(context, mode)
                                repo.setDisguiseMode(mode)
                                settings = repo.getSettings()
                            }
                        }
                    )
                    Text(label)
                }
            }
        }
    }

    if (showMasterDialog || showSharedDialog) {
        val isMaster = showMasterDialog
        val isNumeric = settings?.isNumericKeyboard ?: false
        AlertDialog(
            onDismissRequest = { showMasterDialog = false; showSharedDialog = false },
            title = { Text(if (isMaster) lang.changeMaster else lang.changeShared) },
            text = {
                Column {
                    PasswordField(value = newPass, onValueChange = { newPass = it }, label = "New", isNumeric = isNumeric)
                    Spacer(Modifier.height(8.dp))
                    PasswordField(value = confirmPass, onValueChange = { confirmPass = it }, label = lang.confirmPassword, isNumeric = isNumeric)
                    if (isNumeric) {
                        Spacer(Modifier.height(12.dp))
                        NumericKeypad(
                            onDigit = {
                                // simple: append to focused, but for simplicity append to newPass if shorter
                                if (newPass.length < confirmPass.length || confirmPass.isEmpty()) {
                                    if (newPass.length < 12) newPass += it
                                } else {
                                    if (confirmPass.length < 12) confirmPass += it
                                }
                            },
                            onBackspace = {
                                if (confirmPass.isNotEmpty()) confirmPass = confirmPass.dropLast(1)
                                else if (newPass.isNotEmpty()) newPass = newPass.dropLast(1)
                            },
                            onClear = { newPass = ""; confirmPass = "" }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPass.length >= 4 && newPass == confirmPass) {
                        scope.launch {
                            if (isMaster) repo.setMasterPassword(newPass)
                            else repo.setSharedPassword(newPass)
                            showMasterDialog = false
                            showSharedDialog = false
                        }
                    }
                }) { Text(lang.save) }
            },
            dismissButton = {
                TextButton(onClick = { showMasterDialog = false; showSharedDialog = false }) { Text(lang.cancel) }
            }
        )
    }
}

private fun applyDisguise(context: Context, mode: String) {
    val pm = context.packageManager
    val aliases = listOf(
        "none" to null,
        "calculator" to ".ui.MainActivityCalculator",
        "settings" to ".ui.MainActivitySettings",
        "notes" to ".ui.MainActivityNotes",
        "gallery" to ".ui.MainActivityGallery",
        "chrome" to ".ui.MainActivityChrome"
    )
    // Disable all aliases and main if needed
    val mainComponent = ComponentName(context, MainActivity::class.java)
    aliases.forEach { (m, alias) ->
        if (alias != null) {
            val component = ComponentName(context, "com.lockpath.vegs$alias")
            val state = if (m == mode) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
    // Main activity: enabled only if none
    val mainState = if (mode == "none") PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    pm.setComponentEnabledSetting(mainComponent, mainState, PackageManager.DONT_KILL_APP)
}
