package com.local.ondevicedocumentassistant

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : FragmentActivity() {
    private var isUnlocked by mutableStateOf(false)
    private var authenticationMessage by mutableStateOf<String?>(null)
    private var backgroundedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        StrictModeConfig.enableDebugChecks()
        SecurePreferences.initialize(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isUnlocked) {
                        AppNavigation()
                    } else {
                        LockedScreen(authenticationMessage)
                    }
                }
            }
        }

        authenticate()
    }

    override fun onStart() {
        super.onStart()
        val backgroundedAt = backgroundedAt ?: return
        if (System.currentTimeMillis() - backgroundedAt >= REAUTHENTICATION_TIMEOUT_MS) {
            isUnlocked = false
            authenticate()
        }
        this.backgroundedAt = null
    }

    override fun onStop() {
        backgroundedAt = System.currentTimeMillis()
        super.onStop()
    }

    fun authenticateForSensitiveContent(onSuccess: () -> Unit) {
        authenticate(onSuccess)
    }

    private fun authenticate(onSuccess: () -> Unit = {}) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            authenticationMessage = "Set up a strong biometric or device credential to unlock the app."
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isUnlocked = true
                    authenticationMessage = null
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationMessage = errString.toString()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_name))
                .setSubtitle("Unlock your on-device documents")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    companion object {
        private const val REAUTHENTICATION_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val destinations = listOf("home", "documents", "chat", "settings")
    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination,
                        onClick = {
                            navController.navigate(destination) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.replaceFirstChar(Char::uppercase)) },
                        label = { Text(destination.replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(contentPadding),
        ) {
            composable("home") { PlaceholderScreen("Home") }
            composable("documents") { DocumentsScreen() }
            composable("chat") { ChatScreen() }
            composable("settings") { PlaceholderScreen("Settings") }
        }
    }
}

@Composable
private fun PlaceholderScreen(name: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(name, style = MaterialTheme.typography.headlineSmall)
        Text("Phase 0 foundation is ready", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LockedScreen(message: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Unlock required", style = MaterialTheme.typography.headlineSmall)
        Text("Authenticate to access your on-device documents")
        message?.let { Text(it) }
    }
}