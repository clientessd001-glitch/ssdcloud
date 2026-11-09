package com.seu.nexuscloud

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

val BgBlack = Color(0xFF05070A)
val SurfaceDark = Color(0xFF161A25)
val SurfaceDarker = Color(0xFF10131B)
val Gold = Color(0xFFFFD700)
val DangerRed = Color(0xFFE53935)
val TextGrey = Color(0xFFA0AABF)

object NexusTransfer {
    val progress = MutableStateFlow<Int?>(null)
    val label = MutableStateFlow("")
}

class UIViewModel(private val dao: NexusDao) : ViewModel() {
    val arquivos = dao.buscarTodos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pastas = dao.buscarPastas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val armazenamento = dao.calcularUsoTotal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun criarPasta(nome: String) = viewModelScope.launch {
        if (nome.isNotBlank()) dao.criarPasta(PastaNuvem(nomePasta = nome.trim()))
    }
    fun salvarArquivo(arq: ArquivoNuvem) = viewModelScope.launch { dao.salvarArquivo(arq) }
    fun apagar(id: Int) = viewModelScope.launch { dao.apagarArquivo(id) }
}

fun formatarDataNuvem(timestamp: Long): String {
    if (timestamp <= 0L) return "Data desconhecida"
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}

fun formatarDataCurta(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
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
        if (chave != null && v is String && v.isNotBlank()) mapa[chave] = v
    }
    return mapa
}

fun salvarVinculoPasta(context: Context, dataUpload: Long, nomePasta: String) {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    if (nomePasta.isBlank()) prefs.edit().remove(dataUpload.toString()).apply()
    else prefs.edit().putString(dataUpload.toString(), nomePasta).apply()
}

data class PastaSync(val uri: String, val nome: String, var ativa: Boolean)

fun carregarPastasSync(context: Context): List<PastaSync> {
    val prefs = context.getSharedPreferences("nexus_sync_prefs", Context.MODE_PRIVATE)
    val lista = mutableListOf<PastaSync>()
    prefs.all.forEach { (uriStr, ativo) ->
        if (ativo is Boolean) {
            val decodedPath = Uri.decode(uriStr)
            val nome = decodedPath.substringAfterLast(":", "Pasta").substringAfterLast("/")
            lista.add(PastaSync(uriStr, nome.ifBlank { "Pasta Selecionada" }, ativo))
        }
    }
    return lista
}

fun salvarPastaSync(context: Context, uri: String, ativa: Boolean) {
    context.getSharedPreferences("nexus_sync_prefs", Context.MODE_PRIVATE).edit().putBoolean(uri, ativa).apply()
}

fun removerPastaSync(context: Context, uri: String) {
    context.getSharedPreferences("nexus_sync_prefs", Context.MODE_PRIVATE).edit().remove(uri).apply()
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
    return Pair(nome, tamanho)
}

fun descobrirMimeType(nome: String): String {
    return when (nome.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4", "mov", "mkv", "avi" -> "video/mp4"
        "pdf" -> "application/pdf"
        "zip", "rar" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}

fun salvarMiniaturaPrivada(context: Context, uri: Uri, nomeArquivo: String): String {
    return try {
        val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
        val f = File(dir, "thumb_${System.currentTimeMillis()}_${nomeArquivo.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(f).use { out -> inp.copyTo(out) }
        }
        if (descobrirMimeType(nomeArquivo).startsWith("video/")) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(f.absolutePath)
                val bitmap = retriever.getFrameAtTime(1000000) ?: retriever.getFrameAtTime(0)
                retriever.release()
                if (bitmap != null) {
                    val frameFile = File("${f.absolutePath}_frame.jpg")
                    FileOutputStream(frameFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
            } catch (_: Exception) {}
        }
        Uri.fromFile(f).toString()
    } catch (_: Exception) {
        uri.toString()
    }
}

fun obterUriCapaVideo(caminhoMiniatura: String?): Uri {
    val caminho = caminhoMiniatura ?: ""
    if (caminho.startsWith("file://")) {
        val limpo = Uri.parse(caminho).path ?: caminho.removePrefix("file://")
        val frameFile = File("${limpo}_frame.jpg")
        if (frameFile.exists()) return Uri.fromFile(frameFile)
    }
    return Uri.parse(caminho)
}

fun copiarUriParaCacheTDLib(context: Context, uri: Uri, nomeArquivo: String): File? {
    return try {
        val dir = File(context.filesDir, "tdlib_upload_cache").apply { if (!exists()) mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}_${nomeArquivo.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
        f
    } catch (_: Exception) { null }
}

fun compartilharFicheiroNativo(context: Context, caminhoMiniatura: String?, nomeArquivo: String) {
    try {
        val caminho = caminhoMiniatura ?: ""
        if (caminho.isBlank()) {
            Toast.makeText(context, "Ficheiro local indisponível. Use 'Baixar da Nuvem'.", Toast.LENGTH_SHORT).show()
            return
        }

        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val mime = descobrirMimeType(nomeArquivo)
        if (caminho.startsWith("content://")) {
            val u = Uri.parse(caminho)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(u) ?: mime
                putExtra(Intent.EXTRA_STREAM, u)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(i, "Compartilhar $nomeArquivo"))
            return
        }

        val limpo = if (caminho.startsWith("file://")) Uri.parse(caminho).path ?: caminho.removePrefix("file://") else caminho
        val arq = File(limpo)
        if (!arq.exists()) {
            Toast.makeText(context, "Ficheiro local indisponível. Use 'Baixar da Nuvem'.", Toast.LENGTH_SHORT).show()
            return
        }

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

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriMedia ?: Uri.fromFile(arq))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar $nomeArquivo"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao partilhar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun enviarParaMensagensSalvas(client: Client, caminhoLocal: String, nomeOriginal: String, pastaDestino: String, onResult: (Boolean, Int, String) -> Unit) {
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    val tag = if (pastaDestino.isNotBlank()) "📁 [$pastaDestino] • " else ""
                    val leg = TdApi.FormattedText("☁️ Nexus Cloud • $tag$nomeOriginal", emptyArray())
                    val doc = TdApi.InputMessageDocument(TdApi.InputFileLocal(caminhoLocal), null, false, leg)
                    val req = TdApi.SendMessage().apply { chatId = chatObj.id; inputMessageContent = doc }
                    client.send(req) { msgObj ->
                        when (msgObj) {
                            is TdApi.Message -> {
                                val rId = (msgObj.content as? TdApi.MessageDocument)?.document?.document?.id ?: 0
                                val txt = if (pastaDestino.isNotBlank()) "Guardado na pasta '$pastaDestino'!" else "Enviado para a nuvem!"
                                onResult(true, rId, txt)
                            }
                            is TdApi.Error -> onResult(false, 0, "Erro TDLib (${msgObj.code}): ${msgObj.message}")
                            else -> onResult(true, 0, "Upload iniciado!")
                        }
                    }
                } else if (chatObj is TdApi.Error) onResult(false, 0, "Erro cofre: ${chatObj.message}")
            }
        } else if (meObj is TdApi.Error) onResult(false, 0, "Erro sessão: ${meObj.message}")
    }
}

fun baixarFicheiroDaNuvem(context: Context, client: Client, targetFileId: Int, nomeArquivo: String, onResult: (String) -> Unit) {
    if (targetFileId == 0) {
        onResult("ID ausente na nuvem.")
        return
    }
    NexusTransfer.progress.value = 30
    NexusTransfer.label.value = "A descarregar da nuvem..."

    val req = TdApi.DownloadFile().apply {
        fileId = targetFileId
        priority = 1
        synchronous = false
    }

    client.send(req) { res ->
        when (res) {
            is TdApi.File -> {
                val caminhoRemoto = res.local?.path
                if (!caminhoRemoto.isNullOrBlank() && File(caminhoRemoto).exists()) {
                    try {
                        val mime = descobrirMimeType(nomeArquivo)
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val destino = File(downloadsDir, "Nexus_${System.currentTimeMillis()}_$nomeArquivo")

                        FileInputStream(File(caminhoRemoto)).use { input ->
                            FileOutputStream(destino).use { output ->
                                input.copyTo(output)
                            }
                        }

                        NexusTransfer.progress.value = null
                        onResult("Salvo em Downloads: ${destino.name}")
                    } catch (e: Exception) {
                        NexusTransfer.progress.value = null
                        onResult("Erro ao gravar ficheiro: ${e.localizedMessage}")
                    }
                } else {
                    NexusTransfer.progress.value = null
                    onResult("Transferência concluída na nuvem.")
                }
            }
            is TdApi.Error -> {
                NexusTransfer.progress.value = null
                onResult("Erro (${res.code}): ${res.message}")
            }
            else -> {
                NexusTransfer.progress.value = null
                onResult("Download em andamento...")
            }
        }
    }
}

fun formatarTamanhoNuvem(bytes: Long?): String {
    val total = bytes ?: 0L
    if (total <= 0L) return "0 MB"
    val mb = total.toDouble() / (1024.0 * 1024.0)
    return if (mb < 1024.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

@Composable
fun GrelhaArquivos(lista: List<ArquivoNuvem>, mapaPastas: Map<Long, String>, onFileClick: (ArquivoNuvem) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(lista) { arq ->
            val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase()
            val pasta = mapaPastas[arq.dataUpload]
            val thumbSafe = arq.caminhoMiniatura ?: ""
            val dataCurta = formatarDataCurta(arq.dataUpload)

            Box(Modifier.aspectRatio(1f).background(SurfaceDark).clickable { onFileClick(arq) }, contentAlignment = Alignment.Center) {
                when {
                    ext in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || arq.nomeArquivo.contains("IMG", true) -> {
                        AsyncImage(model = Uri.parse(thumbSafe), contentDescription = arq.nomeArquivo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    ext in listOf("MP4", "MKV", "MOV", "AVI") || arq.nomeArquivo.contains("VID", true) || arq.nomeArquivo.contains("Video", true) -> {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = obterUriCapaVideo(arq.caminhoMiniatura), contentDescription = "Video Preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Icon(Icons.Default.PlayCircleOutline, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(44.dp).align(Alignment.Center))
                        }
                    }
                    ext == "PDF" -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(32.dp))
                        Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (!pasta.isNullOrBlank()) {
                    Box(Modifier.align(Alignment.TopStart).padding(4.dp).background(BgBlack.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(pasta, color = Gold, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                if (dataCurta.isNotBlank()) {
                    Box(Modifier.align(Alignment.BottomStart).padding(4.dp).background(BgBlack.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(dataCurta, color = Color.White, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusUI(dao: NexusDao, client: Client) {
    val vm: UIViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(mClass: Class<T>) = UIViewModel(dao) as T
    })
    val arquivos by vm.arquivos.collectAsState()
    val pastas by vm.pastas.collectAsState()
    val armazenamento by vm.armazenamento.collectAsState()
    val loginStep by TelegramAuth.step.collectAsState()
    val statusText by TelegramAuth.statusText.collectAsState()
    val errorText by TelegramAuth.errorText.collectAsState()
    val transferProgress by NexusTransfer.progress.collectAsState()
    val transferLabel by NexusTransfer.label.collectAsState()

    var phoneNumber by remember { mutableStateOf("+5585") }
    var smsCode by remember { mutableStateOf("") }
    var password2FA by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedFile by remember { mutableStateOf<ArquivoNuvem?>(null) }
    var viewerFile by remember { mutableStateOf<ArquivoNuvem?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    var mapaPastas by remember { mutableStateOf(carregarVinculosPastas(context)) }
    var pastaAberta by remember { mutableStateOf<String?>(null) }
    var pendingUploadUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fileToMove by remember { mutableStateOf<ArquivoNuvem?>(null) }

    var showStorage by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var nomeNovaPasta by remember { mutableStateOf("") }

    var pastasSync by remember { mutableStateOf(carregarPastasSync(context)) }

    var isUploading by remember { mutableStateOf(false) }
    var cloudBannerText by remember { mutableStateOf("Nuvem TDLib Ativa • Mensagens Salvas") }

    LaunchedEffect(isUploading) {
        while (isUploading) {
            delay(350)
            val atual = NexusTransfer.progress.value ?: 10
            if (atual < 92) NexusTransfer.progress.value = (atual + 12).coerceAtMost(92)
        }
    }

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) TelegramAuth.errorText.value = "Erro (${obj.code}): ${obj.message}"
        }
    }

    fun executarUploads(uris: List<Uri>, pastaEscolhida: String) {
        if (uris.isEmpty()) return
        scope.launch {
            isUploading = true
            val total = uris.size
            var enviados = 0

            for ((index, uri) in uris.withIndex()) {
                NexusTransfer.progress.value = 10
                val (nome, tamanho) = obterDetalhesFicheiro(context, uri)
                val loteTag = if (total > 1) "(${index + 1}/$total) " else ""
                NexusTransfer.label.value = "Enviando $loteTag'$nome'..."
                cloudBannerText = "Enviando $loteTag'$nome'..."

                val arqTemp = withContext(Dispatchers.IO) { copiarUriParaCacheTDLib(context, uri, nome) }
                val miniatura = withContext(Dispatchers.IO) { salvarMiniaturaPrivada(context, uri, nome) }

                if (arqTemp != null) {
                    val deferred = CompletableDeferred<Triple<Boolean, Int, String>>()
                    enviarParaMensagensSalvas(client, arqTemp.absolutePath, nome, pastaEscolhida) { ok, rId, msg ->
                        deferred.complete(Triple(ok, rId, msg))
                    }
                    val (ok, rId, msg) = deferred.await()
                    cloudBannerText = msg
                    if (ok) {
                        enviados++
                        val ts = System.currentTimeMillis() + index
                        if (pastaEscolhida.isNotBlank()) {
                            salvarVinculoPasta(context, ts, pastaEscolhida)
                            mapaPastas = carregarVinculosPastas(context)
                        }
                        vm.salvarArquivo(
                            ArquivoNuvem(
                                fileId = rId,
                                nomeArquivo = nome,
                                tamanhoBytes = if (tamanho > 0) tamanho else arqTemp.length(),
                                dataUpload = ts,
                                caminhoMiniatura = miniatura
                            )
                        )
                    }
                } else {
                    cloudBannerText = "Erro ao ler '$nome'."
                }
                delay(120)
            }

            isUploading = false
            NexusTransfer.progress.value = null
            if (total > 1) {
                cloudBannerText = "$enviados de $total ficheiros enviados com sucesso!"
            }
        }
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
                salvarPastaSync(context, it.toString(), true)
                pastasSync = carregarPastasSync(context)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao guardar permissão da pasta.", Toast.LENGTH_SHORT).show()
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

    if (fileToMove != null) {
        val arqM = fileToMove!!
        Dialog(onDismissRequest = { fileToMove = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("MOVER PARA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { salvarVinculoPasta(context, arqM.dataUpload, ""); mapaPastas = carregarVinculosPastas(context); fileToMove = null }, border = BorderStroke(1.dp, TextGrey), modifier = Modifier.fillMaxWidth()) {
                        Text("Remover da Pasta", color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    pastas.forEach { p ->
                        Button(onClick = { salvarVinculoPasta(context, arqM.dataUpload, p.nomePasta); mapaPastas = carregarVinculosPastas(context); fileToMove = null }, colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(p.nomePasta, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (viewerFile != null) {
        val cur = viewerFile!!
        val curThumb = cur.caminhoMiniatura ?: ""
        val ext = cur.nomeArquivo.substringAfterLast('.', "").uppercase()
        val isVideo = ext in listOf("MP4", "MKV", "MOV", "AVI") || cur.nomeArquivo.contains("VID", true) || cur.nomeArquivo.contains("Video", true)

        var videoViewRef by remember { mutableStateOf<android.widget.VideoView?>(null) }
        var isPlaying by remember { mutableStateOf(true) }
        var currentPos by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(0) }

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
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (ext in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || cur.nomeArquivo.contains("IMG", true)) {
                    AsyncImage(model = Uri.parse(curThumb), contentDescription = cur.nomeArquivo, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoURI(Uri.parse(curThumb))
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
                } else {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.InsertDriveFile, null, tint = Gold, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(cur.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Row(Modifier.fillMaxWidth().background(BgBlack.copy(alpha = 0.75f)).padding(16.dp).align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewerFile = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(cur.nomeArquivo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatarDataNuvem(cur.dataUpload), color = TextGrey, fontSize = 10.sp, maxLines = 1)
                    }
                    IconButton(onClick = { compartilharFicheiroNativo(context, cur.caminhoMiniatura, cur.nomeArquivo) }) { Icon(Icons.Default.Share, null, tint = Gold) }
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

    if (loginStep < 3) {
        Column(Modifier.fillMaxSize().background(BgBlack).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Cloud, null, tint = Gold, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("NEXUS CLOUD", color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(statusText, color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
            if (errorText != null) Text(errorText!!, color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(32.dp))
            when (loginStep) {
                0 -> {
                    OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it }, label = { Text("Número (+5585...)", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        TelegramAuth.errorText.value = null
                        val clean = phoneNumber.replace(" ", "").replace("-", "")
                        val cfg = TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray())
                        client.send(TdApi.SetAuthenticationPhoneNumber(clean, cfg), responseHandler)
                    }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ENVIAR CÓDIGO", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                1 -> {
                    OutlinedTextField(value = smsCode, onValueChange = { smsCode = it }, label = { Text("Código SMS/Telegram", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationCode(smsCode.trim()), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("CONFIRMAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                2 -> {
                    OutlinedTextField(value = password2FA, onValueChange = { password2FA = it }, label = { Text("Senha 2FA", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { client.send(TdApi.CheckAuthenticationPassword(password2FA), responseHandler) }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ENTRAR", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = SurfaceDark, modifier = Modifier.width(300.dp)) {
                Column(Modifier.fillMaxWidth().background(SurfaceDarker).padding(32.dp)) {
                    Icon(Icons.Default.Person, null, tint = Gold, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Admin SSD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(phoneNumber, color = TextGrey, fontSize = 12.sp)
                }
                NavigationDrawerItem(icon = { Icon(Icons.Default.Storage, null) }, label = { Text("Armazenamento") }, selected = false, onClick = { showStorage = true; scope.launch { drawerState.close() } }, colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey))
                NavigationDrawerItem(icon = { Icon(Icons.Default.Sync, null) }, label = { Text("Sincronização Auto") }, selected = false, onClick = { showSync = true; scope.launch { drawerState.close() } }, colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey))
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column(Modifier.background(BgBlack)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text("NEXUS CLOUD", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text(if (transferProgress != null) transferLabel else cloudBannerText, color = if (transferProgress != null) Gold else TextGrey, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) searchQuery = ""
                        }) {
                            Icon(if (showSearchBar) Icons.Default.Close else Icons.Default.Search, contentDescription = "Pesquisar", tint = Gold)
                        }
                        if (transferProgress != null || isUploading) {
                            Text("${transferProgress ?: 10}%", color = Gold, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.CloudDone, null, tint = Gold)
                        }
                    }
                    if (showSearchBar) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Pesquisar ficheiro ou pasta...", color = TextGrey, fontSize = 13.sp) },
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
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        )
                    }
                    if (transferProgress != null || isUploading) {
                        val fra = ((transferProgress ?: 10).coerceIn(5, 100)) / 100f
                        Box(Modifier.fillMaxWidth().height(4.dp).background(SurfaceDark)) {
                            Box(Modifier.fillMaxWidth(fra).height(4.dp).background(Gold))
                        }
                    }
                    TabRow(selectedTabIndex = selectedTab, containerColor = BgBlack, indicator = { TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = Gold) }, divider = {}) {
                        listOf("GALERIA", "PASTAS", "FOTOS", "VÍDEOS", "DOCS").forEachIndexed { i, t ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i; if (i != 1) pastaAberta = null }) {
                                Text(t, color = if (selectedTab == i) Gold else TextGrey, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { filePicker.launch(arrayOf("*/*")) }, containerColor = DangerRed, contentColor = Color.White, shape = CircleShape, modifier = Modifier.shadow(16.dp, CircleShape)) {
                    Icon(Icons.Default.CloudUpload, null)
                }
            },
            containerColor = BgBlack
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (selectedTab == 1) {
                    if (pastaAberta != null) {
                        val daPasta = remember(arquivos, mapaPastas, pastaAberta, searchQuery) {
                            arquivos.filter {
                                mapaPastas[it.dataUpload] == pastaAberta &&
                                    (searchQuery.isBlank() || it.nomeArquivo.contains(searchQuery, ignoreCase = true))
                            }
                        }
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().background(SurfaceDarker).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { pastaAberta = null }) { Icon(Icons.Default.ArrowBack, null, tint = Gold) }
                                Text(pastaAberta!!, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            GrelhaArquivos(daPasta, mapaPastas) { selectedFile = it }
                        }
                    } else {
                        val pastasFiltradas = remember(pastas, searchQuery) {
                            if (searchQuery.isBlank()) pastas
                            else pastas.filter { it.nomePasta.contains(searchQuery, ignoreCase = true) }
                        }
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            OutlinedButton(onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(16.dp))
                            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pastasFiltradas) { p ->
                                    val qtd = arquivos.count { mapaPastas[it.dataUpload] == p.nomePasta }
                                    Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(12.dp)).clickable { pastaAberta = p.nomePasta }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(p.nomePasta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                                            Text("$qtd item(ns)", color = TextGrey, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val filtrada = remember(arquivos, selectedTab, searchQuery) {
                        val base = when (selectedTab) {
                            2 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || it.nomeArquivo.contains("IMG", true) }
                            3 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("MP4", "MKV", "MOV", "AVI") || it.nomeArquivo.contains("VID", true) || it.nomeArquivo.contains("Video", true) }
                            4 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("PDF", "ZIP", "RAR", "DOC", "DOCX", "XLS", "XLSX", "TXT", "APK") }
                            else -> arquivos
                        }
                        if (searchQuery.isBlank()) base
                        else base.filter { it.nomeArquivo.contains(searchQuery, ignoreCase = true) }
                    }
                    GrelhaArquivos(filtrada, mapaPastas) { selectedFile = it }
                }
            }

            if (selectedFile != null) {
                val arq = selectedFile!!
                ModalBottomSheet(onDismissRequest = { selectedFile = null }, sheetState = sheetState, containerColor = SurfaceDark) {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(arq.nomeArquivo, color = Gold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), fontWeight = FontWeight.Bold)
                        Text("Tamanho: ${formatarTamanhoNuvem(arq.tamanhoBytes)} • Pasta: ${mapaPastas[arq.dataUpload] ?: "Geral"}\nAdicionado em: ${formatarDataNuvem(arq.dataUpload)}", color = TextGrey, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                        ListItem(headlineContent = { Text("Abrir no Nexus Cloud", color = Color.White) }, leadingContent = { Icon(Icons.Default.Fullscreen, null, tint = Color.White) }, modifier = Modifier.clickable { viewerFile = arq; selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Mover para Pasta...", color = Gold) }, leadingContent = { Icon(Icons.Default.Folder, null, tint = Gold) }, modifier = Modifier.clickable { fileToMove = arq; selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Compartilhar...", color = Color.White) }, leadingContent = { Icon(Icons.Default.Share, null, tint = Color.White) }, modifier = Modifier.clickable { selectedFile = null; compartilharFicheiroNativo(context, arq.caminhoMiniatura, arq.nomeArquivo) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Baixar da Nuvem", color = Gold) }, leadingContent = { Icon(Icons.Default.CloudDownload, null, tint = Gold) }, modifier = Modifier.clickable { selectedFile = null; baixarFicheiroDaNuvem(context, client, arq.fileId, arq.nomeArquivo) { m -> scope.launch(Dispatchers.Main) { Toast.makeText(context, m, Toast.LENGTH_LONG).show() } } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Remover do registo", color = DangerRed) }, leadingContent = { Icon(Icons.Default.Delete, null, tint = DangerRed) }, modifier = Modifier.clickable { salvarVinculoPasta(context, arq.dataUpload, ""); mapaPastas = carregarVinculosPastas(context); vm.apagar(arq.id); selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
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
                            Button(onClick = { vm.criarPasta(nomeNovaPasta); nomeNovaPasta = ""; showNewFolder = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth()) { Text("CRIAR", color = Color.White) }
                        }
                    }
                }
            }

            if (showStorage) {
                Dialog(onDismissRequest = { showStorage = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("TOTAL ENVIADO", color = TextGrey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(formatarTamanhoNuvem(armazenamento), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
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
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("BACKUP AUTOMÁTICO", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Selecione as pastas do dispositivo que deseja sincronizar com a nuvem.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))

                            if (pastasSync.isEmpty()) {
                                Text("Nenhuma pasta selecionada.", color = TextGrey, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                            } else {
                                Column(modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    pastasSync.forEach { p ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { removerPastaSync(context, p.uri); pastasSync = carregarPastasSync(context) }) {
                                                Icon(Icons.Default.Delete, null, tint = DangerRed, modifier = Modifier.size(20.dp))
                                            }
                                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(p.nome, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Switch(
                                                checked = p.ativa,
                                                onCheckedChange = { salvarPastaSync(context, p.uri, it); pastasSync = carregarPastasSync(context) },
                                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DangerRed, uncheckedThumbColor = TextGrey, uncheckedTrackColor = SurfaceDark)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { folderPicker.launch(null) }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Icon(Icons.Default.CreateNewFolder, null, tint = Gold)
                                Spacer(Modifier.width(8.dp))
                                Text("ADICIONAR PASTA", color = Gold, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showSync = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth().height(48.dp)) {
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
