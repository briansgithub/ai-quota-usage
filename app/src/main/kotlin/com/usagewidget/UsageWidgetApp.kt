package com.usagewidget

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.work.ExistingPeriodicWorkPolicy
import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.refresh.UsageRefreshWorker
import com.usagewidget.refresh.WakeRefreshReceiver
import com.usagewidget.widget.WidgetPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UsageWidgetApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CookieManager.getInstance().setAcceptCookie(true)
        runCatching { SecureCookieStore.get(this).restoreIntoCookieManager() }
        UsageRefreshWorker.enqueuePeriodic(this, ExistingPeriodicWorkPolicy.KEEP)
        WakeRefreshReceiver.register(this)
        appScope.launch { WidgetPublisher.push(this@UsageWidgetApp) }
        runCatching { WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) }
    }
}
