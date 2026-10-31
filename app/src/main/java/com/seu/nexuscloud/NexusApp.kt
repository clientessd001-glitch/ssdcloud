package com.seu.nexuscloud

import android.app.Application
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
    val step = MutableStateFlow(0)
    val statusText = MutableStateFlow("Conectando à TDLib...")
    val errorText = MutableStateFlow<String?>(null)

    // Estados para o progresso em tempo real (0% a 100%)
    val transferProgress = MutableStateFlow<Int?>(null)
    val transferLabel = MutableStateFlow("")
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
            client?.send(TdApi.GetAuthorizationState(), handler)
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha no arranque da TDLib", e)
            startupError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
            is TdApi.AuthorizationState -> handleAuthState(obj)
            is TdApi.UpdateFile -> handleFileProgress(obj.file)
            is TdApi.Error -> {
                Log.e("NexusCloud", "Erro TDLib [${obj.code}]: ${obj.message}")
                if (obj.code != 400) {
                    TelegramAuth.errorText.value = "Erro TDLib (${obj.code}): ${obj.message}"
                }
            }
        }
    }

    private fun handleFileProgress(file: TdApi.File) {
        val totalBytes = if (file.expectedSize.toLong() > 0L) file.expectedSize.toLong() else file.size.toLong()
        val remote = file.remote
        val local = file.local

        when {
            remote != null && remote.isUploadingActive -> {
                val enviados = remote.uploadedSize.toLong()
                val pct = if (totalBytes > 0L) ((enviados * 100L) / totalBytes).toInt().coerceIn(1, 99) else 0
                TelegramAuth.transferProgress.value = pct
                TelegramAuth.transferLabel.value = "Enviando para a nuvem: $pct%"
            }
            local != null && local.isDownloadingActive -> {
                val baixados = local.downloadedSize.toLong()
                val pct = if (totalBytes > 0L) ((baixados * 100L) / totalBytes).toInt().coerceIn(1, 99) else 0
                TelegramAuth.transferProgress.value = pct
                TelegramAuth.transferLabel.value = "Baixando da nuvem: $pct%"
            }
            (remote != null && remote.isUploadingCompleted) || (local != null && local.isDownloadingCompleted) -> {
                if (TelegramAuth.transferProgress.value != null) {
                    TelegramAuth.transferProgress.value = 100
                    TelegramAuth.transferLabel.value = "Transferência concluída (100%)"
                    TelegramAuth.transferProgress.value = null
                }
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                TelegramAuth.statusText.value = "Configurando cofre na nuvem..."
                if (BuildConfig.TELEGRAM_API_ID == 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
                    TelegramAuth.errorText.value = "TELEGRAM_API_ID ou API_HASH vazios nos Secrets!"
                    return
                }
                val request = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = app.filesDir.absolutePath + "/tdlib"
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = BuildConfig.TELEGRAM_API_ID
                    apiHash = BuildConfig.TELEGRAM_API_HASH
                    systemLanguageCode = "pt"
                    deviceModel = "Android"
                    applicationVersion = "1.0"
                }
                (app as NexusApp).client?.send(request, this)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Pronto! Digite seu número com DDI (+55...)"
                TelegramAuth.step.value = 0
            }
            is TdApi.AuthorizationStateWaitCode -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Código solicitado! Verifique o Telegram."
                TelegramAuth.step.value = 1
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conta protegida com 2FA."
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
                        modifier = Modifier.fillMaxSize().background(Color(0xFF05070A)).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DIAGNÓSTICO TDLIB", color = Color(0xFFFFD700), fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(nexusApp.startupError ?: "Erro desconhecido.", color = Color(0xFFE53935), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
