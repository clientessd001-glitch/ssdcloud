package com.seu.nexuscloud

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.lang.reflect.Modifier as JavaModifier
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
            val causaReal = throwable.cause ?: throwable
            val resumoErro = "${causaReal.javaClass.simpleName}: ${causaReal.message}\n" +
                causaReal.stackTrace.take(10).joinToString("\n") {
                    "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                }

            try {
                getSharedPreferences("nexus_crash_log", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", resumoErro)
                    .commit()
            } catch (_: Throwable) {}

            if (thread.name != "main") {
                startupError = resumoErro
                TelegramAuth.errorText.value = "Aviso interno: ${causaReal.localizedMessage}"
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    @Synchronized
    fun carregarBibliotecasNativas(): String? {
        return try {
            try { System.loadLibrary("cryptox") } catch (_: Throwable) {}
            try { System.loadLibrary("sslx") } catch (_: Throwable) {}
            System.loadLibrary("tdjni")
            File(filesDir, "tdlib").apply { if (!exists()) mkdirs() }
            File(filesDir, "tdlib_files").apply { if (!exists()) mkdirs() }
            null
        } catch (e: Throwable) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            startupError = msg
            msg
        }
    }

    @Synchronized
    fun criarClienteTDLib(): Client? {
        if (client != null) return client
        return try {
            val handler = TelegramHandler(this)
            val novo = Client.create(handler, null, null)
            client = novo
            startupError = null
            novo
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha ao criar cliente TDLib", e)
            startupError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }
}

class TelegramHandler(private val app: NexusApp) : Client.ResultHandler {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var parametrosEnviados = false

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
                        TelegramAuth.errorText.value = "Erro SSD (${obj.code}): ${obj.message}"
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("NexusCloud", "Falha no TelegramHandler", e)
        }
    }

    private fun handleFileProgress(file: TdApi.File?) {
        if (file == null) return
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

    private fun handleAuthState(state: TdApi.AuthorizationState?) {
        if (state == null) return
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (parametrosEnviados) return
                parametrosEnviados = true
                TelegramAuth.statusText.value = "Configurando cofre na nuvem SSD..."

                if (BuildConfig.TELEGRAM_API_ID == 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
                    TelegramAuth.errorText.value = "TELEGRAM_API_ID ou API_HASH vazios nos Secrets!"
                    parametrosEnviados = false
                    return
                }

                val dirDb = File(app.filesDir, "tdlib").apply { if (!exists()) mkdirs() }.absolutePath
                val dirFiles = File(app.filesDir, "tdlib_files").apply { if (!exists()) mkdirs() }.absolutePath
                val modelo = (Build.MODEL ?: "Android").ifBlank { "Android" }
                val versaoSO = (Build.VERSION.RELEASE ?: "13").ifBlank { "13" }

                // Envia a partir da Main Thread para evitar bloqueio/crash na thread nativa C++
                mainHandler.postDelayed({
                    try {
                        val request = TdApi.SetTdlibParameters().apply {
                            databaseDirectory = dirDb
                            useMessageDatabase = true
                            useSecretChats = false
                            apiId = BuildConfig.TELEGRAM_API_ID
                            apiHash = BuildConfig.TELEGRAM_API_HASH
                            systemLanguageCode = "pt"
                            deviceModel = modelo
                            applicationVersion = "1.9"

                            for (f in javaClass.declaredFields) {
                                if (JavaModifier.isStatic(f.modifiers)) continue
                                f.isAccessible = true
                                when (f.name) {
                                    "filesDirectory" -> f.set(this, dirFiles)
                                    "databaseEncryptionKey" -> f.set(this, ByteArray(0))
                                    "systemVersion" -> f.set(this, versaoSO)
                                    "useFileDatabase", "useChatInfoDatabase", "enableStorageOptimizer" -> f.setBoolean(this, true)
                                }
                            }
                        }
                        app.client?.send(request, this@TelegramHandler)
                    } catch (e: Throwable) {
                        parametrosEnviados = false
                        TelegramAuth.errorText.value = "Erro config SSD: ${e.message}"
                    }
                }, 120L)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                parametrosEnviados = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Pronto! Digite seu número SSD com DDI (+55...)"
                TelegramAuth.step.value = 0
            }
            is TdApi.AuthorizationStateWaitCode -> {
                parametrosEnviados = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Código solicitado! Verifique o código recebido."
                TelegramAuth.step.value = 1
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                parametrosEnviados = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conta protegida com 2FA."
                TelegramAuth.step.value = 2
            }
            is TdApi.AuthorizationStateReady -> {
                parametrosEnviados = false
                TelegramAuth.errorText.value = null
                TelegramAuth.statusText.value = "Conectado à Nuvem SSD!"
                TelegramAuth.step.value = 3
            }
            is TdApi.AuthorizationStateClosed -> {
                parametrosEnviados = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nexusApp = application as NexusApp
        val prefs = getSharedPreferences("nexus_crash_log", Context.MODE_PRIVATE)

        // Se a execução anterior fechou sozinha no meio de uma etapa nativa, bloqueia o loop e mostra na tela
        val etapaInterrompida = prefs.getString("etapa_em_curso", null)
        val crashSalvo = prefs.getString("last_crash", null)
        val diagnosticoInicial = when {
            !crashSalvo.isNullOrBlank() -> crashSalvo
            !etapaInterrompida.isNullOrBlank() -> "O aplicativo fechou inesperadamente durante a etapa:\n👉 $etapaInterrompida"
            else -> null
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var erroVisivel by remember { mutableStateOf(diagnosticoInicial) }
                var etapaTexto by remember { mutableStateOf("Iniciando Nexus Cloud $NEXUS_VERSION...") }
                var daoPronto by remember { mutableStateOf<NexusDao?>(null) }
                var clientPronto by remember { mutableStateOf<Client?>(null) }
                var carregando by remember { mutableStateOf(diagnosticoInicial == null) }

                LaunchedEffect(erroVisivel) {
                    if (erroVisivel == null) {
                        carregando = true
                        try {
                            etapaTexto = "Etapa 1/3: A iniciar base de dados local..."
                            prefs.edit().putString("etapa_em_curso", "Etapa 1: Base de dados Room (NexusDB)").commit()
                            delay(250)
                            val db = withContext(Dispatchers.IO) { NexusDB.get(this@MainActivity) }
                            val dao = db.dao()

                            etapaTexto = "Etapa 2/3: A carregar bibliotecas nativas SSD..."
                            prefs.edit().putString("etapa_em_curso", "Etapa 2: System.loadLibrary(tdjni)").commit()
                            delay(250)
                            val erroLib = nexusApp.carregarBibliotecasNativas()
                            if (erroLib != null) {
                                prefs.edit().remove("etapa_em_curso").commit()
                                erroVisivel = "Falha ao carregar biblioteca nativa:\n$erroLib"
                                carregando = false
                                return@LaunchedEffect
                            }

                            etapaTexto = "Etapa 3/3: A iniciar cliente de nuvem SSD..."
                            prefs.edit().putString("etapa_em_curso", "Etapa 3: Client.create / SetTdlibParameters").commit()
                            delay(250)
                            val cli = nexusApp.criarClienteTDLib()
                            if (cli != null) {
                                daoPronto = dao
                                clientPronto = cli
                                carregando = false
                                // Se permaneceu aberto por 1.5s após abrir a UI, limpa o marcador de etapa
                                delay(1500)
                                prefs.edit().remove("etapa_em_curso").commit()
                            } else {
                                prefs.edit().remove("etapa_em_curso").commit()
                                erroVisivel = nexusApp.startupError ?: "Não foi possível criar o cliente TDLib."
                                carregando = false
                            }
                        } catch (e: Throwable) {
                            prefs.edit().remove("etapa_em_curso").commit()
                            erroVisivel = "${e.javaClass.simpleName}: ${e.message}"
                            carregando = false
                        }
                    }
                }

                when {
                    erroVisivel != null -> {
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
                                "O modo de proteção impediu o fechamento automático:",
                                color = Color(0xFFA0AABF),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                color = Color(0xFF161A25),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFE53935)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = erroVisivel ?: "Erro desconhecido",
                                    color = Color(0xFFE53935),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    try {
                                        File(filesDir, "tdlib").deleteRecursively()
                                        File(filesDir, "tdlib_files").deleteRecursively()
                                    } catch (_: Throwable) {}
                                    prefs.edit().clear().commit()
                                    erroVisivel = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Text("LIMPAR PASTA TDLIB E TENTAR NOVAMENTE", color = Color(0xFF05070A), fontWeight = FontWeight.Black, fontSize = 11.sp)
                            }
                        }
                    }
                    carregando -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF05070A))
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color(0xFFFFD700))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(etapaTexto, color = Color(0xFFFFD700), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
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
