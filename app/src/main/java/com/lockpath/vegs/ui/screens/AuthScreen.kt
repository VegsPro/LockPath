package com.lockpath.vegs.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
fun AuthScreen(repo: AppRepository, onSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<com.lockpath.vegs.data.AppSettings?>(null) }
    LaunchedEffect(Unit) { settings = repo.getSettings() }
    val lang = S.get(settings?.language ?: "ru")
    val isNumeric = settings?.isNumericKeyboard ?: false

    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(lang.enterPassword, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        PasswordField(
            value = password,
            onValueChange = { if (it.length <= 32) { password = it; error = false } },
            label = lang.enterPassword,
            isNumeric = isNumeric,
            isError = error
        )

        if (isNumeric) {
            Spacer(Modifier.height(24.dp))
            NumericKeypad(
                onDigit = {
                    if (password.length < 12) {
                        password += it
                        error = false
                    }
                },
                onBackspace = { if (password.isNotEmpty()) password = password.dropLast(1) },
                onClear = { password = "" }
            )
        }

        if (error) {
            Spacer(Modifier.height(12.dp))
            Text(lang.wrongPassword, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    if (repo.verifyMasterPassword(password)) {
                        onSuccess()
                    } else {
                        error = true
                        password = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = password.length >= 4
        ) {
            Text(lang.unlock)
        }
    }
}
