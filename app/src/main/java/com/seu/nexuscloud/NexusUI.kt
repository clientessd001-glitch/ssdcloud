package com.seu.nexuscloud

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Modifier as JavaModifier
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

const val NEXUS_VERSION = "v2.2"

val BgBlack = Color(0xFF05070A)
val SurfaceDark = Color(0xFF161A25)
val SurfaceDarker = Color(0xFF10131B)
val Gold = Color(0xFFFFD700)
val DangerRed = Color(0xFFE53935)
val TextGrey = Color(0xFFA0AABF)

// Mascara o número SSD visualmente com asteriscos
fun mascararNumeroComAsterisco(numero: String): String {
    val limpo = numero.trim()
    if (limpo.isEmpty()) return "********"
    return "*".repeat(limpo.length.coerceAtLeast(8))
}

// === CONTROLADOR GLOBAL DE TRANSFERÊNCIAS COM SERVIÇO EM SEGUNDO PLANO E NOTIFICAÇÃO FIXA ===
object NexusTransfer {
    val progress = MutableStateFlow<Int?>(null)
    val label = MutableStateFlow("")
    val pausado = MutableStateFlow(false)
    val cancelado = MutableStateFlow(false)
    val fileIdAtivo = MutableStateFlow(0)
    val ehUpload = MutableStateFlow(false)
    val enviandoEmSegundoPlano = MutableStateFlow(false)
    val bannerStatusGlobal = MutableStateFlow("")

    @Volatile var jobAtivo: Job? = null
    @Volatile var chatIdUploadAtivo: Long = 0L
    @Volatile var msgIdUploadAtivo: Long = 0L
    @Volatile private var appContext: Context? = null

    fun iniciar(texto: String, upload: Boolean, fileId: Int = 0, context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        pausado.value = false
        cancelado.value = false
        ehUpload.value = upload
        enviandoEmSegundoPlano.value = upload
        fileIdAtivo.value = fileId
        chatIdUploadAtivo = 0L
        msgIdUploadAtivo = 0L
        progress.value = 5
        label.value = texto

        if (upload) {
            appContext?.let {
                NexusUploadService.iniciar(
                    context = it,
                    titulo = "Nexus Cloud $NEXUS_VERSION • Upload Ativo",
                    texto = texto,
                    progresso = 5
                )
            }
        }
    }

    fun atualizarProgresso(pct: Int, texto: String? = null, forcarNotif: Boolean = false) {
        val pctSafe = pct.coerceIn(1, 100)
        progress.value = pctSafe
        if (texto != null) label.value = texto
        if (ehUpload.value) {
            appContext?.let {
                NexusUploadService.atualizar(
                    context = it,
                    titulo = if (pausado.value) "Nexus Cloud $NEXUS_VERSION • Envio Pausado" else "Nexus Cloud $NEXUS_VERSION • Enviando ($pctSafe%)",
                    texto = texto ?: label.value,
                    progresso = pctSafe,
                    pausado = pausado.value,
                    forcar = forcarNotif
                )
            }
        }
    }

    fun alternarPausa(client: Client? = null) {
        val novoEstado = !pausado.value
        pausado.value = novoEstado
        val fId = fileIdAtivo.value
        if (ehUpload.value) {
            appContext?.let {
                val pctAtual = progress.value ?: 10
                NexusUploadService.atualizar(
                    context = it,
                    titulo = if (novoEstado) "Nexus Cloud $NEXUS_VERSION • Envio Pausado" else "Nexus Cloud $NEXUS_VERSION • Enviando ($pctAtual%)",
                    texto = label.value.ifBlank { "Transferência SSD" },
                    progresso = pctAtual,
                    pausado = novoEstado,
                    forcar = true
                )
            }
        } else if (client != null && fId != 0) {
            if (novoEstado) {
                client.send(TdApi.CancelDownloadFile().apply {
                    this.fileId = fId
                    this.onlyIfPending = false
                }) {}
            } else {
                client.send(TdApi.DownloadFile().apply {
                    this.fileId = fId
                    this.priority = 32
                    this.offset = 0
                    this.limit = 0
                    this.synchronous = false
                }) {}
            }
        }
    }

    fun cancelar(client: Client? = null) {
        cancelado.value = true
        pausado.value = false
        val fId = fileIdAtivo.value
        val cId = chatIdUploadAtivo
        val mId = msgIdUploadAtivo

        if (client != null) {
            if (cId != 0L && mId != 0L) {
                try {
                    client.send(TdApi.DeleteMessages().apply {
                        this.chatId = cId
                        this.messageIds = longArrayOf(mId)
                        this.revoke = true
                    }) {}
                } catch (_: Exception) {}
            }
            if (fId != 0) {
                try {
                    client.send(TdApi.CancelDownloadFile().apply {
                        this.fileId = fId
                        this.onlyIfPending = false
                    }) {}
                } catch (_: Exception) {}
                try {
                    client.send(TdApi.CancelPreliminaryUploadFile().apply {
                        this.fileId = fId
                    }) {}
                } catch (_: Exception) {}
                try {
                    client.send(TdApi.DeleteFile().apply {
                        this.fileId = fId
                    }) {}
                } catch (_: Exception) {}
            }
        }

        try {
            jobAtivo?.cancel()
        } catch (_: Exception) {}
        jobAtivo = null

        appContext?.let { NexusUploadService.encerrar(it) }

        progress.value = null
        label.value = ""
        fileIdAtivo.value = 0
        ehUpload.value = false
        enviandoEmSegundoPlano.value = false
        chatIdUploadAtivo = 0L
        msgIdUploadAtivo = 0L
    }

    fun encerrar() {
        appContext?.let { NexusUploadService.encerrar(it) }
        progress.value = null
        label.value = ""
        pausado.value = false
        fileIdAtivo.value = 0
        ehUpload.value = false
        enviandoEmSegundoPlano.value = false
        chatIdUploadAtivo = 0L
        msgIdUploadAtivo = 0L
    }
}

// === CONTROLADOR DE BARRA DE LEITURA E CONTADOR DE ARQUIVOS NA NUVEM ===
object NexusLeituraNuvem {
    val ativo = MutableStateFlow(false)
    val lidos = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val subProgressoItem = MutableStateFlow(0)
    val titulo = MutableStateFlow("")
    val pausado = MutableStateFlow(false)

    fun iniciar(totalItens: Int, texto: String) {
        if (totalItens <= 0) {
            encerrar()
            return
        }
        pausado.value = false
        total.value = totalItens
        lidos.value = 0
        subProgressoItem.value = 0
        titulo.value = texto
        ativo.value = true
    }

    fun alternarPausa() {
        pausado.value = !pausado.value
    }

    fun avancar(novoLido: Int, novoTotal: Int = total.value, texto: String? = null) {
        if (novoTotal > 0) total.value = novoTotal
        lidos.value = novoLido.coerceIn(0, total.value.coerceAtLeast(1))
        subProgressoItem.value = 0
        if (texto != null) titulo.value = texto
        ativo.value = true
    }

    fun atualizarSubProgresso(pctItem: Int, texto: String? = null) {
        subProgressoItem.value = pctItem.coerceIn(0, 99)
        if (texto != null) titulo.value = texto
    }

    fun concluirItem() {
        val t = total.value
        if (t <= 0) {
            encerrar()
            return
        }
        val prox = (lidos.value + 1).coerceAtMost(t)
        lidos.value = prox
        subProgressoItem.value = 0
        if (prox >= t) {
            encerrar()
        }
    }

    fun encerrar() {
        ativo.value = false
        pausado.value = false
        lidos.value = 0
        total.value = 0
        subProgressoItem.value = 0
        titulo.value = ""
    }
}

// === CACHE RÁPIDO DE MINIATURAS (.JPG) E GESTOR DE CACHE DO SSD NEXUS ===
object NexusThumbCache {
    val mapaMiniaturas = mutableStateMapOf<Long, String>()
    val mapaArquivosCompletos = mutableStateMapOf<Long, String>()
    val mapaThumbIdsNuvem = mutableStateMapOf<Int, Int>()
    val downloadSemaphore = Semaphore(4)
    val emProgresso = Collections.synchronizedSet(mutableSetOf<Long>())
    private var inicializado = false

    fun inicializar(context: Context) {
        if (inicializado) return
        inicializado = true
        val prefs = context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
        for ((k, v) in prefs.all) {
            if (v is String && v.isNotBlank()) {
                when {
                    k.startsWith("ts_") -> {
                        val ts = k.removePrefix("ts_").toLongOrNull()
                        if (ts != null) {
                            val resolvido = resolverCaminhoCapaJpg(v)
                            if (resolvido.isNotBlank()) {
                                mapaMiniaturas[ts] = resolvido
                            }
                        }
                    }
                    k.startsWith("full_") -> {
                        val ts = k.removePrefix("full_").toLongOrNull()
                        if (ts != null && arquivoCompletoExisteLocalmente(v)) {
                            mapaArquivosCompletos[ts] = v
                        }
                    }
                    k.startsWith("thid_") -> {
                        val fId = k.removePrefix("thid_").toIntOrNull()
                        val tId = v.toIntOrNull()
                        if (fId != null && tId != null && tId != 0) mapaThumbIdsNuvem[fId] = tId
                    }
                }
            }
        }
    }

    fun obterMiniatura(arq: ArquivoNuvem): String {
        val emCache = mapaMiniaturas[arq.dataUpload]
        if (!emCache.isNullOrBlank() && miniaturaExisteLocalmente(emCache, arq.nomeArquivo)) {
            return resolverCaminhoCapaJpg(emCache)
        }
        val doArq = arq.caminhoMiniatura ?: ""
        if (doArq.isNotBlank() && miniaturaExisteLocalmente(doArq, arq.nomeArquivo)) {
            val resolvido = resolverCaminhoCapaJpg(doArq)
            if (resolvido.isNotBlank()) {
                mapaMiniaturas[arq.dataUpload] = resolvido
                return resolvido
            }
        }
        return ""
    }

    fun obterArquivoCompleto(arq: ArquivoNuvem): String {
        val emCache = mapaArquivosCompletos[arq.dataUpload]
        if (!emCache.isNullOrBlank() && arquivoCompletoExisteLocalmente(emCache)) return emCache
        val doArq = arq.caminhoMiniatura ?: ""
        if (doArq.isNotBlank() && arquivoCompletoExisteLocalmente(doArq)) return doArq
        return ""
    }

    fun salvarMiniatura(context: Context, dataUpload: Long, caminho: String) {
        val capaLimpa = resolverCaminhoCapaJpg(caminho)
        if (capaLimpa.isBlank()) return
        if (mapaMiniaturas[dataUpload] == capaLimpa) return
        mapaMiniaturas[dataUpload] = capaLimpa
        context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
            .edit()
            .putString("ts_$dataUpload", capaLimpa)
            .apply()
    }

    fun salvarArquivoCompleto(context: Context, dataUpload: Long, caminho: String) {
        if (caminho.isBlank() || !arquivoCompletoExisteLocalmente(caminho)) return
        if (mapaArquivosCompletos[dataUpload] == caminho) return
        mapaArquivosCompletos[dataUpload] = caminho
        context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
            .edit()
            .putString("full_$dataUpload", caminho)
            .apply()
    }

    fun salvarThumbIdNuvem(context: Context, fileId: Int, thumbFileId: Int) {
        if (fileId == 0 || thumbFileId == 0) return
        if (mapaThumbIdsNuvem[fileId] == thumbFileId) return
        mapaThumbIdsNuvem[fileId] = thumbFileId
        context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
            .edit()
            .putString("thid_$fileId", thumbFileId.toString())
            .apply()
    }

    fun limparCapasDeVideos(context: Context, arquivos: List<ArquivoNuvem>): Int {
        var removidas = 0
        val prefs = context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val dir = File(context.filesDir, "nexus_thumbs")

        arquivos.filter { ehArquivoVideo(it.nomeArquivo) }.forEach { vid ->
            val atual = mapaMiniaturas[vid.dataUpload] ?: vid.caminhoMiniatura ?: ""
            if (atual.isNotBlank()) {
                val limpo = if (atual.startsWith("file://")) Uri.parse(atual).path ?: atual.removePrefix("file://") else atual
                val f = File(limpo)
                if (f.exists()) f.delete()
                val fFrame = File("${limpo}_frame.jpg")
                if (fFrame.exists()) fFrame.delete()
            }
            mapaMiniaturas.remove(vid.dataUpload)
            editor.remove("ts_${vid.dataUpload}")
            emProgresso.remove(vid.dataUpload)
            removidas++
        }

        if (dir.exists()) {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("fast_") || ehArquivoVideo(f.name.removeSuffix("_frame.jpg"))) {
                    f.delete()
                }
            }
        }
        editor.apply()
        return removidas
    }

    fun limparTodoCacheLocal(context: Context) {
        mapaMiniaturas.clear()
        mapaArquivosCompletos.clear()
        emProgresso.clear()
        context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE).edit().clear().apply()
        try {
            File(context.filesDir, "nexus_thumbs").deleteRecursively()
            File(context.filesDir, "tdlib_upload_cache").deleteRecursively()
            context.cacheDir.deleteRecursively()
        } catch (_: Exception) {}
    }
}

fun calcularUsoCacheLocal(context: Context): Triple<Long, Int, Int> {
    var totalBytes = 0L
    var qtdThumbs = 0
    var qtdTemp = 0

    val dirThumbs = File(context.filesDir, "nexus_thumbs")
    if (dirThumbs.exists()) {
        dirThumbs.walkTopDown().forEach { f ->
            if (f.isFile) {
                totalBytes += f.length()
                if (f.name.endsWith("_frame.jpg")) qtdThumbs++ else qtdTemp++
            }
        }
    }

    val dirUpload = File(context.filesDir, "tdlib_upload_cache")
    if (dirUpload.exists()) {
        dirUpload.walkTopDown().forEach { f ->
            if (f.isFile) {
                totalBytes += f.length()
                qtdTemp++
            }
        }
    }

    context.cacheDir?.walkTopDown()?.forEach { f ->
        if (f.isFile) {
            totalBytes += f.length()
            qtdTemp++
        }
    }

    return Triple(totalBytes, qtdThumbs, qtdTemp)
}

fun limparCacheUploadsTemporarios(context: Context): Long {
    var liberado = 0L
    try {
        val dirUpload = File(context.filesDir, "tdlib_upload_cache")
        if (dirUpload.exists()) {
            dirUpload.walkTopDown().forEach { if (it.isFile) liberado += it.length() }
            dirUpload.deleteRecursively()
        }
        val dirThumbs = File(context.filesDir, "nexus_thumbs")
        if (dirThumbs.exists()) {
            dirThumbs.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.endsWith("_frame.jpg")) {
                    liberado += f.length()
                    f.delete()
                }
            }
        }
        context.cacheDir?.listFiles()?.forEach { f ->
            if (f.isFile) liberado += f.length()
            f.deleteRecursively()
        }
        NexusThumbCache.mapaArquivosCompletos.clear()
    } catch (_: Exception) {}
    return liberado
}

fun obterConfigAutoLimpezaTemp(context: Context): Pair<Boolean, Int> {
    val prefs = context.getSharedPreferences("nexus_cache_auto_prefs", Context.MODE_PRIVATE)
    val ativo = prefs.getBoolean("auto_clean_temp_enabled", false)
    val limite = prefs.getInt("auto_clean_temp_limit", 50).coerceIn(1, 99999)
    return Pair(ativo, limite)
}

fun salvarConfigAutoLimpezaTemp(context: Context, ativo: Boolean, limiteQtd: Int) {
    val limiteSeguro = limiteQtd.coerceIn(1, 99999)
    context.getSharedPreferences("nexus_cache_auto_prefs", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("auto_clean_temp_enabled", ativo)
        .putInt("auto_clean_temp_limit", limiteSeguro)
        .apply()
}

// Verifica a quantidade atual de temporários e limpa automaticamente se atingir o limite definido
fun verificarELimparTempAutomatico(context: Context): Pair<Boolean, Long> {
    val (ativo, limite) = obterConfigAutoLimpezaTemp(context)
    if (!ativo) return Pair(false, 0L)

    // Trava de segurança: nunca apaga temporários durante um upload ou download ativo
    if (NexusTransfer.enviandoEmSegundoPlano.value || NexusTransfer.progress.value != null) {
        return Pair(false, 0L)
    }

    val (_, _, qtdTemp) = calcularUsoCacheLocal(context)
    if (qtdTemp >= limite) {
        val liberado = limparCacheUploadsTemporarios(context)
        return Pair(true, liberado)
    }
    return Pair(false, 0L)
}

fun resolverCaminhoCapaJpg(caminho: String?): String {
    val c = caminho ?: return ""
    if (c.isBlank()) return ""
    if (c.startsWith("content://")) return c
    val limpo = if (c.startsWith("file://")) Uri.parse(c).path ?: c.removePrefix("file://") else c
    if (limpo.isBlank()) return ""

    if (limpo.endsWith("_frame.jpg")) {
        val f = File(limpo)
        return if (f.exists() && f.length() > 256L) Uri.fromFile(f).toString() else ""
    }

    val fFrame = File("${limpo}_frame.jpg")
    if (fFrame.exists() && fFrame.length() > 256L) {
        return Uri.fromFile(fFrame).toString()
    }

    val fOriginal = File(limpo)
    if (fOriginal.exists() && fOriginal.length() > 256L && ehArquivoImagem(fOriginal.name)) {
        return Uri.fromFile(fOriginal).toString()
    }
    return ""
}

fun limparPrefixoTimestamp(nome: String): String {
    return nome.trim()
        .replace(Regex("^fast_\\d+_"), "")
        .replace(Regex("^thumb_\\d+_"), "")
        .replace(Regex("^\\d{10,16}_"), "")
        .removeSuffix("_frame.jpg")
        .trim()
}

fun ehArquivoImagem(nomeArquivo: String): Boolean {
    val limpo = nomeArquivo.trim()
    val ext = limpo.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return ext in listOf("JPG", "JPEG", "JPE", "JFIF", "PNG", "WEBP", "HEIC", "HEIF", "GIF", "BMP", "AVIF") ||
        ext.startsWith("JP") ||
        limpo.contains("IMG", ignoreCase = true) ||
        limpo.contains("PHOTO", ignoreCase = true) ||
        limpo.contains("Screenshot", ignoreCase = true) ||
        (Regex("^\\d{8}_\\d{6}.*").matches(limpo) && !ehArquivoVideo(limpo))
}

fun ehArquivoVideo(nomeArquivo: String): Boolean {
    val limpo = nomeArquivo.trim()
    val ext = limpo.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return ext in listOf("MP4", "MKV", "MOV", "AVI", "WEBM", "3GP", "M4V") ||
        limpo.contains("VID", ignoreCase = true) ||
        limpo.contains("Video", ignoreCase = true)
}

class UIViewModel(private val dao: NexusDao) : ViewModel() {
    val arquivos = dao.buscarTodos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pastas = dao.buscarPastas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val armazenamento = dao.calcularUsoTotal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    suspend fun obterArquivosReais(): List<ArquivoNuvem> = withContext(Dispatchers.IO) {
        dao.buscarTodos().first()
    }

    suspend fun obterPastasReais(): List<PastaNuvem> = withContext(Dispatchers.IO) {
        dao.buscarPastas().first()
    }

    fun criarPasta(nome: String) = viewModelScope.launch {
        val limpo = nome.trim()
        if (limpo.isNotBlank()) {
            val atuais = obterPastasReais()
            val jaExiste = atuais.any { it.nomePasta.trim().equals(limpo, ignoreCase = true) }
            if (!jaExiste) {
                dao.criarPasta(PastaNuvem(nomePasta = limpo))
            }
        }
    }

    suspend fun salvarArquivoSincrono(arq: ArquivoNuvem, forcarNovoRegistro: Boolean = false) = withContext(Dispatchers.IO) {
        val atuais = dao.buscarTodos().first()
        val nomeLimpo = limparPrefixoTimestamp(arq.nomeArquivo)
        val capaValida = resolverCaminhoCapaJpg(arq.caminhoMiniatura).ifBlank { arq.caminhoMiniatura }

        val correspondentes = atuais.filter {
            (arq.id != 0 && it.id == arq.id) || it.dataUpload == arq.dataUpload
        }

        if (correspondentes.isNotEmpty()) {
            val principal = correspondentes.first()
            val thumbAntigaOk = miniaturaExisteLocalmente(principal.caminhoMiniatura, nomeLimpo)
            val thumbNovaOk = miniaturaExisteLocalmente(arq.caminhoMiniatura, nomeLimpo)
            val precisaAtualizarFileId = arq.fileId != 0 && principal.fileId != arq.fileId

            if (forcarNovoRegistro || correspondentes.size > 1 || (!thumbAntigaOk && thumbNovaOk) || precisaAtualizarFileId) {
                correspondentes.forEach { dao.apagarArquivo(it.id) }
                dao.salvarArquivo(
                    principal.copy(
                        id = 0,
                        nomeArquivo = nomeLimpo,
                        tamanhoBytes = if (arq.tamanhoBytes > 0L) arq.tamanhoBytes else principal.tamanhoBytes,
                        dataUpload = arq.dataUpload,
                        caminhoMiniatura = if (thumbNovaOk) capaValida else principal.caminhoMiniatura,
                        fileId = if (arq.fileId != 0) arq.fileId else principal.fileId
                    )
                )
            }
        } else {
            dao.salvarArquivo(arq.copy(id = 0, nomeArquivo = nomeLimpo, caminhoMiniatura = capaValida))
        }
    }

    fun salvarArquivo(arq: ArquivoNuvem, forcarNovoRegistro: Boolean = false) = viewModelScope.launch {
        salvarArquivoSincrono(arq, forcarNovoRegistro)
    }

    fun apagar(id: Int) = viewModelScope.launch { dao.apagarArquivo(id) }

    fun atualizarCaminhoMiniatura(context: Context, arq: ArquivoNuvem, novoCaminho: String) {
        val capaJpg = resolverCaminhoCapaJpg(novoCaminho)
        if (capaJpg.isNotBlank()) {
            NexusThumbCache.salvarMiniatura(context, arq.dataUpload, capaJpg)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val atuais = dao.buscarTodos().first()
                    val duplicados = atuais.filter { (arq.id != 0 && it.id == arq.id) || it.dataUpload == arq.dataUpload }
                    duplicados.forEach { dao.apagarArquivo(it.id) }
                    val base = duplicados.firstOrNull() ?: arq
                    dao.salvarArquivo(base.copy(id = 0, caminhoMiniatura = capaJpg))
                } catch (_: Exception) {}
            }
        }
    }

    fun renomearArquivo(arq: ArquivoNuvem, novoNome: String) = viewModelScope.launch {
        if (novoNome.isNotBlank()) {
            val atuais = obterArquivosReais()
            val correspondentes = atuais.filter { (arq.id != 0 && it.id == arq.id) || it.dataUpload == arq.dataUpload }
            correspondentes.forEach { dao.apagarArquivo(it.id) }
            val base = correspondentes.firstOrNull() ?: arq
            dao.salvarArquivo(base.copy(id = 0, nomeArquivo = novoNome.trim()))
        }
    }
}

// === FUNÇÕES AUXILIARES TDLIB ASSÍNCRONAS COM SUPORTE A PAUSA, CANCELAMENTO E NOTIFICAÇÃO ===
suspend fun obterInfoArquivoTDLib(client: Client, fileId: Int): TdApi.File? {
    if (fileId == 0) return null
    val def = CompletableDeferred<TdApi.File?>()
    client.send(TdApi.GetFile(fileId)) { obj ->
        def.complete(obj as? TdApi.File)
    }
    return def.await()
}

suspend fun baixarArquivoTDLibAssincrono(
    client: Client,
    fileId: Int,
    prioridade: Int = 16,
    maxTentativas: Int = 400,
    controlarPausaTransfer: Boolean = false,
    onProgress: ((Int, Long, Long) -> Unit)? = null
): File? {
    if (fileId == 0) return null

    if (controlarPausaTransfer) {
        NexusTransfer.fileIdAtivo.value = fileId
    }

    val infoInicial = obterInfoArquivoTDLib(client, fileId)
    val caminhoInicial = infoInicial?.local?.path ?: ""
    if (caminhoInicial.isNotBlank() && infoInicial?.local?.isDownloadingCompleted == true) {
        val f = File(caminhoInicial)
        if (f.exists() && f.length() > 100L) {
            onProgress?.invoke(100, f.length(), f.length())
            return f
        }
    }

    val req = TdApi.DownloadFile().apply {
        this.fileId = fileId
        this.priority = prioridade
        this.offset = 0
        this.limit = 0
        this.synchronous = false
    }
    client.send(req) {}

    var tentativas = 0
    var semEvolucao = 0
    var ultimoBaixado = -1L

    while (tentativas < maxTentativas) {
        if (controlarPausaTransfer && NexusTransfer.cancelado.value) {
            client.send(TdApi.CancelDownloadFile().apply {
                this.fileId = fileId
                this.onlyIfPending = false
            }) {}
            return null
        }

        var estavaPausado = false
        while ((controlarPausaTransfer && NexusTransfer.pausado.value) ||
            (!controlarPausaTransfer && NexusLeituraNuvem.pausado.value)
        ) {
            if (!estavaPausado) {
                estavaPausado = true
                client.send(TdApi.CancelDownloadFile().apply {
                    this.fileId = fileId
                    this.onlyIfPending = false
                }) {}
            }
            delay(200)
            if (controlarPausaTransfer && NexusTransfer.cancelado.value) return null
        }
        if (estavaPausado) {
            client.send(req) {}
            semEvolucao = 0
        }

        delay(150)
        if (controlarPausaTransfer && NexusTransfer.cancelado.value) {
            client.send(TdApi.CancelDownloadFile().apply {
                this.fileId = fileId
                this.onlyIfPending = false
            }) {}
            return null
        }

        tentativas++
        val info = obterInfoArquivoTDLib(client, fileId) ?: break
        val local = info.local
        val path = local?.path ?: ""
        val baixado = local?.downloadedSize ?: 0L
        val total = when {
            info.size > 0 -> info.size
            info.expectedSize > 0 -> info.expectedSize
            else -> 0L
        }

        if (total > 0L && baixado > 0L) {
            val pct = ((baixado * 100L) / total).toInt().coerceIn(1, 99)
            onProgress?.invoke(pct, baixado, total)
        } else {
            val pctEst = (5 + tentativas).coerceAtMost(90)
            onProgress?.invoke(pctEst, baixado, total)
        }

        if (local?.isDownloadingCompleted == true && path.isNotBlank()) {
            val arqFinal = File(path)
            if (arqFinal.exists() && arqFinal.length() > 100L) {
                onProgress?.invoke(100, arqFinal.length(), arqFinal.length())
                return arqFinal
            }
        }

        if (baixado == ultimoBaixado) {
            semEvolucao++
        } else {
            semEvolucao = 0
            ultimoBaixado = baixado
        }

        if (local?.isDownloadingActive == false && local.isDownloadingCompleted == false) {
            if (tentativas == 4 || tentativas == 12 || tentativas == 24) {
                client.send(req) {}
            } else if (semEvolucao > 25) {
                break
            }
        }
    }
    return null
}

suspend fun aguardarUploadTDLibComPausa(
    client: Client,
    fileId: Int,
    nomeArquivo: String,
    loteTag: String = "",
    chatId: Long = 0L,
    messageId: Long = 0L
): Pair<Boolean, String> {
    NexusTransfer.fileIdAtivo.value = fileId
    var tentativas = 0
    var inativoCount = 0

    while (tentativas < 1800) {
        if (NexusTransfer.cancelado.value) {
            if (fileId != 0) client.send(TdApi.DeleteFile().apply { this.fileId = fileId }) {}
            return Pair(false, "Envio cancelado.")
        }
        while (NexusTransfer.pausado.value) {
            delay(200)
            if (NexusTransfer.cancelado.value) {
                if (fileId != 0) client.send(TdApi.DeleteFile().apply { this.fileId = fileId }) {}
                return Pair(false, "Envio cancelado.")
            }
        }

        delay(200)
        if (NexusTransfer.cancelado.value) {
            if (fileId != 0) client.send(TdApi.DeleteFile().apply { this.fileId = fileId }) {}
            return Pair(false, "Envio cancelado.")
        }

        tentativas++

        var msgConfirmadaNoServidor = (chatId == 0L || messageId == 0L)
        if (!msgConfirmadaNoServidor) {
            val defMsg = CompletableDeferred<TdApi.Object?>()
            client.send(TdApi.GetMessage(chatId, messageId)) { defMsg.complete(it) }
            when (val mObj = defMsg.await()) {
                is TdApi.Message -> {
                    when (val st = mObj.sendingState) {
                        null -> msgConfirmadaNoServidor = true
                        is TdApi.MessageSendingStateFailed -> {
                            val errObj = try { st.javaClass.getField("error").get(st) as? TdApi.Error } catch (_: Throwable) { null }
                            val code = errObj?.code ?: 0
                            val msg = errObj?.message ?: st.toString()
                            return Pair(false, "Falha no servidor SSD ($code): $msg")
                        }
                    }
                }
                is TdApi.Error -> {
                    if (mObj.code == 404 && !NexusTransfer.cancelado.value) {
                        msgConfirmadaNoServidor = true
                    }
                }
            }
        }

        val info = if (fileId != 0) obterInfoArquivoTDLib(client, fileId) else null
        val remote = info?.remote
        val enviado = remote?.uploadedSize ?: 0L
        val total = when {
            (info?.size ?: 0L) > 0L -> info!!.size
            (info?.expectedSize ?: 0L) > 0L -> info!!.expectedSize
            else -> 0L
        }

        val uploadArquivoOk = (fileId == 0) || (remote != null && remote.isUploadingCompleted && !remote.id.isNullOrEmpty())

        if (uploadArquivoOk && msgConfirmadaNoServidor) {
            NexusTransfer.atualizarProgresso(100, "Concluído $loteTag'$nomeArquivo' (100%)", forcarNotif = true)
            return Pair(true, "OK")
        }

        if (total > 0L && enviado > 0L) {
            val pctRede = ((enviado * 100L) / total).toInt().coerceIn(1, 99)
            val pctTotal = (40 + (pctRede * 58) / 100).coerceIn(40, 98)
            val mbEnv = String.format(Locale.US, "%.1f", enviado / (1024.0 * 1024.0))
            val mbTot = String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0))
            val txtStatus = if (uploadArquivoOk) {
                "Confirmando na nuvem SSD $loteTag'$nomeArquivo' (99%)..."
            } else {
                "Enviando $loteTag'$nomeArquivo': $mbEnv / $mbTot MB ($pctTotal%)"
            }
            NexusTransfer.atualizarProgresso(pctTotal, txtStatus)
        } else if (uploadArquivoOk) {
            NexusTransfer.atualizarProgresso(99, "Confirmando na nuvem SSD $loteTag'$nomeArquivo' (99%)...")
        }

        if (remote != null && !remote.isUploadingActive && !uploadArquivoOk) {
            inativoCount++
            if (inativoCount >= 80 && msgConfirmadaNoServidor) return Pair(true, "OK")
        } else {
            inativoCount = 0
        }
    }
    return Pair(true, "OK")
}
// === FIM DA PARTE 1 DE 3 ===
// === CONTINUAÇÃO: PARTE 2 DE 3 (COLE LOGO ABAIXO DA PARTE 1) ===

// === CREDENCIAIS MESTRES DO PAINEL ADMIN (ALTERE ANTES DE COMPILAR SE DESEJAR) ===
private const val ADMIN_USER_MESTRE = "adminssd"
private const val ADMIN_PIN_MESTRE = "202699"

// Trava global para impedir limpezas repetidas no Room enquanto o cofre sincroniza com a nuvem
object NexusSyncState {
    val emAndamento = MutableStateFlow(false)
}

data class StatusCofreLocal(
    val bloqueado: Boolean,
    val motivo: String,
    val atualizadoEm: Long
)

data class PerfilAdminInfo(
    val chaveUsuario: String,
    val nomeExibicao: String,
    val pinAtual: String,
    val modo: String,
    val chatIdCanal: Long,
    val bloqueado: Boolean,
    val motivoBloqueio: String,
    val ultimaAtualizacao: Long
)

fun calcularSha256Bytes(texto: String): ByteArray {
    return try {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(texto.toByteArray(Charsets.UTF_8))
    } catch (_: Throwable) {
        texto.toByteArray(Charsets.UTF_8)
    }
}

fun ehCredencialAdmin(nomeUsuario: String, pinDigitado: String): Boolean {
    val userLimpo = normalizarUsuario(nomeUsuario)
    val pinLimpo = pinDigitado.trim()
    if (userLimpo != normalizarUsuario(ADMIN_USER_MESTRE)) return false
    if (pinLimpo.isBlank()) return false
    return java.security.MessageDigest.isEqual(
        calcularSha256Bytes(pinLimpo),
        calcularSha256Bytes(ADMIN_PIN_MESTRE.trim())
    )
}

// === TRAVA ANTI-OFFLINE (STATUS LOCAL DO COFRE) ===
fun salvarStatusCofreLocal(
    context: Context,
    usuario: String,
    bloqueado: Boolean,
    motivo: String,
    atualizadoEmSeg: Long = System.currentTimeMillis() / 1000L
) {
    val chave = normalizarUsuario(usuario)
    if (chave.isBlank()) return
    val prefs = context.getSharedPreferences("nexus_vault_status", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("blocked_$chave", bloqueado)
        .putString("reason_$chave", motivo.trim())
        .putLong("ts_$chave", atualizadoEmSeg)
        .commit()
}

fun obterStatusCofreLocal(context: Context, usuario: String): StatusCofreLocal {
    val chave = normalizarUsuario(usuario)
    if (chave.isBlank()) return StatusCofreLocal(false, "", 0L)
    val prefs = context.getSharedPreferences("nexus_vault_status", Context.MODE_PRIVATE)
    return StatusCofreLocal(
        bloqueado = prefs.getBoolean("blocked_$chave", false),
        motivo = prefs.getString("reason_$chave", "") ?: "",
        atualizadoEm = prefs.getLong("ts_$chave", 0L)
    )
}

fun extrairStatusCofreDasMensagens(mensagens: List<TdApi.Message>): Map<String, StatusCofreLocal> {
    val ordenadas = mensagens.sortedWith(
        compareByDescending<TdApi.Message> { it.date }.thenByDescending { it.id }
    )
    val mapaStatus = mutableMapOf<String, StatusCofreLocal>()
    for (msg in ordenadas) {
        val txt = (msg.content as? TdApi.MessageText)?.text?.text ?: continue
        if (!txt.contains("[STATUS_COFRE]")) continue
        val partes = txt.split("|").map { it.trim() }
        val userPart = partes.firstOrNull { it.startsWith("USER:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: ""
        val statusPart = partes.firstOrNull { it.startsWith("STATUS:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.uppercase(Locale.ROOT) ?: ""
        val motivoPart = partes.firstOrNull { it.startsWith("MOTIVO:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: ""

        val chave = normalizarUsuario(userPart)
        if (chave.isNotBlank() && !mapaStatus.containsKey(chave)) {
            val estaBloqueado = statusPart == "BLOQUEADO"
            mapaStatus[chave] = StatusCofreLocal(
                bloqueado = estaBloqueado,
                motivo = if (estaBloqueado) motivoPart.ifBlank { "Acesso temporariamente suspenso." } else "",
                atualizadoEm = msg.date.toLong()
            )
        }
    }
    return mapaStatus
}

// Consulta remota de bloqueio/desbloqueio com fallback anti-offline
fun verificarStatusRemotoCliente(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    onResult: (Boolean, String) -> Unit
) {
    val chave = normalizarUsuario(usuarioAtivo)
    if (chave.isBlank()) {
        onResult(false, "")
        return
    }
    val localAtual = obterStatusCofreLocal(context, chave)

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, "STATUS_COFRE") { msgsBusca ->
                        buscarHistoricoPaginado(client, chatObj.id, 0L, 6, mutableListOf()) { msgsHist ->
                            NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                                val todas = (msgsBusca + msgsHist).distinctBy { it.id }
                                val mapa = extrairStatusCofreDasMensagens(todas)
                                val remoto = mapa[chave]
                                if (remoto != null) {
                                    salvarStatusCofreLocal(context, chave, remoto.bloqueado, remoto.motivo, remoto.atualizadoEm)
                                    withContext(Dispatchers.Main) {
                                        onResult(remoto.bloqueado, remoto.motivo)
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        onResult(localAtual.bloqueado, localAtual.motivo)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    onResult(localAtual.bloqueado, localAtual.motivo)
                }
            }
        } else {
            onResult(localAtual.bloqueado, localAtual.motivo)
        }
    }
}

// Carrega todos os clientes (MS e CP) + status atual para o Painel Admin SSD
fun carregarTodosPerfisAdmin(
    context: Context,
    client: Client,
    onComplete: (List<PerfilAdminInfo>, String?) -> Unit
) {
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, "REGISTO_COFRE") { msgsReg ->
                        buscarMensagensPorTermoNaNuvem(client, chatObj.id, "STATUS_COFRE") { msgsStatus ->
                            buscarHistoricoPaginado(client, chatObj.id, 0L, 25, mutableListOf()) { msgsHist ->
                                NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                                    try {
                                        val todas = (msgsReg + msgsStatus + msgsHist).distinctBy { it.id }
                                        val ordenadas = todas.sortedWith(
                                            compareByDescending<TdApi.Message> { it.date }.thenByDescending { it.id }
                                        )
                                        val mapaStatus = extrairStatusCofreDasMensagens(ordenadas)
                                        val mapaPerfis = mutableMapOf<String, PerfilAdminInfo>()

                                        for (msg in ordenadas) {
                                            val texto = when (val c = msg.content) {
                                                is TdApi.MessageText -> c.text?.text ?: ""
                                                is TdApi.MessageDocument -> c.caption?.text ?: ""
                                                is TdApi.MessageAudio -> c.caption?.text ?: ""
                                                else -> ""
                                            }
                                            if (texto.contains("[REGISTO_COFRE]")) {
                                                val partes = texto.split("|").map { it.trim() }
                                                val userPart = partes.firstOrNull { it.startsWith("USER:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                                val nomePart = partes.firstOrNull { it.startsWith("NOME:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                                val pinPart = partes.firstOrNull { it.startsWith("PIN:", ignoreCase = true) }?.substringAfter(":")?.trim() ?: ""
                                                val modePart = partes.firstOrNull { it.startsWith("MODE:", ignoreCase = true) }?.substringAfter(":")?.trim()?.uppercase(Locale.ROOT) ?: "MS"
                                                val chatPart = partes.firstOrNull { it.startsWith("CHAT:", ignoreCase = true) }?.substringAfter(":")?.trim()?.toLongOrNull() ?: 0L

                                                val chave = normalizarUsuario(userPart ?: nomePart ?: "")
                                                val exibicao = (nomePart ?: userPart ?: "").trim()
                                                if (chave.isNotBlank() && exibicao.isNotBlank() && !mapaPerfis.containsKey(chave)) {
                                                    val st = mapaStatus[chave]
                                                    if (st != null) {
                                                        salvarStatusCofreLocal(context, chave, st.bloqueado, st.motivo, st.atualizadoEm)
                                                    }
                                                    mapaPerfis[chave] = PerfilAdminInfo(
                                                        chaveUsuario = chave,
                                                        nomeExibicao = exibicao,
                                                        pinAtual = pinPart,
                                                        modo = if (modePart == "CP") "CP" else "MS",
                                                        chatIdCanal = chatPart,
                                                        bloqueado = st?.bloqueado ?: false,
                                                        motivoBloqueio = st?.motivo ?: "",
                                                        ultimaAtualizacao = st?.atualizadoEm ?: msg.date.toLong()
                                                    )
                                                }
                                            }
                                        }

                                        val listaFinal = mapaPerfis.values.sortedBy { it.nomeExibicao.lowercase(Locale.ROOT) }
                                        withContext(Dispatchers.Main) {
                                            onComplete(listaFinal, null)
                                        }
                                    } catch (e: Throwable) {
                                        withContext(Dispatchers.Main) {
                                            onComplete(emptyList(), "Erro ao carregar perfis: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    onComplete(emptyList(), "Não foi possível abrir a conta central SSD.")
                }
            }
        } else {
            onComplete(emptyList(), "Sessão SSD indisponível.")
        }
    }
}

// Envia comando remoto de BLOQUEIO ou DESBLOQUEIO via [STATUS_COFRE]
fun enviarComandoStatusAdmin(
    context: Context,
    client: Client,
    chaveUsuario: String,
    bloquear: Boolean,
    motivo: String,
    onComplete: (Boolean, String) -> Unit
) {
    val chave = normalizarUsuario(chaveUsuario)
    if (chave.isBlank()) {
        onComplete(false, "Utilizador inválido.")
        return
    }
    val motivoLimpo = motivo.trim().replace("|", "-").replace("\n", " ")
        .ifBlank { if (bloquear) "Mensalidade pendente" else "" }

    val textoTag = if (bloquear) {
        "🚫 [STATUS_COFRE] | USER:$chave | STATUS:BLOQUEADO | MOTIVO:$motivoLimpo"
    } else {
        "✅ [STATUS_COFRE] | USER:$chave | STATUS:ATIVO | MOTIVO:"
    }

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    val req = TdApi.SendMessage().apply {
                        this.chatId = chatObj.id
                        this.inputMessageContent = TdApi.InputMessageText().apply {
                            this.text = TdApi.FormattedText(textoTag, emptyArray())
                        }
                    }
                    client.send(req) { resp ->
                        if (resp is TdApi.Error) {
                            onComplete(false, "Erro SSD (${resp.code}): ${resp.message}")
                        } else {
                            salvarStatusCofreLocal(context, chave, bloquear, if (bloquear) motivoLimpo else "")
                            val msgOk = if (bloquear) "Cofre '$chave' bloqueado com sucesso!" else "Cofre '$chave' desbloqueado!"
                            onComplete(true, msgOk)
                        }
                    }
                } else {
                    onComplete(false, "Falha ao conectar às Mensagens Salvas.")
                }
            }
        } else {
            onComplete(false, "Sessão SSD indisponível.")
        }
    }
}

// Altera o PIN do cliente remotamente a partir do Painel Admin
fun alterarPinClienteAdmin(
    context: Context,
    client: Client,
    perfil: PerfilAdminInfo,
    novoPin: String,
    onComplete: (Boolean, String) -> Unit
) {
    val pinLimpo = novoPin.trim()
    if (pinLimpo.length < 4 || pinLimpo.length > 6) {
        onComplete(false, "O PIN deve ter entre 4 e 6 dígitos.")
        return
    }
    val regTexto = "🔒 [REGISTO_COFRE] | USER:${perfil.chaveUsuario} | NOME:${perfil.nomeExibicao.trim()} | PIN:$pinLimpo | MODE:${perfil.modo} | CHAT:${perfil.chatIdCanal}"

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    val req = TdApi.SendMessage().apply {
                        this.chatId = chatObj.id
                        this.inputMessageContent = TdApi.InputMessageText().apply {
                            this.text = TdApi.FormattedText(regTexto, emptyArray())
                        }
                    }
                    client.send(req) { resp ->
                        if (resp is TdApi.Error) {
                            onComplete(false, "Erro SSD (${resp.code}): ${resp.message}")
                        } else {
                            if (perfil.modo == "CP" && perfil.chatIdCanal != 0L) {
                                val reqCanal = TdApi.SendMessage().apply {
                                    this.chatId = perfil.chatIdCanal
                                    this.inputMessageContent = TdApi.InputMessageText().apply {
                                        this.text = TdApi.FormattedText(regTexto, emptyArray())
                                    }
                                }
                                client.send(reqCanal) {}
                            }
                            salvarCredenciaisCofreLocal(
                                context = context,
                                nomeOriginal = perfil.nomeExibicao,
                                pin = pinLimpo,
                                definirComoAtivo = false,
                                modoArmazenamento = perfil.modo,
                                canalChatId = perfil.chatIdCanal
                            )
                            onComplete(true, "PIN de '${perfil.nomeExibicao}' atualizado para $pinLimpo!")
                        }
                    }
                } else {
                    onComplete(false, "Falha ao conectar à nuvem SSD.")
                }
            }
        } else {
            onComplete(false, "Sessão SSD indisponível.")
        }
    }
}

// === GESTÃO DE COFRES HÍBRIDOS (MS = MENSAGENS SALVAS | CP = CANAL PRIVADO) ===
fun normalizarUsuario(nome: String): String = nome.trim().lowercase(Locale.ROOT)

fun obterUltimoUsuarioCofre(context: Context): String {
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getString("last_active_user", "") ?: ""
}

fun salvarCredenciaisCofreLocal(
    context: Context,
    nomeOriginal: String,
    pin: String,
    definirComoAtivo: Boolean = true,
    modoArmazenamento: String? = null,
    canalChatId: Long? = null
) {
    val chave = normalizarUsuario(nomeOriginal)
    if (chave.isBlank()) return
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()
        .putString("pin_$chave", pin.trim())
        .putString("display_$chave", nomeOriginal.trim())
    if (definirComoAtivo) {
        editor.putString("last_active_user", nomeOriginal.trim())
    }
    if (!modoArmazenamento.isNullOrBlank()) {
        editor.putString("mode_$chave", modoArmazenamento.uppercase(Locale.ROOT).trim())
    }
    if (canalChatId != null && canalChatId != 0L) {
        editor.putLong("chatid_$chave", canalChatId)
    }
    editor.apply()
}

fun obterPinCofreLocal(context: Context, nome: String): String? {
    val chave = normalizarUsuario(nome)
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getString("pin_$chave", null)
}

fun obterModoCofreLocal(context: Context, nome: String): String {
    val chave = normalizarUsuario(nome)
    if (chave.isBlank()) return "MS"
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getString("mode_$chave", "MS") ?: "MS"
}

fun obterChatIdCofreLocal(context: Context, nome: String): Long {
    val chave = normalizarUsuario(nome)
    if (chave.isBlank()) return 0L
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getLong("chatid_$chave", 0L)
}

// Cria um Canal Privado dedicado na TDLib para o modo CP
fun criarCanalPrivadoTDLib(
    client: Client,
    nomePerfil: String,
    onResult: (Long, String?) -> Unit
) {
    val req = TdApi.CreateNewSupergroupChat()
    try {
        for (f in TdApi.CreateNewSupergroupChat::class.java.declaredFields) {
            if (JavaModifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            when (f.name) {
                "title" -> f.set(req, "Nexus Cloud • ${nomePerfil.trim()}")
                "isChannel" -> f.setBoolean(req, true)
                "isForum" -> f.setBoolean(req, false)
                "description" -> f.set(req, "Cofre Privado SSD Soluções • Perfil: ${nomePerfil.trim()}")
                "messageAutoDeleteTime" -> f.setInt(req, 0)
                "forImport" -> f.setBoolean(req, false)
            }
        }
    } catch (_: Throwable) {}

    client.send(req) { obj ->
        when (obj) {
            is TdApi.Chat -> onResult(obj.id, null)
            is TdApi.Error -> onResult(0L, "Erro SSD ao criar Canal Privado (${obj.code}): ${obj.message}")
            else -> onResult(0L, "Não foi possível criar o Canal Privado agora.")
        }
    }
}

// Resolve automaticamente o Chat do cofre do utilizador (Canal Privado para CP ou Mensagens Salvas para MS)
fun obterChatCofreUsuario(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    onChatReady: (TdApi.Chat?, String, String?) -> Unit
) {
    val modo = obterModoCofreLocal(context, usuarioAtivo)
    val chatIdSalvo = obterChatIdCofreLocal(context, usuarioAtivo)
    val tituloEsperadoCanal = "Nexus Cloud • ${usuarioAtivo.trim()}"

    if (modo == "CP") {
        fun buscarCanalPorTitulo() {
            client.send(TdApi.GetChats(null, 200)) { chatsObj ->
                val ids = (chatsObj as? TdApi.Chats)?.chatIds ?: LongArray(0)
                if (ids.isEmpty()) {
                    onChatReady(null, "CP", "Nenhum Canal Privado localizado.")
                    return@send
                }
                var verificados = 0
                var concluido = false
                for (cid in ids) {
                    client.send(TdApi.GetChat(cid)) { cObj ->
                        synchronized(ids) {
                            verificados++
                            if (!concluido && cObj is TdApi.Chat && cObj.title.equals(tituloEsperadoCanal, ignoreCase = true)) {
                                concluido = true
                                val pinAtual = obterPinCofreLocal(context, usuarioAtivo) ?: ""
                                salvarCredenciaisCofreLocal(context, usuarioAtivo, pinAtual, false, "CP", cObj.id)
                                onChatReady(cObj, "CP", null)
                            } else if (!concluido && verificados == ids.size) {
                                concluido = true
                                onChatReady(null, "CP", "Canal Privado de '$usuarioAtivo' não encontrado.")
                            }
                        }
                    }
                }
            }
        }

        fun abrirCanalPorIdOuTitulo(idAlvo: Long, jaCarregouLista: Boolean) {
            if (idAlvo != 0L) {
                client.send(TdApi.GetChat(idAlvo)) { chatObj ->
                    if (chatObj is TdApi.Chat) {
                        onChatReady(chatObj, "CP", null)
                    } else if (!jaCarregouLista) {
                        client.send(TdApi.GetChats(null, 200)) {
                            abrirCanalPorIdOuTitulo(idAlvo, true)
                        }
                    } else {
                        buscarCanalPorTitulo()
                    }
                }
            } else {
                buscarCanalPorTitulo()
            }
        }

        abrirCanalPorIdOuTitulo(chatIdSalvo, false)
        return
    }

    // Modo MS (Mensagens Salvas)
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    onChatReady(chatObj, "MS", null)
                } else if (chatObj is TdApi.Error) {
                    onChatReady(null, "MS", "Erro cofre SSD: ${chatObj.message}")
                } else {
                    onChatReady(null, "MS", "Erro ao abrir Mensagens Salvas.")
                }
            }
        } else if (meObj is TdApi.Error) {
            onChatReady(null, "MS", "Erro sessão SSD: ${meObj.message}")
        } else {
            onChatReady(null, "MS", "Sessão SSD indisponível.")
        }
    }
}

// === MOTOR TURBO MODO UNLIM (MINITHUMB EM DISCO + CANCELAMENTO EM LOTE + FILA SILENCIOSA) ===
object NexusPreviewTurbo {
    val semaforoCapasLeves = kotlinx.coroutines.sync.Semaphore(8)
    val semaforoFallbackFotos = kotlinx.coroutines.sync.Semaphore(5)
    val semaforoFallbackVideos = kotlinx.coroutines.sync.Semaphore(2)

    @Volatile
    var usuarioRolando: Boolean = false

    val caminhosConfirmados: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    val miniThumbBytes = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()
    val jaTentouRecuperarId: MutableSet<Long> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())
    val downloadsAtivosPorItem = java.util.concurrent.ConcurrentHashMap<Long, Pair<Int, Boolean>>()

    fun registrarDownloadAtivo(dataUpload: Long, fileId: Int, ehCapaLeve: Boolean) {
        if (fileId != 0) downloadsAtivosPorItem[dataUpload] = Pair(fileId, ehCapaLeve)
    }

    fun limparDownloadAtivo(dataUpload: Long) {
        downloadsAtivosPorItem.remove(dataUpload)
    }

    fun cancelarDownloadItem(client: Client, dataUpload: Long) {
        val ativo = downloadsAtivosPorItem.remove(dataUpload) ?: return
        val (fileId, ehCapaLeve) = ativo
        if (fileId == 0) return
        val downloadManualAtivo = NexusTransfer.progress.value != null && !NexusTransfer.ehUpload.value
        if (ehCapaLeve || !downloadManualAtivo) {
            try {
                client.send(TdApi.CancelDownloadFile(fileId, false)) {}
            } catch (_: Throwable) {}
        }
    }

fun cancelarDownloadsPreviewAtivos(client: Client) {
        try {
            val it = downloadsAtivosPorItem.keys.iterator()
            while (it.hasNext()) {
                val ts = it.next()
                cancelarDownloadItem(client, ts)
            }
        } catch (_: Throwable) {}
    }

    fun cancelarForaDosVisiveis(client: Client, idsVisiveis: Set<Long>) {
        try {
            val it = downloadsAtivosPorItem.keys.iterator()
            while (it.hasNext()) {
                val ts = it.next()
                if (!idsVisiveis.contains(ts)) {
                    cancelarDownloadItem(client, ts)
                }
            }
        } catch (_: Throwable) {}
    }

    fun registrarCaminhoValido(caminho: String?) {
        val resolvido = resolverCaminhoCapaJpg(caminho)
        if (resolvido.isNotBlank()) caminhosConfirmados.add(resolvido)
    }

    fun invalidarCaminho(caminho: String?) {
        val resolvido = resolverCaminhoCapaJpg(caminho)
        if (resolvido.isNotBlank()) caminhosConfirmados.remove(resolvido)
        if (!caminho.isNullOrBlank()) caminhosConfirmados.remove(caminho)
    }

    fun salvarMiniThumbMemoria(dataUpload: Long, bytes: ByteArray?) {
        if (bytes == null || bytes.size < 64 || bytes.size > 16384) return
        if (miniThumbBytes.size >= 450) {
            miniThumbBytes.keys.firstOrNull()?.let { miniThumbBytes.remove(it) }
        }
        miniThumbBytes[dataUpload] = bytes
    }

    fun salvarMiniThumbDisco(context: Context, dataUpload: Long, bytes: ByteArray?) {
        if (bytes == null || bytes.size < 64 || bytes.size > 16384) return
        salvarMiniThumbMemoria(dataUpload, bytes)
        try {
            val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
            val fMini = File(dir, "mini_$dataUpload.jpg")
            if (!fMini.exists() || fMini.length() < 64L) {
                FileOutputStream(fMini).use { it.write(bytes) }
            }
        } catch (_: Throwable) {}
    }

    fun obterModeloMiniThumb(context: Context, dataUpload: Long): Any? {
        miniThumbBytes[dataUpload]?.let { return it }
        val fMini = File(File(context.filesDir, "nexus_thumbs"), "mini_$dataUpload.jpg")
        return if (fMini.exists() && fMini.length() >= 64L) fMini else null
    }
}

// Pré-aquece silenciosamente as capas leves (~15 KB) em segundo plano; pausa na hora se você rolar
suspend fun baixarCapasLevesEmSegundoPlano(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    listaArquivos: List<ArquivoNuvem>,
    vm: UIViewModel
) = withContext(Dispatchers.IO) {
    val pendentes = listaArquivos
        .sortedByDescending { it.dataUpload }
        .filter { arq ->
            (NexusThumbCache.mapaThumbIdsNuvem[arq.fileId] ?: 0) != 0 &&
                !miniaturaExisteLocalmente(NexusThumbCache.obterMiniatura(arq), arq.nomeArquivo)
        }
        .take(200)

    if (pendentes.isEmpty()) return@withContext

    kotlinx.coroutines.coroutineScope {
        val filaSilenciosa = kotlinx.coroutines.sync.Semaphore(4)
        for (arq in pendentes) {
            while (NexusPreviewTurbo.usuarioRolando || NexusSyncState.emAndamento.value || NexusTransfer.progress.value != null) {
                delay(350)
            }
            if (miniaturaExisteLocalmente(NexusThumbCache.obterMiniatura(arq), arq.nomeArquivo)) continue
            launch(Dispatchers.IO) {
                filaSilenciosa.withPermit {
                    if (!NexusPreviewTurbo.usuarioRolando && NexusThumbCache.emProgresso.add(arq.dataUpload)) {
                        try {
                            carregarMiniaturaIndividualDaNuvem(
                                context = context,
                                client = client,
                                usuarioAtivo = usuarioAtivo,
                                arq = arq,
                                vm = vm,
                                atualizarSubBarra = false,
                                prioridadeRede = 14,
                                apenasCapaLeve = true
                            )
                        } finally {
                            NexusPreviewTurbo.limparDownloadAtivo(arq.dataUpload)
                            NexusThumbCache.emProgresso.remove(arq.dataUpload)
                        }
                    }
                }
            }
        }
    }
}
fun extrairMiniThumbnailBytes(mediaObj: Any?): ByteArray? {
    if (mediaObj == null) return null
    return try {
        val fMini = mediaObj.javaClass.getField("minithumbnail")
        val miniObj = fMini.get(mediaObj) ?: return null
        val fData = miniObj.javaClass.getField("data")
        fData.get(miniObj) as? ByteArray
    } catch (_: Throwable) {
        null
    }
}

fun escolherFotoMiniaturaLeve(photo: TdApi.Photo?): TdApi.PhotoSize? {
    val validos = photo?.sizes?.filterNotNull()?.filter { (it.photo?.id ?: 0) != 0 } ?: return null
    if (validos.size <= 1) return null
    val ordenados = validos.sortedBy { maxOf(it.width, it.height) }
    return ordenados.firstOrNull { maxOf(it.width, it.height) >= 240 }
        ?: ordenados.firstOrNull()
}

suspend fun recuperarFileIdNaNuvemPorNome(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    nomeAlvo: String,
    tamanhoAlvo: Long
): Pair<Int, Int> {
    val def = CompletableDeferred<Pair<Int, Int>>()
    val chaveAtiva = normalizarUsuario(usuarioAtivo)
    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"
    val nomeAlvoLimpo = limparPrefixoTimestamp(nomeAlvo).lowercase(Locale.ROOT)

    obterChatCofreUsuario(context, client, usuarioAtivo) { chatObj, modoCofre, _ ->
        if (chatObj != null) {
            buscarMensagensPorTermoNaNuvem(client, chatObj.id, nomeAlvoLimpo.take(25)) { msgsBusca ->
                buscarHistoricoPaginado(client, chatObj.id, 0L, 10, mutableListOf()) { msgsHist ->
                    NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                        val todasMensagens = (msgsBusca + msgsHist).distinctBy { it.id }
                        var achouFileId = 0
                        var achouThumbId = 0
                        for (msg in todasMensagens) {
                            when (val c = msg.content) {
                                is TdApi.MessageDocument -> {
                                    val legenda = c.caption?.text ?: ""
                                    if (modoCofre == "CP" ||
                                        legenda.contains(tagPerfil, ignoreCase = true) ||
                                        legenda.contains(tagPerfilExibicao, ignoreCase = true) ||
                                        legenda.contains("☁️ Nexus Cloud")
                                    ) {
                                        val nomeBruto = c.document?.fileName ?: ""
                                        val nomeMsg = extrairNomeLimpoDaMensagem(nomeBruto, legenda).lowercase(Locale.ROOT)
                                        val tamMsg = c.document?.document?.size ?: 0L
                                        if (nomeMsg == nomeAlvoLimpo || (tamanhoAlvo > 0L && tamMsg == tamanhoAlvo && nomeMsg.contains(nomeAlvoLimpo.take(8)))) {
                                            achouFileId = c.document?.document?.id ?: 0
                                            achouThumbId = c.document?.thumbnail?.file?.id ?: 0
                                            if (achouFileId != 0) break
                                        }
                                    }
                                }
                                is TdApi.MessageAudio -> {
                                    val legenda = c.caption?.text ?: ""
                                    if (modoCofre == "CP" ||
                                        legenda.contains(tagPerfil, ignoreCase = true) ||
                                        legenda.contains(tagPerfilExibicao, ignoreCase = true) ||
                                        legenda.contains("☁️ Nexus Cloud")
                                    ) {
                                        val nomeBruto = c.audio?.fileName?.ifBlank { c.audio?.title ?: "" } ?: ""
                                        val nomeMsg = extrairNomeLimpoDaMensagem(nomeBruto, legenda).lowercase(Locale.ROOT)
                                        if (nomeMsg == nomeAlvoLimpo) {
                                            achouFileId = c.audio?.audio?.id ?: 0
                                            achouThumbId = c.audio?.albumCoverThumbnail?.file?.id ?: 0
                                            if (achouFileId != 0) break
                                        }
                                    }
                                }
                                is TdApi.MessagePhoto -> {
                                    val legenda = c.caption?.text ?: ""
                                    if (modoCofre == "CP" || legenda.contains(tagPerfil, ignoreCase = true) || legenda.contains(tagPerfilExibicao, ignoreCase = true)) {
                                        val nomeMsg = extrairNomeLimpoDaMensagem("", legenda).lowercase(Locale.ROOT)
                                        if (nomeMsg == nomeAlvoLimpo) {
                                            val maior = c.photo?.sizes?.maxByOrNull { it.photo?.size ?: 0 }
                                            achouFileId = maior?.photo?.id ?: 0
                                            val thumbPhoto = escolherFotoMiniaturaLeve(c.photo)
                                            achouThumbId = thumbPhoto?.photo?.id?.takeIf { it != 0 && it != achouFileId } ?: 0
                                            if (achouFileId != 0) break
                                        }
                                    }
                                }
                                is TdApi.MessageVideo -> {
                                    val legenda = c.caption?.text ?: ""
                                    if (modoCofre == "CP" || legenda.contains(tagPerfil, ignoreCase = true) || legenda.contains(tagPerfilExibicao, ignoreCase = true)) {
                                        val nomeMsg = extrairNomeLimpoDaMensagem(c.video?.fileName ?: "", legenda).lowercase(Locale.ROOT)
                                        if (nomeMsg == nomeAlvoLimpo) {
                                            achouFileId = c.video?.video?.id ?: 0
                                            achouThumbId = c.video?.thumbnail?.file?.id ?: 0
                                            if (achouFileId != 0) break
                                        }
                                    }
                                }
                            }
                        }
                        def.complete(Pair(achouFileId, achouThumbId))
                    }
                }
            }
        } else {
            def.complete(Pair(0, 0))
        }
    }
    return def.await()
}

fun listarPerfisSalvosLocalmente(context: Context): List<String> {
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    val nomes = mutableListOf<String>()
    for ((k, v) in prefs.all) {
        if (k.startsWith("display_") && v is String && v.isNotBlank()) {
            nomes.add(v.trim())
        }
    }
    return nomes.distinctBy { normalizarUsuario(it) }.sortedBy { normalizarUsuario(it) }
}

fun carregarDonosFicheiros(context: Context): Map<Long, String> {
    val prefs = context.getSharedPreferences("nexus_file_owners", Context.MODE_PRIVATE)
    val mapa = mutableMapOf<Long, String>()
    for ((k, v) in prefs.all) {
        val ts = k.toLongOrNull()
        if (ts != null && v is String && v.isNotBlank()) mapa[ts] = v
    }
    return mapa
}

fun salvarDonoFicheiro(context: Context, dataUpload: Long, usuario: String) {
    val prefs = context.getSharedPreferences("nexus_file_owners", Context.MODE_PRIVATE)
    val chave = dataUpload.toString()
    if (usuario.isBlank()) {
        if (!prefs.contains(chave)) return
        prefs.edit().remove(chave).apply()
    } else {
        val norm = normalizarUsuario(usuario)
        if (prefs.getString(chave, null) == norm) return
        prefs.edit().putString(chave, norm).apply()
    }
}

fun carregarPastasDoUsuario(context: Context, usuario: String): Set<String> {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_folder_owners", Context.MODE_PRIVATE)
    return prefs.getStringSet("folders_$chave", emptySet()) ?: emptySet()
}

fun associarPastaAoUsuario(context: Context, usuario: String, nomePasta: String) {
    val limpo = nomePasta.trim()
    if (limpo.isBlank()) return
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_folder_owners", Context.MODE_PRIVATE)
    val atuais = (prefs.getStringSet("folders_$chave", emptySet()) ?: emptySet()).toMutableSet()
    val jaTem = atuais.any { it.equals(limpo, ignoreCase = true) }
    if (!jaTem) {
        atuais.add(limpo)
        prefs.edit().putStringSet("folders_$chave", atuais).apply()
    }
    desmarcarPastaEliminada(context, usuario, limpo)
}

fun desassociarPastaDoUsuario(context: Context, usuario: String, nomePasta: String) {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_folder_owners", Context.MODE_PRIVATE)
    val antigos = prefs.getStringSet("folders_$chave", emptySet()) ?: emptySet()
    val atuais = antigos.filterNot { it.equals(nomePasta.trim(), ignoreCase = true) }.toSet()
    if (atuais.size == antigos.size) return
    prefs.edit().putStringSet("folders_$chave", atuais).apply()
}

fun marcarPastaComoEliminada(context: Context, usuario: String, nomePasta: String) {
    val chave = normalizarUsuario(usuario)
    val k = nomePasta.trim().lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_folders_$chave", Context.MODE_PRIVATE)
    if (prefs.getBoolean(k, false)) return
    prefs.edit().putBoolean(k, true).apply()
}

fun desmarcarPastaEliminada(context: Context, usuario: String, nomePasta: String) {
    val chave = normalizarUsuario(usuario)
    val chavePasta = nomePasta.trim().lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_folders_$chave", Context.MODE_PRIVATE)
    if (!prefs.contains(chavePasta)) return
    prefs.edit().remove(chavePasta).apply()
}

fun pastaFoiEliminadaPeloUsuario(context: Context, usuario: String, nomePasta: String): Boolean {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_deleted_folders_$chave", Context.MODE_PRIVATE)
    return prefs.getBoolean(nomePasta.trim().lowercase(Locale.ROOT), false)
}

fun guardarRenomeacaoPasta(context: Context, usuario: String, nomeAntigo: String, nomeNovo: String) {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_folder_renames_$chave", Context.MODE_PRIVATE)
    val antigoLower = nomeAntigo.trim().lowercase(Locale.ROOT)
    val novoLimpo = nomeNovo.trim()
    val editor = prefs.edit()
    for ((k, v) in prefs.all) {
        if (v is String && v.equals(nomeAntigo.trim(), ignoreCase = true)) {
            editor.putString(k, novoLimpo)
        }
    }
    editor.putString(antigoLower, novoLimpo)
    editor.apply()
}

fun resolverNomePastaAtualizado(context: Context, usuario: String, nomeOriginal: String): String {
    val limpo = nomeOriginal.trim()
    if (limpo.isBlank()) return ""
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_folder_renames_$chave", Context.MODE_PRIVATE)
    return prefs.getString(limpo.lowercase(Locale.ROOT), limpo) ?: limpo
}

fun renomearPastaCompleta(
    context: Context,
    usuario: String,
    nomeAntigo: String,
    nomeNovo: String,
    vm: UIViewModel
) {
    val antigoLimpo = nomeAntigo.trim()
    val novoLimpo = nomeNovo.trim()
    if (antigoLimpo.isBlank() || novoLimpo.isBlank() || antigoLimpo.equals(novoLimpo, ignoreCase = true)) return

    desassociarPastaDoUsuario(context, usuario, antigoLimpo)
    marcarPastaComoEliminada(context, usuario, antigoLimpo)

    vm.criarPasta(novoLimpo)
    associarPastaAoUsuario(context, usuario, novoLimpo)
    guardarRenomeacaoPasta(context, usuario, antigoLimpo, novoLimpo)

    val prefsLinks = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    val editor = prefsLinks.edit()
    for ((k, v) in prefsLinks.all) {
        if (v is String && v.equals(antigoLimpo, ignoreCase = true)) {
            editor.putString(k, novoLimpo)
        }
    }
    editor.apply()
}

fun eliminarPastaCompleta(
    context: Context,
    client: Client,
    usuario: String,
    nomePasta: String,
    apagarFicheirosDaPasta: Boolean,
    arquivosDaPasta: List<ArquivoNuvem>,
    vm: UIViewModel,
    onComplete: (String) -> Unit
) {
    val limpo = nomePasta.trim()
    desassociarPastaDoUsuario(context, usuario, limpo)
    marcarPastaComoEliminada(context, usuario, limpo)

    if (!apagarFicheirosDaPasta || arquivosDaPasta.isEmpty()) {
        val prefsLinks = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
        val editor = prefsLinks.edit()
        for ((k, v) in prefsLinks.all) {
            if (v is String && v.equals(limpo, ignoreCase = true)) {
                editor.remove(k)
            }
        }
        editor.apply()
        onComplete("Pasta '$limpo' apagada! Ficheiros movidos para a Galeria Geral.")
    } else {
        eliminarMultiplosFicheiros(context, client, usuario, arquivosDaPasta, true, vm) {
            onComplete("Pasta '$limpo' e ${arquivosDaPasta.size} ficheiro(s) eliminados!")
        }
    }
}

// === SINCRONIZAÇÃO DE PASTAS E MOVIMENTAÇÕES NA NUVEM SSD (MS & CP) ===
fun registrarAcaoPastaNaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    acao: String,
    pastaPrincipal: String,
    pastaNova: String = ""
) {
    val chave = normalizarUsuario(usuarioAtivo)
    val p1 = pastaPrincipal.trim().replace("|", "-")
    val p2 = pastaNova.trim().replace("|", "-")
    if (chave.isBlank() || p1.isBlank()) return

    val texto = when (acao.uppercase(Locale.ROOT)) {
        "CRIAR" -> "📂 [SYNC_PASTA] | USER:$chave | ACAO:CRIAR | PASTA:$p1"
        "RENOMEAR" -> "📂 [SYNC_PASTA] | USER:$chave | ACAO:RENOMEAR | DE:$p1 | PARA:$p2"
        "APAGAR" -> "📂 [SYNC_PASTA] | USER:$chave | ACAO:APAGAR | PASTA:$p1"
        else -> return
    }

    obterChatCofreUsuario(context, client, usuarioAtivo) { chatObj, _, _ ->
        if (chatObj != null) {
            val req = TdApi.SendMessage().apply {
                this.chatId = chatObj.id
                this.inputMessageContent = TdApi.InputMessageText().apply {
                    this.text = TdApi.FormattedText(texto, emptyArray())
                }
            }
            client.send(req) {}
        }
    }
}

fun registrarMovimentacaoNaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    pastaDestino: String,
    listaArquivos: List<ArquivoNuvem>
) {
    val chave = normalizarUsuario(usuarioAtivo)
    if (chave.isBlank() || listaArquivos.isEmpty()) return
    val destinoLimpo = pastaDestino.trim().replace("|", "-")

    val chavesItens = listaArquivos.map { arq ->
        val nomeLower = limparPrefixoTimestamp(arq.nomeArquivo)
            .lowercase(Locale.ROOT)
            .replace(";;", "_")
            .replace("|", "_")
        "$nomeLower#${arq.tamanhoBytes}"
    }.distinct()

    obterChatCofreUsuario(context, client, usuarioAtivo) { chatObj, _, _ ->
        if (chatObj != null) {
            chavesItens.chunked(18).forEach { bloco ->
                val texto = "📂 [SYNC_MOVER] | USER:$chave | PASTA:$destinoLimpo | ITENS:${bloco.joinToString(";;")}"
                val req = TdApi.SendMessage().apply {
                    this.chatId = chatObj.id
                    this.inputMessageContent = TdApi.InputMessageText().apply {
                        this.text = TdApi.FormattedText(texto, emptyArray())
                    }
                }
                client.send(req) {}
            }
        }
    }
}

fun backupCompletoPastasNaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    pastas: List<PastaNuvem>,
    arquivos: List<ArquivoNuvem>,
    mapaPastas: Map<Long, String>,
    onComplete: (String) -> Unit
) {
    val chave = normalizarUsuario(usuarioAtivo)
    if (chave.isBlank()) {
        onComplete("Nenhum perfil ativo.")
        return
    }
    obterChatCofreUsuario(context, client, usuarioAtivo) { chatObj, modoCofre, erro ->
        if (chatObj == null) {
            onComplete(erro ?: "Não foi possível conectar ao cofre SSD.")
            return@obterChatCofreUsuario
        }
        val mensagensTexto = mutableListOf<String>()

        val nomesPastas = pastas.map { it.nomePasta.trim().replace("|", "-") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        nomesPastas.forEach { nomeP ->
            mensagensTexto.add("📂 [SYNC_PASTA] | USER:$chave | ACAO:CRIAR | PASTA:$nomeP")
        }

        val porPasta = arquivos.groupBy { arq ->
            (mapaPastas[arq.dataUpload] ?: "").trim().replace("|", "-")
        }
        porPasta.forEach { (pastaDest, lista) ->
            if (pastaDest.isNotBlank() && lista.isNotEmpty()) {
                val chavesItens = lista.map { arq ->
                    val nomeLower = limparPrefixoTimestamp(arq.nomeArquivo)
                        .lowercase(Locale.ROOT)
                        .replace(";;", "_")
                        .replace("|", "_")
                    "$nomeLower#${arq.tamanhoBytes}"
                }.distinct()
                chavesItens.chunked(18).forEach { bloco ->
                    mensagensTexto.add("📂 [SYNC_MOVER] | USER:$chave | PASTA:$pastaDest | ITENS:${bloco.joinToString(";;")}")
                }
            }
        }

        if (mensagensTexto.isEmpty()) {
            onComplete("Nenhuma pasta ou vínculo para salvar na nuvem.")
            return@obterChatCofreUsuario
        }

        NexusBackgroundScope.scope.launch(Dispatchers.IO) {
            mensagensTexto.forEach { txt ->
                val def = CompletableDeferred<Unit>()
                val req = TdApi.SendMessage().apply {
                    this.chatId = chatObj.id
                    this.inputMessageContent = TdApi.InputMessageText().apply {
                        this.text = TdApi.FormattedText(txt, emptyArray())
                    }
                }
                client.send(req) { def.complete(Unit) }
                def.await()
                delay(180)
            }
            withContext(Dispatchers.Main) {
                onComplete("${nomesPastas.size} pasta(s) e vínculos salvos na nuvem SSD ($modoCofre)!")
            }
        }
    }
}

fun registarMensagemSincronizada(context: Context, usuario: String, msgId: Long) {
    if (msgId == 0L) return
    val chave = normalizarUsuario(usuario)
    val k = msgId.toString()
    val prefs = context.getSharedPreferences("nexus_synced_msgs_$chave", Context.MODE_PRIVATE)
    if (prefs.getBoolean(k, false)) return
    prefs.edit().putBoolean(k, true).apply()
}

fun mensagemJaSincronizada(context: Context, usuario: String, msgId: Long): Boolean {
    if (msgId == 0L) return false
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_synced_msgs_$chave", Context.MODE_PRIVATE)
    return prefs.getBoolean(msgId.toString(), false)
}

fun marcarFicheiroComoSincronizado(context: Context, usuario: String, nomeArquivo: String, tamanho: Long, uriStr: String = "") {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_auto_synced_files_$chave", Context.MODE_PRIVATE)
    val kName = "name_$nomeLimpo"
    val kKey = "key_${nomeLimpo}|$tamanho"
    val kUri = if (uriStr.isNotBlank()) "uri_$uriStr" else ""

    val jaMarcado = prefs.getBoolean(kName, false) &&
        prefs.getBoolean(kKey, false) &&
        (kUri.isBlank() || prefs.getBoolean(kUri, false))
    if (jaMarcado) return

    val editor = prefs.edit()
    editor.putBoolean(kName, true)
    editor.putBoolean(kKey, true)
    if (kUri.isNotBlank()) {
        editor.putBoolean(kUri, true)
    }
    editor.apply()
}

// Grava centenas de arquivos em uma única transação de memória (elimina OutOfMemoryError e travamentos)
fun marcarLoteComoSincronizado(context: Context, usuario: String, lista: List<ArquivoNuvem>) {
    if (usuario.isBlank() || lista.isEmpty()) return
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_auto_synced_files_$chave", Context.MODE_PRIVATE)
    var editor: android.content.SharedPreferences.Editor? = null

    for (arq in lista) {
        val nomeLimpo = limparPrefixoTimestamp(arq.nomeArquivo).lowercase(Locale.ROOT)
        val kName = "name_$nomeLimpo"
        val kKey = "key_${nomeLimpo}|${arq.tamanhoBytes}"
        if (!prefs.getBoolean(kName, false) || !prefs.getBoolean(kKey, false)) {
            if (editor == null) editor = prefs.edit()
            editor?.putBoolean(kName, true)
            editor?.putBoolean(kKey, true)
        }
    }
    editor?.apply()
}

fun ficheiroJaSincronizadoAntes(context: Context, usuario: String, nomeArquivo: String, tamanho: Long, uriStr: String = ""): Boolean {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_auto_synced_files_$chave", Context.MODE_PRIVATE)
    if (uriStr.isNotBlank() && prefs.getBoolean("uri_$uriStr", false)) return true
    if (prefs.getBoolean("key_${nomeLimpo}|$tamanho", false)) return true
    if (prefs.getBoolean("name_$nomeLimpo", false)) return true
    return false
}

fun marcarFicheiroComoDeletado(context: Context, usuario: String, nomeArquivo: String, tamanho: Long) {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_files_$chave", Context.MODE_PRIVATE)
    val kName = "del_name_$nomeLimpo"
    val kKey = "del_key_${nomeLimpo}|$tamanho"
    if (prefs.getBoolean(kName, false) && prefs.getBoolean(kKey, false)) return
    prefs.edit()
        .putBoolean(kName, true)
        .putBoolean(kKey, true)
        .apply()
}

fun desmarcarFicheiroDeletado(context: Context, usuario: String, nomeArquivo: String, tamanho: Long) {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_files_$chave", Context.MODE_PRIVATE)
    val kName = "del_name_$nomeLimpo"
    val kKey = "del_key_${nomeLimpo}|$tamanho"
    if (!prefs.contains(kName) && !prefs.contains(kKey)) return
    prefs.edit()
        .remove(kName)
        .remove(kKey)
        .apply()
}

fun ficheiroFoiDeletadoPeloUsuario(context: Context, usuario: String, nomeArquivo: String, tamanho: Long): Boolean {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_files_$chave", Context.MODE_PRIVATE)
    return prefs.getBoolean("del_key_${nomeLimpo}|$tamanho", false) ||
        prefs.getBoolean("del_name_$nomeLimpo", false)
}

fun eliminarMultiplosFicheiros(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    listaArquivos: List<ArquivoNuvem>,
    apagarNaNuvemSSD: Boolean,
    vm: UIViewModel,
    onComplete: (String) -> Unit
) {
    if (listaArquivos.isEmpty()) {
        onComplete("Nenhum ficheiro selecionado.")
        return
    }

    val nomesParaApagar = mutableSetOf<String>()
    val idsRemotosParaApagar = mutableSetOf<Int>()
    val chaveAtiva = normalizarUsuario(usuarioAtivo)

    val prefsDel = context.getSharedPreferences("nexus_deleted_files_$chaveAtiva", Context.MODE_PRIVATE)
    val editorDel = prefsDel.edit()
    val prefsLinks = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    val editorLinks = prefsLinks.edit()
    val prefsOwners = context.getSharedPreferences("nexus_file_owners", Context.MODE_PRIVATE)
    val editorOwners = prefsOwners.edit()

    for (arq in listaArquivos) {
        val nomeLimpo = limparPrefixoTimestamp(arq.nomeArquivo)
        val nomeLower = nomeLimpo.lowercase(Locale.ROOT)
        nomesParaApagar.add(nomeLower)
        if (arq.fileId != 0) idsRemotosParaApagar.add(arq.fileId)

        editorDel.putBoolean("del_name_$nomeLower", true)
        editorDel.putBoolean("del_key_${nomeLower}|${arq.tamanhoBytes}", true)
        NexusAI.removerIndice(context, arq.dataUpload)

        try {
            val thumb = NexusThumbCache.obterMiniatura(arq)
            if (thumb.startsWith("file://") || thumb.startsWith("/")) {
                val limpo = if (thumb.startsWith("file://")) Uri.parse(thumb).path ?: thumb.removePrefix("file://") else thumb
                if (limpo.isNotBlank()) {
                    val f = File(limpo)
                    if (f.exists()) f.delete()
                    val fFrame = File("${limpo}_frame.jpg")
                    if (fFrame.exists()) fFrame.delete()
                }
            }
        } catch (_: Exception) {}

        NexusThumbCache.mapaMiniaturas.remove(arq.dataUpload)
        NexusThumbCache.mapaArquivosCompletos.remove(arq.dataUpload)
        editorLinks.remove(arq.dataUpload.toString())
        editorOwners.remove(arq.dataUpload.toString())
        vm.apagar(arq.id)
    }

    editorDel.apply()
    editorLinks.apply()
    editorOwners.apply()
    marcarLoteComoSincronizado(context, usuarioAtivo, listaArquivos)

    val qtd = listaArquivos.size
    if (!apagarNaNuvemSSD) {
        onComplete("$qtd ficheiro(s) removido(s) do aplicativo.")
        return
    }

    idsRemotosParaApagar.forEach { fId ->
        client.send(TdApi.DeleteFile().apply { fileId = fId }) {}
    }

    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"

    obterChatCofreUsuario(context, client, usuarioAtivo) { chatObj, modoCofre, _ ->
        if (chatObj != null) {
            buscarMensagensPorTermoNaNuvem(client, chatObj.id, tagPerfil) { msgsTag ->
                buscarHistoricoPaginado(client, chatObj.id, 0L, 15, mutableListOf()) { msgsHist ->
                    NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                        val todasMensagens = (msgsTag + msgsHist).distinctBy { it.id }
                        val msgIdsParaApagar = mutableListOf<Long>()
                        for (msg in todasMensagens) {
                            val legenda = when (val c = msg.content) {
                                is TdApi.MessageDocument -> c.caption?.text ?: ""
                                is TdApi.MessageAudio -> c.caption?.text ?: ""
                                is TdApi.MessagePhoto -> c.caption?.text ?: ""
                                is TdApi.MessageVideo -> c.caption?.text ?: ""
                                else -> ""
                            }
                            val pertenceAoUsuario = modoCofre == "CP" ||
                                legenda.contains(tagPerfil, ignoreCase = true) ||
                                legenda.contains(tagPerfilExibicao, ignoreCase = true) ||
                                legenda.contains("☁️ Nexus Cloud")

                            if (pertenceAoUsuario) {
                                val rId = when (val c = msg.content) {
                                    is TdApi.MessageDocument -> c.document?.document?.id ?: 0
                                    is TdApi.MessageAudio -> c.audio?.audio?.id ?: 0
                                    is TdApi.MessagePhoto -> c.photo?.sizes?.maxByOrNull { it.photo?.size ?: 0 }?.photo?.id ?: 0
                                    is TdApi.MessageVideo -> c.video?.video?.id ?: 0
                                    else -> 0
                                }
                                val nomeBruto = when (val c = msg.content) {
                                    is TdApi.MessageDocument -> c.document?.fileName ?: ""
                                    is TdApi.MessageAudio -> c.audio?.fileName?.ifBlank { c.audio?.title ?: "" } ?: ""
                                    is TdApi.MessageVideo -> c.video?.fileName ?: ""
                                    else -> ""
                                }
                                val nomeMsg = extrairNomeLimpoDaMensagem(nomeBruto, legenda).lowercase(Locale.ROOT)

                                if ((rId != 0 && idsRemotosParaApagar.contains(rId)) || nomesParaApagar.contains(nomeMsg)) {
                                    msgIdsParaApagar.add(msg.id)
                                }
                            }
                        }

                        if (msgIdsParaApagar.isNotEmpty()) {
                            val delReq = TdApi.DeleteMessages().apply {
                                this.chatId = chatObj.id
                                this.messageIds = msgIdsParaApagar.toLongArray()
                                this.revoke = true
                            }
                            client.send(delReq) {
                                onComplete("$qtd ficheiro(s) deletado(s) do app e da nuvem SSD ($modoCofre)!")
                            }
                        } else {
                            onComplete("$qtd ficheiro(s) deletado(s)!")
                        }
                    }
                }
            }
        } else {
            onComplete("$qtd ficheiro(s) deletado(s) localmente.")
        }
    }
}

fun migrarDadosV11SeNecessario(context: Context, usuario: String, arquivos: List<ArquivoNuvem>, pastas: List<PastaNuvem>) {
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    if (!prefs.getBoolean("migrated_v11_data", false)) {
        val donosAtuais = carregarDonosFicheiros(context)
        val prefsOwners = context.getSharedPreferences("nexus_file_owners", Context.MODE_PRIVATE)
        var editorOwners: android.content.SharedPreferences.Editor? = null
        val normUser = normalizarUsuario(usuario)
        arquivos.forEach { arq ->
            if (!donosAtuais.containsKey(arq.dataUpload)) {
                if (editorOwners == null) editorOwners = prefsOwners.edit()
                editorOwners?.putString(arq.dataUpload.toString(), normUser)
            }
        }
        editorOwners?.apply()
        pastas.forEach { p ->
            associarPastaAoUsuario(context, usuario, p.nomePasta)
        }
        prefs.edit().putBoolean("migrated_v11_data", true).apply()
    }
    if (!prefs.getBoolean("fixed_video_thumbs_v19", false)) {
        NexusThumbCache.limparCapasDeVideos(context, arquivos)
        prefs.edit().putBoolean("fixed_video_thumbs_v19", true).apply()
    }
}

fun miniaturaExisteLocalmente(caminhoMiniatura: String?, nomeArquivo: String = ""): Boolean {
    val resolvido = resolverCaminhoCapaJpg(caminhoMiniatura)
    if (resolvido.isBlank()) return false
    if (resolvido.startsWith("content://")) {
        return !ehArquivoVideo(nomeArquivo)
    }
    if (NexusThumbCache.mapaMiniaturas.isEmpty() && NexusPreviewTurbo.caminhosConfirmados.isNotEmpty()) {
        NexusPreviewTurbo.caminhosConfirmados.clear()
    }
    if (NexusPreviewTurbo.caminhosConfirmados.contains(resolvido)) {
        return true
    }
    val limpo = if (resolvido.startsWith("file://")) Uri.parse(resolvido).path ?: resolvido.removePrefix("file://") else resolvido
    if (limpo.isBlank()) return false
    val f = File(limpo)
    val existe = f.exists() && f.length() > 256L
    if (existe) {
        NexusPreviewTurbo.caminhosConfirmados.add(resolvido)
    }
    return existe
}

fun arquivoCompletoExisteLocalmente(caminho: String?): Boolean {
    val c = caminho ?: return false
    if (c.isBlank()) return false
    if (c.startsWith("content://")) return true
    val limpo = if (c.startsWith("file://")) Uri.parse(c).path ?: c.removePrefix("file://") else c
    if (limpo.isBlank() || limpo.endsWith("_frame.jpg")) return false
    val f = File(limpo)
    return f.exists() && f.length() > 256L
}

fun limparDuplicadosExistentes(
    context: Context,
    todosArquivos: List<ArquivoNuvem>,
    vm: UIViewModel,
    onCleaned: () -> Unit
) {
    if (todosArquivos.isEmpty()) return
    val donos = carregarDonosFicheiros(context)
    val pastas = carregarVinculosPastas(context)
    val paraSincronizarPorDono = mutableMapOf<String, MutableList<ArquivoNuvem>>()
    val pastasPorDono = mutableMapOf<String, MutableSet<String>>()

    val grupos = todosArquivos.groupBy { arq ->
        val dono = donos[arq.dataUpload] ?: ""
        val nomeBase = limparPrefixoTimestamp(arq.nomeArquivo).lowercase(Locale.ROOT)
        "${dono}|${nomeBase}|${arq.tamanhoBytes}"
    }

    var alterouAlgum = false
    for ((_, listaGrupo) in grupos) {
        if (listaGrupo.size > 1) {
            val principal = listaGrupo.sortedWith(
                compareByDescending<ArquivoNuvem> { miniaturaExisteLocalmente(NexusThumbCache.obterMiniatura(it), it.nomeArquivo) }
                    .thenByDescending { it.fileId != 0 }
                    .thenByDescending { !pastas[it.dataUpload].isNullOrBlank() }
                    .thenByDescending { it.dataUpload }
                    .thenBy { it.nomeArquivo.length }
                    .thenBy { it.id }
            ).first()

            val pastaEncontrada = listaGrupo.firstNotNullOfOrNull { pastas[it.dataUpload] }
            if (!pastaEncontrada.isNullOrBlank() && pastas[principal.dataUpload].isNullOrBlank()) {
                salvarVinculoPasta(context, principal.dataUpload, pastaEncontrada)
            }

            val donoEncontrado = listaGrupo.firstNotNullOfOrNull { donos[it.dataUpload] }
            if (!donoEncontrado.isNullOrBlank()) {
                if (donos[principal.dataUpload].isNullOrBlank()) {
                    salvarDonoFicheiro(context, principal.dataUpload, donoEncontrado)
                }
                if (!pastaEncontrada.isNullOrBlank()) {
                    pastasPorDono.getOrPut(donoEncontrado) { mutableSetOf() }.add(pastaEncontrada)
                }
                paraSincronizarPorDono.getOrPut(donoEncontrado) { mutableListOf() }.add(principal)
            }

            listaGrupo.forEach { item ->
                if (item.id != principal.id) {
                    vm.apagar(item.id)
                    alterouAlgum = true
                }
            }
            val nomeLimpoPrincipal = limparPrefixoTimestamp(principal.nomeArquivo)
            if (principal.nomeArquivo != nomeLimpoPrincipal) {
                vm.renomearArquivo(principal, nomeLimpoPrincipal)
                alterouAlgum = true
            }
        } else {
            val unico = listaGrupo.first()
            val donoUnico = donos[unico.dataUpload] ?: ""
            val pastaUnico = pastas[unico.dataUpload] ?: ""
            if (donoUnico.isNotBlank()) {
                paraSincronizarPorDono.getOrPut(donoUnico) { mutableListOf() }.add(unico)
                if (pastaUnico.isNotBlank()) {
                    pastasPorDono.getOrPut(donoUnico) { mutableSetOf() }.add(pastaUnico)
                }
            }
            val nomeLimpo = limparPrefixoTimestamp(unico.nomeArquivo)
            if (unico.nomeArquivo != nomeLimpo) {
                vm.renomearArquivo(unico, nomeLimpo)
                alterouAlgum = true
            }
        }
    }

    for ((dono, itens) in paraSincronizarPorDono) {
        marcarLoteComoSincronizado(context, dono, itens)
    }
    for ((dono, setPastas) in pastasPorDono) {
        setPastas.forEach { p -> associarPastaAoUsuario(context, dono, p) }
    }

    if (alterouAlgum) {
        onCleaned()
    }
}

fun extrairArrayMensagensTDLib(obj: TdApi.Object?): Array<TdApi.Message>? {
    if (obj == null) return null
    if (obj is TdApi.Messages) return obj.messages
    return try {
        @Suppress("UNCHECKED_CAST")
        obj.javaClass.getField("messages").get(obj) as? Array<TdApi.Message>
    } catch (_: Exception) {
        null
    }
}

fun buscarMensagensPorTermoNaNuvem(
    client: Client,
    chatId: Long,
    termo: String,
    onResult: (List<TdApi.Message>) -> Unit
) {
    if (termo.isBlank()) {
        onResult(emptyList())
        return
    }
    try {
        val req = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = termo
            this.fromMessageId = 0L
            this.offset = 0
            this.limit = 100
        }
        client.send(req) { obj ->
            val arr = extrairArrayMensagensTDLib(obj)
            onResult(arr?.filterNotNull() ?: emptyList())
        }
    } catch (_: Exception) {
        onResult(emptyList())
    }
}

// Paginação leve fora da thread nativa da TDLib com progresso em tempo real
fun buscarHistoricoPaginado(
    client: Client,
    chatId: Long,
    fromMsgId: Long,
    lotesRestantes: Int,
    acumulado: MutableList<TdApi.Message>,
    tentativasVazio: Int = 0,
    onProgress: ((Int) -> Unit)? = null,
    onComplete: (List<TdApi.Message>) -> Unit
) {
    if (lotesRestantes <= 0) {
        onComplete(acumulado.distinctBy { it.id })
        return
    }
    if (fromMsgId == 0L && tentativasVazio == 0) {
        try { client.send(TdApi.OpenChat(chatId)) {} } catch (_: Exception) {}
    }

    val req = TdApi.GetChatHistory().apply {
        this.chatId = chatId
        this.fromMessageId = fromMsgId
        this.offset = 0
        this.limit = 100
        this.onlyLocal = false
    }
    client.send(req) { obj ->
        val msgs = extrairArrayMensagensTDLib(obj)?.filterNotNull() ?: emptyList()

        if (msgs.isNotEmpty()) {
            acumulado.addAll(msgs)
            onProgress?.invoke(acumulado.size)
            val ultimoId = msgs.minOf { it.id }
            if (ultimoId != 0L && ultimoId != fromMsgId) {
                NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                    delay(35)
                    buscarHistoricoPaginado(client, chatId, ultimoId, lotesRestantes - 1, acumulado, 0, onProgress, onComplete)
                }
            } else {
                onComplete(acumulado.distinctBy { it.id })
            }
        } else if (tentativasVazio < 3 && (fromMsgId == 0L || acumulado.size <= 3)) {
            NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                delay(500)
                buscarHistoricoPaginado(client, chatId, fromMsgId, lotesRestantes, acumulado, tentativasVazio + 1, onProgress, onComplete)
            }
        } else {
            onComplete(acumulado.distinctBy { it.id })
        }
    }
}

// Sincroniza silenciosamente os PINs, modos (MS/CP), IDs de canais e status de bloqueio [STATUS_COFRE]
fun descobrirPerfisExistentesNaNuvem(
    context: Context,
    client: Client,
    onFound: (List<String>) -> Unit
) {
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, "REGISTO_COFRE") { msgsBusca ->
                        buscarMensagensPorTermoNaNuvem(client, chatObj.id, "STATUS_COFRE") { msgsStatus ->
                            buscarHistoricoPaginado(client, chatObj.id, 0L, 12, mutableListOf()) { msgsHist ->
                                NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                                    val todas = (msgsBusca + msgsStatus + msgsHist).distinctBy { it.id }
                                    val ordenadas = todas.sortedWith(
                                        compareByDescending<TdApi.Message> { it.date }.thenByDescending { it.id }
                                    )

                                    // Aplica primeiro todos os status remotos mais recentes (trava anti-offline)
                                    val mapaStatus = extrairStatusCofreDasMensagens(ordenadas)
                                    mapaStatus.forEach { (chaveUser, st) ->
                                        salvarStatusCofreLocal(context, chaveUser, st.bloqueado, st.motivo, st.atualizadoEm)
                                    }

                                    val nomesEncontrados = mutableMapOf<String, String>()
                                    for (msg in ordenadas) {
                                        val texto = when (val c = msg.content) {
                                            is TdApi.MessageText -> c.text?.text ?: ""
                                            is TdApi.MessageDocument -> c.caption?.text ?: ""
                                            is TdApi.MessageAudio -> c.caption?.text ?: ""
                                            else -> ""
                                        }
                                        if (texto.contains("[REGISTO_COFRE]")) {
                                            val partes = texto.split("|").map { it.trim() }
                                            val userPart = partes.firstOrNull { it.startsWith("USER:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                            val nomePart = partes.firstOrNull { it.startsWith("NOME:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                            val pinPart = partes.firstOrNull { it.startsWith("PIN:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                            val modePart = partes.firstOrNull { it.startsWith("MODE:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                            val chatPart = partes.firstOrNull { it.startsWith("CHAT:", ignoreCase = true) }?.substringAfter(":")?.trim()?.toLongOrNull()

                                            val chave = normalizarUsuario(userPart ?: nomePart ?: "")
                                            val exibicao = (nomePart ?: userPart ?: "").trim()
                                            if (chave.isNotBlank() && exibicao.isNotBlank() && !nomesEncontrados.containsKey(chave)) {
                                                nomesEncontrados[chave] = exibicao
                                                if (!pinPart.isNullOrBlank()) {
                                                    salvarCredenciaisCofreLocal(
                                                        context = context,
                                                        nomeOriginal = exibicao,
                                                        pin = pinPart,
                                                        definirComoAtivo = false,
                                                        modoArmazenamento = modePart,
                                                        canalChatId = chatPart
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    onFound(nomesEncontrados.values.toList())
                                }
                            }
                        }
                    }
                } else onFound(emptyList())
            }
        } else onFound(emptyList())
    }
}

// Verifica ou regista o cofre com suporte híbrido a MS/CP e checagem de bloqueio [STATUS_COFRE]
fun verificarOuRegistarCofreNuvem(
    context: Context,
    client: Client,
    nomeUsuario: String,
    pin: String,
    modoCriarNovo: Boolean,
    modoArmazenamentoEscolhido: String = "MS",
    onResult: (Boolean, String, String, Long, String) -> Unit
) {
    val chaveDesejada = normalizarUsuario(nomeUsuario)
    val pinLimpo = pin.trim()
    val modoDesejado = modoArmazenamentoEscolhido.uppercase(Locale.ROOT).trim().let {
        if (it == "CP") "CP" else "MS"
    }

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, "REGISTO_COFRE") { msgsRegisto ->
                        buscarMensagensPorTermoNaNuvem(client, chatObj.id, "STATUS_COFRE") { msgsStatus ->
                            buscarMensagensPorTermoNaNuvem(client, chatObj.id, nomeUsuario.trim()) { msgsUsuario ->
                                buscarHistoricoPaginado(client, chatObj.id, 0L, 15, mutableListOf()) { msgsHistorico ->
                                    NexusBackgroundScope.scope.launch(Dispatchers.IO) {
                                        val todasMensagens = (msgsRegisto + msgsStatus + msgsUsuario + msgsHistorico)
                                            .distinctBy { it.id }
                                            .sortedWith(compareByDescending<TdApi.Message> { it.date }.thenByDescending { it.id })

                                        val mapaStatus = extrairStatusCofreDasMensagens(todasMensagens)
                                        val statusRemoto = mapaStatus[chaveDesejada]
                                        if (statusRemoto != null) {
                                            salvarStatusCofreLocal(
                                                context,
                                                chaveDesejada,
                                                statusRemoto.bloqueado,
                                                statusRemoto.motivo,
                                                statusRemoto.atualizadoEm
                                            )
                                        }

                                        var pinEncontrado: String? = null
                                        var nomeOriginalEncontrado = nomeUsuario.trim()
                                        var modoEncontrado = "MS"
                                        var chatIdCanalEncontrado = 0L
                                        var temArquivosDoUsuarioNaNuvem = false

                                        for (msg in todasMensagens) {
                                            val texto = when (val c = msg.content) {
                                                is TdApi.MessageText -> c.text?.text ?: ""
                                                is TdApi.MessageDocument -> c.caption?.text ?: ""
                                                is TdApi.MessageAudio -> c.caption?.text ?: ""
                                                is TdApi.MessagePhoto -> c.caption?.text ?: ""
                                                is TdApi.MessageVideo -> c.caption?.text ?: ""
                                                else -> ""
                                            }

                                            if (texto.contains("[REGISTO_COFRE]") && pinEncontrado == null) {
                                                val partes = texto.split("|").map { it.trim() }
                                                val userPart = partes.firstOrNull { it.startsWith("USER:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                                val nomePart = partes.firstOrNull { it.startsWith("NOME:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                                val pinPart = partes.firstOrNull { it.startsWith("PIN:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                                val modePart = partes.firstOrNull { it.startsWith("MODE:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                                val chatPart = partes.firstOrNull { it.startsWith("CHAT:", ignoreCase = true) }?.substringAfter(":")?.trim()?.toLongOrNull()

                                                val chaveMsg = normalizarUsuario(userPart ?: nomePart ?: "")
                                                if (chaveMsg == chaveDesejada && !pinPart.isNullOrBlank()) {
                                                    pinEncontrado = pinPart
                                                    if (!nomePart.isNullOrBlank()) nomeOriginalEncontrado = nomePart
                                                    if (!modePart.isNullOrBlank()) modoEncontrado = modePart.uppercase(Locale.ROOT)
                                                    if (chatPart != null && chatPart != 0L) chatIdCanalEncontrado = chatPart
                                                }
                                            }

                                            if (msg.content is TdApi.MessageDocument ||
                                                msg.content is TdApi.MessageAudio ||
                                                msg.content is TdApi.MessagePhoto ||
                                                msg.content is TdApi.MessageVideo
                                            ) {
                                                if (texto.contains("👤 [$chaveDesejada]", ignoreCase = true) ||
                                                    texto.contains("👤 [${nomeUsuario.trim()}]", ignoreCase = true)
                                                ) {
                                                    temArquivosDoUsuarioNaNuvem = true
                                                    val extraido = texto.substringAfter("👤 [", "").substringBefore("]", "").trim()
                                                    if (extraido.isNotBlank() && nomeOriginalEncontrado.equals(nomeUsuario.trim(), ignoreCase = true)) {
                                                        nomeOriginalEncontrado = extraido
                                                    }
                                                }
                                            }
                                        }

                                        // Se o perfil já existe e está bloqueado pelo admin, barra antes de liberar
                                        val statusEfetivo = statusRemoto ?: obterStatusCofreLocal(context, chaveDesejada)
                                        if (statusEfetivo.bloqueado && (pinEncontrado != null || temArquivosDoUsuarioNaNuvem || !modoCriarNovo)) {
                                            onResult(
                                                false,
                                                nomeOriginalEncontrado,
                                                modoEncontrado,
                                                chatIdCanalEncontrado,
                                                "BLOQUEADO::${statusEfetivo.motivo.ifBlank { "Acesso temporariamente suspenso." }}"
                                            )
                                            return@launch
                                        }

                                        fun gravarRegistoNaNuvem(
                                            nomeConfirmado: String,
                                            modoFinal: String,
                                            chatIdCanal: Long,
                                            mensagemSucesso: String
                                        ) {
                                            val regTexto = "🔒 [REGISTO_COFRE] | USER:$chaveDesejada | NOME:${nomeConfirmado.trim()} | PIN:$pinLimpo | MODE:$modoFinal | CHAT:$chatIdCanal"
                                            val msgContent = TdApi.InputMessageText().apply {
                                                text = TdApi.FormattedText(regTexto, emptyArray())
                                            }
                                            val sendReq = TdApi.SendMessage().apply {
                                                this.chatId = chatObj.id
                                                this.inputMessageContent = msgContent
                                            }
                                            client.send(sendReq) {
                                                if (modoFinal == "CP" && chatIdCanal != 0L) {
                                                    val sendCanal = TdApi.SendMessage().apply {
                                                        this.chatId = chatIdCanal
                                                        this.inputMessageContent = TdApi.InputMessageText().apply {
                                                            text = TdApi.FormattedText(regTexto, emptyArray())
                                                        }
                                                    }
                                                    client.send(sendCanal) {}
                                                }
                                                onResult(true, nomeConfirmado.trim(), modoFinal, chatIdCanal, mensagemSucesso)
                                            }
                                        }

                                        if (modoCriarNovo) {
                                            when {
                                                pinEncontrado != null && pinEncontrado == pinLimpo -> {
                                                    onResult(true, nomeOriginalEncontrado, modoEncontrado, chatIdCanalEncontrado, "Cofre existente ($modoEncontrado) reconhecido e restaurado!")
                                                }
                                                pinEncontrado != null && pinEncontrado != pinLimpo -> {
                                                    onResult(false, "", modoEncontrado, 0L, "Este perfil já existe na nuvem SSD! Digite o PIN correto ou escolha outro nome.")
                                                }
                                                temArquivosDoUsuarioNaNuvem -> {
                                                    gravarRegistoNaNuvem(nomeOriginalEncontrado, "MS", 0L, "Perfil existente (MS) vinculado e restaurado!")
                                                }
                                                modoDesejado == "CP" -> {
                                                    criarCanalPrivadoTDLib(client, nomeUsuario.trim()) { novoCanalId, erroCanal ->
                                                        if (novoCanalId != 0L) {
                                                            gravarRegistoNaNuvem(
                                                                nomeConfirmado = nomeUsuario.trim(),
                                                                modoFinal = "CP",
                                                                chatIdCanal = novoCanalId,
                                                                mensagemSucesso = "Cofre SSD em Canal Privado (CP) criado com sucesso!"
                                                            )
                                                        } else {
                                                            onResult(false, "", "CP", 0L, erroCanal ?: "Falha ao criar Canal Privado (CP). Tente o modo MS.")
                                                        }
                                                    }
                                                }
                                                else -> {
                                                    gravarRegistoNaNuvem(nomeUsuario.trim(), "MS", 0L, "Cofre SSD em Mensagens Salvas (MS) criado com sucesso!")
                                                }
                                            }
                                        } else {
                                            when {
                                                pinEncontrado != null && pinEncontrado == pinLimpo -> {
                                                    onResult(true, nomeOriginalEncontrado, modoEncontrado, chatIdCanalEncontrado, "Cofre SSD ($modoEncontrado) desbloqueado!")
                                                }
                                                pinEncontrado != null && pinEncontrado != pinLimpo -> {
                                                    onResult(false, "", modoEncontrado, 0L, "PIN incorreto para o utilizador '$nomeOriginalEncontrado'.")
                                                }
                                                temArquivosDoUsuarioNaNuvem -> {
                                                    gravarRegistoNaNuvem(nomeOriginalEncontrado, "MS", 0L, "Cofre '$nomeOriginalEncontrado' (MS) reconhecido na nuvem SSD!")
                                                }
                                                else -> {
                                                    onResult(false, "", "MS", 0L, "Cofre '${nomeUsuario.trim()}' não encontrado na nuvem SSD. Verifique o nome ou use 'CRIAR COFRE'.")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    onResult(false, "", "MS", 0L, "Erro ao aceder ao cofre na nuvem SSD.")
                }
            }
        } else {
            onResult(false, "", "MS", 0L, "Sessão SSD indisponível.")
        }
    }
}

fun calcularBrilhoMedioBitmap(bmp: Bitmap): Int {
    if (bmp.width <= 0 || bmp.height <= 0) return 0
    var somaBrilho = 0L
    var totalAmostras = 0
    val cols = 5
    val rows = 5
    for (ix in 1..cols) {
        for (iy in 1..rows) {
            val x = ((bmp.width * ix) / (cols + 1)).coerceIn(0, bmp.width - 1)
            val y = ((bmp.height * iy) / (rows + 1)).coerceIn(0, bmp.height - 1)
            val px = bmp.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            somaBrilho += luma
            totalAmostras++
        }
    }
    return if (totalAmostras > 0) (somaBrilho / totalAmostras).toInt() else 0
}

fun clarearBitmapVideoSeNecessario(origem: Bitmap): Bitmap {
    val brilho = calcularBrilhoMedioBitmap(origem)
    if (brilho >= 68) return origem

    return try {
        val fatorEscala = when {
            brilho < 20 -> 1.75f
            brilho < 40 -> 1.48f
            else -> 1.25f
        }
        val offsetLuz = when {
            brilho < 20 -> 42f
            brilho < 40 -> 28f
            else -> 16f
        }
        val cm = ColorMatrix(
            floatArrayOf(
                fatorEscala, 0f, 0f, 0f, offsetLuz,
                0f, fatorEscala, 0f, 0f, offsetLuz,
                0f, 0f, fatorEscala, 0f, offsetLuz,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val resultado = Bitmap.createBitmap(origem.width, origem.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultado)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(origem, 0f, 0f, paint)
        resultado
    } catch (_: Exception) {
        origem
    }
}

fun extrairCapaVideoParaArquivo(
    context: Context,
    arquivoVideo: File?,
    uriFallback: Uri?,
    destinoFrame: File
): Boolean {
    if (destinoFrame.exists() && destinoFrame.length() > 256L) {
        val existenteBmp = try { BitmapFactory.decodeFile(destinoFrame.absolutePath) } catch (_: Exception) { null }
        if (existenteBmp != null) {
            val brilhoOk = calcularBrilhoMedioBitmap(existenteBmp) >= 35
            existenteBmp.recycle()
            if (brilhoOk) return true
        }
    }

    var melhorBitmap: Bitmap? = null
    var melhorBrilho = -1

    val retriever = MediaMetadataRetriever()
    try {
        if (arquivoVideo != null && arquivoVideo.exists() && arquivoVideo.length() > 100L) {
            retriever.setDataSource(arquivoVideo.absolutePath)
        } else if (uriFallback != null) {
            retriever.setDataSource(context, uriFallback)
        }

        val duracaoMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val temposUs = mutableListOf<Long>()
        if (duracaoMs > 500L) {
            temposUs.add((duracaoMs * 250L))
            temposUs.add((duracaoMs * 450L))
            temposUs.add((duracaoMs * 150L))
            temposUs.add((duracaoMs * 650L))
        }
        temposUs.addAll(listOf(1_500_000L, 3_000_000L, 800_000L, 300_000L, 0L))

        for (t in temposUs) {
            val candidato = try {
                retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(t)
            } catch (_: Exception) {
                null
            }
            if (candidato != null) {
                val b = calcularBrilhoMedioBitmap(candidato)
                if (b > melhorBrilho) {
                    melhorBitmap?.recycle()
                    melhorBrilho = b
                    melhorBitmap = candidato
                } else {
                    candidato.recycle()
                }
                if (b >= 48) break
            }
        }
    } catch (_: Exception) {
    } finally {
        try { retriever.release() } catch (_: Exception) {}
    }

    if ((melhorBitmap == null || melhorBrilho < 20) && arquivoVideo != null && arquivoVideo.exists()) {
        try {
            @Suppress("DEPRECATION")
            val thumbFallback = ThumbnailUtils.createVideoThumbnail(
                arquivoVideo.absolutePath,
                MediaStore.Images.Thumbnails.MINI_KIND
            )
            if (thumbFallback != null) {
                val b = calcularBrilhoMedioBitmap(thumbFallback)
                if (melhorBitmap == null || b > melhorBrilho) {
                    melhorBitmap?.recycle()
                    melhorBitmap = thumbFallback
                } else {
                    thumbFallback.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    val bmpBruto = melhorBitmap ?: return false
    val bmpFinal = clarearBitmapVideoSeNecessario(bmpBruto)
    if (bmpFinal !== bmpBruto) bmpBruto.recycle()

    return try {
        val tempFile = File("${destinoFrame.absolutePath}.tmp")
        FileOutputStream(tempFile).use { out ->
            bmpFinal.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        bmpFinal.recycle()
        if (tempFile.exists() && tempFile.length() > 256L) {
            if (destinoFrame.exists()) destinoFrame.delete()
            tempFile.renameTo(destinoFrame)
            true
        } else {
            tempFile.delete()
            false
        }
    } catch (_: Exception) {
        try { bmpFinal.recycle() } catch (_: Throwable) {}
        false
    }
}

fun gerarMiniaturaImagemLeve(arquivoImagem: File, destinoFrame: File): Boolean {
    if (destinoFrame.exists() && destinoFrame.length() > 256L) {
        NexusPreviewTurbo.registrarCaminhoValido(Uri.fromFile(destinoFrame).toString())
        return true
    }
    return try {
        val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(arquivoImagem.absolutePath, optsBounds)
        if (optsBounds.outWidth <= 0 || optsBounds.outHeight <= 0) return false

        var sample = 1
        while (optsBounds.outWidth / sample > 240 || optsBounds.outHeight / sample > 240) {
            sample *= 2
        }
        val optsDecode = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(arquivoImagem.absolutePath, optsDecode) ?: return false
        val tempFile = File("${destinoFrame.absolutePath}.tmp")
        FileOutputStream(tempFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        }
        bmp.recycle()
        if (tempFile.exists() && tempFile.length() > 256L) {
            if (destinoFrame.exists()) destinoFrame.delete()
            tempFile.renameTo(destinoFrame)
            NexusPreviewTurbo.registrarCaminhoValido(Uri.fromFile(destinoFrame).toString())
            true
        } else {
            tempFile.delete()
            false
        }
    } catch (_: Exception) {
        false
    }
}

fun salvarMiniaturaRapidaTDLib(context: Context, arquivoThumb: File, fileId: Int, nomeArquivo: String): String {
    return try {
        if (!arquivoThumb.exists() || arquivoThumb.length() <= 100L) return ""
        val ehVid = ehArquivoVideo(nomeArquivo)
        val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
        val nomeSeguro = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fFrame = File(dir, "fast_${fileId}_${nomeSeguro}_frame.jpg")

        // Para fotos e PDFs: copia o .jpg direto sem decodificar Bitmap (zero custo de CPU)
        if (!ehVid) {
            val tempFile = File("${fFrame.absolutePath}.tmp")
            FileInputStream(arquivoThumb).use { inp ->
                FileOutputStream(tempFile).use { out -> inp.copyTo(out) }
            }
            if (tempFile.exists() && tempFile.length() > 128L) {
                if (fFrame.exists()) fFrame.delete()
                tempFile.renameTo(fFrame)
                val uriFinal = Uri.fromFile(fFrame).toString()
                NexusPreviewTurbo.registrarCaminhoValido(uriFinal)
                return uriFinal
            }
            tempFile.delete()
            return ""
        }

        // Para vídeos: checa se a capa não veio preta e aplica clareamento leve
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(arquivoThumb.absolutePath, opts) ?: return ""
        if (bmp.width <= 0 || bmp.height <= 0) {
            bmp.recycle()
            return ""
        }

        val brilho = calcularBrilhoMedioBitmap(bmp)
        if (brilho < 22) {
            bmp.recycle()
            return ""
        }

        val bmpAjustado = clarearBitmapVideoSeNecessario(bmp)
        if (bmpAjustado !== bmp) bmp.recycle()
        val tempFile = File("${fFrame.absolutePath}.tmp")
        FileOutputStream(tempFile).use { out ->
            bmpAjustado.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        bmpAjustado.recycle()
        if (tempFile.exists() && tempFile.length() > 256L) {
            if (fFrame.exists()) fFrame.delete()
            tempFile.renameTo(fFrame)
            val uriFinal = Uri.fromFile(fFrame).toString()
            NexusPreviewTurbo.registrarCaminhoValido(uriFinal)
            uriFinal
        } else {
            tempFile.delete()
            ""
        }
    } catch (_: Exception) {
        ""
    }
}

fun prepararMiniaturaDeFicheiroBaixado(context: Context, arquivoOrigem: File, nomeArquivo: String): String {
    return try {
        if (!arquivoOrigem.exists() || arquivoOrigem.length() <= 100L) return ""
        val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
        val nomeSeguro = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val baseFile = File(dir, "thumb_${arquivoOrigem.length()}_$nomeSeguro")
        val frameFile = File("${baseFile.absolutePath}_frame.jpg")

        if (ehArquivoVideo(nomeSeguro)) {
            val ok = extrairCapaVideoParaArquivo(context, arquivoOrigem, null, frameFile)
            return if (ok && frameFile.exists() && frameFile.length() > 256L) {
                val u = Uri.fromFile(frameFile).toString()
                NexusPreviewTurbo.registrarCaminhoValido(u)
                u
            } else ""
        }

        if (!baseFile.exists() || baseFile.length() <= 100L) {
            FileInputStream(arquivoOrigem).use { inp ->
                FileOutputStream(baseFile).use { out -> inp.copyTo(out) }
            }
        }

        val resultado = when {
            ehArquivoImagem(nomeSeguro) -> {
                val gerou = gerarMiniaturaImagemLeve(baseFile, frameFile)
                if (gerou && frameFile.exists()) Uri.fromFile(frameFile).toString() else Uri.fromFile(baseFile).toString()
            }
            descobrirMimeType(nomeSeguro) == "application/pdf" -> {
                val capaPdf = gerarCapaPdf(baseFile)
                if (capaPdf != null && capaPdf.exists()) Uri.fromFile(capaPdf).toString() else ""
            }
            else -> Uri.fromFile(baseFile).toString()
        }
        if (resultado.isNotBlank()) NexusPreviewTurbo.registrarCaminhoValido(resultado)
        resultado
    } catch (_: Exception) {
        ""
    }
}

fun extrairNomeLimpoDaMensagem(nomeDocumento: String, legenda: String): String {
    val daLegenda = if (legenda.contains("• ")) {
        legenda.substringAfterLast("• ").trim()
    } else ""
    val candidato = if (daLegenda.isNotBlank() && daLegenda.contains(".")) daLegenda else nomeDocumento
    return limparPrefixoTimestamp(candidato).ifBlank { "Arquivo_Nuvem" }
}

// === SINCRONIZAÇÃO HÍBRIDA LEVE (CP DIRETO NO HISTÓRICO | MS COM ETIQUETAS | THUMBID EM FOTOS + MINITHUMB) ===
fun sincronizarCofreDaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arquivosAtuaisReais: List<ArquivoNuvem>,
    dao: NexusDao,
    vm: UIViewModel,
    onComplete: (Int) -> Unit
) {
    if (NexusSyncState.emAndamento.value) {
        onComplete(0)
        return
    }
    NexusSyncState.emAndamento.value = true

    val chaveAtiva = normalizarUsuario(usuarioAtivo)
    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"
    val modoCofre = obterModoCofreLocal(context, usuarioAtivo)

    fun encerrarSync(qtdImportados: Int, fecharBarra: Boolean = false) {
        if (fecharBarra) NexusLeituraNuvem.encerrar()
        NexusSyncState.emAndamento.value = false
        onComplete(qtdImportados)
    }

    NexusLeituraNuvem.iniciar(1, "Conectando ao cofre $modoCofre de $usuarioAtivo...")

    fun processarMensagensEmLoteIO(todasMensagens: List<TdApi.Message>, modoReal: String) {
        NexusBackgroundScope.scope.launch(Dispatchers.IO) {
            try {
                val pastasAtivasNuvem = mutableMapOf<String, String>()
                val pastasApagadasNuvem = mutableSetOf<String>()
                val mapaRenomeacoesNuvem = mutableMapOf<String, String>()
                val mapaMovidosPorChave = mutableMapOf<String, String>()
                val mapaMovidosPorNome = mutableMapOf<String, String>()

                val msgsMetadados = todasMensagens
                    .filter { it.content is TdApi.MessageText }
                    .sortedWith(compareBy<TdApi.Message> { it.date }.thenBy { it.id })

                for (msg in msgsMetadados) {
                    val txt = (msg.content as? TdApi.MessageText)?.text?.text ?: continue
                    if (!txt.contains("[SYNC_PASTA]") && !txt.contains("[SYNC_MOVER]")) continue

                    val partes = txt.split("|").map { it.trim() }
                    val userPart = partes.firstOrNull { it.startsWith("USER:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim() ?: ""
                    val pertenceAoUsuario = modoReal == "CP" ||
                        normalizarUsuario(userPart) == chaveAtiva
                    if (!pertenceAoUsuario) continue

                    if (txt.contains("[SYNC_PASTA]")) {
                        val acao = partes.firstOrNull { it.startsWith("ACAO:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()?.uppercase(Locale.ROOT) ?: ""
                        when (acao) {
                            "CRIAR" -> {
                                val p = partes.firstOrNull { it.startsWith("PASTA:", ignoreCase = true) }
                                    ?.substringAfter(":")?.trim() ?: ""
                                if (p.isNotBlank()) {
                                    val pLower = p.lowercase(Locale.ROOT)
                                    pastasApagadasNuvem.remove(pLower)
                                    pastasAtivasNuvem[pLower] = p
                                }
                            }
                            "RENOMEAR" -> {
                                val de = partes.firstOrNull { it.startsWith("DE:", ignoreCase = true) }
                                    ?.substringAfter(":")?.trim() ?: ""
                                val para = partes.firstOrNull { it.startsWith("PARA:", ignoreCase = true) }
                                    ?.substringAfter(":")?.trim() ?: ""
                                if (de.isNotBlank() && para.isNotBlank()) {
                                    val deLower = de.lowercase(Locale.ROOT)
                                    val paraLower = para.lowercase(Locale.ROOT)
                                    pastasAtivasNuvem.remove(deLower)
                                    pastasApagadasNuvem.add(deLower)
                                    pastasApagadasNuvem.remove(paraLower)
                                    pastasAtivasNuvem[paraLower] = para
                                    mapaRenomeacoesNuvem.entries.forEach { entry ->
                                        if (entry.value.equals(de, ignoreCase = true)) entry.setValue(para)
                                    }
                                    mapaRenomeacoesNuvem[deLower] = para
                                    mapaMovidosPorChave.entries.forEach { entry ->
                                        if (entry.value.equals(de, ignoreCase = true)) entry.setValue(para)
                                    }
                                    mapaMovidosPorNome.entries.forEach { entry ->
                                        if (entry.value.equals(de, ignoreCase = true)) entry.setValue(para)
                                    }
                                }
                            }
                            "APAGAR" -> {
                                val p = partes.firstOrNull { it.startsWith("PASTA:", ignoreCase = true) }
                                    ?.substringAfter(":")?.trim() ?: ""
                                if (p.isNotBlank()) {
                                    val pLower = p.lowercase(Locale.ROOT)
                                    pastasAtivasNuvem.remove(pLower)
                                    pastasApagadasNuvem.add(pLower)
                                    mapaMovidosPorChave.entries.forEach { entry ->
                                        if (entry.value.equals(p, ignoreCase = true)) entry.setValue("")
                                    }
                                    mapaMovidosPorNome.entries.forEach { entry ->
                                        if (entry.value.equals(p, ignoreCase = true)) entry.setValue("")
                                    }
                                }
                            }
                        }
                    } else if (txt.contains("[SYNC_MOVER]")) {
                        val pastaDest = partes.firstOrNull { it.startsWith("PASTA:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim() ?: ""
                        val itensStr = partes.firstOrNull { it.startsWith("ITENS:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim() ?: ""
                        if (pastaDest.isNotBlank()) {
                            val pLower = pastaDest.lowercase(Locale.ROOT)
                            pastasApagadasNuvem.remove(pLower)
                            pastasAtivasNuvem[pLower] = pastaDest
                        }
                        itensStr.split(";;").forEach { itemToken ->
                            val limpoToken = itemToken.trim()
                            if (limpoToken.isNotBlank()) {
                                val temHash = limpoToken.contains('#')
                                val nLower = (if (temHash) limpoToken.substringBeforeLast('#') else limpoToken)
                                    .trim().lowercase(Locale.ROOT)
                                val tStr = if (temHash) limpoToken.substringAfterLast('#').trim() else ""
                                if (nLower.isNotBlank()) {
                                    if (tStr.isNotBlank()) {
                                        mapaMovidosPorChave["$nLower|$tStr"] = pastaDest
                                    }
                                    mapaMovidosPorNome[nLower] = pastaDest
                                }
                            }
                        }
                    }
                }

                val mensagensDoUsuario = todasMensagens.filter { msg ->
                    val temMidia = msg.content is TdApi.MessageDocument ||
                        msg.content is TdApi.MessageAudio ||
                        msg.content is TdApi.MessagePhoto ||
                        msg.content is TdApi.MessageVideo
                    if (!temMidia) return@filter false

                    if (modoReal == "CP") {
                        true
                    } else {
                        val legenda = when (val c = msg.content) {
                            is TdApi.MessageDocument -> c.caption?.text ?: ""
                            is TdApi.MessageAudio -> c.caption?.text ?: ""
                            is TdApi.MessagePhoto -> c.caption?.text ?: ""
                            is TdApi.MessageVideo -> c.caption?.text ?: ""
                            else -> ""
                        }
                        legenda.contains(tagPerfil, ignoreCase = true) ||
                            legenda.contains(tagPerfilExibicao, ignoreCase = true)
                    }
                }

                val totalNaNuvem = mensagensDoUsuario.size
                if (totalNaNuvem == 0 && pastasAtivasNuvem.isEmpty()) {
                    encerrarSync(0, fecharBarra = true)
                    return@launch
                }

                if (totalNaNuvem > 0) {
                    NexusLeituraNuvem.iniciar(totalNaNuvem, "Restaurando cofre SSD ($modoReal)...")
                }

                val donosAtuais = carregarDonosFicheiros(context)
                val pastasAtuais = carregarVinculosPastas(context)

                val prefsMsgs = context.getSharedPreferences("nexus_synced_msgs_$chaveAtiva", Context.MODE_PRIVATE)
                var editorMsgs: android.content.SharedPreferences.Editor? = null
                val prefsSynced = context.getSharedPreferences("nexus_auto_synced_files_$chaveAtiva", Context.MODE_PRIVATE)
                var editorSynced: android.content.SharedPreferences.Editor? = null
                val prefsOwners = context.getSharedPreferences("nexus_file_owners", Context.MODE_PRIVATE)
                var editorOwners: android.content.SharedPreferences.Editor? = null
                val prefsLinks = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
                var editorLinks: android.content.SharedPreferences.Editor? = null
                val prefsThumbs = context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
                var editorThumbs: android.content.SharedPreferences.Editor? = null

                val loteThumbIds = mutableMapOf<Int, Int>()
                val loteMiniaturas = mutableMapOf<Long, String>()
                val loteCompletos = mutableMapOf<Long, String>()
                val novosParaInserirRoom = mutableListOf<ArquivoNuvem>()
                val existentesParaAtualizar = mutableListOf<ArquivoNuvem>()

                val pastasEncontradas = mutableSetOf<String>()
                pastasEncontradas.addAll(pastasAtivasNuvem.values)

                val mapaExistentesPorChave = arquivosAtuaisReais
                    .filter { donosAtuais[it.dataUpload] == chaveAtiva || donosAtuais.isEmpty() }
                    .associateBy { "${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}|${it.tamanhoBytes}" }
                    .toMutableMap()

                val timestampsUsados = arquivosAtuaisReais.map { it.dataUpload }.toMutableSet()

                mensagensDoUsuario.forEachIndexed { index, msg ->
                    var legenda = ""
                    var rId = 0
                    var thumbId = 0
                    var thumbPathLocal = ""
                    var nomeBruto = ""
                    var tam = 0L
                    var caminhoLocalExistente = ""
                    var miniBytes: ByteArray? = null

                    when (val c = msg.content) {
                        is TdApi.MessageDocument -> {
                            legenda = c.caption?.text ?: ""
                            rId = c.document?.document?.id ?: 0
                            thumbId = c.document?.thumbnail?.file?.id ?: 0
                            thumbPathLocal = c.document?.thumbnail?.file?.local?.path ?: ""
                            nomeBruto = c.document?.fileName?.ifBlank { "Arquivo_$rId" } ?: "Arquivo_$rId"
                            tam = c.document?.document?.size ?: 0L
                            caminhoLocalExistente = c.document?.document?.local?.path ?: ""
                            miniBytes = extrairMiniThumbnailBytes(c.document)
                        }
                        is TdApi.MessageAudio -> {
                            legenda = c.caption?.text ?: ""
                            rId = c.audio?.audio?.id ?: 0
                            thumbId = c.audio?.albumCoverThumbnail?.file?.id ?: 0
                            thumbPathLocal = c.audio?.albumCoverThumbnail?.file?.local?.path ?: ""
                            nomeBruto = c.audio?.fileName?.ifBlank { c.audio?.title ?: "Arquivo_$rId" } ?: "Arquivo_$rId"
                            tam = c.audio?.audio?.size ?: 0L
                            caminhoLocalExistente = c.audio?.audio?.local?.path ?: ""
                            miniBytes = extrairMiniThumbnailBytes(c.audio)
                        }
                        is TdApi.MessagePhoto -> {
                            legenda = c.caption?.text ?: ""
                            val maior = c.photo?.sizes?.maxByOrNull { it.photo?.size ?: 0 }
                            val thumbPhoto = escolherFotoMiniaturaLeve(c.photo)
                            rId = maior?.photo?.id ?: 0
                            thumbId = thumbPhoto?.photo?.id?.takeIf { it != 0 && it != rId } ?: 0
                            thumbPathLocal = if (thumbId != 0) (thumbPhoto?.photo?.local?.path ?: "") else ""
                            nomeBruto = "Foto_$rId.jpg"
                            tam = maior?.photo?.size ?: 0L
                            caminhoLocalExistente = maior?.photo?.local?.path ?: ""
                            miniBytes = extrairMiniThumbnailBytes(c.photo)
                        }
                        is TdApi.MessageVideo -> {
                            legenda = c.caption?.text ?: ""
                            rId = c.video?.video?.id ?: 0
                            thumbId = c.video?.thumbnail?.file?.id ?: 0
                            thumbPathLocal = c.video?.thumbnail?.file?.local?.path ?: ""
                            nomeBruto = c.video?.fileName?.ifBlank { "Video_$rId.mp4" } ?: "Video_$rId.mp4"
                            tam = c.video?.video?.size ?: 0L
                            caminhoLocalExistente = c.video?.video?.local?.path ?: ""
                            miniBytes = extrairMiniThumbnailBytes(c.video)
                        }
                        else -> return@forEachIndexed
                    }

                    if (rId != 0 && thumbId != 0) {
                        loteThumbIds[rId] = thumbId
                        if (prefsThumbs.getString("thid_$rId", null) != thumbId.toString()) {
                            if (editorThumbs == null) editorThumbs = prefsThumbs.edit()
                            editorThumbs?.putString("thid_$rId", thumbId.toString())
                        }
                    }

                    val nomeDoc = extrairNomeLimpoDaMensagem(nomeBruto, legenda)
                    val nomeLower = nomeDoc.lowercase(Locale.ROOT)
                    val chaveUnica = "$nomeLower|$tam"

                    if (index % 5 == 0 || index == totalNaNuvem - 1) {
                        NexusLeituraNuvem.avancar(
                            novoLido = index + 1,
                            novoTotal = totalNaNuvem.coerceAtLeast(1),
                            texto = "Restaurando SSD ($modoReal): $nomeDoc"
                        )
                    }

                    val msgIdKey = msg.id.toString()
                    if (msg.id != 0L && !prefsMsgs.getBoolean(msgIdKey, false)) {
                        if (editorMsgs == null) editorMsgs = prefsMsgs.edit()
                        editorMsgs?.putBoolean(msgIdKey, true)
                    }

                    if (ficheiroFoiDeletadoPeloUsuario(context, usuarioAtivo, nomeDoc, tam)) {
                        return@forEachIndexed
                    }

                    val pastaBrutaLegenda = if (legenda.contains("📁 [")) {
                        legenda.substringAfter("📁 [").substringBefore("]").trim()
                    } else ""

                    val veioDeMovimentoNuvem = mapaMovidosPorChave.containsKey(chaveUnica) ||
                        mapaMovidosPorNome.containsKey(nomeLower)

                    val pastaEscolhida = when {
                        mapaMovidosPorChave.containsKey(chaveUnica) -> mapaMovidosPorChave[chaveUnica] ?: ""
                        mapaMovidosPorNome.containsKey(nomeLower) -> mapaMovidosPorNome[nomeLower] ?: ""
                        else -> pastaBrutaLegenda
                    }

                    val pastaAposRenomeNuvem = if (pastaEscolhida.isNotBlank()) {
                        mapaRenomeacoesNuvem[pastaEscolhida.lowercase(Locale.ROOT)] ?: pastaEscolhida
                    } else ""

                    val pastaResolvida = resolverNomePastaAtualizado(context, usuarioAtivo, pastaAposRenomeNuvem)
                    val pastaResolvidaLower = pastaResolvida.lowercase(Locale.ROOT)
                    val pastaFinal = if (
                        pastaResolvida.isNotBlank() &&
                        !pastasApagadasNuvem.contains(pastaResolvidaLower) &&
                        !pastaFoiEliminadaPeloUsuario(context, usuarioAtivo, pastaResolvida)
                    ) {
                        pastaResolvida
                    } else ""

                    if (pastaFinal.isNotBlank()) {
                        pastasEncontradas.add(pastaFinal)
                    }

                    val kName = "name_$nomeLower"
                    val kKey = "key_${nomeLower}|$tam"
                    if (!prefsSynced.getBoolean(kName, false) || !prefsSynced.getBoolean(kKey, false)) {
                        if (editorSynced == null) editorSynced = prefsSynced.edit()
                        editorSynced?.putBoolean(kName, true)
                        editorSynced?.putBoolean(kKey, true)
                    }

                    val uriMiniaturaInicial = when {
                        caminhoLocalExistente.isNotBlank() && File(caminhoLocalExistente).exists() && File(caminhoLocalExistente).length() > 100L ->
                            prepararMiniaturaDeFicheiroBaixado(context, File(caminhoLocalExistente), nomeDoc)
                        thumbPathLocal.isNotBlank() && File(thumbPathLocal).exists() && File(thumbPathLocal).length() > 100L ->
                            salvarMiniaturaRapidaTDLib(context, File(thumbPathLocal), rId, nomeDoc)
                        else -> ""
                    }
                    val capaResolvida = resolverCaminhoCapaJpg(uriMiniaturaInicial)

                    val arqExistente = mapaExistentesPorChave[chaveUnica]
                    if (arqExistente != null) {
                        if (miniBytes != null) {
                            NexusPreviewTurbo.salvarMiniThumbMemoria(arqExistente.dataUpload, miniBytes)
                        }
                        if (veioDeMovimentoNuvem) {
                            if (pastaFinal.isBlank()) {
                                if (editorLinks == null) editorLinks = prefsLinks.edit()
                                editorLinks?.remove(arqExistente.dataUpload.toString())
                            } else if (pastasAtuais[arqExistente.dataUpload] != pastaFinal) {
                                if (editorLinks == null) editorLinks = prefsLinks.edit()
                                editorLinks?.putString(arqExistente.dataUpload.toString(), pastaFinal)
                            }
                        } else if (pastaFinal.isNotBlank() && pastasAtuais[arqExistente.dataUpload].isNullOrBlank()) {
                            if (editorLinks == null) editorLinks = prefsLinks.edit()
                            editorLinks?.putString(arqExistente.dataUpload.toString(), pastaFinal)
                        }
                        if (capaResolvida.isNotBlank()) {
                            loteMiniaturas[arqExistente.dataUpload] = capaResolvida
                            if (editorThumbs == null) editorThumbs = prefsThumbs.edit()
                            editorThumbs?.putString("ts_${arqExistente.dataUpload}", capaResolvida)
                        }
                        if (caminhoLocalExistente.isNotBlank() && File(caminhoLocalExistente).exists() && File(caminhoLocalExistente).length() > 100L) {
                            val uriComp = Uri.fromFile(File(caminhoLocalExistente)).toString()
                            loteCompletos[arqExistente.dataUpload] = uriComp
                            if (editorThumbs == null) editorThumbs = prefsThumbs.edit()
                            editorThumbs?.putString("full_${arqExistente.dataUpload}", uriComp)
                        }
                        if ((rId != 0 && arqExistente.fileId != rId) ||
                            (!miniaturaExisteLocalmente(arqExistente.caminhoMiniatura, nomeDoc) && capaResolvida.isNotBlank())
                        ) {
                            existentesParaAtualizar.add(
                                arqExistente.copy(
                                    fileId = if (rId != 0) rId else arqExistente.fileId,
                                    caminhoMiniatura = if (capaResolvida.isNotBlank()) capaResolvida else arqExistente.caminhoMiniatura
                                )
                            )
                        }
                    } else {
                        var ts = (msg.date.toLong() * 1000L) + kotlin.math.abs((msg.id ushr 20) % 997L)
                        while (!timestampsUsados.add(ts)) {
                            ts++
                        }

                        if (miniBytes != null) {
                            NexusPreviewTurbo.salvarMiniThumbMemoria(ts, miniBytes)
                        }
                        if (editorOwners == null) editorOwners = prefsOwners.edit()
                        editorOwners?.putString(ts.toString(), chaveAtiva)
                        if (pastaFinal.isNotBlank()) {
                            if (editorLinks == null) editorLinks = prefsLinks.edit()
                            editorLinks?.putString(ts.toString(), pastaFinal)
                        }
                        if (capaResolvida.isNotBlank()) {
                            loteMiniaturas[ts] = capaResolvida
                            if (editorThumbs == null) editorThumbs = prefsThumbs.edit()
                            editorThumbs?.putString("ts_$ts", capaResolvida)
                        }
                        if (caminhoLocalExistente.isNotBlank() && File(caminhoLocalExistente).exists() && File(caminhoLocalExistente).length() > 100L) {
                            val uriComp = Uri.fromFile(File(caminhoLocalExistente)).toString()
                            loteCompletos[ts] = uriComp
                            if (editorThumbs == null) editorThumbs = prefsThumbs.edit()
                            editorThumbs?.putString("full_$ts", uriComp)
                        }

                        val novoArq = ArquivoNuvem(
                            id = 0,
                            fileId = rId,
                            nomeArquivo = nomeDoc,
                            tamanhoBytes = tam,
                            dataUpload = ts,
                            caminhoMiniatura = capaResolvida.ifBlank { uriMiniaturaInicial }
                        )
                        mapaExistentesPorChave[chaveUnica] = novoArq
                        novosParaInserirRoom.add(novoArq)
                    }
                }

                val prefsFolders = context.getSharedPreferences("nexus_folder_owners", Context.MODE_PRIVATE)
                val conjuntoPastas = (prefsFolders.getStringSet("folders_$chaveAtiva", emptySet()) ?: emptySet())
                    .filterNot { pastasApagadasNuvem.contains(it.trim().lowercase(Locale.ROOT)) }
                    .toMutableSet()
                pastasEncontradas.forEach { p ->
                    val limpo = p.trim()
                    if (limpo.isNotBlank() && conjuntoPastas.none { it.equals(limpo, ignoreCase = true) }) {
                        conjuntoPastas.add(limpo)
                    }
                }
                prefsFolders.edit().putStringSet("folders_$chaveAtiva", conjuntoPastas).commit()

                if (pastasApagadasNuvem.isNotEmpty()) {
                    val prefsDelPastas = context.getSharedPreferences("nexus_deleted_folders_$chaveAtiva", Context.MODE_PRIVATE)
                    val edDel = prefsDelPastas.edit()
                    pastasApagadasNuvem.forEach { edDel.putBoolean(it, true) }
                    edDel.commit()
                }

                editorMsgs?.commit()
                editorSynced?.commit()
                editorOwners?.commit()
                editorLinks?.commit()
                editorThumbs?.commit()

                withContext(Dispatchers.Main) {
                    if (loteThumbIds.isNotEmpty()) NexusThumbCache.mapaThumbIdsNuvem.putAll(loteThumbIds)
                    if (loteMiniaturas.isNotEmpty()) NexusThumbCache.mapaMiniaturas.putAll(loteMiniaturas)
                    if (loteCompletos.isNotEmpty()) NexusThumbCache.mapaArquivosCompletos.putAll(loteCompletos)
                }

                val pastasExistentesRoom = dao.buscarPastas().first()
                    .map { it.nomePasta.trim().lowercase(Locale.ROOT) }
                    .toMutableSet()
                for (p in conjuntoPastas) {
                    val limpo = p.trim()
                    if (limpo.isNotBlank() && pastasExistentesRoom.add(limpo.lowercase(Locale.ROOT))) {
                        dao.criarPasta(PastaNuvem(nomePasta = limpo))
                    }
                }

                for (novo in novosParaInserirRoom) {
                    dao.salvarArquivo(novo)
                }
                for (upd in existentesParaAtualizar) {
                    vm.salvarArquivoSincrono(upd)
                }

                encerrarSync(novosParaInserirRoom.size)
            } catch (_: Throwable) {
                encerrarSync(0, fecharBarra = true)
            }
        }
    }

    obterChatCofreUsuario(context, client, usuarioAtivo) { chatObj, modoReal, _ ->
        if (chatObj != null) {
            try { client.send(TdApi.OpenChat(chatObj.id)) {} } catch (_: Exception) {}

            client.send(TdApi.GetChat(chatObj.id)) {
                if (modoReal == "CP") {
                    NexusLeituraNuvem.iniciar(1, "Lendo cofre CP de $usuarioAtivo...")
                    buscarHistoricoPaginado(
                        client = client,
                        chatId = chatObj.id,
                        fromMsgId = 0L,
                        lotesRestantes = 40,
                        acumulado = mutableListOf(),
                        onProgress = { totalLidas ->
                            NexusLeituraNuvem.titulo.value = "Lendo cofre CP ($totalLidas itens encontrados)..."
                        }
                    ) { msgsHistorico ->
                        processarMensagensEmLoteIO(msgsHistorico, "CP")
                    }
                } else {
                    NexusLeituraNuvem.iniciar(1, "Lendo cofre MS de $usuarioAtivo...")
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, tagPerfil) { msgsTag ->
                        buscarMensagensPorTermoNaNuvem(client, chatObj.id, "SYNC_PASTA") { msgsSyncPasta ->
                            buscarMensagensPorTermoNaNuvem(client, chatObj.id, "SYNC_MOVER") { msgsSyncMover ->
                                buscarHistoricoPaginado(
                                    client = client,
                                    chatId = chatObj.id,
                                    fromMsgId = 0L,
                                    lotesRestantes = 30,
                                    acumulado = mutableListOf(),
                                    onProgress = { totalLidas ->
                                        NexusLeituraNuvem.titulo.value = "Lendo cofre MS ($totalLidas itens verificados)..."
                                    }
                                ) { msgsHistorico ->
                                    val unificadas = (msgsTag + msgsSyncPasta + msgsSyncMover + msgsHistorico).distinctBy { it.id }
                                    processarMensagensEmLoteIO(unificadas, "MS")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            encerrarSync(0, fecharBarra = true)
        }
    }
}

// === CARREGAMENTO TURBO MODO UNLIM: 8 CAPAS LEVES + 5 FOTOS SEM THUMB + CANCELAMENTO TDLIB ===
suspend fun carregarMiniaturaIndividualDaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arq: ArquivoNuvem,
    vm: UIViewModel,
    atualizarSubBarra: Boolean = false,
    prioridadeRede: Int = 31,
    apenasCapaLeve: Boolean = false
): String = withContext(Dispatchers.IO) {
    val jaTem = NexusThumbCache.obterMiniatura(arq)
    if (jaTem.isNotBlank()) {
        val resolvidoJaTem = resolverCaminhoCapaJpg(jaTem)
        if (resolvidoJaTem.startsWith("content://") && !ehArquivoVideo(arq.nomeArquivo)) {
            return@withContext resolvidoJaTem
        }
        val limpoJaTem = if (resolvidoJaTem.startsWith("file://")) Uri.parse(resolvidoJaTem).path ?: resolvidoJaTem.removePrefix("file://") else resolvidoJaTem
        if (limpoJaTem.isNotBlank() && File(limpoJaTem).exists() && File(limpoJaTem).length() > 256L) {
            NexusPreviewTurbo.registrarCaminhoValido(resolvidoJaTem)
            return@withContext resolvidoJaTem
        } else {
            NexusPreviewTurbo.invalidarCaminho(jaTem)
        }
    }

    val completoLocal = NexusThumbCache.obterArquivoCompleto(arq)
    if (completoLocal.isNotBlank()) {
        val limpoComp = if (completoLocal.startsWith("file://")) Uri.parse(completoLocal).path ?: completoLocal.removePrefix("file://") else completoLocal
        val fComp = File(limpoComp)
        if (fComp.exists() && fComp.length() > 256L) {
            val geradaLocal = prepararMiniaturaDeFicheiroBaixado(context, fComp, arq.nomeArquivo)
            if (geradaLocal.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    vm.atualizarCaminhoMiniatura(context, arq, geradaLocal)
                }
                return@withContext geradaLocal
            }
        }
    }

    var fIdAtual = arq.fileId
    var thumbIdLeve = NexusThumbCache.mapaThumbIdsNuvem[fIdAtual] ?: 0

    if ((fIdAtual == 0 || (thumbIdLeve == 0 && ehArquivoVideo(arq.nomeArquivo))) &&
        usuarioAtivo.isNotBlank() &&
        !apenasCapaLeve &&
        NexusPreviewTurbo.jaTentouRecuperarId.add(arq.dataUpload)
    ) {
        val (recFileId, recThumbId) = recuperarFileIdNaNuvemPorNome(context, client, usuarioAtivo, arq.nomeArquivo, arq.tamanhoBytes)
        if (recFileId != 0) {
            fIdAtual = recFileId
            if (recThumbId != 0) {
                thumbIdLeve = recThumbId
                NexusThumbCache.salvarThumbIdNuvem(context, recFileId, recThumbId)
            }
        }
    }

    var miniaturaPronta = ""

    // 1. Via expressa: 8 downloads simultâneos só para capas leves (~15-30 KB)
    if (thumbIdLeve != 0) {
        val arqThumb = NexusPreviewTurbo.semaforoCapasLeves.withPermit {
            NexusPreviewTurbo.registrarDownloadAtivo(arq.dataUpload, thumbIdLeve, ehCapaLeve = true)
            try {
                baixarArquivoTDLibAssincrono(
                    client = client,
                    fileId = thumbIdLeve,
                    prioridade = prioridadeRede,
                    maxTentativas = 70,
                    controlarPausaTransfer = false
                ) { pct, _, _ ->
                    if (atualizarSubBarra) {
                        NexusLeituraNuvem.atualizarSubProgresso(pct, "Capa rápida SSD: ${arq.nomeArquivo}")
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                NexusPreviewTurbo.cancelarDownloadItem(client, arq.dataUpload)
                throw ce
            } finally {
                NexusPreviewTurbo.limparDownloadAtivo(arq.dataUpload)
            }
        }
        if (arqThumb != null && arqThumb.exists() && arqThumb.length() > 100L) {
            miniaturaPronta = salvarMiniaturaRapidaTDLib(context, arqThumb, fIdAtual, arq.nomeArquivo)
        }
    }

    // 2. Fallback separado: 5 paralelos para fotos/PDFs sem thumbId; 2 para vídeos
    val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val ehVideo = ehArquivoVideo(arq.nomeArquivo)
    val podeBaixarParaCapa = ehArquivoImagem(arq.nomeArquivo) || ehVideo || ext == "PDF"
    val limiteBytesCapa = when {
        apenasCapaLeve -> 0L
        ehVideo -> 25L * 1024L * 1024L
        else -> 60L * 1024L * 1024L
    }

    if (miniaturaPronta.isBlank() && fIdAtual != 0 && podeBaixarParaCapa && limiteBytesCapa > 0L && arq.tamanhoBytes <= limiteBytesCapa) {
        val semaforoAlvo = if (ehVideo) NexusPreviewTurbo.semaforoFallbackVideos else NexusPreviewTurbo.semaforoFallbackFotos
        val arqFull = semaforoAlvo.withPermit {
            NexusPreviewTurbo.registrarDownloadAtivo(arq.dataUpload, fIdAtual, ehCapaLeve = false)
            try {
                baixarArquivoTDLibAssincrono(
                    client = client,
                    fileId = fIdAtual,
                    prioridade = if (ehVideo) (prioridadeRede - 10).coerceAtLeast(10) else (prioridadeRede - 3).coerceAtLeast(18),
                    maxTentativas = if (ehVideo) 350 else 180,
                    controlarPausaTransfer = false
                ) { pct, _, _ ->
                    if (atualizarSubBarra) {
                        NexusLeituraNuvem.atualizarSubProgresso(pct, "Gerando capa nítida: ${arq.nomeArquivo} ($pct%)")
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                NexusPreviewTurbo.cancelarDownloadItem(client, arq.dataUpload)
                throw ce
            } finally {
                NexusPreviewTurbo.limparDownloadAtivo(arq.dataUpload)
            }
        }
        if (arqFull != null && arqFull.exists() && arqFull.length() > 100L) {
            NexusThumbCache.salvarArquivoCompleto(context, arq.dataUpload, Uri.fromFile(arqFull).toString())
            miniaturaPronta = prepararMiniaturaDeFicheiroBaixado(context, arqFull, arq.nomeArquivo)
        }
    }

    if (miniaturaPronta.isNotBlank()) {
        NexusPreviewTurbo.registrarCaminhoValido(miniaturaPronta)
        withContext(Dispatchers.Main) {
            vm.atualizarCaminhoMiniatura(context, arq, miniaturaPronta)
        }
    }
    return@withContext miniaturaPronta
}

// === ABERTURA COM PRIORIDADE TOTAL DE BANDA (PAUSA A FILA DE CAPAS AUTOMATICAMENTE AO ABRIR) ===
fun garantirFicheiroLocal(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arq: ArquivoNuvem,
    vm: UIViewModel,
    onReady: (ArquivoNuvem) -> Unit
) {
    val completoCache = NexusThumbCache.obterArquivoCompleto(arq)
    if (completoCache.isNotBlank() && arquivoCompletoExisteLocalmente(completoCache)) {
        onReady(arq.copy(caminhoMiniatura = completoCache))
        return
    }

    val thumbAtual = NexusThumbCache.obterMiniatura(arq)
    if (!ehArquivoVideo(arq.nomeArquivo) && thumbAtual.isNotBlank()) {
        val limpo = if (thumbAtual.startsWith("file://")) Uri.parse(thumbAtual).path ?: thumbAtual.removePrefix("file://") else thumbAtual
        val fOrig = File(limpo.removeSuffix("_frame.jpg"))
        if (fOrig.exists() && fOrig.length() > 256L) {
            onReady(arq.copy(caminhoMiniatura = Uri.fromFile(fOrig).toString()))
            return
        }
    }

    val leituraEstavaAtiva = NexusLeituraNuvem.ativo.value && !NexusLeituraNuvem.pausado.value
    if (leituraEstavaAtiva) {
        NexusLeituraNuvem.pausado.value = true
    }

    NexusTransfer.jobAtivo?.cancel()
    NexusTransfer.jobAtivo = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        try {
            var targetFileId = arq.fileId
            NexusTransfer.iniciar(
                texto = "Baixando '${arq.nomeArquivo}' (Prioridade máxima)...",
                upload = false,
                fileId = targetFileId,
                context = context
            )

            var arquivoBaixado = withContext(Dispatchers.IO) {
                if (targetFileId != 0) {
                    baixarArquivoTDLibAssincrono(
                        client = client,
                        fileId = targetFileId,
                        prioridade = 32,
                        maxTentativas = 800,
                        controlarPausaTransfer = true
                    ) { pct, baixadoBytes, totalBytes ->
                        if (!NexusTransfer.cancelado.value) {
                            val mbBaixado = String.format(Locale.US, "%.1f", baixadoBytes / (1024.0 * 1024.0))
                            val mbTotal = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                            val txt = if (totalBytes > 0) {
                                "Baixando '${arq.nomeArquivo}': $mbBaixado / $mbTotal MB ($pct%)"
                            } else {
                                "Baixando '${arq.nomeArquivo}' ($pct%)..."
                            }
                            NexusTransfer.atualizarProgresso(pct, txt)
                        }
                    }
                } else null
            }

            if (NexusTransfer.cancelado.value) {
                NexusTransfer.encerrar()
                Toast.makeText(context, "Download de '${arq.nomeArquivo}' cancelado.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (arquivoBaixado == null && usuarioAtivo.isNotBlank() && !NexusTransfer.cancelado.value) {
                NexusTransfer.label.value = "Localizando '${arq.nomeArquivo}' na nuvem SSD..."
                val (novoId, novoThumbId) = withContext(Dispatchers.IO) {
                    recuperarFileIdNaNuvemPorNome(context, client, usuarioAtivo, arq.nomeArquivo, arq.tamanhoBytes)
                }
                if (novoId != 0 && !NexusTransfer.cancelado.value) {
                    targetFileId = novoId
                    NexusTransfer.fileIdAtivo.value = novoId
                    if (novoThumbId != 0) {
                        NexusThumbCache.salvarThumbIdNuvem(context, novoId, novoThumbId)
                    }
                    arquivoBaixado = withContext(Dispatchers.IO) {
                        baixarArquivoTDLibAssincrono(
                            client = client,
                            fileId = novoId,
                            prioridade = 32,
                            maxTentativas = 800,
                            controlarPausaTransfer = true
                        ) { pct, baixadoBytes, totalBytes ->
                            if (!NexusTransfer.cancelado.value) {
                                val mbBaixado = String.format(Locale.US, "%.1f", baixadoBytes / (1024.0 * 1024.0))
                                val mbTotal = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                                val txt = if (totalBytes > 0) {
                                    "Baixando '${arq.nomeArquivo}': $mbBaixado / $mbTotal MB ($pct%)"
                                } else {
                                    "Baixando '${arq.nomeArquivo}' ($pct%)..."
                                }
                                NexusTransfer.atualizarProgresso(pct, txt)
                            }
                        }
                    }
                }
            }

            val foiCancelado = NexusTransfer.cancelado.value
            NexusTransfer.encerrar()
            if (foiCancelado) {
                Toast.makeText(context, "Download cancelado.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (arquivoBaixado != null && arquivoBaixado.exists() && arquivoBaixado.length() > 100L) {
                val capaJpg = withContext(Dispatchers.IO) {
                    prepararMiniaturaDeFicheiroBaixado(context, arquivoBaixado, arq.nomeArquivo)
                }
                val uriRealCompleto = Uri.fromFile(arquivoBaixado).toString()
                NexusThumbCache.salvarArquivoCompleto(context, arq.dataUpload, uriRealCompleto)
                if (capaJpg.isNotBlank()) {
                    vm.atualizarCaminhoMiniatura(context, arq, capaJpg)
                }
                onReady(
                    arq.copy(
                        fileId = if (targetFileId != 0) targetFileId else arq.fileId,
                        caminhoMiniatura = uriRealCompleto
                    )
                )
            } else {
                Toast.makeText(context, "Não foi possível carregar '${arq.nomeArquivo}' da nuvem agora.", Toast.LENGTH_SHORT).show()
            }
        } finally {
            if (leituraEstavaAtiva && NexusLeituraNuvem.ativo.value) {
                NexusLeituraNuvem.pausado.value = false
            }
        }
    }
}

// === DETETOR DE NOVOS FICHEIROS NAS PASTAS SELECIONADAS ===
data class PastaSync(val uri: String, val nome: String, var ativa: Boolean)

data class NovoArquivoSync(
    val uri: Uri,
    val nome: String,
    val tamanho: Long,
    val nomePastaOrigem: String
)

fun carregarPastasSync(context: Context, usuario: String): List<PastaSync> {
    val sufixo = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_sync_prefs_$sufixo", Context.MODE_PRIVATE)
    val lista = mutableListOf<PastaSync>()
    prefs.all.forEach { (uriStr, ativo) ->
        if (ativo is Boolean) {
            val decodedPath = Uri.decode(uriStr)
            val nome = decodedPath.substringAfterLast(":", "Pasta").substringAfterLast("/")
            lista.add(PastaSync(uriStr, nome.ifBlank { "Pasta Selecionada" }, ativo))
        }
    }
    return lista.distinctBy { it.uri }
}

fun salvarPastaSync(context: Context, usuario: String, uri: String, ativa: Boolean) {
    val sufixo = normalizarUsuario(usuario)
    context.getSharedPreferences("nexus_sync_prefs_$sufixo", Context.MODE_PRIVATE).edit().putBoolean(uri, ativa).apply()
}

fun removerPastaSync(context: Context, usuario: String, uri: String) {
    val sufixo = normalizarUsuario(usuario)
    context.getSharedPreferences("nexus_sync_prefs_$sufixo", Context.MODE_PRIVATE).edit().remove(uri).apply()
}

fun procurarNovosFicheirosNasPastas(
    context: Context,
    usuarioAtivo: String,
    arquivosNoCofre: List<ArquivoNuvem>
): List<NovoArquivoSync> {
    if (usuarioAtivo.isBlank()) return emptyList()
    val pastasAtivas = carregarPastasSync(context, usuarioAtivo).filter { it.ativa }
    if (pastasAtivas.isEmpty()) return emptyList()

    val chavesNoCofre = arquivosNoCofre.map {
        "${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}|${it.tamanhoBytes}"
    }.toSet()
    val nomesNoCofre = arquivosNoCofre.map {
        limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)
    }.toSet()

    val encontrados = mutableListOf<NovoArquivoSync>()
    val chavesJaAdicionadas = mutableSetOf<String>()

    for (pasta in pastasAtivas) {
        try {
            val treeUri = Uri.parse(pasta.uri)
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idxId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val idxName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idxSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val idxMime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = if (idxId != -1 && !cursor.isNull(idxId)) cursor.getString(idxId) else continue
                    val nomeBruto = if (idxName != -1 && !cursor.isNull(idxName)) cursor.getString(idxName) else continue
                    val tamanho = if (idxSize != -1 && !cursor.isNull(idxSize)) cursor.getLong(idxSize) else 0L
                    val mime = if (idxMime != -1 && !cursor.isNull(idxMime)) cursor.getString(idxMime) else ""

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val nome = limparPrefixoTimestamp(nomeBruto)
                    if (nome.startsWith(".") || tamanho <= 0L) continue

                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val uriStr = fileUri.toString()
                    val nomeLower = nome.lowercase(Locale.ROOT)
                    val chave = "$nomeLower|$tamanho"

                    val jaSincronizado = chavesNoCofre.contains(chave) ||
                        nomesNoCofre.contains(nomeLower) ||
                        chavesJaAdicionadas.contains(chave) ||
                        ficheiroJaSincronizadoAntes(context, usuarioAtivo, nome, tamanho, uriStr) ||
                        ficheiroFoiDeletadoPeloUsuario(context, usuarioAtivo, nome, tamanho)

                    if (!jaSincronizado) {
                        chavesJaAdicionadas.add(chave)
                        encontrados.add(
                            NovoArquivoSync(
                                uri = fileUri,
                                nome = nome,
                                tamanho = tamanho,
                                nomePastaOrigem = pasta.nome
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
    return encontrados
}

fun formatarDataCabecalhoGaleria(timestamp: Long): String {
    if (timestamp <= 0L) return "Sem data"
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestamp
    val dia = cal.get(Calendar.DAY_OF_MONTH)
    val ano = cal.get(Calendar.YEAR)
    val meses = arrayOf("jan.", "fev.", "mar.", "abr.", "mai.", "jun.", "jul.", "ago.", "set.", "out.", "nov.", "dez.")
    val mes = meses[cal.get(Calendar.MONTH).coerceIn(0, 11)]
    return "$dia de $mes de $ano"
}

fun formatarDataNuvem(timestamp: Long): String {
    if (timestamp <= 0L) return "Data desconhecida"
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}

fun formatarTempoVideo(ms: Int): String {
    val totalSegundos = (ms / 1000).coerceAtLeast(0)
    val minutos = totalSegundos / 60
    val segundos = totalSegundos % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutos, segundos)
}

fun carregarVinculosPastas(context: Context): Map<Long, String> {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    val mapa = mutableMapOf<Long, String>()
    for ((k, v) in prefs.all) {
        val chave = k.toLongOrNull()
        if (chave != null && v is String && v.isNotBlank()) mapa[chave] = v.trim()
    }
    return mapa
}

fun salvarVinculoPasta(context: Context, dataUpload: Long, nomePasta: String) {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    val chave = dataUpload.toString()
    if (nomePasta.isBlank()) {
        if (!prefs.contains(chave)) return
        prefs.edit().remove(chave).apply()
    } else {
        val limpo = nomePasta.trim()
        if (prefs.getString(chave, null) == limpo) return
        prefs.edit().putString(chave, limpo).apply()
    }
}

fun obterDetalhesFicheiro(context: Context, uri: Uri): Pair<String, Long> {
    var nome = "Arquivo_${System.currentTimeMillis()}"
    var tamanho = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iT = c.getColumnIndex(OpenableColumns.SIZE)
            if (iN != -1 && !c.isNull(iN)) nome = c.getString(iN)
            if (iT != -1 && !c.isNull(iT)) tamanho = c.getLong(iT)
        }
    }
    return Pair(limparPrefixoTimestamp(nome), tamanho)
}

fun descobrirMimeType(nome: String): String {
    val limpo = nome.trim()
    return when (limpo.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "mp4", "mov", "mkv", "avi", "webm", "3gp" -> "video/mp4"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt", "csv", "log", "json", "xml", "md" -> "text/plain"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "apk" -> "application/vnd.android.package-archive"
        else -> if (ehArquivoImagem(limpo)) "image/jpeg" else if (ehArquivoVideo(limpo)) "video/mp4" else "*/*"
    }
}

fun gerarCapaPdf(arquivoPdf: File): File? {
    return try {
        val frameFile = File("${arquivoPdf.absolutePath}_frame.jpg")
        if (frameFile.exists() && frameFile.length() > 256L) return frameFile
        ParcelFileDescriptor.open(arquivoPdf, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount > 0) {
                    renderer.openPage(0).use { page ->
                        val largura = (page.width * 2).coerceIn(300, 800)
                        val altura = (page.height * 2).coerceIn(400, 1100)
                        val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(frameFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        }
                        bmp.recycle()
                        return frameFile
                    }
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

fun renderizarPaginasPdf(caminhoMiniatura: String?): List<Bitmap> {
    val lista = mutableListOf<Bitmap>()
    try {
        val caminho = caminhoMiniatura ?: return emptyList()
        val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
        val arq = File(limpo)
        if (!arq.exists()) return emptyList()

        ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val total = renderer.pageCount.coerceAtMost(20)
                for (i in 0 until total) {
                    renderer.openPage(i).use { page ->
                        val largura = (page.width * 2).coerceIn(400, 1400)
                        val altura = (page.height * 2).coerceIn(500, 1800)
                        val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        lista.add(bmp)
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return lista
}

fun lerTextoDocumento(caminhoMiniatura: String?): String? {
    return try {
        val caminho = caminhoMiniatura ?: return null
        val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
        val arq = File(limpo)
        if (!arq.exists()) return null
        arq.bufferedReader().use { it.readText().take(40000) }
    } catch (_: Exception) {
        null
    }
}

fun salvarMiniaturaPrivada(context: Context, uri: Uri, nomeArquivo: String, arquivoCopiadoLocal: File? = null): String {
    return try {
        val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
        val nomeSeguro = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val tamRef = arquivoCopiadoLocal?.length() ?: System.currentTimeMillis()
        val f = File(dir, "thumb_${tamRef}_$nomeSeguro")
        val frameFile = File("${f.absolutePath}_frame.jpg")

        if (ehArquivoVideo(nomeSeguro)) {
            val gerou = extrairCapaVideoParaArquivo(context, arquivoCopiadoLocal, uri, frameFile)
            return if (gerou && frameFile.exists() && frameFile.length() > 256L) {
                Uri.fromFile(frameFile).toString()
            } else ""
        }

        if (arquivoCopiadoLocal != null && arquivoCopiadoLocal.exists()) {
            FileInputStream(arquivoCopiadoLocal).use { inp ->
                FileOutputStream(f).use { out -> inp.copyTo(out) }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(f).use { out -> inp.copyTo(out) }
            }
        }

        if (ehArquivoImagem(nomeSeguro)) {
            val ok = gerarMiniaturaImagemLeve(f, frameFile)
            return if (ok && frameFile.exists()) Uri.fromFile(frameFile).toString() else Uri.fromFile(f).toString()
        } else if (descobrirMimeType(nomeSeguro) == "application/pdf") {
            val capa = gerarCapaPdf(f)
            return if (capa != null && capa.exists()) Uri.fromFile(capa).toString() else ""
        }
        Uri.fromFile(f).toString()
    } catch (_: Exception) {
        ""
    }
}

fun obterUriCapaMidia(caminhoMiniatura: String?): Uri {
    val resolvido = resolverCaminhoCapaJpg(caminhoMiniatura)
    if (resolvido.isNotBlank()) return Uri.parse(resolvido)
    return Uri.parse(caminhoMiniatura ?: "")
}

fun temCapaPdfLocal(caminhoMiniatura: String?): Boolean {
    return resolverCaminhoCapaJpg(caminhoMiniatura).isNotBlank()
}

suspend fun copiarUriParaCacheTDLib(
    context: Context,
    uri: Uri,
    nomeArquivo: String,
    tamanhoEsperado: Long = 0L,
    loteTag: String = ""
): File? = withContext(Dispatchers.IO) {
    try {
        val subDir = File(context.filesDir, "tdlib_upload_cache/${System.currentTimeMillis()}").apply { if (!exists()) mkdirs() }
        val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val f = File(subDir, nomeLimpo)

        context.contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(f).use { out ->
                val buffer = ByteArray(64 * 1024)
                var lidosTotal = 0L
                var ultimoPct = -1
                while (true) {
                    if (NexusTransfer.cancelado.value) {
                        out.close()
                        f.delete()
                        return@withContext null
                    }
                    while (NexusTransfer.pausado.value) {
                        delay(200)
                        if (NexusTransfer.cancelado.value) {
                            out.close()
                            f.delete()
                            return@withContext null
                        }
                    }
                    val read = inp.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    lidosTotal += read
                    if (tamanhoEsperado > 0L && !NexusTransfer.cancelado.value) {
                        val pctCopia = ((lidosTotal * 35L) / tamanhoEsperado).toInt().coerceIn(5, 35)
                        if (pctCopia != ultimoPct) {
                            ultimoPct = pctCopia
                            NexusTransfer.atualizarProgresso(pctCopia, "Preparando envio $loteTag'$nomeLimpo' ($pctCopia%)...")
                        }
                    }
                }
            }
        }
        if (NexusTransfer.cancelado.value) {
            f.delete()
            return@withContext null
        }
        if (f.exists() && f.length() > 0L) f else null
    } catch (_: Exception) {
        null
    }
}

fun prepararUriPublica(context: Context, caminhoMiniatura: String?, nomeArquivo: String): Pair<Uri, String>? {
    val caminho = caminhoMiniatura ?: ""
    if (caminho.isBlank()) return null

    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    val mime = descobrirMimeType(nomeArquivo)
    if (caminho.startsWith("content://")) {
        val u = Uri.parse(caminho)
        return Pair(u, context.contentResolver.getType(u) ?: mime)
    }

    val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
    val arq = File(limpo)
    if (!arq.exists()) return null

    val col = when {
        mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }
    val vals = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Nexus_${System.currentTimeMillis()}_$nomeArquivo")
        put(MediaStore.MediaColumns.MIME_TYPE, if (mime == "*/*") "application/octet-stream" else mime)
    }
    val uriMedia = try {
        context.contentResolver.insert(col, vals)?.also { d ->
            context.contentResolver.openOutputStream(d)?.use { o -> arq.inputStream().use { it.copyTo(o) } }
        }
    } catch (_: Exception) { null }

    return Pair(uriMedia ?: Uri.fromFile(arq), mime)
}

fun abrirFicheiroExterno(context: Context, caminhoMiniatura: String?, nomeArquivo: String) {
    try {
        val preparado = prepararUriPublica(context, caminhoMiniatura, nomeArquivo)
        if (preparado == null) {
            Toast.makeText(context, "Ficheiro local indisponível. Use 'Baixar da Nuvem' primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, mime) = preparado
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (mime == "*/*") "application/octet-stream" else mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(viewIntent, "Abrir $nomeArquivo com..."))
    } catch (e: Exception) {
        Toast.makeText(context, "Nenhuma app encontrada para abrir este formato.", Toast.LENGTH_SHORT).show()
    }
}

fun compartilharFicheiroNativo(context: Context, caminhoMiniatura: String?, nomeArquivo: String) {
    try {
        val preparado = prepararUriPublica(context, caminhoMiniatura, nomeArquivo)
        if (preparado == null) {
            Toast.makeText(context, "Ficheiro local indisponível. Use 'Baixar da Nuvem'.", Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, mime) = preparado
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar $nomeArquivo"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao partilhar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun compartilharMultiplosFicheirosNativo(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    listaArquivos: List<ArquivoNuvem>,
    vm: UIViewModel
) {
    if (listaArquivos.isEmpty()) return
    if (listaArquivos.size == 1) {
        val unico = listaArquivos.first()
        garantirFicheiroLocal(context, client, usuarioAtivo, unico, vm) { pronto ->
            compartilharFicheiroNativo(context, pronto.caminhoMiniatura, pronto.nomeArquivo)
        }
        return
    }

    val urisPublicas = ArrayList<Uri>()
    var restantes = listaArquivos.size

    listaArquivos.forEach { arq ->
        garantirFicheiroLocal(context, client, usuarioAtivo, arq, vm) { pronto ->
            val prep = prepararUriPublica(context, pronto.caminhoMiniatura, pronto.nomeArquivo)
            if (prep != null) {
                synchronized(urisPublicas) {
                    urisPublicas.add(prep.first)
                }
            }
            restantes--
            if (restantes == 0) {
                if (urisPublicas.isEmpty()) {
                    Toast.makeText(context, "Não foi possível preparar os ficheiros para partilha.", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, urisPublicas)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar ${urisPublicas.size} ficheiro(s)"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Erro ao partilhar lote: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

fun copiarLoteParaDownloads(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    listaArquivos: List<ArquivoNuvem>,
    vm: UIViewModel,
    onComplete: (String) -> Unit
) {
    if (listaArquivos.isEmpty()) return
    var restantes = listaArquivos.size
    var copiados = 0

    listaArquivos.forEach { arq ->
        garantirFicheiroLocal(context, client, usuarioAtivo, arq, vm) { pronto ->
            try {
                val caminho = pronto.caminhoMiniatura ?: ""
                val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destino = File(downloadsDir, "Nexus_${System.currentTimeMillis()}_${limparPrefixoTimestamp(pronto.nomeArquivo)}")

                if (caminho.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(caminho))?.use { inp ->
                        FileOutputStream(destino).use { out -> inp.copyTo(out) }
                    }
                    copiados++
                } else if (limpo.isNotBlank() && File(limpo).exists()) {
                    FileInputStream(File(limpo)).use { inp ->
                        FileOutputStream(destino).use { out -> inp.copyTo(out) }
                    }
                    copiados++
                }
            } catch (_: Exception) {}

            restantes--
            if (restantes == 0) {
                onComplete("$copiados de ${listaArquivos.size} ficheiro(s) copiados para a pasta Downloads!")
            }
        }
    }
}

// === ACELERAÇÃO DE CAPAS: CRIA INPUTTHUMBNAIL LEVE (<= 320px, <= 180KB) PARA ANEXAR NO UPLOAD ===
fun criarInputThumbnailLeve(context: Context, caminhoCapaJpg: String): TdApi.InputThumbnail? {
    if (caminhoCapaJpg.isBlank()) return null
    return try {
        val limpo = if (caminhoCapaJpg.startsWith("file://")) Uri.parse(caminhoCapaJpg).path ?: caminhoCapaJpg.removePrefix("file://") else caminhoCapaJpg
        val origem = File(limpo)
        if (!origem.exists() || origem.length() <= 256L) return null

        val bmpOrig = BitmapFactory.decodeFile(origem.absolutePath) ?: return null
        val maxLado = 320
        val wOrig = bmpOrig.width
        val hOrig = bmpOrig.height
        if (wOrig <= 0 || hOrig <= 0) {
            bmpOrig.recycle()
            return null
        }

        val escala = minOf(maxLado.toFloat() / wOrig.toFloat(), maxLado.toFloat() / hOrig.toFloat(), 1f)
        val wFinal = (wOrig * escala).toInt().coerceIn(32, maxLado)
        val hFinal = (hOrig * escala).toInt().coerceIn(32, maxLado)
        val bmpScaled = if (escala < 0.99f) Bitmap.createScaledBitmap(bmpOrig, wFinal, hFinal, true) else bmpOrig

        val dir = File(context.filesDir, "tdlib_upload_cache/thumbs").apply { if (!exists()) mkdirs() }
        val thumbFile = File(dir, "tdthumb_${System.currentTimeMillis()}_${wFinal}x${hFinal}.jpg")
        FileOutputStream(thumbFile).use { out ->
            bmpScaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
        }
        if (bmpScaled !== bmpOrig) bmpScaled.recycle()
        bmpOrig.recycle()

        if (!thumbFile.exists() || thumbFile.length() <= 128L || thumbFile.length() > 195_000L) {
            thumbFile.delete()
            return null
        }

        val inputThumb = TdApi.InputThumbnail()
        val thumbInputFile = TdApi.InputFileLocal(thumbFile.absolutePath)
        for (f in TdApi.InputThumbnail::class.java.declaredFields) {
            if (JavaModifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            when {
                f.type.isAssignableFrom(thumbInputFile.javaClass) || f.name == "thumbnail" -> f.set(inputThumb, thumbInputFile)
                f.name == "width" -> f.setInt(inputThumb, wFinal)
                f.name == "height" -> f.setInt(inputThumb, hFinal)
            }
        }
        inputThumb
    } catch (_: Throwable) {
        null
    }
}

fun crc32Tl(esquema: String): Int {
    val crc = CRC32()
    crc.update(esquema.toByteArray(Charsets.UTF_8))
    return crc.value.toInt()
}

fun inspecionarAssinaturasNativasSo(context: Context): String {
    return try {
        val soFile = File(context.applicationInfo.nativeLibraryDir, "libtdjni.so")
        if (!soFile.exists()) return "so_nao_encontrado"
        val bytes = soFile.readBytes()
        val ascii = String(bytes, Charsets.ISO_8859_1)

        fun extrairVizinhanca(termo: String): String {
            val idx = ascii.indexOf(termo)
            if (idx == -1) return "$termo:ausente"
            val ini = (idx - 120).coerceAtLeast(0)
            val fim = (idx + 260).coerceAtMost(ascii.length)
            val trecho = ascii.substring(ini, fim)
            val limpo = Regex("[A-Za-z0-9_\\$/.;]{3,}").findAll(trecho).map { it.value }.toList()
            return "$termo->${limpo.joinToString(",")}"
        }

        val camposSendJava = TdApi.SendMessage::class.java.declaredFields
            .filter { !JavaModifier.isStatic(it.modifiers) }
            .joinToString(",") { "${it.name}:${it.type.simpleName}" }

        val camposDocJava = TdApi.InputMessageDocument::class.java.declaredFields
            .filter { !JavaModifier.isStatic(it.modifiers) }
            .joinToString(",") { "${it.name}:${it.type.simpleName}" }

        "JavaSend[$camposSendJava] | JavaDoc[$camposDocJava] | " +
            extrairVizinhanca("TdApi\$SendMessage") + " | " +
            extrairVizinhanca("TdApi\$InputMessageDocument")
    } catch (e: Throwable) {
        "ErroScanSo:${e.message}"
    }
}

fun criarInstanciaWrapperMidia(wrapperClass: Class<*>, inputFile: TdApi.InputFile, nomeLimpo: String): Any? {
    for (ctor in wrapperClass.constructors) {
        val params = ctor.parameterTypes
        if (params.isNotEmpty() && params[0].isAssignableFrom(inputFile.javaClass)) {
            val args = arrayOfNulls<Any>(params.size)
            args[0] = inputFile
            var ok = true
            for (i in 1 until params.size) {
                val pt = params[i]
                when {
                    TdApi.InputThumbnail::class.java.isAssignableFrom(pt) -> args[i] = null
                    pt == String::class.java -> args[i] = nomeLimpo
                    pt == Boolean::class.javaPrimitiveType || pt == Boolean::class.java -> args[i] = false
                    pt == Int::class.javaPrimitiveType || pt == Int::class.java -> args[i] = 0
                    pt == Long::class.javaPrimitiveType || pt == Long::class.java -> args[i] = 0L
                    pt == IntArray::class.java -> args[i] = IntArray(0)
                    else -> {
                        ok = false
                        break
                    }
                }
            }
            if (ok) {
                try { return ctor.newInstance(*args) } catch (_: Exception) {}
            }
        }
    }
    return try {
        val inst = wrapperClass.getDeclaredConstructor().newInstance()
        for (df in wrapperClass.declaredFields) {
            if (JavaModifier.isStatic(df.modifiers)) continue
            df.isAccessible = true
            when {
                df.type.isAssignableFrom(inputFile.javaClass) -> df.set(inst, inputFile)
                df.type == String::class.java -> df.set(inst, if (df.name.contains("name", true) || df.name.contains("title", true)) nomeLimpo else "")
                df.type == IntArray::class.java -> df.set(inst, IntArray(0))
            }
        }
        inst
    } catch (_: Exception) {
        null
    }
}

// === CONSTRUTOR DINÂMICO COM ANEXO AUTOMÁTICO DE INPUTTHUMBNAIL (ACELERAÇÃO DE CAPAS) ===
fun construirConteudoUploadTDLib(
    inputFile: TdApi.InputFile,
    nomeLimpo: String,
    legenda: TdApi.FormattedText,
    inputThumb: TdApi.InputThumbnail? = null,
    modoTentativa: Int = 0
): Pair<TdApi.InputMessageContent, String> {
    val idsClassicosDoc = intArrayOf(
        TdApi.InputMessageDocument.CONSTRUCTOR,
        crc32Tl("inputMessageDocument document:InputFile thumbnail:InputThumbnail disable_content_type_detection:Bool caption:FormattedText = InputMessageContent"),
        crc32Tl("inputMessageDocument document:InputFile thumbnail:InputThumbnail disable_content_type_detection:Bool caption:FormattedText show_caption_above_media:Bool = InputMessageContent"),
        crc32Tl("inputMessageDocument document:InputFile thumbnail:InputThumbnail caption:FormattedText = InputMessageContent"),
        742562196,
        -1497438798
    )

    val idEscolhido = idsClassicosDoc[modoTentativa.coerceIn(0, idsClassicosDoc.lastIndex)]
    val thumbParaEnviar = if (modoTentativa < 3) inputThumb else null

    val doc = if (modoTentativa == 0) {
        TdApi.InputMessageDocument()
    } else {
        object : TdApi.InputMessageDocument() {
            override fun getConstructor(): Int = idEscolhido
        }
    }

    for (f in TdApi.InputMessageDocument::class.java.declaredFields) {
        if (JavaModifier.isStatic(f.modifiers)) continue
        f.isAccessible = true
        try {
            when {
                f.type.isAssignableFrom(legenda.javaClass) -> f.set(doc, legenda)
                thumbParaEnviar != null && (TdApi.InputThumbnail::class.java.isAssignableFrom(f.type) || f.name == "thumbnail") -> {
                    f.set(doc, thumbParaEnviar)
                }
                f.type.isAssignableFrom(inputFile.javaClass) -> f.set(doc, inputFile)
                f.name == "document" -> {
                    val inst = criarInstanciaWrapperMidia(f.type, inputFile, nomeLimpo)
                    f.set(doc, inst)
                }
            }
        } catch (_: Exception) {}
    }

    return Pair(doc, "DocCtor($idEscolhido)")
}

// === ENVIO HÍBRIDO PARA MS OU CP COM CAPA ULTRALEVE ANEXADA (INPUTTHUMBNAIL) ===
fun enviarParaMensagensSalvas(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    caminhoLocal: String,
    caminhoCapaJpg: String,
    nomeOriginal: String,
    pastaDestino: String,
    onResult: (Boolean, Int, Long, Long, String) -> Unit
) {
    if (NexusTransfer.cancelado.value) {
        onResult(false, 0, 0L, 0L, "Envio cancelado.")
        return
    }
    val arqLocalFile = File(caminhoLocal)
    if (!arqLocalFile.exists() || arqLocalFile.length() <= 0L) {
        onResult(false, 0, 0L, 0L, "Arquivo temporário vazio ou não encontrado.")
        return
    }

    val nomeLimpo = limparPrefixoTimestamp(nomeOriginal)
    val inputThumbLeve = criarInputThumbnailLeve(context, caminhoCapaJpg)

    obterChatCofreUsuario(context, client, usuarioAtivo) { chatObj, modoCofre, erroChat ->
        if (NexusTransfer.cancelado.value) {
            onResult(false, 0, 0L, 0L, "Envio cancelado.")
            return@obterChatCofreUsuario
        }
        if (chatObj != null) {
            NexusTransfer.chatIdUploadAtivo = chatObj.id
            try { client.send(TdApi.OpenChat(chatObj.id)) {} } catch (_: Exception) {}

            val userTag = "👤 [$usuarioAtivo] • "
            val pastaTag = if (pastaDestino.isNotBlank()) "📁 [$pastaDestino] • " else ""
            val textoCompletoLegenda = "☁️ Nexus Cloud • $userTag$pastaTag$nomeLimpo"
            val leg = TdApi.FormattedText(textoCompletoLegenda, emptyArray())
            val fileLocal = TdApi.InputFileLocal(caminhoLocal)

            fun tentarEnvioSequencial(etapa: Int, historicoDiag: String) {
                val (conteudo, diagAtual) = construirConteudoUploadTDLib(
                    inputFile = fileLocal,
                    nomeLimpo = nomeLimpo,
                    legenda = leg,
                    inputThumb = inputThumbLeve,
                    modoTentativa = etapa
                )

                val req = TdApi.SendMessage().apply {
                    chatId = chatObj.id
                    inputMessageContent = conteudo
                }

                client.send(req) { msgObj ->
                    when (msgObj) {
                        is TdApi.Message -> {
                            var rId = 0
                            var tId = 0
                            when (val c = msgObj.content) {
                                is TdApi.MessageDocument -> {
                                    rId = c.document?.document?.id ?: 0
                                    tId = c.document?.thumbnail?.file?.id ?: 0
                                }
                                is TdApi.MessageVideo -> {
                                    rId = c.video?.video?.id ?: 0
                                    tId = c.video?.thumbnail?.file?.id ?: 0
                                }
                                is TdApi.MessagePhoto -> {
                                    val maior = c.photo?.sizes?.maxByOrNull { it.photo?.size ?: 0 }
                                    rId = maior?.photo?.id ?: 0
                                }
                                is TdApi.MessageAudio -> {
                                    rId = c.audio?.audio?.id ?: 0
                                    tId = c.audio?.albumCoverThumbnail?.file?.id ?: 0
                                }
                            }
                            NexusTransfer.msgIdUploadAtivo = msgObj.id
                            if (rId != 0) NexusTransfer.fileIdAtivo.value = rId

                            registarMensagemSincronizada(context, usuarioAtivo, msgObj.id)
                            if (rId != 0 && tId != 0) {
                                NexusThumbCache.salvarThumbIdNuvem(context, rId, tId)
                            }
                            val txt = if (pastaDestino.isNotBlank()) {
                                "Guardado na pasta '$pastaDestino' ($modoCofre)!"
                            } else {
                                "Guardado no cofre $modoCofre de $usuarioAtivo!"
                            }
                            onResult(true, rId, chatObj.id, msgObj.id, txt)
                        }
                        is TdApi.Error -> {
                            val novoHist = if (historicoDiag.isBlank()) diagAtual else "$historicoDiag -> $diagAtual"
                            if (etapa < 5) {
                                tentarEnvioSequencial(etapa + 1, novoHist)
                            } else {
                                val infoSo = inspecionarAssinaturasNativasSo(context)
                                onResult(
                                    false,
                                    0,
                                    0L,
                                    0L,
                                    "Erro SSD (${msgObj.code}): ${msgObj.message}\n[$novoHist]\n\n$infoSo"
                                )
                            }
                        }
                        else -> onResult(true, 0, chatObj.id, 0L, "Upload SSD ($modoCofre) iniciado!")
                    }
                }
            }

            tentarEnvioSequencial(0, "")
        } else {
            onResult(false, 0, 0L, 0L, erroChat ?: "Erro ao localizar o chat do cofre.")
        }
    }
}

fun baixarFicheiroDaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arq: ArquivoNuvem,
    vm: UIViewModel,
    onResult: (String) -> Unit
) {
    garantirFicheiroLocal(context, client, usuarioAtivo, arq, vm) { pronto ->
        try {
            val caminho = pronto.caminhoMiniatura ?: ""
            val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
            if (limpo.isNotBlank() && File(limpo).exists()) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destino = File(downloadsDir, "Nexus_${System.currentTimeMillis()}_${limparPrefixoTimestamp(arq.nomeArquivo)}")
                FileInputStream(File(limpo)).use { input ->
                    FileOutputStream(destino).use { output -> input.copyTo(output) }
                }
                onResult("Salvo em Downloads: ${destino.name}")
            } else {
                onResult("Não foi possível gravar o ficheiro localmente.")
            }
        } catch (e: Exception) {
            onResult("Erro ao gravar ficheiro: ${e.localizedMessage}")
        }
    }
}

fun formatarTamanhoNuvem(bytes: Long?): String {
    val total = bytes ?: 0L
    if (total <= 0L) return "0 MB"
    val mb = total.toDouble() / (1024.0 * 1024.0)
    return if (mb < 1024.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

// === GRELHA DE ARQUIVOS MODO UNLIM (CONTROLADOR ÚNICO, ZERO SPINNERS, ZERO CROSSFADE, 3/4 COL E LISTA) ===
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GrelhaArquivos(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    vm: UIViewModel,
    lista: List<ArquivoNuvem>,
    mapaPastas: Map<Long, String>,
    modoSelecao: Boolean,
    idsSelecionados: Set<Int>,
    modoVisualizacao: Int = 3,
    onFileClick: (ArquivoNuvem) -> Unit,
    onFileLongClick: (ArquivoNuvem) -> Unit
) {
    val colunas = when (modoVisualizacao) {
        1 -> 1
        4 -> 4
        else -> 3
    }
    val ehLista = colunas == 1
    val ehQuatroColunas = colunas == 4
    val tamanhoDecode = if (ehQuatroColunas) 144 else 200

    val gruposPorData = remember(lista) {
        lista
            .distinctBy { "${it.dataUpload}_${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.dataUpload }
            .groupBy { formatarDataCabecalhoGaleria(it.dataUpload) }
    }

    val indicesParaData = remember(gruposPorData) {
        val mapa = ArrayList<String>()
        gruposPorData.forEach { (dataCabecalho, arquivosDoDia) ->
            mapa.add(dataCabecalho)
            repeat(arquivosDoDia.size) { mapa.add(dataCabecalho) }
        }
        mapa
    }
    val totalItensGrid = indicesParaData.size

    val indicesParaArquivo = remember(gruposPorData) {
        val mapa = ArrayList<ArquivoNuvem?>(totalItensGrid)
        gruposPorData.forEach { (_, arquivosDoDia) ->
            mapa.add(null)
            arquivosDoDia.forEach { mapa.add(it) }
        }
        mapa
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scopeGrid = rememberCoroutineScope()
    var isDraggingScrollbar by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var retryThumbTick by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            NexusPreviewTurbo.usuarioRolando = false
            NexusPreviewTurbo.cancelarDownloadsPreviewAtivos(client)
        }
    }

    // CONTROLADOR ÚNICO DA TELA: zero coroutines por quadrado durante o arrasto rápido
    LaunchedEffect(totalItensGrid, usuarioAtivo, indicesParaArquivo) {
        if (totalItensGrid == 0 || usuarioAtivo.isBlank()) return@LaunchedEffect
        var jobTelaAtual: kotlinx.coroutines.Job? = null
        androidx.compose.runtime.snapshotFlow {
            val emMovimento = gridState.isScrollInProgress || isDraggingScrollbar
            val primeiro = gridState.firstVisibleItemIndex
            val qtdVis = gridState.layoutInfo.visibleItemsInfo.size
            "$emMovimento|$primeiro|$qtdVis|$retryThumbTick"
        }.collect { estadoStr ->
            val emMovimento = estadoStr.startsWith("true|")
            NexusPreviewTurbo.usuarioRolando = emMovimento
            jobTelaAtual?.cancel()

            if (emMovimento) {
                NexusPreviewTurbo.cancelarDownloadsPreviewAtivos(client)
                return@collect
            }

            jobTelaAtual = launch {
                val visiveis = gridState.layoutInfo.visibleItemsInfo
                    .mapNotNull { indicesParaArquivo.getOrNull(it.index) }
                    .distinctBy { it.dataUpload }

                if (visiveis.isEmpty()) return@launch

                val idsVisiveis = visiveis.map { it.dataUpload }.toSet()
                NexusPreviewTurbo.cancelarForaDosVisiveis(client, idsVisiveis)

                val pendentesVisiveis = visiveis.filter { arq ->
                    val thumbAtual = NexusThumbCache.mapaMiniaturas[arq.dataUpload] ?: arq.caminhoMiniatura ?: ""
                    !miniaturaExisteLocalmente(thumbAtual, arq.nomeArquivo)
                }

                // 1. Baixa em 0 ms apenas os itens visíveis na tela parada
                val jobsVisiveis = pendentesVisiveis.map { arq ->
                    launch(Dispatchers.IO) {
                        if (NexusThumbCache.emProgresso.add(arq.dataUpload)) {
                            try {
                                carregarMiniaturaIndividualDaNuvem(
                                    context = context,
                                    client = client,
                                    usuarioAtivo = usuarioAtivo,
                                    arq = arq,
                                    vm = vm,
                                    atualizarSubBarra = false,
                                    prioridadeRede = 31,
                                    apenasCapaLeve = false
                                )
                            } finally {
                                NexusPreviewTurbo.limparDownloadAtivo(arq.dataUpload)
                                NexusThumbCache.emProgresso.remove(arq.dataUpload)
                            }
                        }
                    }
                }
                jobsVisiveis.forEach { it.join() }

                // 2. Só depois que a tela atual ficou pronta, pré-carrega as próximas 12 capas leves abaixo
                val ultimoVisivel = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val inicioPref = (ultimoVisivel + 1).coerceAtMost(indicesParaArquivo.size)
                val fimPref = (ultimoVisivel + 20).coerceAtMost(indicesParaArquivo.size)
                val candidatosPref = mutableListOf<ArquivoNuvem>()
                for (i in inicioPref until fimPref) {
                    val arq = indicesParaArquivo.getOrNull(i) ?: continue
                    if ((NexusThumbCache.mapaThumbIdsNuvem[arq.fileId] ?: 0) == 0) continue
                    val thumbAtual = NexusThumbCache.mapaMiniaturas[arq.dataUpload] ?: arq.caminhoMiniatura ?: ""
                    if (!miniaturaExisteLocalmente(thumbAtual, arq.nomeArquivo) &&
                        !NexusThumbCache.emProgresso.contains(arq.dataUpload)
                    ) {
                        candidatosPref.add(arq)
                        if (candidatosPref.size >= 12) break
                    }
                }
                candidatosPref.forEach { arqPref ->
                    launch(Dispatchers.IO) {
                        if (NexusThumbCache.emProgresso.add(arqPref.dataUpload)) {
                            try {
                                carregarMiniaturaIndividualDaNuvem(
                                    context = context,
                                    client = client,
                                    usuarioAtivo = usuarioAtivo,
                                    arq = arqPref,
                                    vm = vm,
                                    atualizarSubBarra = false,
                                    prioridadeRede = 18,
                                    apenasCapaLeve = true
                                )
                            } finally {
                                NexusPreviewTurbo.limparDownloadAtivo(arqPref.dataUpload)
                                NexusThumbCache.emProgresso.remove(arqPref.dataUpload)
                            }
                        }
                    }
                }
            }
        }
    }

    val thumbFraction by remember(totalItensGrid) {
        derivedStateOf {
            if (isDraggingScrollbar) {
                dragFraction
            } else {
                val visibleCount = gridState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                val maxFirst = (totalItensGrid - visibleCount).coerceAtLeast(1)
                (gridState.firstVisibleItemIndex.toFloat() / maxFirst.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    val barAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isDraggingScrollbar || gridState.isScrollInProgress) 1f else 0.58f,
        label = "scrollbarAlpha"
    )

    val dataAtualArrasto = remember(indicesParaData, thumbFraction) {
        if (indicesParaData.isEmpty()) ""
        else {
            val idx = (thumbFraction * (indicesParaData.size - 1)).toInt()
                .coerceIn(0, indicesParaData.lastIndex)
            indicesParaData[idx]
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val thumbHeightPx = with(density) { 54.dp.toPx() }
        val trackHeightPx = constraints.maxHeight.toFloat()
        val usableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)

        LazyVerticalGrid(
            columns = GridCells.Fixed(colunas),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (ehLista) 8.dp else 1.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(if (ehLista) 0.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(if (ehLista) 6.dp else 2.dp)
        ) {
            gruposPorData.forEach { (dataCabecalho, arquivosDoDia) ->

                item(
                    key = "header_$dataCabecalho",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "cabecalho_data"
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgBlack)
                            .padding(start = 10.dp, end = 28.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = dataCabecalho,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${arquivosDoDia.size}",
                            color = Gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                itemsIndexed(
                    items = arquivosDoDia,
                    key = { idx, arq -> "item_${arq.id}_${arq.dataUpload}_${arq.nomeArquivo}_$idx" },
                    contentType = { _, _ -> if (ehLista) "item_lista" else "item_midia" }
                ) { _, arq ->
                    val ext = remember(arq.nomeArquivo) { arq.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT) }
                    val pasta = mapaPastas[arq.dataUpload]
                    val thumbSafe = NexusThumbCache.mapaMiniaturas[arq.dataUpload] ?: arq.caminhoMiniatura ?: ""
                    val estaSelecionado = idsSelecionados.contains(arq.id)

                    val isImage = remember(arq.nomeArquivo) { ehArquivoImagem(arq.nomeArquivo) }
                    val isVideo = remember(arq.nomeArquivo) { ehArquivoVideo(arq.nomeArquivo) }

                    val temArquivoMiniatura = remember(thumbSafe, arq.nomeArquivo, retryThumbTick) {
                        miniaturaExisteLocalmente(thumbSafe, arq.nomeArquivo)
                    }

                    val miniModeloInstantaneo = remember(arq.dataUpload, temArquivoMiniatura) {
                        if (!temArquivoMiniatura) NexusPreviewTurbo.obterModeloMiniThumb(context, arq.dataUpload) else null
                    }

                    val thumbModelRequest = remember(thumbSafe, temArquivoMiniatura, tamanhoDecode) {
                        if (temArquivoMiniatura && thumbSafe.isNotBlank()) {
                            val targetUri = obterUriCapaMidia(thumbSafe)
                            ImageRequest.Builder(context)
                                .data(targetUri)
                                .size(tamanhoDecode, tamanhoDecode)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .allowHardware(true)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .crossfade(false)
                                .build()
                        } else null
                    }

                    val miniModelRequest = remember(miniModeloInstantaneo) {
                        if (miniModeloInstantaneo != null) {
                            ImageRequest.Builder(context)
                                .data(miniModeloInstantaneo)
                                .size(96, 96)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .crossfade(false)
                                .build()
                        } else null
                    }

                    if (ehLista) {
                        // === MODO LISTA ULTRA-LEVE ===
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = if (totalItensGrid > 15) 22.dp else 0.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (estaSelecionado) Gold.copy(alpha = 0.14f) else SurfaceDark)
                                .border(
                                    width = if (estaSelecionado) 1.5.dp else 0.8.dp,
                                    color = if (estaSelecionado) Gold else SurfaceDarker,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .combinedClickable(
                                    onClick = { onFileClick(arq) },
                                    onLongClick = { onFileLongClick(arq) }
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceDarker),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    isImage -> {
                                        val imgModel = thumbModelRequest ?: miniModelRequest
                                        if (imgModel != null) {
                                            AsyncImage(
                                                model = imgModel,
                                                contentDescription = arq.nomeArquivo,
                                                contentScale = ContentScale.Crop,
                                                onError = {
                                                    if (thumbModelRequest != null) {
                                                        NexusPreviewTurbo.invalidarCaminho(thumbSafe)
                                                        retryThumbTick++
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(Icons.Default.Image, null, tint = TextGrey.copy(alpha = 0.25f), modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    isVideo -> {
                                        val vidModel = thumbModelRequest ?: miniModelRequest
                                        if (vidModel != null) {
                                            AsyncImage(
                                                model = vidModel,
                                                contentDescription = arq.nomeArquivo,
                                                contentScale = ContentScale.Crop,
                                                onError = {
                                                    if (thumbModelRequest != null) {
                                                        NexusPreviewTurbo.invalidarCaminho(thumbSafe)
                                                        retryThumbTick++
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(BgBlack.copy(alpha = 0.65f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.PlayArrow, null, tint = Gold, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    ext == "PDF" -> {
                                        val possuiCapa = remember(thumbSafe, retryThumbTick) { temCapaPdfLocal(thumbSafe) }
                                        if (possuiCapa && thumbModelRequest != null) {
                                            AsyncImage(
                                                model = thumbModelRequest,
                                                contentDescription = arq.nomeArquivo,
                                                contentScale = ContentScale.Crop,
                                                onError = {
                                                    NexusPreviewTurbo.invalidarCaminho(thumbSafe)
                                                    retryThumbTick++
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(28.dp))
                                        }
                                    }
                                    ext in listOf("ZIP", "RAR", "APK") -> {
                                        Icon(Icons.Default.FolderZip, null, tint = Gold, modifier = Modifier.size(28.dp))
                                    }
                                    else -> {
                                        Icon(Icons.Default.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(26.dp))
                                    }
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = arq.nomeArquivo,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${formatarTamanhoNuvem(arq.tamanhoBytes)} • ${formatarDataNuvem(arq.dataUpload)}",
                                    color = TextGrey,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!pasta.isNullOrBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(Gold.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(pasta, color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            if (modoSelecao) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(if (estaSelecionado) Gold else BgBlack.copy(alpha = 0.7f), CircleShape)
                                        .border(1.5.dp, if (estaSelecionado) BgBlack else Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (estaSelecionado) {
                                        Icon(Icons.Default.Check, null, tint = BgBlack, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else if (ext.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .background(SurfaceDarker, RoundedCornerShape(6.dp))
                                        .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = ext.take(4),
                                        color = if (ext == "PDF") DangerRed else Gold,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    } else {
                        // === GRADE 3 OU 4 COLUNAS ESTILO UNLIM (ZERO SPINNERS, ZERO ANIMAÇÕES PESADAS) ===
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .background(SurfaceDark)
                                .border(
                                    width = if (estaSelecionado) 3.dp else 0.dp,
                                    color = if (estaSelecionado) Gold else Color.Transparent
                                )
                                .combinedClickable(
                                    onClick = { onFileClick(arq) },
                                    onLongClick = { onFileLongClick(arq) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isImage -> {
                                    val imgModel = thumbModelRequest ?: miniModelRequest
                                    if (imgModel != null) {
                                        AsyncImage(
                                            model = imgModel,
                                            contentDescription = arq.nomeArquivo,
                                            contentScale = ContentScale.Crop,
                                            onError = {
                                                if (thumbModelRequest != null) {
                                                    NexusPreviewTurbo.invalidarCaminho(thumbSafe)
                                                    retryThumbTick++
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                isVideo -> {
                                    val vidModel = thumbModelRequest ?: miniModelRequest
                                    if (vidModel != null) {
                                        AsyncImage(
                                            model = vidModel,
                                            contentDescription = "Video Preview",
                                            contentScale = ContentScale.Crop,
                                            onError = {
                                                if (thumbModelRequest != null) {
                                                    NexusPreviewTurbo.invalidarCaminho(thumbSafe)
                                                    retryThumbTick++
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(if (ehQuatroColunas) 28.dp else 36.dp)
                                            .align(Alignment.Center)
                                            .background(BgBlack.copy(alpha = 0.45f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(if (ehQuatroColunas) 17.dp else 22.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(3.dp)
                                            .background(BgBlack.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("VÍDEO", color = Gold, fontSize = if (ehQuatroColunas) 6.sp else 7.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                ext == "PDF" -> {
                                    val possuiCapa = remember(thumbSafe, retryThumbTick) { temCapaPdfLocal(thumbSafe) }
                                    if (possuiCapa && thumbModelRequest != null) {
                                        AsyncImage(
                                            model = thumbModelRequest,
                                            contentDescription = arq.nomeArquivo,
                                            contentScale = ContentScale.Crop,
                                            onError = {
                                                NexusPreviewTurbo.invalidarCaminho(thumbSafe)
                                                retryThumbTick++
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .background(DangerRed, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text("PDF", color = Color.White, fontSize = if (ehQuatroColunas) 7.sp else 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                            Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(if (ehQuatroColunas) 24.dp else 32.dp))
                                            Text(arq.nomeArquivo, color = TextGrey, fontSize = if (ehQuatroColunas) 8.sp else 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                ext in listOf("ZIP", "RAR", "APK") -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                    Icon(Icons.Default.FolderZip, null, tint = Gold, modifier = Modifier.size(if (ehQuatroColunas) 24.dp else 32.dp))
                                    Text(arq.nomeArquivo, color = TextGrey, fontSize = if (ehQuatroColunas) 8.sp else 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                    Icon(Icons.Default.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(if (ehQuatroColunas) 22.dp else 28.dp))
                                    Text(arq.nomeArquivo, color = TextGrey, fontSize = if (ehQuatroColunas) 8.sp else 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            if (estaSelecionado) {
                                Box(Modifier.fillMaxSize().background(Gold.copy(alpha = 0.22f)))
                            }

                            if (modoSelecao) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(if (ehQuatroColunas) 4.dp else 6.dp)
                                        .size(if (ehQuatroColunas) 20.dp else 24.dp)
                                        .background(if (estaSelecionado) Gold else BgBlack.copy(alpha = 0.7f), CircleShape)
                                        .border(1.5.dp, if (estaSelecionado) BgBlack else Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (estaSelecionado) {
                                        Icon(Icons.Default.Check, null, tint = BgBlack, modifier = Modifier.size(if (ehQuatroColunas) 13.dp else 16.dp))
                                    }
                                }
                            }

                            if (!pasta.isNullOrBlank()) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .padding(3.dp)
                                        .background(BgBlack.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(pasta, color = Gold, fontSize = if (ehQuatroColunas) 7.sp else 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        // === BARRA LATERAL DE VELOCIDADE ===
        if (totalItensGrid > 15) {
            val thumbOffsetY = (thumbFraction * usableTrackPx).toInt()

            if (isDraggingScrollbar && dataAtualArrasto.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 42.dp)
                        .offset { androidx.compose.ui.unit.IntOffset(0, (thumbOffsetY + 10.dp.roundToPx()).coerceAtLeast(0)) }
                        .background(SurfaceDarker.copy(alpha = 0.95f), RoundedCornerShape(10.dp))
                        .border(1.dp, Gold, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = dataAtualArrasto,
                        color = Gold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(34.dp)
                    .fillMaxHeight()
                    .pointerInput(totalItensGrid, usableTrackPx) {
                        try {
                            awaitPointerEventScope {
                                var lastTarget = -1
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        if (change.pressed) {
                                            change.consume()
                                            isDraggingScrollbar = true
                                            val frac = ((change.position.y - thumbHeightPx / 2f) / usableTrackPx).coerceIn(0f, 1f)
                                            dragFraction = frac
                                            val target = (frac * (totalItensGrid - 1)).toInt().coerceIn(0, totalItensGrid - 1)
                                            if (target != lastTarget) {
                                                lastTarget = target
                                                scopeGrid.launch { gridState.scrollToItem(target) }
                                            }
                                        } else {
                                            lastTarget = -1
                                            isDraggingScrollbar = false
                                        }
                                    }
                                }
                            }
                        } finally {
                            isDraggingScrollbar = false
                        }
                    }
            ) {
                if (isDraggingScrollbar) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(Gold.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 2.dp)
                        .offset { androidx.compose.ui.unit.IntOffset(0, thumbOffsetY) }
                        .graphicsLayer { alpha = barAlpha }
                        .width(if (isDraggingScrollbar) 24.dp else 18.dp)
                        .height(54.dp)
                        .background(
                            if (isDraggingScrollbar) Gold else SurfaceDarker.copy(alpha = 0.92f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(1.5.dp, Gold, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Topo",
                            tint = if (isDraggingScrollbar) BgBlack else Gold,
                            modifier = Modifier.size(13.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Fim",
                            tint = if (isDraggingScrollbar) BgBlack else Gold,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
// === FIM DA PARTE 2 DE 3 ===
// === CONTINUAÇÃO: PARTE 3 DE 4 (COLE LOGO ABAIXO DA PARTE 2 - v2.2) ===
private data class ResultadoEnvioSSD(
    val ok: Boolean,
    val fileId: Int,
    val chatId: Long,
    val messageId: Long,
    val mensagem: String
)

private fun gerarNomeUnicoNoLote(
    nomeBase: String,
    nomesJaUsados: MutableSet<String>
): String {
    val limpo = limparPrefixoTimestamp(nomeBase).ifBlank { "Video_${System.currentTimeMillis()}.mp4" }
    val chaveInicial = limpo.lowercase(Locale.ROOT)
    if (nomesJaUsados.add(chaveInicial)) return limpo

    val temExt = limpo.contains('.')
    val prefixo = if (temExt) limpo.substringBeforeLast('.') else limpo
    val ext = if (temExt) ".${limpo.substringAfterLast('.')}" else ""

    var contador = 2
    while (true) {
        val candidato = "${prefixo}_$contador$ext"
        if (nomesJaUsados.add(candidato.lowercase(Locale.ROOT))) {
            return candidato
        }
        contador++
    }
}

// === PREFERÊNCIAS: OCR AUTOMÁTICO NO UPLOAD E MODO DA GALERIA (3 COL, 4 COL OU LISTA) ===
fun obterOcrAutoNoUpload(context: Context): Boolean {
    return context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
        .getBoolean("ocr_auto_upload_enabled", true)
}

fun salvarOcrAutoNoUpload(context: Context, ativo: Boolean) {
    context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("ocr_auto_upload_enabled", ativo)
        .apply()
}

fun obterModoVisualizacaoGaleria(context: Context): Int {
    val salvo = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
        .getInt("gallery_layout_mode", 3)
    return if (salvo in listOf(1, 3, 4)) salvo else 3
}

fun salvarModoVisualizacaoGaleria(context: Context, modo: Int) {
    val valido = if (modo in listOf(1, 3, 4)) modo else 3
    context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
        .edit()
        .putInt("gallery_layout_mode", valido)
        .apply()
}

// === ANEL CIRCULAR ANIMADO DE PROGRESSO SSD (PREENCHE DE 0% A 100%) ===
@Composable
fun AnelProgressoCircularSSD(
    progressoFracao: Float,
    pausado: Boolean,
    tamanho: androidx.compose.ui.unit.Dp,
    espessura: androidx.compose.ui.unit.Dp,
    textoCentro: String = "",
    mostrarIconePausaSePausado: Boolean = true
) {
    val progressoAnimado by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progressoFracao.coerceIn(0.02f, 1f),
        label = "anelProgressoAnimado"
    )
    val corArco = if (pausado) DangerRed else Gold

    Box(
        modifier = Modifier.size(tamanho),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = espessura.toPx()
            val inset = strokePx / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                width = (size.width - strokePx).coerceAtLeast(1f),
                height = (size.height - strokePx).coerceAtLeast(1f)
            )
            val topLeft = Offset(inset, inset)

            drawArc(
                color = SurfaceDark,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx)
            )
            drawArc(
                color = corArco,
                startAngle = -90f,
                sweepAngle = 360f * progressoAnimado,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokePx,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }

        if (pausado && mostrarIconePausaSePausado) {
            Icon(
                imageVector = Icons.Default.Pause,
                contentDescription = "Pausado",
                tint = DangerRed,
                modifier = Modifier.size(tamanho * 0.46f)
            )
        } else if (textoCentro.isNotBlank()) {
            Text(
                text = textoCentro,
                color = if (pausado) DangerRed else Gold,
                fontSize = if (tamanho >= 42.dp) 10.sp else 9.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }
    }
}

// === BOTÃO ISOLADO NO TOPO: ANEL CIRCULAR CLICÁVEL (EXPANDE CONTROLES) OU CADEADO ===
@Composable
fun BotaoTopoProgressoOuCadeado(
    expandido: Boolean,
    onAlternarExpandido: () -> Unit,
    onTrancar: () -> Unit
) {
    val transferProgress by NexusTransfer.progress.collectAsState()
    val transferPaused by NexusTransfer.pausado.collectAsState()
    val isUploading by NexusTransfer.enviandoEmSegundoPlano.collectAsState()

    val leituraAtiva by NexusLeituraNuvem.ativo.collectAsState()
    val leituraLidos by NexusLeituraNuvem.lidos.collectAsState()
    val leituraTotal by NexusLeituraNuvem.total.collectAsState()
    val leituraSubPct by NexusLeituraNuvem.subProgressoItem.collectAsState()
    val leituraPausada by NexusLeituraNuvem.pausado.collectAsState()

    val emTransferencia = transferProgress != null || isUploading
    val emLeitura = !emTransferencia && leituraAtiva && leituraTotal > 0

    when {
        emTransferencia -> {
            val pctAtual = (transferProgress ?: 10).coerceIn(2, 100)
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (expandido) Gold.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onAlternarExpandido() },
                contentAlignment = Alignment.Center
            ) {
                AnelProgressoCircularSSD(
                    progressoFracao = pctAtual / 100f,
                    pausado = transferPaused,
                    tamanho = 34.dp,
                    espessura = 3.2.dp,
                    textoCentro = "$pctAtual%",
                    mostrarIconePausaSePausado = true
                )
            }
        }
        emLeitura -> {
            val lidosSafe = leituraLidos.coerceIn(0, leituraTotal)
            val fracao = ((lidosSafe.toFloat() + (leituraSubPct.toFloat() / 100f)) / leituraTotal.toFloat()).coerceIn(0.02f, 1f)
            val pctLeitura = (fracao * 100).toInt().coerceIn(1, 100)
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (expandido) Gold.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onAlternarExpandido() },
                contentAlignment = Alignment.Center
            ) {
                AnelProgressoCircularSSD(
                    progressoFracao = fracao,
                    pausado = leituraPausada,
                    tamanho = 34.dp,
                    espessura = 3.2.dp,
                    textoCentro = "$pctLeitura%",
                    mostrarIconePausaSePausado = true
                )
            }
        }
        else -> {
            IconButton(onClick = onTrancar) {
                Icon(Icons.Default.Lock, contentDescription = "Trancar Cofre", tint = Gold)
            }
        }
    }
}

// === PAINEL EXPANSÍVEL E ISOLADO DE CONTROLE (ABRE AO TOCAR NO ANEL CIRCULAR DO TOPO) ===
@Composable
fun BarraProgressoIsoladaSSD(
    context: Context,
    client: Client,
    vaultStorageMode: String,
    expandido: Boolean,
    onRecolher: () -> Unit,
    onCancelarLeitura: () -> Unit,
    onTransferCancelada: () -> Unit
) {
    val transferProgress by NexusTransfer.progress.collectAsState()
    val transferLabel by NexusTransfer.label.collectAsState()
    val transferPaused by NexusTransfer.pausado.collectAsState()
    val transferIsUpload by NexusTransfer.ehUpload.collectAsState()
    val isUploading by NexusTransfer.enviandoEmSegundoPlano.collectAsState()

    val leituraAtiva by NexusLeituraNuvem.ativo.collectAsState()
    val leituraLidos by NexusLeituraNuvem.lidos.collectAsState()
    val leituraTotal by NexusLeituraNuvem.total.collectAsState()
    val leituraSubPct by NexusLeituraNuvem.subProgressoItem.collectAsState()
    val leituraTitulo by NexusLeituraNuvem.titulo.collectAsState()
    val leituraPausada by NexusLeituraNuvem.pausado.collectAsState()

    val emTransferencia = transferProgress != null || isUploading
    val emLeitura = !emTransferencia && leituraAtiva && leituraTotal > 0

    LaunchedEffect(emTransferencia, emLeitura) {
        if (!emTransferencia && !emLeitura && expandido) {
            onRecolher()
        }
    }

    if (!expandido) return

    if (emTransferencia) {
        val pctAtual = (transferProgress ?: 10).coerceIn(2, 100)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .background(SurfaceDarker, RoundedCornerShape(12.dp))
                .border(1.dp, if (transferPaused) DangerRed else Gold, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnelProgressoCircularSSD(
                progressoFracao = pctAtual / 100f,
                pausado = transferPaused,
                tamanho = 44.dp,
                espessura = 4.dp,
                textoCentro = "$pctAtual%",
                mostrarIconePausaSePausado = false
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                Text(
                    text = when {
                        transferPaused && transferIsUpload -> "⏸ ENVIO PAUSADO [$vaultStorageMode]"
                        transferPaused && !transferIsUpload -> "⏸ DOWNLOAD PAUSADO [$vaultStorageMode]"
                        transferIsUpload -> "⬆️ ENVIANDO [$vaultStorageMode]"
                        else -> "⬇️ BAIXANDO [$vaultStorageMode]"
                    },
                    color = if (transferPaused) DangerRed else Gold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = transferLabel.ifBlank { "Processando transferência..." },
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Button(
                    onClick = { NexusTransfer.alternarPausa(client) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (transferPaused) Gold else SurfaceDark
                    ),
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = if (transferPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (transferPaused) "Continuar" else "Pausar",
                        tint = if (transferPaused) BgBlack else Gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (transferPaused) "IR" else "PAUSA",
                        color = if (transferPaused) BgBlack else Gold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                IconButton(
                    onClick = {
                        NexusTransfer.cancelar(client)
                        onTransferCancelada()
                        onRecolher()
                        Toast.makeText(context, "Transferência cancelada.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(30.dp)
                        .background(DangerRed.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancelar transferência",
                        tint = DangerRed,
                        modifier = Modifier.size(15.dp)
                    )
                }

                IconButton(
                    onClick = onRecolher,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Recolher",
                        tint = TextGrey,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    } else if (emLeitura) {
        val lidosSafe = leituraLidos.coerceIn(0, leituraTotal)
        val fracaoLeitura = ((lidosSafe.toFloat() + (leituraSubPct.toFloat() / 100f)) / leituraTotal.toFloat()).coerceIn(0.02f, 1f)
        val pctLeitura = (fracaoLeitura * 100).toInt().coerceIn(1, 100)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .background(SurfaceDarker, RoundedCornerShape(12.dp))
                .border(1.dp, Gold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnelProgressoCircularSSD(
                progressoFracao = fracaoLeitura,
                pausado = leituraPausada,
                tamanho = 42.dp,
                espessura = 3.8.dp,
                textoCentro = "$pctLeitura%",
                mostrarIconePausaSePausado = false
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                Text(
                    text = if (leituraPausada) "⏸ PAUSADO • ${(lidosSafe + 1).coerceAtMost(leituraTotal)}/$leituraTotal"
                    else "☁️ SINCRONIZANDO • ${(lidosSafe + 1).coerceAtMost(leituraTotal)}/$leituraTotal",
                    color = Gold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = leituraTitulo.ifBlank { "Lendo arquivos da nuvem SSD..." },
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { NexusLeituraNuvem.alternarPausa() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (leituraPausada) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (leituraPausada) "Continuar leitura" else "Pausar leitura",
                    tint = Gold,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = {
                    onCancelarLeitura()
                    onRecolher()
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Fechar leitura",
                    tint = DangerRed,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onRecolher,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Recolher",
                    tint = TextGrey,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// === PAINEL ADMIN EMBUTIDO SSD (BLOQUEIO, DESBLOQUEIO E TROCA DE PIN REMOTO) ===
@Composable
fun PainelAdminSSD(
    context: Context,
    client: Client,
    onSair: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var listaPerfis by remember { mutableStateOf<List<PerfilAdminInfo>>(emptyList()) }
    var carregando by remember { mutableStateOf(true) }
    var erroMsg by remember { mutableStateOf<String?>(null) }
    var buscaAdmin by remember { mutableStateOf("") }
    var processandoAcao by remember { mutableStateOf(false) }

    var perfilParaBloquear by remember { mutableStateOf<PerfilAdminInfo?>(null) }
    var motivoBloqueioInput by remember { mutableStateOf("Mensalidade pendente") }

    var perfilParaTrocarPin by remember { mutableStateOf<PerfilAdminInfo?>(null) }
    var novoPinInput by remember { mutableStateOf("") }

    fun recarregarPerfis() {
        carregando = true
        erroMsg = null
        carregarTodosPerfisAdmin(context, client) { perfis, erro ->
            listaPerfis = perfis
            erroMsg = erro
            carregando = false
        }
    }

    LaunchedEffect(Unit) {
        recarregarPerfis()
    }

    val perfisFiltrados = remember(listaPerfis, buscaAdmin) {
        if (buscaAdmin.isBlank()) listaPerfis
        else listaPerfis.filter {
            it.nomeExibicao.contains(buscaAdmin, ignoreCase = true) ||
                it.chaveUsuario.contains(buscaAdmin, ignoreCase = true) ||
                it.modo.contains(buscaAdmin, ignoreCase = true)
        }
    }

    val totalPerfis = listaPerfis.size
    val totalBloqueados = listaPerfis.count { it.bloqueado }
    val totalAtivos = totalPerfis - totalBloqueados
    val verdeAtivo = Color(0xFF2ECC71)

    if (perfilParaBloquear != null) {
        val alvo = perfilParaBloquear!!
        Dialog(onDismissRequest = { if (!processandoAcao) perfilParaBloquear = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.5.dp, DangerRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("BLOQUEAR COFRE", color = DangerRed, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Cliente: ${alvo.nomeExibicao} [${alvo.modo}]", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Motivos rápidos:", color = TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { motivoBloqueioInput = "Mensalidade pendente" },
                            border = BorderStroke(1.dp, Gold),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f).height(34.dp)
                        ) {
                            Text("Mensalidade", color = Color.White, fontSize = 10.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { motivoBloqueioInput = "Manutenção programada" },
                            border = BorderStroke(1.dp, Gold),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f).height(34.dp)
                        ) {
                            Text("Manutenção", color = Color.White, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = motivoBloqueioInput,
                        onValueChange = { motivoBloqueioInput = it.replace("\n", "").take(90) },
                        label = { Text("Mensagem exibida ao cliente", color = TextGrey, fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (!processandoAcao) perfilParaBloquear = null },
                            border = BorderStroke(1.dp, TextGrey),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CANCELAR", color = Color.White, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                processandoAcao = true
                                enviarComandoStatusAdmin(
                                    context = context,
                                    client = client,
                                    chaveUsuario = alvo.chaveUsuario,
                                    bloquear = true,
                                    motivo = motivoBloqueioInput
                                ) { ok, msg ->
                                    scope.launch(Dispatchers.Main) {
                                        processandoAcao = false
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        if (ok) {
                                            val mot = motivoBloqueioInput.trim().ifBlank { "Mensalidade pendente" }
                                            listaPerfis = listaPerfis.map { p ->
                                                if (p.chaveUsuario == alvo.chaveUsuario) p.copy(bloqueado = true, motivoBloqueio = mot)
                                                else p
                                            }
                                            perfilParaBloquear = null
                                        }
                                    }
                                }
                            },
                            enabled = !processandoAcao,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            if (processandoAcao) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Text("CONFIRMAR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (perfilParaTrocarPin != null) {
        val alvoPin = perfilParaTrocarPin!!
        Dialog(onDismissRequest = { if (!processandoAcao) perfilParaTrocarPin = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.5.dp, Gold),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("ALTERAR PIN DO CLIENTE", color = Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Cliente: ${alvoPin.nomeExibicao} [${alvoPin.modo}]", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = novoPinInput,
                        onValueChange = { if (it.length <= 6) novoPinInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Novo PIN (4 a 6 dígitos)", color = TextGrey) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (!processandoAcao) perfilParaTrocarPin = null },
                            border = BorderStroke(1.dp, TextGrey),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CANCELAR", color = Color.White, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                processandoAcao = true
                                alterarPinClienteAdmin(context, client, alvoPin, novoPinInput) { ok, msg ->
                                    scope.launch(Dispatchers.Main) {
                                        processandoAcao = false
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        if (ok) {
                                            val pLimpo = novoPinInput.trim()
                                            listaPerfis = listaPerfis.map { p ->
                                                if (p.chaveUsuario == alvoPin.chaveUsuario) p.copy(pinAtual = pLimpo)
                                                else p
                                            }
                                            perfilParaTrocarPin = null
                                        }
                                    }
                                }
                            },
                            enabled = !processandoAcao && novoPinInput.length >= 4,
                            colors = ButtonDefaults.buttonColors(containerColor = Gold),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            if (processandoAcao) {
                                CircularProgressIndicator(color = BgBlack, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Text("SALVAR PIN", color = BgBlack, fontWeight = FontWeight.Black, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, null, tint = Gold, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PAINEL ADMIN SSD", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Controle Remoto de Cofres (MS & CP)", color = TextGrey, fontSize = 11.sp)
            }
            IconButton(onClick = { if (!carregando) recarregarPerfis() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = Gold)
            }
            IconButton(onClick = onSair) {
                Icon(Icons.Default.Close, contentDescription = "Sair do Painel", tint = DangerRed)
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDarker, RoundedCornerShape(14.dp))
                .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$totalPerfis", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Total", color = TextGrey, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$totalAtivos", color = verdeAtivo, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Ativos", color = TextGrey, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$totalBloqueados", color = DangerRed, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Bloqueados", color = TextGrey, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = buscaAdmin,
            onValueChange = { buscaAdmin = it.replace("\n", "").take(40) },
            placeholder = { Text("Filtrar cliente por nome ou modo (MS/CP)...", color = TextGrey, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Gold) },
            trailingIcon = {
                if (buscaAdmin.isNotBlank()) {
                    IconButton(onClick = { buscaAdmin = "" }) {
                        Icon(Icons.Default.Close, null, tint = TextGrey)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Gold,
                unfocusedBorderColor = SurfaceDark,
                focusedContainerColor = SurfaceDarker,
                unfocusedContainerColor = SurfaceDarker
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        if (carregando) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Gold)
                    Spacer(Modifier.height(12.dp))
                    Text("Carregando cofres da nuvem SSD...", color = TextGrey, fontSize = 12.sp)
                }
            }
        } else if (erroMsg != null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = DangerRed, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(erroMsg ?: "", color = DangerRed, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { recarregarPerfis() },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold)
                    ) {
                        Text("TENTAR NOVAMENTE", color = BgBlack, fontWeight = FontWeight.Black)
                    }
                }
            }
        } else if (perfisFiltrados.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nenhum cofre encontrado.", color = TextGrey, fontSize = 13.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                perfisFiltrados.forEach { perfil ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SurfaceDark,
                        border = BorderStroke(
                            1.dp,
                            if (perfil.bloqueado) DangerRed.copy(alpha = 0.8f) else Gold.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    null,
                                    tint = if (perfil.bloqueado) DangerRed else Gold,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            perfil.nomeExibicao,
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(Gold.copy(alpha = 0.18f), RoundedCornerShape(5.dp))
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        ) {
                                            Text(perfil.modo, color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                        }
                                    }
                                    Text(
                                        "PIN: ${perfil.pinAtual.ifBlank { "---" }}",
                                        color = TextGrey,
                                        fontSize = 11.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (perfil.bloqueado) DangerRed.copy(alpha = 0.2f) else verdeAtivo.copy(alpha = 0.2f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (perfil.bloqueado) DangerRed else verdeAtivo,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        if (perfil.bloqueado) "BLOQUEADO" else "ATIVO",
                                        color = if (perfil.bloqueado) DangerRed else verdeAtivo,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }

                            if (perfil.bloqueado && perfil.motivoBloqueio.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Motivo: ${perfil.motivoBloqueio}",
                                    color = DangerRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (perfil.bloqueado) {
                                    Button(
                                        onClick = {
                                            processandoAcao = true
                                            enviarComandoStatusAdmin(
                                                context = context,
                                                client = client,
                                                chaveUsuario = perfil.chaveUsuario,
                                                bloquear = false,
                                                motivo = ""
                                            ) { ok, msg ->
                                                scope.launch(Dispatchers.Main) {
                                                    processandoAcao = false
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    if (ok) {
                                                        listaPerfis = listaPerfis.map { p ->
                                                            if (p.chaveUsuario == perfil.chaveUsuario) p.copy(bloqueado = false, motivoBloqueio = "")
                                                            else p
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !processandoAcao,
                                        colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                        modifier = Modifier.weight(1.4f).height(40.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, null, tint = BgBlack, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("DESBLOQUEAR", color = BgBlack, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            motivoBloqueioInput = "Mensalidade pendente"
                                            perfilParaBloquear = perfil
                                        },
                                        enabled = !processandoAcao,
                                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                        modifier = Modifier.weight(1.4f).height(40.dp)
                                    ) {
                                        Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("BLOQUEAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        novoPinInput = perfil.pinAtual
                                        perfilParaTrocarPin = perfil
                                    },
                                    enabled = !processandoAcao,
                                    border = BorderStroke(1.dp, Gold),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Icon(Icons.Default.Edit, null, tint = Gold, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("PIN", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onSair,
            border = BorderStroke(1.dp, Gold),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text("VOLTAR PARA TELA DE LOGIN", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

// === TELA DE AVISO DE COFRE BLOQUEADO (COM TRAVA ANTI-OFFLINE) ===
@Composable
fun TelaCofreBloqueado(
    nomeUsuario: String,
    motivo: String,
    verificando: Boolean,
    onVerificarLiberacao: () -> Unit,
    onTrocarUsuario: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDarker,
            border = BorderStroke(1.5.dp, DangerRed),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Lock, null, tint = DangerRed, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("COFRE BLOQUEADO", color = DangerRed, fontSize = 21.sp, fontWeight = FontWeight.Black)
                if (nomeUsuario.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Perfil: $nomeUsuario", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .border(1.dp, DangerRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("MOTIVO DO BLOQUEIO", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = motivo.ifBlank { "Acesso temporariamente suspenso pela administração." },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Seus arquivos e pastas continuam preservados na nuvem SSD. Fale com o suporte SSD Soluções para regularizar o acesso.",
                    color = TextGrey,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onVerificarLiberacao,
                    enabled = !verificando,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (verificando) {
                        CircularProgressIndicator(color = BgBlack, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, tint = BgBlack)
                        Spacer(Modifier.width(8.dp))
                        Text("VERIFICAR LIBERAÇÃO", color = BgBlack, fontWeight = FontWeight.Black)
                    }
                }

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onTrocarUsuario) {
                    Text("Entrar com outro utilizador", color = TextGrey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NexusUI(dao: NexusDao, client: Client) {
    val vm: UIViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(mClass: Class<T>) = UIViewModel(dao) as T
    })
    val todosArquivos by vm.arquivos.collectAsState()
    val todasPastas by vm.pastas.collectAsState()
    val loginStep by TelegramAuth.step.collectAsState()
    val statusText by TelegramAuth.statusText.collectAsState()
    val errorText by TelegramAuth.errorText.collectAsState()

    // Apenas estados binários ficam aqui; percentuais ficam isolados em BarraProgressoIsoladaSSD
    val isUploading by NexusTransfer.enviandoEmSegundoPlano.collectAsState()
    val bannerStatusGlobal by NexusTransfer.bannerStatusGlobal.collectAsState()
    val sincronizandoNuvem by NexusSyncState.emAndamento.collectAsState()

    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()
    var jobFilaMiniaturas by remember { mutableStateOf<Job?>(null) }
    var jobCapasSilencioso by remember { mutableStateOf<Job?>(null) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun solicitarPermissaoNotificacaoSeNecessario() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val concedida = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!concedida) {
                try {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Throwable) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        NexusThumbCache.inicializar(context)
        NexusAI.inicializar(context)
    }

    var phoneNumber by remember { mutableStateOf("+5585") }
    var smsCode by remember { mutableStateOf("") }
    var password2FA by remember { mutableStateOf("") }

// Recupera o último utilizador salvo (ou retorno imediato do seletor de upload)
    val ultimoUsuarioSalvo = remember { obterUltimoUsuarioCofre(context) }
    val usuarioRetornoUpload = remember { NexusSessaoUpload.consumirUsuarioPendente(context) }
    val usuarioInicial = usuarioRetornoUpload ?: ultimoUsuarioSalvo

    var activeVaultUser by remember { mutableStateOf(usuarioInicial) }
    var vaultStorageMode by remember { mutableStateOf(obterModoCofreLocal(context, usuarioInicial)) }
    var newVaultModeInput by remember { mutableStateOf("MS") }
    var vaultUnlocked by remember { mutableStateOf(usuarioRetornoUpload != null) }
    var isSwitchingUser by remember { mutableStateOf(usuarioInicial.isBlank()) }
    var isCreatingNewVault by remember { mutableStateOf(false) }
    var vaultNameInput by remember { mutableStateOf("") }
    var vaultPinInput by remember { mutableStateOf("") }
    var vaultErrorMsg by remember { mutableStateOf<String?>(null) }
    var isCheckingVault by remember { mutableStateOf(false) }
    var procurandoPerfisNuvem by remember { mutableStateOf(false) }
    var biometriaAtiva by remember { mutableStateOf(NexusBiometria.estaAtiva(context)) }
    var usuarioTrancouManualmente by remember { mutableStateOf(false) }

    // Tranca automaticamente sempre que fechar ou minimizar o app (exceto ao escolher arquivo para upload)
    BloqueioAutomaticoAoSair(
        context = context,
        vaultUnlocked = vaultUnlocked,
        onTrancarCofre = {
            vaultUnlocked = false
            vaultPinInput = ""
            usuarioTrancouManualmente = false
        }
    )
    // Estados do Painel Admin e do Bloqueio Remoto Anti-Offline
    var showAdminPanel by remember { mutableStateOf(false) }
    val statusInicialLocal = remember(ultimoUsuarioSalvo) { obterStatusCofreLocal(context, ultimoUsuarioSalvo) }
    var vaultBlocked by remember { mutableStateOf(ultimoUsuarioSalvo.isNotBlank() && statusInicialLocal.bloqueado) }
    var vaultBlockedReason by remember { mutableStateOf(statusInicialLocal.motivo) }
    var checkingUnlockStatus by remember { mutableStateOf(false) }

    // Estados da Nexus IA (Leitura Offline OCR + Chave para desligar OCR automático no upload)
    var ocrAutoNoUpload by remember { mutableStateOf(obterOcrAutoNoUpload(context)) }
    var arquivoAnaliseIA by remember { mutableStateOf<ArquivoNuvem?>(null) }
    var analisandoIA by remember { mutableStateOf(false) }
    var resultadoIA by remember { mutableStateOf<ResultadoAnaliseIA?>(null) }
    var iaIndexTick by remember { mutableIntStateOf(0) }
    var indexandoLoteIA by remember { mutableStateOf(false) }

    // Sincroniza silenciosamente os registos (MS/CP) e status [STATUS_COFRE] na nuvem
    LaunchedEffect(loginStep, vaultUnlocked, showAdminPanel) {
        if (loginStep >= 3 && !vaultUnlocked && !showAdminPanel) {
            procurandoPerfisNuvem = true
            descobrirPerfisExistentesNaNuvem(context, client) {
                scope.launch(Dispatchers.Main) {
                    procurandoPerfisNuvem = false
                    if (activeVaultUser.isNotBlank()) {
                        vaultStorageMode = obterModoCofreLocal(context, activeVaultUser)
                        val stAtual = obterStatusCofreLocal(context, activeVaultUser)
                        if (stAtual.bloqueado && !isSwitchingUser) {
                            vaultBlocked = true
                            vaultBlockedReason = stAtual.motivo
                        } else if (!stAtual.bloqueado && vaultBlocked) {
                            vaultBlocked = false
                            vaultBlockedReason = ""
                        }
                    }
                }
            }
        }
    }

    var mapaDonos by remember { mutableStateOf(carregarDonosFicheiros(context)) }
    var mapaPastas by remember { mutableStateOf(carregarVinculosPastas(context)) }
    var pastasDoUsuarioSet by remember(activeVaultUser) { mutableStateOf(carregarPastasDoUsuario(context, activeVaultUser)) }

    var novosFicheirosSync by remember { mutableStateOf<List<NovoArquivoSync>>(emptyList()) }
    var selecionadosSync by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showNovosSyncDialog by remember { mutableStateOf(false) }
    var organizarPorPastaSync by remember { mutableStateOf(true) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFileIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var filesToMoveBatch by remember { mutableStateOf<List<ArquivoNuvem>>(emptyList()) }
    var filesToDeleteBatch by remember { mutableStateOf<List<ArquivoNuvem>>(emptyList()) }
    var isDeletingFile by remember { mutableStateOf(false) }

    var pastaSelecionadaMenu by remember { mutableStateOf<String?>(null) }
    var pastaParaRenomear by remember { mutableStateOf<String?>(null) }
    var novoNomePastaInput by remember { mutableStateOf("") }
    var pastaParaApagar by remember { mutableStateOf<String?>(null) }
    var isDeletingFolder by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var erroUploadDetalhado by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(todosArquivos.size, sincronizandoNuvem) {
        if (!sincronizandoNuvem) {
            mapaDonos = carregarDonosFicheiros(context)
            mapaPastas = carregarVinculosPastas(context)
            if (activeVaultUser.isNotBlank()) {
                pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
            }
        }
    }

    val arquivos = remember(todosArquivos, mapaDonos, activeVaultUser) {
        val chaveAtiva = normalizarUsuario(activeVaultUser)
        if (chaveAtiva.isBlank()) emptyList()
        else todosArquivos
            .filter { mapaDonos[it.dataUpload] == chaveAtiva }
            .distinctBy { "${it.dataUpload}_${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}" }
    }

    val pastas = remember(todasPastas, pastasDoUsuarioSet, arquivos, mapaPastas, activeVaultUser) {
        if (activeVaultUser.isBlank()) emptyList()
        else {
            val pastasDosArquivos = arquivos.mapNotNull { arq ->
                mapaPastas[arq.dataUpload]?.trim()?.takeIf { it.isNotBlank() }
            }
            val combinadas = (pastasDosArquivos + pastasDoUsuarioSet.map { it.trim() } +
                todasPastas.map { it.nomePasta.trim() }.filter { p -> pastasDoUsuarioSet.any { it.equals(p, ignoreCase = true) } })
                .filter { it.isNotBlank() }
                .filterNot { p -> pastaFoiEliminadaPeloUsuario(context, activeVaultUser, p) }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .sortedBy { it.lowercase(Locale.ROOT) }
                .map { PastaNuvem(nomePasta = it) }
            combinadas
        }
    }

    val armazenamentoUsuario = remember(arquivos) {
        arquivos.sumOf { it.tamanhoBytes }
    }

    fun abrirAnaliseIA(arq: ArquivoNuvem, forcarReleitura: Boolean = false) {
        arquivoAnaliseIA = arq
        analisandoIA = true
        resultadoIA = null
        garantirFicheiroLocal(context, client, activeVaultUser, arq, vm) { arqPronto ->
            scope.launch {
                val caminhoAlvo = NexusThumbCache.obterArquivoCompleto(arqPronto)
                    .ifBlank { arqPronto.caminhoMiniatura ?: "" }
                    .ifBlank { NexusThumbCache.obterMiniatura(arqPronto) }
                val texto = NexusAI.extrairTextoDeArquivo(
                    context = context,
                    caminhoArquivo = caminhoAlvo,
                    nomeArquivo = arqPronto.nomeArquivo,
                    dataUpload = arqPronto.dataUpload,
                    forcarReleitura = forcarReleitura
                )
                resultadoIA = NexusAI.analisarConteudo(texto, arqPronto.nomeArquivo)
                analisandoIA = false
                iaIndexTick++
            }
        }
    }

    fun processarFilaMiniaturasNuvem(listaArquivosCofre: List<ArquivoNuvem>) {
        jobFilaMiniaturas?.cancel()
        NexusSessaoUpload.limparAberturaSeletor(context)
                    usuarioTrancouManualmente = true
        jobFilaMiniaturas = scope.launch(Dispatchers.IO) {
            val pendentes = listaArquivosCofre
                .sortedByDescending { it.dataUpload }
                .filter { arq ->
                    val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
                    val suportaCapa = ehArquivoImagem(arq.nomeArquivo) || ehArquivoVideo(arq.nomeArquivo) || ext == "PDF"
                    suportaCapa && !miniaturaExisteLocalmente(NexusThumbCache.obterMiniatura(arq), arq.nomeArquivo)
                }

            if (pendentes.isEmpty()) {
                NexusLeituraNuvem.encerrar()
                return@launch
            }

            NexusLeituraNuvem.iniciar(pendentes.size, "Carregando miniaturas da nuvem SSD ($vaultStorageMode)...")

            for ((idx, arq) in pendentes.withIndex()) {
                while (NexusLeituraNuvem.pausado.value || NexusSyncState.emAndamento.value) {
                    delay(200)
                }
                if (!NexusLeituraNuvem.ativo.value) break

                NexusLeituraNuvem.avancar(
                    novoLido = idx,
                    novoTotal = pendentes.size,
                    texto = "Carregando preview ($vaultStorageMode): ${arq.nomeArquivo}"
                )
                if (!miniaturaExisteLocalmente(NexusThumbCache.obterMiniatura(arq), arq.nomeArquivo)) {
                    NexusThumbCache.emProgresso.add(arq.dataUpload)
                    try {
                        NexusThumbCache.downloadSemaphore.withPermit {
                            carregarMiniaturaIndividualDaNuvem(
                                context = context,
                                client = client,
                                usuarioAtivo = activeVaultUser,
                                arq = arq,
                                vm = vm,
                                atualizarSubBarra = true
                            )
                        }
                    } catch (_: Exception) {
                    } finally {
                        NexusThumbCache.emProgresso.remove(arq.dataUpload)
                    }
                }
                NexusLeituraNuvem.concluirItem()
                delay(80)
            }
            NexusLeituraNuvem.encerrar()
        }
    }

    fun verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio: Boolean) {
        scope.launch {
            val chaveAtiva = normalizarUsuario(activeVaultUser)
            val listaReal = vm.obterArquivosReais()
            val donosAtuais = carregarDonosFicheiros(context)
            val arquivosAtuaisDoCofre = listaReal.filter { donosAtuais[it.dataUpload] == chaveAtiva }

            val encontrados = withContext(Dispatchers.IO) {
                marcarLoteComoSincronizado(appContext, activeVaultUser, arquivosAtuaisDoCofre)
                procurarNovosFicheirosNasPastas(appContext, activeVaultUser, arquivosAtuaisDoCofre)
            }
            if (encontrados.isNotEmpty()) {
                novosFicheirosSync = encontrados
                selecionadosSync = encontrados.map { it.uri.toString() }.toSet()
                showNovosSyncDialog = true
            } else if (mostrarAvisoSeVazio) {
                Toast.makeText(context, "Nenhum ficheiro novo nas pastas selecionadas.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(todosArquivos.size, vaultUnlocked, isUploading, sincronizandoNuvem) {
        if (todosArquivos.isNotEmpty() && !isUploading && !sincronizandoNuvem) {
            val alterou = withContext(Dispatchers.IO) {
                var mudou = false
                limparDuplicadosExistentes(appContext, todosArquivos, vm) { mudou = true }
                mudou
            }
            if (alterou) {
                mapaDonos = carregarDonosFicheiros(context)
                mapaPastas = carregarVinculosPastas(context)
                pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
            }
        }
    }

    LaunchedEffect(vaultUnlocked, activeVaultUser) {
        if (vaultUnlocked && activeVaultUser.isNotBlank()) {
            val stLocalAntes = obterStatusCofreLocal(context, activeVaultUser)
            if (stLocalAntes.bloqueado) {
                vaultUnlocked = false
                vaultBlocked = true
                vaultBlockedReason = stLocalAntes.motivo
                return@LaunchedEffect
            }

            verificarStatusRemotoCliente(context, client, activeVaultUser) { bloqueadoRemoto, motivoRemoto ->
                if (bloqueadoRemoto) {
                    scope.launch(Dispatchers.Main) {
                        jobFilaMiniaturas?.cancel()
                        NexusSessaoUpload.limparAberturaSeletor(context)
                    usuarioTrancouManualmente = true
                        NexusLeituraNuvem.encerrar()
                        NexusTransfer.cancelar(client)
                        vaultUnlocked = false
                        vaultBlocked = true
                        vaultBlockedReason = motivoRemoto
                    }
                }
            }

            solicitarPermissaoNotificacaoSeNecessario()

            val (limpouIni, bytesIni) = withContext(Dispatchers.IO) {
                verificarELimparTempAutomatico(appContext)
            }
            if (limpouIni) {
                Toast.makeText(
                    context,
                    "Auto-limpeza SSD: temporários removidos (${formatarTamanhoNuvem(bytesIni)} liberados).",
                    Toast.LENGTH_SHORT
                ).show()
            }

            vaultStorageMode = obterModoCofreLocal(context, activeVaultUser)
            val arquivosIniciais = vm.obterArquivosReais()
            val pastasIniciais = vm.obterPastasReais()
            withContext(Dispatchers.IO) {
                migrarDadosV11SeNecessario(appContext, activeVaultUser, arquivosIniciais, pastasIniciais)
                limparDuplicadosExistentes(appContext, arquivosIniciais, vm) {}
            }
            mapaDonos = carregarDonosFicheiros(context)
            mapaPastas = carregarVinculosPastas(context)
            pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)

            val aposLimpeza = vm.obterArquivosReais()
            sincronizarCofreDaNuvem(context, client, activeVaultUser, aposLimpeza, dao, vm) { qtd ->
                scope.launch(Dispatchers.Main) {
                    NexusLeituraNuvem.encerrar()
                    delay(150)
                    vaultStorageMode = obterModoCofreLocal(context, activeVaultUser)
                    val atualizados = vm.obterArquivosReais()
                    withContext(Dispatchers.IO) {
                        limparDuplicadosExistentes(appContext, atualizados, vm) {}
                    }
                    mapaDonos = carregarDonosFicheiros(context)
                    mapaPastas = carregarVinculosPastas(context)
                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)

                    if (qtd > 0) {
                        Toast.makeText(context, "$qtd novo(s) ficheiro(s) sincronizados na nuvem SSD ($vaultStorageMode)!", Toast.LENGTH_SHORT).show()
                    }
                    delay(350)
                    verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio = false)
                    val chaveUsuarioAtual = normalizarUsuario(activeVaultUser)
                    val donosSincronizados = carregarDonosFicheiros(context)
                    val arquivosDoPerfil = atualizados.filter { donosSincronizados[it.dataUpload] == chaveUsuarioAtual }
                    jobCapasSilencioso?.cancel()
                    jobCapasSilencioso = scope.launch(Dispatchers.IO) {
                        baixarCapasLevesEmSegundoPlano(appContext, client, activeVaultUser, arquivosDoPerfil, vm)
                    }
                }
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedFile by remember { mutableStateOf<ArquivoNuvem?>(null) }
    var viewerFile by remember { mutableStateOf<ArquivoNuvem?>(null) }

    var fileToRename by remember { mutableStateOf<ArquivoNuvem?>(null) }
    var novoNomeInput by remember { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    var pastaAberta by remember { mutableStateOf<String?>(null) }
    var pendingUploadUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var showStorage by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var nomeNovaPasta by remember { mutableStateOf("") }

    var pastasSync by remember(activeVaultUser) { mutableStateOf(carregarPastasSync(context, activeVaultUser)) }

    var cloudBannerText by remember(activeVaultUser, vaultStorageMode) {
        mutableStateOf(
            if (activeVaultUser.isNotBlank()) "Cofre [$vaultStorageMode]: $activeVaultUser • Nuvem SSD Ativa"
            else "Nuvem SSD Ativa"
        )
    }

    LaunchedEffect(bannerStatusGlobal) {
        if (bannerStatusGlobal.isNotBlank()) {
            cloudBannerText = bannerStatusGlobal
        }
    }

    val listaVisivelAtual = remember(arquivos, selectedTab, pastaAberta, mapaPastas, searchQuery, iaIndexTick) {
        val filtraPorConsulta: (ArquivoNuvem) -> Boolean = { arq ->
            searchQuery.isBlank() ||
                arq.nomeArquivo.contains(searchQuery, ignoreCase = true) ||
                NexusAI.correspondeBuscaIA(context, arq.dataUpload, searchQuery)
        }
        if (selectedTab == 1) {
            if (pastaAberta != null) {
                arquivos.filter {
                    mapaPastas[it.dataUpload]?.equals(pastaAberta, ignoreCase = true) == true && filtraPorConsulta(it)
                }
            } else {
                emptyList()
            }
        } else {
            val base = when (selectedTab) {
                2 -> arquivos.filter { ehArquivoImagem(it.nomeArquivo) }
                3 -> arquivos.filter { ehArquivoVideo(it.nomeArquivo) }
                4 -> arquivos.filter { !ehArquivoImagem(it.nomeArquivo) && !ehArquivoVideo(it.nomeArquivo) }
                else -> arquivos
            }
            if (searchQuery.isBlank()) base else base.filter(filtraPorConsulta)
        }
    }

    // Lista na mesma ordem cronológica da grade para o deslizar do visualizador
    val listaOrdenadaViewer = remember(listaVisivelAtual) {
        listaVisivelAtual
            .distinctBy { "${it.dataUpload}_${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.dataUpload }
    }

    fun alternarSelecaoFicheiro(arq: ArquivoNuvem) {
        val novos = if (selectedFileIds.contains(arq.id)) {
            selectedFileIds - arq.id
        } else {
            selectedFileIds + arq.id
        }
        selectedFileIds = novos
        isSelectionMode = novos.isNotEmpty()
    }

    LaunchedEffect(isUploading) {
        while (isUploading) {
            delay(450)
            if (!NexusTransfer.pausado.value && !NexusTransfer.cancelado.value) {
                val atual = NexusTransfer.progress.value ?: 10
                if (atual in 35..88) {
                    NexusTransfer.atualizarProgresso((atual + 3).coerceAtMost(90))
                }
            }
        }
    }

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) TelegramAuth.errorText.value = "Erro SSD (${obj.code}): ${obj.message}"
        }
    }

    // === UPLOAD REAL EM SEGUNDO PLANO (RESPEITA A CHAVE DE OCR AUTOMÁTICO) ===
    fun executarUploads(uris: List<Uri>, pastaEscolhida: String) {
        if (uris.isEmpty() || activeVaultUser.isBlank()) return
        solicitarPermissaoNotificacaoSeNecessario()

        val usuarioAlvo = activeVaultUser
        val modoAlvo = vaultStorageMode

        NexusTransfer.jobAtivo?.cancel()
        NexusTransfer.jobAtivo = NexusBackgroundScope.scope.launch {
            var enviados = 0
            val total = uris.size
            val nomesUsadosNoLote = mutableSetOf<String>()
            try {
                NexusTransfer.iniciar(
                    texto = "Preparando envio $modoAlvo (1/$total)...",
                    upload = true,
                    context = appContext
                )

                for ((index, uri) in uris.withIndex()) {
                    if (NexusTransfer.cancelado.value) break
                    while (NexusTransfer.pausado.value) {
                        delay(200)
                        if (NexusTransfer.cancelado.value) break
                    }
                    if (NexusTransfer.cancelado.value) break

                    val (nomeBruto, tamanho) = obterDetalhesFicheiro(appContext, uri)
                    val nome = gerarNomeUnicoNoLote(nomeBruto, nomesUsadosNoLote)
                    val loteTag = "(${index + 1}/$total) "

                    NexusTransfer.atualizarProgresso(8, "Preparando $loteTag'$nome'...", forcarNotif = true)
                    NexusTransfer.bannerStatusGlobal.value = "Enviando [$modoAlvo] $loteTag'$nome'..."

                    val arqTemp = copiarUriParaCacheTDLib(appContext, uri, nome, tamanho, loteTag)
                    if (NexusTransfer.cancelado.value) {
                        arqTemp?.delete()
                        break
                    }

                    val capaJpg = if (arqTemp != null) {
                        salvarMiniaturaPrivada(appContext, uri, nome, arqTemp)
                    } else ""

                    if (arqTemp != null) {
                        while (NexusTransfer.pausado.value) {
                            delay(200)
                            if (NexusTransfer.cancelado.value) break
                        }
                        if (NexusTransfer.cancelado.value) {
                            arqTemp.delete()
                            break
                        }

                        val tamFinal = if (arqTemp.length() > 0L) arqTemp.length() else tamanho
                        desmarcarFicheiroDeletado(appContext, usuarioAlvo, nome, tamFinal)
                        marcarFicheiroComoSincronizado(appContext, usuarioAlvo, nome, tamFinal, uri.toString())

                        NexusTransfer.atualizarProgresso(40, "Enviando para nuvem SSD [$modoAlvo] $loteTag'$nome'...", forcarNotif = true)

                        val deferred = CompletableDeferred<ResultadoEnvioSSD>()
                        enviarParaMensagensSalvas(appContext, client, usuarioAlvo, arqTemp.absolutePath, capaJpg, nome, pastaEscolhida) { ok, rId, cId, mId, msg ->
                            deferred.complete(ResultadoEnvioSSD(ok, rId, cId, mId, msg))
                        }
                        val res = deferred.await()

                        if (NexusTransfer.cancelado.value) {
                            arqTemp.delete()
                            break
                        }

                        if (!res.ok) {
                            arqTemp.delete()
                            NexusTransfer.bannerStatusGlobal.value = res.mensagem
                            withContext(Dispatchers.Main) { erroUploadDetalhado = res.mensagem }
                            continue
                        }

                        val (concluiuRede, erroRede) = aguardarUploadTDLibComPausa(
                            client = client,
                            fileId = res.fileId,
                            nomeArquivo = nome,
                            loteTag = loteTag,
                            chatId = res.chatId,
                            messageId = res.messageId
                        )

                        if (NexusTransfer.cancelado.value || !concluiuRede) {
                            arqTemp.delete()
                            val motivo = if (NexusTransfer.cancelado.value) "Envio de '$nome' cancelado." else erroRede
                            NexusTransfer.bannerStatusGlobal.value = motivo
                            if (!NexusTransfer.cancelado.value && erroRede.isNotBlank() && erroRede != "OK") {
                                withContext(Dispatchers.Main) { erroUploadDetalhado = erroRede }
                            }
                            break
                        }

                        NexusTransfer.bannerStatusGlobal.value = res.mensagem
                        enviados++
                        val ts = System.currentTimeMillis() + (index * 15L) + enviados
                        salvarDonoFicheiro(appContext, ts, usuarioAlvo)
                        if (capaJpg.isNotBlank()) {
                            NexusThumbCache.salvarMiniatura(appContext, ts, capaJpg)
                        }
                        NexusThumbCache.salvarArquivoCompleto(appContext, ts, Uri.fromFile(arqTemp).toString())

                        if (pastaEscolhida.isNotBlank()) {
                            try {
                                val atuaisP = dao.buscarPastas().first()
                                if (atuaisP.none { it.nomePasta.trim().equals(pastaEscolhida.trim(), ignoreCase = true) }) {
                                    dao.criarPasta(PastaNuvem(nomePasta = pastaEscolhida.trim()))
                                }
                            } catch (_: Throwable) {}
                            associarPastaAoUsuario(appContext, usuarioAlvo, pastaEscolhida)
                            salvarVinculoPasta(appContext, ts, pastaEscolhida)
                        }

                        vm.salvarArquivoSincrono(
                            ArquivoNuvem(
                                fileId = res.fileId,
                                nomeArquivo = nome,
                                tamanhoBytes = tamFinal,
                                dataUpload = ts,
                                caminhoMiniatura = capaJpg
                            ),
                            forcarNovoRegistro = true
                        )

                        // Só roda OCR automático no upload se a chave estiver ativada (poupa bateria)
                        if (obterOcrAutoNoUpload(appContext) && NexusAI.suportaLeituraIA(nome)) {
                            NexusBackgroundScope.scope.launch {
                                NexusAI.extrairTextoDeArquivo(appContext, arqTemp.absolutePath, nome, ts)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            mapaDonos = carregarDonosFicheiros(appContext)
                            mapaPastas = carregarVinculosPastas(appContext)
                            if (pastaEscolhida.isNotBlank()) {
                                pastasDoUsuarioSet = carregarPastasDoUsuario(appContext, usuarioAlvo)
                            }
                        }
                    } else {
                        val erroLeitura = "Erro ao ler '$nome'."
                        NexusTransfer.bannerStatusGlobal.value = erroLeitura
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, erroLeitura, Toast.LENGTH_SHORT).show()
                        }
                    }
                    delay(120)
                }

                val msgFinal = if (NexusTransfer.cancelado.value) {
                    "Envio cancelado ($enviados guardado(s))."
                } else if (enviados > 0) {
                    if (total > 1) {
                        "$enviados de $total ficheiros guardados no cofre [$modoAlvo] de $usuarioAlvo!"
                    } else {
                        "Guardado no cofre [$modoAlvo] de $usuarioAlvo!"
                    }
                } else ""

                if (msgFinal.isNotBlank()) {
                    NexusTransfer.bannerStatusGlobal.value = msgFinal
                    withContext(Dispatchers.Main) {
                        mapaDonos = carregarDonosFicheiros(appContext)
                        mapaPastas = carregarVinculosPastas(appContext)
                        Toast.makeText(appContext, msgFinal, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                NexusTransfer.encerrar()
                val (limpouAuto, bytesAuto) = verificarELimparTempAutomatico(appContext)
                if (limpouAuto) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            "Auto-limpeza SSD: temporários removidos (${formatarTamanhoNuvem(bytesAuto)} liberados).",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    // === SINCRONIZAÇÃO AUTOMÁTICA REAL EM SEGUNDO PLANO (RESPEITA A CHAVE DE OCR) ===
    fun executarUploadsNovosSync(itensParaEnviar: List<NovoArquivoSync>, manterPastasOrigem: Boolean) {
        if (itensParaEnviar.isEmpty() || activeVaultUser.isBlank()) return
        solicitarPermissaoNotificacaoSeNecessario()

        val usuarioAlvo = activeVaultUser
        val modoAlvo = vaultStorageMode

        NexusTransfer.jobAtivo?.cancel()
        NexusTransfer.jobAtivo = NexusBackgroundScope.scope.launch {
            var enviados = 0
            val total = itensParaEnviar.size
            val nomesUsadosNoLote = mutableSetOf<String>()
            try {
                NexusTransfer.iniciar(
                    texto = "Sincronizando novos arquivos (1/$total)...",
                    upload = true,
                    context = appContext
                )

                for ((index, item) in itensParaEnviar.withIndex()) {
                    if (NexusTransfer.cancelado.value) break
                    while (NexusTransfer.pausado.value) {
                        delay(200)
                        if (NexusTransfer.cancelado.value) break
                    }
                    if (NexusTransfer.cancelado.value) break

                    val nomeLimpo = gerarNomeUnicoNoLote(item.nome, nomesUsadosNoLote)
                    desmarcarFicheiroDeletado(appContext, usuarioAlvo, nomeLimpo, item.tamanho)
                    marcarFicheiroComoSincronizado(appContext, usuarioAlvo, nomeLimpo, item.tamanho, item.uri.toString())

                    val pastaAlvo = if (manterPastasOrigem) item.nomePastaOrigem.trim() else ""
                    val loteTag = "(${index + 1}/$total) "
                    NexusTransfer.atualizarProgresso(10, "Sincronizando $loteTag'$nomeLimpo'...", forcarNotif = true)
                    NexusTransfer.bannerStatusGlobal.value = "Sincronizando [$modoAlvo] $loteTag'$nomeLimpo'..."

                    val arqTemp = copiarUriParaCacheTDLib(appContext, item.uri, nomeLimpo, item.tamanho, loteTag)
                    if (NexusTransfer.cancelado.value) {
                        arqTemp?.delete()
                        break
                    }

                    val capaJpg = if (arqTemp != null) {
                        salvarMiniaturaPrivada(appContext, item.uri, nomeLimpo, arqTemp)
                    } else ""

                    if (arqTemp != null) {
                        while (NexusTransfer.pausado.value) {
                            delay(200)
                            if (NexusTransfer.cancelado.value) break
                        }
                        if (NexusTransfer.cancelado.value) {
                            arqTemp.delete()
                            break
                        }

                        val tamFinal = if (arqTemp.length() > 0L) arqTemp.length() else item.tamanho
                        marcarFicheiroComoSincronizado(appContext, usuarioAlvo, nomeLimpo, tamFinal, item.uri.toString())

                        val deferred = CompletableDeferred<ResultadoEnvioSSD>()
                        enviarParaMensagensSalvas(appContext, client, usuarioAlvo, arqTemp.absolutePath, capaJpg, nomeLimpo, pastaAlvo) { ok, rId, cId, mId, msg ->
                            deferred.complete(ResultadoEnvioSSD(ok, rId, cId, mId, msg))
                        }
                        val res = deferred.await()

                        if (NexusTransfer.cancelado.value) {
                            arqTemp.delete()
                            break
                        }

                        if (!res.ok) {
                            arqTemp.delete()
                            NexusTransfer.bannerStatusGlobal.value = res.mensagem
                            withContext(Dispatchers.Main) { erroUploadDetalhado = res.mensagem }
                            continue
                        }

                        val (concluiuRede, erroRede) = aguardarUploadTDLibComPausa(
                            client = client,
                            fileId = res.fileId,
                            nomeArquivo = nomeLimpo,
                            loteTag = loteTag,
                            chatId = res.chatId,
                            messageId = res.messageId
                        )

                        if (NexusTransfer.cancelado.value || !concluiuRede) {
                            arqTemp.delete()
                            val motivo = if (NexusTransfer.cancelado.value) "Sincronização cancelada." else erroRede
                            NexusTransfer.bannerStatusGlobal.value = motivo
                            if (!NexusTransfer.cancelado.value && erroRede.isNotBlank() && erroRede != "OK") {
                                withContext(Dispatchers.Main) { erroUploadDetalhado = erroRede }
                            }
                            break
                        }

                        NexusTransfer.bannerStatusGlobal.value = res.mensagem
                        enviados++
                        val ts = System.currentTimeMillis() + (index * 15L) + enviados
                        salvarDonoFicheiro(appContext, ts, usuarioAlvo)
                        if (capaJpg.isNotBlank()) {
                            NexusThumbCache.salvarMiniatura(appContext, ts, capaJpg)
                        }
                        NexusThumbCache.salvarArquivoCompleto(appContext, ts, Uri.fromFile(arqTemp).toString())

                        if (pastaAlvo.isNotBlank()) {
                            try {
                                val atuaisP = dao.buscarPastas().first()
                                if (atuaisP.none { it.nomePasta.trim().equals(pastaAlvo.trim(), ignoreCase = true) }) {
                                    dao.criarPasta(PastaNuvem(nomePasta = pastaAlvo.trim()))
                                }
                            } catch (_: Throwable) {}
                            associarPastaAoUsuario(appContext, usuarioAlvo, pastaAlvo)
                            salvarVinculoPasta(appContext, ts, pastaAlvo)
                        }

                        vm.salvarArquivoSincrono(
                            ArquivoNuvem(
                                fileId = res.fileId,
                                nomeArquivo = nomeLimpo,
                                tamanhoBytes = tamFinal,
                                dataUpload = ts,
                                caminhoMiniatura = capaJpg
                            ),
                            forcarNovoRegistro = true
                        )

                        if (obterOcrAutoNoUpload(appContext) && NexusAI.suportaLeituraIA(nomeLimpo)) {
                            NexusBackgroundScope.scope.launch {
                                NexusAI.extrairTextoDeArquivo(appContext, arqTemp.absolutePath, nomeLimpo, ts)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            mapaDonos = carregarDonosFicheiros(appContext)
                            mapaPastas = carregarVinculosPastas(appContext)
                            if (pastaAlvo.isNotBlank()) {
                                pastasDoUsuarioSet = carregarPastasDoUsuario(appContext, usuarioAlvo)
                            }
                        }
                    }
                    delay(120)
                }

                val msgSync = if (NexusTransfer.cancelado.value) {
                    "Sincronização interrompida ($enviados enviado(s))."
                } else {
                    "$enviados novo(s) ficheiro(s) sincronizados na nuvem SSD ($modoAlvo)!"
                }
                NexusTransfer.bannerStatusGlobal.value = msgSync
                withContext(Dispatchers.Main) {
                    mapaDonos = carregarDonosFicheiros(appContext)
                    mapaPastas = carregarVinculosPastas(appContext)
                    Toast.makeText(appContext, msgSync, Toast.LENGTH_SHORT).show()
                }
            } finally {
                NexusTransfer.encerrar()
                val (limpouAuto, bytesAuto) = verificarELimparTempAutomatico(appContext)
                if (limpouAuto) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            "Auto-limpeza SSD: temporários removidos (${formatarTamanhoNuvem(bytesAuto)} liberados).",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { u -> NexusSessaoUpload.limparAberturaSeletor(context)
        if (uris.isNotEmpty() && activeVaultUser.isNotBlank()) {
            vaultUnlocked = true
        }
                try { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            if (pastaAberta != null) executarUploads(uris, pastaAberta!!)
            else if (pastas.isNotEmpty()) pendingUploadUris = uris
            else executarUploads(uris, "")
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                salvarPastaSync(context, activeVaultUser, it.toString(), true)
                pastasSync = carregarPastasSync(context, activeVaultUser)
                verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio = false)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao guardar permissão da pasta.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 1. ECRÃ DE AUTENTICAÇÃO DO NÚMERO SSD (COM LIMITE DE CARACTERES E ASTERISCOS)
    if (loginStep < 3) {
        val statusFormatadoSSD = remember(statusText) {
            statusText.replace("Telegram", "SSD", ignoreCase = true)
                .replace("TDLib", "SSD", ignoreCase = true)
        }
        val erroFormatadoSSD = remember(errorText) {
            errorText?.replace("Telegram", "SSD", ignoreCase = true)
                ?.replace("TDLib", "SSD", ignoreCase = true)
        }

        Column(Modifier.fillMaxSize().background(BgBlack).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Cloud, null, tint = Gold, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("NEXUS CLOUD $NEXUS_VERSION", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("SSD SOLUÇÕES", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(statusFormatadoSSD, color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
            if (erroFormatadoSSD != null) Text(erroFormatadoSSD, color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(32.dp))
            when (loginStep) {
                0 -> {
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it.replace("\n", "").take(25) },
                        label = { Text("Número SSD (********)", color = Color.Gray) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation('*'),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        TelegramAuth.errorText.value = null
                        val clean = phoneNumber.replace(" ", "").replace("-", "")
                        val cfg = TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray())
                        client.send(TdApi.SetAuthenticationPhoneNumber(clean, cfg), responseHandler)
                    }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ENVIAR CÓDIGO SSD", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                1 -> {
                    OutlinedTextField(
                        value = smsCode,
                        onValueChange = { smsCode = it.replace("\n", "").take(20) },
                        label = { Text("Código SMS / SSD", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationCode(smsCode.trim()), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("CONFIRMAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                2 -> {
                    OutlinedTextField(
                        value = password2FA,
                        onValueChange = { password2FA = it.replace("\n", "").take(64) },
                        label = { Text("Senha 2FA SSD", color = Color.Gray) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation('*'),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationPassword(password2FA), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ENTRAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }

    // 1B. PAINEL ADMIN SECRETO (ACESSADO COM USUÁRIO + PIN MESTRE)
    if (showAdminPanel) {
        PainelAdminSSD(
            context = context,
            client = client,
            onSair = {
                showAdminPanel = false
                vaultNameInput = ""
                vaultPinInput = ""
                vaultErrorMsg = null
            }
        )
        return
    }

    // 1C. TELA DE COFRE BLOQUEADO (TRAVA ANTI-OFFLINE ATIVA)
    if (vaultBlocked && !isSwitchingUser) {
        TelaCofreBloqueado(
            nomeUsuario = activeVaultUser,
            motivo = vaultBlockedReason,
            verificando = checkingUnlockStatus,
            onVerificarLiberacao = {
                checkingUnlockStatus = true
                verificarStatusRemotoCliente(context, client, activeVaultUser) { bloqueadoAgora, motivoAgora ->
                    scope.launch(Dispatchers.Main) {
                        checkingUnlockStatus = false
                        if (!bloqueadoAgora) {
                            vaultBlocked = false
                            vaultBlockedReason = ""
                            Toast.makeText(context, "Cofre liberado! Digite seu PIN para entrar.", Toast.LENGTH_LONG).show()
                        } else {
                            vaultBlockedReason = motivoAgora
                            Toast.makeText(context, "Cofre ainda bloqueado.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onTrocarUsuario = {
                vaultBlocked = false
                vaultErrorMsg = null
                vaultNameInput = ""
                vaultPinInput = ""
                isSwitchingUser = true
            }
        )
        return
    }

    // 2. ECRÃ DE COFRE HÍBRIDO (MS / CP): PEDE APENAS O PIN QUANDO JÁ HÁ UTILIZADOR SALVO
    if (!vaultUnlocked) {
        val pedirApenasPin = activeVaultUser.isNotBlank() && !isSwitchingUser

        Column(
            modifier = Modifier.fillMaxSize().background(BgBlack).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, Gold),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, null, tint = Gold, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("NEXUS CLOUD $NEXUS_VERSION", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("SSD SOLUÇÕES • PERFIL HÍBRIDO + IA", color = TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    if (procurandoPerfisNuvem) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Conectando à nuvem SSD...", color = Gold, fontSize = 11.sp)
                        }
                    }

                    if (pedirApenasPin) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Digite o seu PIN para desbloquear o cofre:",
                            color = TextGrey,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = vaultPinInput,
                            onValueChange = { if (it.length <= 6) vaultPinInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("PIN do Cofre", color = TextGrey) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Gold) },
                            visualTransformation = PasswordVisualTransformation('*'),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (vaultErrorMsg != null) {
                            Text(
                                vaultErrorMsg!!,
                                color = DangerRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                vaultErrorMsg = null
                                val pinLimpo = vaultPinInput.trim()
                                if (pinLimpo.length < 4) {
                                    vaultErrorMsg = "O PIN deve ter pelo menos 4 dígitos."
                                    return@Button
                                }

                                val stLocal = obterStatusCofreLocal(context, activeVaultUser)
                                if (stLocal.bloqueado) {
                                    isCheckingVault = true
                                    verificarStatusRemotoCliente(context, client, activeVaultUser) { blq, mot ->
                                        scope.launch(Dispatchers.Main) {
                                            isCheckingVault = false
                                            if (blq) {
                                                vaultPinInput = ""
                                                vaultBlockedReason = mot
                                                vaultBlocked = true
                                            } else {
                                                val pinLocal = obterPinCofreLocal(context, activeVaultUser)
                                                if (pinLocal != null && pinLocal == pinLimpo) {
                                                    vaultStorageMode = obterModoCofreLocal(context, activeVaultUser)
                                                    vaultPinInput = ""
                                                    vaultUnlocked = true
                                                } else {
                                                    vaultErrorMsg = "PIN incorreto."
                                                }
                                            }
                                        }
                                    }
                                    return@Button
                                }

                                val pinLocal = obterPinCofreLocal(context, activeVaultUser)
                                if (pinLocal != null && pinLocal == pinLimpo) {
                                    vaultStorageMode = obterModoCofreLocal(context, activeVaultUser)
                                    salvarCredenciaisCofreLocal(
                                        context = context,
                                        nomeOriginal = activeVaultUser,
                                        pin = pinLimpo,
                                        definirComoAtivo = true,
                                        modoArmazenamento = vaultStorageMode,
                                        canalChatId = obterChatIdCofreLocal(context, activeVaultUser)
                                    )
                                    vaultPinInput = ""
                                    vaultUnlocked = true
                                } else {
                                    isCheckingVault = true
                                    verificarOuRegistarCofreNuvem(
                                        context = context,
                                        client = client,
                                        nomeUsuario = activeVaultUser,
                                        pin = pinLimpo,
                                        modoCriarNovo = false,
                                        modoArmazenamentoEscolhido = vaultStorageMode
                                    ) { ok, nomeConfirmado, modoConfirmado, chatIdCanal, msg ->
                                        scope.launch(Dispatchers.Main) {
                                            isCheckingVault = false
                                            if (ok) {
                                                salvarCredenciaisCofreLocal(
                                                    context = context,
                                                    nomeOriginal = nomeConfirmado,
                                                    pin = pinLimpo,
                                                    definirComoAtivo = true,
                                                    modoArmazenamento = modoConfirmado,
                                                    canalChatId = chatIdCanal
                                                )
                                                activeVaultUser = nomeConfirmado
                                                vaultStorageMode = modoConfirmado
                                                vaultPinInput = ""
                                                vaultUnlocked = true
                                            } else if (msg.startsWith("BLOQUEADO::")) {
                                                vaultPinInput = ""
                                                vaultBlockedReason = msg.removePrefix("BLOQUEADO::")
                                                vaultBlocked = true
                                            } else {
                                                vaultErrorMsg = "PIN incorreto."
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isCheckingVault,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (isCheckingVault) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            } else {
                                Text("DESBLOQUEAR COFRE", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                            BotaoBiometriaPin(
                            context = context,
                            usuarioAtivo = activeVaultUser,
                            biometriaAtiva = biometriaAtiva,
                            vaultUnlocked = vaultUnlocked,
                            usuarioTrancouManualmente = usuarioTrancouManualmente,
                            isCheckingVault = isCheckingVault,
                            temPinLocal = obterPinCofreLocal(context, activeVaultUser) != null,
                            onBiometriaAprovada = {
                                vaultErrorMsg = null
                                val stLocalBio = obterStatusCofreLocal(context, activeVaultUser)
                                if (stLocalBio.bloqueado) {
                                    isCheckingVault = true
                                    verificarStatusRemotoCliente(context, client, activeVaultUser) { blq, mot ->
                                        scope.launch(Dispatchers.Main) {
                                            isCheckingVault = false
                                            if (blq) {
                                                vaultBlockedReason = mot
                                                vaultBlocked = true
                                            } else {
                                                vaultStorageMode = obterModoCofreLocal(context, activeVaultUser)
                                                usuarioTrancouManualmente = false
                                                vaultPinInput = ""
                                                vaultUnlocked = true
                                            }
                                        }
                                    }
                                } else {
                                    vaultStorageMode = obterModoCofreLocal(context, activeVaultUser)
                                    usuarioTrancouManualmente = false
                                    vaultPinInput = ""
                                    vaultUnlocked = true
                                }
                            },
                            onErroBiometria = { msgErro ->
                                vaultErrorMsg = msgErro
                            }
                        )

                        TextButton(
                            onClick = {
                                vaultErrorMsg = null
                                vaultPinInput = ""
                                vaultNameInput = ""
                                isSwitchingUser = true
                            }
                        ) {
                            Text("Entrar com outro utilizador / Criar Cofre", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { isCreatingNewVault = false; vaultErrorMsg = null },
                                colors = ButtonDefaults.buttonColors(containerColor = if (!isCreatingNewVault) Gold else SurfaceDark),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("JÁ TENHO COFRE", color = if (!isCreatingNewVault) BgBlack else TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { isCreatingNewVault = true; vaultErrorMsg = null },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isCreatingNewVault) Gold else SurfaceDark),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("CRIAR COFRE", color = if (isCreatingNewVault) BgBlack else TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (isCreatingNewVault) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "MODO DO PERFIL NA NUVEM SSD:",
                                color = Gold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { newVaultModeInput = "MS"; vaultErrorMsg = null },
                                    border = BorderStroke(1.5.dp, if (newVaultModeInput == "MS") Gold else Color.Gray),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (newVaultModeInput == "MS") Gold.copy(alpha = 0.16f) else SurfaceDark
                                    ),
                                    modifier = Modifier.weight(1f).height(52.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "MS",
                                            color = if (newVaultModeInput == "MS") Gold else Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "Mensagens Salvas",
                                            color = TextGrey,
                                            fontSize = 9.sp,
                                            maxLines = 1
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { newVaultModeInput = "CP"; vaultErrorMsg = null },
                                    border = BorderStroke(1.5.dp, if (newVaultModeInput == "CP") Gold else Color.Gray),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (newVaultModeInput == "CP") Gold.copy(alpha = 0.16f) else SurfaceDark
                                    ),
                                    modifier = Modifier.weight(1f).height(52.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "CP",
                                            color = if (newVaultModeInput == "CP") Gold else Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "Canal Privado",
                                            color = TextGrey,
                                            fontSize = 9.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (isCreatingNewVault) {
                                if (newVaultModeInput == "CP") "Modo CP: Cria um Canal Privado exclusivo no servidor para armazenar os ficheiros deste perfil."
                                else "Modo MS: Perfis ilimitados guardados nas Mensagens Salvas com isolamento por etiqueta e PIN."
                            } else {
                                "Digite o seu Nome e PIN para restaurar o seu cofre (o app reconhece automaticamente se é MS ou CP)."
                            },
                            color = TextGrey,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = vaultNameInput,
                            onValueChange = { vaultNameInput = it.replace("\n", "").take(40) },
                            label = { Text("Nome do Utilizador", color = TextGrey) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Person, null, tint = Gold) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = vaultPinInput,
                            onValueChange = { if (it.length <= 6) vaultPinInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("PIN (4 a 6 dígitos)", color = TextGrey) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Gold) },
                            visualTransformation = PasswordVisualTransformation('*'),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (vaultErrorMsg != null) {
                            Text(
                                vaultErrorMsg!!,
                                color = DangerRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                vaultErrorMsg = null
                                val nomeLimpo = vaultNameInput.trim()
                                val pinLimpo = vaultPinInput.trim()

                                if (!isCreatingNewVault && ehCredencialAdmin(nomeLimpo, pinLimpo)) {
                                    vaultNameInput = ""
                                    vaultPinInput = ""
                                    showAdminPanel = true
                                    return@Button
                                }

                                if (nomeLimpo.length < 3) {
                                    vaultErrorMsg = "O nome deve ter pelo menos 3 caracteres."
                                    return@Button
                                }
                                if (pinLimpo.length < 4) {
                                    vaultErrorMsg = "O PIN deve ter pelo menos 4 dígitos."
                                    return@Button
                                }

                                val stLocalDigitado = obterStatusCofreLocal(context, nomeLimpo)
                                val pinLocalExistente = obterPinCofreLocal(context, nomeLimpo)
                                if (!isCreatingNewVault && !stLocalDigitado.bloqueado && pinLocalExistente != null && pinLocalExistente == pinLimpo) {
                                    val modoSalvo = obterModoCofreLocal(context, nomeLimpo)
                                    val chatSalvo = obterChatIdCofreLocal(context, nomeLimpo)
                                    salvarCredenciaisCofreLocal(context, nomeLimpo, pinLimpo, true, modoSalvo, chatSalvo)
                                    activeVaultUser = nomeLimpo
                                    vaultStorageMode = modoSalvo
                                    isSwitchingUser = false
                                    vaultNameInput = ""
                                    vaultPinInput = ""
                                    vaultUnlocked = true
                                    Toast.makeText(context, "Cofre '$nomeLimpo' ($modoSalvo) desbloqueado!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                isCheckingVault = true
                                verificarOuRegistarCofreNuvem(
                                    context = context,
                                    client = client,
                                    nomeUsuario = nomeLimpo,
                                    pin = pinLimpo,
                                    modoCriarNovo = isCreatingNewVault,
                                    modoArmazenamentoEscolhido = newVaultModeInput
                                ) { ok, nomeConfirmado, modoConfirmado, chatIdCanal, msg ->
                                    scope.launch(Dispatchers.Main) {
                                        isCheckingVault = false
                                        if (ok) {
                                            salvarCredenciaisCofreLocal(
                                                context = context,
                                                nomeOriginal = nomeConfirmado,
                                                pin = pinLimpo,
                                                definirComoAtivo = true,
                                                modoArmazenamento = modoConfirmado,
                                                canalChatId = chatIdCanal
                                            )
                                            activeVaultUser = nomeConfirmado
                                            vaultStorageMode = modoConfirmado
                                            isSwitchingUser = false
                                            vaultBlocked = false
                                            vaultBlockedReason = ""
                                            vaultNameInput = ""
                                            vaultPinInput = ""
                                            vaultUnlocked = true
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        } else if (msg.startsWith("BLOQUEADO::")) {
                                            activeVaultUser = nomeConfirmado.ifBlank { nomeLimpo }
                                            isSwitchingUser = false
                                            vaultNameInput = ""
                                            vaultPinInput = ""
                                            vaultBlockedReason = msg.removePrefix("BLOQUEADO::")
                                            vaultBlocked = true
                                        } else {
                                            vaultErrorMsg = msg
                                        }
                                    }
                                }
                            },
                            enabled = !isCheckingVault,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (isCheckingVault) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            } else {
                                Text(
                                    if (isCreatingNewVault) "CRIAR COFRE ($newVaultModeInput)" else "ENTRAR E RESTAURAR COFRE",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (activeVaultUser.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            TextButton(
                                onClick = {
                                    vaultErrorMsg = null
                                    vaultPinInput = ""
                                    isSwitchingUser = false
                                    val stVoltar = obterStatusCofreLocal(context, activeVaultUser)
                                    if (stVoltar.bloqueado) {
                                        vaultBlocked = true
                                        vaultBlockedReason = stVoltar.motivo
                                    }
                                }
                            ) {
                                Text("Voltar para desbloqueio apenas com PIN", color = TextGrey, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        return
    }
// === FIM DA PARTE 3 DE 4 (COLE A PARTE 4 LOGO ABAIXO DESTA LINHA) ===
// === CONTINUAÇÃO: PARTE 4 DE 4 (COLE LOGO ABAIXO DA PARTE 3 - v2.2) ===

    // === JANELA DE RESULTADO DA NEXUS IA (OCR + EXTRAÇÃO PIX / BOLETOS / VALORES) ===
    if (arquivoAnaliseIA != null) {
        val arqIA = arquivoAnaliseIA!!
        DialogAnaliseIA(
            context = context,
            nomeArquivo = arqIA.nomeArquivo,
            analisando = analisandoIA,
            resultado = resultadoIA,
            onReanalisar = { abrirAnaliseIA(arqIA, forcarReleitura = true) },
            onDismiss = {
                arquivoAnaliseIA = null
                analisandoIA = false
            }
        )
    }

    // === JANELA DE ALERTA DETALHADO CASO O UPLOAD RETORNE ERRO ===
    if (erroUploadDetalhado != null) {
        Dialog(onDismissRequest = { erroUploadDetalhado = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.5.dp, DangerRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(22.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = DangerRed, modifier = Modifier.size(46.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("FALHA NO ENVIO SSD", color = Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = erroUploadDetalhado ?: "",
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { erroUploadDetalhado = null },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("FECHAR", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // === JANELA DE CACHE DO SSD NEXUS (COM AUTO-LIMPEZA DE TEMPORÁRIOS POR QUANTIDADE CONFIGURÁVEL) ===
    if (showCacheDialog) {
        var statsCache by remember { mutableStateOf(calcularUsoCacheLocal(context)) }
        val (bytesCache, qtdCapas, qtdTemps) = statsCache

        val configInicial = remember { obterConfigAutoLimpezaTemp(context) }
        var autoLimpezaAtiva by remember { mutableStateOf(configInicial.first) }
        var limiteTempInput by remember { mutableStateOf(configInicial.second.toString()) }

        Dialog(onDismissRequest = { showCacheDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.5.dp, Gold),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(22.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Storage, null, tint = Gold, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("CACHE DO SSD NEXUS", color = Gold, fontWeight = FontWeight.Black, fontSize = 19.sp)
                    Text("Gestão de Miniaturas e Arquivos Locais", color = TextGrey, fontSize = 11.sp)

                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                            .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ESPAÇO OCUPADO PELO CACHE", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(formatarTamanhoNuvem(bytesCache), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$qtdCapas", color = Gold, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                Text("Capas (.jpg)", color = TextGrey, fontSize = 10.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$qtdTemps", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                Text("Temporários", color = TextGrey, fontSize = 10.sp)
                            }
                        }
                    }

                    // === REGRA DE LIMPEZA AUTOMÁTICA DE TEMPORÁRIOS POR QUANTIDADE ===
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                            .border(1.dp, if (autoLimpezaAtiva) Gold else TextGrey.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("AUTO-LIMPAR TEMPORÁRIOS", color = Gold, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                Text(
                                    "Apaga só arquivos temporários ao atingir a quantidade abaixo (mantém capas .jpg).",
                                    color = TextGrey,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = autoLimpezaAtiva,
                                onCheckedChange = { novoEstado ->
                                    autoLimpezaAtiva = novoEstado
                                    val qtd = limiteTempInput.toIntOrNull()?.coerceIn(1, 99999) ?: 50
                                    salvarConfigAutoLimpezaTemp(context, novoEstado, qtd)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BgBlack,
                                    checkedTrackColor = Gold,
                                    uncheckedThumbColor = TextGrey,
                                    uncheckedTrackColor = SurfaceDarker
                                )
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = limiteTempInput,
                                onValueChange = { txt ->
                                    limiteTempInput = txt.filter { it.isDigit() }.take(5)
                                },
                                label = { Text("Qtd. máxima", color = TextGrey, fontSize = 11.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Gold,
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val limiteValido = limiteTempInput.toIntOrNull()?.coerceIn(1, 99999)
                                    if (limiteValido == null) {
                                        Toast.makeText(context, "Digite uma quantidade válida (ex: 30).", Toast.LENGTH_SHORT).show()
                                    } else {
                                        limiteTempInput = limiteValido.toString()
                                        salvarConfigAutoLimpezaTemp(context, autoLimpezaAtiva, limiteValido)
                                        val (limpouAgora, bytesLib) = verificarELimparTempAutomatico(context)
                                        statsCache = calcularUsoCacheLocal(context)
                                        val msg = if (limpouAgora) {
                                            "Limite ($limiteValido) atingido! Temporários limpos (${formatarTamanhoNuvem(bytesLib)} liberados)."
                                        } else if (autoLimpezaAtiva) {
                                            "Auto-limpeza ativada ao atingir $limiteValido temporários!"
                                        } else {
                                            "Limite salvo ($limiteValido), mas a auto-limpeza está desativada."
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                modifier = Modifier.height(54.dp)
                            ) {
                                Text("SALVAR", color = BgBlack, fontWeight = FontWeight.Black, fontSize = 11.sp)
                            }
                        }

                        val limiteExib = limiteTempInput.toIntOrNull() ?: 50
                        Text(
                            text = if (autoLimpezaAtiva) "Status: $qtdTemps / $limiteExib temporários acumulados"
                            else "Status: Desativado (atual: $qtdTemps temporários)",
                            color = if (autoLimpezaAtiva && qtdTemps >= limiteExib) DangerRed else TextGrey,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val qtdRemovida = NexusThumbCache.limparCapasDeVideos(context, arquivos)
                            statsCache = calcularUsoCacheLocal(context)
                            showCacheDialog = false
                            Toast.makeText(context, "Regenerando $qtdRemovida capa(s) de vídeo com brilho otimizado...", Toast.LENGTH_SHORT).show()
                            processarFilaMiniaturasNuvem(arquivos)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = BgBlack, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("RECARREGAR CAPAS DE VÍDEOS", color = BgBlack, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val liberado = limparCacheUploadsTemporarios(context)
                            statsCache = calcularUsoCacheLocal(context)
                            Toast.makeText(
                                context,
                                "Cache temporário limpo (${formatarTamanhoNuvem(liberado)} liberados)!",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        border = BorderStroke(1.dp, Gold),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Gold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("LIMPAR TEMPORÁRIOS AGORA", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            NexusThumbCache.limparTodoCacheLocal(context)
                            statsCache = calcularUsoCacheLocal(context)
                            showCacheDialog = false
                            Toast.makeText(context, "Cache SSD Nexus limpo! As capas recarregarão ao navegar.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Default.DeleteForever, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("LIMPAR TODO O CACHE LOCAL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showCacheDialog = false }) {
                        Text("FECHAR", color = TextGrey, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // === JANELA DE OPÇÕES RÁPIDAS DA PASTA ===
    if (pastaSelecionadaMenu != null) {
        val nomeP = pastaSelecionadaMenu!!
        val qtdItens = arquivos.count { mapaPastas[it.dataUpload]?.equals(nomeP, ignoreCase = true) == true }
        Dialog(onDismissRequest = { pastaSelecionadaMenu = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, Gold),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(nomeP, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("$qtdItens ficheiro(s) nesta pasta", color = TextGrey, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            pastaSelecionadaMenu = null
                            selectedTab = 1
                            pastaAberta = nomeP
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, tint = Gold)
                        Spacer(Modifier.width(8.dp))
                        Text("ABRIR PASTA", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            pastaSelecionadaMenu = null
                            novoNomePastaInput = nomeP
                            pastaParaRenomear = nomeP
                        },
                        border = BorderStroke(1.dp, Gold),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, tint = Gold)
                        Spacer(Modifier.width(8.dp))
                        Text("RENOMEAR PASTA", color = Gold, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            pastaSelecionadaMenu = null
                            pastaParaApagar = nomeP
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("APAGAR PASTA", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { pastaSelecionadaMenu = null }) {
                        Text("FECHAR", color = TextGrey, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // === JANELA PARA RENOMEAR PASTA (COM SINCRONIZAÇÃO NA NUVEM SSD) ===
    if (pastaParaRenomear != null) {
        val nomeAntigo = pastaParaRenomear!!
        Dialog(onDismissRequest = { pastaParaRenomear = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, Gold),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("RENOMEAR PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Pasta atual: $nomeAntigo", color = TextGrey, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = novoNomePastaInput,
                        onValueChange = { novoNomePastaInput = it.replace("\n", "").take(60) },
                        label = { Text("Novo nome da pasta", color = TextGrey) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pastaParaRenomear = null },
                            border = BorderStroke(1.dp, TextGrey),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CANCELAR", color = Color.White)
                        }
                        Button(
                            onClick = {
                                val limpo = novoNomePastaInput.trim()
                                if (limpo.isNotBlank() && !limpo.equals(nomeAntigo, ignoreCase = true)) {
                                    renomearPastaCompleta(context, activeVaultUser, nomeAntigo, limpo, vm)
                                    registrarAcaoPastaNaNuvem(context, client, activeVaultUser, "RENOMEAR", nomeAntigo, limpo)
                                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                    mapaPastas = carregarVinculosPastas(context)
                                    if (pastaAberta?.equals(nomeAntigo, ignoreCase = true) == true) {
                                        pastaAberta = limpo
                                    }
                                    Toast.makeText(context, "Pasta renomeada para '$limpo'!", Toast.LENGTH_SHORT).show()
                                }
                                pastaParaRenomear = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SALVAR", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // === JANELA DE CONFIRMAÇÃO PARA APAGAR PASTA (COM SINCRONIZAÇÃO NA NUVEM SSD) ===
    if (pastaParaApagar != null) {
        val nomePastaDel = pastaParaApagar!!
        val arquivosDaPastaDel = remember(arquivos, mapaPastas, nomePastaDel) {
            arquivos.filter { mapaPastas[it.dataUpload]?.equals(nomePastaDel, ignoreCase = true) == true }
        }
        Dialog(onDismissRequest = { if (!isDeletingFolder) pastaParaApagar = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, DangerRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderDelete, null, tint = DangerRed, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("ELIMINAR PASTA?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(nomePastaDel, color = Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (arquivosDaPastaDel.isEmpty()) "Esta pasta está vazia."
                        else "Esta pasta contém ${arquivosDaPastaDel.size} ficheiro(s). Escolha como deseja apagar:",
                        color = TextGrey,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))

                    OutlinedButton(
                        onClick = {
                            isDeletingFolder = true
                            registrarAcaoPastaNaNuvem(context, client, activeVaultUser, "APAGAR", nomePastaDel)
                            eliminarPastaCompleta(context, client, activeVaultUser, nomePastaDel, false, arquivosDaPastaDel, vm) { msg ->
                                scope.launch(Dispatchers.Main) {
                                    isDeletingFolder = false
                                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                    mapaPastas = carregarVinculosPastas(context)
                                    if (pastaAberta?.equals(nomePastaDel, ignoreCase = true) == true) pastaAberta = null
                                    pastaParaApagar = null
                                    cloudBannerText = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isDeletingFolder,
                        border = BorderStroke(1.dp, Gold),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text(
                            if (arquivosDaPastaDel.isEmpty()) "APAGAR PASTA" else "APAGAR SÓ A PASTA (MANTER FICHEIROS)",
                            color = Gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    if (arquivosDaPastaDel.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                isDeletingFolder = true
                                registrarAcaoPastaNaNuvem(context, client, activeVaultUser, "APAGAR", nomePastaDel)
                                eliminarPastaCompleta(context, client, activeVaultUser, nomePastaDel, true, arquivosDaPastaDel, vm) { msg ->
                                    scope.launch(Dispatchers.Main) {
                                        isDeletingFolder = false
                                        pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                        mapaPastas = carregarVinculosPastas(context)
                                        mapaDonos = carregarDonosFicheiros(context)
                                        if (pastaAberta?.equals(nomePastaDel, ignoreCase = true) == true) pastaAberta = null
                                        pastaParaApagar = null
                                        cloudBannerText = msg
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isDeletingFolder,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            if (isDeletingFolder) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.DeleteForever, null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("APAGAR PASTA + FICHEIROS (${arquivosDaPastaDel.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { if (!isDeletingFolder) pastaParaApagar = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CANCELAR", color = TextGrey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // === JANELA SOBRE (DESENVOLVEDOR E SSD SOLUÇÕES 2026) ===
    if (showAboutDialog) {
        Dialog(onDismissRequest = { showAboutDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.5.dp, Gold),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Cloud, null, tint = Gold, modifier = Modifier.size(60.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("NEXUS CLOUD $NEXUS_VERSION", color = Gold, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text("Cofres Híbridos (MS & CP) • Segundo Plano • Nexus IA", color = TextGrey, fontSize = 11.sp, textAlign = TextAlign.Center)

                    Spacer(Modifier.height(20.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                            .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DESENVOLVEDOR", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Josimar Sousa", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(14.dp))
                        Divider(color = SurfaceDarker, thickness = 1.dp)
                        Spacer(Modifier.height(14.dp))
                        Text("EMPRESA & TECNOLOGIA", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("SSD Soluções 2026", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "© 2026 SSD Soluções • Todos os direitos reservados.",
                        color = TextGrey,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { showAboutDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("FECHAR", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // JANELA DE CONFIRMAÇÃO PARA DELETAR / REMOVER FICHEIROS (1 OU LOTE)
    if (filesToDeleteBatch.isNotEmpty()) {
        val listaParaDeletar = filesToDeleteBatch
        val tituloDel = if (listaParaDeletar.size == 1) listaParaDeletar.first().nomeArquivo else "${listaParaDeletar.size} ficheiros selecionados"
        Dialog(onDismissRequest = { if (!isDeletingFile) filesToDeleteBatch = emptyList() }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, DangerRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DeleteForever, null, tint = DangerRed, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("REMOVER OU DELETAR?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tituloDel,
                        color = Gold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Escolha se deseja apagar também da Nuvem SSD ($vaultStorageMode) ou remover apenas da galeria do aplicativo.",
                        color = TextGrey,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            isDeletingFile = true
                            val idsApagados = listaParaDeletar.map { it.id }.toSet()
                            eliminarMultiplosFicheiros(context, client, activeVaultUser, listaParaDeletar, true, vm) { msg ->
                                scope.launch(Dispatchers.Main) {
                                    isDeletingFile = false
                                    mapaPastas = carregarVinculosPastas(context)
                                    mapaDonos = carregarDonosFicheiros(context)
                                    if (viewerFile != null && idsApagados.contains(viewerFile!!.id)) viewerFile = null
                                    filesToDeleteBatch = emptyList()
                                    selectedFileIds = emptySet()
                                    isSelectionMode = false
                                    cloudBannerText = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isDeletingFile,
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        if (isDeletingFile) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.DeleteForever, null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("DELETAR TUDO (APP + NUVEM $vaultStorageMode)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            isDeletingFile = true
                            val idsApagados = listaParaDeletar.map { it.id }.toSet()
                            eliminarMultiplosFicheiros(context, client, activeVaultUser, listaParaDeletar, false, vm) { msg ->
                                scope.launch(Dispatchers.Main) {
                                    isDeletingFile = false
                                    mapaPastas = carregarVinculosPastas(context)
                                    mapaDonos = carregarDonosFicheiros(context)
                                    if (viewerFile != null && idsApagados.contains(viewerFile!!.id)) viewerFile = null
                                    filesToDeleteBatch = emptyList()
                                    selectedFileIds = emptySet()
                                    isSelectionMode = false
                                    cloudBannerText = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isDeletingFile,
                        border = BorderStroke(1.dp, Gold),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("REMOVER SÓ DO APP", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { if (!isDeletingFile) filesToDeleteBatch = emptyList() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CANCELAR", color = TextGrey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // JANELA AUTOMÁTICA DE NOVOS FICHEIROS NAS PASTAS DE SINCRONIZAÇÃO
    if (showNovosSyncDialog && novosFicheirosSync.isNotEmpty()) {
        Dialog(onDismissRequest = { showNovosSyncDialog = false }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SurfaceDarker,
                border = BorderStroke(1.dp, Gold),
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = Gold, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NOVOS FICHEIROS ($vaultStorageMode)", color = Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(
                                "${novosFicheirosSync.size} ficheiro(s) novo(s) nas pastas sincronizadas",
                                color = TextGrey,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selecionadosSync = if (selecionadosSync.size == novosFicheirosSync.size) {
                                emptySet()
                            } else {
                                novosFicheirosSync.map { it.uri.toString() }.toSet()
                            }
                        }) {
                            Text(
                                if (selecionadosSync.size == novosFicheirosSync.size) "Desmarcar todos" else "Selecionar todos",
                                color = Gold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = organizarPorPastaSync,
                                onCheckedChange = { organizarPorPastaSync = it },
                                colors = CheckboxDefaults.colors(checkedColor = Gold, checkmarkColor = BgBlack)
                            )
                            Text("Manter pasta", color = Color.White, fontSize = 11.sp)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        novosFicheirosSync.forEach { item ->
                            val chaveUri = item.uri.toString()
                            val marcado = selecionadosSync.contains(chaveUri)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selecionadosSync = if (marcado) selecionadosSync - chaveUri else selecionadosSync + chaveUri
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = marcado,
                                    onCheckedChange = {
                                        selecionadosSync = if (it) selecionadosSync + chaveUri else selecionadosSync - chaveUri
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Gold, checkmarkColor = BgBlack)
                                )
                                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                    Text(
                                        item.nome,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "📁 ${item.nomePastaOrigem} • ${formatarTamanhoNuvem(item.tamanho)}",
                                        color = TextGrey,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showNovosSyncDialog = false },
                            border = BorderStroke(1.dp, TextGrey),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("AGORA NÃO", color = Color.White, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                val escolhidos = novosFicheirosSync.filter { selecionadosSync.contains(it.uri.toString()) }
                                showNovosSyncDialog = false
                                executarUploadsNovosSync(escolhidos, organizarPorPastaSync)
                            },
                            enabled = selecionadosSync.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.weight(1.3f).height(48.dp)
                        ) {
                            Text(
                                "UPLOAD (${selecionadosSync.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (fileToRename != null) {
        val arqR = fileToRename!!
        val extOriginal = arqR.nomeArquivo.substringAfterLast('.', "")
        Dialog(onDismissRequest = { fileToRename = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("RENOMEAR FICHEIRO", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    if (extOriginal.isNotBlank()) {
                        Text("Formato mantido automaticamente: .$extOriginal", color = TextGrey, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = novoNomeInput,
                        onValueChange = { novoNomeInput = it.replace("\n", "").take(100) },
                        label = { Text("Novo nome", color = TextGrey) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { fileToRename = null },
                            border = BorderStroke(1.dp, TextGrey),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CANCELAR", color = Color.White)
                        }
                        Button(
                            onClick = {
                                val limpo = novoNomeInput.trim()
                                if (limpo.isNotBlank()) {
                                    val nomeFinal = if (extOriginal.isNotBlank() && !limpo.endsWith(".$extOriginal", ignoreCase = true)) {
                                        "$limpo.$extOriginal"
                                    } else {
                                        limpo
                                    }
                                    vm.renomearArquivo(arqR, nomeFinal)
                                    marcarFicheiroComoSincronizado(context, activeVaultUser, nomeFinal, arqR.tamanhoBytes)
                                    if (viewerFile?.dataUpload == arqR.dataUpload) {
                                        viewerFile = arqR.copy(nomeArquivo = nomeFinal)
                                    }
                                    Toast.makeText(context, "Renomeado para $nomeFinal", Toast.LENGTH_SHORT).show()
                                }
                                fileToRename = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SALVAR", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (pendingUploadUris.isNotEmpty()) {
        val listaAtual = pendingUploadUris
        Dialog(onDismissRequest = { pendingUploadUris = emptyList() }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("GUARDAR ${listaAtual.size} FICHEIRO(S) [$vaultStorageMode]", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { pendingUploadUris = emptyList(); executarUploads(listaAtual, "") }, border = BorderStroke(1.dp, TextGrey), modifier = Modifier.fillMaxWidth()) {
                        Text("Galeria Geral (Sem pasta)", color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    pastas.forEach { p ->
                        Button(onClick = { pendingUploadUris = emptyList(); executarUploads(listaAtual, p.nomePasta) }, colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(p.nomePasta, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // MOVER PARA PASTA (SUPORTA 1 OU MÚLTIPLOS FICHEIROS E REGISTA NA NUVEM SSD)
    if (filesToMoveBatch.isNotEmpty()) {
        val listaMover = filesToMoveBatch
        Dialog(onDismissRequest = { filesToMoveBatch = emptyList() }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        if (listaMover.size == 1) "MOVER PARA PASTA" else "MOVER ${listaMover.size} FICHEIROS",
                        color = Gold,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            listaMover.forEach { salvarVinculoPasta(context, it.dataUpload, "") }
                            registrarMovimentacaoNaNuvem(context, client, activeVaultUser, "", listaMover)
                            mapaPastas = carregarVinculosPastas(context)
                            filesToMoveBatch = emptyList()
                            selectedFileIds = emptySet()
                            isSelectionMode = false
                            Toast.makeText(context, "Movido(s) para a Galeria Geral!", Toast.LENGTH_SHORT).show()
                        },
                        border = BorderStroke(1.dp, TextGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Remover da Pasta (Galeria Geral)", color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (pastas.isEmpty()) {
                        Text("Nenhuma pasta criada ainda.", color = TextGrey, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        pastas.forEach { p ->
                            Button(
                                onClick = {
                                    associarPastaAoUsuario(context, activeVaultUser, p.nomePasta)
                                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                    listaMover.forEach { salvarVinculoPasta(context, it.dataUpload, p.nomePasta) }
                                    registrarMovimentacaoNaNuvem(context, client, activeVaultUser, p.nomePasta, listaMover)
                                    mapaPastas = carregarVinculosPastas(context)
                                    filesToMoveBatch = emptyList()
                                    selectedFileIds = emptySet()
                                    isSelectionMode = false
                                    Toast.makeText(context, "Movido(s) para a pasta '${p.nomePasta}'!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(p.nomePasta, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // === VISUALIZADOR EM ECRÃ INTEIRO v2.2: DESLIZAR ENTRE FOTOS/VÍDEOS + ZOOM + NEXUS IA ===
    if (viewerFile != null) {
        val cur = viewerFile!!
        val completoCacheViewer = NexusThumbCache.obterArquivoCompleto(cur)
        val curMediaPath = completoCacheViewer
            .ifBlank { cur.caminhoMiniatura ?: "" }
            .ifBlank { NexusThumbCache.obterMiniatura(cur) }
        val ext = cur.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val isImage = ehArquivoImagem(cur.nomeArquivo)
        val isVideo = ehArquivoVideo(cur.nomeArquivo)
        val isPdf = ext == "PDF"
        val isText = ext in listOf("TXT", "CSV", "LOG", "JSON", "XML", "MD")
        val suportaIA = NexusAI.suportaLeituraIA(cur.nomeArquivo)
        val videoProntoLocal = isVideo && arquivoCompletoExisteLocalmente(curMediaPath)
        val thumbVideoPreview = NexusThumbCache.obterMiniatura(cur)

        val indiceViewer = remember(cur.id, cur.dataUpload, listaOrdenadaViewer) {
            listaOrdenadaViewer.indexOfFirst {
                it.id == cur.id || (it.dataUpload == cur.dataUpload && it.nomeArquivo == cur.nomeArquivo)
            }
        }
        val temAnterior = indiceViewer > 0
        val temProximo = indiceViewer != -1 && indiceViewer < listaOrdenadaViewer.lastIndex

        val viewerDownloadPct by NexusTransfer.progress.collectAsState()

        fun navegarViewer(delta: Int) {
            if (listaOrdenadaViewer.size <= 1 || indiceViewer == -1) return
            val novoIdx = indiceViewer + delta
            if (novoIdx !in listaOrdenadaViewer.indices) return
            val proximo = listaOrdenadaViewer[novoIdx]

            val compLocal = NexusThumbCache.obterArquivoCompleto(proximo)
            if (compLocal.isNotBlank() && arquivoCompletoExisteLocalmente(compLocal)) {
                viewerFile = proximo.copy(caminhoMiniatura = compLocal)
                return
            }

            val thumbLocal = NexusThumbCache.obterMiniatura(proximo)
            if (!ehArquivoVideo(proximo.nomeArquivo) && thumbLocal.isNotBlank()) {
                val limpo = if (thumbLocal.startsWith("file://")) Uri.parse(thumbLocal).path ?: thumbLocal.removePrefix("file://") else thumbLocal
                val fOrig = File(limpo.removeSuffix("_frame.jpg"))
                if (fOrig.exists() && fOrig.length() > 256L) {
                    viewerFile = proximo.copy(caminhoMiniatura = Uri.fromFile(fOrig).toString())
                    return
                }
            }

            // Troca imediatamente para o próximo item e baixa o arquivo completo da nuvem com prioridade
            viewerFile = proximo
            garantirFicheiroLocal(context, client, activeVaultUser, proximo, vm) { arqPronto ->
                scope.launch(Dispatchers.Main) {
                    if (viewerFile?.id == proximo.id && viewerFile?.dataUpload == proximo.dataUpload) {
                        viewerFile = arqPronto
                    }
                }
            }
        }

        var zoomScale by remember(cur.id, cur.dataUpload) { mutableFloatStateOf(1f) }
        var zoomOffset by remember(cur.id, cur.dataUpload) { mutableStateOf(Offset.Zero) }
        val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
            val novoScale = (zoomScale * zoomChange).coerceIn(1f, 5f)
            zoomScale = novoScale
            if (novoScale > 1f) {
                zoomOffset += panChange
            } else {
                zoomOffset = Offset.Zero
            }
        }

        var videoViewRef by remember(cur.id, curMediaPath) { mutableStateOf<android.widget.VideoView?>(null) }
        var isPlaying by remember(cur.id, curMediaPath) { mutableStateOf(true) }
        var currentPos by remember(cur.id, curMediaPath) { mutableIntStateOf(0) }
        var duration by remember(cur.id, curMediaPath) { mutableIntStateOf(0) }

        var pdfPages by remember(cur.id, curMediaPath) { mutableStateOf<List<Bitmap>>(emptyList()) }
        var isLoadingDoc by remember(cur.id, curMediaPath) { mutableStateOf(false) }
        var textContent by remember(cur.id, curMediaPath) { mutableStateOf<String?>(null) }

        LaunchedEffect(cur.id, curMediaPath, ext) {
            if (isPdf && arquivoCompletoExisteLocalmente(curMediaPath)) {
                isLoadingDoc = true
                pdfPages = withContext(Dispatchers.IO) { renderizarPaginasPdf(curMediaPath) }
                isLoadingDoc = false
            } else if (isText && arquivoCompletoExisteLocalmente(curMediaPath)) {
                isLoadingDoc = true
                textContent = withContext(Dispatchers.IO) { lerTextoDocumento(curMediaPath) }
                isLoadingDoc = false
            }
        }

        LaunchedEffect(videoViewRef, isPlaying) {
            while (videoViewRef != null) {
                try {
                    val vv = videoViewRef!!
                    if (vv.isPlaying) {
                        currentPos = vv.currentPosition
                        val dur = vv.duration
                        if (dur > 0) duration = dur
                    }
                    isPlaying = vv.isPlaying
                } catch (_: Exception) {}
                delay(250)
            }
        }

        Dialog(onDismissRequest = { viewerFile = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
                // Área central de mídia com gesto de deslizar horizontal (ativo quando sem zoom)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(cur.id, cur.dataUpload, listaOrdenadaViewer.size, zoomScale) {
                            if (zoomScale <= 1.05f && listaOrdenadaViewer.size > 1) {
                                awaitPointerEventScope {
                                    var startX = 0f
                                    var startY = 0f
                                    var tracking = false
                                    var disparouSwipe = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pointers = event.changes
                                        if (pointers.size > 1) {
                                            tracking = false
                                            disparouSwipe = false
                                            continue
                                        }
                                        val change = pointers.firstOrNull() ?: continue
                                        if (change.pressed && !tracking) {
                                            tracking = true
                                            disparouSwipe = false
                                            startX = change.position.x
                                            startY = change.position.y
                                        } else if (change.pressed && tracking && !disparouSwipe) {
                                            val dx = change.position.x - startX
                                            val dy = change.position.y - startY
                                            if (kotlin.math.abs(dx) > 95f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f) {
                                                disparouSwipe = true
                                                change.consume()
                                                if (dx < 0f) navegarViewer(1) else navegarViewer(-1)
                                            }
                                        } else if (!change.pressed) {
                                            tracking = false
                                            disparouSwipe = false
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isImage -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(cur.id, cur.dataUpload) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                if (zoomScale > 1.05f) {
                                                    zoomScale = 1f
                                                    zoomOffset = Offset.Zero
                                                } else {
                                                    zoomScale = 2.5f
                                                }
                                            }
                                        )
                                    }
                                    .transformable(state = transformableState),
                                contentAlignment = Alignment.Center
                            ) {
                                if (curMediaPath.isNotBlank()) {
                                    AsyncImage(
                                        model = Uri.parse(curMediaPath),
                                        contentDescription = cur.nomeArquivo,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = zoomScale,
                                                scaleY = zoomScale,
                                                translationX = zoomOffset.x,
                                                translationY = zoomOffset.y
                                            ),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    CircularProgressIndicator(color = Gold)
                                }
                            }
                        }
                        isVideo -> {
                            if (videoProntoLocal) {
                                androidx.compose.runtime.key(cur.id, curMediaPath) {
                                    AndroidView(
                                        factory = { ctx ->
                                            android.widget.VideoView(ctx).apply {
                                                setVideoURI(Uri.parse(curMediaPath))
                                                setOnPreparedListener { mp ->
                                                    duration = mp.duration.coerceAtLeast(0)
                                                    start()
                                                    isPlaying = true
                                                }
                                                setOnCompletionListener {
                                                    isPlaying = false
                                                    currentPos = duration
                                                }
                                                videoViewRef = this
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize().align(Alignment.Center)
                                    )
                                }
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (thumbVideoPreview.isNotBlank() && miniaturaExisteLocalmente(thumbVideoPreview, cur.nomeArquivo)) {
                                        AsyncImage(
                                            model = obterUriCapaMidia(thumbVideoPreview),
                                            contentDescription = cur.nomeArquivo,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Gold)
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = if (viewerDownloadPct != null) "Baixando vídeo (${viewerDownloadPct}%)..." else "Carregando vídeo da nuvem...",
                                            color = Gold,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                        isPdf -> {
                            if (isLoadingDoc || !arquivoCompletoExisteLocalmente(curMediaPath)) {
                                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Gold)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = if (viewerDownloadPct != null) "Baixando PDF (${viewerDownloadPct}%)..." else "A renderizar páginas do PDF...",
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                }
                            } else if (pdfPages.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 72.dp, bottom = 72.dp)
                                        .pointerInput(cur.id) {
                                            detectTapGestures(
                                                onDoubleTap = {
                                                    if (zoomScale > 1.05f) {
                                                        zoomScale = 1f
                                                        zoomOffset = Offset.Zero
                                                    } else {
                                                        zoomScale = 2.2f
                                                    }
                                                }
                                            )
                                        }
                                        .transformable(state = transformableState)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 12.dp)
                                            .graphicsLayer(
                                                scaleX = zoomScale,
                                                scaleY = zoomScale,
                                                translationX = zoomOffset.x,
                                                translationY = zoomOffset.y
                                            ),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        pdfPages.forEachIndexed { idx, paginaBmp ->
                                            Text(
                                                "Página ${idx + 1} de ${pdfPages.size}",
                                                color = Gold,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                            )
                                            Image(
                                                bitmap = paginaBmp.asImageBitmap(),
                                                contentDescription = "Página ${idx + 1}",
                                                contentScale = ContentScale.FillWidth,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.White, RoundedCornerShape(6.dp))
                                            )
                                            Spacer(Modifier.height(12.dp))
                                        }
                                    }
                                }
                            } else {
                                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(64.dp))
                                    Spacer(Modifier.height(16.dp))
                                    Text(cur.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    Spacer(Modifier.height(20.dp))
                                    Button(
                                        onClick = { abrirFicheiroExterno(context, curMediaPath, cur.nomeArquivo) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Gold)
                                    ) {
                                        Icon(Icons.Default.OpenInNew, null, tint = BgBlack)
                                        Spacer(Modifier.width(8.dp))
                                        Text("ABRIR NO LEITOR DE PDF", color = BgBlack, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        isText && !textContent.isNullOrBlank() -> {
                            val tamanhoFonteTexto = (13f * zoomScale).coerceIn(11f, 32f).sp
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 76.dp, bottom = 76.dp, start = 16.dp, end = 16.dp)
                                    .background(SurfaceDark, RoundedCornerShape(12.dp))
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(textContent!!, color = Color.White, fontSize = tamanhoFonteTexto)
                            }
                        }
                        else -> {
                            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.InsertDriveFile, null, tint = Gold, modifier = Modifier.size(72.dp))
                                Spacer(Modifier.height(16.dp))
                                Text(cur.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(8.dp))
                                Text("Tamanho: ${formatarTamanhoNuvem(cur.tamanhoBytes)}", color = TextGrey, fontSize = 12.sp)
                                Spacer(Modifier.height(24.dp))
                                Button(
                                    onClick = { abrirFicheiroExterno(context, curMediaPath, cur.nomeArquivo) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                    modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
                                ) {
                                    Icon(Icons.Default.OpenInNew, null, tint = BgBlack)
                                    Spacer(Modifier.width(8.dp))
                                    Text("ABRIR DOCUMENTO", color = BgBlack, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Aviso discreto quando estiver baixando a versão HD da foto ao deslizar
                    if (isImage && viewerDownloadPct != null && !arquivoCompletoExisteLocalmente(completoCacheViewer)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 74.dp)
                                .background(BgBlack.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                                .border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "Carregando HD (${viewerDownloadPct}%)...",
                                color = Gold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Setas laterais discretas para alternar com 1 toque além do deslizar
                    if (zoomScale <= 1.05f && temAnterior) {
                        IconButton(
                            onClick = { navegarViewer(-1) },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 6.dp)
                                .size(38.dp)
                                .background(BgBlack.copy(alpha = 0.48f), CircleShape)
                        ) {
                            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Anterior", tint = Gold)
                        }
                    }
                    if (zoomScale <= 1.05f && temProximo) {
                        IconButton(
                            onClick = { navegarViewer(1) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 6.dp)
                                .size(38.dp)
                                .background(BgBlack.copy(alpha = 0.48f), CircleShape)
                        ) {
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Próximo", tint = Gold)
                        }
                    }
                }

                // Barra superior do visualizador (com contador de posição da galeria)
                Row(Modifier.fillMaxWidth().background(BgBlack.copy(alpha = 0.85f)).padding(12.dp).align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewerFile = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(cur.nomeArquivo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val infoSubtitulo = if (indiceViewer != -1 && listaOrdenadaViewer.size > 1) {
                            "${indiceViewer + 1} / ${listaOrdenadaViewer.size} • ${formatarDataNuvem(cur.dataUpload)}"
                        } else {
                            formatarDataNuvem(cur.dataUpload)
                        }
                        Text(infoSubtitulo, color = TextGrey, fontSize = 10.sp, maxLines = 1)
                    }
                    if (suportaIA) {
                        IconButton(onClick = { abrirAnaliseIA(cur) }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Ler com Nexus IA", tint = Gold)
                        }
                    }
                    IconButton(onClick = {
                        novoNomeInput = cur.nomeArquivo.substringBeforeLast('.')
                        fileToRename = cur
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Renomear", tint = Color.White)
                    }
                    IconButton(onClick = { compartilharFicheiroNativo(context, curMediaPath, cur.nomeArquivo) }) {
                        Icon(Icons.Default.Share, contentDescription = "Compartilhar", tint = Gold)
                    }
                    IconButton(onClick = { filesToDeleteBatch = listOf(cur) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Deletar", tint = DangerRed)
                    }
                }

                if (!isVideo) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgBlack.copy(alpha = 0.9f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .align(Alignment.BottomCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val novo = (zoomScale - 0.5f).coerceAtLeast(1f)
                                    zoomScale = novo
                                    if (novo <= 1f) zoomOffset = Offset.Zero
                                },
                                border = BorderStroke(1.dp, Gold),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(Icons.Default.ZoomOut, null, tint = Gold, modifier = Modifier.size(18.dp))
                            }
                            TextButton(
                                onClick = {
                                    zoomScale = 1f
                                    zoomOffset = Offset.Zero
                                }
                            ) {
                                Text(
                                    "${(zoomScale * 100).toInt()}%",
                                    color = Gold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    zoomScale = (zoomScale + 0.5f).coerceAtMost(5f)
                                },
                                border = BorderStroke(1.dp, Gold),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(Icons.Default.ZoomIn, null, tint = Gold, modifier = Modifier.size(18.dp))
                            }
                        }

                        OutlinedButton(
                            onClick = { abrirFicheiroExterno(context, curMediaPath, cur.nomeArquivo) },
                            border = BorderStroke(1.dp, Gold),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, null, tint = Gold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("APP EXTERNA", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }

                if (isVideo && videoProntoLocal) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgBlack.copy(alpha = 0.85f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        Slider(
                            value = currentPos.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                            onValueChange = { novoValor ->
                                val pos = novoValor.toInt()
                                currentPos = pos
                                videoViewRef?.seekTo(pos)
                            },
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Gold,
                                activeTrackColor = Gold,
                                inactiveTrackColor = SurfaceDark
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatarTempoVideo(currentPos), color = TextGrey, fontSize = 11.sp)
                            Text(formatarTempoVideo(duration), color = TextGrey, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    videoViewRef?.let { vv ->
                                        val novo = (vv.currentPosition - 10000).coerceAtLeast(0)
                                        vv.seekTo(novo)
                                        currentPos = novo
                                    }
                                },
                                border = BorderStroke(1.dp, Gold),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.FastRewind, null, tint = Gold, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("-10s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            FloatingActionButton(
                                onClick = {
                                    videoViewRef?.let { vv ->
                                        if (vv.isPlaying) {
                                            vv.pause()
                                            isPlaying = false
                                        } else {
                                            vv.start()
                                            isPlaying = true
                                        }
                                    }
                                },
                                containerColor = Gold,
                                contentColor = BgBlack,
                                shape = CircleShape,
                                modifier = Modifier.size(52.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    videoViewRef?.let { vv ->
                                        val maxDur = if (duration > 0) duration else vv.duration.coerceAtLeast(0)
                                        val novo = (vv.currentPosition + 10000).coerceAtMost(maxDur)
                                        vv.seekTo(novo)
                                        currentPos = novo
                                    }
                                },
                                border = BorderStroke(1.dp, Gold),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("+10s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.FastForward, null, tint = Gold, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = SurfaceDark, modifier = Modifier.width(300.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column(Modifier.fillMaxWidth().background(SurfaceDarker).padding(32.dp)) {
                        Icon(Icons.Default.Person, null, tint = Gold, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Cofre [$vaultStorageMode]: $activeVaultUser", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("Nexus Cloud $NEXUS_VERSION • SSD ${mascararNumeroComAsterisco(phoneNumber)}", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Storage, null) },
                        label = { Text("Armazenamento do Cofre ($vaultStorageMode)") },
                        selected = false,
                        onClick = { showStorage = true; scope.launch { drawerState.close() } },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey)
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.AutoAwesome, null) },
                        label = {
                            val totalIdx = remember(iaIndexTick, arquivos.size) { NexusAI.totalIndexados(context) }
                            Text(if (indexandoLoteIA) "Indexando com Nexus IA..." else "Indexar Busca IA ($totalIdx lido(s))")
                        },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                if (indexandoLoteIA) return@launch
                                val candidatos = arquivos.filter { arq ->
                                    NexusAI.suportaLeituraIA(arq.nomeArquivo) &&
                                        NexusAI.obterTextoIndexado(context, arq.dataUpload).isBlank() &&
                                        (NexusThumbCache.obterArquivoCompleto(arq).isNotBlank() ||
                                            NexusThumbCache.obterMiniatura(arq).isNotBlank())
                                }
                                if (candidatos.isEmpty()) {
                                    Toast.makeText(context, "Todos os arquivos locais já estão indexados pela Nexus IA!", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                indexandoLoteIA = true
                                NexusLeituraNuvem.iniciar(candidatos.size, "Nexus IA indexando textos offline...")
                                var lidosIA = 0
                                withContext(Dispatchers.IO) {
                                    for ((idx, arq) in candidatos.withIndex()) {
                                        if (!NexusLeituraNuvem.ativo.value) break
                                        NexusLeituraNuvem.avancar(idx, candidatos.size, "Nexus IA lendo: ${arq.nomeArquivo}")
                                        val caminho = NexusThumbCache.obterArquivoCompleto(arq)
                                            .ifBlank { NexusThumbCache.obterMiniatura(arq) }
                                        val txt = NexusAI.extrairTextoDeArquivo(appContext, caminho, arq.nomeArquivo, arq.dataUpload)
                                        if (txt.isNotBlank()) lidosIA++
                                        NexusLeituraNuvem.concluirItem()
                                    }
                                }
                                NexusLeituraNuvem.encerrar()
                                indexandoLoteIA = false
                                iaIndexTick++
                                Toast.makeText(context, "Nexus IA indexou $lidosIA arquivo(s) para busca por texto!", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Gold)
                    )
    // Chave liga/desliga do OCR automático no upload (economia de bateria em lotes grandes)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val novo = !ocrAutoNoUpload
                                ocrAutoNoUpload = novo
                                salvarOcrAutoNoUpload(context, novo)
                                Toast.makeText(
                                    context,
                                    if (novo) "OCR automático no upload ativado." else "OCR automático desligado (economia de bateria).",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (ocrAutoNoUpload) Gold else TextGrey
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "OCR Auto no Upload",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (ocrAutoNoUpload) "Ativado (lê textos ao enviar)" else "Desligado (poupa bateria)",
                                color = if (ocrAutoNoUpload) Gold else TextGrey,
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = ocrAutoNoUpload,
                            onCheckedChange = { novo ->
                                ocrAutoNoUpload = novo
                                salvarOcrAutoNoUpload(context, novo)
                                Toast.makeText(
                                    context,
                                    if (novo) "OCR automático no upload ativado." else "OCR automático desligado (economia de bateria).",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BgBlack,
                                checkedTrackColor = Gold,
                                uncheckedThumbColor = TextGrey,
                                uncheckedTrackColor = SurfaceDarker
                            )
                        )
                    }
                    ItemMenuBiometria(
                        context = context,
                        biometriaAtiva = biometriaAtiva,
                        onAlterar = { biometriaAtiva = it }
                    )
                        Switch(
                            checked = ocrAutoNoUpload,
                            onCheckedChange = { novo ->
                                ocrAutoNoUpload = novo
                                salvarOcrAutoNoUpload(context, novo)
                                Toast.makeText(
                                    context,
                                    if (novo) "OCR automático no upload ativado." else "OCR automático desligado (economia de bateria).",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BgBlack,
                                checkedTrackColor = Gold,
                                uncheckedThumbColor = TextGrey,
                                uncheckedTrackColor = SurfaceDarker
                            )
                        )
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Refresh, null) },
                        label = { Text("Cache do SSD Nexus") },
                        selected = false,
                        onClick = { showCacheDialog = true; scope.launch { drawerState.close() } },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Gold)
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Sync, null) },
                        label = { Text("Sincronização Auto") },
                        selected = false,
                        onClick = { showSync = true; scope.launch { drawerState.close() } },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey)
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.CloudUpload, null) },
                        label = { Text("Salvar Pastas na Nuvem ($vaultStorageMode)") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                Toast.makeText(context, "Salvando estrutura de pastas na nuvem SSD...", Toast.LENGTH_SHORT).show()
                                backupCompletoPastasNaNuvem(context, client, activeVaultUser, pastas, arquivos, mapaPastas) { msg ->
                                    cloudBannerText = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Gold)
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.CloudDownload, null) },
                        label = { Text("Restaurar Cofre da Nuvem ($vaultStorageMode)") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                verificarStatusRemotoCliente(context, client, activeVaultUser) { bloqueadoRemoto, motivoRemoto ->
                                    if (bloqueadoRemoto) {
                                        scope.launch(Dispatchers.Main) {
                                            jobFilaMiniaturas?.cancel()
                                            NexusSessaoUpload.limparAberturaSeletor(context)
                                            usuarioTrancouManualmente = true
                                            jobCapasSilencioso?.cancel()
                                            NexusLeituraNuvem.encerrar()
                                            NexusTransfer.cancelar(client)
                                            vaultUnlocked = false
                                            vaultBlocked = true
                                            vaultBlockedReason = motivoRemoto
                                        }
                                        return@verificarStatusRemotoCliente
                                    }
                                    scope.launch(Dispatchers.Main) {
                                        cloudBannerText = "A procurar ficheiros de $activeVaultUser na nuvem SSD ($vaultStorageMode)..."
                                        val atuaisReais = vm.obterArquivosReais()
                                        sincronizarCofreDaNuvem(context, client, activeVaultUser, atuaisReais, dao, vm) { qtd ->
                                            scope.launch(Dispatchers.Main) {
                                                NexusLeituraNuvem.encerrar()
                                                delay(150)
                                                val aposSync = vm.obterArquivosReais()
                                                withContext(Dispatchers.IO) {
                                                    limparDuplicadosExistentes(appContext, aposSync, vm) {}
                                                }
                                                mapaDonos = carregarDonosFicheiros(context)
                                                mapaPastas = carregarVinculosPastas(context)
                                                pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                                cloudBannerText = if (qtd > 0) "$qtd ficheiro(s) restaurados da nuvem SSD ($vaultStorageMode)!" else "Cofre SSD [$vaultStorageMode] de $activeVaultUser atualizado!"
                                                Toast.makeText(context, cloudBannerText, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Gold)
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Info, null) },
                        label = { Text("Sobre") },
                        selected = false,
                        onClick = {
                            showAboutDialog = true
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Gold)
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Lock, null) },
                        label = { Text("Trancar / Trocar Cofre") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            jobFilaMiniaturas?.cancel()
                            NexusSessaoUpload.limparAberturaSeletor(context)
                            usuarioTrancouManualmente = true
                            NexusLeituraNuvem.encerrar()
                            NexusTransfer.cancelar(client)
                            pastaAberta = null
                            selectedFileIds = emptySet()
                            isSelectionMode = false
                            vaultNameInput = ""
                            vaultPinInput = ""
                            vaultErrorMsg = null
                            showAdminPanel = false
                            isSwitchingUser = false
                            vaultUnlocked = false
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = DangerRed, unselectedIconColor = DangerRed)
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    ) {
var modoVisualizacao by remember { mutableIntStateOf(obterModoVisualizacaoGaleria(context)) }
        var progressoExpandido by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                Column(Modifier.background(BgBlack)) {
                    if (isSelectionMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceDarker)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                selectedFileIds = emptySet()
                                isSelectionMode = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancelar seleção", tint = Color.White)
                            }
                            Text(
                                "${selectedFileIds.size} selecionado(s)",
                                color = Gold,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            )
                            TextButton(onClick = {
                                val todosIdsVisiveis = listaVisivelAtual.map { it.id }.toSet()
                                if (selectedFileIds.containsAll(todosIdsVisiveis) && todosIdsVisiveis.isNotEmpty()) {
                                    selectedFileIds = emptySet()
                                    isSelectionMode = false
                                } else {
                                    selectedFileIds = todosIdsVisiveis
                                }
                            }) {
                                val todosMarcados = listaVisivelAtual.isNotEmpty() && selectedFileIds.containsAll(listaVisivelAtual.map { it.id })
                                Text(
                                    if (todosMarcados) "DESMARCAR TUDO" else "SELECIONAR TUDO",
                                    color = Gold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        // Barra superior limpa com alternador (3 col / 4 col / Lista) e anel circular expansível
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, null, tint = Color.White)
                            }
                            Spacer(Modifier.weight(1f))
                            if (listaVisivelAtual.isNotEmpty()) {
                                IconButton(onClick = { isSelectionMode = true }) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Selecionar vários", tint = Gold)
                                }
                            }
                            IconButton(onClick = {
                                val proximoModo = when (modoVisualizacao) {
                                    3 -> 4
                                    4 -> 1
                                    else -> 3
                                }
                                modoVisualizacao = proximoModo
                                salvarModoVisualizacaoGaleria(context, proximoModo)
                                val nomeModo = when (proximoModo) {
                                    4 -> "Grade 4 colunas"
                                    1 -> "Modo Lista"
                                    else -> "Grade 3 colunas"
                                }
                                Toast.makeText(context, "Visualização: $nomeModo", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = when (modoVisualizacao) {
                                        4 -> Icons.Default.Apps
                                        1 -> Icons.Default.ViewList
                                        else -> Icons.Default.GridView
                                    },
                                    contentDescription = "Alterar visualização (3 col / 4 col / Lista)",
                                    tint = Gold
                                )
                            }
                            IconButton(onClick = {
                                showSearchBar = !showSearchBar
                                if (!showSearchBar) searchQuery = ""
                            }) {
                                Icon(if (showSearchBar) Icons.Default.Close else Icons.Default.Search, contentDescription = "Pesquisar", tint = Gold)
                            }
                            BotaoTopoProgressoOuCadeado(
                                expandido = progressoExpandido,
                                onAlternarExpandido = { progressoExpandido = !progressoExpandido },
                                onTrancar = {
                                    jobFilaMiniaturas?.cancel()
                                    NexusSessaoUpload.limparAberturaSeletor(context)
                                    usuarioTrancouManualmente = true
                                    NexusLeituraNuvem.encerrar()
                                    NexusTransfer.cancelar(client)
                                    progressoExpandido = false
                                    selectedFileIds = emptySet()
                                    isSelectionMode = false
                                    vaultNameInput = ""
                                    vaultPinInput = ""
                                    vaultErrorMsg = null
                                    showAdminPanel = false
                                    isSwitchingUser = false
                                    vaultUnlocked = false
                                }
                            )
                        }
                    }

                    // Painel isolado expansível ao tocar no anel circular do topo
                    BarraProgressoIsoladaSSD(
                        context = context,
                        client = client,
                        vaultStorageMode = vaultStorageMode,
                        expandido = progressoExpandido,
                        onRecolher = { progressoExpandido = false },
                        onCancelarLeitura = {
                            jobFilaMiniaturas?.cancel()
                            NexusSessaoUpload.limparAberturaSeletor(context)
                            usuarioTrancouManualmente = true
                            NexusLeituraNuvem.encerrar()
                        },
                        onTransferCancelada = {
                            cloudBannerText = "Transferência cancelada."
                        }
                    )

                    if (showSearchBar) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it.replace("\n", "").take(100) },
                            placeholder = { Text("Buscar por nome ou texto na foto/PDF (Nexus IA)...", color = TextGrey, fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = Gold) },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, null, tint = TextGrey)
                                    }
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = SurfaceDark,
                                focusedContainerColor = SurfaceDarker,
                                unfocusedContainerColor = SurfaceDarker
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp, top = 4.dp)
                        )
                    }

                    TabRow(selectedTabIndex = selectedTab, containerColor = BgBlack, indicator = { TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = Gold) }, divider = {}) {
                        listOf("GALERIA", "PASTAS", "FOTOS", "VÍDEOS", "DOCS").forEachIndexed { i, t ->
                            Tab(selected = selectedTab == i, onClick = {
                                selectedTab = i
                                if (i != 1) pastaAberta = null
                            }) {
                                val labelTab = if (i == 1 && pastas.isNotEmpty()) "PASTAS (${pastas.size})" else t
                                Text(labelTab, color = if (selectedTab == i) Gold else TextGrey, modifier = Modifier.padding(vertical = 12.dp, horizontal = 2.dp), fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (isSelectionMode && selectedFileIds.isNotEmpty()) {
                    val arquivosSelecionados = remember(arquivos, selectedFileIds) {
                        arquivos.filter { selectedFileIds.contains(it.id) }
                    }
                    Surface(
                        color = SurfaceDarker,
                        border = BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        val lote = arquivosSelecionados
                                        selectedFileIds = emptySet()
                                        isSelectionMode = false
                                        compartilharMultiplosFicheirosNativo(context, client, activeVaultUser, lote, vm)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Share, null, tint = Gold, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Compartilhar", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        filesToMoveBatch = arquivosSelecionados
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Mover / Pasta", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        val lote = arquivosSelecionados
                                        selectedFileIds = emptySet()
                                        isSelectionMode = false
                                        copiarLoteParaDownloads(context, client, activeVaultUser, lote, vm) { msg ->
                                            scope.launch(Dispatchers.Main) {
                                                cloudBannerText = msg
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Copiar / Baixar", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        filesToDeleteBatch = arquivosSelecionados
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Delete, null, tint = DangerRed, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Remover", color = DangerRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!isSelectionMode) {
                    NexusSessaoUpload.registrarAberturaSeletor(context, activeVaultUser)
                    FloatingActionButton(onClick = { filePicker.launch(arrayOf("*/*")) }, containerColor = DangerRed, contentColor = Color.White, shape = CircleShape, modifier = Modifier.shadow(16.dp, CircleShape)) {
                        Icon(Icons.Default.CloudUpload, null)
                    }
                }
            },
            containerColor = BgBlack
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (selectedTab == 1) {
                    if (pastaAberta != null) {
                        val nomePastaAtual = pastaAberta!!
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().background(SurfaceDarker).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    pastaAberta = null
                                    selectedFileIds = emptySet()
                                    isSelectionMode = false
                                }) {
                                    Icon(Icons.Default.ArrowBack, null, tint = Gold)
                                }
                                Icon(Icons.Default.FolderOpen, null, tint = Gold, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(nomePastaAtual, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${listaVisivelAtual.size} arquivo(s) na pasta", color = TextGrey, fontSize = 10.sp)
                                }
                                IconButton(onClick = {
                                    novoNomePastaInput = nomePastaAtual
                                    pastaParaRenomear = nomePastaAtual
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Renomear Pasta", tint = Gold)
                                }
                                IconButton(onClick = {
                                    pastaParaApagar = nomePastaAtual
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Apagar Pasta", tint = DangerRed)
                                }
                            }
                            GrelhaArquivos(
                                context = context,
                                client = client,
                                usuarioAtivo = activeVaultUser,
                                vm = vm,
                                lista = listaVisivelAtual,
                                mapaPastas = mapaPastas,
                                modoSelecao = isSelectionMode,
                                idsSelecionados = selectedFileIds,
                                modoVisualizacao = modoVisualizacao,
                                onFileClick = { arq ->
                                    if (isSelectionMode) alternarSelecaoFicheiro(arq)
                                    else selectedFile = arq
                                },
                                onFileLongClick = { arq ->
                                    alternarSelecaoFicheiro(arq)
                                }
                            )
                        }
                    } else {
                        val pastasFiltradas = remember(pastas, searchQuery) {
                            if (searchQuery.isBlank()) pastas
                            else pastas.filter { it.nomePasta.contains(searchQuery, ignoreCase = true) }
                        }
                        Column(Modifier.fillMaxSize().padding(14.dp)) {
                            OutlinedButton(onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Icon(Icons.Default.CreateNewFolder, null, tint = Gold)
                                Spacer(Modifier.width(8.dp))
                                Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Pastas do cofre [$vaultStorageMode] ($activeVaultUser) • Toque numa pasta para ver os arquivos ou nos 3 pontos para editar:",
                                color = TextGrey,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
                            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(
                                    items = pastasFiltradas,
                                    key = { idx, p -> "folder_${p.nomePasta.trim().lowercase(Locale.ROOT)}_$idx" }
                                ) { _, p ->
                                    val arquivosDaPasta = remember(arquivos, mapaPastas, p.nomePasta) {
                                        arquivos.filter { mapaPastas[it.dataUpload]?.equals(p.nomePasta, ignoreCase = true) == true }
                                    }
                                    val qtd = arquivosDaPasta.size
                                    val tamanhoPasta = remember(arquivosDaPasta) { arquivosDaPasta.sumOf { it.tamanhoBytes } }
                                    val arqCapa = remember(arquivosDaPasta) {
                                        arquivosDaPasta.firstOrNull { miniaturaExisteLocalmente(NexusThumbCache.obterMiniatura(it), it.nomeArquivo) }
                                            ?: arquivosDaPasta.firstOrNull()
                                    }
                                    val thumbCapa = arqCapa?.let { NexusThumbCache.mapaMiniaturas[it.dataUpload] ?: it.caminhoMiniatura ?: "" } ?: ""

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                                            .border(1.dp, Gold.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                                            .combinedClickable(
                                                onClick = { pastaAberta = p.nomePasta },
                                                onLongClick = { pastaSelecionadaMenu = p.nomePasta }
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(92.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(SurfaceDarker),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (thumbCapa.isNotBlank() && miniaturaExisteLocalmente(thumbCapa, arqCapa?.nomeArquivo ?: "")) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(obterUriCapaMidia(thumbCapa))
                                                        .size(220, 220)
                                                        .bitmapConfig(Bitmap.Config.RGB_565)
                                                        .build(),
                                                    contentDescription = p.nomePasta,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                Box(Modifier.fillMaxSize().background(BgBlack.copy(alpha = 0.25f)))
                                            } else {
                                                Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(44.dp))
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(6.dp)
                                                    .background(BgBlack.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("$qtd arq.", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            IconButton(
                                                onClick = { pastaSelecionadaMenu = p.nomePasta },
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(28.dp)
                                            ) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "Opções da pasta", tint = Color.White, modifier = Modifier.size(18.dp))
                                            }
                                        }

                                        Spacer(Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                p.nomePasta,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Text(
                                            "$qtd item(ns) • ${formatarTamanhoNuvem(tamanhoPasta)}",
                                            color = TextGrey,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    GrelhaArquivos(
                        context = context,
                        client = client,
                        usuarioAtivo = activeVaultUser,
                        vm = vm,
                        lista = listaVisivelAtual,
                        mapaPastas = mapaPastas,
                        modoSelecao = isSelectionMode,
                        idsSelecionados = selectedFileIds,
                        modoVisualizacao = modoVisualizacao,
                        onFileClick = { arq ->
                            if (isSelectionMode) alternarSelecaoFicheiro(arq)
                            else selectedFile = arq
                        },
                        onFileLongClick = { arq ->
                            alternarSelecaoFicheiro(arq)
                        }
                    )
                }
            }

            if (selectedFile != null) {
                val arq = selectedFile!!
                ModalBottomSheet(onDismissRequest = { selectedFile = null }, sheetState = sheetState, containerColor = SurfaceDark) {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(arq.nomeArquivo, color = Gold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), fontWeight = FontWeight.Bold)
                        Text("Cofre SSD [$vaultStorageMode]: $activeVaultUser • Tamanho: ${formatarTamanhoNuvem(arq.tamanhoBytes)} • Pasta: ${mapaPastas[arq.dataUpload] ?: "Geral"}\nAdicionado em: ${formatarDataNuvem(arq.dataUpload)}", color = TextGrey, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                        ListItem(
                            headlineContent = { Text("Abrir no Nexus Cloud (Com Zoom)", color = Color.White) },
                            leadingContent = { Icon(Icons.Default.Fullscreen, null, tint = Color.White) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                garantirFicheiroLocal(context, client, activeVaultUser, arq, vm) { arqPronto ->
                                    scope.launch(Dispatchers.Main) { viewerFile = arqPronto }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        if (NexusAI.suportaLeituraIA(arq.nomeArquivo)) {
                            ListItem(
                                headlineContent = { Text("Ler com Nexus IA (Texto, PIX e Valores)", color = Gold, fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = Gold) },
                                modifier = Modifier.clickable {
                                    selectedFile = null
                                    abrirAnaliseIA(arq)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        ListItem(
                            headlineContent = { Text("Selecionar vários ficheiros...", color = Gold) },
                            leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = Gold) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                isSelectionMode = true
                                selectedFileIds = setOf(arq.id)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Abrir em App Externa...", color = Gold) },
                            leadingContent = { Icon(Icons.Default.OpenInNew, null, tint = Gold) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                garantirFicheiroLocal(context, client, activeVaultUser, arq, vm) { arqPronto ->
                                    scope.launch(Dispatchers.Main) { abrirFicheiroExterno(context, arqPronto.caminhoMiniatura, arqPronto.nomeArquivo) }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(headlineContent = { Text("Renomear ficheiro...", color = Color.White) }, leadingContent = { Icon(Icons.Default.Edit, null, tint = Color.White) }, modifier = Modifier.clickable { novoNomeInput = arq.nomeArquivo.substringBeforeLast('.'); fileToRename = arq; selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Mover para Pasta...", color = Gold) }, leadingContent = { Icon(Icons.Default.Folder, null, tint = Gold) }, modifier = Modifier.clickable { filesToMoveBatch = listOf(arq); selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(
                            headlineContent = { Text("Compartilhar...", color = Color.White) },
                            leadingContent = { Icon(Icons.Default.Share, null, tint = Color.White) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                garantirFicheiroLocal(context, client, activeVaultUser, arq, vm) { arqPronto ->
                                    scope.launch(Dispatchers.Main) { compartilharFicheiroNativo(context, arqPronto.caminhoMiniatura, arqPronto.nomeArquivo) }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Copiar / Baixar da Nuvem SSD ($vaultStorageMode)", color = Gold) },
                            leadingContent = { Icon(Icons.Default.CloudDownload, null, tint = Gold) },
                            modifier = Modifier.clickable {
                                selectedFile = null
                                baixarFicheiroDaNuvem(context, client, activeVaultUser, arq, vm) { m ->
                                    scope.launch(Dispatchers.Main) { Toast.makeText(context, m, Toast.LENGTH_LONG).show() }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Deletar / Remover ficheiro...", color = DangerRed, fontWeight = FontWeight.Bold) },
                            leadingContent = { Icon(Icons.Default.Delete, null, tint = DangerRed) },
                            modifier = Modifier.clickable {
                                filesToDeleteBatch = listOf(arq)
                                selectedFile = null
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            if (showNewFolder) {
                Dialog(onDismissRequest = { showNewFolder = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp)) {
                            Text("NOVA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(
                                value = nomeNovaPasta,
                                onValueChange = { nomeNovaPasta = it.replace("\n", "").take(60) },
                                label = { Text("Nome", color = TextGrey) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                val n = nomeNovaPasta.trim()
                                if (n.isNotBlank()) {
                                    vm.criarPasta(n)
                                    associarPastaAoUsuario(context, activeVaultUser, n)
                                    registrarAcaoPastaNaNuvem(context, client, activeVaultUser, "CRIAR", n)
                                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                }
                                nomeNovaPasta = ""
                                showNewFolder = false
                            }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth()) { Text("CRIAR", color = Color.White) }
                        }
                    }
                }
            }

            if (showStorage) {
                Dialog(onDismissRequest = { showStorage = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("COFRE SSD [$vaultStorageMode]: ${activeVaultUser.uppercase(Locale.getDefault())}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(formatarTamanhoNuvem(armazenamentoUsuario), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                            Text("${arquivos.size} ficheiro(s) neste perfil ($vaultStorageMode)", color = TextGrey, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            Box(Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(listOf(Gold, DangerRed)), RoundedCornerShape(3.dp)))
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { showStorage = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth()) { Text("FECHAR", color = Color.White) }
                        }
                    }
                }
            }

            if (showSync) {
                Dialog(onDismissRequest = { showSync = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("BACKUP AUTOMÁTICO SSD [$vaultStorageMode] ($activeVaultUser)", color = Gold, fontWeight = FontWeight.Black, fontSize = 15.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text("Ao abrir o cofre, a app verifica estas pastas e mostra os ficheiros novos para upload.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))

                            if (pastasSync.isEmpty()) {
                                Text("Nenhuma pasta selecionada.", color = TextGrey, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                            } else {
                                Column(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    pastasSync.forEach { p ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { removerPastaSync(context, activeVaultUser, p.uri); pastasSync = carregarPastasSync(context, activeVaultUser) }) {
                                                Icon(Icons.Default.Delete, null, tint = DangerRed, modifier = Modifier.size(20.dp))
                                            }
                                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(p.nome, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Switch(
                                                checked = p.ativa,
                                                onCheckedChange = { salvarPastaSync(context, activeVaultUser, p.uri, it); pastasSync = carregarPastasSync(context, activeVaultUser) },
                                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DangerRed, uncheckedThumbColor = TextGrey, uncheckedTrackColor = SurfaceDark)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { folderPicker.launch(null) }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                                Icon(Icons.Default.CreateNewFolder, null, tint = Gold)
                                Spacer(Modifier.width(8.dp))
                                Text("ADICIONAR PASTA", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            if (pastasSync.any { it.ativa }) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        showSync = false
                                        verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio = true)
                                    },
                                    border = BorderStroke(1.dp, Color.White),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Icon(Icons.Default.Search, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("PROCURAR NOVOS AGORA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showSync = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                                Text("CONCLUÍDO", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
// === FIM DO NEXUSUI.KT v2.2 ===
