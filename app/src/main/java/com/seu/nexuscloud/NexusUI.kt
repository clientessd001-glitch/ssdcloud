package com.seu.nexuscloud

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
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

// === VERSÃO GLOBAL E TEMA NEXUS CLOUD v1.9 ===
const val NEXUS_VERSION = "v1.9"

val BgBlack = Color(0xFF0A0A0C)
val SurfaceDark = Color(0xFF16161A)
val SurfaceDarker = Color(0xFF101014)
val Gold = Color(0xFFD4AF37)
val DangerRed = Color(0xFFE53935)
val TextGrey = Color(0xFF9E9E9E)

// === MOTOR DE CONTROLE DE TRANSFERÊNCIAS (PAUSA / CANCELAMENTO SEGURO) ===
object NexusTransfer {
    @Volatile var pausado: Boolean = false
    @Volatile var cancelado: Boolean = false
    @Volatile var fileIdAtivo: Int = 0
    val idsCancelados = ConcurrentHashMap.newKeySet<Int>()

    fun resetarParaNovoFluxo() {
        pausado = false
        cancelado = false
        fileIdAtivo = 0
    }

    fun cancelarTudo(client: Client?) {
        cancelado = true
        pausado = false
        val id = fileIdAtivo
        if (id != 0) {
            idsCancelados.add(id)
            try {
                client?.send(TdApi.CancelPreliminaryUploadFile(id)) {}
                client?.send(TdApi.CancelDownloadFile(id, false)) {}
            } catch (_: Exception) {}
        }
    }
}

// === VIEWMODEL CONECTADO AO NEXUSDAO ===
class UIViewModel(private val dao: NexusDao) : ViewModel() {
    val arquivos = dao.buscarTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pastas = dao.buscarPastas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val armazenamento = dao.calcularUsoTotal()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun criarPasta(nome: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.criarPasta(PastaNuvem(nomePasta = nome))
        }
    }

    fun salvarArquivo(arq: ArquivoNuvem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.salvarArquivo(arq)
        }
    }

    fun apagar(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.apagarArquivo(id)
        }
    }

    fun apagar(arq: ArquivoNuvem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.apagarArquivo(arq.id)
        }
    }
}

// === FUNÇÕES UTILITÁRIAS DE FORMATAÇÃO E PRIVACIDADE ===
fun mascararNumeroComAsterisco(numero: String): String {
    val limpo = numero.trim()
    if (limpo.length <= 4) return "****"
    val prefixo = limpo.take(4)
    val sufixo = limpo.takeLast(2)
    return "$prefixo*****$sufixo"
}

fun formatarDataNuvem(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
    return sdf.format(Date(timestamp))
}

fun formatarDataCurta(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
    return sdf.format(Date(timestamp))
}

fun formatarTempoVideo(ms: Long): String {
    val totalSegundos = max(0L, ms / 1000L)
    val minutos = totalSegundos / 60L
    val segundos = totalSegundos % 60L
    return String.format(Locale.getDefault(), "%02d:%02d", minutos, segundos)
}

fun formatarTamanhoNuvem(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

// === PREFERÊNCIAS DE PASTAS E AUTO-SYNC POR COFRE ===
fun carregarVinculosPastas(context: Context, cofreTag: String): Map<String, String> {
    val prefs = context.getSharedPreferences("nexus_pastas_$cofreTag", Context.MODE_PRIVATE)
    val mapa = mutableMapOf<String, String>()
    for ((k, v) in prefs.all) {
        if (v is String) mapa[k] = v
    }
    return mapa
}

fun salvarVinculoPasta(context: Context, cofreTag: String, chaveArquivo: String, nomePasta: String) {
    val prefs = context.getSharedPreferences("nexus_pastas_$cofreTag", Context.MODE_PRIVATE)
    prefs.edit().putString(chaveArquivo, nomePasta).apply()
}

fun carregarPastasSync(context: Context, cofreTag: String): Set<String> {
    val prefs = context.getSharedPreferences("nexus_sync_$cofreTag", Context.MODE_PRIVATE)
    return prefs.getStringSet("uris_sync", emptySet())?.toSet() ?: emptySet()
}

fun salvarPastaSync(context: Context, cofreTag: String, uriStr: String) {
    val prefs = context.getSharedPreferences("nexus_sync_$cofreTag", Context.MODE_PRIVATE)
    val atual = prefs.getStringSet("uris_sync", emptySet())?.toMutableSet() ?: mutableSetOf()
    atual.add(uriStr)
    prefs.edit().putStringSet("uris_sync", atual).apply()
}

fun removerPastaSync(context: Context, cofreTag: String, uriStr: String) {
    val prefs = context.getSharedPreferences("nexus_sync_$cofreTag", Context.MODE_PRIVATE)
    val atual = prefs.getStringSet("uris_sync", emptySet())?.toMutableSet() ?: mutableSetOf()
    atual.remove(uriStr)
    prefs.edit().putStringSet("uris_sync", atual).apply()
}

fun obterDetalhesFicheiro(context: Context, uri: Uri): Pair<String, Long> {
    var nome = "arquivo_${System.currentTimeMillis()}"
    var tamanho = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                    nome = cursor.getString(nameIdx)
                }
                if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                    tamanho = cursor.getLong(sizeIdx)
                }
            }
        }
    } catch (_: Exception) {}
    return Pair(nome, tamanho)
}

fun descobrirMimeType(nome: String): String {
    val ext = nome.substringAfterLast('.', "").lowercase(Locale.getDefault())
    if (ext.isEmpty()) return "*/*"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
        "mp4", "mkv", "mov", "avi", "webm", "3gp" -> "video/*"
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "image/*"
        "pdf" -> "application/pdf"
        "txt", "log", "json", "xml", "csv", "md", "kt" -> "text/plain"
        "mp3", "wav", "ogg", "m4a", "flac" -> "audio/*"
        else -> "*/*"
    }
}

// === PROCESSAMENTO DE MINIATURAS E CORREÇÃO DE CAPA DE VÍDEO ESCURA ===
fun calcularBrilhoMedioBitmap(bmp: Bitmap): Double {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return 0.0
    var soma = 0L
    var amostras = 0
    val passoX = max(1, w / 20)
    val passoY = max(1, h / 20)
    for (y in 0 until h step passoY) {
        for (x in 0 until w step passoX) {
            val pixel = bmp.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            soma += (r * 299 + g * 587 + b * 114) / 1000
            amostras++
        }
    }
    return if (amostras > 0) soma.toDouble() / amostras else 0.0
}

fun bitmapNaoEhTotalmentePreto(bmp: Bitmap): Boolean {
    return calcularBrilhoMedioBitmap(bmp) > 14.0
}

fun clarearBitmapVideoSeNecessario(bmp: Bitmap): Bitmap {
    val brilho = calcularBrilhoMedioBitmap(bmp)
    if (brilho >= 42.0) return bmp
    return try {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val ganho = if (brilho < 18.0) 1.65f else 1.35f
        val offset = if (brilho < 18.0) 38f else 22f
        val cm = ColorMatrix(
            floatArrayOf(
                ganho, 0f, 0f, 0f, offset,
                0f, ganho, 0f, 0f, offset,
                0f, 0f, ganho, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        out
    } catch (_: Exception) {
        bmp
    }
}

fun extrairCapaVideoParaArquivo(context: Context, videoFile: File, destinoJpg: File): Boolean {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(videoFile.absolutePath)
        val duracaoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val duracaoMs = duracaoStr?.toLongOrNull() ?: 0L

        val temposTesteUs = listOf(
            min(1_500_000L, max(200_000L, (duracaoMs * 1000L) / 5L)),
            min(3_500_000L, max(500_000L, (duracaoMs * 1000L) / 3L)),
            min(7_000_000L, max(1_000_000L, (duracaoMs * 1000L) / 2L)),
            300_000L,
            0L
        )

        var melhorFrame: Bitmap? = null
        var melhorBrilho = -1.0

        for (tempoUs in temposTesteUs) {
            val frame = try {
                retriever.getFrameAtTime(tempoUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(tempoUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (_: Exception) {
                null
            }
            if (frame != null) {
                val brilho = calcularBrilhoMedioBitmap(frame)
                if (brilho > melhorBrilho) {
                    melhorBrilho = brilho
                    melhorFrame = frame
                }
                if (bitmapNaoEhTotalmentePreto(frame) && brilho >= 36.0) {
                    break
                }
            }
        }

        if (melhorFrame == null) {
            melhorFrame = retriever.frameAtTime
        }

        if (melhorFrame != null) {
            val corrigido = clarearBitmapVideoSeNecessario(melhorFrame)
            val w = corrigido.width
            val h = corrigido.height
            val maxDim = 420
            val escala = min(maxDim.toFloat() / max(1, w), maxDim.toFloat() / max(1, h)).coerceAtMost(1f)
            val finalBmp = if (escala < 1f) {
                Bitmap.createScaledBitmap(
                    corrigido,
                    max(1, (w * escala).toInt()),
                    max(1, (h * escala).toInt()),
                    true
                )
            } else corrigido

            destinoJpg.parentFile?.mkdirs()
            FileOutputStream(destinoJpg).use { out ->
                finalBmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            return destinoJpg.exists() && destinoJpg.length() > 100L
        }
    } catch (_: Exception) {
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {}
    }
    return false
}

fun gerarMiniaturaImagemLeve(origemFile: File, destinoJpg: File, maxDim: Int = 360): Boolean {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(origemFile.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
        var sample = 1
        while (opts.outWidth / sample > maxDim * 2 || opts.outHeight / sample > maxDim * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(origemFile.absolutePath, decodeOpts) ?: return false
        val escala = min(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height).coerceAtMost(1f)
        val reduzido = if (escala < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                max(1, (bmp.width * escala).toInt()),
                max(1, (bmp.height * escala).toInt()),
                true
            )
        } else bmp
        destinoJpg.parentFile?.mkdirs()
        FileOutputStream(destinoJpg).use { out ->
            reduzido.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        destinoJpg.exists() && destinoJpg.length() > 100L
    } catch (_: Exception) {
        false
    }
}

fun gerarCapaPdf(context: Context, uri: Uri, nome: String): String? {
    return try {
        val dir = File(context.cacheDir, "thumbs_privadas").apply { mkdirs() }
        val outFile = File(dir, "pdf_${nome.hashCode()}_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount > 0) {
                    renderer.openPage(0).use { page ->
                        val bmp = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(outFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        }
                        return outFile.absolutePath
                    }
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

fun renderizarPaginasPdf(context: Context, file: File): List<Bitmap> {
    val lista = mutableListOf<Bitmap>()
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val total = min(renderer.pageCount, 25)
                for (i in 0 until total) {
                    renderer.openPage(i).use { page ->
                        val w = max(600, min(1200, page.width * 2))
                        val h = max(800, min(1600, page.height * 2))
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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

fun lerTextoDocumento(file: File, maxChars: Int = 50000): String {
    return try {
        val texto = file.readText(Charsets.UTF_8)
        if (texto.length > maxChars) texto.take(maxChars) + "\n\n[... Documento truncado na pré-visualização ...]"
        else texto
    } catch (_: Exception) {
        "Não foi possível exibir o texto deste documento internamente."
    }
}

fun salvarMiniaturaPrivada(context: Context, uri: Uri, nome: String): String? {
    val ext = nome.substringAfterLast('.', "").lowercase(Locale.getDefault())
    val dir = File(context.cacheDir, "thumbs_privadas").apply { mkdirs() }
    val destino = File(dir, "thumb_${nome.hashCode()}_${System.currentTimeMillis()}.jpg")

    if (ext == "pdf") {
        return gerarCapaPdf(context, uri, nome)
    }

    val isVideo = ext in listOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
    val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "heic")

    if (isVideo) {
        val tempVid = copiarUriParaCacheTDLib(context, uri, "tmp_thumb_$nome")
        if (tempVid != null) {
            val ok = extrairCapaVideoParaArquivo(context, tempVid, destino)
            tempVid.delete()
            if (ok) return destino.absolutePath
        }
    } else if (isImage) {
        val tempImg = copiarUriParaCacheTDLib(context, uri, "tmp_img_$nome")
        if (tempImg != null) {
            val ok = gerarMiniaturaImagemLeve(tempImg, destino, 360)
            tempImg.delete()
            if (ok) return destino.absolutePath
        }
    }
    return null
}

fun salvarMiniaturaRapidaTDLib(context: Context, minithumb: TdApi.Minithumbnail?, chaveUnica: String): String? {
    if (minithumb == null || minithumb.data == null || minithumb.data.isEmpty()) return null
    return try {
        val dir = File(context.cacheDir, "thumbs_privadas").apply { mkdirs() }
        val arq = File(dir, "tdmini_${chaveUnica.hashCode()}.jpg")
        if (!arq.exists() || arq.length() < 50L) {
            FileOutputStream(arq).use { it.write(minithumb.data) }
        }
        if (arq.exists() && arq.length() > 50L) arq.absolutePath else null
    } catch (_: Exception) {
        null
    }
}

fun prepararMiniaturaDeFicheiroBaixado(context: Context, localFile: File, nome: String): String? {
    if (!localFile.exists()) return null
    val ext = nome.substringAfterLast('.', "").lowercase(Locale.getDefault())
    val dir = File(context.cacheDir, "thumbs_privadas").apply { mkdirs() }
    val destino = File(dir, "local_${nome.hashCode()}_${localFile.length()}.jpg")
    if (destino.exists() && destino.length() > 150L) return destino.absolutePath

    return when {
        ext in listOf("mp4", "mkv", "mov", "avi", "webm", "3gp") -> {
            if (extrairCapaVideoParaArquivo(context, localFile, destino)) destino.absolutePath else null
        }
        ext in listOf("jpg", "jpeg", "png", "webp", "bmp") -> {
            if (gerarMiniaturaImagemLeve(localFile, destino, 360)) destino.absolutePath else null
        }
        ext == "pdf" -> {
            gerarCapaPdf(context, Uri.fromFile(localFile), nome)
        }
        else -> null
    }
}

fun obterUriCapaMidia(context: Context, arq: ArquivoNuvem): String? {
    val caminho = arq.caminhoMiniatura
    if (!caminho.isNullOrBlank()) {
        val f = File(caminho)
        if (f.exists() && f.length() > 50L) return f.absolutePath
    }
    val cacheFile = File(File(context.cacheDir, "tdlib_upload_cache"), arq.nomeArquivo)
    if (cacheFile.exists() && cacheFile.length() > 0L) {
        val gerada = prepararMiniaturaDeFicheiroBaixado(context, cacheFile, arq.nomeArquivo)
        if (!gerada.isNullOrBlank()) return gerada
    }
    return null
}

fun temCapaPdfLocal(arq: ArquivoNuvem): Boolean {
    val c = arq.caminhoMiniatura ?: return false
    return File(c).exists()
}

fun copiarUriParaCacheTDLib(context: Context, uri: Uri, nome: String): File? {
    return try {
        val dir = File(context.cacheDir, "tdlib_upload_cache").apply { mkdirs() }
        val nomeSeguro = nome.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val destino = File(dir, nomeSeguro)
        if (destino.exists() && destino.length() > 0L && !nome.startsWith("tmp_")) {
            return destino
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destino).use { output ->
                val buffer = ByteArray(64 * 1024)
                var lidos: Int
                while (input.read(buffer).also { lidos = it } != -1) {
                    if (NexusTransfer.cancelado) break
                    output.write(buffer, 0, lidos)
                }
            }
        }
        if (NexusTransfer.cancelado) {
            destino.delete()
            null
        } else if (destino.exists() && destino.length() > 0L) {
            destino
        } else null
    } catch (_: Exception) {
        null
    }
}

fun prepararUriPublica(context: Context, file: File): Uri? {
    return try {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    } catch (_: Exception) {
        try {
            Uri.fromFile(file)
        } catch (_: Exception) {
            null
        }
    }
}

fun abrirFicheiroExterno(context: Context, file: File, nome: String) {
    try {
        val uri = prepararUriPublica(context, file) ?: return
        val mime = descobrirMimeType(nome)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Abrir com").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, "Nenhum aplicativo encontrado para abrir este arquivo.", Toast.LENGTH_SHORT).show()
    }
}

fun compartilharFicheiroNativo(context: Context, file: File, nome: String) {
    try {
        val uri = prepararUriPublica(context, file) ?: return
        val mime = descobrirMimeType(nome)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar $nome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, "Erro ao compartilhar o arquivo.", Toast.LENGTH_SHORT).show()
    }
}

// === ENVIO REAL PARA MENSAGENS SALVAS (COMPATÍVEL COM TDAPI INPUTDOCUMENT) ===
suspend fun enviarParaMensagensSalvas(
    context: Context,
    client: Client,
    arquivoLocal: File,
    nomeOriginal: String,
    cofreTag: String,
    pastaDestino: String,
    caminhoThumbLocal: String?,
    onProgress: (Float) -> Unit
): Int = withContext(Dispatchers.IO) {
    if (!arquivoLocal.exists() || arquivoLocal.length() <= 0L) return@withContext -1

    // 1. Descobrir o ID do próprio usuário (Mensagens Salvas)
    val myUserIdRef = AtomicReference<Long>(0L)
    val meLatch = CountDownLatch(1)
    client.send(TdApi.GetMe()) { res ->
        if (res is TdApi.User) {
            myUserIdRef.set(res.id)
        }
        meLatch.countDown()
    }
    meLatch.await(12, TimeUnit.SECONDS)
    val myUserId = myUserIdRef.get()
    if (myUserId == 0L) return@withContext -1

    // 2. Garantir criação/abertura do chat privado "Mensagens Salvas"
    val chatIdRef = AtomicReference<Long>(0L)
    val chatLatch = CountDownLatch(1)
    client.send(TdApi.CreatePrivateChat(myUserId, false)) { res ->
        if (res is TdApi.Chat) {
            chatIdRef.set(res.id)
        }
        chatLatch.countDown()
    }
    chatLatch.await(12, TimeUnit.SECONDS)
    val savedMessagesChatId = chatIdRef.get()
    if (savedMessagesChatId == 0L) return@withContext -1

    // 3. Montar a legenda com a tag de isolamento do cofre e pasta
    val legendaTexto = "NEXUS_VAULT:$cofreTag|FOLDER:$pastaDestino|FILE:$nomeOriginal"
    val legendaFormatada = TdApi.FormattedText(legendaTexto, emptyArray())

    val fileLocal = TdApi.InputFileLocal(arquivoLocal.absolutePath)
    val conteudoDoc = TdApi.InputMessageDocument().apply {
        caption = legendaFormatada
        try {
            val docField = javaClass.getDeclaredField("document")
            docField.isAccessible = true
            if (docField.type.isAssignableFrom(fileLocal.javaClass)) {
                docField.set(this, fileLocal)
            } else {
                val inputDocClass = docField.type
                var inst: Any? = null
                for (ctor in inputDocClass.constructors) {
                    val p = ctor.parameterTypes
                    try {
                        if (p.size == 3 && p[0].isAssignableFrom(fileLocal.javaClass)) {
                            inst = ctor.newInstance(fileLocal, null, false)
                            break
                        } else if (p.size == 2 && p[0].isAssignableFrom(fileLocal.javaClass)) {
                            inst = ctor.newInstance(fileLocal, null)
                            break
                        } else if (p.size == 1 && p[0].isAssignableFrom(fileLocal.javaClass)) {
                            inst = ctor.newInstance(fileLocal)
                            break
                        }
                    } catch (_: Exception) {}
                }
                if (inst == null) {
                    inst = inputDocClass.getDeclaredConstructor().newInstance()
                    for (f in inputDocClass.declaredFields) {
                        f.isAccessible = true
                        if (f.type.isAssignableFrom(fileLocal.javaClass)) {
                            f.set(inst, fileLocal)
                        }
                    }
                }
                docField.set(this, inst)
            }
        } catch (_: Exception) {}
    }

    val msgEnviadaRef = AtomicReference<TdApi.Message?>(null)
    val sendLatch = CountDownLatch(1)

    val reqEnvio = TdApi.SendMessage().apply {
        chatId = savedMessagesChatId
        inputMessageContent = conteudoDoc
    }

    client.send(reqEnvio) { res ->
        if (res is TdApi.Message) {
            msgEnviadaRef.set(res)
        }
        sendLatch.countDown()
    }
    sendLatch.await(20, TimeUnit.SECONDS)

    val msgInicial = msgEnviadaRef.get() ?: return@withContext -1
    val docInicial = (msgInicial.content as? TdApi.MessageDocument)?.document ?: return@withContext -1
    var fileId = docInicial.document.id
    NexusTransfer.fileIdAtivo = fileId

    // Registrar vínculos locais imediatamente para que o arquivo nunca se perca
    salvarVinculoPasta(context, cofreTag, nomeOriginal, pastaDestino)

    // 4. Acompanhar o upload real na TDLib até atingir 100% nas Mensagens Salvas
    val totalBytes = max(1L, arquivoLocal.length())
    var tentativasSemAtividade = 0
    var uploadConcluido = docInicial.document.remote?.isUploadingCompleted == true &&
            !docInicial.document.remote?.id.isNullOrEmpty()

    while (!uploadConcluido && !NexusTransfer.cancelado) {
        if (NexusTransfer.pausado) {
            delay(300)
            continue
        }

        val fileRef = AtomicReference<TdApi.File?>(null)
        val fileLatch = CountDownLatch(1)
        client.send(TdApi.GetFile(fileId)) { res ->
            if (res is TdApi.File) fileRef.set(res)
            fileLatch.countDown()
        }
        fileLatch.await(5, TimeUnit.SECONDS)

        val f = fileRef.get()
        if (f != null) {
            val remote = f.remote
            val enviados = remote?.uploadedSize ?: 0L
            val prog = (enviados.toFloat() / totalBytes.toFloat()).coerceIn(0.02f, 0.99f)
            onProgress(prog)

            if (remote != null && remote.isUploadingCompleted && !remote.id.isNullOrEmpty()) {
                uploadConcluido = true
                fileId = f.id
                break
            }

            if (remote != null && !remote.isUploadingActive && !remote.isUploadingCompleted) {
                tentativasSemAtividade++
                if (tentativasSemAtividade > 60) {
                    break
                }
            } else {
                tentativasSemAtividade = 0
            }
        }
        delay(250)
    }

    if (NexusTransfer.cancelado) {
        try {
            client.send(TdApi.DeleteMessages(savedMessagesChatId, longArrayOf(msgInicial.id), true)) {}
        } catch (_: Exception) {}
        return@withContext -1
    }

    onProgress(1.0f)
    return@withContext fileId
}

// === DOWNLOAD DE ARQUIVOS DA NUVEM TDLIB ===
suspend fun baixarFicheiroDaNuvem(
    client: Client,
    fileId: Int,
    onProgress: (Float) -> Unit = {}
): File? = withContext(Dispatchers.IO) {
    if (fileId <= 0) return@withContext null
    NexusTransfer.fileIdAtivo = fileId

    val fileInicialRef = AtomicReference<TdApi.File?>(null)
    val initLatch = CountDownLatch(1)
    client.send(TdApi.GetFile(fileId)) { res ->
        if (res is TdApi.File) fileInicialRef.set(res)
        initLatch.countDown()
    }
    initLatch.await(8, TimeUnit.SECONDS)

    val inicial = fileInicialRef.get()
    if (inicial?.local?.isDownloadingCompleted == true && !inicial.local.path.isNullOrEmpty()) {
        val fLocal = File(inicial.local.path)
        if (fLocal.exists() && fLocal.length() > 0L) {
            onProgress(1f)
            return@withContext fLocal
        }
    }

    client.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) {}

    var tentativas = 0
    while (tentativas < 1200 && !NexusTransfer.cancelado) {
        if (NexusTransfer.pausado) {
            delay(300)
            continue
        }
        val fRef = AtomicReference<TdApi.File?>(null)
        val l = CountDownLatch(1)
        client.send(TdApi.GetFile(fileId)) { res ->
            if (res is TdApi.File) fRef.set(res)
            l.countDown()
        }
        l.await(5, TimeUnit.SECONDS)
        val f = fRef.get()
        if (f != null) {
            val total = max(1L, f.expectedSize.takeIf { it > 0 } ?: f.size)
            val baixado = f.local?.downloadedSize ?: 0L
            onProgress((baixado.toFloat() / total.toFloat()).coerceIn(0.01f, 0.99f))

            if (f.local?.isDownloadingCompleted == true && !f.local.path.isNullOrEmpty()) {
                val arqFinal = File(f.local.path)
                if (arqFinal.exists() && arqFinal.length() > 0L) {
                    onProgress(1f)
                    return@withContext arqFinal
                }
            }
            if (f.local?.isDownloadingActive == false && f.local?.isDownloadingCompleted == false) {
                client.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) {}
            }
        }
        tentativas++
        delay(250)
    }
    null
}

suspend fun garantirFicheiroLocal(
    context: Context,
    client: Client,
    arq: ArquivoNuvem,
    onProgress: (Float) -> Unit = {}
): File? = withContext(Dispatchers.IO) {
    val cacheUpload = File(File(context.cacheDir, "tdlib_upload_cache"), arq.nomeArquivo)
    if (cacheUpload.exists() && cacheUpload.length() > 0L) {
        onProgress(1f)
        return@withContext cacheUpload
    }
    baixarFicheiroDaNuvem(client, arq.fileId, onProgress)
}

// === EXTRAÇÃO E SINCRONIZAÇÃO DE MENSAGENS SALVAS POR COFRE ===
fun extrairNomeLimpoDaMensagem(legenda: String, nomeDocPadrao: String): Triple<String?, String, String> {
    if (legenda.startsWith("NEXUS_VAULT:")) {
        val partes = legenda.split("|")
        val cofre = partes.find { it.startsWith("NEXUS_VAULT:") }?.removePrefix("NEXUS_VAULT:")?.trim()
        val pasta = partes.find { it.startsWith("FOLDER:") }?.removePrefix("FOLDER:")?.trim() ?: "Geral"
        val nome = partes.find { it.startsWith("FILE:") }?.removePrefix("FILE:")?.trim() ?: nomeDocPadrao
        return Triple(cofre, pasta, nome)
    }
    return Triple(null, "Geral", nomeDocPadrao)
}

suspend fun sincronizarCofreDaNuvem(
    context: Context,
    client: Client,
    vm: UIViewModel,
    cofreTag: String,
    arquivosAtuais: List<ArquivoNuvem>
) = withContext(Dispatchers.IO) {
    val meRef = AtomicReference<TdApi.User?>(null)
    val l1 = CountDownLatch(1)
    client.send(TdApi.GetMe()) { res ->
        if (res is TdApi.User) meRef.set(res)
        l1.countDown()
    }
    l1.await(8, TimeUnit.SECONDS)
    val me = meRef.get() ?: return@withContext

    val chatRef = AtomicReference<TdApi.Chat?>(null)
    val l2 = CountDownLatch(1)
    client.send(TdApi.CreatePrivateChat(me.id, false)) { res ->
        if (res is TdApi.Chat) chatRef.set(res)
        l2.countDown()
    }
    l2.await(8, TimeUnit.SECONDS)
    val chat = chatRef.get() ?: return@withContext

    val msgsRef = AtomicReference<TdApi.Messages?>(null)
    val l3 = CountDownLatch(1)
    client.send(TdApi.GetChatHistory(chat.id, 0, 0, 100, false)) { res ->
        if (res is TdApi.Messages) msgsRef.set(res)
        l3.countDown()
    }
    l3.await(10, TimeUnit.SECONDS)
    val mensagens = msgsRef.get()?.messages ?: return@withContext

    val nomesExistentes = arquivosAtuais.map { it.nomeArquivo }.toMutableSet()

    for (msg in mensagens) {
        val content = msg.content
        if (content is TdApi.MessageDocument) {
            val legenda = content.caption?.text ?: ""
            val doc = content.document
            val (cofreMsg, pastaMsg, nomeLimpo) = extrairNomeLimpoDaMensagem(legenda, doc.fileName ?: "arquivo_${msg.id}")
            if (cofreMsg == cofreTag && !nomesExistentes.contains(nomeLimpo)) {
                val thumbRapida = salvarMiniaturaRapidaTDLib(context, doc.minithumbnail, "${msg.id}_$nomeLimpo")
                salvarVinculoPasta(context, cofreTag, nomeLimpo, pastaMsg)
                vm.criarPasta(pastaMsg)
                vm.salvarArquivo(
                    ArquivoNuvem(
                        fileId = doc.document.id,
                        nomeArquivo = nomeLimpo,
                        tamanhoBytes = doc.document.size.takeIf { it > 0 } ?: doc.document.expectedSize,
                        dataUpload = msg.date.toLong() * 1000L,
                        caminhoMiniatura = thumbRapida
                    )
                )
                nomesExistentes.add(nomeLimpo)
            }
        }
    }
}

// === AUTO-SYNC COM DOCUMENTSCONTRACT NATIVO (SEM PRECISAR DE DOCUMENTFILE) ===
suspend fun procurarNovosFicheirosNasPastas(
    context: Context,
    cofreTag: String,
    nomesJaSalvos: Set<String>
): List<Pair<Uri, String>> = withContext(Dispatchers.IO) {
    val encontrados = mutableListOf<Pair<Uri, String>>()
    val pastasSync = carregarPastasSync(context, cofreTag)
    for (uriStr in pastasSync) {
        try {
            val treeUri = Uri.parse(uriStr)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val nomePasta = docId.substringAfterLast(':').substringAfterLast('/').ifBlank { "AutoSync" }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: continue
                    val nome = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR && !nomesJaSalvos.contains(nome)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        encontrados.add(Pair(fileUri, nomePasta))
                    }
                }
            }
        } catch (_: Exception) {}
    }
    encontrados
}

// === COMPONENTES VISUAIS DE BARRA DE PROGRESSO E GRELHA ===
@Composable
fun LinhaSincronizacao(
    visivel: Boolean,
    textoStatus: String,
    progresso: Float,
    pausado: Boolean,
    onTogglePausa: () -> Unit,
    onCancelar: () -> Unit
) {
    AnimatedVisibility(visible = visivel) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (pausado) "PAUSADO • $textoStatus" else textoStatus,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${(progresso * 100).toInt()}% concluído",
                            color = Gold,
                            fontSize = 11.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onTogglePausa) {
                            Icon(
                                imageVector = if (pausado) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Pausar ou Retomar",
                                tint = Gold
                            )
                        }
                        IconButton(onClick = onCancelar) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancelar",
                                tint = DangerRed
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progresso.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Gold,
                    trackColor = SurfaceDarker
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GrelhaArquivos(
    arquivos: List<ArquivoNuvem>,
    selecionados: Set<Int>,
    modoSelecao: Boolean,
    onClickArquivo: (ArquivoNuvem) -> Unit,
    onLongClickArquivo: (ArquivoNuvem) -> Unit
) {
    val context = LocalContext.current
    val agrupados = remember(arquivos) {
        arquivos.sortedByDescending { it.dataUpload }
            .groupBy { formatarDataNuvem(it.dataUpload) }
    }

    if (arquivos.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = TextGrey,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Nenhum arquivo neste cofre ou pasta",
                    color = TextGrey,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        agrupados.forEach { (dataTitulo, listaDia) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "header_$dataTitulo") {
                Text(
                    text = dataTitulo,
                    color = Gold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
                )
            }
            items(listaDia, key = { "${it.fileId}_${it.nomeArquivo}_${it.dataUpload}" }) { arq ->
                val isSelected = selecionados.contains(arq.fileId)
                val ext = arq.nomeArquivo.substringAfterLast('.', "").lowercase(Locale.getDefault())
                val isVideo = ext in listOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
                val isPdf = ext == "pdf"

                var bitmapCapa by remember(arq.fileId, arq.caminhoMiniatura) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(arq.fileId, arq.caminhoMiniatura) {
                    withContext(Dispatchers.IO) {
                        val caminho = obterUriCapaMidia(context, arq)
                        if (!caminho.isNullOrBlank()) {
                            val bmp = BitmapFactory.decodeFile(caminho)
                            if (bmp != null) {
                                bitmapCapa = if (isVideo) clarearBitmapVideoSeNecessario(bmp) else bmp
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) Gold else Color(0xFF25252B),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .combinedClickable(
                            onClick = { onClickArquivo(arq) },
                            onLongClick = { onLongClickArquivo(arq) }
                        )
                ) {
                    if (bitmapCapa != null) {
                        Image(
                            bitmap = bitmapCapa!!.asImageBitmap(),
                            contentDescription = arq.nomeArquivo,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = when {
                                    isVideo -> Icons.Default.Videocam
                                    isPdf -> Icons.Default.PictureAsPdf
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = Gold,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = arq.nomeArquivo,
                                color = Color.White,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (isVideo) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(32.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = formatarTamanhoNuvem(arq.tamanhoBytes),
                            color = Color.White,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }

                    if (modoSelecao) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(22.dp)
                                .background(if (isSelected) Gold else Color.Black.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
// === FIM DA PARTE 1 (COLE A PARTE 2 LOGO ABAIXO DESTA LINHA) ===
// === CONTINUAÇÃO: PARTE 2 DE 2 (COLE LOGO ABAIXO DA PARTE 1) ===

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NexusUI(dao: NexusDao, client: Client) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = remember(dao) { UIViewModel(dao) }

    val todosArquivos by vm.arquivos.collectAsState()
    val todasPastas by vm.pastas.collectAsState()

    // === ESTADOS DE AUTENTICAÇÃO E ISOLAMENTO DE COFRE ===
    var cofreDestrancado by remember { mutableStateOf(false) }
    var pinDigitado by remember { mutableStateOf("") }
    var cofreTagAtual by remember { mutableStateOf("") }
    var telefoneMascarado by remember { mutableStateOf("Carregando...") }

    // === ESTADOS DE NAVEGAÇÃO, PASTAS E SELEÇÃO ===
    var pastaSelecionada by remember { mutableStateOf("Geral") }
    var buscaTexto by remember { mutableStateOf("") }
    var modoSelecao by remember { mutableStateOf(false) }
    var selecionados by remember { mutableStateOf(setOf<Int>()) }
    var mapaPastas by remember { mutableStateOf(mapOf<String, String>()) }

    // === ESTADOS DE TRANSFERÊNCIA (UPLOAD / DOWNLOAD / AUTO-SYNC) ===
    var transferindo by remember { mutableStateOf(false) }
    var statusTransferencia by remember { mutableStateOf("") }
    var progressoTransferencia by remember { mutableStateOf(0f) }
    var pausaEstadoUI by remember { mutableStateOf(false) }

    // === ESTADOS DE DIÁLOGOS E VISUALIZADOR INTERNO ===
    var mostrarDialogNovaPasta by remember { mutableStateOf(false) }
    var nomeNovaPasta by remember { mutableStateOf("") }
    var mostrarDialogAutoSync by remember { mutableStateOf(false) }
    var mostrarMenuCache by remember { mutableStateOf(false) }
    var arquivoEmVisualizacao by remember { mutableStateOf<ArquivoNuvem?>(null) }

    // Carregar telefone mascarado do usuário na TDLib
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val l = CountDownLatch(1)
            client.send(TdApi.GetMe()) { res ->
                if (res is TdApi.User) {
                    telefoneMascarado = mascararNumeroComAsterisco(res.phoneNumber ?: "Privado")
                }
                l.countDown()
            }
            l.await(6, TimeUnit.SECONDS)
        }
    }

    // === FUNÇÃO CORRIGIDA DE UPLOADS EM LOTE ===
    fun executarUploads(uris: List<Uri>, pastaDestino: String) {
        if (uris.isEmpty() || cofreTagAtual.isBlank()) return
        scope.launch(Dispatchers.IO) {
            NexusTransfer.resetarParaNovoFluxo()
            withContext(Dispatchers.Main) {
                transferindo = true
                pausaEstadoUI = false
                progressoTransferencia = 0.01f
            }

            vm.criarPasta(pastaDestino)

            for ((index, uri) in uris.withIndex()) {
                if (NexusTransfer.cancelado) break

                val (nomeOriginal, tamanhoUri) = obterDetalhesFicheiro(context, uri)
                withContext(Dispatchers.Main) {
                    statusTransferencia = "Enviando (${index + 1}/${uris.size}): $nomeOriginal"
                    progressoTransferencia = 0.02f
                }

                // 1. Gerar capa rápida local antes do envio
                val caminhoThumb = salvarMiniaturaPrivada(context, uri, nomeOriginal)

                // 2. Copiar arquivo para o cache da TDLib (mantido após envio para leitura instantânea)
                val arquivoCache = copiarUriParaCacheTDLib(context, uri, nomeOriginal)
                if (arquivoCache == null || !arquivoCache.exists()) {
                    continue
                }

                val tamanhoReal = max(tamanhoUri, arquivoCache.length())

                // 3. Enviar para as Mensagens Salvas sem InputThumbnail (evita erro 400 da TDLib)
                val fileIdRetornado = enviarParaMensagensSalvas(
                    context = context,
                    client = client,
                    arquivoLocal = arquivoCache,
                    nomeOriginal = nomeOriginal,
                    cofreTag = cofreTagAtual,
                    pastaDestino = pastaDestino,
                    caminhoThumbLocal = caminhoThumb,
                    onProgress = { prog ->
                        progressoTransferencia = prog
                    }
                )

                if (NexusTransfer.cancelado) break

                // 4. Salvar imediatamente no banco local Room e atualizar vínculo da pasta
                val fileIdSeguro = if (fileIdRetornado > 0) {
                    fileIdRetornado
                } else {
                    (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
                }

                salvarVinculoPasta(context, cofreTagAtual, nomeOriginal, pastaDestino)
                val novoRegistro = ArquivoNuvem(
                    fileId = fileIdSeguro,
                    nomeArquivo = nomeOriginal,
                    tamanhoBytes = tamanhoReal,
                    dataUpload = System.currentTimeMillis(),
                    caminhoMiniatura = caminhoThumb
                )
                vm.salvarArquivo(novoRegistro)

                withContext(Dispatchers.Main) {
                    mapaPastas = carregarVinculosPastas(context, cofreTagAtual)
                    progressoTransferencia = 1f
                }
            }

            withContext(Dispatchers.Main) {
                transferindo = false
                statusTransferencia = ""
                progressoTransferencia = 0f
                mapaPastas = carregarVinculosPastas(context, cofreTagAtual)
                if (!NexusTransfer.cancelado) {
                    Toast.makeText(context, "Upload concluído com sucesso!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Seletores de Arquivos e Pastas (SAF)
    val seletorArquivosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            executarUploads(uris, pastaSelecionada)
        }
    }

    val seletorPastaSyncLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null && cofreTagAtual.isNotBlank()) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            salvarPastaSync(context, cofreTagAtual, treeUri.toString())
            Toast.makeText(context, "Pasta adicionada à sincronização automática!", Toast.LENGTH_SHORT).show()
        }
    }

    // Sincronizar cofre ao destrancar
    LaunchedEffect(cofreDestrancado, cofreTagAtual) {
        if (cofreDestrancado && cofreTagAtual.isNotBlank()) {
            mapaPastas = carregarVinculosPastas(context, cofreTagAtual)
            vm.criarPasta("Geral")
            sincronizarCofreDaNuvem(context, client, vm, cofreTagAtual, todosArquivos)
            mapaPastas = carregarVinculosPastas(context, cofreTagAtual)

            val nomesSalvos = todosArquivos.map { it.nomeArquivo }.toSet()
            val novosAutoSync = procurarNovosFicheirosNasPastas(context, cofreTagAtual, nomesSalvos)
            if (novosAutoSync.isNotEmpty()) {
                val agrupadosPorPasta = novosAutoSync.groupBy({ it.second }, { it.first })
                for ((pastaSync, listaUris) in agrupadosPorPasta) {
                    executarUploads(listaUris, pastaSync)
                }
            }
        }
    }

    // === TELA DE BLOQUEIO DO COFRE (PIN ISOLADO SEM EXIBIR PERFIS ANTERIORES) ===
    if (!cofreDestrancado) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Gold,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "SSD NEXUS CLOUD $NEXUS_VERSION",
                        color = Gold,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Conta: $telefoneMascarado",
                        color = TextGrey,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = pinDigitado,
                        onValueChange = { novo ->
                            if (novo.length <= 8 && novo.all { it.isDigit() }) {
                                pinDigitado = novo
                            }
                        },
                        label = { Text("PIN do Cofre (4 a 8 dígitos)", color = TextGrey) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Gold,
                            unfocusedBorderColor = TextGrey,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (pinDigitado.length < 4) {
                                Toast.makeText(context, "Digite um PIN de pelo menos 4 dígitos", Toast.LENGTH_SHORT).show()
                            } else {
                                cofreTagAtual = "PIN_${pinDigitado.trim()}"
                                mapaPastas = carregarVinculosPastas(context, cofreTagAtual)
                                cofreDestrancado = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "ACESSAR COFRE PRIVADO",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        return
    }

    // Filtrar arquivos exclusivos do cofre atual e da pasta selecionada
    val arquivosDoCofre = remember(todosArquivos, mapaPastas, pastaSelecionada, buscaTexto) {
        todosArquivos.filter { arq ->
            val pastaDoArq = mapaPastas[arq.nomeArquivo]
            val pertenceAoCofre = pastaDoArq != null
            val pertenceAPasta = pastaSelecionada == "Geral" || pastaDoArq == pastaSelecionada
            val bateBusca = buscaTexto.isBlank() || arq.nomeArquivo.contains(buscaTexto, ignoreCase = true)
            pertenceAoCofre && pertenceAPasta && bateBusca
        }
    }

    val bytesCofreAtual = remember(todosArquivos, mapaPastas) {
        todosArquivos.filter { mapaPastas.containsKey(it.nomeArquivo) }
            .sumOf { it.tamanhoBytes }
    }

    val listaNomesPastas = remember(todasPastas, mapaPastas) {
        val nomes = mutableSetOf("Geral")
        todasPastas.forEach { nomes.add(it.nomePasta) }
        mapaPastas.values.forEach { nomes.add(it) }
        nomes.toList()
    }

    BackHandler(enabled = modoSelecao || pastaSelecionada != "Geral") {
        if (modoSelecao) {
            modoSelecao = false
            selecionados = emptySet()
        } else if (pastaSelecionada != "Geral") {
            pastaSelecionada = "Geral"
        }
    }

    // === ESTRUTURA PRINCIPAL DO COFRE ===
    Scaffold(
        containerColor = BgBlack,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDarker)
                    .padding(top = 12.dp, bottom = 8.dp, start = 14.dp, end = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "NEXUS CLOUD $NEXUS_VERSION",
                            color = Gold,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "$telefoneMascarado • ${formatarTamanhoNuvem(bytesCofreAtual)} usados",
                            color = TextGrey,
                            fontSize = 11.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            scope.launch {
                                Toast.makeText(context, "Sincronizando Mensagens Salvas...", Toast.LENGTH_SHORT).show()
                                sincronizarCofreDaNuvem(context, client, vm, cofreTagAtual, todosArquivos)
                                mapaPastas = carregarVinculosPastas(context, cofreTagAtual)
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sincronizar", tint = Gold)
                        }
                        IconButton(onClick = { mostrarDialogAutoSync = true }) {
                            Icon(Icons.Default.Sync, contentDescription = "Auto-Sync", tint = Color.White)
                        }
                        IconButton(onClick = { mostrarMenuCache = true }) {
                            Icon(Icons.Default.Storage, contentDescription = "Cache SSD", tint = Color.White)
                        }
                        IconButton(onClick = {
                            cofreDestrancado = false
                            pinDigitado = ""
                            cofreTagAtual = ""
                            modoSelecao = false
                            selecionados = emptySet()
                        }) {
                            Icon(Icons.Default.Lock, contentDescription = "Trancar Cofre", tint = DangerRed)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Barra de pesquisa rápida
                OutlinedTextField(
                    value = buscaTexto,
                    onValueChange = { buscaTexto = it },
                    placeholder = { Text("Buscar arquivo em $pastaSelecionada...", color = TextGrey, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Gold) },
                    trailingIcon = {
                        if (buscaTexto.isNotEmpty()) {
                            IconButton(onClick = { buscaTexto = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpar", tint = TextGrey)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Color(0xFF282830),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Barra de Pastas Horizontal
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(listaNomesPastas) { nomePasta ->
                        val ativa = pastaSelecionada == nomePasta
                        FilterChip(
                            selected = ativa,
                            onClick = { pastaSelecionada = nomePasta },
                            label = {
                                Text(
                                    text = nomePasta,
                                    color = if (ativa) Color.Black else Color.White,
                                    fontWeight = if (ativa) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (ativa) Color.Black else Gold,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Gold,
                                containerColor = SurfaceDark
                            )
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { mostrarDialogNovaPasta = true },
                            label = { Text("+ Pasta", color = Gold) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = SurfaceDark)
                        )
                    }
                }

                // Barra de Ações em Lote quando o modo seleção está ativo
                AnimatedVisibility(visible = modoSelecao) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${selecionados.size} selecionado(s)",
                            color = Gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Row {
                            TextButton(onClick = {
                                selecionados = arquivosDoCofre.map { it.fileId }.toSet()
                            }) {
                                Text("Todos", color = Color.White, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                val paraApagar = arquivosDoCofre.filter { selecionados.contains(it.fileId) }
                                paraApagar.forEach { vm.apagar(it.id) }
                                selecionados = emptySet()
                                modoSelecao = false
                                Toast.makeText(context, "${paraApagar.size} arquivo(s) removido(s) da galeria", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Excluir Selecionados", tint = DangerRed)
                            }
                            IconButton(onClick = {
                                modoSelecao = false
                                selecionados = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Sair da Seleção", tint = TextGrey)
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    seletorArquivosLauncher.launch(arrayOf("*/*"))
                },
                containerColor = Gold,
                contentColor = Color.Black,
                icon = { Icon(Icons.Default.Add, contentDescription = "Enviar") },
                text = { Text("ENVIAR ARQUIVOS", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LinhaSincronizacao(
                visivel = transferindo,
                textoStatus = statusTransferencia,
                progresso = progressoTransferencia,
                pausado = pausaEstadoUI,
                onTogglePausa = {
                    NexusTransfer.pausado = !NexusTransfer.pausado
                    pausaEstadoUI = NexusTransfer.pausado
                },
                onCancelar = {
                    NexusTransfer.cancelarTudo(client)
                    transferindo = false
                    pausaEstadoUI = false
                    Toast.makeText(context, "Transferência cancelada.", Toast.LENGTH_SHORT).show()
                }
            )

            GrelhaArquivos(
                arquivos = arquivosDoCofre,
                selecionados = selecionados,
                modoSelecao = modoSelecao,
                onClickArquivo = { arq ->
                    if (modoSelecao) {
                        selecionados = if (selecionados.contains(arq.fileId)) {
                            selecionados - arq.fileId
                        } else {
                            selecionados + arq.fileId
                        }
                        if (selecionados.isEmpty()) modoSelecao = false
                    } else {
                        arquivoEmVisualizacao = arq
                    }
                },
                onLongClickArquivo = { arq ->
                    modoSelecao = true
                    selecionados = selecionados + arq.fileId
                }
            )
        }
    }

    // === DIÁLOGO: CRIAR NOVA PASTA ===
    if (mostrarDialogNovaPasta) {
        AlertDialog(
            onDismissRequest = { mostrarDialogNovaPasta = false },
            containerColor = SurfaceDark,
            title = { Text("Criar Nova Pasta", color = Gold, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = nomeNovaPasta,
                    onValueChange = { nomeNovaPasta = it },
                    label = { Text("Nome da pasta", color = TextGrey) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = TextGrey,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val limpo = nomeNovaPasta.trim()
                        if (limpo.isNotEmpty()) {
                            vm.criarPasta(limpo)
                            pastaSelecionada = limpo
                            nomeNovaPasta = ""
                            mostrarDialogNovaPasta = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                ) {
                    Text("CRIAR", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogNovaPasta = false }) {
                    Text("Cancelar", color = TextGrey)
                }
            }
        )
    }

    // === DIÁLOGO: GERENCIADOR DE AUTO-SYNC DE PASTAS LOCAIS ===
    if (mostrarDialogAutoSync) {
        var pastasAtuais by remember { mutableStateOf(carregarPastasSync(context, cofreTagAtual).toList()) }
        AlertDialog(
            onDismissRequest = { mostrarDialogAutoSync = false },
            containerColor = SurfaceDark,
            title = { Text("Auto-Sync de Pastas", color = Gold, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Pastas monitoradas enviam novos arquivos automaticamente ao abrir este cofre:",
                        color = TextGrey,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (pastasAtuais.isEmpty()) {
                        Text("Nenhuma pasta vinculada.", color = Color.White, fontSize = 13.sp)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(pastasAtuais) { uriStr ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = Uri.parse(uriStr).lastPathSegment ?: uriStr,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(onClick = {
                                        removerPastaSync(context, cofreTagAtual, uriStr)
                                        pastasAtuais = carregarPastasSync(context, cofreTagAtual).toList()
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remover", tint = DangerRed)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            mostrarDialogAutoSync = false
                            seletorPastaSyncLauncher.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold)
                    ) {
                        Text("ADICIONAR PASTA LOCAL", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarDialogAutoSync = false }) {
                    Text("Fechar", color = Gold)
                }
            }
        )
    }

    // === DIÁLOGO: MENU DE GESTÃO DE CACHE SSD NEXUS (v1.9) ===
    if (mostrarMenuCache) {
        val dirCacheUpload = File(context.cacheDir, "tdlib_upload_cache")
        val dirThumbs = File(context.cacheDir, "thumbs_privadas")
        var bytesUpload by remember {
            mutableStateOf(dirCacheUpload.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }
        var bytesThumbs by remember {
            mutableStateOf(dirThumbs.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }

        AlertDialog(
            onDismissRequest = { mostrarMenuCache = false },
            containerColor = SurfaceDark,
            title = { Text("Gestão de Cache SSD Nexus", color = Gold, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Cache de Arquivos: ${formatarTamanhoNuvem(bytesUpload)}",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Cache de Miniaturas: ${formatarTamanhoNuvem(bytesThumbs)}",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            dirCacheUpload.deleteRecursively()
                            dirCacheUpload.mkdirs()
                            bytesUpload = 0L
                            Toast.makeText(context, "Cache de arquivos temporários limpo!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDarker)
                    ) {
                        Text("LIMPAR CACHE DE ARQUIVOS", color = Gold)
                    }
                    Button(
                        onClick = {
                            dirThumbs.deleteRecursively()
                            dirThumbs.mkdirs()
                            bytesThumbs = 0L
                            Toast.makeText(context, "Miniaturas reiniciadas!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDarker)
                    ) {
                        Text("RECRIAR MINIATURAS DE VÍDEO/FOTO", color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarMenuCache = false }) {
                    Text("Concluir", color = Gold)
                }
            }
        )
    }

    // === VISUALIZADOR INTERNO MULTIMÍDIA (FOTOS COM ZOOM, VÍDEOS, PDF E TEXTO) ===
    val arqVisual = arquivoEmVisualizacao
    if (arqVisual != null) {
        Dialog(
            onDismissRequest = { arquivoEmVisualizacao = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var carregandoArquivo by remember(arqVisual.fileId) { mutableStateOf(true) }
            var progressoDownload by remember(arqVisual.fileId) { mutableStateOf(0f) }
            var ficheiroLocal by remember(arqVisual.fileId) { mutableStateOf<File?>(null) }

            val ext = arqVisual.nomeArquivo.substringAfterLast('.', "").lowercase(Locale.getDefault())
            val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "heic")
            val isVideo = ext in listOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
            val isPdf = ext == "pdf"
            val isText = ext in listOf("txt", "log", "json", "xml", "csv", "md", "kt")

            LaunchedEffect(arqVisual) {
                carregandoArquivo = true
                val baixado = garantirFicheiroLocal(context, client, arqVisual) { p ->
                    progressoDownload = p
                }
                if (baixado != null && baixado.exists()) {
                    if (arqVisual.caminhoMiniatura.isNullOrBlank()) {
                        val novaCapa = prepararMiniaturaDeFicheiroBaixado(context, baixado, arqVisual.nomeArquivo)
                        if (!novaCapa.isNullOrBlank()) {
                            vm.salvarArquivo(arqVisual.copy(caminhoMiniatura = novaCapa))
                        }
                    }
                }
                ficheiroLocal = baixado
                carregandoArquivo = false
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgBlack)
            ) {
                if (carregandoArquivo) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Gold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Carregando ${arqVisual.nomeArquivo} (${(progressoDownload * 100).toInt()}%)",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                } else if (ficheiroLocal == null || !ficheiroLocal!!.exists()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Arquivo ainda em processamento na nuvem ou indisponível offline.",
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val file = ficheiroLocal!!
                    when {
                        isImage -> {
                            val bmp = remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath) }
                            var escala by remember { mutableStateOf(1f) }
                            var offsetX by remember { mutableStateOf(0f) }
                            var offsetY by remember { mutableStateOf(0f) }

                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = arqVisual.nomeArquivo,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                escala = (escala * zoom).coerceIn(1f, 5f)
                                                if (escala > 1f) {
                                                    offsetX += pan.x
                                                    offsetY += pan.y
                                                } else {
                                                    offsetX = 0f
                                                    offsetY = 0f
                                                }
                                            }
                                        }
                                        .graphicsLayer(
                                            scaleX = escala,
                                            scaleY = escala,
                                            translationX = offsetX,
                                            translationY = offsetY
                                        )
                                )
                            }
                        }
                        isVideo -> {
                            AndroidView(
                                factory = { ctx ->
                                    android.widget.VideoView(ctx).apply {
                                        val controller = android.widget.MediaController(ctx)
                                        controller.setAnchorView(this)
                                        setMediaController(controller)
                                        setVideoPath(file.absolutePath)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = false
                                            start()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.Center)
                            )
                        }
                        isPdf -> {
                            val paginas = remember(file.absolutePath) { renderizarPaginasPdf(context, file) }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 64.dp, bottom = 64.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                items(paginas) { pgBmp ->
                                    Image(
                                        bitmap = pgBmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                        }
                        isText -> {
                            val conteudoTxt = remember(file.absolutePath) { lerTextoDocumento(file) }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 70.dp, bottom = 70.dp, start = 16.dp, end = 16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(text = conteudoTxt, color = Color.White, fontSize = 13.sp)
                            }
                        }
                        else -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = Gold,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = arqVisual.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { abrirFicheiroExterno(context, file, arqVisual.nomeArquivo) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                                ) {
                                    Text("ABRIR EM APLICATIVO EXTERNO", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Barra Superior do Visualizador
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = arqVisual.nomeArquivo,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatarTamanhoNuvem(arqVisual.tamanhoBytes)} • ${formatarDataCurta(arqVisual.dataUpload)}",
                            color = Gold,
                            fontSize = 11.sp
                        )
                    }
                    Row {
                        if (ficheiroLocal != null && ficheiroLocal!!.exists()) {
                            IconButton(onClick = {
                                compartilharFicheiroNativo(context, ficheiroLocal!!, arqVisual.nomeArquivo)
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Compartilhar", tint = Color.White)
                            }
                        }
                        IconButton(onClick = {
                            vm.apagar(arqVisual.id)
                            arquivoEmVisualizacao = null
                            Toast.makeText(context, "Arquivo removido do cofre.", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = DangerRed)
                        }
                        IconButton(onClick = { arquivoEmVisualizacao = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Gold)
                        }
                    }
                }
            }
        }
    }
}
// === FIM DO FICHEIRO ===
