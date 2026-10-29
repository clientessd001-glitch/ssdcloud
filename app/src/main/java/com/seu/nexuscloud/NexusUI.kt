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
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    fun criarPasta(nome: String) = viewModelScope.launch { if (nome.isNotBlank()) dao.criarPasta(PastaNuvem(nomePasta = nome)) }
    fun salvarArquivo(arq: ArquivoNuvem) = viewModelScope.launch { dao.salvarArquivo(arq) }
    fun apagar(arq: Int) = viewModelScope.launch { dao.apagarArquivo(arq) }
}

fun obterDetalhesFicheiro(context: Context, uri: Uri): Pair<String, Long> {
    var nome = "Arquivo"
    var tamanho = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val iNome = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iTamanho = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (iNome != -1) nome = cursor.getString(iNome)
            if (iTamanho != -1) tamanho = cursor.getLong(iTamanho)
        }
    }
    return Pair(nome, tamanho)
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

    // Escuta o estado real vindo dos servidores do Telegram
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

    val responseHandler = remember {
        Client.ResultHandler { obj ->
            if (obj is TdApi.Error) {
                TelegramAuth.errorText.value = "Erro (${obj.code}): ${obj.message}"
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val (nome, tamanho) = obterDetalhesFicheiro(context, it)
            vm.salvarArquivo(ArquivoNuvem(fileId = 0, nomeArquivo = nome, tamanhoBytes = tamanho, dataUpload = System.currentTimeMillis(), caminhoMiniatura = it.toString()))
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
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                        Text("NEXUS CLOUD", color = Gold, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f), fontSize = 18.sp)
                        Icon(Icons.Default.CloudQueue, null, tint = TextGrey)
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
                    Icon(Icons.Default.Add, null)
                }
            },
            containerColor = BgBlack
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (selectedTab == 0) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3), 
                        modifier = Modifier.fillMaxSize().padding(1.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(arquivos) { arq ->
                            val ext = arq.nomeArquivo.substringAfterLast('.', "").uppercase()
                            Box(
                                modifier = Modifier.aspectRatio(1f).background(SurfaceDark).clickable { selectedFile = arq },
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    ext in listOf("JPG", "PNG") || arq.nomeArquivo.contains("IMG", ignoreCase = true) -> {
                                        Text("IMG", color = Gold, fontWeight = FontWeight.Black, fontSize = 24.sp)
                                    }
                                    ext == "PDF" || arq.nomeArquivo.contains("PDF", ignoreCase = true) -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.PictureAsPdf, null, tint = DangerRed, modifier = Modifier.size(32.dp))
                                            Text("PDF", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    ext == "ZIP" || arq.nomeArquivo.contains("ZIP", ignoreCase = true) -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.FolderZip, null, tint = Gold, modifier = Modifier.size(32.dp))
                                            Text("ZIP", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    ext in listOf("MP4", "MKV") || arq.nomeArquivo.contains("Video", ignoreCase = true) -> {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            Icon(Icons.Default.PlayArrow, null, tint = Color.DarkGray, modifier = Modifier.size(48.dp).align(Alignment.Center))
                                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black, RoundedCornerShape(4.dp))) {
                                                Text("12:04", color = Color.White, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                            }
                                        }
                                    }
                                    else -> {
                                        Text("FOTO", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedButton(
                            onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            
            if (selectedFile != null) {
                ModalBottomSheet(onDismissRequest = { selectedFile = null }, sheetState = sheetState, containerColor = SurfaceDark) {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(selectedFile!!.nomeArquivo, color = Gold, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                        ListItem(headlineContent = { Text("Apagar", color = DangerRed) }, leadingContent = { Icon(Icons.Default.Delete, null, tint = DangerRed) },
                            modifier = Modifier.clickable { vm.apagar(selectedFile!!.id); selectedFile = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
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
                            Text("TOTAL NA NUVEM", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text("12.4 GB", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(listOf(Gold, DangerRed)), RoundedCornerShape(3.dp)))
                            Spacer(Modifier.height(8.dp))
                            Text("Armazenamento TDLib", color = TextGrey, fontSize = 10.sp)
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
                            Text("Status: Conectado à TDLib", color = TextGrey, fontSize = 12.sp)
                            Spacer(Modifier.height(24.dp))
                            LinearProgressIndicator(
                                progress = 1f,
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
