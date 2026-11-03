package com.lockpath.vegs.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lockpath.vegs.LockPathApp
import com.lockpath.vegs.ui.screens.*
import com.lockpath.vegs.ui.theme.LockPathTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as LockPathApp).repository
        setContent {
            val settings by repo.settingsFlow.collectAsState(initial = null)
            LockPathTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settings == null) {
                        // Loading
                    } else {
                        val navController = rememberNavController()
                        val startDest = when {
                            !settings!!.isSetupComplete -> "setup"
                            else -> "auth"
                        }
                        NavHost(navController = navController, startDestination = startDest) {
                            composable("setup") {
                                SetupScreen(repo = repo, onComplete = {
                                    navController.navigate("permissions") {
                                        popUpTo("setup") { inclusive = true }
                                    }
                                })
                            }
                            composable("auth") {
                                AuthScreen(
                                    repo = repo,
                                    onSuccess = {
                                        navController.navigate("permissions") {
                                            popUpTo("auth") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("permissions") {
                                PermissionsScreen(
                                    onContinue = {
                                        navController.navigate("apps") {
                                            popUpTo("permissions") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("apps") {
                                AppsScreen(
                                    repo = repo,
                                    onSettings = { navController.navigate("settings") }
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    repo = repo,
                                    onBack = { navController.popBackStack() },
                                    activity = this@MainActivity
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
