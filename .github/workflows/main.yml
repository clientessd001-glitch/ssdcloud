package com.seu.nexuscloud

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// MODELOS DE DADOS E ESTRUTURAS DO SISTEMA NEXUS CLOUD v1.5 (SSD SOLUÇÕES)
// ============================================================================

enum class FileCategory { PHOTO, VIDEO, DOCUMENT, OTHER }

data class CloudFileItem(
    val messageId: Long,
    val fileId: Int,
    val name: String,
    val size: Long,
    val dateTimestamp: Long,
    val category: FileCategory,
    val folderName: String,
    val localPath: String,
    val isDownloaded: Boolean,
    val isDownloading: Boolean = false
)

data class VaultProfile(
    val id: String,
    val name: String,
    val pin: String
)

// Formatador de Data no padrão "9 de nov. de 2026"
fun formatarDataCabecalho(timestamp: Long): String {
    val millis = if (timestamp < 10000000000L) timestamp * 1000L else timestamp
    val localeBr = Locale("pt", "BR")
    val sdf = SimpleDateFormat("d 'de' MMM. 'de' yyyy", localeBr)
    return sdf.format(Date(millis)).lowercase(localeBr)
}

fun formatarTamanho(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// ============================================================================
// COMPONENTE PRINCIPAL: NEXUS UI v1.5
// Aceita até 3 argumentos opcionais para compatibilidade total com NexusApp.kt
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NexusUI(
    arg0: Any? = null,
    arg1: Any? = null,
    arg2: Any? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ssd_nexus_prefs_v15", Context.MODE_PRIVATE) }

    // Cores Tema SSD Soluções
    val bgDark = Color(0xFF0D1117)
    val cardDark = Color(0xFF161B22)
    val accentCyan = Color(0xFF00E5FF)
    val textPrimary = Color(0xFFF0F6FC)
    val textSecondary = Color(0xFF8B949E)

    // Estados do Cofre Multiutilizador (PIN)
    var activeVault by remember { mutableStateOf<VaultProfile?>(null) }
    var vaultNameInput by remember { mutableStateOf("") }
    var vaultPinInput by remember { mutableStateOf("") }
    var vaultError by remember { mutableStateOf("") }

    // Estados de Autenticação Nuvem SSD (Motor TDLib)
    var client by remember { mutableStateOf<Client?>(arg0 as? Client) }
    var authState by remember { mutableStateOf("AGUARDANDO_COFRE") }
    var phoneInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Conectando ao servidor SSD Cloud...") }
    var savedMessagesChatId by remember { mutableStateOf(0L) }

    // Estados de Arquivos, Pastas e Sincronização
    val cloudFiles = remember { mutableStateListOf<CloudFileItem>() }
    val customFolders = remember { mutableStateListOf("Geral", "Fotos", "Vídeos", "Documentos") }
    var selectedFolder by remember { mutableStateOf("Todas") }
    var currentTab by remember { mutableStateOf(0) } // 0: Galeria, 1: Pastas, 2: Auto-Sync, 3: Sobre
    var searchQuery by remember { mutableStateOf("") }

    // Estados de Seleção Múltipla em Lote
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode by remember { derivedStateOf { selectedIds.isNotEmpty() } }

    // Estados de Diálogos de Gestão de Pastas
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showRenameFolderDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteFolderDialog by remember { mutableStateOf<String?>(null) }
    var folderInputText by remember { mutableStateOf("") }

    // Estado do Visualizador Interno em Tela Cheia (Foto com Zoom, Vídeo, PDF/Doc)
    var viewingFile by remember { mutableStateOf<CloudFileItem?>(null) }

    // Estados do Detector Automático de Novos Arquivos (Auto-Sync)
    var autoSyncEnabled by remember { mutableStateOf(false) }
    var syncLocalDirPath by remember { mutableStateOf("") }
    var lastSyncStatus by remember { mutableStateOf("Monitoramento ativo em segundo plano") }

    // Carregar pastas e configurações do Cofre ativo
    fun carregarDadosCofre(vault: VaultProfile) {
        val savedFolders = prefs.getStringSet("folders_${vault.id}", setOf("Geral", "Fotos", "Vídeos", "Documentos")) ?: emptySet()
        customFolders.clear()
        customFolders.addAll(savedFolders.sorted())
        if (!customFolders.contains("Geral")) customFolders.add(0, "Geral")

        autoSyncEnabled = prefs.getBoolean("autosync_${vault.id}", false)
        syncLocalDirPath = prefs.getString(
            "syncdir_${vault.id}",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath
        ) ?: ""
    }

    fun salvarPastasCofre() {
        val vault = activeVault ?: return
        prefs.edit().putStringSet("folders_${vault.id}", customFolders.toSet()).apply()
    }

    // Parse de mensagem TDLib para CloudFileItem com TAG de cofre e pasta
    fun processarMensagemNuvem(msg: TdApi.Message) {
        val vault = activeVault ?: return
        val vaultTag = "[VAULT:${vault.id}]"

        var caption = ""
        var fileObj: TdApi.File? = null
        var fileName = "Arquivo_${msg.id}"
        var category = FileCategory.OTHER

        when (val content = msg.content) {
            is TdApi.MessagePhoto -> {
                caption = content.caption?.text ?: ""
                val bestPhoto = content.photo.sizes.maxByOrNull { it.width * it.height }
                fileObj = bestPhoto?.photo
                fileName = "IMG_${msg.id}.jpg"
                category = FileCategory.PHOTO
            }
            is TdApi.MessageVideo -> {
                caption = content.caption?.text ?: ""
                fileObj = content.video.video
                fileName = content.video.fileName.ifEmpty { "VID_${msg.id}.mp4" }
                category = FileCategory.VIDEO
            }
            is TdApi.MessageDocument -> {
                caption = content.caption?.text ?: ""
                fileObj = content.document.document
                fileName = content.document.fileName.ifEmpty { "DOC_${msg.id}" }
                val lower = fileName.lowercase()
                category = when {
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") -> FileCategory.PHOTO
                    lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") -> FileCategory.VIDEO
                    else -> FileCategory.DOCUMENT
                }
            }
        }

        if (caption.contains("[VAULT:") && !caption.contains(vaultTag)) return

        val folderRegex = Regex("\\[FOLDER:(.*?)]")
        val matchedFolder = folderRegex.find(caption)?.groupValues?.get(1) ?: "Geral"
        if (!customFolders.contains(matchedFolder)) {
            customFolders.add(matchedFolder)
        }

        val f = fileObj ?: return
        val localPath = f.local?.path ?: ""
        val isDownloaded = f.local?.isDownloadingCompleted == true && localPath.isNotEmpty() && File(localPath).exists()

        val item = CloudFileItem(
            messageId = msg.id,
            fileId = f.id,
            name = fileName,
            size = f.expectedSize.takeIf { it > 0 } ?: f.size,
            dateTimestamp = msg.date.toLong(),
            category = category,
            folderName = matchedFolder,
            localPath = localPath,
            isDownloaded = isDownloaded,
            isDownloading = f.local?.isDownloadingActive == true
        )

        scope.launch(Dispatchers.Main) {
            val idx = cloudFiles.indexOfFirst { it.messageId == item.messageId }
            if (idx >= 0) {
                cloudFiles[idx] = item
            } else {
                cloudFiles.add(item)
            }
            if (!isDownloaded && f.local?.isDownloadingActive == false) {
                client?.send(TdApi.DownloadFile(f.id, 1, 0, 0, false)) {}
            }
        }
    }

    // Carregar histórico de arquivos da Nuvem SSD
    fun sincronizarHistoricoNuvem() {
        val c = client ?: return
        c.send(TdApi.GetMe()) { res ->
            if (res is TdApi.User) {
                val myUserId = res.id
                c.send(TdApi.CreatePrivateChat(myUserId, false)) { chatRes ->
                    if (chatRes is TdApi.Chat) {
                        savedMessagesChatId = chatRes.id
                        c.send(TdApi.GetChatHistory(chatRes.id, 0, 0, 100, false)) { hist ->
                            if (hist is TdApi.Messages) {
                                hist.messages.forEach { msg -> processarMensagemNuvem(msg) }
                            }
                        }
                    }
                }
            }
        }
    }

    // Inicializar Motor TDLib isolado por Cofre
    fun iniciarMotorNuvem(vault: VaultProfile) {
        authState = "CONECTANDO"
        val dbDir = File(context.filesDir, "ssd_tdlib_${vault.id}").absolutePath
        val filesDir = File(context.getExternalFilesDir(null), "SSD_Cloud_${vault.id}").apply { mkdirs() }.absolutePath

        val novoClient = Client.create({ update ->
            when (update) {
                is TdApi.UpdateAuthorizationState -> {
                    when (update.authorizationState) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> {
                            val params = TdApi.SetTdlibParameters().apply {
                                databaseDirectory = dbDir
                                filesDirectory = filesDir
                                useMessageDatabase = true
                                useSecretChats = false
                                apiId = 94575
                                apiHash = "a3406de8d171bb422bb6ddf3bbd800e2"
                                systemLanguageCode = "pt"
                                deviceModel = Build.MODEL
                                applicationVersion = "1.5"
                            }
                            client?.send(params) {}
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> {
                            authState = "WAIT_PHONE"
                            statusMessage = "Informe seu número para ativar a Nuvem SSD"
                        }
                        is TdApi.AuthorizationStateWaitCode -> {
                            authState = "WAIT_CODE"
                            statusMessage = "Digite o código de verificação recebido"
                        }
                        is TdApi.AuthorizationStateWaitPassword -> {
                            authState = "WAIT_PASSWORD"
                            statusMessage = "Informe sua senha de verificação em duas etapas"
                        }
                        is TdApi.AuthorizationStateReady -> {
                            authState = "READY"
                            statusMessage = "SSD Cloud Conectada"
                            sincronizarHistoricoNuvem()
                        }
                    }
                }
                is TdApi.UpdateNewMessage -> {
                    if (update.message.chatId == savedMessagesChatId) {
                        processarMensagemNuvem(update.message)
                    }
                }
                is TdApi.UpdateFile -> {
                    val f = update.file
                    if (f.local?.isDownloadingCompleted == true) {
                        scope.launch(Dispatchers.Main) {
                            val idx = cloudFiles.indexOfFirst { it.fileId == f.id }
                            if (idx >= 0) {
                                val atual = cloudFiles[idx]
                                cloudFiles[idx] = atual.copy(
                                    localPath = f.local.path,
                                    isDownloaded = true,
                                    isDownloading = false
                                )
                                if (viewingFile?.fileId == f.id) {
                                    viewingFile = cloudFiles[idx]
                                }
                            }
                        }
                    }
                }
            }
        }, null, null)

        client = novoClient
    }

    // Função de Upload de Arquivo para a Nuvem SSD (Compatível com qualquer versão da TDLib)
    fun enviarArquivoParaNuvem(file: File, targetFolder: String) {
        val c = client ?: return
        val vault = activeVault ?: return
        if (savedMessagesChatId == 0L || !file.exists()) return

        val captionText = "[VAULT:${vault.id}] [FOLDER:$targetFolder] ${file.name}"
        val formattedCaption = TdApi.FormattedText(captionText, emptyArray())
        val lower = file.name.lowercase()

        val inputContent: TdApi.InputMessageContent = when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> {
                TdApi.InputMessagePhoto().apply {
                    photo = TdApi.InputFileLocal(file.absolutePath)
                    caption = formattedCaption
                }
            }
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") -> {
                TdApi.InputMessageVideo().apply {
                    video = TdApi.InputFileLocal(file.absolutePath)
                    supportsStreaming = true
                    caption = formattedCaption
                }
            }
            else -> {
                TdApi.InputMessageDocument().apply {
                    document = TdApi.InputFileLocal(file.absolutePath)
                    caption = formattedCaption
                }
            }
        }

        val sendReq = TdApi.SendMessage().apply {
            chatId = savedMessagesChatId
            inputMessageContent = inputContent
        }

        c.send(sendReq) { res ->
            if (res is TdApi.Message) {
                processarMensagemNuvem(res)
            }
        }
    }

    // Detector Automático de Novos Arquivos (Auto-Sync em Background)
    LaunchedEffect(autoSyncEnabled, authState, syncLocalDirPath) {
        if (autoSyncEnabled && authState == "READY" && syncLocalDirPath.isNotEmpty()) {
            val vault = activeVault ?: return@LaunchedEffect
            while (autoSyncEnabled) {
                withContext(Dispatchers.IO) {
                    val dir = File(syncLocalDirPath)
                    if (dir.exists() && dir.isDirectory) {
                        val syncedSet = prefs.getStringSet("synced_files_${vault.id}", emptySet())?.toMutableSet() ?: mutableSetOf()
                        val novosArquivos = dir.walkTopDown()
                            .filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 }
                            .take(25)
                            .toList()

                        var enviadosAgora = 0
                        for (arquivo in novosArquivos) {
                            val uniqueKey = "${arquivo.absolutePath}_${arquivo.lastModified()}"
                            if (!syncedSet.contains(uniqueKey)) {
                                enviarArquivoParaNuvem(arquivo, if (selectedFolder == "Todas") "Geral" else selectedFolder)
                                syncedSet.add(uniqueKey)
                                enviadosAgora++
                            }
                        }
                        if (enviadosAgora > 0) {
                            prefs.edit().putStringSet("synced_files_${vault.id}", syncedSet).apply()
                            withContext(Dispatchers.Main) {
                                lastSyncStatus = "Sincronizados $enviadosAgora novos arquivos automaticamente"
                            }
                        }
                    }
                }
                delay(15000L)
            }
        }
    }

    // Seletor de Upload Manual de Múltiplos Arquivos
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val pastaDestino = if (selectedFolder == "Todas") "Geral" else selectedFolder
            scope.launch(Dispatchers.IO) {
                uris.forEach { uri ->
                    val tempFile = copiarUriParaArquivoLocal(context, uri)
                    if (tempFile != null) {
                        enviarArquivoParaNuvem(tempFile, pastaDestino)
                    }
                }
            }
            Toast.makeText(context, "Enviando ${uris.size} arquivo(s) para $pastaDestino...", Toast.LENGTH_SHORT).show()
        }
    }

    // Exclusão em Lote (Local + Nuvem SSD)
    fun excluirSelecionadosEmLote() {
        val c = client ?: return
        val idsParaExcluir = selectedIds.toLongArray()
        if (idsParaExcluir.isEmpty()) return

        c.send(TdApi.DeleteMessages(savedMessagesChatId, idsParaExcluir, true)) {
            scope.launch(Dispatchers.Main) {
                cloudFiles.removeAll { idsParaExcluir.contains(it.messageId) }
                selectedIds.clear()
                Toast.makeText(context, "Arquivos excluídos da Nuvem SSD!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Compartilhamento em Lote
    fun compartilharSelecionadosEmLote() {
        val arquivosParaCompartilhar = cloudFiles.filter { selectedIds.contains(it.messageId) && it.isDownloaded }
        if (arquivosParaCompartilhar.isEmpty()) {
            Toast.makeText(context, "Aguarde o download dos arquivos selecionados para compartilhar.", Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList<Uri>()
        arquivosParaCompartilhar.forEach { item ->
            val file = File(item.localPath)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                uris.add(uri)
            }
        }

        if (uris.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartilhar via SSD Cloud"))
            selectedIds.clear()
        }
    }

    // ========================================================================
    // TELA 1: ACESSO AO COFRE MULTIUTILIZADOR (PIN)
    // ========================================================================
    if (activeVault == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgDark)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = accentCyan,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "NEXUS CLOUD v1.5",
                        color = textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "SSD Soluções • Cofre Inteligente",
                        color = accentCyan,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = vaultNameInput,
                        onValueChange = { vaultNameInput = it },
                        label = { Text("Nome do Usuário / Cofre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentCyan,
                            focusedLabelColor = accentCyan,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = vaultPinInput,
                        onValueChange = { if (it.length <= 8) vaultPinInput = it },
                        label = { Text("PIN de Segurança (4 a 8 dígitos)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentCyan,
                            focusedLabelColor = accentCyan,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        )
                    )

                    if (vaultError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = vaultError, color = Color(0xFFFF5252), fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val cleanName = vaultNameInput.trim()
                            val cleanPin = vaultPinInput.trim()
                            if (cleanName.isEmpty() || cleanPin.length < 4) {
                                vaultError = "Preencha o nome e um PIN de pelo menos 4 dígitos."
                            } else {
                                val vaultId = cleanName.lowercase().replace(" ", "_")
                                val savedPin = prefs.getString("pin_$vaultId", null)
                                if (savedPin == null) {
                                    prefs.edit().putString("pin_$vaultId", cleanPin).apply()
                                    val novo = VaultProfile(vaultId, cleanName, cleanPin)
                                    activeVault = novo
                                    carregarDadosCofre(novo)
                                    iniciarMotorNuvem(novo)
                                } else if (savedPin == cleanPin) {
                                    val existente = VaultProfile(vaultId, cleanName, cleanPin)
                                    activeVault = existente
                                    carregarDadosCofre(existente)
                                    iniciarMotorNuvem(existente)
                                } else {
                                    vaultError = "PIN incorreto para o cofre '$cleanName'."
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentCyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("ACESSAR COFRE SSD", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    // ========================================================================
    // TELA 2: AUTENTICAÇÃO DO MOTOR DE NUVEM (CASO AINDA NÃO CONECTADO)
    // ========================================================================
    if (authState != "READY") {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgDark)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = accentCyan,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Ativação SSD Cloud",
                        color = textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusMessage,
                        color = textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    when (authState) {
                        "WAIT_PHONE" -> {
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { phoneInput = it },
                                label = { Text("Telefone com DDI (Ex: +55859...)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentCyan,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    client?.send(TdApi.SetAuthenticationPhoneNumber(phoneInput.trim(), null)) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Receber Código", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        "WAIT_CODE" -> {
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { codeInput = it },
                                label = { Text("Código de Verificação") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentCyan,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    client?.send(TdApi.CheckAuthenticationCode(codeInput.trim())) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Confirmar Código", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        "WAIT_PASSWORD" -> {
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Senha de 2 Etapas") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentCyan,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    client?.send(TdApi.CheckAuthenticationPassword(passwordInput)) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentCyan),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Desbloquear Nuvem", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> {
                            CircularProgressIndicator(color = accentCyan)
                        }
                    }
                }
            }
        }
        return
    }

    // ========================================================================
    // VISUALIZADOR INTERNO EM TELA CHEIA (ZOOM, VÍDEO, PDF E DOCUMENTOS)
    // ========================================================================
    viewingFile?.let { fileItem ->
        VisualizadorInternoTelaCheia(
            item = fileItem,
            onClose = { viewingFile = null },
            onDelete = {
                client?.send(TdApi.DeleteMessages(savedMessagesChatId, longArrayOf(fileItem.messageId), true)) {
                    scope.launch(Dispatchers.Main) {
                        cloudFiles.removeAll { it.messageId == fileItem.messageId }
                        viewingFile = null
                    }
                }
            },
            onShare = {
                if (fileItem.isDownloaded) {
                    val f = File(fileItem.localPath)
                    if (f.exists()) {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Compartilhar ${fileItem.name}"))
                    }
                }
            }
        )
        return
    }

    // ========================================================================
    // TELA PRINCIPAL: GALERIA AGRUPADA POR DATA, PASTAS, AUTO-SYNC E SOBRE
    // ========================================================================
    Scaffold(
        containerColor = bgDark,
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedIds.size} selecionado(s)",
                            color = textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = textPrimary)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val todosIds = cloudFiles.map { it.messageId }
                            selectedIds.clear()
                            selectedIds.addAll(todosIds)
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Selecionar Tudo", tint = accentCyan)
                        }
                        IconButton(onClick = { compartilharSelecionadosEmLote() }) {
                            Icon(Icons.Default.Share, contentDescription = "Compartilhar", tint = accentCyan)
                        }
                        IconButton(onClick = { excluirSelecionadosEmLote() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Color(0xFFFF5252))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1F2937))
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Nexus Cloud v1.5",
                                color = textPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Cofre: ${activeVault?.name} • SSD Soluções",
                                color = accentCyan,
                                fontSize = 12.sp
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { sincronizarHistoricoNuvem() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = textPrimary)
                        }
                        IconButton(onClick = {
                            // Encerra a instância TDLib de forma segura
                            client?.send(TdApi.Close()) {}
                            client = null
                            cloudFiles.clear()
                            selectedIds.clear()
                            activeVault = null
                        }) {
                            Icon(Icons.Default.Lock, contentDescription = "Trocar Cofre", tint = accentCyan)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cardDark)
                )
            }
        },
        floatingActionButton = {
            if (currentTab == 0 || currentTab == 1) {
                FloatingActionButton(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    containerColor = accentCyan,
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Enviar Arquivos")
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = cardDark) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("Galeria") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentCyan, selectedTextColor = accentCyan)
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Pastas") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentCyan, selectedTextColor = accentCyan)
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    label = { Text("Auto-Sync") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentCyan, selectedTextColor = accentCyan)
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("Sobre") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentCyan, selectedTextColor = accentCyan)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                // ============================================================
                // ABA 0: GALERIA ULTRA FLUIDA COM DATAS AGRUPADAS NO TOPO
                // ============================================================
                0 -> {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Pesquisar fotos, vídeos ou documentos...", color = textSecondary) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = accentCyan) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = null, tint = textSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentCyan,
                            unfocusedBorderColor = Color(0xFF30363D),
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val todasOpcoes = listOf("Todas") + customFolders
                        todasOpcoes.take(5).forEach { folder ->
                            FilterChip(
                                selected = selectedFolder == folder,
                                onClick = { selectedFolder = folder },
                                label = { Text(folder, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accentCyan,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    // Agrupamento com remember() para rolagem rápida sem travamentos
                    val arquivosAgrupadosPorData = remember(cloudFiles.toList(), selectedFolder, searchQuery) {
                        cloudFiles
                            .filter { item ->
                                (selectedFolder == "Todas" || item.folderName == selectedFolder) &&
                                        (searchQuery.isEmpty() || item.name.contains(searchQuery, ignoreCase = true))
                            }
                            .sortedByDescending { it.dateTimestamp }
                            .groupBy { formatarDataCabecalho(it.dateTimestamp) }
                    }

                    if (arquivosAgrupadosPorData.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Nenhum arquivo encontrado nesta pasta.",
                                color = textSecondary,
                                fontSize = 15.sp
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            arquivosAgrupadosPorData.forEach { (dataCabecalho, arquivosDoDia) ->

                                // CABEÇALHO DA DATA ("9 de nov. de 2026")
                                item(
                                    key = "header_$dataCabecalho",
                                    span = { GridItemSpan(maxLineSpan) },
                                    contentType = "cabecalho_data"
                                ) {
                                    Text(
                                        text = dataCabecalho,
                                        color = textPrimary,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 10.dp, top = 16.dp, bottom = 8.dp)
                                    )
                                }

                                // MINIATURAS DO DIA (Com chave única e downsampling 280x280 RGB_565)
                                items(
                                    items = arquivosDoDia,
                                    key = { it.messageId },
                                    contentType = { "midia_grid_item" }
                                ) { item ->
                                    val isSelected = selectedIds.contains(item.messageId)

                                    MiniaturaOtimizadaGrid(
                                        item = item,
                                        isSelected = isSelected,
                                        accentCyan = accentCyan,
                                        onClick = {
                                            if (isSelectionMode) {
                                                if (isSelected) selectedIds.remove(item.messageId)
                                                else selectedIds.add(item.messageId)
                                            } else {
                                                if (!item.isDownloaded) {
                                                    client?.send(TdApi.DownloadFile(item.fileId, 32, 0, 0, false)) {}
                                                    Toast.makeText(context, "Baixando arquivo original...", Toast.LENGTH_SHORT).show()
                                                }
                                                viewingFile = item
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelected) selectedIds.add(item.messageId)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ============================================================
                // ABA 1: GESTÃO COMPLETA DE PASTAS (CRIAR, RENOMEAR, APAGAR)
                // ============================================================
                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Suas Pastas na Nuvem",
                                color = textPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Button(
                                onClick = {
                                    folderInputText = ""
                                    showCreateFolderDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentCyan)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Nova Pasta", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(customFolders) { folder ->
                                val qtdArquivos = cloudFiles.count { it.folderName == folder }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = cardDark),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedFolder = folder
                                            currentTab = 0
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = accentCyan,
                                                modifier = Modifier.size(36.dp)
                                            )
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column {
                                                Text(
                                                    text = folder,
                                                    color = textPrimary,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "$qtdArquivos arquivo(s) armazenado(s)",
                                                    color = textSecondary,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        }

                                        if (folder != "Geral") {
                                            Row {
                                                IconButton(onClick = {
                                                    folderInputText = folder
                                                    showRenameFolderDialog = folder
                                                }) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Renomear", tint = accentCyan)
                                                }
                                                IconButton(onClick = {
                                                    showDeleteFolderDialog = folder
                                                }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Apagar", tint = Color(0xFFFF5252))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ============================================================
                // ABA 2: DETECTOR AUTOMÁTICO E SINCRONIZAÇÃO DE PASTAS LOCAIS
                // ============================================================
                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Detector Automático de Arquivos",
                                            color = textPrimary,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Detecta novas fotos e documentos na pasta local e envia sozinho para a Nuvem SSD.",
                                            color = textSecondary,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Switch(
                                        checked = autoSyncEnabled,
                                        onCheckedChange = { checked ->
                                            autoSyncEnabled = checked
                                            activeVault?.let {
                                                prefs.edit().putBoolean("autosync_${it.id}", checked).apply()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = accentCyan)
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                OutlinedTextField(
                                    value = syncLocalDirPath,
                                    onValueChange = {
                                        syncLocalDirPath = it
                                        activeVault?.let { v ->
                                            prefs.edit().putString("syncdir_${v.id}", it).apply()
                                        }
                                    },
                                    label = { Text("Caminho da Pasta Monitorada no Aparelho") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentCyan,
                                        focusedTextColor = textPrimary,
                                        unfocusedTextColor = textPrimary
                                    )
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Status: $lastSyncStatus",
                                    color = accentCyan,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // ============================================================
                // ABA 3: SOBRE (SSD SOLUÇÕES / JOSIMAR SOUSA)
                // ============================================================
                3 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardDark),
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF002B36))
                                        .border(2.dp, accentCyan, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = null,
                                        tint = accentCyan,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "NEXUS CLOUD v1.5",
                                    color = textPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "SSD SOLUÇÕES • ARMAZENAMENTO ILIMITADO",
                                    color = accentCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(20.dp))
                                HorizontalDivider(color = Color(0xFF30363D))
                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Desenvolvido por:",
                                    color = textSecondary,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Josimar Sousa",
                                    color = textPrimary,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "CEO & Desenvolvedor • SSD Soluções",
                                    color = accentCyan,
                                    fontSize = 13.sp
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("• Cofre Multiutilizador Isolado por PIN", color = textSecondary, fontSize = 13.sp)
                                    Text("• Galeria Ultra Rápida Agrupada por Datas", color = textSecondary, fontSize = 13.sp)
                                    Text("• Gestão Completa de Pastas e Lotes", color = textSecondary, fontSize = 13.sp)
                                    Text("• Detector Automático de Novos Arquivos", color = textSecondary, fontSize = 13.sp)
                                    Text("• Visualizador Interno de Fotos, Vídeos e PDF", color = textSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // DIÁLOGOS DE CRIAÇÃO, RENOMEAÇÃO E EXCLUSÃO DE PASTAS
    // ========================================================================
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = cardDark,
            title = { Text("Criar Nova Pasta", color = textPrimary) },
            text = {
                OutlinedTextField(
                    value = folderInputText,
                    onValueChange = { folderInputText = it },
                    label = { Text("Nome da Pasta") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentCyan,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nome = folderInputText.trim()
                    if (nome.isNotEmpty() && !customFolders.contains(nome)) {
                        customFolders.add(nome)
                        salvarPastasCofre()
                    }
                    showCreateFolderDialog = false
                }) {
                    Text("Criar", color = accentCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancelar", color = textSecondary)
                }
            }
        )
    }

    showRenameFolderDialog?.let { pastaAntiga ->
        AlertDialog(
            onDismissRequest = { showRenameFolderDialog = null },
            containerColor = cardDark,
            title = { Text("Renomear Pasta", color = textPrimary) },
            text = {
                OutlinedTextField(
                    value = folderInputText,
                    onValueChange = { folderInputText = it },
                    label = { Text("Novo nome para '$pastaAntiga'") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentCyan,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val novoNome = folderInputText.trim()
                    if (novoNome.isNotEmpty() && novoNome != pastaAntiga) {
                        val idx = customFolders.indexOf(pastaAntiga)
                        if (idx >= 0) customFolders[idx] = novoNome
                        salvarPastasCofre()

                        for (i in cloudFiles.indices) {
                            if (cloudFiles[i].folderName == pastaAntiga) {
                                cloudFiles[i] = cloudFiles[i].copy(folderName = novoNome)
                            }
                        }
                    }
                    showRenameFolderDialog = null
                }) {
                    Text("Salvar", color = accentCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFolderDialog = null }) {
                    Text("Cancelar", color = textSecondary)
                }
            }
        )
    }

    showDeleteFolderDialog?.let { pastaParaExcluir ->
        AlertDialog(
            onDismissRequest = { showDeleteFolderDialog = null },
            containerColor = cardDark,
            title = { Text("Excluir Pasta '$pastaParaExcluir'?", color = textPrimary) },
            text = {
                Text(
                    "Você deseja mover os arquivos desta pasta para 'Geral' ou apagar todos os arquivos dela também da nuvem?",
                    color = textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    for (i in cloudFiles.indices) {
                        if (cloudFiles[i].folderName == pastaParaExcluir) {
                            cloudFiles[i] = cloudFiles[i].copy(folderName = "Geral")
                        }
                    }
                    customFolders.remove(pastaParaExcluir)
                    salvarPastasCofre()
                    if (selectedFolder == pastaParaExcluir) selectedFolder = "Todas"
                    showDeleteFolderDialog = null
                }) {
                    Text("Mover p/ Geral", color = accentCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val ids = cloudFiles.filter { it.folderName == pastaParaExcluir }.map { it.messageId }.toLongArray()
                    if (ids.isNotEmpty()) {
                        client?.send(TdApi.DeleteMessages(savedMessagesChatId, ids, true)) {}
                    }
                    cloudFiles.removeAll { it.folderName == pastaParaExcluir }
                    customFolders.remove(pastaParaExcluir)
                    salvarPastasCofre()
                    if (selectedFolder == pastaParaExcluir) selectedFolder = "Todas"
                    showDeleteFolderDialog = null
                }) {
                    Text("Apagar Tudo", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// ============================================================================
// MINIATURA OTIMIZADA DO GRID (CACHE + DOWNSAMPLING 280x280 RGB_565)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniaturaOtimizadaGrid(
    item: CloudFileItem,
    isSelected: Boolean,
    accentCyan: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current

    val imageRequest = remember(item.localPath, item.isDownloaded) {
        if (item.isDownloaded && item.localPath.isNotEmpty()) {
            ImageRequest.Builder(context)
                .data(File(item.localPath))
                .size(280, 280)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        } else null
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A202C))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (item.isDownloaded && imageRequest != null && (item.category == FileCategory.PHOTO || item.category == FileCategory.VIDEO)) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = when (item.category) {
                        FileCategory.PHOTO -> Icons.Default.Image
                        FileCategory.VIDEO -> Icons.Default.PlayCircleOutline
                        FileCategory.DOCUMENT -> Icons.Default.PictureAsPdf
                        FileCategory.OTHER -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = accentCyan,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (item.category == FileCategory.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x8800E5FF))
                    .border(2.dp, accentCyan, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.TopEnd
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(22.dp)
                        .background(accentCyan, CircleShape)
                )
            }
        }
    }
}

// ============================================================================
// VISUALIZADOR INTERNO EM TELA CHEIA (ZOOM INTERATIVO, VÍDEO E PDF NATIVO)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualizadorInternoTelaCheia(
    item: CloudFileItem,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    BackHandler { onClose() }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatarDataCabecalho(item.dateTimestamp)} • ${formatarTamanho(item.size)}",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Compartilhar", tint = Color(0xFF00E5FF))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Color(0xFFFF5252))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xCC000000))
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (!item.isDownloaded || item.localPath.isEmpty() || !File(item.localPath).exists()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Carregando arquivo da Nuvem SSD...", color = Color.White)
                }
            } else {
                when (item.category) {
                    FileCategory.PHOTO -> {
                        AsyncImage(
                            model = File(item.localPath),
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        if (scale > 1f) {
                                            offsetX += pan.x
                                            offsetY += pan.y
                                        } else {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    }
                                }
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        )
                    }

                    FileCategory.VIDEO -> {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(item.localPath)
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    start()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    FileCategory.DOCUMENT, FileCategory.OTHER -> {
                        if (item.name.lowercase().endsWith(".pdf")) {
                            LeitorPdfInterno(file = File(item.localPath))
                        } else {
                            val textoConteudo = remember(item.localPath) {
                                runCatching {
                                    File(item.localPath).takeIf { it.length() < 500_000 }?.readText()
                                        ?: "Documento pronto no aparelho.\nUse o botão Compartilhar no topo para abrir em outro app externo."
                                }.getOrDefault("Pré-visualização indisponível para este formato binário.")
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                item {
                                    Text(text = textoConteudo, color = Color.White, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// LEITOR DE PDF INTERNO NATIVO (RENDERIZA PÁGINAS COM ZOOM)
// ============================================================================

@Composable
fun LeitorPdfInterno(file: File) {
    val bitmaps = remember(file.absolutePath) {
        val list = mutableListOf<Bitmap>()
        runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount.coerceAtMost(30)
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                list.add(bmp)
            }
            renderer.close()
            pfd.close()
        }
        list
    }

    if (bitmaps.isEmpty()) {
        Text("Não foi possível renderizar o PDF.", color = Color.White)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            items(bitmaps) { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Página PDF",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

// ============================================================================
// UTILITÁRIO PARA COPIAR URI SELECIONADA PARA ARQUIVO FÍSICO ANTES DO UPLOAD
// ============================================================================

fun copiarUriParaArquivoLocal(context: Context, uri: Uri): File? {
    return try {
        val contentResolver = context.contentResolver
        var fileName = "SSD_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        val tempFile = File(context.cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    } catch (e: Exception) {
        null
    }
}
