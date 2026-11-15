package com.seu.nexuscloud

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

object TelegramAuth {
    val step = MutableStateFlow(0)
    val statusText = MutableStateFlow("Conectando à nuvem SSD...")
    val errorText = MutableStateFlow<String?>(null)

    val transferProgress = MutableStateFlow<Int?>(null)
    val transferLabel = MutableStateFlow("")
}

class NexusApp : Application() {
    @Volatile
    var client: Client? = null
    @Volatile
    var startupError: String? = null

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("NexusCloud", "Exceção capturada na thread ${thread.name}", throwable)
            val resumoErro = "${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                throwable.stackTrace.take(8).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }

            try {
                getSharedPreferences("nexus_crash_log", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", resumoErro)
                    .commit()
            } catch (_: Throwable) {}

            // Se o erro veio de uma thread secundária (ex: TDLib), não fecha o aplicativo
            if (thread.name != "main") {
                startupError = resumoErro
                TelegramAuth.errorText.value = "Aviso interno: ${throwable.localizedMessage}"
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    @Synchronized
    fun iniciarClienteTDLib(): Client? {
        if (client != null) return client
        return try {
            try {
                System.loadLibrary("tdjni")
            } catch (_: Throwable) {}

            File(filesDir, "tdlib").apply { if (!exists()) mkdirs() }

            val handler = TelegramHandler(this)
            val novo = Client.create(handler, null, null)
            client = novo
            novo?.send(TdApi.GetAuthorizationState(), handler)
            startupError = null
            novo
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha ao criar cliente TDLib", e)
            startupError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }
}

class TelegramHandler(private val app: Application) : Client.ResultHandler {
    override fun onResult(obj: TdApi.Object) {
        try {
            when (obj) {
                is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
                is TdApi.AuthorizationState -> handleAuthState(obj)
                is TdApi.UpdateFile -> handleFileProgress(obj.file)
                is TdApi.Error -> {
                    Log.e("NexusCloud", "Erro TDLib [${obj.code}]: ${obj.message}")
                    if (obj.code != 400) {
                        TelegramAuth.errorText.value = "Erro SSD (${obj.code}): ${obj.message}"
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha no TelegramHandler", e)
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
                    TelegramAuth.transferProgress.value = null
                }
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                TelegramAuth.statusText.value = "Configurando cofre na nuvem SSD..."
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
                    applicationVersion = "1.9"
                }
                (app as NexusApp).client?.send(request, this)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Pronto! Digite seu número SSD com DDI (+55...)"
                TelegramAuth.step.value = 0
            }
            is TdApi.AuthorizationStateWaitCode -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Código solicitado! Verifique o código recebido."
                TelegramAuth.step.value = 1
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conta protegida com 2FA."
                TelegramAuth.step.value = 2
            }
            is TdApi.AuthorizationStateReady -> {
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conectado à Nuvem SSD!"
                TelegramAuth.step.value = 3
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nexusApp = application as NexusApp
        val prefsCrash = getSharedPreferences("nexus_crash_log", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var crashAnterior by remember { mutableStateOf(prefsCrash.getString("last_crash", null)) }
                var daoPronto by remember { mutableStateOf<NexusDao?>(null) }
                var clientPronto by remember { mutableStateOf<Client?>(null) }
                var erroArranque by remember { mutableStateOf<String?>(null) }
                var carregandoMotor by remember { mutableStateOf(true) }

                LaunchedEffect(crashAnterior) {
                    if (crashAnterior == null) {
                        carregandoMotor = true
                        // Aguarda a janela abrir na tela antes de carregar o C++ e o banco Room
                        delay(300)
                        try {
                            val db = withContext(Dispatchers.IO) { NexusDB.get(this@MainActivity) }
                            val cli = withContext(Dispatchers.IO) { nexusApp.iniciarClienteTDLib() }
                            if (cli != null) {
                                daoPronto = db.dao()
                                clientPronto = cli
                            } else {
                                erroArranque = nexusApp.startupError ?: "Não foi possível iniciar o motor TDLib."
                            }
                        } catch (e: Throwable) {
                            erroArranque = "${e.javaClass.simpleName}: ${e.message}"
                        } finally {
                            carregandoMotor = false
                        }
                    }
                }

                when {
                    crashAnterior != null || erroArranque != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF05070A))
                                .padding(24.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("DIAGNÓSTICO NEXUS SSD", color = Color(0xFFFFD700), fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "O aplicativo detectou um erro de inicialização:",
                                color = Color(0xFFA0AABF),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = Color(0xFF161A25),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFE53935)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = crashAnterior ?: erroArranque ?: "Erro desconhecido",
                                    color = Color(0xFFE53935),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    prefsCrash.edit().remove("last_crash").commit()
                                    erroArranque = null
                                    crashAnterior = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("TENTAR ABRIR NOVAMENTE", color = Color(0xFF05070A), fontWeight = FontWeight.Black)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    try {
                                        File(filesDir, "tdlib").deleteRecursively()
                                        File(filesDir, "nexus_thumbs").deleteRecursively()
                                    } catch (_: Throwable) {}
                                    prefsCrash.edit().remove("last_crash").commit()
                                    erroArranque = null
                                    crashAnterior = null
                                },
                                border = BorderStroke(1.dp, Color(0xFFE53935)),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("LIMPAR CACHE NATIVO E ABRIR", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    carregandoMotor -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF05070A)),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color(0xFFFFD700))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Iniciando Nexus Cloud $NEXUS_VERSION...", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    clientPronto != null && daoPronto != null -> {
                        NexusUI(daoPronto!!, clientPronto!!)
                    }
                }
            }
        }
    }
}
