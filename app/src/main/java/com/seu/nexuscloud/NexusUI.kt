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
        if (chave != null && v is String && v.isNotBlank()) mapa[chave] = v
    }
    return mapa
}

fun salvarVinculoPasta(context: Context, dataUpload: Long, nomePasta: String) {
    val prefs = context.getSharedPreferences("nexus_folder_links", Context.MODE_PRIVATE)
    if (nomePasta.isBlank()) prefs.edit().remove(dataUpload.toString()).apply()
    else prefs.edit().putString(dataUpload.toString(), nomePasta).apply()
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

fun salvarMiniaturaPrivada(context: Context, uri: Uri, nomeArquivo: String): String {
    return try {
        val dir = File(context.filesDir, "nexus_thumbs").apply { if (!exists()) mkdirs() }
        val f = File(dir, "thumb_${System.currentTimeMillis()}_${nomeArquivo.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
        Uri.fromFile(f).toString()
    } catch (_: Exception) { uri.toString() }
}

fun copiarUriParaCacheTDLib(context: Context, uri: Uri, nomeArquivo: String): File? {
    return try {
        val dir = File(context.filesDir, "tdlib_upload_cache").apply { if (!exists()) mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}_${nomeArquivo.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
        f
    } catch (_: Exception) { null }
}

fun descobrirMimeType(nome: String): String {
    return when (nome.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
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
        val mime = descobrirMimeType(nomeArquivo)
        if (caminhoMiniatura.startsWith("content://")) {
            val u = Uri.parse(caminhoMiniatura)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(u) ?: mime
                putExtra(Intent.EXTRA_STREAM, u)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(i, "Compartilhar $nomeArquivo"))
            return
        }
        val limpo = if (caminhoMiniatura.startsWith("file://")) Uri.parse(caminhoMiniatura).path ?: caminhoMiniatura.removePrefix("file://") else caminhoMiniatura
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

fun baixarFicheiroDaNuvem(client: Client, targetFileId: Int, onResult: (String) -> Unit) {
    if (targetFileId == 0) { onResult("ID ausente na nuvem."); return }
    NexusTransfer.progress.value = 20
    NexusTransfer.label.value = "Baixando da nuvem..."
    val req = TdApi.DownloadFile().apply { fileId = targetFileId; priority = 1; synchronous = false }
    client.send(req) { res ->
        NexusTransfer.progress.value = null
        when (res) {
            is TdApi.File -> onResult(if (!res.local?.path.isNullOrBlank()) "Salvo em: ${res.local.path}" else "Transferência iniciada!")
            is TdApi.Error -> onResult("Erro (${res.code}): ${res.message}")
            else -> onResult("Download em andamento...")
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
fun LinhaSincronizacao(nomePasta: String, ativo: Boolean, onAtivoChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = if (ativo) Gold else TextGrey, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(nomePasta, color = if (ativo) Color.White else TextGrey, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Switch(checked = ativo, onCheckedChange = onAtivoChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DangerRed, uncheckedThumbColor = TextGrey, uncheckedTrackColor = SurfaceDark))
    }
}

@Composable
fun GrelhaArquivos(lista: List<ArquivoNuvem>, mapaPastas: Map<Long, String>, onFileClick: (ArquivoNuvem) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(lista) { arq ->
            val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase()
            val pasta = mapaPastas[arq.dataUpload]
            Box(Modifier.aspectRatio(1f).background(SurfaceDark).clickable { onFileClick(arq) }, contentAlignment = Alignment.Center) {
                when {
                    ext in listOf("JPG", "JPEG", "PNG", "WEBP") || arq.nomeArquivo.contains("IMG", true) ->
                        AsyncImage(model = Uri.parse(arq.caminhoMiniatura), contentDescription = arq.nomeArquivo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    ext == "PDF" -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(32.dp))
                        Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ext in listOf("ZIP", "RAR", "APK") -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.FolderZip, null, tint = Gold, modifier = Modifier.size(32.dp))
                        Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ext in listOf("MP4", "MKV", "MOV") -> Box(Modifier.fillMaxSize()) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp).align(Alignment.Center))
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
            if (atual < 92) NexusTransfer.progress.value = (atual + 12).coerceAtMost(92)
        }
    }

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) TelegramAuth.errorText.value = "Erro (${obj.code}): ${obj.message}"
        }
    }

    fun executarUpload(uri: Uri, pastaEscolhida: String) {
        scope.launch {
            isUploading = true
            NexusTransfer.progress.value = 10
            val (nome, tamanho) = obterDetalhesFicheiro(context, uri)
            NexusTransfer.label.value = "Enviando '$nome'..."
            cloudBannerText = "Enviando '$nome'..."
            val arqTemp = withContext(Dispatchers.IO) { copiarUriParaCacheTDLib(context, uri, nome) }
            val miniatura = withContext(Dispatchers.IO) { salvarMiniaturaPrivada(context, uri, nome) }
            if (arqTemp != null) {
                enviarParaMensagensSalvas(client, arqTemp.absolutePath, nome, pastaEscolhida) { ok, rId, msg ->
                    isUploading = false
                    NexusTransfer.progress.value = null
                    cloudBannerText = msg
                    if (ok) {
                        val ts = System.currentTimeMillis()
                        if (pastaEscolhida.isNotBlank()) {
                            salvarVinculoPasta(context, ts, pastaEscolhida)
                            mapaPastas = carregarVinculosPastas(context)
                        }
                        vm.salvarArquivo(ArquivoNuvem(fileId = rId, nomeArquivo = nome, tamanhoBytes = if (tamanho > 0) tamanho else arqTemp.length(), dataUpload = ts, caminhoMiniatura = miniatura))
                    }
                }
            } else {
                isUploading = false
                NexusTransfer.progress.value = null
                cloudBannerText = "Erro ao ler ficheiro."
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            if (pastaAberta != null) executarUpload(it, pastaAberta!!)
            else if (pastas.isNotEmpty()) pendingUploadUri = it
            else executarUpload(it, "")
        }
    }

    if (pendingUploadUri != null) {
        val u = pendingUploadUri!!
        Dialog(onDismissRequest = { pendingUploadUri = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("GUARDAR NA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { pendingUploadUri = null; executarUpload(u, "") }, border = BorderStroke(1.dp, TextGrey), modifier = Modifier.fillMaxWidth()) {
                        Text("Galeria Geral (Sem pasta)", color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    pastas.forEach { p ->
                        Button(onClick = { pendingUploadUri = null; executarUpload(u, p.nomePasta) }, colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
        Dialog(onDismissRequest = { viewerFile = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val ext = cur.nomeArquivo.substringAfterLast('.', "").uppercase()
                if (ext in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || cur.nomeArquivo.contains("IMG", true)) {
                    AsyncImage(model = Uri.parse(cur.caminhoMiniatura), contentDescription = cur.nomeArquivo, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (ext in listOf("MP4", "MKV", "MOV")) {
                    AndroidView(factory = { ctx -> android.widget.VideoView(ctx).apply { setVideoURI(Uri.parse(cur.caminhoMiniatura)); start() } }, modifier = Modifier.fillMaxSize().align(Alignment.Center))
                } else {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.InsertDriveFile, null, tint = Gold, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(cur.nomeArquivo, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Row(Modifier.fillMaxWidth().background(BgBlack.copy(alpha = 0.75f)).padding(16.dp).align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewerFile = null }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    Text(cur.nomeArquivo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    IconButton(onClick = { compartilharFicheiroNativo(context, cur.caminhoMiniatura, cur.nomeArquivo) }) { Icon(Icons.Default.Share, null, tint = Gold) }
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
                        if (transferProgress != null || isUploading) {
                            Text("${transferProgress ?: 10}%", color = Gold, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.CloudDone, null, tint = Gold)
                        }
                    }
                    if (transferProgress != null || isUploading) {
                        val fra = ((transferProgress ?: 10).coerceIn(5, 100)) / 100f
                        Box(Modifier.fillMaxWidth().height(4.dp).background(SurfaceDark)) {
                            Box(Modifier.fillMaxWidth(fra).height(4.dp).background(Gold))
                        }
                    }
                    TabRow(selectedTabIndex = selectedTab, containerColor = BgBlack, indicator = { TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = Gold) }, divider = {}) {
                        listOf("GALERIA", "PASTAS", "FOTOS", "DOCS").forEachIndexed { i, t ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i; if (i != 1) pastaAberta = null }) {
                                Text(t, color = if (selectedTab == i) Gold else TextGrey, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                        val daPasta = remember(arquivos, mapaPastas, pastaAberta) { arquivos.filter { mapaPastas[it.dataUpload] == pastaAberta } }
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().background(SurfaceDarker).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { pastaAberta = null }) { Icon(Icons.Default.ArrowBack, null, tint = Gold) }
                                Text(pastaAberta!!, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            GrelhaArquivos(daPasta, mapaPastas) { selectedFile = it }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            OutlinedButton(onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(16.dp))
                            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pastas) { p ->
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
                    val filtrada = remember(arquivos, selectedTab) {
                        when (selectedTab) {
                            2 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || it.nomeArquivo.contains("IMG", true) }
                            3 -> arquivos.filter { it.nomeArquivo.substringAfterLast('.', "").uppercase() in listOf("PDF", "ZIP", "RAR", "DOC", "DOCX", "XLS", "XLSX", "TXT", "APK") }
                            else -> arquivos
                        }
                    }
                    GrelhaArquivos(filtrada, mapaPastas) { selectedFile = it }
                }
            }

            if (selectedFile != null) {
                val arq = selectedFile!!
                ModalBottomSheet(onDismissRequest = { selectedFile = null }, sheetState = sheetState, containerColor = SurfaceDark) {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(arq.nomeArquivo, color = Gold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                        ListItem(headlineContent = { Text("Abrir no Nexus Cloud", color = Color.White) }, leadingContent = { Icon(Icons.Default.Fullscreen, null, tint = Color.White) }, modifier = Modifier.clickable { viewerFile = arq; selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Mover para Pasta...", color = Gold) }, leadingContent = { Icon(Icons.Default.Folder, null, tint = Gold) }, modifier = Modifier.clickable { fileToMove = arq; selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Compartilhar...", color = Color.White) }, leadingContent = { Icon(Icons.Default.Share, null, tint = Color.White) }, modifier = Modifier.clickable { selectedFile = null; compartilharFicheiroNativo(context, arq.caminhoMiniatura, arq.nomeArquivo) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                        ListItem(headlineContent = { Text("Baixar da Nuvem", color = Gold) }, leadingContent = { Icon(Icons.Default.CloudDownload, null, tint = Gold) }, modifier = Modifier.clickable { selectedFile = null; baixarFicheiroDaNuvem(client, arq.fileId) { m -> scope.launch(Dispatchers.Main) { Toast.makeText(context, m, Toast.LENGTH_LONG).show() } } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
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
                    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("BACKUP AUTOMÁTICO", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Spacer(Modifier.height(16.dp))
                            LinhaSincronizacao("Câmera", syncCamera) { syncCamera = it }
                            LinhaSincronizacao("WhatsApp", syncWhatsApp) { syncWhatsApp = it }
                            LinhaSincronizacao("Downloads", syncDownloads) { syncDownloads = it }
                            LinhaSincronizacao("Documentos", syncDocumentos) { syncDocumentos = it }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { showSync = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed), modifier = Modifier.fillMaxWidth()) { Text("SALVAR", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}
// === FIM DO FICHEIRO ===
