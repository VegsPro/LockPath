package com.lockpath.vegs.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lockpath.vegs.data.AppRepository
import com.lockpath.vegs.ui.S
import com.lockpath.vegs.ui.components.NumericKeypad
import com.lockpath.vegs.ui.components.PasswordField
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(repo: AppRepository, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<com.lockpath.vegs.data.AppSettings?>(null) }
    LaunchedEffect(Unit) {
        settings = repo.getSettings()
    }
    val lang = S.get(settings?.language ?: "ru")

    var step by remember { mutableIntStateOf(0) }
    var master by remember { mutableStateOf("") }
    var masterConfirm by remember { mutableStateOf("") }
    var shared by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isNumeric by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(lang.setupTitle, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        when (step) {
            0 -> {
                Text(lang.createMasterPassword, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = master,
                    onValueChange = { if (it.length <= 32) master = it },
                    label = lang.createMasterPassword,
                    isNumeric = isNumeric,
                    isError = error != null
                )
                if (isNumeric) {
                    Spacer(Modifier.height(16.dp))
                    NumericKeypad(
                        onDigit = { if (master.length < 12) master += it },
                        onBackspace = { if (master.isNotEmpty()) master = master.dropLast(1) },
                        onClear = { master = "" }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isNumeric, onCheckedChange = { isNumeric = it; master = "" })
                    Spacer(Modifier.width(8.dp))
                    Text(lang.numericKeyboard)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (master.length < 4) {
                            error = lang.minLength
                        } else {
                            error = null
                            step = 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(lang.next) }
            }
            1 -> {
                Text(lang.confirmPassword, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = masterConfirm,
                    onValueChange = { if (it.length <= 32) masterConfirm = it },
                    label = lang.confirmPassword,
                    isNumeric = isNumeric,
                    isError = error != null
                )
                if (isNumeric) {
                    Spacer(Modifier.height(16.dp))
                    NumericKeypad(
                        onDigit = { if (masterConfirm.length < 12) masterConfirm += it },
                        onBackspace = { if (masterConfirm.isNotEmpty()) masterConfirm = masterConfirm.dropLast(1) },
                        onClear = { masterConfirm = "" }
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (master != masterConfirm) {
                            error = lang.passwordsDontMatch
                        } else {
                            error = null
                            step = 2
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(lang.next) }
            }
            2 -> {
                Text(lang.sharedPassword, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Можно оставить пустым и задать позже", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = shared,
                    onValueChange = { if (it.length <= 32) shared = it },
                    label = lang.sharedPassword,
                    isNumeric = isNumeric
                )
                if (isNumeric) {
                    Spacer(Modifier.height(16.dp))
                    NumericKeypad(
                        onDigit = { if (shared.length < 12) shared += it },
                        onBackspace = { if (shared.isNotEmpty()) shared = shared.dropLast(1) },
                        onClear = { shared = "" }
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch {
                            repo.setMasterPassword(master)
                            if (shared.isNotEmpty()) repo.setSharedPassword(shared)
                            repo.setNumericKeyboard(isNumeric)
                            repo.setSetupComplete(true)
                            onComplete()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(lang.save) }
            }
        }
    }
}
