package com.usagewidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.UsageDump
import com.usagewidget.refresh.UsageAlertNotifier
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
        handleScreenIntent()
        maybeRequestNotificationPermission(force = intent.getBooleanExtra(EXTRA_TEST_NOTIFICATION, false))
        maybeSendTestNotification(intent)
        setContent {
            UsageWidgetTheme {
                var diagnostics by rememberSaveable { mutableStateOf(false) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(requestedScreen) {
                    requestedScreen?.let {
                        diagnostics = it == EXTRA_OPEN_DIAGNOSTICS
                        settingsOpen = it == EXTRA_OPEN_SETTINGS
                        requestedScreen = null
                    }
                }
                BackHandler(enabled = diagnostics) { diagnostics = false }
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
                        onOpenDiagnostics = { diagnostics = true },
                        settingsOpen = settingsOpen,
                        onSettingsChange = { settingsOpen = it }
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
        if (intent.getBooleanExtra(EXTRA_TEST_NOTIFICATION, false)) {
            maybeRequestNotificationPermission(force = true)
            maybeSendTestNotification(intent)
        }
        handleScreenIntent()
    }

    private var requestedScreen by mutableStateOf<String?>(null)

    private fun handleScreenIntent() {
        val dump = intent.getBooleanExtra(EXTRA_DUMP_USAGE, false)
        requestedScreen = when {
            dump || intent.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false) -> EXTRA_OPEN_DIAGNOSTICS
            intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) -> EXTRA_OPEN_SETTINGS
            else -> null
        }
        intent.removeExtra(EXTRA_OPEN_SETTINGS)
        intent.removeExtra(EXTRA_OPEN_DIAGNOSTICS)
        intent.removeExtra(EXTRA_DUMP_USAGE)
        if (dump) dumpUsageToLogcat()
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

    private fun maybeRequestNotificationPermission(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 33) return
        if (!force && !viewModel.wantsNotificationPermission()) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeSendTestNotification(incoming: android.content.Intent) {
        if (!incoming.getBooleanExtra(EXTRA_TEST_NOTIFICATION, false)) return
        incoming.removeExtra(EXTRA_TEST_NOTIFICATION)
        UsageAlertNotifier(this).postTest()
    }

    private fun dumpUsageToLogcat() {
        lifecycleScope.launch {
            val repo = UsageRepository.get(this@MainActivity)
            logDump("cached", UsageDump.format(repo.snapshot.value))
            val refreshed = repo.refresh(allowHtmlFallback = false)
            logDump("network", UsageDump.format(refreshed))
        }
    }

    private fun logDump(phase: String, text: String) {
        Log.i(UsageDump.LOG_TAG, "=== dump_usage $phase ===")
        text.lineSequence().forEach { line ->
            if (line.length <= 3500) {
                Log.i(UsageDump.LOG_TAG, line)
            } else {
                line.chunked(3500).forEach { chunk -> Log.i(UsageDump.LOG_TAG, chunk) }
            }
        }
    }

    companion object {
        const val EXTRA_SIGN_IN_PROVIDER = "sign_in_provider"
        const val EXTRA_OPEN_DIAGNOSTICS = "open_diagnostics"
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        const val EXTRA_TEST_NOTIFICATION = "test_notification"
        const val EXTRA_DUMP_USAGE = "dump_usage"
    }
}
