package com.usagewidget.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.data.UsageRepository
import com.usagewidget.providers.gemini.GeminiOAuth
import com.usagewidget.ui.theme.UsageWidgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** External browser, loopback redirect, state validation and PKCE; no Google credentials enter a WebView. */
class GeminiLoginActivity : ComponentActivity() {
    private var listener: ServerSocket? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var status by remember { mutableStateOf("Sign in with Google to read Antigravity usage.") }
            var busy by remember { mutableStateOf(false) }
            UsageWidgetTheme {
                Column(Modifier.padding(24.dp)) {
                    Text(status)
                    Button(enabled = !busy, onClick = {
                        busy = true
                        lifecycleScope.launch {
                            status = "Complete sign-in in your browser."
                            val success = runCatching { signIn() }.getOrDefault(false)
                            if (success) {
                                UsageRepository.get(this@GeminiLoginActivity).refresh()
                                finish()
                            } else {
                                status = "Google sign-in could not finish. Try again."
                                busy = false
                            }
                        }
                    }) { Text("Sign in with Google (Antigravity)") }
                    Button(onClick = { finish() }) { Text("Cancel") }
                }
            }
        }
    }

    private suspend fun signIn(): Boolean = withContext(Dispatchers.IO) {
        fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val state = random()
        val verifier = random()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            listener = server
            server.soTimeout = 180_000
            val redirect = "http://127.0.0.1:${server.localPort}/oauth/callback"
            val uri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
                .appendQueryParameter("client_id", GeminiOAuth.CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email")
                .appendQueryParameter("access_type", "offline").appendQueryParameter("prompt", "consent")
                .appendQueryParameter("state", state).appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256").build()
            withContext(Dispatchers.Main) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val line = socket.getInputStream().bufferedReader().readLine()?.takeIf { it.length < 16384 } ?: return@withContext false
                val target = line.split(' ').getOrNull(1) ?: return@withContext false
                val callback = Uri.parse("http://127.0.0.1$target")
                val code = callback.getQueryParameter("code")
                val valid = callback.path == "/oauth/callback" && callback.getQueryParameter("state") == state && !code.isNullOrBlank()
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nReturn to Usage Widget.\r\n".toByteArray())
                if (!valid) return@withContext false
                GeminiOAuth.exchange(SecureCookieStore.get(this@GeminiLoginActivity), mapOf(
                    "grant_type" to "authorization_code", "code" to code!!, "redirect_uri" to redirect, "code_verifier" to verifier))
            }
        }
    }

    override fun onDestroy() {
        runCatching { listener?.close() }
        super.onDestroy()
    }
}
