package com.seu.nexuscloud

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var inicializado = false

    override fun onCreate() {
        super.onCreate()
        // Captura qualquer erro inesperado para exibir na tela em vez de fechar em tela preta
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("NexusCloud", "Erro crítico capturado", throwable)
            try {
                getSharedPreferences("nexus_crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", "${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString().take(1200)}")
                    .apply()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        inicializarMotorSSD()
    }

    @Synchronized
    fun inicializarMotorSSD() {
        if (inicializado && client != null) return
        try {
            try { System.loadLibrary("cryptox") } catch (_: Throwable) {}
            try { System.loadLibrary("sslx") } catch (_: Throwable) {}
            try { System.loadLibrary("tdjni") } catch (_: Throwable) {}

            // Garante que as pastas nativas existam antes do C++ tentar acessá-las
            File(filesDir, "tdlib").apply { if (!exists()) mkdirs() }
            File(filesDir, "tdlib_files").apply { if (!exists()) mkdirs() }

            val handler = TelegramHandler(this)
            val novoCliente = Client.create(handler, null, null)
            client = novoCliente
            inicializado = true
            startupError = null
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha no arranque da TDLib", e)
            startupError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

class TelegramHandler(private val app: NexusApp) : Client.ResultHandler {
    @Volatile
    private var enviandoParametros = false

    override fun onResult(obj: TdApi.Object?) {
        if (obj == null) return
        try {
            when (obj) {
                is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
                is TdApi.AuthorizationState -> handleAuthState(obj)
                is TdApi.UpdateFile -> handleFileProgress(obj.file)
                is TdApi.Error -> {
                    Log.e("NexusCloud", "Erro TDLib [${obj.code}]: ${obj.message}")
                    if (obj.code != 400 && !obj.message.orEmpty().contains("already", ignoreCase = true)) {
                        TelegramAuth.errorText.value = "Aviso SSD (${obj.code}): ${obj.message}"
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Erro ao processar evento TDLib", e)
        }
    }

    private fun handleFileProgress(file: TdApi.File?) {
        if (file == null) return
        val totalBytes = if (file.expectedSize > 0L) file.expectedSize else file.size
        val remote = file.remote
        val local = file.local

        when {
            remote != null && remote.isUploadingActive -> {
                val enviados = remote.uploadedSize
                val pct = if (totalBytes > 0L) ((enviados * 100L) / totalBytes).toInt().coerceIn(1, 99) else 0
                TelegramAuth.transferProgress.value = pct
                TelegramAuth.transferLabel.value = "Enviando para a nuvem: $pct%"
            }
            local != null && local.isDownloadingActive -> {
                val baixados = local.downloadedSize
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

    private fun handleAuthState(state: TdApi.AuthorizationState?) {
        if (state == null) return
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (enviandoParametros) return
                enviandoParametros = true
                TelegramAuth.statusText.value = "Configurando cofre na nuvem SSD..."

                if (BuildConfig.TELEGRAM_API_ID == 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
                    TelegramAuth.errorText.value = "TELEGRAM_API_ID ou API_HASH vazios nos Secrets!"
                    enviandoParametros = false
                    return
                }

                val dirDb = File(app.filesDir, "tdlib").apply { if (!exists()) mkdirs() }.absolutePath
                val dirFiles = File(app.filesDir, "tdlib_files").apply { if (!exists()) mkdirs() }.absolutePath
                val modeloAparelho = (Build.MODEL ?: "Android").ifBlank { "Android" }
                val versaoSistema = (Build.VERSION.RELEASE ?: "13").ifBlank { "13" }

                val request = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = dirDb
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = BuildConfig.TELEGRAM_API_ID
                    apiHash = BuildConfig.TELEGRAM_API_HASH
                    systemLanguageCode = "pt"
                    deviceModel = modeloAparelho
                    applicationVersion = "1.9"

                    // Preenche todos os campos extras exigidos pelo motor C++ (evita ponteiro NULL na JNI)
                    try {
                        for (f in javaClass.declaredFields) {
                            f.isAccessible = true
                            when (f.name) {
                                "filesDirectory" -> f.set(this, dirFiles)
                                "databaseEncryptionKey" -> f.set(this, ByteArray(0))
                                "systemVersion" -> f.set(this, versaoSistema)
                                "useFileDatabase", "useChatInfoDatabase", "enableStorageOptimizer" -> f.setBoolean(this, true)
                                else -> {
                                    if (f.type == String::class.java && f.get(this) == null) {
                                        f.set(this, "")
                                    } else if (f.type == ByteArray::class.java && f.get(this) == null) {
                                        f.set(this, ByteArray(0))
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                enviarParametrosQuandoClientePronto(request, 0)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                enviandoParametros = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Pronto! Digite seu número SSD com DDI (+55...)"
                TelegramAuth.step.value = 0
            }
            is TdApi.AuthorizationStateWaitCode -> {
                enviandoParametros = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Código solicitado! Verifique o código recebido."
                TelegramAuth.step.value = 1
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                enviandoParametros = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conta protegida com senha 2FA."
                TelegramAuth.step.value = 2
            }
            is TdApi.AuthorizationStateReady -> {
                enviandoParametros = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conectado à Nuvem SSD!"
                TelegramAuth.step.value = 3
            }
            is TdApi.AuthorizationStateClosed -> {
                enviandoParametros = false
            }
        }
    }

    private fun enviarParametrosQuandoClientePronto(request: TdApi.SetTdlibParameters, tentativa: Int) {
        val cli = app.client
        if (cli != null) {
            cli.send(request) { res ->
                if (res is TdApi.Error) {
                    enviandoParametros = false
                    Log.e("NexusCloud", "Erro SetTdlibParameters: ${res.message}")
                    TelegramAuth.errorText.value = "Erro config SSD (${res.code}): ${res.message}"
                }
            }
        } else if (tentativa < 10) {
            Handler(Looper.getMainLooper()).postDelayed({
                enviarParametrosQuandoClientePronto(request, tentativa + 1)
            }, 60L)
        } else {
            enviandoParametros = false
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nexusApp = application as NexusApp
        nexusApp.inicializarMotorSSD()

        val db = try {
            NexusDB.get(this)
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Erro ao iniciar banco local", e)
            null
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val activeClient = nexusApp.client
                if (activeClient != null && db != null) {
                    NexusUI(db.dao(), activeClient)
                } else {
                    val ultimoCrash = remember {
                        getSharedPreferences("nexus_crash_log", MODE_PRIVATE).getString("last_crash", null)
                    }
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
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            nexusApp.startupError ?: ultimoCrash ?: "Erro ao inicializar banco ou motor SSD.",
                            color = Color(0xFFE53935),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
