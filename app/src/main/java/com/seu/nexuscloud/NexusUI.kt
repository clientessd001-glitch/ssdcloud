package com.seu.nexuscloud

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
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

fun enviarParaMensagensSalvas(
    client: Client,
    caminhoLocal: String,
    nomeOriginal: String,
    onResult: (Boolean, Int, String) -> Unit
) {
    // 1. Obtém o utilizador autenticado para descobrir o ID das "Mensagens Salvas"
    client.send(TdApi.GetMe()) { meObj ->
        if (meObj is TdApi.User) {
            // 2. Abre/cria o chat privado consigo mesmo (Saved Messages)
            client.send(TdApi.CreatePrivateChat(meObj.id, false)) { chatObj ->
                if (chatObj is TdApi.Chat) {
                    val inputFile = TdApi.InputFileLocal(caminhoLocal)
                    val legenda = TdApi.FormattedText("☁️ Nexus Cloud • $nomeOriginal", emptyArray())
                    val conteudo = TdApi.InputMessageDocument(inputFile, null, false, legenda)

                    val req = TdApi.SendMessage().apply {
                        chatId = chatObj.id
                        inputMessageContent = conteudo
                    }

                    // 3. Dispara o upload real para os servidores do Telegram
                    client.send(req) { msgObj ->
                        when (msgObj) {
                            is TdApi.Message -> {
                                onResult(true, msgObj.id.toInt(), "Enviado para Mensagens Salvas no Telegram!")
                            }
                            is TdApi.Error -> {
                                onResult(false, 0, "Erro TDLib (${msgObj.code}): ${msgObj.message}")
                            }
                            else -> {
                                onResult(true, 0, "Upload iniciado na TDLib!")
                            }
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

    var phoneNumber by remember { mutableStateOf("+5585") }
    var smsCode by remember { mutableStateOf("") }
    var password2FA by remember { mutableStateOf("") }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedFile by remember { mutableStateOf<ArquivoNuvem?>(null) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    var showStorage by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var nomeNovaPasta by remember { mutableStateOf("") }

    var isUploading by remember { mutableStateOf(false) }
    var cloudBannerText by remember { mutableStateOf("Nuvem TDLib Ativa • Mensagens Salvas") }

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) {
                TelegramAuth.errorText.value = "Erro (${obj.code}): ${obj.message}"
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                isUploading = true
                val (nome, tamanho) = obterDetalhesFicheiro(context, it)
                cloudBannerText = "Enviando '$nome' para o Telegram..."

                val arquivoTemp = withContext(Dispatchers.IO) {
                    copiarUriParaCacheTDLib(context, it, nome)
                }

                if (arquivoTemp != null) {
                    enviarParaMensagensSalvas(client, arquivoTemp.absolutePath, nome) { sucesso, msgId, detalhe ->
                        isUploading = false
                        cloudBannerText = detalhe
                        if (sucesso) {
                            vm.salvarArquivo(
                                ArquivoNuvem(
                                    fileId = msgId,
                                    nomeArquivo = nome,
                                    tamanhoBytes = if (tamanho > 0) tamanho else arquivoTemp.length(),
                                    dataUpload = System.currentTimeMillis(),
                                    caminhoMiniatura = it.toString()
                                )
                            )
                        }
                    }
                } else {
                    isUploading = false
                    cloudBannerText = "Erro ao ler o ficheiro selecionado no telemóvel."
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
                    Box(
                        modifier = Modifier.size(64.dp).border(2.dp, Gold, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
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
                    icon = { Icon(Icons.Default.Sync, null) }, label = { Text("Sincronização") }, selected = false,
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
                            Text(cloudBannerText, color = TextGrey, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (isUploading) {
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Default.CloudDone, null, tint = Gold)
                        }
                    }
                    TabRow(
                        selectedTabIndex = selectedTab, containerColor = BgBlack,
                        indicator = { TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = Gold) },
                        divider = { Divider(color = Color.Transparent) }
                    ) {
                        listOf("GALERIA", "PASTAS", "FOTOS", "DOCS").forEachIndexed { i, title ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i }) {
                                Text(title, color = if (selectedTab == i) Gold else TextGrey, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    containerColor = DangerRed,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.shadow(elevation = 16.dp, shape = CircleShape, spotColor = DangerRed, ambientColor = DangerRed)
                ) {
                    Icon(Icons.Default.CloudUpload, null)
                }
            },
            containerColor = BgBlack
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (selectedTab == 1) {
                    // Aba PASTAS
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        OutlinedButton(
                            onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold) }

                        Spacer(Modifier.height(16.dp))

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(pastas) { pasta ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                                        .border(1.dp, SurfaceDarker, RoundedCornerShape(12.dp))
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = Gold, modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(pasta.nomePasta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else {
                    // Filtra os ficheiros consoante a aba selecionada (0 = Galeria, 2 = Fotos, 3 = Docs)
                    val listaFiltrada = remember(arquivos, selectedTab) {
                        when (selectedTab) {
                            2 -> arquivos.filter {
                                val ext = it.nomeArquivo.substringAfterLast('.', "").uppercase()
                                ext in listOf("JPG", "JPEG", "PNG", "WEBP", "HEIC") || it.nomeArquivo.contains("IMG", ignoreCase = true)
                            }
                            3 -> arquivos.filter {
                                val ext = it.nomeArquivo.substringAfterLast('.', "").uppercase()
                                ext in listOf("PDF", "ZIP", "RAR", "DOC", "DOCX", "XLS", "XLSX", "TXT", "APK")
                            }
                            else -> arquivos
                        }
                    }

                    if (listaFiltrada.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.CloudUpload, null, tint = TextGrey, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Nenhum ficheiro nesta aba", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Toque no botão vermelho para enviar para a nuvem do Telegram.", color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize().padding(1.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(listaFiltrada) { arq ->
                                val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase()
                                Box(
                                    modifier = Modifier.aspectRatio(1f).background(SurfaceDark).clickable { selectedFile = arq },
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        ext in listOf("JPG", "JPEG", "PNG", "WEBP") || arq.nomeArquivo.contains("IMG", ignoreCase = true) -> {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                                Text("IMG", color = Gold, fontWeight = FontWeight.Black, fontSize = 22.sp)
                                                Text(arq.nomeArquivo, color = TextGrey, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
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
                                }
                            }
                        }
                    }
                }
            }

            if (selectedFile != null) {
                ModalBottomSheet(onDismissRequest = { selectedFile = null }, sheetState = sheetState, containerColor = SurfaceDark) {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(selectedFile!!.nomeArquivo, color = Gold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                        Text(
                            "Tamanho: ${formatarTamanhoNuvem(selectedFile!!.tamanhoBytes)} • Salvo na Nuvem TDLib",
                            color = TextGrey,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        ListItem(
                            headlineContent = { Text("Remover do registo", color = DangerRed) },
                            leadingContent = { Icon(Icons.Default.Delete, null, tint = DangerRed) },
                            modifier = Modifier.clickable { vm.apagar(selectedFile!!.id); selectedFile = null },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            if (showNewFolder) {
                Dialog(onDismissRequest = { showNewFolder = false }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("NOVA PASTA", color = Gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(
                                value = nomeNovaPasta,
                                onValueChange = { nomeNovaPasta = it },
                                label = { Text("Nome da Pasta", color = TextGrey) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    vm.criarPasta(nomeNovaPasta)
                                    nomeNovaPasta = ""
                                    showNewFolder = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("CRIAR", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            if (showStorage) {
                Dialog(onDismissRequest = { showStorage = false }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.matchParentSize()) {
                                    drawCircle(color = Gold, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)))
                                }
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
                            Button(
                                onClick = { showStorage = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("FECHAR", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            if (showSync) {
                Dialog(onDismissRequest = { showSync = false }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = SurfaceDarker, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.Default.Sync, null, tint = Gold, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("SINCRONIZAÇÃO", color = Gold, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(cloudBannerText, color = TextGrey, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(24.dp))
                            LinearProgressIndicator(
                                progress = if (isUploading) 0.5f else 1f,
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = DangerRed,
                                trackColor = SurfaceDark
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { showSync = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) { Text("OK", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}
