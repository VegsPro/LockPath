package com.lockpath.vegs.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lockpath.vegs.LockPathApp
import com.lockpath.vegs.ui.components.NumericKeypad
import com.lockpath.vegs.ui.components.PasswordField
import com.lockpath.vegs.ui.theme.LockPathTheme
import kotlinx.coroutines.launch

class LockActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
        val repo = (application as LockPathApp).repository

        setContent {
            LockPathTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LockContent(repo = repo, targetPackage = targetPackage, onUnlock = { finish() })
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent back
        moveTaskToBack(true)
    }
}

@Composable
private fun LockContent(repo: com.lockpath.vegs.data.AppRepository, targetPackage: String, onUnlock: () -> Unit) {
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
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(lang.enterPassword, style = MaterialTheme.typography.headlineSmall)
        if (targetPackage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(targetPackage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))

        PasswordField(
            value = password,
            onValueChange = { if (it.length <= 32) { password = it; error = false } },
            label = lang.enterPassword,
            isNumeric = isNumeric,
            isError = error
        )

        if (isNumeric) {
            Spacer(Modifier.height(20.dp))
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
                    val ok = if (targetPackage.isNotEmpty()) {
                        repo.verifyAppPassword(targetPackage, password)
                    } else {
                        repo.verifyMasterPassword(password)
                    }
                    if (ok) {
                        onUnlock()
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
