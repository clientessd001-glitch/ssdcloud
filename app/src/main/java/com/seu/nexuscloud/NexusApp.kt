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
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class NexusApp : Application() {
    var client: Client? = null
    var startupError: String? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // Garante que o motor C++ libtdjni.so é carregado na memória
            try {
                System.loadLibrary("tdjni")
            } catch (_: Throwable) {
                // Ignora se a própria classe Client já o tiver carregado internamente
            }

            val handler = TelegramHandler(this)
            client = Client.create(handler, null, null)
            client?.send(TdApi.GetAuthorizationState(), handler)
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha no arranque da TDLib", e)
            startupError = "${e.javaClass.simpleName}: ${e.message}\n\nCausa: ${e.cause?.message ?: "N/A"}"
        }
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState) {
            when (obj.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    val request = TdApi.SetTdlibParameters().apply {
                        databaseDirectory = app.filesDir.absolutePath + "/tdlib"
                        filesDirectory = app.filesDir.absolutePath + "/tdlib_files"
                        useMessageDatabase = true
                        useSecretChats = false
                        apiId = BuildConfig.TELEGRAM_API_ID
                        apiHash = BuildConfig.TELEGRAM_API_HASH
                        systemLanguageCode = "pt"
                        deviceModel = Build.MODEL ?: "Android"
                        systemVersion = Build.VERSION.RELEASE ?: "14"
                        applicationVersion = "1.0"
                    }
                    (app as NexusApp).client?.send(request, this)
                }
                is TdApi.AuthorizationStateWaitPhoneNumber -> Log.d("NexusCloud", "Aguardando Número")
                is TdApi.AuthorizationStateWaitCode -> Log.d("NexusCloud", "Aguardando SMS")
                is TdApi.AuthorizationStateReady -> Log.d("NexusCloud", "Autenticado na Nuvem!")
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
                    // Ecrã de diagnóstico caso o motor falhe no arranque
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
