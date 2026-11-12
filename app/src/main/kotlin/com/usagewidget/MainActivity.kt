package com.usagewidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.usagewidget.domain.ProviderId
import com.usagewidget.ui.dashboard.DashboardScreen
import com.usagewidget.ui.dashboard.DashboardViewModel
import com.usagewidget.ui.diagnostics.DiagnosticsScreen
import com.usagewidget.ui.login.LoginActivity
import com.usagewidget.ui.theme.UsageWidgetTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* toggle stays as the user set it; OS may still suppress alerts */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeOpenLoginFromIntent()
        val openDiagnostics = intent.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false)
        if (openDiagnostics) intent.removeExtra(EXTRA_OPEN_DIAGNOSTICS)
        maybeRequestNotificationPermission()
        setContent {
            UsageWidgetTheme {
                var diagnostics by rememberSaveable { mutableStateOf(openDiagnostics) }
                if (diagnostics) {
                    DiagnosticsScreen(
                        viewModel = viewModel,
                        onBack = { diagnostics = false },
                        onNotificationsToggled = { enabled ->
                            if (enabled) maybeRequestNotificationPermission()
                        }
                    )
                } else {
                    DashboardScreen(
                        viewModel = viewModel,
                        onSignIn = { provider -> startActivity(LoginActivity.intent(this, provider)) },
                        onOpenDiagnostics = { diagnostics = true }
                    )
                }
            }
        }
        lifecycleScope.launch { viewModel.refresh() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeOpenLoginFromIntent()
        if (intent.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false)) {
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun maybeOpenLoginFromIntent() {
        val provider = intent.getStringExtra(EXTRA_SIGN_IN_PROVIDER) ?: return
        intent.removeExtra(EXTRA_SIGN_IN_PROVIDER)
        runCatching {
            startActivity(LoginActivity.intent(this, ProviderId.fromWire(provider)))
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (!viewModel.wantsNotificationPermission()) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_SIGN_IN_PROVIDER = "sign_in_provider"
        const val EXTRA_OPEN_DIAGNOSTICS = "open_diagnostics"
    }
}
