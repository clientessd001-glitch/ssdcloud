package com.seu.nexuscloud

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Modifier as JavaModifier
import kotlinx.coroutines.Dispatchers
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

// Verifica se o último encerramento foi ação normal do utilizador (ex: SwipeUpClean) e não um crash
fun foiEncerramentoNormalDoSistema(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val ult = am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()
            if (ult != null) {
                val r = ult.reason
                val ehFalhaCritica = r == ApplicationExitInfo.REASON_CRASH ||
                    r == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    r == ApplicationExitInfo.REASON_ANR ||
                    r == ApplicationExitInfo.REASON_SIGNALED
                return !ehFalhaCritica
            }
        } catch (_: Throwable) {}
    }
    return false
}

fun recolherDiagnosticoNativo(context: Context): String {
    val sb = StringBuilder()

    try {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val libs = libDir.listFiles()?.joinToString(", ") { "${it.name} (${it.length() / 1024}KB)" } ?: "pasta vazia"
        sb.append("• Libs Nativas: $libs\n")
    } catch (_: Throwable) {}

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val exits = am?.getHistoricalProcessExitReasons(context.packageName, 0, 3)
            if (!exits.isNullOrEmpty()) {
                val ult = exits.first()
                sb.append("• Sinal SO: reason=${ult.reason} | status=${ult.status} | desc=${ult.description ?: "sem desc"}\n")
                try {
                    val rawBytes = ult.traceInputStream?.use { it.readBytes() } ?: ByteArray(0)
                    if (rawBytes.isNotEmpty()) {
                        val extraidas = mutableListOf<String>()
                        val seq = StringBuilder()
                        for (b in rawBytes) {
                            val v = b.toInt() and 0xFF
                            if (v in 32..126) {
                                seq.append(v.toChar())
                            } else {
                                if (seq.length >= 5) {
                                    extraidas.add(seq.toString().trim())
                                }
                                seq.setLength(0)
                            }
                        }
                        if (seq.length >= 5) extraidas.add(seq.toString().trim())

                        val cabecalho = extraidas.take(20)
                        val palavrasChave = extraidas.filter { s ->
                            s.contains("Abort", true) ||
                                s.contains("FATAL", true) ||
                                s.contains("JNI", true) ||
                                s.contains("Mismatched", true) ||
                                s.contains("tdjni", true) ||
                                s.contains("TdApi", true) ||
                                s.contains("NoSuch", true) ||
                                s.contains("Error", true)
                        }.take(25)

                        val combinadas = (cabecalho + palavrasChave).distinct()
                        sb.append("\n• Trace Extraído:\n")
                        sb.append(combinadas.joinToString("\n")).append("\n")
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    try {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-b", "all", "-d", "-t", "300"))
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val relevantes = mutableListOf<String>()
        var line: String? = reader.readLine()
        while (line != null) {
            val l = line.replace("\u0000", "")
            if (l.contains("FATAL", true) ||
                l.contains("tdjni", true) ||
                l.contains("TDLib", true) ||
                l.contains("Mismatched", true) ||
                l.contains("Abort", true) ||
                l.contains("AndroidRuntime", true)
            ) {
                relevantes.add(l)
            }
            line = reader.readLine()
        }
        if (relevantes.isNotEmpty()) {
            sb.append("\n• Logcat:\n")
            sb.append(relevantes.takeLast(12).joinToString("\n"))
        }
    } catch (_: Throwable) {}

    return sb.toString().replace("\u0000", "").trim()
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
                causaReal.stackTrace.take(8).joinToString("\n") {
                    "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                }

            try {
                getSharedPreferences("nexus_crash_log", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash_v2", resumoErro.replace("\u0000", ""))
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
    fun carregarSomenteTdjni(): String? {
        return try {
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
                            applicationVersion = "2.2"

                            for (f in javaClass.declaredFields) {
                                if (JavaModifier.isStatic(f.modifiers)) continue
                                f.isAccessible = true
                                when (f.name) {
                                    "filesDirectory" -> f.set(this, dirFiles)
                                    "databaseEncryptionKey" -> f.set(this, ByteArray(0))
                                    "systemVersion" -> f.set(this, versaoSO)
                                    "useFileDatabase", "useChatInfoDatabase", "enableStorageOptimizer" -> f.setBoolean(this, true)
                                    else -> {
                                        if (f.type == String::class.java && f.get(this) == null) f.set(this, "")
                                        if (f.type == ByteArray::class.java && f.get(this) == null) f.set(this, ByteArray(0))
                                    }
                                }
                            }
                        }
                        app.client?.send(request, this@TelegramHandler)
                    } catch (e: Throwable) {
                        parametrosEnviados = false
                        TelegramAuth.errorText.value = "Erro config SSD: ${e.message}"
                    }
                }, 100L)
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

        if (prefs.getString("versao_fix_hash", "") != "v2.2_hash_clean") {
            prefs.edit().clear().putString("versao_fix_hash", "v2.2_hash_clean").commit()
        }

        // Ignora encerramento normal quando o utilizador fecha o app nos Apps Recentes (SwipeUpClean)
        if (foiEncerramentoNormalDoSistema(this)) {
            prefs.edit().remove("etapa_em_curso_v2").commit()
        }

        val etapaInterrompida = prefs.getString("etapa_em_curso_v2", null)
        val crashSalvo = prefs.getString("last_crash_v2", null)
        val logNativo = if (!etapaInterrompida.isNullOrBlank() || !crashSalvo.isNullOrBlank()) {
            recolherDiagnosticoNativo(this)
        } else ""

        val diagnosticoInicial = when {
            !crashSalvo.isNullOrBlank() -> "$crashSalvo\n\n$logNativo"
            !etapaInterrompida.isNullOrBlank() -> "Falha na etapa:\n👉 $etapaInterrompida\n\n$logNativo"
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
                            prefs.edit().putString("etapa_em_curso_v2", "Etapa 1: Base de dados Room (NexusDB)").commit()
                            val db = withContext(Dispatchers.IO) { NexusDB.get(this@MainActivity) }
                            val dao = db.dao()

                            etapaTexto = "Etapa 2/3: A carregar motor nativo SSD..."
                            prefs.edit().putString("etapa_em_curso_v2", "Etapa 2: System.loadLibrary(tdjni)").commit()
                            val erroLib = nexusApp.carregarSomenteTdjni()
                            if (erroLib != null) {
                                prefs.edit().remove("etapa_em_curso_v2").commit()
                                erroVisivel = "Falha ao carregar biblioteca nativa:\n$erroLib\n\n${recolherDiagnosticoNativo(this@MainActivity)}"
                                carregando = false
                                return@LaunchedEffect
                            }

                            etapaTexto = "Etapa 3/3: A iniciar cliente de nuvem SSD..."
                            prefs.edit().putString("etapa_em_curso_v2", "Etapa 3: Client.create / SetTdlibParameters").commit()
                            val cli = nexusApp.criarClienteTDLib()
                            if (cli != null) {
                                // Limpa imediatamente a etapa de arranque para evitar falso alarme ao fechar o app
                                prefs.edit().remove("etapa_em_curso_v2").commit()
                                daoPronto = dao
                                clientPronto = cli
                                carregando = false
                            } else {
                                prefs.edit().remove("etapa_em_curso_v2").commit()
                                erroVisivel = (nexusApp.startupError ?: "Não foi possível criar o cliente TDLib.") +
                                    "\n\n${recolherDiagnosticoNativo(this@MainActivity)}"
                                carregando = false
                            }
                        } catch (e: Throwable) {
                            prefs.edit().remove("etapa_em_curso_v2").commit()
                            erroVisivel = "${e.javaClass.simpleName}: ${e.message}\n\n${recolherDiagnosticoNativo(this@MainActivity)}"
                            carregando = false
                        }
                    }
                }

                when {
                    erroVisivel != null -> {
                        val textoErro = (erroVisivel ?: "Erro desconhecido").replace("\u0000", "")
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF05070A))
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("DIAGNÓSTICO NEXUS SSD", color = Color(0xFFFFD700), fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Detalhes técnicos capturados do sistema:",
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
                                    text = textoErro,
                                    color = Color(0xFFE53935),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    prefs.edit().clear().putString("versao_fix_hash", "v2.2_hash_clean").commit()
                                    erroVisivel = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("CONTINUAR SEM LIMPAR DADOS", color = Color(0xFF05070A), fontWeight = FontWeight.Black, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Erro Nexus", textoErro))
                                    Toast.makeText(this@MainActivity, "Relatório completo copiado!", Toast.LENGTH_SHORT).show()
                                },
                                border = BorderStroke(1.dp, Color(0xFFFFD700)),
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            ) {
                                Text("COPIAR RELATÓRIO DE ERRO", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    try {
                                        File(filesDir, "tdlib").deleteRecursively()
                                        File(filesDir, "tdlib_files").deleteRecursively()
                                    } catch (_: Throwable) {}
                                    prefs.edit().clear().putString("versao_fix_hash", "v2.2_hash_clean").commit()
                                    erroVisivel = null
                                },
                                border = BorderStroke(1.dp, Color(0xFFE53935)),
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            ) {
                                Text("LIMPAR PASTA TDLIB E TENTAR NOVAMENTE", color = Color(0xFFE53935), fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
