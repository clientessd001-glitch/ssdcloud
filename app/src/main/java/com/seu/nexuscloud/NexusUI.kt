package com.seu.nexuscloud

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

const val NEXUS_VERSION = "v1.9"

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

// === CONTROLADOR DE DOWNLOAD E ENVIO COM CANCELAMENTO IMEDIATO E PAUSA REAL ===
object NexusTransfer {
    val progress = MutableStateFlow<Int?>(null)
    val label = MutableStateFlow("")
    val pausado = MutableStateFlow(false)
    val cancelado = MutableStateFlow(false)
    val fileIdAtivo = MutableStateFlow(0)
    val ehUpload = MutableStateFlow(false)

    var jobAtivo: Job? = null
    var chatIdAtivo: Long = 0L
    var msgIdAtivo: Long = 0L
    var arquivoTempAtivo: File? = null

    fun iniciar(texto: String, upload: Boolean, fileId: Int = 0) {
        pausado.value = false
        cancelado.value = false
        ehUpload.value = upload
        fileIdAtivo.value = fileId
        chatIdAtivo = 0L
        msgIdAtivo = 0L
        arquivoTempAtivo = null
        progress.value = 5
        label.value = texto
    }

    fun alternarPausa(client: Client? = null) {
        val novoEstado = !pausado.value
        pausado.value = novoEstado
        val fId = fileIdAtivo.value
        if (client != null && fId != 0) {
            if (!ehUpload.value) {
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
    }

    // Cancela imediatamente a Coroutine ativa, interrompe o TDLib e apaga resíduos
    fun cancelar(client: Client? = null) {
        cancelado.value = true
        pausado.value = false

        val fId = fileIdAtivo.value
        val cId = chatIdAtivo
        val mId = msgIdAtivo
        val temp = arquivoTempAtivo

        try { jobAtivo?.cancel() } catch (_: Exception) {}
        jobAtivo = null

        if (client != null) {
            if (fId != 0) {
                client.send(TdApi.CancelDownloadFile().apply {
                    this.fileId = fId
                    this.onlyIfPending = false
                }) {}
                if (ehUpload.value) {
                    try {
                        client.send(TdApi.CancelPreliminaryUploadFile().apply { this.fileId = fId }) {}
                    } catch (_: Exception) {}
                    client.send(TdApi.DeleteFile().apply { this.fileId = fId }) {}
                }
            }
            if (ehUpload.value && cId != 0L && mId != 0L) {
                client.send(TdApi.DeleteMessages().apply {
                    this.chatId = cId
                    this.messageIds = longArrayOf(mId)
                    this.revoke = true
                }) {}
            }
        }

        try {
            if (temp != null && temp.exists()) temp.delete()
        } catch (_: Exception) {}

        // Limpa a barra visual mantendo cancelado.value = true até o próximo iniciar()
        progress.value = null
        label.value = ""
        fileIdAtivo.value = 0
        chatIdAtivo = 0L
        msgIdAtivo = 0L
        arquivoTempAtivo = null
        ehUpload.value = false
    }

    fun encerrar() {
        progress.value = null
        label.value = ""
        pausado.value = false
        fileIdAtivo.value = 0
        chatIdAtivo = 0L
        msgIdAtivo = 0L
        arquivoTempAtivo = null
        ehUpload.value = false
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
    val cancelado = MutableStateFlow(false)

    fun iniciar(totalItens: Int, texto: String) {
        if (totalItens <= 0) {
            encerrar()
            return
        }
        pausado.value = false
        cancelado.value = false
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
        if (cancelado.value) return
        if (novoTotal > 0) total.value = novoTotal
        lidos.value = novoLido.coerceIn(0, total.value.coerceAtLeast(1))
        subProgressoItem.value = 5
        if (texto != null) titulo.value = texto
        ativo.value = true
    }

    fun atualizarSubProgresso(pctItem: Int, texto: String? = null) {
        if (cancelado.value) return
        subProgressoItem.value = pctItem.coerceIn(1, 99)
        if (texto != null) titulo.value = texto
    }

    fun concluirItem() {
        if (cancelado.value) return
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

    fun cancelar() {
        cancelado.value = true
        encerrar()
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

// === CACHE TURBO DE MINIATURAS (.JPG) E GESTOR DE CACHE DO SSD NEXUS ===
object NexusThumbCache {
    val mapaMiniaturas = mutableStateMapOf<Long, String>()
    val mapaArquivosCompletos = mutableStateMapOf<Long, String>()
    val mapaThumbIdsNuvem = mutableStateMapOf<Int, Int>()
    val mapaThumbIdsPorData = mutableStateMapOf<Long, Int>()
    // 8 canais simultâneos para carregar fotos da galeria muito mais rápido
    val downloadSemaphore = Semaphore(8)
    val emProgresso = Collections.synchronizedSet(mutableSetOf<Long>())
    val falhasRecentes = Collections.synchronizedMap(mutableMapOf<Long, Long>())

    @Volatile
    var cacheMensagensNuvem: List<TdApi.Message> = emptyList()
    @Volatile
    var timestampCacheMensagens: Long = 0L

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
                    k.startsWith("thts_") -> {
                        val ts = k.removePrefix("thts_").toLongOrNull()
                        val tId = v.toIntOrNull()
                        if (ts != null && tId != null && tId != 0) mapaThumbIdsPorData[ts] = tId
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
        mapaMiniaturas[dataUpload] = capaLimpa
        falhasRecentes.remove(dataUpload)
        context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
            .edit()
            .putString("ts_$dataUpload", capaLimpa)
            .apply()
    }

    fun salvarArquivoCompleto(context: Context, dataUpload: Long, caminho: String) {
        if (caminho.isBlank() || !arquivoCompletoExisteLocalmente(caminho)) return
        mapaArquivosCompletos[dataUpload] = caminho
        context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE)
            .edit()
            .putString("full_$dataUpload", caminho)
            .apply()
    }

    fun salvarThumbIdNuvem(context: Context, fileId: Int, thumbFileId: Int, dataUpload: Long = 0L) {
        if (thumbFileId == 0) return
        val editor = context.getSharedPreferences("nexus_fast_thumbs", Context.MODE_PRIVATE).edit()
        if (fileId != 0) {
            mapaThumbIdsNuvem[fileId] = thumbFileId
            editor.putString("thid_$fileId", thumbFileId.toString())
        }
        if (dataUpload > 0L) {
            mapaThumbIdsPorData[dataUpload] = thumbFileId
            editor.putString("thts_$dataUpload", thumbFileId.toString())
        }
        editor.apply()
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
            falhasRecentes.remove(vid.dataUpload)
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
        falhasRecentes.clear()
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

    fun salvarArquivo(arq: ArquivoNuvem) = viewModelScope.launch {
        val atuais = obterArquivosReais()
        val nomeLimpo = limparPrefixoTimestamp(arq.nomeArquivo)
        val existente = atuais.firstOrNull {
            limparPrefixoTimestamp(it.nomeArquivo).equals(nomeLimpo, ignoreCase = true) &&
                (it.tamanhoBytes == arq.tamanhoBytes || it.tamanhoBytes == 0L || arq.tamanhoBytes == 0L)
        }
        if (existente != null) {
            val thumbAntigaOk = miniaturaExisteLocalmente(existente.caminhoMiniatura, nomeLimpo)
            val thumbNovaOk = miniaturaExisteLocalmente(arq.caminhoMiniatura, nomeLimpo)
            val precisaAtualizarFileId = arq.fileId != 0 && existente.fileId != arq.fileId
            if ((!thumbAntigaOk && thumbNovaOk) || precisaAtualizarFileId) {
                dao.apagarArquivo(existente.id)
                dao.salvarArquivo(
                    existente.copy(
                        id = 0,
                        nomeArquivo = nomeLimpo,
                        caminhoMiniatura = if (thumbNovaOk) resolverCaminhoCapaJpg(arq.caminhoMiniatura) else existente.caminhoMiniatura,
                        fileId = if (arq.fileId != 0) arq.fileId else existente.fileId
                    )
                )
            }
        } else {
            val capaValida = resolverCaminhoCapaJpg(arq.caminhoMiniatura).ifBlank { arq.caminhoMiniatura }
            dao.salvarArquivo(arq.copy(nomeArquivo = nomeLimpo, caminhoMiniatura = capaValida))
        }
    }

    fun apagar(id: Int) = viewModelScope.launch { dao.apagarArquivo(id) }

    fun atualizarCaminhoMiniatura(context: Context, arq: ArquivoNuvem, novoCaminho: String) {
        val capaJpg = resolverCaminhoCapaJpg(novoCaminho)
        if (capaJpg.isNotBlank()) {
            NexusThumbCache.salvarMiniatura(context, arq.dataUpload, capaJpg)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    dao.apagarArquivo(arq.id)
                    dao.salvarArquivo(arq.copy(id = 0, caminhoMiniatura = capaJpg))
                } catch (_: Exception) {}
            }
        }
    }

    fun renomearArquivo(arq: ArquivoNuvem, novoNome: String) = viewModelScope.launch {
        if (novoNome.isNotBlank()) {
            dao.apagarArquivo(arq.id)
            dao.salvarArquivo(arq.copy(id = 0, nomeArquivo = novoNome.trim()))
        }
    }
}

// === FUNÇÕES AUXILIARES TDLIB ASSÍNCRONAS COM VELOCIDADE OTIMIZADA (80ms) E CANCELAMENTO ===
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
    prioridade: Int = 24,
    maxTentativas: Int = 300,
    controlarPausaTransfer: Boolean = false,
    onProgress: ((Int, Long, Long) -> Unit)? = null
): File? {
    if (fileId == 0) return null

    if (controlarPausaTransfer) {
        if (NexusTransfer.cancelado.value) return null
        NexusTransfer.fileIdAtivo.value = fileId
    } else if (NexusLeituraNuvem.cancelado.value) {
        return null
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
        if ((controlarPausaTransfer && NexusTransfer.cancelado.value) ||
            (!controlarPausaTransfer && NexusLeituraNuvem.cancelado.value)
        ) {
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
            delay(150)
            if ((controlarPausaTransfer && NexusTransfer.cancelado.value) ||
                (!controlarPausaTransfer && NexusLeituraNuvem.cancelado.value)
            ) {
                return null
            }
        }
        if (estavaPausado) {
            client.send(req) {}
            semEvolucao = 0
        }

        delay(80)
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

        if (local?.isDownloadingCompleted == true && path.isNotBlank()) {
            val arqFinal = File(path)
            if (arqFinal.exists() && arqFinal.length() > 100L) {
                onProgress?.invoke(100, arqFinal.length(), arqFinal.length())
                return arqFinal
            }
        }

        if (total > 0L && baixado > 0L) {
            val pctReal = ((baixado * 100L) / total).toInt().coerceIn(2, 99)
            onProgress?.invoke(pctReal, baixado, total)
        } else {
            val pctSuave = (8 + (tentativas * 2)).coerceIn(8, 92)
            onProgress?.invoke(pctSuave, baixado, total)
        }

        if (baixado == ultimoBaixado) {
            semEvolucao++
        } else {
            semEvolucao = 0
            ultimoBaixado = baixado
        }

        if (local?.isDownloadingActive == false && local.isDownloadingCompleted == false) {
            if (tentativas == 4 || tentativas == 12 || tentativas == 25) {
                client.send(req) {}
            } else if (semEvolucao > 22) {
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
    loteTag: String = ""
): Boolean {
    if (fileId == 0) return !NexusTransfer.cancelado.value
    NexusTransfer.fileIdAtivo.value = fileId
    var tentativas = 0
    var inativoCount = 0

    while (tentativas < 1500) {
        if (NexusTransfer.cancelado.value) {
            client.send(TdApi.DeleteFile().apply { this.fileId = fileId }) {}
            return false
        }
        while (NexusTransfer.pausado.value) {
            delay(150)
            if (NexusTransfer.cancelado.value) {
                client.send(TdApi.DeleteFile().apply { this.fileId = fileId }) {}
                return false
            }
        }

        delay(100)
        tentativas++
        val info = obterInfoArquivoTDLib(client, fileId) ?: return !NexusTransfer.cancelado.value
        val remote = info.remote ?: return !NexusTransfer.cancelado.value
        val enviado = remote.uploadedSize
        val total = when {
            info.size > 0 -> info.size
            info.expectedSize > 0 -> info.expectedSize
            else -> 0L
        }

        if (remote.isUploadingCompleted) {
            NexusTransfer.progress.value = 100
            return true
        }

        if (total > 0L && enviado > 0L) {
            val pctRede = ((enviado * 100L) / total).toInt().coerceIn(1, 99)
            val pctTotal = (35 + (pctRede * 64) / 100).coerceIn(36, 99)
            val mbEnv = String.format(Locale.US, "%.1f", enviado / (1024.0 * 1024.0))
            val mbTot = String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0))
            NexusTransfer.progress.value = pctTotal
            NexusTransfer.label.value = "Enviando $loteTag'$nomeArquivo': $mbEnv / $mbTot MB ($pctTotal%)"
        } else {
            val pctEst = (38 + tentativas).coerceIn(38, 92)
            NexusTransfer.progress.value = pctEst
        }

        if (!remote.isUploadingActive) {
            inativoCount++
            if (inativoCount >= 5) return !NexusTransfer.cancelado.value
        } else {
            inativoCount = 0
        }
    }
    return !NexusTransfer.cancelado.value
}

// Recupera fileId e thumbId usando cache em memória e busca rápida por nome (sem travar a fila de fotos)
suspend fun recuperarFileIdNaNuvemPorNome(
    client: Client,
    usuarioAtivo: String,
    nomeAlvo: String,
    tamanhoAlvo: Long
): Pair<Int, Int> {
    val chaveAtiva = normalizarUsuario(usuarioAtivo)
    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"
    val nomeAlvoLimpo = limparPrefixoTimestamp(nomeAlvo).lowercase(Locale.ROOT)

    fun procurarEmLista(lista: List<TdApi.Message>): Pair<Int, Int>? {
        for (msg in lista) {
            val docContent = msg.content as? TdApi.MessageDocument ?: continue
            val legenda = docContent.caption?.text ?: ""
            if (legenda.contains(tagPerfil, ignoreCase = true) ||
                legenda.contains(tagPerfilExibicao, ignoreCase = true) ||
                legenda.contains("☁️ Nexus Cloud")
            ) {
                val nomeBruto = docContent.document?.fileName ?: ""
                val nomeMsg = extrairNomeLimpoDaMensagem(nomeBruto, legenda).lowercase(Locale.ROOT)
                val tamMsg = docContent.document?.document?.size ?: 0L
                if (nomeMsg == nomeAlvoLimpo || (tamanhoAlvo > 0L && tamMsg == tamanhoAlvo && nomeMsg.contains(nomeAlvoLimpo.take(8)))) {
                    val fId = docContent.document?.document?.id ?: 0
                    val tId = docContent.document?.thumbnail?.file?.id ?: 0
                    if (fId != 0) return Pair(fId, tId)
                }
            }
        }
        return null
    }

    // 1. Verifica instantaneamente no cache em memória se foi atualizado há menos de 60s
    if (NexusThumbCache.cacheMensagensNuvem.isNotEmpty() &&
        System.currentTimeMillis() - NexusThumbCache.timestampCacheMensagens < 60_000L
    ) {
        val achouCache = procurarEmLista(NexusThumbCache.cacheMensagensNuvem)
        if (achouCache != null) return achouCache
    }

    // 2. Faz busca direta pelo nome do arquivo no servidor (1 única chamada rápida)
    val def = CompletableDeferred<Pair<Int, Int>>()
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    val termoBusca = nomeAlvoLimpo.substringBeforeLast('.').take(16).ifBlank { nomeAlvoLimpo }
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, termoBusca) { msgsBusca ->
                        val daBusca = procurarEmLista(msgsBusca)
                        if (daBusca != null) {
                            def.complete(daBusca)
                        } else {
                            buscarHistoricoPaginado(client, chatObj.id, 0L, 6, mutableListOf()) { todasMensagens ->
                                NexusThumbCache.cacheMensagensNuvem = todasMensagens
                                NexusThumbCache.timestampCacheMensagens = System.currentTimeMillis()
                                def.complete(procurarEmLista(todasMensagens) ?: Pair(0, 0))
                            }
                        }
                    }
                } else def.complete(Pair(0, 0))
            }
        } else def.complete(Pair(0, 0))
    }
    return def.await()
}

// === GESTÃO DE COFRES MULTIUTILIZADOR, PASTAS E EXCLUSÃO ===
fun normalizarUsuario(nome: String): String = nome.trim().lowercase(Locale.ROOT)

fun obterUltimoUsuarioCofre(context: Context): String {
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getString("last_active_user", "") ?: ""
}

fun salvarCredenciaisCofreLocal(context: Context, nomeOriginal: String, pin: String, definirComoAtivo: Boolean = true) {
    val chave = normalizarUsuario(nomeOriginal)
    if (chave.isBlank()) return
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()
        .putString("pin_$chave", pin.trim())
        .putString("display_$chave", nomeOriginal.trim())
    if (definirComoAtivo) {
        editor.putString("last_active_user", nomeOriginal.trim())
    }
    editor.apply()
}

fun obterPinCofreLocal(context: Context, nome: String): String? {
    val chave = normalizarUsuario(nome)
    val prefs = context.getSharedPreferences("nexus_vault_prefs", Context.MODE_PRIVATE)
    return prefs.getString("pin_$chave", null)
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
    if (usuario.isBlank()) prefs.edit().remove(dataUpload.toString()).apply()
    else prefs.edit().putString(dataUpload.toString(), normalizarUsuario(usuario)).apply()
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
    val atuais = (prefs.getStringSet("folders_$chave", emptySet()) ?: emptySet())
        .filterNot { it.equals(nomePasta.trim(), ignoreCase = true) }
        .toSet()
    prefs.edit().putStringSet("folders_$chave", atuais).apply()
}

fun marcarPastaComoEliminada(context: Context, usuario: String, nomePasta: String) {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_deleted_folders_$chave", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(nomePasta.trim().lowercase(Locale.ROOT), true).apply()
}

fun desmarcarPastaEliminada(context: Context, usuario: String, nomePasta: String) {
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_deleted_folders_$chave", Context.MODE_PRIVATE)
    prefs.edit().remove(nomePasta.trim().lowercase(Locale.ROOT)).apply()
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

fun registarMensagemSincronizada(context: Context, usuario: String, msgId: Long) {
    if (msgId == 0L) return
    val chave = normalizarUsuario(usuario)
    val prefs = context.getSharedPreferences("nexus_synced_msgs_$chave", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(msgId.toString(), true).apply()
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
    val editor = prefs.edit()
    editor.putBoolean("name_$nomeLimpo", true)
    editor.putBoolean("key_${nomeLimpo}|$tamanho", true)
    if (uriStr.isNotBlank()) {
        editor.putBoolean("uri_$uriStr", true)
    }
    editor.apply()
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
    prefs.edit()
        .putBoolean("del_name_$nomeLimpo", true)
        .putBoolean("del_key_${nomeLimpo}|$tamanho", true)
        .apply()
}

fun desmarcarFicheiroDeletado(context: Context, usuario: String, nomeArquivo: String, tamanho: Long) {
    val chave = normalizarUsuario(usuario)
    val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).lowercase(Locale.ROOT)
    val prefs = context.getSharedPreferences("nexus_deleted_files_$chave", Context.MODE_PRIVATE)
    prefs.edit()
        .remove("del_name_$nomeLimpo")
        .remove("del_key_${nomeLimpo}|$tamanho")
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

    for (arq in listaArquivos) {
        val nomeLimpo = limparPrefixoTimestamp(arq.nomeArquivo)
        nomesParaApagar.add(nomeLimpo.lowercase(Locale.ROOT))
        if (arq.fileId != 0) idsRemotosParaApagar.add(arq.fileId)

        marcarFicheiroComoDeletado(context, usuarioAtivo, nomeLimpo, arq.tamanhoBytes)
        marcarFicheiroComoSincronizado(context, usuarioAtivo, nomeLimpo, arq.tamanhoBytes)

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
        salvarVinculoPasta(context, arq.dataUpload, "")
        salvarDonoFicheiro(context, arq.dataUpload, "")
        vm.apagar(arq.id)
    }

    val qtd = listaArquivos.size
    if (!apagarNaNuvemSSD) {
        onComplete("$qtd ficheiro(s) removido(s) do aplicativo.")
        return
    }

    idsRemotosParaApagar.forEach { fId ->
        client.send(TdApi.DeleteFile().apply { fileId = fId }) {}
    }

    val chaveAtiva = normalizarUsuario(usuarioAtivo)
    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarHistoricoPaginado(client, chatObj.id, 0L, 12, mutableListOf()) { todasMensagens ->
                        val msgIdsParaApagar = mutableListOf<Long>()
                        for (msg in todasMensagens) {
                            val docContent = msg.content as? TdApi.MessageDocument ?: continue
                            val legenda = docContent.caption?.text ?: ""
                            val pertenceAoUsuario = legenda.contains(tagPerfil, ignoreCase = true) ||
                                legenda.contains(tagPerfilExibicao, ignoreCase = true) ||
                                legenda.contains("☁️ Nexus Cloud")

                            if (pertenceAoUsuario) {
                                val rId = docContent.document?.document?.id ?: 0
                                val nomeBruto = docContent.document?.fileName ?: ""
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
                                onComplete("$qtd ficheiro(s) deletado(s) do app e da nuvem SSD!")
                            }
                        } else {
                            onComplete("$qtd ficheiro(s) deletado(s)!")
                        }
                    }
                } else {
                    onComplete("$qtd ficheiro(s) deletado(s) localmente.")
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
        arquivos.forEach { arq ->
            if (!donosAtuais.containsKey(arq.dataUpload)) {
                salvarDonoFicheiro(context, arq.dataUpload, usuario)
            }
        }
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
    val limpo = if (resolvido.startsWith("file://")) Uri.parse(resolvido).path ?: resolvido.removePrefix("file://") else resolvido
    if (limpo.isBlank()) return false
    val f = File(limpo)
    return f.exists() && f.length() > 256L
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
                    associarPastaAoUsuario(context, donoEncontrado, pastaEncontrada)
                }
                marcarFicheiroComoSincronizado(context, donoEncontrado, principal.nomeArquivo, principal.tamanhoBytes)
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
                marcarFicheiroComoSincronizado(context, donoUnico, unico.nomeArquivo, unico.tamanhoBytes)
                if (pastaUnico.isNotBlank()) {
                    associarPastaAoUsuario(context, donoUnico, pastaUnico)
                }
            }
            val nomeLimpo = limparPrefixoTimestamp(unico.nomeArquivo)
            if (unico.nomeArquivo != nomeLimpo) {
                vm.renomearArquivo(unico, nomeLimpo)
                alterouAlgum = true
            }
        }
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

fun buscarHistoricoPaginado(
    client: Client,
    chatId: Long,
    fromMsgId: Long,
    lotesRestantes: Int,
    acumulado: MutableList<TdApi.Message>,
    tentativasVazio: Int = 0,
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
            val ultimoId = msgs.last().id
            if (ultimoId != 0L && ultimoId != fromMsgId) {
                buscarHistoricoPaginado(client, chatId, ultimoId, lotesRestantes - 1, acumulado, 0, onComplete)
            } else {
                onComplete(acumulado.distinctBy { it.id })
            }
        } else if (acumulado.size <= 1 && tentativasVazio < 4) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                delay(450)
                buscarHistoricoPaginado(client, chatId, fromMsgId, lotesRestantes, acumulado, tentativasVazio + 1, onComplete)
            }
        } else {
            onComplete(acumulado.distinctBy { it.id })
        }
    }
}

// Sincroniza silenciosamente os registos de cofre da nuvem em segundo plano (SEM exibir os nomes na tela)
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
                        buscarHistoricoPaginado(client, chatObj.id, 0L, 8, mutableListOf()) { msgsHist ->
                            val todas = (msgsBusca + msgsHist).distinctBy { it.id }
                            NexusThumbCache.cacheMensagensNuvem = todas
                            NexusThumbCache.timestampCacheMensagens = System.currentTimeMillis()

                            val nomesEncontrados = mutableMapOf<String, String>()
                            for (msg in todas) {
                                val texto = when (val c = msg.content) {
                                    is TdApi.MessageText -> c.text?.text ?: ""
                                    is TdApi.MessageDocument -> c.caption?.text ?: ""
                                    else -> ""
                                }
                                if (texto.contains("[REGISTO_COFRE]")) {
                                    val partes = texto.split("|").map { it.trim() }
                                    val userPart = partes.firstOrNull { it.startsWith("USER:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                    val nomePart = partes.firstOrNull { it.startsWith("NOME:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                    val pinPart = partes.firstOrNull { it.startsWith("PIN:", ignoreCase = true) }?.substringAfter(":")?.trim()

                                    val chave = normalizarUsuario(userPart ?: nomePart ?: "")
                                    val exibicao = (nomePart ?: userPart ?: "").trim()
                                    if (chave.isNotBlank() && exibicao.isNotBlank()) {
                                        if (!nomesEncontrados.containsKey(chave)) {
                                            nomesEncontrados[chave] = exibicao
                                        }
                                        if (!pinPart.isNullOrBlank()) {
                                            // Salva apenas o PIN internamente sem definir active_user para manter tudo oculto
                                            salvarCredenciaisCofreLocal(context, exibicao, pinPart, definirComoAtivo = false)
                                        }
                                    }
                                }
                            }
                            onFound(nomesEncontrados.values.toList())
                        }
                    }
                } else onFound(emptyList())
            }
        } else onFound(emptyList())
    }
}

fun verificarOuRegistarCofreNuvem(
    client: Client,
    nomeUsuario: String,
    pin: String,
    modoCriarNovo: Boolean,
    onResult: (Boolean, String, String) -> Unit
) {
    val chaveDesejada = normalizarUsuario(nomeUsuario)
    val pinLimpo = pin.trim()

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, "REGISTO_COFRE") { msgsRegisto ->
                        buscarMensagensPorTermoNaNuvem(client, chatObj.id, nomeUsuario.trim()) { msgsUsuario ->
                            buscarHistoricoPaginado(client, chatObj.id, 0L, 12, mutableListOf()) { msgsHistorico ->
                                val todasMensagens = (msgsRegisto + msgsUsuario + msgsHistorico).distinctBy { it.id }
                                NexusThumbCache.cacheMensagensNuvem = todasMensagens
                                NexusThumbCache.timestampCacheMensagens = System.currentTimeMillis()

                                var pinEncontrado: String? = null
                                var nomeOriginalEncontrado = nomeUsuario.trim()
                                var temArquivosDoUsuarioNaNuvem = false

                                for (msg in todasMensagens) {
                                    val texto = when (val c = msg.content) {
                                        is TdApi.MessageText -> c.text?.text ?: ""
                                        is TdApi.MessageDocument -> c.caption?.text ?: ""
                                        else -> ""
                                    }

                                    if (texto.contains("[REGISTO_COFRE]") && pinEncontrado == null) {
                                        val partes = texto.split("|").map { it.trim() }
                                        val userPart = partes.firstOrNull { it.startsWith("USER:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                        val nomePart = partes.firstOrNull { it.startsWith("NOME:", ignoreCase = true) }?.substringAfter(":")?.trim()
                                        val pinPart = partes.firstOrNull { it.startsWith("PIN:", ignoreCase = true) }?.substringAfter(":")?.trim()

                                        val chaveMsg = normalizarUsuario(userPart ?: nomePart ?: "")
                                        if (chaveMsg == chaveDesejada && !pinPart.isNullOrBlank()) {
                                            pinEncontrado = pinPart
                                            if (!nomePart.isNullOrBlank()) nomeOriginalEncontrado = nomePart
                                        }
                                    }

                                    if (msg.content is TdApi.MessageDocument) {
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

                                fun gravarRegistoNaNuvem(nomeConfirmado: String, mensagemSucesso: String) {
                                    val regTexto = "🔒 [REGISTO_COFRE] | USER:$chaveDesejada | NOME:${nomeConfirmado.trim()} | PIN:$pinLimpo"
                                    val msgContent = TdApi.InputMessageText().apply {
                                        text = TdApi.FormattedText(regTexto, emptyArray())
                                    }
                                    val sendReq = TdApi.SendMessage().apply {
                                        chatId = chatObj.id
                                        inputMessageContent = msgContent
                                    }
                                    client.send(sendReq) {
                                        onResult(true, nomeConfirmado.trim(), mensagemSucesso)
                                    }
                                }

                                if (modoCriarNovo) {
                                    when {
                                        pinEncontrado != null && pinEncontrado == pinLimpo -> {
                                            onResult(true, nomeOriginalEncontrado, "Cofre existente reconhecido e restaurado!")
                                        }
                                        pinEncontrado != null && pinEncontrado != pinLimpo -> {
                                            onResult(false, "", "Este perfil já existe na nuvem SSD! Digite o PIN correto ou escolha outro nome.")
                                        }
                                        temArquivosDoUsuarioNaNuvem -> {
                                            gravarRegistoNaNuvem(nomeOriginalEncontrado, "Perfil existente vinculado e restaurado!")
                                        }
                                        else -> {
                                            gravarRegistoNaNuvem(nomeUsuario.trim(), "Cofre SSD criado com sucesso!")
                                        }
                                    }
                                } else {
                                    when {
                                        pinEncontrado != null && pinEncontrado == pinLimpo -> {
                                            onResult(true, nomeOriginalEncontrado, "Cofre SSD desbloqueado!")
                                        }
                                        pinEncontrado != null && pinEncontrado != pinLimpo -> {
                                            onResult(false, "", "PIN incorreto para o utilizador '$nomeOriginalEncontrado'.")
                                        }
                                        temArquivosDoUsuarioNaNuvem -> {
                                            gravarRegistoNaNuvem(nomeOriginalEncontrado, "Cofre '$nomeOriginalEncontrado' reconhecido na nuvem SSD!")
                                        }
                                        else -> {
                                            onResult(false, "", "Cofre '${nomeUsuario.trim()}' não encontrado na nuvem SSD. Verifique o nome ou use 'CRIAR COFRE'.")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    onResult(false, "", "Erro ao aceder ao cofre na nuvem SSD.")
                }
            }
        } else {
            onResult(false, "", "Sessão SSD indisponível.")
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

fun bitmapNaoEhTotalmentePreto(bmp: Bitmap): Boolean {
    return calcularBrilhoMedioBitmap(bmp) >= 26
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
        if (existenteBmp != null && calcularBrilhoMedioBitmap(existenteBmp) >= 35) {
            return true
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
                    melhorBrilho = b
                    melhorBitmap = candidato
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
                    melhorBitmap = thumbFallback
                }
            }
        } catch (_: Exception) {}
    }

    val bmpBruto = melhorBitmap ?: return false
    val bmpFinal = clarearBitmapVideoSeNecessario(bmpBruto)

    return try {
        val tempFile = File("${destinoFrame.absolutePath}.tmp")
        FileOutputStream(tempFile).use { out ->
            bmpFinal.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        if (tempFile.exists() && tempFile.length() > 256L) {
            if (destinoFrame.exists()) destinoFrame.delete()
            tempFile.renameTo(destinoFrame)
            true
        } else {
            tempFile.delete()
            false
        }
    } catch (_: Exception) {
        false
    }
}

fun gerarMiniaturaImagemLeve(arquivoImagem: File, destinoFrame: File): Boolean {
    if (destinoFrame.exists() && destinoFrame.length() > 256L) return true
    return try {
        val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(arquivoImagem.absolutePath, optsBounds)
        if (optsBounds.outWidth <= 0 || optsBounds.outHeight <= 0) return false

        var sample = 1
        while (optsBounds.outWidth / sample > 360 || optsBounds.outHeight / sample > 360) {
            sample *= 2
        }
        val optsDecode = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(arquivoImagem.absolutePath, optsDecode) ?: return false
        val tempFile = File("${destinoFrame.absolutePath}.tmp")
        FileOutputStream(tempFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
        }
        if (tempFile.exists() && tempFile.length() > 256L) {
            if (destinoFrame.exists()) destinoFrame.delete()
            tempFile.renameTo(destinoFrame)
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

        val bmp = BitmapFactory.decodeFile(arquivoThumb.absolutePath) ?: return ""
        if (bmp.width <= 0 || bmp.height <= 0) return ""

        val brilho = calcularBrilhoMedioBitmap(bmp)
        if (ehVid && brilho < 22) {
            return ""
        }

        val bmpAjustado = if (ehVid) clarearBitmapVideoSeNecessario(bmp) else bmp
        FileOutputStream(fFrame).use { out ->
            bmpAjustado.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        if (fFrame.exists() && fFrame.length() > 256L) {
            Uri.fromFile(fFrame).toString()
        } else ""
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
                Uri.fromFile(frameFile).toString()
            } else ""
        }

        if (!baseFile.exists() || baseFile.length() <= 100L) {
            FileInputStream(arquivoOrigem).use { inp ->
                FileOutputStream(baseFile).use { out -> inp.copyTo(out) }
            }
        }

        if (ehArquivoImagem(nomeSeguro)) {
            val gerou = gerarMiniaturaImagemLeve(baseFile, frameFile)
            return if (gerou && frameFile.exists()) Uri.fromFile(frameFile).toString() else Uri.fromFile(baseFile).toString()
        } else if (descobrirMimeType(nomeSeguro) == "application/pdf") {
            val capaPdf = gerarCapaPdf(baseFile)
            return if (capaPdf != null && capaPdf.exists()) Uri.fromFile(capaPdf).toString() else ""
        }
        Uri.fromFile(baseFile).toString()
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

// === SINCRONIZAÇÃO COM BARRA DE CONTAGEM DE ARQUIVOS NA NUVEM ===
fun sincronizarCofreDaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arquivosAtuaisReais: List<ArquivoNuvem>,
    vm: UIViewModel,
    onComplete: (Int) -> Unit
) {
    val chaveAtiva = normalizarUsuario(usuarioAtivo)
    val tagPerfil = "👤 [$chaveAtiva]"
    val tagPerfilExibicao = "👤 [$usuarioAtivo]"

    NexusLeituraNuvem.iniciar(1, "Conectando à nuvem SSD de $usuarioAtivo...")

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    buscarMensagensPorTermoNaNuvem(client, chatObj.id, usuarioAtivo.trim()) { msgsBusca ->
                        buscarHistoricoPaginado(client, chatObj.id, 0L, 12, mutableListOf()) { msgsHistorico ->
                            val todasMensagens = (msgsBusca + msgsHistorico).distinctBy { it.id }
                            NexusThumbCache.cacheMensagensNuvem = todasMensagens
                            NexusThumbCache.timestampCacheMensagens = System.currentTimeMillis()

                            val mensagensDoUsuario = todasMensagens.filter { msg ->
                                val docContent = msg.content as? TdApi.MessageDocument ?: return@filter false
                                val legenda = docContent.caption?.text ?: ""
                                legenda.contains(tagPerfil, ignoreCase = true) || legenda.contains(tagPerfilExibicao, ignoreCase = true)
                            }

                            val totalNaNuvem = mensagensDoUsuario.size
                            if (totalNaNuvem > 0) {
                                NexusLeituraNuvem.iniciar(totalNaNuvem, "Lendo arquivos da nuvem SSD...")
                            } else {
                                NexusLeituraNuvem.encerrar()
                                onComplete(0)
                                return@buscarHistoricoPaginado
                            }

                            var importados = 0
                            val donosAtuais = carregarDonosFicheiros(context)
                            val pastasAtuais = carregarVinculosPastas(context)

                            val mapaExistentesPorChave = arquivosAtuaisReais
                                .filter { donosAtuais[it.dataUpload] == chaveAtiva || donosAtuais.isEmpty() }
                                .associateBy { "${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}|${it.tamanhoBytes}" }
                                .toMutableMap()

                            val nomesExistentes = arquivosAtuaisReais
                                .filter { donosAtuais[it.dataUpload] == chaveAtiva || donosAtuais.isEmpty() }
                                .map { limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT) }
                                .toMutableSet()

                            mensagensDoUsuario.forEachIndexed { index, msg ->
                                val docContent = msg.content as? TdApi.MessageDocument ?: return@forEachIndexed
                                val legenda = docContent.caption?.text ?: ""
                                val rId = docContent.document?.document?.id ?: 0
                                val thumbObj = docContent.document?.thumbnail?.file
                                val thumbId = thumbObj?.id ?: 0
                                val thumbPathLocal = thumbObj?.local?.path ?: ""

                                // Dispara pré-download assíncrono imediato da miniatura leve no TDLib
                                if (thumbId != 0 && thumbPathLocal.isBlank()) {
                                    client.send(TdApi.DownloadFile().apply {
                                        this.fileId = thumbId
                                        this.priority = 32
                                        this.offset = 0
                                        this.limit = 0
                                        this.synchronous = false
                                    }) {}
                                }

                                val nomeBruto = docContent.document?.fileName?.ifBlank { "Arquivo_$rId" } ?: "Arquivo_$rId"
                                val nomeDoc = extrairNomeLimpoDaMensagem(nomeBruto, legenda)
                                val nomeLower = nomeDoc.lowercase(Locale.ROOT)
                                val tam = docContent.document?.document?.size ?: 0L
                                val chaveUnica = "$nomeLower|$tam"

                                NexusLeituraNuvem.avancar(
                                    novoLido = index + 1,
                                    novoTotal = totalNaNuvem,
                                    texto = "Lendo nuvem SSD: $nomeDoc"
                                )

                                if (ficheiroFoiDeletadoPeloUsuario(context, usuarioAtivo, nomeDoc, tam)) {
                                    registarMensagemSincronizada(context, usuarioAtivo, msg.id)
                                    return@forEachIndexed
                                }

                                val pastaBruta = if (legenda.contains("📁 [")) {
                                    legenda.substringAfter("📁 [").substringBefore("]").trim()
                                } else ""
                                val pastaResolvida = resolverNomePastaAtualizado(context, usuarioAtivo, pastaBruta)
                                val pastaFinal = if (pastaResolvida.isNotBlank() && !pastaFoiEliminadaPeloUsuario(context, usuarioAtivo, pastaResolvida)) {
                                    pastaResolvida
                                } else ""

                                if (pastaFinal.isNotBlank()) {
                                    vm.criarPasta(pastaFinal)
                                    associarPastaAoUsuario(context, usuarioAtivo, pastaFinal)
                                }

                                marcarFicheiroComoSincronizado(context, usuarioAtivo, nomeDoc, tam)

                                val caminhoLocalExistente = docContent.document?.document?.local?.path ?: ""
                                val uriMiniaturaInicial = when {
                                    caminhoLocalExistente.isNotBlank() && File(caminhoLocalExistente).exists() && File(caminhoLocalExistente).length() > 100L ->
                                        prepararMiniaturaDeFicheiroBaixado(context, File(caminhoLocalExistente), nomeDoc)
                                    thumbPathLocal.isNotBlank() && File(thumbPathLocal).exists() && File(thumbPathLocal).length() > 100L ->
                                        salvarMiniaturaRapidaTDLib(context, File(thumbPathLocal), rId, nomeDoc)
                                    else -> ""
                                }

                                val arqExistente = mapaExistentesPorChave[chaveUnica]
                                if (arqExistente != null) {
                                    if (thumbId != 0) {
                                        NexusThumbCache.salvarThumbIdNuvem(context, rId, thumbId, arqExistente.dataUpload)
                                    }
                                    if (pastaFinal.isNotBlank() && pastasAtuais[arqExistente.dataUpload].isNullOrBlank()) {
                                        salvarVinculoPasta(context, arqExistente.dataUpload, pastaFinal)
                                    }
                                    if (uriMiniaturaInicial.isNotBlank()) {
                                        NexusThumbCache.salvarMiniatura(context, arqExistente.dataUpload, uriMiniaturaInicial)
                                    }
                                    if (caminhoLocalExistente.isNotBlank() && File(caminhoLocalExistente).exists() && File(caminhoLocalExistente).length() > 100L) {
                                        NexusThumbCache.salvarArquivoCompleto(context, arqExistente.dataUpload, Uri.fromFile(File(caminhoLocalExistente)).toString())
                                    }
                                    if ((rId != 0 && arqExistente.fileId != rId) ||
                                        (!miniaturaExisteLocalmente(arqExistente.caminhoMiniatura, nomeDoc) && uriMiniaturaInicial.isNotBlank())
                                    ) {
                                        vm.salvarArquivo(
                                            arqExistente.copy(
                                                fileId = if (rId != 0) rId else arqExistente.fileId,
                                                caminhoMiniatura = if (uriMiniaturaInicial.isNotBlank()) uriMiniaturaInicial else arqExistente.caminhoMiniatura
                                            )
                                        )
                                    }
                                    registarMensagemSincronizada(context, usuarioAtivo, msg.id)
                                } else if (!nomesExistentes.contains(nomeLower)) {
                                    nomesExistentes.add(nomeLower)
                                    registarMensagemSincronizada(context, usuarioAtivo, msg.id)

                                    val ts = (msg.date.toLong() * 1000L) + (msg.id % 997L).let { if (it < 0) -it else it } + index
                                    if (thumbId != 0) {
                                        NexusThumbCache.salvarThumbIdNuvem(context, rId, thumbId, ts)
                                    }
                                    salvarDonoFicheiro(context, ts, usuarioAtivo)
                                    if (pastaFinal.isNotBlank()) {
                                        salvarVinculoPasta(context, ts, pastaFinal)
                                    }
                                    if (uriMiniaturaInicial.isNotBlank()) {
                                        NexusThumbCache.salvarMiniatura(context, ts, uriMiniaturaInicial)
                                    }
                                    if (caminhoLocalExistente.isNotBlank() && File(caminhoLocalExistente).exists() && File(caminhoLocalExistente).length() > 100L) {
                                        NexusThumbCache.salvarArquivoCompleto(context, ts, Uri.fromFile(File(caminhoLocalExistente)).toString())
                                    }

                                    val novoArq = ArquivoNuvem(
                                        fileId = rId,
                                        nomeArquivo = nomeDoc,
                                        tamanhoBytes = tam,
                                        dataUpload = ts,
                                        caminhoMiniatura = uriMiniaturaInicial
                                    )
                                    mapaExistentesPorChave[chaveUnica] = novoArq
                                    vm.salvarArquivo(novoArq)
                                    importados++
                                }
                            }

                            onComplete(importados)
                        }
                    }
                } else {
                    NexusLeituraNuvem.encerrar()
                    onComplete(0)
                }
            }
        } else {
            NexusLeituraNuvem.encerrar()
            onComplete(0)
        }
    }
}

// === FILA TURBO DE CARREGAMENTO DE MINIATURAS EM SEGUNDO PLANO ===
suspend fun carregarMiniaturaIndividualDaNuvem(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    arq: ArquivoNuvem,
    vm: UIViewModel,
    atualizarSubBarra: Boolean = false
): String = withContext(Dispatchers.IO) {
    val jaTem = NexusThumbCache.obterMiniatura(arq)
    if (jaTem.isNotBlank() && miniaturaExisteLocalmente(jaTem, arq.nomeArquivo)) {
        return@withContext jaTem
    }

    // Evita repetir tentativas seguidas no mesmo segundo em arquivo indisponível
    val ultimaFalha = NexusThumbCache.falhasRecentes[arq.dataUpload] ?: 0L
    if (System.currentTimeMillis() - ultimaFalha < 15_000L) {
        return@withContext ""
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
    var thumbIdLeve = NexusThumbCache.mapaThumbIdsPorData[arq.dataUpload]
        ?: NexusThumbCache.mapaThumbIdsNuvem[fIdAtual]
        ?: 0

    if (fIdAtual == 0 && thumbIdLeve == 0 && usuarioAtivo.isNotBlank()) {
        val (recFileId, recThumbId) = recuperarFileIdNaNuvemPorNome(client, usuarioAtivo, arq.nomeArquivo, arq.tamanhoBytes)
        if (recFileId != 0) {
            fIdAtual = recFileId
            if (recThumbId != 0) {
                thumbIdLeve = recThumbId
                NexusThumbCache.salvarThumbIdNuvem(context, recFileId, recThumbId, arq.dataUpload)
            }
        }
    }

    var miniaturaPronta = ""

    // 1. Prioridade máxima para a miniatura leve (~15KB), baixando em milissegundos
    if (thumbIdLeve != 0) {
        val arqThumb = baixarArquivoTDLibAssincrono(
            client = client,
            fileId = thumbIdLeve,
            prioridade = 32,
            maxTentativas = 45,
            controlarPausaTransfer = false
        ) { pct, _, _ ->
            if (atualizarSubBarra) {
                NexusLeituraNuvem.atualizarSubProgresso(pct, "Carregando capa: ${arq.nomeArquivo}")
            }
        }
        if (arqThumb != null && arqThumb.exists() && arqThumb.length() > 100L) {
            miniaturaPronta = salvarMiniaturaRapidaTDLib(context, arqThumb, fIdAtual.coerceAtLeast(thumbIdLeve), arq.nomeArquivo)
        }
    }

    // 2. Fallback para arquivo completo (se a foto/vídeo não tinha thumb leve ou precisava clarear capa)
    val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val podeBaixarParaCapa = ehArquivoImagem(arq.nomeArquivo) ||
        ehArquivoVideo(arq.nomeArquivo) ||
        ext == "PDF"

    val limiteAutoBytes = if (ehArquivoImagem(arq.nomeArquivo)) 35L * 1024L * 1024L else 85L * 1024L * 1024L

    if (miniaturaPronta.isBlank() && fIdAtual != 0 && podeBaixarParaCapa && arq.tamanhoBytes <= limiteAutoBytes) {
        val arqFull = baixarArquivoTDLibAssincrono(
            client = client,
            fileId = fIdAtual,
            prioridade = 26,
            maxTentativas = 240,
            controlarPausaTransfer = false
        ) { pct, _, _ ->
            if (atualizarSubBarra) {
                NexusLeituraNuvem.atualizarSubProgresso(pct, "Gerando capa: ${arq.nomeArquivo} ($pct%)")
            }
        }
        if (arqFull != null && arqFull.exists() && arqFull.length() > 100L) {
            NexusThumbCache.salvarArquivoCompleto(context, arq.dataUpload, Uri.fromFile(arqFull).toString())
            miniaturaPronta = prepararMiniaturaDeFicheiroBaixado(context, arqFull, arq.nomeArquivo)
        }
    }

    if (miniaturaPronta.isNotBlank()) {
        withContext(Dispatchers.Main) {
            vm.atualizarCaminhoMiniatura(context, arq, miniaturaPronta)
        }
    } else {
        NexusThumbCache.falhasRecentes[arq.dataUpload] = System.currentTimeMillis()
    }
    return@withContext miniaturaPronta
}

// === ABERTURA E DOWNLOAD DE FICHEIROS COM CANCELAMENTO IMEDIATO E PAUSA ===
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

    try { NexusTransfer.jobAtivo?.cancel() } catch (_: Exception) {}

    val novoJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        var targetFileId = arq.fileId
        NexusTransfer.iniciar(
            texto = "Baixando '${arq.nomeArquivo}' (5%)...",
            upload = false,
            fileId = targetFileId
        )

        var arquivoBaixado = withContext(Dispatchers.IO) {
            if (targetFileId != 0) {
                baixarArquivoTDLibAssincrono(
                    client = client,
                    fileId = targetFileId,
                    prioridade = 32,
                    maxTentativas = 900,
                    controlarPausaTransfer = true
                ) { pct, baixadoBytes, totalBytes ->
                    if (!NexusTransfer.cancelado.value) {
                        val mbBaixado = String.format(Locale.US, "%.1f", baixadoBytes / (1024.0 * 1024.0))
                        val mbTotal = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                        NexusTransfer.progress.value = pct
                        NexusTransfer.label.value = if (totalBytes > 0) {
                            "Baixando '${arq.nomeArquivo}': $mbBaixado / $mbTotal MB ($pct%)"
                        } else {
                            "Baixando '${arq.nomeArquivo}' ($pct%)..."
                        }
                    }
                }
            } else null
        }

        if (NexusTransfer.cancelado.value) {
            NexusTransfer.encerrar()
            return@launch
        }

        if (arquivoBaixado == null && usuarioAtivo.isNotBlank()) {
            NexusTransfer.label.value = "Localizando '${arq.nomeArquivo}' na nuvem SSD..."
            val (novoId, novoThumbId) = withContext(Dispatchers.IO) {
                recuperarFileIdNaNuvemPorNome(client, usuarioAtivo, arq.nomeArquivo, arq.tamanhoBytes)
            }
            if (novoId != 0 && !NexusTransfer.cancelado.value) {
                targetFileId = novoId
                NexusTransfer.fileIdAtivo.value = novoId
                if (novoThumbId != 0) {
                    NexusThumbCache.salvarThumbIdNuvem(context, novoId, novoThumbId, arq.dataUpload)
                }
                arquivoBaixado = withContext(Dispatchers.IO) {
                    baixarArquivoTDLibAssincrono(
                        client = client,
                        fileId = novoId,
                        prioridade = 32,
                        maxTentativas = 900,
                        controlarPausaTransfer = true
                    ) { pct, baixadoBytes, totalBytes ->
                        if (!NexusTransfer.cancelado.value) {
                            val mbBaixado = String.format(Locale.US, "%.1f", baixadoBytes / (1024.0 * 1024.0))
                            val mbTotal = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                            NexusTransfer.progress.value = pct
                            NexusTransfer.label.value = if (totalBytes > 0) {
                                "Baixando '${arq.nomeArquivo}': $mbBaixado / $mbTotal MB ($pct%)"
                            } else {
                                "Baixando '${arq.nomeArquivo}' ($pct%)..."
                            }
                        }
                    }
                }
            }
        }

        if (NexusTransfer.cancelado.value) {
            NexusTransfer.encerrar()
            return@launch
        }

        NexusTransfer.encerrar()
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
    }
    NexusTransfer.jobAtivo = novoJob
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
    if (nomePasta.isBlank()) prefs.edit().remove(dataUpload.toString()).apply()
    else prefs.edit().putString(dataUpload.toString(), nomePasta.trim()).apply()
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

        if (ehArquivoImagem(nomeSeguro)) {
            if (arquivoCopiadoLocal != null && arquivoCopiadoLocal.exists()) {
                val ok = gerarMiniaturaImagemLeve(arquivoCopiadoLocal, frameFile)
                if (ok && frameFile.exists()) return Uri.fromFile(frameFile).toString()
            }
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
        if (NexusTransfer.cancelado.value) return@withContext null
        val subDir = File(context.filesDir, "tdlib_upload_cache/${System.currentTimeMillis()}").apply { if (!exists()) mkdirs() }
        val nomeLimpo = limparPrefixoTimestamp(nomeArquivo).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val f = File(subDir, nomeLimpo)
        NexusTransfer.arquivoTempAtivo = f

        context.contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(f).use { out ->
                val buffer = ByteArray(128 * 1024)
                var lidosTotal = 0L
                while (true) {
                    if (NexusTransfer.cancelado.value) {
                        out.close()
                        f.delete()
                        return@withContext null
                    }
                    while (NexusTransfer.pausado.value) {
                        delay(150)
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
                    if (tamanhoEsperado > 0L) {
                        val pctCopia = ((lidosTotal * 35L) / tamanhoEsperado).toInt().coerceIn(5, 35)
                        NexusTransfer.progress.value = pctCopia
                        NexusTransfer.label.value = "Preparando envio $loteTag'$nomeLimpo' ($pctCopia%)..."
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

fun enviarParaMensagensSalvas(
    context: Context,
    client: Client,
    usuarioAtivo: String,
    caminhoLocal: String,
    caminhoCapaJpg: String,
    nomeOriginal: String,
    pastaDestino: String,
    onResult: (Boolean, Int, String) -> Unit
) {
    if (NexusTransfer.cancelado.value) {
        onResult(false, 0, "Envio cancelado.")
        return
    }
    val nomeLimpo = limparPrefixoTimestamp(nomeOriginal)
    val pathThumbLimpo = if (caminhoCapaJpg.startsWith("file://")) {
        Uri.parse(caminhoCapaJpg).path ?: caminhoCapaJpg.removePrefix("file://")
    } else caminhoCapaJpg

    val inputThumb = if (pathThumbLimpo.isNotBlank() && File(pathThumbLimpo).exists() && File(pathThumbLimpo).length() > 256L) {
        TdApi.InputThumbnail(TdApi.InputFileLocal(pathThumbLimpo), 320, 320)
    } else null

    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    if (NexusTransfer.cancelado.value) {
                        onResult(false, 0, "Envio cancelado.")
                        return@CreatePrivateChat
                    }
                    NexusTransfer.chatIdAtivo = chatObj.id
                    val userTag = "👤 [$usuarioAtivo] • "
                    val pastaTag = if (pastaDestino.isNotBlank()) "📁 [$pastaDestino] • " else ""
                    val leg = TdApi.FormattedText("☁️ Nexus Cloud • $userTag$pastaTag$nomeLimpo", emptyArray())
                    val doc = TdApi.InputMessageDocument(TdApi.InputFileLocal(caminhoLocal), inputThumb, false, leg)
                    val req = TdApi.SendMessage().apply { chatId = chatObj.id; inputMessageContent = doc }
                    client.send(req) { msgObj ->
                        when (msgObj) {
                            is TdApi.Message -> {
                                val docResp = (msgObj.content as? TdApi.MessageDocument)?.document
                                val rId = docResp?.document?.id ?: 0
                                val tId = docResp?.thumbnail?.file?.id ?: 0
                                NexusTransfer.msgIdAtivo = msgObj.id
                                NexusTransfer.fileIdAtivo.value = rId

                                // Se o usuário apertou cancelar enquanto a mensagem estava sendo criada, apaga na hora
                                if (NexusTransfer.cancelado.value) {
                                    if (rId != 0) client.send(TdApi.DeleteFile().apply { fileId = rId }) {}
                                    client.send(TdApi.DeleteMessages().apply {
                                        this.chatId = chatObj.id
                                        this.messageIds = longArrayOf(msgObj.id)
                                        this.revoke = true
                                    }) {}
                                    onResult(false, 0, "Envio cancelado.")
                                    return@SendMessage
                                }

                                registarMensagemSincronizada(context, usuarioAtivo, msgObj.id)
                                if (rId != 0 && tId != 0) {
                                    NexusThumbCache.salvarThumbIdNuvem(context, rId, tId)
                                }
                                val txt = if (pastaDestino.isNotBlank()) "Guardado na pasta '$pastaDestino'!" else "Guardado no cofre SSD de $usuarioAtivo!"
                                onResult(true, rId, txt)
                            }
                            is TdApi.Error -> onResult(false, 0, "Erro SSD (${msgObj.code}): ${msgObj.message}")
                            else -> onResult(true, 0, "Upload SSD iniciado!")
                        }
                    }
                } else if (chatObj is TdApi.Error) onResult(false, 0, "Erro cofre SSD: ${chatObj.message}")
            }
        } else if (meObj is TdApi.Error) onResult(false, 0, "Erro sessão SSD: ${meObj.message}")
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

// === GRELHA DE ARQUIVOS AGRUPADA POR DATA, OTIMIZADA PARA CARREGAMENTO RÁPIDO ===
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
    onFileClick: (ArquivoNuvem) -> Unit,
    onFileLongClick: (ArquivoNuvem) -> Unit
) {
    val gruposPorData = remember(lista) {
        lista
            .sortedByDescending { it.dataUpload }
            .groupBy { formatarDataCabecalhoGaleria(it.dataUpload) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
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
                        .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
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

            items(
                items = arquivosDoDia,
                key = { "${it.dataUpload}_${it.nomeArquivo}" },
                contentType = { "item_midia" }
            ) { arq ->
                val ext = remember(arq.nomeArquivo) { arq.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT) }
                val pasta = mapaPastas[arq.dataUpload]
                val thumbSafe = NexusThumbCache.mapaMiniaturas[arq.dataUpload] ?: arq.caminhoMiniatura ?: ""
                val estaSelecionado = idsSelecionados.contains(arq.id)

                val isImage = remember(arq.nomeArquivo) { ehArquivoImagem(arq.nomeArquivo) }
                val isVideo = remember(arq.nomeArquivo) { ehArquivoVideo(arq.nomeArquivo) }

                val temArquivoMiniatura = remember(thumbSafe, arq.nomeArquivo) {
                    miniaturaExisteLocalmente(thumbSafe, arq.nomeArquivo)
                }

                val thumbModelRequest = remember(thumbSafe, temArquivoMiniatura) {
                    if (temArquivoMiniatura && thumbSafe.isNotBlank()) {
                        val targetUri = obterUriCapaMidia(thumbSafe)
                        ImageRequest.Builder(context)
                            .data(targetUri)
                            .size(260, 260)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .allowHardware(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .crossfade(false)
                            .build()
                    } else null
                }

                LaunchedEffect(arq.dataUpload, temArquivoMiniatura) {
                    if (!temArquivoMiniatura && !NexusThumbCache.emProgresso.contains(arq.dataUpload)) {
                        NexusThumbCache.emProgresso.add(arq.dataUpload)
                        try {
                            NexusThumbCache.downloadSemaphore.withPermit {
                                carregarMiniaturaIndividualDaNuvem(
                                    context = context,
                                    client = client,
                                    usuarioAtivo = usuarioAtivo,
                                    arq = arq,
                                    vm = vm,
                                    atualizarSubBarra = false
                                )
                            }
                        } finally {
                            NexusThumbCache.emProgresso.remove(arq.dataUpload)
                        }
                    }
                }

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
                            if (thumbModelRequest != null) {
                                AsyncImage(
                                    model = thumbModelRequest,
                                    contentDescription = arq.nomeArquivo,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                        isVideo -> {
                            Box(Modifier.fillMaxSize()) {
                                if (thumbModelRequest != null) {
                                    AsyncImage(
                                        model = thumbModelRequest,
                                        contentDescription = "Video Preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.height(28.dp))
                                        Text("Gerando capa...", color = TextGrey, fontSize = 8.sp)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .align(Alignment.Center)
                                        .background(BgBlack.copy(alpha = 0.45f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(BgBlack.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("VÍDEO", color = Gold, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        ext == "PDF" -> {
                            val possuiCapa = remember(thumbSafe) { temCapaPdfLocal(thumbSafe) }
                            if (possuiCapa && thumbModelRequest != null) {
                                Box(Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = thumbModelRequest,
                                        contentDescription = arq.nomeArquivo,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .background(DangerRed, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text("PDF", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                    Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(32.dp))
                                    Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        ext in listOf("ZIP", "RAR", "APK") -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Default.FolderZip, null, tint = Gold, modifier = Modifier.size(32.dp))
                            Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Default.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    if (estaSelecionado) {
                        Box(Modifier.fillMaxSize().background(Gold.copy(alpha = 0.22f)))
                    }

                    if (modoSelecao) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(24.dp)
                                .background(if (estaSelecionado) Gold else BgBlack.copy(alpha = 0.7f), CircleShape)
                                .border(1.5.dp, if (estaSelecionado) BgBlack else Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (estaSelecionado) {
                                Icon(Icons.Default.Check, null, tint = BgBlack, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (!pasta.isNullOrBlank()) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .background(BgBlack.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(pasta, color = Gold, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
// === FIM DA PARTE 1 (ENVIE "parte 2" PARA RECEBER O RESTANTE DO CÓDIGO) ===
// === CONTINUAÇÃO: PARTE 2 DE 2 (COLE LOGO ABAIXO DA PARTE 1) ===

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

    val transferProgress by NexusTransfer.progress.collectAsState()
    val transferLabel by NexusTransfer.label.collectAsState()
    val transferPaused by NexusTransfer.pausado.collectAsState()
    val transferIsUpload by NexusTransfer.ehUpload.collectAsState()

    val leituraAtiva by NexusLeituraNuvem.ativo.collectAsState()
    val leituraLidos by NexusLeituraNuvem.lidos.collectAsState()
    val leituraTotal by NexusLeituraNuvem.total.collectAsState()
    val leituraSubPct by NexusLeituraNuvem.subProgressoItem.collectAsState()
    val leituraTitulo by NexusLeituraNuvem.titulo.collectAsState()
    val leituraPausada by NexusLeituraNuvem.pausado.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()
    var jobFilaMiniaturas by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        NexusThumbCache.inicializar(context)
    }

    var phoneNumber by remember { mutableStateOf("+5585") }
    var smsCode by remember { mutableStateOf("") }
    var password2FA by remember { mutableStateOf("") }

    var activeVaultUser by remember { mutableStateOf(obterUltimoUsuarioCofre(context)) }
    var vaultUnlocked by remember { mutableStateOf(false) }
    var isSwitchingUser by remember { mutableStateOf(activeVaultUser.isBlank()) }
    var isCreatingNewVault by remember { mutableStateOf(false) }
    var vaultNameInput by remember { mutableStateOf(activeVaultUser) }
    var vaultPinInput by remember { mutableStateOf("") }
    var vaultErrorMsg by remember { mutableStateOf<String?>(null) }
    var isCheckingVault by remember { mutableStateOf(false) }

    // Sincroniza silenciosamente os registos da nuvem em segundo plano (mantendo os perfis ocultos na interface)
    LaunchedEffect(loginStep, vaultUnlocked) {
        if (loginStep >= 3 && !vaultUnlocked) {
            descobrirPerfisExistentesNaNuvem(context, client) { _ -> }
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

    val arquivos = remember(todosArquivos, mapaDonos, activeVaultUser) {
        val chaveAtiva = normalizarUsuario(activeVaultUser)
        if (chaveAtiva.isBlank()) emptyList()
        else todosArquivos
            .filter { mapaDonos[it.dataUpload] == chaveAtiva }
            .distinctBy { "${limparPrefixoTimestamp(it.nomeArquivo).lowercase(Locale.ROOT)}|${it.tamanhoBytes}" }
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

    // Fila paralela turbo: prioriza fotos e capas leves primeiro para não travar em vídeos grandes
    fun processarFilaMiniaturasNuvem(listaArquivosCofre: List<ArquivoNuvem>) {
        jobFilaMiniaturas?.cancel()
        jobFilaMiniaturas = scope.launch(Dispatchers.IO) {
            val pendentes = listaArquivosCofre
                .filter { arq ->
                    val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
                    val suportaCapa = ehArquivoImagem(arq.nomeArquivo) || ehArquivoVideo(arq.nomeArquivo) || ext == "PDF"
                    suportaCapa && !miniaturaExisteLocalmente(NexusThumbCache.obterMiniatura(arq), arq.nomeArquivo)
                }
                .sortedWith(
                    compareByDescending<ArquivoNuvem> {
                        (NexusThumbCache.mapaThumbIdsPorData[it.dataUpload] ?: NexusThumbCache.mapaThumbIdsNuvem[it.fileId] ?: 0) != 0
                    }
                        .thenByDescending { ehArquivoImagem(it.nomeArquivo) }
                        .thenByDescending { it.dataUpload }
                )

            if (pendentes.isEmpty()) {
                NexusLeituraNuvem.encerrar()
                return@launch
            }

            NexusLeituraNuvem.iniciar(pendentes.size, "Carregando imagens da nuvem SSD...")

            val filaSincrona = Collections.synchronizedList(pendentes.toMutableList())
            val totalItens = pendentes.size
            val workers = (1..4).map {
                launch(Dispatchers.IO) {
                    while (true) {
                        while (NexusLeituraNuvem.pausado.value && !NexusLeituraNuvem.cancelado.value) {
                            delay(150)
                        }
                        if (NexusLeituraNuvem.cancelado.value || !NexusLeituraNuvem.ativo.value) break

                        val arq = synchronized(filaSincrona) {
                            if (filaSincrona.isNotEmpty()) filaSincrona.removeAt(0) else null
                        } ?: break

                        NexusLeituraNuvem.atualizarSubProgresso(25, "Carregando: ${arq.nomeArquivo}")

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
                                        atualizarSubBarra = (totalItens <= 15)
                                    )
                                }
                            } catch (_: Exception) {
                            } finally {
                                NexusThumbCache.emProgresso.remove(arq.dataUpload)
                            }
                        }
                        NexusLeituraNuvem.concluirItem()
                    }
                }
            }
            workers.forEach { it.join() }
            NexusLeituraNuvem.encerrar()
        }
    }

    fun verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio: Boolean) {
        scope.launch {
            val chaveAtiva = normalizarUsuario(activeVaultUser)
            val listaReal = vm.obterArquivosReais()
            val donosAtuais = carregarDonosFicheiros(context)
            val arquivosAtuaisDoCofre = listaReal.filter { donosAtuais[it.dataUpload] == chaveAtiva }

            arquivosAtuaisDoCofre.forEach { arq ->
                marcarFicheiroComoSincronizado(context, activeVaultUser, arq.nomeArquivo, arq.tamanhoBytes)
            }

            val encontrados = withContext(Dispatchers.IO) {
                procurarNovosFicheirosNasPastas(context, activeVaultUser, arquivosAtuaisDoCofre)
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

    LaunchedEffect(todosArquivos.size, vaultUnlocked) {
        if (todosArquivos.isNotEmpty()) {
            limparDuplicadosExistentes(context, todosArquivos, vm) {
                mapaDonos = carregarDonosFicheiros(context)
                mapaPastas = carregarVinculosPastas(context)
                pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
            }
        }
    }

    LaunchedEffect(vaultUnlocked, activeVaultUser) {
        if (vaultUnlocked && activeVaultUser.isNotBlank()) {
            val arquivosIniciais = vm.obterArquivosReais()
            val pastasIniciais = vm.obterPastasReais()
            migrarDadosV11SeNecessario(context, activeVaultUser, arquivosIniciais, pastasIniciais)
            limparDuplicadosExistentes(context, arquivosIniciais, vm) {}
            mapaDonos = carregarDonosFicheiros(context)
            mapaPastas = carregarVinculosPastas(context)
            pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)

            val aposLimpeza = vm.obterArquivosReais()
            sincronizarCofreDaNuvem(context, client, activeVaultUser, aposLimpeza, vm) { qtd ->
                scope.launch(Dispatchers.Main) {
                    delay(200)
                    val atualizados = vm.obterArquivosReais()
                    limparDuplicadosExistentes(context, atualizados, vm) {}
                    mapaDonos = carregarDonosFicheiros(context)
                    mapaPastas = carregarVinculosPastas(context)
                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                    val chaveAtiva = normalizarUsuario(activeVaultUser)
                    val doUsuario = atualizados.filter { mapaDonos[it.dataUpload] == chaveAtiva }

                    processarFilaMiniaturasNuvem(doUsuario)

                    if (qtd > 0) {
                        Toast.makeText(context, "$qtd novo(s) ficheiro(s) sincronizados na nuvem SSD!", Toast.LENGTH_SHORT).show()
                    }
                    delay(300)
                    verificarNovosFicheirosLocalmente(mostrarAvisoSeVazio = false)
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

    var isUploading by remember { mutableStateOf(false) }
    var cloudBannerText by remember(activeVaultUser) {
        mutableStateOf(if (activeVaultUser.isNotBlank()) "Cofre: $activeVaultUser • Nuvem SSD Ativa" else "Nuvem SSD Ativa")
    }

    val listaVisivelAtual = remember(arquivos, selectedTab, pastaAberta, mapaPastas, searchQuery) {
        if (selectedTab == 1) {
            if (pastaAberta != null) {
                arquivos.filter {
                    mapaPastas[it.dataUpload]?.equals(pastaAberta, ignoreCase = true) == true &&
                        (searchQuery.isBlank() || it.nomeArquivo.contains(searchQuery, ignoreCase = true))
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
            if (searchQuery.isBlank()) base
            else base.filter { it.nomeArquivo.contains(searchQuery, ignoreCase = true) }
        }
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

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) TelegramAuth.errorText.value = "Erro SSD (${obj.code}): ${obj.message}"
        }
    }

    // Upload com BARRA ÚNICA e cancelamento real imediato
    fun executarUploads(uris: List<Uri>, pastaEscolhida: String) {
        if (uris.isEmpty() || activeVaultUser.isBlank()) return
        try { NexusTransfer.jobAtivo?.cancel() } catch (_: Exception) {}

        val job = scope.launch {
            isUploading = true
            val total = uris.size
            var enviados = 0
            NexusTransfer.iniciar("Preparando envio (1/$total)...", upload = true)

            try {
                for ((index, uri) in uris.withIndex()) {
                    if (NexusTransfer.cancelado.value) break
                    while (NexusTransfer.pausado.value) {
                        delay(150)
                        if (NexusTransfer.cancelado.value) break
                    }
                    if (NexusTransfer.cancelado.value) break

                    val (nomeBruto, tamanho) = obterDetalhesFicheiro(context, uri)
                    val nome = limparPrefixoTimestamp(nomeBruto)
                    val loteTag = "(${index + 1}/$total) "

                    NexusTransfer.progress.value = 8
                    NexusTransfer.label.value = "Preparando $loteTag'$nome'..."
                    cloudBannerText = "Enviando $loteTag'$nome'..."

                    val arqTemp = copiarUriParaCacheTDLib(context, uri, nome, tamanho, loteTag)
                    if (NexusTransfer.cancelado.value) {
                        arqTemp?.delete()
                        break
                    }

                    val capaJpg = if (arqTemp != null) {
                        withContext(Dispatchers.IO) { salvarMiniaturaPrivada(context, uri, nome, arqTemp) }
                    } else ""

                    if (arqTemp != null) {
                        while (NexusTransfer.pausado.value) {
                            delay(150)
                            if (NexusTransfer.cancelado.value) break
                        }
                        if (NexusTransfer.cancelado.value) {
                            arqTemp.delete()
                            break
                        }

                        val tamFinal = if (tamanho > 0) tamanho else arqTemp.length()
                        NexusTransfer.progress.value = 36
                        NexusTransfer.label.value = "Enviando para nuvem SSD $loteTag'$nome'..."

                        val deferred = CompletableDeferred<Triple<Boolean, Int, String>>()
                        enviarParaMensagensSalvas(context, client, activeVaultUser, arqTemp.absolutePath, capaJpg, nome, pastaEscolhida) { ok, rId, msg ->
                            deferred.complete(Triple(ok, rId, msg))
                        }
                        val (ok, rId, msg) = deferred.await()

                        val concluiuRede = if (ok && rId != 0) {
                            aguardarUploadTDLibComPausa(client, rId, nome, loteTag)
                        } else ok

                        if (NexusTransfer.cancelado.value || !concluiuRede) {
                            arqTemp.delete()
                            cloudBannerText = "Envio de '$nome' cancelado."
                            break
                        }

                        desmarcarFicheiroDeletado(context, activeVaultUser, nome, tamFinal)
                        marcarFicheiroComoSincronizado(context, activeVaultUser, nome, tamFinal, uri.toString())

                        cloudBannerText = msg
                        if (ok) {
                            enviados++
                            val ts = System.currentTimeMillis() + index
                            salvarDonoFicheiro(context, ts, activeVaultUser)
                            if (capaJpg.isNotBlank()) {
                                NexusThumbCache.salvarMiniatura(context, ts, capaJpg)
                            }
                            NexusThumbCache.salvarArquivoCompleto(context, ts, Uri.fromFile(arqTemp).toString())
                            mapaDonos = carregarDonosFicheiros(context)
                            if (pastaEscolhida.isNotBlank()) {
                                vm.criarPasta(pastaEscolhida)
                                associarPastaAoUsuario(context, activeVaultUser, pastaEscolhida)
                                pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                salvarVinculoPasta(context, ts, pastaEscolhida)
                                mapaPastas = carregarVinculosPastas(context)
                            }
                            vm.salvarArquivo(
                                ArquivoNuvem(
                                    fileId = rId,
                                    nomeArquivo = nome,
                                    tamanhoBytes = tamFinal,
                                    dataUpload = ts,
                                    caminhoMiniatura = capaJpg
                                )
                            )
                        }
                    } else if (!NexusTransfer.cancelado.value) {
                        cloudBannerText = "Erro ao ler '$nome'."
                    }
                    delay(100)
                }
            } finally {
                val foiCancelado = NexusTransfer.cancelado.value
                isUploading = false
                NexusTransfer.encerrar()
                if (foiCancelado) {
                    cloudBannerText = "Envio cancelado ($enviados guardado(s))."
                } else if (total > 1) {
                    cloudBannerText = "$enviados de $total ficheiros guardados no cofre SSD de $activeVaultUser!"
                }
            }
        }
        NexusTransfer.jobAtivo = job
    }

    // Sincronização de novos arquivos com BARRA ÚNICA e cancelamento real imediato
    fun executarUploadsNovosSync(itensParaEnviar: List<NovoArquivoSync>, manterPastasOrigem: Boolean) {
        if (itensParaEnviar.isEmpty() || activeVaultUser.isBlank()) return
        try { NexusTransfer.jobAtivo?.cancel() } catch (_: Exception) {}

        val job = scope.launch {
            isUploading = true
            val total = itensParaEnviar.size
            var enviados = 0
            NexusTransfer.iniciar("Sincronizando novos arquivos (1/$total)...", upload = true)

            try {
                for ((index, item) in itensParaEnviar.withIndex()) {
                    if (NexusTransfer.cancelado.value) break
                    while (NexusTransfer.pausado.value) {
                        delay(150)
                        if (NexusTransfer.cancelado.value) break
                    }
                    if (NexusTransfer.cancelado.value) break

                    val nomeLimpo = limparPrefixoTimestamp(item.nome)
                    val pastaAlvo = if (manterPastasOrigem) item.nomePastaOrigem.trim() else ""
                    val loteTag = "(${index + 1}/$total) "
                    NexusTransfer.progress.value = 10
                    NexusTransfer.label.value = "Sincronizando $loteTag'$nomeLimpo'..."
                    cloudBannerText = "Sincronizando $loteTag'$nomeLimpo'..."

                    val arqTemp = copiarUriParaCacheTDLib(context, item.uri, nomeLimpo, item.tamanho, loteTag)
                    if (NexusTransfer.cancelado.value) {
                        arqTemp?.delete()
                        break
                    }

                    val capaJpg = if (arqTemp != null) {
                        withContext(Dispatchers.IO) { salvarMiniaturaPrivada(context, item.uri, nomeLimpo, arqTemp) }
                    } else ""

                    if (arqTemp != null) {
                        while (NexusTransfer.pausado.value) {
                            delay(150)
                            if (NexusTransfer.cancelado.value) break
                        }
                        if (NexusTransfer.cancelado.value) {
                            arqTemp.delete()
                            break
                        }

                        val tamFinal = if (item.tamanho > 0) item.tamanho else arqTemp.length()
                        val deferred = CompletableDeferred<Triple<Boolean, Int, String>>()
                        enviarParaMensagensSalvas(context, client, activeVaultUser, arqTemp.absolutePath, capaJpg, nomeLimpo, pastaAlvo) { ok, rId, msg ->
                            deferred.complete(Triple(ok, rId, msg))
                        }
                        val (ok, rId, msg) = deferred.await()

                        val concluiuRede = if (ok && rId != 0) {
                            aguardarUploadTDLibComPausa(client, rId, nomeLimpo, loteTag)
                        } else ok

                        if (NexusTransfer.cancelado.value || !concluiuRede) {
                            arqTemp.delete()
                            cloudBannerText = "Sincronização cancelada."
                            break
                        }

                        desmarcarFicheiroDeletado(context, activeVaultUser, nomeLimpo, tamFinal)
                        marcarFicheiroComoSincronizado(context, activeVaultUser, nomeLimpo, tamFinal, item.uri.toString())

                        cloudBannerText = msg
                        if (ok) {
                            enviados++
                            val ts = System.currentTimeMillis() + index
                            salvarDonoFicheiro(context, ts, activeVaultUser)
                            if (capaJpg.isNotBlank()) {
                                NexusThumbCache.salvarMiniatura(context, ts, capaJpg)
                            }
                            NexusThumbCache.salvarArquivoCompleto(context, ts, Uri.fromFile(arqTemp).toString())
                            mapaDonos = carregarDonosFicheiros(context)
                            if (pastaAlvo.isNotBlank()) {
                                vm.criarPasta(pastaAlvo)
                                associarPastaAoUsuario(context, activeVaultUser, pastaAlvo)
                                pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                salvarVinculoPasta(context, ts, pastaAlvo)
                                mapaPastas = carregarVinculosPastas(context)
                            }
                            vm.salvarArquivo(
                                ArquivoNuvem(
                                    fileId = rId,
                                    nomeArquivo = nomeLimpo,
                                    tamanhoBytes = tamFinal,
                                    dataUpload = ts,
                                    caminhoMiniatura = capaJpg
                                )
                            )
                        }
                    }
                    delay(100)
                }
            } finally {
                val foiCancelado = NexusTransfer.cancelado.value
                isUploading = false
                NexusTransfer.encerrar()
                cloudBannerText = if (foiCancelado) {
                    "Sincronização interrompida ($enviados enviado(s))."
                } else {
                    "$enviados novo(s) ficheiro(s) sincronizados na nuvem SSD!"
                }
                Toast.makeText(context, cloudBannerText, Toast.LENGTH_SHORT).show()
            }
        }
        NexusTransfer.jobAtivo = job
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { u ->
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

    // 1. ECRÃ DE AUTENTICAÇÃO DO NÚMERO SSD (COM ASTERISCOS NA PARTE VISUAL)
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
                        onValueChange = { phoneNumber = it },
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
                    OutlinedTextField(value = smsCode, onValueChange = { smsCode = it }, label = { Text("Código SMS / SSD", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationCode(smsCode.trim()), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("CONFIRMAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                2 -> {
                    OutlinedTextField(value = password2FA, onValueChange = { password2FA = it }, label = { Text("Senha 2FA SSD", color = Color.Gray) }, singleLine = true, visualTransformation = PasswordVisualTransformation('*'), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationPassword(password2FA), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ENTRAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }

    // 2. ECRÃ DE COFRE INDIVIDUAL (PERFIS OCULTOS NA TELA, MAS RECONHECIDOS AUTOMATICAMENTE NA NUVEM)
    if (!vaultUnlocked) {
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
                    Text("SSD SOLUÇÕES", color = TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    if (!isSwitchingUser && activeVaultUser.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "COFRE DE ${activeVaultUser.uppercase(Locale.getDefault())}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Digite o seu PIN para desbloquear os seus ficheiros",
                            color = TextGrey,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(20.dp))

                        OutlinedTextField(
                            value = vaultPinInput,
                            onValueChange = { if (it.length <= 6) vaultPinInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("PIN de Acesso", color = TextGrey) },
                            singleLine = true,
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
                                val pinDigitado = vaultPinInput.trim()
                                if (pinDigitado.length < 4) {
                                    vaultErrorMsg = "O PIN deve ter pelo menos 4 dígitos."
                                    return@Button
                                }
                                val pinLocal = obterPinCofreLocal(context, activeVaultUser)
                                if (pinLocal != null && pinLocal == pinDigitado) {
                                    salvarCredenciaisCofreLocal(context, activeVaultUser, pinDigitado)
                                    vaultPinInput = ""
                                    vaultUnlocked = true
                                } else {
                                    isCheckingVault = true
                                    verificarOuRegistarCofreNuvem(client, activeVaultUser, pinDigitado, false) { ok, nomeReal, msg ->
                                        scope.launch(Dispatchers.Main) {
                                            isCheckingVault = false
                                            if (ok) {
                                                salvarCredenciaisCofreLocal(context, nomeReal, pinDigitado)
                                                activeVaultUser = nomeReal
                                                vaultPinInput = ""
                                                vaultUnlocked = true
                                            } else {
                                                vaultErrorMsg = msg
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
                        TextButton(onClick = {
                            vaultErrorMsg = null
                            vaultPinInput = ""
                            vaultNameInput = ""
                            isSwitchingUser = true
                            isCreatingNewVault = false
                        }) {
                            Text("Trocar de utilizador / Criar novo cofre", color = Gold, fontSize = 12.sp)
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

                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (isCreatingNewVault) "Escolha um Nome único e um PIN de 4 dígitos para isolar os seus ficheiros na nuvem SSD."
                            else "Digite o seu Nome e PIN para restaurar e abrir o seu cofre privado da nuvem SSD.",
                            color = TextGrey,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = vaultNameInput,
                            onValueChange = { vaultNameInput = it },
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

                                if (nomeLimpo.length < 3) {
                                    vaultErrorMsg = "O nome deve ter pelo menos 3 caracteres."
                                    return@Button
                                }
                                if (pinLimpo.length < 4) {
                                    vaultErrorMsg = "O PIN deve ter pelo menos 4 dígitos."
                                    return@Button
                                }

                                val pinLocalExistente = obterPinCofreLocal(context, nomeLimpo)
                                if (pinLocalExistente != null && pinLocalExistente == pinLimpo) {
                                    salvarCredenciaisCofreLocal(context, nomeLimpo, pinLimpo)
                                    activeVaultUser = nomeLimpo
                                    isSwitchingUser = false
                                    vaultPinInput = ""
                                    vaultUnlocked = true
                                    Toast.makeText(context, "Cofre '$nomeLimpo' desbloqueado!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                isCheckingVault = true
                                verificarOuRegistarCofreNuvem(client, nomeLimpo, pinLimpo, isCreatingNewVault) { ok, nomeConfirmado, msg ->
                                    scope.launch(Dispatchers.Main) {
                                        isCheckingVault = false
                                        if (ok) {
                                            salvarCredenciaisCofreLocal(context, nomeConfirmado, pinLimpo)
                                            activeVaultUser = nomeConfirmado
                                            isSwitchingUser = false
                                            vaultPinInput = ""
                                            vaultUnlocked = true
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
                                    if (isCreatingNewVault) "CRIAR E ABRIR COFRE" else "ENTRAR E RESTAURAR COFRE",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (activeVaultUser.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                vaultErrorMsg = null
                                vaultPinInput = ""
                                isSwitchingUser = false
                            }) {
                                Text("Voltar para o cofre de $activeVaultUser", color = TextGrey, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // === JANELA DE CACHE DO SSD NEXUS ===
    if (showCacheDialog) {
        var statsCache by remember { mutableStateOf(calcularUsoCacheLocal(context)) }
        val (bytesCache, qtdCapas, qtdTemps) = statsCache

        Dialog(onDismissRequest = { showCacheDialog = false }) {
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
                    Icon(Icons.Default.Storage, null, tint = Gold, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("CACHE DO SSD NEXUS", color = Gold, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("Gestão de Miniaturas e Arquivos Locais", color = TextGrey, fontSize = 11.sp)

                    Spacer(Modifier.height(18.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(14.dp))
                            .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ESPAÇO OCUPADO PELO CACHE", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(formatarTamanhoNuvem(bytesCache), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
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

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            val qtdRemovida = NexusThumbCache.limparCapasDeVideos(context, arquivos)
                            statsCache = calcularUsoCacheLocal(context)
                            showCacheDialog = false
                            Toast.makeText(context, "Regenerando $qtdRemovida capa(s) de vídeo com brilho otimizado...", Toast.LENGTH_SHORT).show()
                            processarFilaMiniaturasNuvem(arquivos)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = BgBlack, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("RECARREGAR CAPAS DE VÍDEOS", color = BgBlack, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(10.dp))

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
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Gold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("LIMPAR ARQUIVOS TEMPORÁRIOS", color = Gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            NexusThumbCache.limparTodoCacheLocal(context)
                            statsCache = calcularUsoCacheLocal(context)
                            showCacheDialog = false
                            Toast.makeText(context, "Cache SSD Nexus limpo! Recarregando capas da nuvem...", Toast.LENGTH_SHORT).show()
                            processarFilaMiniaturasNuvem(arquivos)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.DeleteForever, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("LIMPAR TODO O CACHE LOCAL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(10.dp))
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

    // === JANELA PARA RENOMEAR PASTA ===
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
                        onValueChange = { novoNomePastaInput = it },
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

    // === JANELA DE CONFIRMAÇÃO PARA APAGAR PASTA ===
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
                    Text("Armazenamento & Cofres Seguros", color = TextGrey, fontSize = 12.sp)

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
                        "Escolha se deseja apagar também da Nuvem SSD ou remover apenas da galeria do aplicativo.",
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
                            Text("DELETAR TUDO (APP + NUVEM SSD)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                            Text("NOVOS FICHEIROS", color = Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
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
                        onValueChange = { novoNomeInput = it },
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
                    Text("GUARDAR ${listaAtual.size} FICHEIRO(S)", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
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

    // MOVER PARA PASTA (SUPORTA 1 OU MÚLTIPLOS FICHEIROS)
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

    // VISUALIZADOR EM ECRÃ INTEIRO COM ZOOM INTERATIVO
    if (viewerFile != null) {
        val cur = viewerFile!!
        val curMediaPath = NexusThumbCache.obterArquivoCompleto(cur).ifBlank { NexusThumbCache.obterMiniatura(cur) }
        val ext = cur.nomeArquivo.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val isImage = ehArquivoImagem(cur.nomeArquivo)
        val isVideo = ehArquivoVideo(cur.nomeArquivo)
        val isPdf = ext == "PDF"
        val isText = ext in listOf("TXT", "CSV", "LOG", "JSON", "XML", "MD")

        var zoomScale by remember(cur.id) { mutableFloatStateOf(1f) }
        var zoomOffset by remember(cur.id) { mutableStateOf(Offset.Zero) }
        val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
            val novoScale = (zoomScale * zoomChange).coerceIn(1f, 5f)
            zoomScale = novoScale
            if (novoScale > 1f) {
                zoomOffset += panChange
            } else {
                zoomOffset = Offset.Zero
            }
        }

        var videoViewRef by remember { mutableStateOf<android.widget.VideoView?>(null) }
        var isPlaying by remember { mutableStateOf(true) }
        var currentPos by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(0) }

        var pdfPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
        var isLoadingDoc by remember { mutableStateOf(false) }
        var textContent by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(curMediaPath, ext) {
            if (isPdf) {
                isLoadingDoc = true
                pdfPages = withContext(Dispatchers.IO) { renderizarPaginasPdf(curMediaPath) }
                isLoadingDoc = false
            } else if (isText) {
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
                when {
                    isImage -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
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
                        }
                    }
                    isVideo -> {
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
                    isPdf -> {
                        if (isLoadingDoc) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Gold)
                                Spacer(Modifier.height(12.dp))
                                Text("A renderizar páginas do PDF...", color = Color.White, fontSize = 13.sp)
                            }
                        } else if (pdfPages.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 72.dp, bottom = 72.dp)
                                    .pointerInput(Unit) {
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

                Row(Modifier.fillMaxWidth().background(BgBlack.copy(alpha = 0.85f)).padding(12.dp).align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewerFile = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(cur.nomeArquivo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatarDataNuvem(cur.dataUpload), color = TextGrey, fontSize = 10.sp, maxLines = 1)
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

                if (isVideo) {
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
                Column(Modifier.fillMaxWidth().background(SurfaceDarker).padding(32.dp)) {
                    Icon(Icons.Default.Person, null, tint = Gold, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Cofre: $activeVaultUser", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Nexus Cloud $NEXUS_VERSION • SSD ${mascararNumeroComAsterisco(phoneNumber)}", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Storage, null) },
                    label = { Text("Armazenamento do Cofre") },
                    selected = false,
                    onClick = { showStorage = true; scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey)
                )
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
                    icon = { Icon(Icons.Default.CloudDownload, null) },
                    label = { Text("Restaurar Cofre da Nuvem SSD") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            cloudBannerText = "A procurar ficheiros de $activeVaultUser na nuvem SSD..."
                            val atuaisReais = vm.obterArquivosReais()
                            sincronizarCofreDaNuvem(context, client, activeVaultUser, atuaisReais, vm) { qtd ->
                                scope.launch(Dispatchers.Main) {
                                    delay(200)
                                    val aposSync = vm.obterArquivosReais()
                                    limparDuplicadosExistentes(context, aposSync, vm) {}
                                    mapaDonos = carregarDonosFicheiros(context)
                                    mapaPastas = carregarVinculosPastas(context)
                                    pastasDoUsuarioSet = carregarPastasDoUsuario(context, activeVaultUser)
                                    val chaveAtiva = normalizarUsuario(activeVaultUser)
                                    val doUsuario = aposSync.filter { mapaDonos[it.dataUpload] == chaveAtiva }
                                    processarFilaMiniaturasNuvem(doUsuario)
                                    cloudBannerText = if (qtd > 0) "$qtd ficheiro(s) restaurados da nuvem SSD!" else "Cofre SSD de $activeVaultUser atualizado!"
                                    Toast.makeText(context, cloudBannerText, Toast.LENGTH_SHORT).show()
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
                        NexusLeituraNuvem.cancelar()
                        NexusTransfer.cancelar(client)
                        isUploading = false
                        pastaAberta = null
                        selectedFileIds = emptySet()
                        isSelectionMode = false
                        vaultPinInput = ""
                        vaultErrorMsg = null
                        isSwitchingUser = false
                        vaultUnlocked = false
                    },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = DangerRed, unselectedIconColor = DangerRed)
                )
            }
        }
    ) {
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
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                            Column(Modifier.weight(1f)) {
                                Text("NEXUS CLOUD $NEXUS_VERSION", color = Gold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                                Text(
                                    text = if (transferProgress != null) {
                                        if (transferPaused) "⏸ PAUSADO • $transferLabel" else transferLabel
                                    } else cloudBannerText,
                                    color = if (transferProgress != null) Gold else TextGrey,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (listaVisivelAtual.isNotEmpty()) {
                                IconButton(onClick = { isSelectionMode = true }) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Selecionar vários", tint = Gold)
                                }
                            }
                            IconButton(onClick = {
                                showSearchBar = !showSearchBar
                                if (!showSearchBar) searchQuery = ""
                            }) {
                                Icon(if (showSearchBar) Icons.Default.Close else Icons.Default.Search, contentDescription = "Pesquisar", tint = Gold)
                            }
                            if (transferProgress != null || isUploading) {
                                Text("${transferProgress ?: 10}%", color = Gold, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                                if (!transferPaused) {
                                    CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                } else {
                                    Icon(Icons.Default.Pause, contentDescription = "Pausado", tint = Gold, modifier = Modifier.size(18.dp))
                                }
                            } else {
                                IconButton(onClick = {
                                    jobFilaMiniaturas?.cancel()
                                    NexusLeituraNuvem.cancelar()
                                    selectedFileIds = emptySet()
                                    isSelectionMode = false
                                    vaultPinInput = ""
                                    vaultErrorMsg = null
                                    isSwitchingUser = false
                                    vaultUnlocked = false
                                }) {
                                    Icon(Icons.Default.Lock, contentDescription = "Trancar Cofre", tint = Gold)
                                }
                            }
                        }
                    }

                    // === BARRA ÚNICA DE DOWNLOAD / ENVIO (UPLOAD) COM PAUSAR / CONTINUAR E CANCELAR (X) IMEDIATO ===
                    if (transferProgress != null || isUploading) {
                        val pctAtual = (transferProgress ?: 10).coerceIn(2, 100)
                        val fra = pctAtual / 100f
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .background(SurfaceDarker, RoundedCornerShape(10.dp))
                                .border(1.dp, if (transferPaused) DangerRed else Gold, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = when {
                                            transferPaused && transferIsUpload -> "⏸ ENVIO PAUSADO ($pctAtual%)"
                                            transferPaused && !transferIsUpload -> "⏸ DOWNLOAD PAUSADO ($pctAtual%)"
                                            transferIsUpload -> "⬆️ ENVIANDO ARQUIVO ($pctAtual%)"
                                            else -> "⬇️ BAIXANDO ARQUIVO ($pctAtual%)"
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
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { NexusTransfer.alternarPausa(client) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (transferPaused) Gold else SurfaceDark
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (transferPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = if (transferPaused) "Continuar" else "Pausar",
                                            tint = if (transferPaused) BgBlack else Gold,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (transferPaused) "CONTINUAR" else "PAUSAR",
                                            color = if (transferPaused) BgBlack else Gold,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            NexusTransfer.cancelar(client)
                                            isUploading = false
                                            cloudBannerText = "Transferência cancelada."
                                            Toast.makeText(context, "Transferência cancelada!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(DangerRed.copy(alpha = 0.25f), CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Cancelar transferência",
                                            tint = DangerRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(SurfaceDark)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fra)
                                        .height(6.dp)
                                        .background(
                                            if (transferPaused) Brush.horizontalGradient(listOf(DangerRed, DangerRed))
                                            else Brush.horizontalGradient(listOf(Gold, DangerRed))
                                        )
                                )
                            }
                        }
                    } else if (leituraAtiva && leituraTotal > 0) {
                        val lidosSafe = leituraLidos.coerceIn(0, leituraTotal)
                        val avancoSuave = (lidosSafe.toFloat() + (leituraSubPct.toFloat() / 100f)) / leituraTotal.toFloat()
                        val fracaoLeitura = avancoSuave.coerceIn(0.02f, 1f)
                        val pctLeitura = (fracaoLeitura * 100).toInt().coerceIn(1, 100)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .background(SurfaceDarker, RoundedCornerShape(10.dp))
                                .border(1.dp, Gold.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (leituraPausada) "⏸ PAUSADO: ${leituraTitulo.ifBlank { "Leitura da nuvem SSD" }}"
                                    else leituraTitulo.ifBlank { "Lendo arquivos da nuvem SSD..." },
                                    color = if (leituraPausada) Gold else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                Text(
                                    text = "${(lidosSafe + 1).coerceAtMost(leituraTotal)} / $leituraTotal ($pctLeitura%)",
                                    color = Gold,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                IconButton(
                                    onClick = { NexusLeituraNuvem.alternarPausa() },
                                    modifier = Modifier.size(26.dp)
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
                                        jobFilaMiniaturas?.cancel()
                                        NexusLeituraNuvem.cancelar()
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancelar leitura",
                                        tint = DangerRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(SurfaceDark)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fracaoLeitura)
                                        .height(6.dp)
                                        .background(Brush.horizontalGradient(listOf(Gold, DangerRed)))
                                )
                            }
                        }
                    }

                    if (showSearchBar) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Pesquisar no cofre SSD de $activeVaultUser...", color = TextGrey, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = Gold) },
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
                                "Pastas do cofre ($activeVaultUser) • Toque numa pasta para ver os arquivos ou nos 3 pontos para editar:",
                                color = TextGrey,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
                            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(pastasFiltradas, key = { it.nomePasta.trim().lowercase(Locale.ROOT) }) { p ->
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
                                                        .size(240, 240)
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
                        Text("Cofre SSD: $activeVaultUser • Tamanho: ${formatarTamanhoNuvem(arq.tamanhoBytes)} • Pasta: ${mapaPastas[arq.dataUpload] ?: "Geral"}\nAdicionado em: ${formatarDataNuvem(arq.dataUpload)}", color = TextGrey, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
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
                            headlineContent = { Text("Copiar / Baixar da Nuvem SSD", color = Gold) },
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
                            OutlinedTextField(value = nomeNovaPasta, onValueChange = { nomeNovaPasta = it }, label = { Text("Nome", color = TextGrey) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                val n = nomeNovaPasta.trim()
                                if (n.isNotBlank()) {
                                    vm.criarPasta(n)
                                    associarPastaAoUsuario(context, activeVaultUser, n)
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
                            Text("COFRE SSD: ${activeVaultUser.uppercase(Locale.getDefault())}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(formatarTamanhoNuvem(armazenamentoUsuario), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                            Text("${arquivos.size} ficheiro(s) neste perfil", color = TextGrey, fontSize = 12.sp)
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
                            Text("BACKUP AUTOMÁTICO SSD ($activeVaultUser)", color = Gold, fontWeight = FontWeight.Black, fontSize = 16.sp, textAlign = TextAlign.Center)
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
// === FIM DO FICHEIRO ===
