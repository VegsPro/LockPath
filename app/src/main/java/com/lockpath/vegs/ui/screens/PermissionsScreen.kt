package com.lockpath.vegs.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lockpath.vegs.service.AppLockAccessibilityService

@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }
    var usageOn by remember { mutableStateOf(false) }

    fun refresh() {
        accessibilityOn = isAccessibilityEnabled(context)
        overlayOn = Settings.canDrawOverlays(context)
        usageOn = isUsageAccessGranted(context)
    }

    // Refresh when returning from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val allOk = accessibilityOn && overlayOn

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Разрешения", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Для работы блокировки нужны эти разрешения. Включите все пункты.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        PermissionCard(
            title = "Служба спец. возможностей",
            subtitle = "Обязательно — отслеживает открытие приложений",
            granted = accessibilityOn,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            title = "Поверх других окон",
            subtitle = "Обязательно — экран пароля поверх приложения",
            granted = overlayOn,
            onClick = {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            title = "Статистика использования",
            subtitle = "Рекомендуется — надёжнее определяет, какое приложение на экране",
            granted = usageOn,
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        )

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onContinue,
            enabled = allOk,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (allOk) "Продолжить" else "Сначала включите обязательные")
        }
        if (!allOk) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { refresh() }) {
                Text("Обновить статус")
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !granted
            ) {
                Text(if (granted) "Включено" else "Включить")
            }
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val my = context.packageName + "/" + AppLockAccessibilityService::class.java.name
    return enabled.any { it.id == my || it.id.contains(context.packageName) }
}

private fun isUsageAccessGranted(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                "android:get_usage_stats",
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                "android:get_usage_stats",
                Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}
