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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

val DarkBlue = Color(0xFF0B1120)
val SurfaceDark = Color(0xFF111827)
val Gold = Color(0xFFFFD700)
val DangerRed = Color(0xFFE53935)

class UIViewModel(private val dao: NexusDao) : ViewModel() {
    val arquivos = dao.buscarTodos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pastas = dao.buscarPastas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val armazenamento = dao.calcularUsoTotal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun criarPasta(nome: String) = viewModelScope.launch {
        if (nome.isNotBlank()) dao.criarPasta(PastaNuvem(nomePasta = nome))
    }
    fun salvarArquivo(arq: ArquivoNuvem) = viewModelScope.launch { dao.salvarArquivo(arq) }
    fun apagar(arq: Int) = viewModelScope.launch { dao.apagarArquivo(arq) }
    
    // Função simulada para renomear no banco local
    fun renomearArquivo(arq: ArquivoNuvem, novoNome: String) = viewModelScope.launch {
        dao.salvarArquivo(arq.copy(nomeArquivo = novoNome))
    }
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

    var loginStep by remember { mutableIntStateOf(0) }
    var phoneNumber by remember { mutableStateOf("+55") }
    var smsCode by remember { mutableStateOf("") }
    
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedFile by remember { mutableStateOf<ArquivoNuvem?>(null) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    var showStorage by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var nomeNovaPasta by remember { mutableStateOf("") }
    
    var showRenameDialog by remember { mutableStateOf(false) }
    var novoNomeArquivo by remember { mutableStateOf("") }
    
    var isUploading by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val (nome, tamanho) = obterDetalhesFicheiro(context, it)
            scope.launch {
                isUploading = true
                delay(1500) // Simula o tempo de upload visual
                vm.salvarArquivo(ArquivoNuvem(fileId = 0, nomeArquivo = nome, tamanhoBytes = tamanho, dataUpload = System.currentTimeMillis(), caminhoMiniatura = it.toString()))
                isUploading = false
            }
        }
    }

    if (loginStep < 2) {
        Column(
            modifier = Modifier.fillMaxSize().background(DarkBlue).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Security, null, tint = Gold, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("FJU MACHINE", color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("NEXUS CLOUD ADMIN", color = Color.White, fontSize = 14.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(48.dp))

            if (loginStep == 0) {
                OutlinedTextField(
                    value = phoneNumber, onValueChange = { phoneNumber = it },
                    label = { Text("Número de Telemóvel", color = Color.Gray) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { client.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber)); loginStep = 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("ENVIAR CÓDIGO SMS", fontWeight = FontWeight.Bold, color = Color.White) }
            } else {
                OutlinedTextField(
                    value = smsCode, onValueChange = { smsCode = it },
                    label = { Text("Código de Autenticação", color = Color.Gray) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Gold, unfocusedBorderColor = Color.Gray),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { client.send(TdApi.CheckAuthenticationCode(smsCode)); loginStep = 2 },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("CONFIRMAR E ENTRAR", fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = SurfaceDark, modifier = Modifier.width(300.dp)) {
                Box(modifier = Modifier.fillMaxWidth().background(DarkBlue).padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(SurfaceDark), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, null, tint = Gold, modifier = Modifier.size(48.dp))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Administrador FJU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(phoneNumber, color = Color.Gray, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.PieChart, null) }, label = { Text("Armazenamento") }, selected = false,
                    onClick = { showStorage = true; scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Gold)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Definições de Nuvem") }, selected = false,
                    onClick = { scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.White, unselectedIconColor = Color.Gray)
                )
                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.ExitToApp, null) }, label = { Text("Terminar Sessão") }, selected = false,
                    onClick = { loginStep = 0; scope.launch { drawerState.close() } },
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = DangerRed, unselectedIconColor = DangerRed)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column(Modifier.background(DarkBlue)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.White) }
                        Text("NEXUS CLOUD", color = Gold, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        if (isUploading) {
                            CircularProgressIndicator(color = Gold, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    TabRow(
                        selectedTabIndex = selectedTab, containerColor = DarkBlue,
                        indicator = { TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(it[selectedTab]), color = Gold) }
                    ) {
                        listOf("GALERIA", "PASTAS").forEachIndexed { i, title ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i }) {
                                Text(title, color = if (selectedTab == i) Gold else Color.Gray, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { filePicker.launch(arrayOf("*/*")) }, containerColor = DangerRed, contentColor = Color.White) {
                    Icon(Icons.Default.Add, null)
                }
            },
            containerColor = DarkBlue
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (selectedTab == 0) {
                    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(4.dp)) {
                        items(arquivos) { arq ->
                            Box(
                                modifier = Modifier.aspectRatio(1f).padding(4.dp).background(SurfaceDark, RoundedCornerShape(8.dp)).clickable { selectedFile = arq },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                                    Icon(Icons.Default.InsertDriveFile, null, tint = Gold, modifier = Modifier.size(36.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(text = arq.nomeArquivo, color = Color.White, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedButton(
                            onClick = { showNewFolder = true }, border = BorderStroke(1.dp, Gold), modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("CRIAR NOVA PASTA", color = Gold, fontWeight = FontWeight.Bold) }
                        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.padding(top = 16.dp)) {
                            items(pastas) { pasta ->
                                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.padding(4.dp)) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, null, tint = Gold)
                                        Spacer(Modifier.width(8.dp))
                                        Text(pasta.nomePasta, color = Color.White, fontWeight = FontWeight.Bold)
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
                        Text(selectedFile!!.nomeArquivo, color = Gold, fontWeight = FontWeight.Black, modifier = Modifier.padding(16.dp), fontSize = 18.sp)
                        Divider(color = Color.DarkGray)
                        
                        ListItem(
                            headlineContent = { Text("Descarregar Ficheiro", color = Color.White) }, leadingContent = { Icon(Icons.Default.Download, null, tint = Color.White) },
                            modifier = Modifier.clickable { /* Futura ação de download real */ selectedFile = null },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Partilhar", color = Color.White) }, leadingContent = { Icon(Icons.Default.Share, null, tint = Color.White) },
                            modifier = Modifier.clickable { /* Futura ação de partilha */ selectedFile = null },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Renomear", color = Color.White) }, leadingContent = { Icon(Icons.Default.Edit, null, tint = Color.White) },
                            modifier = Modifier.clickable { 
                                novoNomeArquivo = selectedFile!!.nomeArquivo
                                showRenameDialog = true 
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Apagar da Nuvem", color = DangerRed) }, leadingContent = { Icon(Icons.Default.Delete, null, tint = DangerRed) },
                            modifier = Modifier.clickable { vm.apagar(selectedFile!!.id); selectedFile = null },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
            if (showRenameDialog && selectedFile != null) {
                AlertDialog(
                    onDismissRequest = { showRenameDialog = false }, containerColor = SurfaceDark,
                    title = { Text("Renomear", color = Gold, fontWeight = FontWeight.Bold) },
                    text = { OutlinedTextField(value = novoNomeArquivo, onValueChange = { novoNomeArquivo = it }, label = { Text("Novo nome", color = Color.Gray) }) },
                    confirmButton = { 
                        Button(
                            onClick = { vm.renomearArquivo(selectedFile!!, novoNomeArquivo); showRenameDialog = false; selectedFile = null }, 
                            colors = ButtonDefaults.buttonColors(containerColor = Gold)
                        ) { Text("Salvar", color = DarkBlue, fontWeight = FontWeight.Bold) } 
                    }
                )
            }
            if (showNewFolder) {
                AlertDialog(
                    onDismissRequest = { showNewFolder = false }, containerColor = SurfaceDark,
                    title = { Text("Nova Pasta", color = Gold, fontWeight = FontWeight.Bold) },
                    text = { OutlinedTextField(value = nomeNovaPasta, onValueChange = { nomeNovaPasta = it }, label = { Text("Nome da pasta", color = Color.Gray) }) },
                    confirmButton = { Button(onClick = { vm.criarPasta(nomeNovaPasta); nomeNovaPasta = ""; showNewFolder = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed)) { Text("Criar", color = Color.White) } }
                )
            }
            if (showStorage) {
                AlertDialog(
                    onDismissRequest = { showStorage = false }, containerColor = SurfaceDark,
                    title = { Text("Armazenamento", color = Gold, fontWeight = FontWeight.Bold) },
                    text = { Text("Uso Local: ${(armazenamento ?: 0) / (1024 * 1024)} MB\n\nNuvem: ILIMITADO", color = Color.White) },
                    confirmButton = { Button(onClick = { showStorage = false }, colors = ButtonDefaults.buttonColors(containerColor = DangerRed)) { Text("Fechar", color = Color.White) } }
                )
            }
        }
    }
}
