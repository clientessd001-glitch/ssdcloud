package com.seu.nexuscloud

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

object TelegramAuth {
    // 0 = Número, 1 = Código SMS, 2 = Senha 2FA (se tiver), 3 = Autenticado (Galeria)
    val step = MutableStateFlow(0)
    val statusText = MutableStateFlow("Conectando à TDLib...")
    val errorText = MutableStateFlow<String?>(null)
}

class NexusApp : Application() {
    var client: Client? = null
    var startupError: String? = null

    override fun onCreate() {
        super.onCreate()
        try {
            try {
                System.loadLibrary("tdjni")
            } catch (_: Throwable) {}

            val handler = TelegramHandler(this)
            client = Client.create(handler, null, null)
            client?.send(TdApi.SetLogVerbosityLevel(1), handler)
            client?.send(TdApi.GetAuthorizationState(), handler)
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha no arranque da TDLib", e)
            startupError = "${e.javaClass.simpleName}: ${e.message}\n\nCausa: ${e.cause?.message ?: "N/A"}"
        }
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
            is TdApi.AuthorizationState -> handleAuthState(obj)
            is TdApi.Error -> {
                Log.e("NexusCloud", "Erro TDLib [${obj.code}]: ${obj.message}")
                TelegramAuth.errorText.value = "Erro TDLib (${obj.code}): ${obj.message}"
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                TelegramAuth.statusText.value = "Validando chaves da API..."
                if (BuildConfig.TELEGRAM_API_ID == 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
                    TelegramAuth.errorText.value = "TELEGRAM_API_ID ou API_HASH estão vazios nos Secrets do GitHub!"
                    return
                }
                val request = TdApi.SetTdlibParameters(
                    false,
                    app.filesDir.absolutePath + "/tdlib",
                    app.filesDir.absolutePath + "/tdlib_files",
                    byteArrayOf(),
                    true,
                    true,
                    true,
                    false,
                    BuildConfig.TELEGRAM_API_ID,
                    BuildConfig.TELEGRAM_API_HASH,
                    "pt",
                    Build.MODEL ?: "Android",
                    Build.VERSION.RELEASE ?: "14",
                    "1.0"
                )
                (app as NexusApp).client?.send(request, this)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Pronto! Digite seu número com DDI (+55...)"
                TelegramAuth.step.value = 0
            }
            is TdApi.AuthorizationStateWaitCode -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Código solicitado! Verifique seu app do Telegram ou SMS."
                TelegramAuth.step.value = 1
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conta protegida com verificação em duas etapas (2FA)."
                TelegramAuth.step.value = 2
            }
            is TdApi.AuthorizationStateReady -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conectado à Nuvem!"
                TelegramAuth.step.value = 3
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nexusApp = application as NexusApp
        val db = NexusDB.get(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val activeClient = nexusApp.client
                if (activeClient != null) {
                    NexusUI(db.dao(), activeClient)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF05070A))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "DIAGNÓSTICO DO MOTOR TDLIB",
                            color = Color(0xFFFFD700),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = nexusApp.startupError ?: "Erro desconhecido ao inicializar Client.",
                            color = Color(0xFFE53935),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
