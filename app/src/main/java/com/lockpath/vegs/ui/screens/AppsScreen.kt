package com.lockpath.vegs.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lockpath.vegs.data.AppInfo
import com.lockpath.vegs.data.AppLockConfig
import com.lockpath.vegs.data.AppRepository
import com.lockpath.vegs.ui.S
import com.lockpath.vegs.ui.components.NumericKeypad
import com.lockpath.vegs.ui.components.PasswordField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(repo: AppRepository, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<com.lockpath.vegs.data.AppSettings?>(null) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var configs by remember { mutableStateOf<Map<String, AppLockConfig>>(emptyMap()) }
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var editingPkg by remember { mutableStateOf<String?>(null) }
    var uniquePass by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        settings = repo.getSettings()
        apps = repo.getInstalledApps()
        configs = repo.getAllAppConfigs()
    }

    val lang = S.get(settings?.language ?: "ru")
    val isNumeric = settings?.isNumericKeyboard ?: false

    val filtered = remember(apps, search, showSystem) {
        apps.filter {
            (showSystem || !it.isSystem) &&
                    (search.isEmpty() || it.name.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lang.apps) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = lang.settings)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(lang.search) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (showSystem) lang.systemApps else lang.userApps)
                Spacer(Modifier.weight(1f))
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    val config = configs[app.packageName] ?: AppLockConfig(app.packageName)
                    AppItem(
                        app = app,
                        config = config,
                        lang = lang,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                val newConfig = config.copy(enabled = enabled)
                                repo.saveAppConfig(newConfig)
                                configs = configs + (app.packageName to newConfig)
                            }
                        },
                        onToggleUnique = { unique ->
                            scope.launch {
                                val newConfig = config.copy(useUniquePassword = unique)
                                repo.saveAppConfig(newConfig)
                                configs = configs + (app.packageName to newConfig)
                                if (unique) editingPkg = app.packageName
                            }
                        },
                        onEditPassword = { editingPkg = app.packageName }
                    )
                }
            }
        }
    }

    // Dialog for unique password
    if (editingPkg != null) {
        AlertDialog(
            onDismissRequest = { editingPkg = null; uniquePass = "" },
            title = { Text(lang.uniquePassword) },
            text = {
                Column {
                    PasswordField(
                        value = uniquePass,
                        onValueChange = { uniquePass = it },
                        label = lang.uniquePassword,
                        isNumeric = isNumeric
                    )
                    if (isNumeric) {
                        Spacer(Modifier.height(12.dp))
                        NumericKeypad(
                            onDigit = { if (uniquePass.length < 12) uniquePass += it },
                            onBackspace = { if (uniquePass.isNotEmpty()) uniquePass = uniquePass.dropLast(1) },
                            onClear = { uniquePass = "" }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (uniquePass.length >= 4) {
                            repo.setAppUniquePassword(editingPkg!!, uniquePass)
                            val updated = repo.getAppConfig(editingPkg!!)
                            configs = configs + (editingPkg!! to updated)
                        }
                        editingPkg = null
                        uniquePass = ""
                    }
                }) { Text(lang.save) }
            },
            dismissButton = {
                TextButton(onClick = { editingPkg = null; uniquePass = "" }) { Text(lang.cancel) }
            }
        )
    }
}

@Composable
private fun AppItem(
    app: AppInfo,
    config: AppLockConfig,
    lang: S.Lang,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleUnique: (Boolean) -> Unit,
    onEditPassword: () -> Unit
) {
    val context = LocalContext.current
    var iconBitmap by remember(app.packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(app.packageName) {
        iconBitmap = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(app.packageName)
                drawableToBitmap(drawable)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (config.enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // App icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap!!,
                            contentDescription = app.name,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {}
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = onToggleEnabled)
            }
            if (config.enabled) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lang.useUniquePassword, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = config.useUniquePassword, onCheckedChange = onToggleUnique)
                }
                if (config.useUniquePassword) {
                    TextButton(onClick = onEditPassword) {
                        Text(if (config.uniquePasswordHash.isEmpty()) lang.uniquePassword else "••••••••")
                    }
                }
            }
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return try {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    } catch (_: Exception) {
        null
    }
}

