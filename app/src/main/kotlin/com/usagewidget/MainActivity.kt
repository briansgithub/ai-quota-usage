package com.usagewidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
        val dumpUsage = intent.getBooleanExtra(EXTRA_DUMP_USAGE, false)
        val openDiagnostics = dumpUsage || intent.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false)
        val openSettings = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        if (openDiagnostics) intent.removeExtra(EXTRA_OPEN_DIAGNOSTICS)
        if (openSettings) intent.removeExtra(EXTRA_OPEN_SETTINGS)
        if (dumpUsage) {
            intent.removeExtra(EXTRA_DUMP_USAGE)
            dumpUsageToLogcat()
        }
        maybeRequestNotificationPermission(force = intent.getBooleanExtra(EXTRA_TEST_NOTIFICATION, false))
        maybeSendTestNotification(intent)
        setContent {
            UsageWidgetTheme {
                var diagnostics by rememberSaveable { mutableStateOf(openDiagnostics || openSettings) }
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
                        openSettings = false
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
        if (intent.getBooleanExtra(EXTRA_DUMP_USAGE, false) ||
            intent.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false) ||
            intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        ) {
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
