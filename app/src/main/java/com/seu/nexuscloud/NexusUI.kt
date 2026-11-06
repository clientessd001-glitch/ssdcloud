package com.seu.nexuscloud

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

fun carregarVinculosPastas(context: Context): Map<Long, String> {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    val mapa = mutableMapOf<Long, String>()
    for ((k, v) in prefs.all) {
        val chave = k.toLongOrNull()
        if (chave != null && v is String && v.isNotBlank()) {
            mapa[chave] = v
        }
    }
    return mapa
}

fun salvarVinculoPasta(context: Context, dataUpload: Long, nomePasta: String) {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    if (nomePasta.isBlank()) {
        prefs.edit().remove(dataUpload.toString()).apply()
    } else {
        prefs.edit().putString(dataUpload.toString(), nomePasta).apply()
    }
}

fun obterDetalhesFicheiro(context: Context, uri: Uri): Pair<String, Long> {
    var nome = "Arquivo_${System.currentTimeMillis()}"
    var tamanho = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val iNome = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iTamanho = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (iNome != -1 && !cursor.isNull(iNome)) nome = cursor.getString(iNome)
            if (iTamanho != -1 && !cursor.isNull(iTamanho)) tamanho = cursor.getLong(iTamanho)
        }
    }
    return Pair(nome, tamanho)
}

fun salvarMiniaturaPrivada(context: Context, uri: Uri, nomeArquivo: String): String {
    return try {
        val pastaThumbs = File(context.filesDir, "nexus_thumbs")
        if (!pastaThumbs.exists()) pastaThumbs.mkdirs()
        val nomeSeguro = nomeArquivo.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val thumbFile = File(pastaThumbs, "thumb_${System.currentTimeMillis()}_$nomeSeguro")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(thumbFile).use { output ->
                input.copyTo(output)
            }
        }
        Uri.fromFile(thumbFile).toString()
    } catch (e: Exception) {
        uri.toString()
    }
}

fun copiarUriParaCacheTDLib(context: Context, uri: Uri, nomeArquivo: String): File? {
    return try {
        val pastaCache = File(context.filesDir, "tdlib_upload_cache")
        if (!pastaCache.exists()) pastaCache.mkdirs()
        val nomeSeguro = nomeArquivo.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destino = File(pastaCache, "${System.currentTimeMillis()}_$nomeSeguro")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destino).use { output ->
                input.copyTo(output)
            }
        }
        destino
    } catch (e: Exception) {
        null
    }
}

fun descobrirMimeType(nomeArquivo: String): String {
    return when (nomeArquivo.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "mov", "mkv" -> "video/mp4"
        "pdf" -> "application/pdf"
        "zip", "rar" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}

fun compartilharFicheiroNativo(context: Context, caminhoMiniatura: String, nomeArquivo: String) {
    try {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val mimeType = descobrirMimeType(nomeArquivo)

        if (caminhoMiniatura.startsWith("content://")) {
            val uriDireta = Uri.parse(caminhoMiniatura)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uriDireta) ?: mimeType
                putExtra(Intent.EXTRA_STREAM, uriDireta)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartilhar $nomeArquivo"))
            return
        }

        val caminhoLimpo = if (caminhoMiniatura.startsWith("file://")) {
            Uri.parse(caminhoMiniatura).path ?: caminhoMiniatura.removePrefix("file://")
        } else {
            caminhoMiniatura
        }

        val arquivoLocal = File(caminhoLimpo)
        if (!arquivoLocal.exists()) {
            Toast.makeText(context, "Ficheiro local indisponível. Use 'Baixar da Nuvem'.", Toast.LENGTH_SHORT).show()
            return
        }

        val colecao = when {
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Nexus_${System.currentTimeMillis()}_$nomeArquivo")
            put(MediaStore.MediaColumns.MIME_TYPE, if (mimeType == "*/*") "application/octet-stream" else mimeType)
        }

        val uriMedia = try {
            context.contentResolver.insert(colecao, valores)?.also { destUri ->
                context.contentResolver.openOutputStream(destUri)?.use { output ->
                    arquivoLocal.inputStream().use { input -> input.copyTo(output) }
                }
            }
        } catch (_: Exception) {
            null
        }

        val uriFinal = uriMedia ?: Uri.fromFile(arquivoLocal)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uriFinal)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar $nomeArquivo"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao partilhar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun enviarParaMensagensSalvas(
    client: Client,
    caminhoLocal: String,
    nomeOriginal: String,
    pastaDestino: String,
    onResult: (Boolean, Int, String) -> Unit
) {
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    val inputFile = TdApi.InputFileLocal(caminhoLocal)
                    val tagPasta = if (pastaDestino.isNotBlank()) "📁 [$pastaDestino] • " else ""
                    val legenda = TdApi.FormattedText("☁️ Nexus Cloud • $tagPasta$nomeOriginal", emptyArray())
                    val conteudo = TdApi.InputMessageDocument(inputFile, null, false, legenda)

                    val req = TdApi.SendMessage().apply {
                        chatId = chatObj.id
                        inputMessageContent = conteudo
                    }

                    client.send(req) { msgObj ->
                        when (msgObj) {
                            is TdApi.Message -> {
                                val docContent = msgObj.content as? TdApi.MessageDocument
                                val remoteFileId = docContent?.document?.document?.id ?: 0
                                val msgSucesso = if (pastaDestino.isNotBlank()) {
                                    "Guardado na pasta '$pastaDestino' e na nuvem!"
                                } else {
                                    "Enviado para Mensagens Salvas no Telegram!"
                                }
                                onResult(true, remoteFileId, msgSucesso)
                            }
                            is TdApi.Error -> onResult(false, 0, "Erro TDLib (${msgObj.code}): ${msgObj.message}")
                            else -> onResult(true, 0, "Upload iniciado na TDLib!")
                        }
                    }
                } else if (chatObj is TdApi.Error) {
                    onResult(false, 0, "Erro ao abrir cofre (${chatObj.code}): ${chatObj.message}")
                }
            }
        } else if (meObj is TdApi.Error) {
            onResult(false, 0, "Erro de sessão (${meObj.code}): ${meObj.message}")
        }
    }
}

fun baixarFicheiroDaNuvem(client: Client, targetFileId: Int, onResult: (String) -> Unit) {
    if (targetFileId == 0) {
        onResult("ID do ficheiro ausente na nuvem.")
        return
    }
    NexusTransfer.progress.value = 15
    NexusTransfer.label.value = "Iniciando download da nuvem..."

    val req = TdApi.DownloadFile().apply {
        fileId = targetFileId
        priority = 1
        synchronous = false
    }
    client.send(req) { result ->
        when (result) {
            is TdApi.File -> {
                val caminho = result.local?.path
                if (result.local?.isDownloadingCompleted == true && !caminho.isNullOrBlank()) {
                    NexusTransfer.progress.value = null
                    onResult("Ficheiro descarregado em: $caminho")
                } else {
                    NexusTransfer.progress.value = null
                    onResult("Download da nuvem iniciado em segundo plano!")
                }
            }
            is TdApi.Error -> {
                NexusTransfer.progress.value = null
                onResult("Erro no download (${result.code}): ${result.message}")
            }
            else -> {
                NexusTransfer.progress.value = null
                onResult("A processar download na TDLib...")
            }
        }
    }
}

fun formatarTamanhoNuvem(bytes: Long?): String {
    val total = bytes ?: 0L
    if (total <= 0L) return "0 MB"
    val mb = total.toDouble() / (1024.0 * 1024.0)
    return if (mb < 1024.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        val gb = mb / 1024.0
        String.format(Locale.US, "%.2f GB", gb)
    }
}

@Composable
fun LinhaSincronizacao(nomePasta: String, ativo: Boolean, onAtivoChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = if (ativo) Gold else TextGrey, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(nomePasta, color = if (ativo) Color.White else TextGrey, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Switch(
            checked = ativo,
            onCheckedChange = onAtivoChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = DangerRed,
                uncheckedThumbColor = TextGrey,
                uncheckedTrackColor = SurfaceDark
            )
        )
    }
}

@Composable
fun GrelhaArquivos(
    lista: List<ArquivoNuvem>,
    mapaPastas: Map<Long, String>,
    onFileClick: (ArquivoNuvem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(lista) { arq ->
            val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase()
            val pastaAssociada = mapaPastas[arq.dataUpload]

            Box(
                modifier = Modifier.aspectRatio(1f).background(SurfaceDark).clickable { onFileClick(arq) },
                contentAlignment = Alignment.Center
            ) {
                when {
                    ext in listOf("JPG", "JPEG", "PNG", "WEBP") || arq.nomeArquivo.contains("IMG", ignoreCase = true) -> {
                        AsyncImage(
                            model = Uri.parse(arq.caminhoMiniatura),
                            contentDescription = arq.nomeArquivo,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ext == "PDF" || arq.nomeArquivo.contains("PDF", ignoreCase = true) -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(32.dp))
                            Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    ext in listOf("ZIP", "RAR", "APK") || arq.nomeArquivo.contains("ZIP", ignoreCase = true) -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Default.FolderZip, null, tint = Gold, modifier = Modifier.size(32.dp))
                            Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    ext in listOf("MP4", "MKV", "MOV") || arq.nomeArquivo.contains("Video", ignoreCase = true) -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp).align(Alignment.Center))
                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black, RoundedCornerShape(4.dp))) {
                                Text(ext.ifBlank { "VID" }, color = Gold, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Default.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                if (!pastaAssociada.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(BgBlack.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(pastaAssociada, color = Gold, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusUI(dao: NexusDao, client: Client) {
    val vm: UIViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(mClass: Class<T>) = UIViewModel(dao) as T
        }
    )
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

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    var mapaPastas by remember { mutableStateOf(carregarVinculosPastas(context)) }
    var pastaAberta by remember { mutableStateOf<String?>(null) }
    var pendingUploadUri by remember { mutableStateOf<Uri?>(null) }
    var fileToMove by remember { mutableStateOf<ArquivoNuvem?>(null) }

    var showStorage by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var nomeNovaPasta by remember { mutableStateOf("") }

    var syncCamera by remember { mutableStateOf(true) }
    var syncWhatsApp by remember { mutableStateOf(false) }
    var syncDownloads by remember { mutableStateOf(false) }
    var syncDocumentos by remember { mutableStateOf(false) }

    var isUploading by remember { mutableStateOf(false) }
    var cloudBannerText by remember { mutableStateOf("Nuvem TDLib Ativa • Mensagens Salvas") }

    LaunchedEffect(isUploading) {
        while (isUploading) {
            delay(350)
            val atual = NexusTransfer.progress.value ?: 10
            if (atual < 92) {
                val novo = (atual + 12).coerceAtMost(92)
                NexusTransfer.progress.value = novo
            }
        }
    }

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) {
                TelegramAuth.errorText.value = "Erro (${obj.code}): ${obj.message}"
            }
        }
    }

    fun executarUpload(uri: Uri, pastaEscolhida: String) {
        scope.launch {
            isUploading = true
            NexusTransfer.progress.value = 10
            val (nome, tamanho) = obterDetalhesFicheiro(context, uri)
            NexusTransfer.label.value = "Enviando '$nome'..."
            cloudBannerText = "Enviando '$nome' para o Telegram..."

            val arquivoTemp = withContext(Dispatchers.IO) {
                copiarUriParaCacheTDLib(context, uri, nome)
            }
            val copiaPrivada = withContext(Dispatchers.IO) {
                salvarMiniaturaPrivada(context, uri, nome)
            }

            if (arquivoTemp != null) {
                enviarParaMensagensSalvas(client, arquivoTemp.absolutePath, nome, pastaEscolhida) { sucesso, remoteId, detalhe ->
                    isUploading = false
                    NexusTransfer.progress.value = null
                    cloudBannerText = detalhe
                    if (sucesso) {
                        val timestamp = System.currentTimeMillis()
                        if (pastaEscolhida.isNotBlank()) {
                            salvarVinculoPasta(context, timestamp, pastaEscolhida)
                            mapaPastas = carregarVinculosPastas(context)
                        }
                        vm.salvarArquivo(
                            ArquivoNuvem(
                                fileId = remoteId,
                                nomeArquivo = nome,
                                tamanhoBytes = if (tamanho > 0) tamanho else arquivoTemp.length(),
                                dataUpload = timestamp,
                                caminhoMiniatura = copiaPrivada
                            )
                        )
                    }
                }
            } else {
                isUploading = false
                NexusTransfer.progress.value = null
                cloudBannerText = "Erro ao ler o ficheiro selecionado."
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            if (pastaAberta != null) {
                executarUpload(it, pastaAberta!!)
            } else if (pastas.isNotEmpty()) {
                pendingUploadUri = it
            } else {
                executarUpload(it, "")
            }
        }
    }

    if (pendingUploadUri != null) {
        val uriAtual = pendingUploadUri!!
        Dialog(onDismissRequest = { pendingUploadUri = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("GUARDAR NA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Escolha onde organizar este ficheiro:", color = TextGrey, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            pendingUploadUri = null
                            executarUpload(uriAtual, "")
                        },
                        border = BorderStroke(1.dp, TextGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Galeria Geral (Sem pasta)", color = Color.White)
                    }

                    Spacer(Modifier.height(8.dp))

                    pastas.forEach { p ->
                        Button(
                            onClick = {
                                pendingUploadUri = null
                                executarUpload(uriAtual, p.nomePasta)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                            shape = RoundedCornerShape(10.dp),
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

    if (fileToMove != null) {
        val arqMover = fileToMove!!
        Dialog(onDismissRequest = { fileToMove = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("MOVER PARA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(arqMover.nomeArquivo, color = TextGrey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            salvarVinculoPasta(context, arqMover.dataUpload, "")
                            mapaPastas = carregarVinculosPastas(context)
                            fileToMove = null
                        },
                        border = BorderStroke(1.dp, TextGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Remover da Pasta (Apenas Galeria)", color = Color.White)
                    }

                    Spacer(Modifier.height(8.dp))

                    if (pastas.isEmpty()) {
                        Text("Crie uma pasta na aba PASTAS primeiro.", color = DangerRed, fontSize = 12.sp)
                    } else {
                        pastas.forEach { p ->
                            Button(
                                onClick = {
                                    salvarVinculoPasta(context, arqMover.dataUpload, p.nomePasta)
                                    mapaPastas = carregarVinculosPastas(context)
                                    fileToMove = null
                                    Toast.makeText(context, "Movido para ${p.nomePasta}!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(10.dp),
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

    if (viewerFile != null) {
        val currentFile = viewerFile!!
        Dialog(
            onDismissRequest = { viewerFile = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                val ext = currentFile.nomeArquivo.substringAfterLast('.', "").uppercase()
                val isVideo = ext in listOf("MP4", "MKV", "MOV", "AVI") || currentFile.nomeArquivo.contains("Video", ignoreCase = true)
                val isImage = ext in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || currentFile.nomeArquivo.contains("IMG", ignoreCase = true)

                if (isImage) {
                    AsyncImage(
                        model = Uri.parse(currentFile.caminhoMiniatura),
                        contentDescription = currentFile.nomeArquivo,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else if (isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoURI(Uri.parse(currentFile.caminhoMiniatura))
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize().align(Alignment.Center)
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.InsertDriveFile, null, tint = Gold, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(currentFile.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("Toque no botão de partilha no topo para enviar ou abrir noutro leitor.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgBlack.copy(alpha = 0.75f))
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewerFile = null }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                    Text(
                        text = currentFile.nomeArquivo,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                    )
                    IconButton(onClick = {
                        compartilharFicheiroNativo(context, currentFile.caminhoMiniatura, currentFile.nomeArquivo)
                    }) {
                        Icon(Icons.Default.Share, null, tint = Gold)
                    }
                }
            }
        }
    }

    if (loginStep < 3) {
        Column(
            modifier = Modifier.fillMaxSize().background(BgBlack).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Cloud, null, tint = Gold, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("NEXUS CLOUD", color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(statusText, color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)

            if (errorText != null) {
                Spacer(Modifier.height(12.dp))
                Text(errorText!!, color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(32.dp))

            when (loginStep) {
                0 -> {
                    OutlinedTextField(
                        value = phoneNumber, onValueChange = { phoneNumber = it }, label = { Text("Número (Ex: +5585999999999)", color = Color.Gray) }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            TelegramAuth.errorText.value = null
                            TelegramAuth.statusText.value = "Contactando servidores do Telegram..."
                            val cleanPhone = phoneNumber.replace(" ", "").replace("-", "")
                            val settings = TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray())
                            client.send(TdApi.SetAuthenticationPhoneNumber(cleanPhone, settings), responseHandler)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("ENVIAR CÓDIGO SMS", fontWeight = FontWeight.Bold, color = Color.White) }
                }
                1 -> {
                    OutlinedTextField(
                        value = smsCode, onValueChange = { smsCode = it }, label = { Text("Código recebido no Telegram/SMS", color = Color.Gray) }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            TelegramAuth.errorText.value = null
                            TelegramAuth.statusText.value = "Verificando código..."
                            client.send(TdApi.CheckAuthenticationCode(smsCode.trim()), responseHandler)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("CONFIRMAR E ENTRAR", fontWeight = FontWeight.Bold, color = Color.White) }
                }
                2 -> {
                    OutlinedTextField(
                        value = password2FA, onValueChange = { password2FA = it }, label = { Text("Senha de Duas Etapas (2FA)", color = Color.Gray) }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            TelegramAuth.errorText.value = null
                            TelegramAuth.statusText.value = "Verificando senha..."
                            client.send(TdApi.CheckAuthenticationPassword(password2FA), responseHandler)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("DESBLOQUEAR CONTA", fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = SurfaceDark, modifier = Modifier.width(300.dp)) {
                Column(modifier = Modifier.fillMaxWidth().background(SurfaceDarker).padding(32.dp)) {
                    Box(modifier = Modifier.size(64.dp).border(2.dp, Gold, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, tint = Gold, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Admin SSD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(phoneNumber, color = TextGrey, fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Storage, null) }, label = { Text("Armazenamento") }, selected = false,
                    onClick = { showStorage = true; scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Sync, null) }, label = { Text("Sincronização Auto") }, selected = false,
                    onClick = { showSync = true; scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = TextGrey)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column(Modifier.background(BgBlack)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("NEXUS CLOUD", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text(
                                text = if (transferProgress != null) transferLabel else cloudBannerText,
                                color = if (transferProgress != null) Gold else TextGrey,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (transferProgress != null || isUploading) {
                            Text(
                                text = "${transferProgress ?: 10}%",
                                color = Gold,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.CloudDone, null, tint = Gold)
                        }
                    }

                    if (transferProgress != null || isUploading) {
                        val fra = ((transferProgress ?: 10).coerceIn(5, 100)) / 100f
                        Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(SurfaceDark)) {
                            Box(modifier = Modifier.fillMaxWidth(fra).height(4.dp).background(Gold))
                        }
                    }

                    TabRow(
                        selectedTabIndex = selectedTab, containerColor = BgBlack,
                        indicator = { TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = Gold) },
                        divider = { Divider(color = Color.Transparent) }
                    ) {
                        listOf("GALERIA", "PASTAS", "FOTOS", "DOCS").forEachIndexed { i, title ->
                            Tab(
                                selected = selectedTab == i,
                                onClick = {
                                    selectedTab = i
                                    if (i != 1) pastaAberta = null
                                }
                            ) {
                                Text(title, color = if (selectedTab == i) Gold else TextGrey, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    containerColor = DangerRed, contentColor = Color.White, shape = CircleShape,
                    modifier = Modifier.shadow(elevation = 16.dp, shape = CircleShape, spotColor = DangerRed, ambientColor = DangerRed)
                ) { Icon(Icons.Default.CloudUpload, null) }
            },
            containerColor = BgBlack
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (selectedTab == 1) {
                    if (pastaAberta != null) {
                        val arquivosDaPasta = remember(arquivos, mapaPastas, pastaAberta) {
                            arquivos.filter { mapaPastas[it.dataUpload] == pastaAberta }
                        }
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().background(SurfaceDarker).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { pastaAberta = null }) {
                                    Icon(Icons.Default.ArrowBack, null, tint = Gold)
                                }
                                Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pastaAberta!!, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("${arquivosDaPasta.size} ficheiro(s) nesta pasta", color = TextGrey, fontSize = 11.sp)
                                }
                            }

                            if (arquivosDaPasta.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = TextGrey, modifier = Modifier.size(56.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("Pasta '$pastaAberta' vazia", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Toque no botão vermelho (+) para enviar ficheiros diretamente para esta pasta.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                                }
                            } else {
                                GrelhaArquivos(arquivosDaPasta, mapaPastas) { selectedFile = it }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            OutlinedButton(
                                onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) { Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.height(16.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(pastas) { pasta ->
                                    val qtd = arquivos.count { mapaPastas[it.dataUpload] == pasta.nomePasta }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(SurfaceDark, RoundedCornerShape(12.dp))
                                            .border(1.dp, SurfaceDarker, RoundedCornerShape(12.dp))
                                            .clickable { pastaAberta = pasta.nomePasta }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(pasta.nomePasta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("$qtd item(ns)", color = TextGrey, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val listaFiltrada = remember(arquivos, selectedTab) {
                        when (selectedTab) {
                            2 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || it.nomeArquivo.contains("IMG", ignoreCase = true) }
                            3 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("PDF", "ZIP", "RAR", "DOC", "DOCX", "XLS", "XLSX", "TXT", "APK") }
                            else -> arquivos
                        }
                    }

                    if (listaFiltrada.isEmpty()) {
                        Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.CloudUpload, null, tint = TextGrey, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Nenhum ficheiro nesta aba", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Toque no botão vermelho para enviar para a nuvem do Telegram.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    } else {
                        GrelhaArquivos(listaFiltrada, mapaPastas) { selectedFile = it }
                    }
                }
            }

            if (selectedFile != null) {
                val pastaAtual = mapaPastas[selectedFile!!.dataUpload] ?: "Galeria Geral"
                ModalBottomSheet(onDismissRequest = { selectedFile = null }, sheetState = sheetState, containerColor = SurfaceDark) {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(selectedFile!!.nomeArquivo, color = Gold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                        Text(
                            "Tamanho: ${formatarTamanhoNuvem(selectedFile!!.tamanhoBytes)} • Pasta: $pastaAtual",
                            color = TextGrey, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.height(8.dp))

                        ListItem(
                            headlineContent = { Text("Abrir no Nexus Cloud", color = Color.White) },
                            leadingContent = { Icon(Icons.Default.Fullscreen, null, tint = Color.White) },
                            modifier = Modifier.clickable {
                                viewerFile = selectedFile
                                selectedFile = null
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            headlineContent = { Text("Mover para Pasta...", color = Gold) },
                            leadingContent = { Icon(Icons.Default.Folder, null, tint = Gold) },
                            modifier = Modifier.clickable {
                                fileToMove = selectedFile
                                selectedFile = null
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            headlineContent = { Text("Compartilhar...", color = Color.White) },
                            leadingContent = { Icon(Icons.Default.Share, null, tint = Color.White) },
                            modifier = Modifier.clickable {
                                val arq = selectedFile!!
                                selectedFile = null
                                compartilharFicheiroNativo(context, arq.caminhoMiniatura, arq.nomeArquivo)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            headlineContent = { Text("Baixar da Nuvem", color = Gold) },
                            leadingContent = { Icon(Icons.Default.CloudDownload, null, tint = Gold) },
                            modifier = Modifier.clickable {
                                val arqAtual = selectedFile!!
                                selectedFile = null
                                baixarFicheiroDaNuvem(client, arqAtual.fileId) { msg ->
                                    scope.launch(Dispatchers.Main) {
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        ListItem(
                            headlineContent = { Text("Remover do registo", color = DangerRed) },
                            leadingContent = { Icon(Icons.Default.Delete, null, tint = DangerRed) },
                            modifier = Modifier.clickable {
                                salvarVinculoPasta(context, selectedFile!!.dataUpload, "")
                                mapaPastas = carregarVinculosPastas(context)
                                vm.apagar(selectedFile!!.id)
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
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("NOVA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(
                                value = nomeNovaPasta, onValueChange = { nomeNovaPasta = it }, label = { Text("Nome da Pasta", color = TextGrey) }, singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray), modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { vm.criarPasta(nomeNovaPasta); nomeNovaPasta = ""; showNewFolder = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("CRIAR", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            if (showStorage) {
                Dialog(onDismissRequest = { showStorage = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.matchParentSize()) { drawCircle(color = Gold, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))) }
                                Text("∞", color = Gold, fontSize = 36.sp)
                            }
                            Spacer(Modifier.height(24.dp))
                            Text("TOTAL ENVIADO PELO NEXUS", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text(formatarTamanhoNuvem(armazenamento), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(listOf(Gold, DangerRed)), RoundedCornerShape(3.dp)))
                            Spacer(Modifier.height(8.dp))
                            Text("${arquivos.size} ficheiro(s) sincronizados via TDLib", color = TextGrey, fontSize = 10.sp)
                            Spacer(Modifier.height(32.dp))
                            Button(onClick = { showStorage = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("FECHAR", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            if (showSync) {
                Dialog(onDismissRequest = { showSync = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.Default.CloudSync, null, tint = Gold, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("BACKUP AUTOMÁTICO", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Escolha as pastas locais que deseja enviar automaticamente para a nuvem.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(24.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                LinhaSincronizacao("Câmera (Fotos e Vídeos)", syncCamera) { syncCamera = it }
                                LinhaSincronizacao("WhatsApp Images", syncWhatsApp) { syncWhatsApp = it }
                                LinhaSincronizacao("Downloads", syncDownloads) { syncDownloads = it }
                                LinhaSincronizacao("Documentos / PDFs", syncDocumentos) { syncDocumentos = it }
                            }
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { showSync = false; cloudBannerText = "Sincronização em segundo plano ativada!" },
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("SALVAR PREFERÊNCIAS", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}
