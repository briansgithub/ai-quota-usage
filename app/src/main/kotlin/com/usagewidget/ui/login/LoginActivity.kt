package com.usagewidget.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.usagewidget.BuildConfig
import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.auth.SessionPaste
import com.usagewidget.data.UsageRepository
import com.usagewidget.domain.ProviderId
import com.usagewidget.ui.theme.CursorBarWarn
import com.usagewidget.ui.theme.CursorChrome
import com.usagewidget.ui.theme.CursorMuted
import com.usagewidget.ui.theme.CursorSurface
import com.usagewidget.ui.theme.CursorText
import com.usagewidget.ui.theme.UsageWidgetTheme
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    private val host = LoginWebViewHost()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = ProviderId.fromWire(intent.getStringExtra(EXTRA_PROVIDER) ?: ProviderId.CURSOR.wireName)
        enableEdgeToEdge()
        setContent {
            UsageWidgetTheme {
                LoginScreen(
                    provider = provider,
                    host = host,
                    onClose = { finish() },
                    onCaptured = {
                        lifecycleScope.launch {
                            UsageRepository.get(this@LoginActivity).refresh(allowHtmlFallback = true)
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        host.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"

        fun intent(context: Context, provider: ProviderId): Intent =
            Intent(context, if (provider == ProviderId.GEMINI) GeminiLoginActivity::class.java else LoginActivity::class.java).putExtra(EXTRA_PROVIDER, provider.wireName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    provider: ProviderId,
    host: LoginWebViewHost,
    onClose: () -> Unit,
    onCaptured: () -> Unit
) {
    var page by remember { mutableStateOf(LoginPageState()) }
    var showPaste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    val startUrl = remember(provider) { loginStartUrl(provider) }
    val context = LocalContext.current

    Scaffold(
        containerColor = CursorChrome,
        topBar = {
            TopAppBar(
                title = { Text("Sign in to ${provider.displayName}") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = { host.reload() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reload")
                        }
                    }
                    TextButton(onClick = onCaptured) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Text("Done", modifier = Modifier.padding(start = 4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CursorChrome,
                    titleContentColor = CursorText,
                    actionIconContentColor = CursorText
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (page.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { page.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (page.progress == 0 && page.error == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = statusLine(provider, page),
                color = CursorMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            page.error?.let { err ->
                Banner(err, warn = true)
            }
            if (page.googleBlocked) {
                Banner("Google blocked this embedded browser. Use Continue with email on the page, or paste a session cookie.")
            }
            if (page.likelyBlank) {
                Banner("This page looks empty (Cloudflare/ChatGPT sometimes hide the form in a WebView). Try the OpenAI login page, Reload, or paste a session cookie from desktop Chrome.")
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (provider == ProviderId.OPENAI) {
                    OutlinedButton(
                        onClick = { host.load(loginFallbackUrl(provider)) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text("OpenAI login page") }
                }
                OutlinedButton(onClick = { showPaste = true; pasteError = null }) {
                    Text("Paste session")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val view = createLoginWebView(
                        context = context,
                        provider = provider,
                        startUrl = startUrl,
                        onState = { page = it },
                        onMaybeSignedIn = onCaptured
                    )
                    host.container = view
                    host.webView = view.getChildAt(0) as? android.webkit.WebView
                    view
                },
                update = { layout ->
                    layout.layoutParams = ViewGroup.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            )
        }
    }

    if (showPaste) {
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Paste session cookie") },
            text = {
                Column {
                    Text(
                        "Chrome Custom Tabs cannot give this app cookies. From desktop Chrome: DevTools → Application → Cookies → ${pasteHost(provider)}. Paste the Cookie header, or just the session token. The value is stored encrypted on this phone and never logged.",
                        color = CursorMuted
                    )
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it; pasteError = null },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        placeholder = { Text(SessionPaste.defaultCookieName(provider) + "=…") },
                        singleLine = false,
                        minLines = 3
                    )
                    pasteError?.let { Text(it, color = CursorBarWarn, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val ok = SecureCookieStore.get(context).importRawCookies(provider, pasteText)
                    if (ok) {
                        pasteText = ""
                        showPaste = false
                        onCaptured()
                    } else {
                        pasteError = "Could not find a ${SessionPaste.defaultCookieName(provider)} cookie in what you pasted."
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showPaste = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Banner(text: String, warn: Boolean = false) {
    Text(
        text = text,
        color = if (warn) CursorBarWarn else CursorText,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CursorSurface)
            .padding(10.dp)
    )
}

private fun statusLine(provider: ProviderId, page: LoginPageState): String {
    val url = page.url.ifBlank { loginStartUrl(provider) }
    val shortUrl = url.substringAfter("://").take(64)
    val loading = if (page.progress in 1..99) "Loading ${page.progress}% · " else ""
    return loading + shortUrl
}

private fun pasteHost(provider: ProviderId): String = when (provider) {
    ProviderId.CURSOR -> "cursor.com"
    ProviderId.OPENAI -> "chatgpt.com"
    ProviderId.CLAUDE -> "claude.ai"
    ProviderId.GEMINI -> "accounts.google.com"
}
